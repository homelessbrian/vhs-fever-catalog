package org.vhsfever.review

import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** One box the finder bot found for a movie: a videotapes.io sleeve or an image on the Archive. */
data class ArtCandidate(
    val id: String,
    val source: String,      // "vt", "page" (the tape's own Archive page) or "search"
    val thumb: String,
    val url: String,
    val kind: String,        // "front", "wrap" (back, spine and front in one) or "unknown"
    val score: Int,
    val why: List<String>,
    val label: String,       // "videotapes.io · US · Paramount" or the Archive item's title
    val link: String?,       // where to see it on the web
    val hasBack: Boolean,
    val item: String? = null,
    val itemTitle: String? = null,
) {
    /** What's saved with a pick Brian found himself, so the box job can use it. */
    fun toJson(): JSONObject = JSONObject().put("id", id).put("source", source).put("url", url).put("thumb", thumb)
        .put("kind", kind).put("item", item).put("item_title", itemTitle)

    val sourceName get() = when (source) { "vt" -> "videotapes.io"; "page" -> "This tape's page"; "manual" -> "Your search"; else -> "Archive" }
}

data class ArtTitle(
    val key: String, val name: String, val year: String?, val tmdb: Int?,
    val candidates: List<ArtCandidate>, val botPick: String?, val sure: Boolean,
)

enum class ArtFilter(val label: String) { REVIEW("Needs a look"), BOT("Bot picked"), MINE("Your picks"), NONE("Nothing found"), ALL("All") }

/**
 * Brian's own searches of the Archive, straight from the phone: any words, or an Archive link / item id.
 * Lists the pictures on the matching pages (the Archive's own thumbnails and video frames left out).
 */
object ArchiveArt {
    private val IMAGE = Regex("\\.(jpe?g|png|webp|gif|tiff?)$", RegexOption.IGNORE_CASE)
    private val ITEM_LINK = Regex("archive\\.org/(?:details|download)/([^/?#\\s]+)")

    fun search(query: String): List<ArtCandidate> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val item = ITEM_LINK.find(q)?.groupValues?.get(1)
            ?: q.takeIf { !it.contains(' ') && it.length > 3 && it.any { c -> c == '-' || c == '_' } }
        val items: List<Pair<String, String>> = if (item != null) listOf(item to item) else {
            val url = "https://archive.org/advancedsearch.php?q=" + enc("title:($q) AND (mediatype:image OR collection:vhsvault OR collection:vhs-cover-art)") +
                "&fl[]=identifier&fl[]=title&rows=12&output=json"
            JSONObject(Http.request(url)).optJSONObject("response")?.optJSONArray("docs").objects()
                .map { it.optString("identifier") to (it.optStringOrNull("title") ?: it.optString("identifier")) }
        }
        val out = mutableListOf<ArtCandidate>()
        for ((ident, title) in items.take(8)) {
            val meta = runCatching { JSONObject(Http.request("https://archive.org/metadata/${enc(ident)}")) }.getOrNull() ?: continue
            val itemTitle = meta.optJSONObject("metadata")?.optStringOrNull("title") ?: title
            meta.optJSONArray("files").objects()
                .filter { f -> val n = f.optString("name")
                    IMAGE.containsMatchIn(n) && f.optString("source") != "derivative" && !n.contains("__ia_thumb") &&
                        f.optString("size").toLongOrNull()?.let { it > 15000 } != false }
                .sortedByDescending { it.optString("size").toLongOrNull() ?: 0 }
                .take(6)
                .forEach { f ->
                    val name = f.optString("name")
                    val url = "https://archive.org/download/${enc(ident)}/" + name.split('/').joinToString("/") { enc(it).replace("+", "%20") }
                    val w = f.optString("width").toIntOrNull(); val h = f.optString("height").toIntOrNull()
                    val kind = if (w == null || h == null) "unknown" else (w.toFloat() / h).let { r ->
                        if (r in 1.15f..2.0f) "wrap" else if (r in 0.48f..0.82f) "front" else "unknown" }
                    out += ArtCandidate("ia:" + sha1(url).take(16), "manual", url, url, kind, 0, emptyList(),
                        "$itemTitle · $name", "https://archive.org/details/$ident", false, item = ident, itemTitle = itemTitle)
                }
        }
        return out
    }

    private fun sha1(s: String) = java.security.MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

/** The private box-art repository: the finder bot's lists, and Brian's picks (choices.json). */
class BoxArtRepo(private val token: String) {
    private val api = "https://api.github.com/repos/${BuildConfig.BOXART_REPO}/contents"
    private val auth = mapOf("Authorization" to "Bearer $token", "Accept" to "application/vnd.github+json",
        "X-GitHub-Api-Version" to "2022-11-28")

    /** The file's text, or null if it isn't there yet. */
    fun read(path: String): String? = try {
        Http.request("$api/$path?ref=main", headers = auth + ("Accept" to "application/vnd.github.raw+json"), timeoutMs = 90000)
    } catch (e: HttpError) { if (e.code == 404) null else throw e }

    private fun sha(path: String): String? = try {
        JSONObject(Http.request("$api/$path?ref=main", headers = auth)).optString("sha").ifBlank { null }
    } catch (e: HttpError) { if (e.code == 404) null else throw e }

    /** Saves one pick, merged into whatever choices.json holds now (two phones never undo each other). */
    fun savePick(key: String, pick: String?, candidate: ArtCandidate? = null) {
        repeat(3) { attempt ->
            val s = sha("choices.json")
            val now = (if (s != null) read("choices.json") else null)?.let { JSONObject(it) } ?: JSONObject()
            val titles = now.optJSONObject("titles") ?: JSONObject().also { now.put("titles", it) }
            if (pick == null) titles.remove(key)
            else titles.put(key, JSONObject().put("pick", pick).put("at", Instant.now().toString())
                .apply { if (candidate != null) put("candidate", candidate.toJson()) })
            now.put("about", "Box art picked in the review app. \"none\" = show the TMDB poster.")
            val body = JSONObject().put("message", "Box art pick from phone").put("branch", "main")
                .put("content", Base64.encodeToString(now.toString(1).toByteArray(), Base64.NO_WRAP))
            if (s != null) body.put("sha", s)
            try { Http.request("$api/choices.json", "PUT", auth, body.toString(), 60000); return }
            catch (e: HttpError) { if (e.code != 409 && e.code != 422 || attempt == 2) throw e }
        }
    }
}

/** Box art screen state: what the bot found, what's on the TV now, and Brian's picks. */
class BoxArtModel(private val scope: CoroutineScope, private val token: () -> String) {
    var titles by mutableStateOf<List<ArtTitle>>(emptyList()); private set
    /** Brian's picks: title key → candidate id, or "none" for the TMDB poster. */
    var picks by mutableStateOf<Map<String, String>>(emptyMap()); private set
    /** What each movie shows on the TV right now (candidate id). */
    var onTv by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var status by mutableStateOf(""); private set
    var loaded by mutableStateOf(false); private set
    /** Boxes Brian found with his own search and picked: title key → that box. */
    var custom by mutableStateOf<Map<String, ArtCandidate>>(emptyMap()); private set
    var searchResults by mutableStateOf<List<ArtCandidate>>(emptyList()); private set
    var searching by mutableStateOf(false); private set
    var searchNote by mutableStateOf(""); private set
    var filter by mutableStateOf(ArtFilter.REVIEW)
    var search by mutableStateOf("")

    fun load() {
        if (token().isBlank()) { status = "Add your GitHub key in Settings first."; return }
        status = "Loading box art…"
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val repo = BoxArtRepo(token())
                    Triple(repo.read("candidates.json"), repo.read("choices.json"), repo.read("index.json"))
                }
            }
            r.onSuccess { (cands, choices, index) ->
                titles = cands?.let { parseTitles(it) } ?: emptyList()
                picks = choices?.let { parsePicks(it) } ?: emptyMap()
                custom = choices?.let { parseCustom(it) } ?: emptyMap()
                onTv = index?.let { parseOnTv(it) } ?: emptyMap()
                loaded = true
                status = if (cands == null) "The box art bot hasn't finished its first search yet. Check back soon." else ""
            }.onFailure { e ->
                status = if (e is HttpError && (e.code == 404 || e.code == 403))
                    "Your GitHub key can't open vhs-fever-boxart. Give it access (Contents: Read and write) on GitHub."
                else "Couldn't load box art: ${e.message}"
            }
        }
    }

    /** Pick a box for a movie (or "none" for the TMDB poster; null goes back to the bot's choice). */
    fun pick(key: String, id: String?, found: ArtCandidate? = null) {
        val before = picks
        picks = if (id == null) picks - key else picks + (key to id)
        if (found != null) custom = custom + (key to found)
        status = "Saving…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { BoxArtRepo(token()).savePick(key, id, found) } }
            status = r.fold({ "Saved. The TV shows it after its next refresh, in a few minutes." },
                { picks = before; "Couldn't save: ${it.message}" })
        }
    }

    /** Search the Archive for a movie's box with Brian's own words, or an Archive link. */
    fun searchArchive(query: String) {
        if (query.isBlank()) return
        searching = true
        searchNote = "Searching the Archive…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { ArchiveArt.search(query) } }
            searching = false
            r.onSuccess { searchResults = it; searchNote = if (it.isEmpty()) "No pictures found. Try other words or paste an Archive link." else "" }
                .onFailure { searchNote = "Couldn't search: ${it.message}" }
        }
    }

    fun clearSearch() { searchResults = emptyList(); searchNote = "" }

    /** The boxes listed for a movie: the bot's finds, plus one Brian found himself. */
    fun candidatesOf(t: ArtTitle): List<ArtCandidate> =
        custom[t.key]?.takeIf { c -> t.candidates.none { it.id == c.id } }?.let { listOf(it) + t.candidates } ?: t.candidates

    fun stateOf(t: ArtTitle): ArtFilter = when {
        t.key in picks -> ArtFilter.MINE
        t.candidates.isEmpty() -> ArtFilter.NONE
        t.sure -> ArtFilter.BOT
        else -> ArtFilter.REVIEW
    }

    fun count(f: ArtFilter) = if (f == ArtFilter.ALL) titles.size else titles.count { stateOf(it) == f }

    fun list(): List<ArtTitle> {
        val q = search.trim().lowercase()
        return titles.filter { (filter == ArtFilter.ALL || stateOf(it) == filter) && (q.isEmpty() || q in it.name.lowercase()) }
            .sortedBy { it.name.lowercase().removePrefix("the ").removePrefix("a ") }
    }

    /** The box that shows (or will show) for a movie: Brian's pick, else what the TV has, else the bot's pick. */
    fun shown(t: ArtTitle): ArtCandidate? {
        val id = picks[t.key] ?: onTv[t.key] ?: t.botPick.takeIf { t.sure }
        return candidatesOf(t).firstOrNull { it.id == id }
    }

    companion object {
        fun parseTitles(text: String): List<ArtTitle> {
            val o = JSONObject(text).optJSONObject("titles") ?: return emptyList()
            return o.keys().asSequence().mapNotNull { k ->
                val t = o.optJSONObject(k) ?: return@mapNotNull null
                ArtTitle(k, t.optString("name", k), t.optStringOrNull("year"), t.optInt("tmdb").takeIf { it > 0 },
                    t.optJSONArray("candidates").objects().map(::parseCandidate), t.optStringOrNull("bot_pick"), t.optBoolean("sure"))
            }.toList()
        }

        private fun parseCandidate(c: JSONObject): ArtCandidate {
            val src = c.optString("source")
            val label = if (src == "vt") listOfNotNull("videotapes.io", c.optStringOrNull("region"), c.optStringOrNull("label"),
                c.opt("tape_year")?.takeIf { it != JSONObject.NULL }?.toString()).joinToString(" · ")
                else c.optStringOrNull("item_title") ?: c.optString("item")
            val link = if (src == "vt") null else c.optStringOrNull("item")?.let { "https://archive.org/details/$it" }
            return ArtCandidate(c.optString("id"), src, c.optStringOrNull("thumb") ?: c.optString("url"), c.optString("url"),
                c.optString("kind", "unknown"), c.optInt("score"), c.optJSONArray("why").strings(), label, link,
                c.optStringOrNull("back_url") != null)
        }

        fun parsePicks(text: String): Map<String, String> {
            val o = JSONObject(text).optJSONObject("titles") ?: return emptyMap()
            return o.keys().asSequence().mapNotNull { k -> o.optJSONObject(k)?.optStringOrNull("pick")?.let { k to it } }.toMap()
        }

        fun parseCustom(text: String): Map<String, ArtCandidate> {
            val o = JSONObject(text).optJSONObject("titles") ?: return emptyMap()
            return o.keys().asSequence().mapNotNull { k ->
                val c = o.optJSONObject(k)?.optJSONObject("candidate") ?: return@mapNotNull null
                k to ArtCandidate(c.optString("id"), "manual", c.optString("thumb", c.optString("url")), c.optString("url"),
                    c.optString("kind", "unknown"), 0, emptyList(), c.optStringOrNull("item_title") ?: c.optString("item"),
                    c.optStringOrNull("item")?.let { "https://archive.org/details/$it" }, false,
                    item = c.optStringOrNull("item"), itemTitle = c.optStringOrNull("item_title"))
            }.toMap()
        }

        /** index.json boxes → candidate ids ("covers/uk7" → "vt:uk7", "ia-ab12" → "ia:ab12"). */
        fun parseOnTv(text: String): Map<String, String> {
            val o = JSONObject(text).optJSONObject("boxes") ?: return emptyMap()
            return o.keys().asSequence().mapNotNull { k ->
                val s = o.optJSONObject(k)?.optStringOrNull("sleeve") ?: return@mapNotNull null
                k to (if (s.startsWith("ia-")) "ia:" + s.removePrefix("ia-") else "vt:$s")
            }.toMap()
        }

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotBlank() }
    }
}
