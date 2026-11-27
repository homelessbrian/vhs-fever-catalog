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

/** Brian's line-up of a box: the spine lines (wraps) and trimmed edges, all fractions of the picture. */
data class Adjust(
    val sleeve: String,
    val folds: Pair<Float, Float>?,
    val left: Float = 0f, val right: Float = 1f, val top: Float = 0f, val bottom: Float = 1f,
    /** Quarter turns clockwise (0, 90, 180, 270); the lines are on the turned picture. */
    val rotate: Int = 0,
    /** A wrap scanned front | spine | back: true swaps them, false keeps them. Null lets the bot decide. */
    val flip: Boolean? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("sleeve", sleeve).apply {
        if (rotate != 0) put("rotate", rotate)
        flip?.let { put("flip", it) }
        folds?.let { put("folds", JSONObject().put("left", it.first.toDouble()).put("right", it.second.toDouble())) }
        put("trim", JSONObject().put("left", left.toDouble()).put("right", right.toDouble())
            .put("top", top.toDouble()).put("bottom", bottom.toDouble()))
    }

    companion object {
        fun parse(o: JSONObject?, sleeve: String? = null): Adjust? {
            if (o == null) return null
            val s = o.optStringOrNull("sleeve") ?: sleeve ?: return null
            val f = o.optJSONObject("folds")?.let { it.optDouble("left").toFloat() to it.optDouble("right").toFloat() }
            val t = o.optJSONObject("trim")
            return Adjust(s, f, t?.optDouble("left", 0.0)?.toFloat() ?: 0f, t?.optDouble("right", 1.0)?.toFloat() ?: 1f,
                t?.optDouble("top", 0.0)?.toFloat() ?: 0f, t?.optDouble("bottom", 1.0)?.toFloat() ?: 1f,
                Math.floorMod(o.optInt("rotate"), 360),
                if (o.has("flip") && !o.isNull("flip")) o.optBoolean("flip") else null)
        }
    }
}

fun foldsOf(o: JSONObject?): Pair<Float, Float>? = o?.let {
    val l = it.optDouble("left"); val r = it.optDouble("right")
    if (l.isNaN() || r.isNaN() || l >= r) null else l.toFloat() to r.toFloat()
}

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
    /** Where the spine starts and ends, as fractions of the picture's width (wraps only). */
    val folds: Pair<Float, Float>? = null,
) {
    /** The name the box job gives this box's folder ("vt:uk7" → "uk7", "ia:ab12" → "ia-ab12"). */
    val sleeve: String get() = if (id.startsWith("vt:")) id.removePrefix("vt:") else "ia-" + id.removePrefix("ia:")

    /** What's saved with a pick Brian found himself, so the box job can use it. */
    fun toJson(): JSONObject = JSONObject().put("id", id).put("source", source).put("url", url).put("thumb", thumb)
        .put("kind", kind).put("item", item).put("item_title", itemTitle)

    val sourceName get() = when (source) { "vt" -> "videotapes.io"; "page" -> "This tape's page"; "manual" -> "Your search"; else -> "Archive" }
}

data class ArtTitle(
    val key: String, val name: String, val year: String?, val tmdb: Int?,
    val candidates: List<ArtCandidate>, val botPick: String?, val sure: Boolean,
)

enum class ArtFilter(val label: String) {
    REVIEW("Needs a look"), ON_TV("Has a box"), MINE("Your picks"), NONE("Nothing found"), ALL("All")
}

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

    /** Whether this key can see the box art repository at all (GitHub says "not found" when it can't). */
    fun canOpen(): Boolean = try {
        Http.request("https://api.github.com/repos/${BuildConfig.BOXART_REPO}", headers = auth); true
    } catch (e: HttpError) { if (e.code == 404 || e.code == 403 || e.code == 401) false else throw e }

    private fun sha(path: String): String? = try {
        JSONObject(Http.request("$api/$path?ref=main", headers = auth)).optString("sha").ifBlank { null }
    } catch (e: HttpError) { if (e.code == 404) null else throw e }

    /** Sets (or with null, removes) the separate picture used for the back of a movie's box. */
    fun saveBack(key: String, back: ArtCandidate?) = change(key, "Box art back from phone") { e ->
        if (back == null) e.remove("back") else e.put("back", JSONObject().put("id", back.id).put("url", back.url))
    }

    /** Sets (or with null, removes) the spine: a strip Brian marked on a picture, often the front's or the back's own scan. */
    fun saveSpine(key: String, pic: ArtCandidate?, cut: Adjust?) = change(key, "Box art spine from phone") { e ->
        if (pic == null || cut == null) e.remove("spine")
        else e.put("spine", JSONObject().put("id", pic.id).put("url", pic.url).put("rotate", cut.rotate)
            .put("trim", JSONObject().put("left", cut.left.toDouble()).put("right", cut.right.toDouble())
                .put("top", cut.top.toDouble()).put("bottom", cut.bottom.toDouble())))
    }

    /** Changes one movie's entry in choices.json, merged into whatever it holds now. */
    private fun change(key: String, message: String, edit: (JSONObject) -> Unit) {
        repeat(3) { attempt ->
            val s = sha("choices.json")
            val now = (if (s != null) read("choices.json") else null)?.let { JSONObject(it) } ?: JSONObject()
            val titles = now.optJSONObject("titles") ?: JSONObject().also { now.put("titles", it) }
            val e = titles.optJSONObject(key) ?: JSONObject()
            edit(e)
            e.put("at", Instant.now().toString())
            titles.put(key, e)
            now.put("about", "Box art picked in the review app. \"none\" = show the TMDB poster.")
            val body = JSONObject().put("message", message).put("branch", "main")
                .put("content", Base64.encodeToString(now.toString(1).toByteArray(), Base64.NO_WRAP))
            if (s != null) body.put("sha", s)
            try { Http.request("$api/choices.json", "PUT", auth, body.toString(), 60000); return }
            catch (e: HttpError) { if (e.code != 409 && e.code != 422 || attempt == 2) throw e }
        }
    }

    /** Saves one pick, merged into whatever choices.json holds now (two phones never undo each other). */
    fun savePick(key: String, pick: String?, candidate: ArtCandidate? = null, adjust: Adjust? = null) {
        repeat(3) { attempt ->
            val s = sha("choices.json")
            val now = (if (s != null) read("choices.json") else null)?.let { JSONObject(it) } ?: JSONObject()
            val titles = now.optJSONObject("titles") ?: JSONObject().also { now.put("titles", it) }
            if (pick == null) titles.remove(key)
            else titles.put(key, JSONObject().put("pick", pick).put("at", Instant.now().toString())
                .apply {
                    val before = titles.optJSONObject(key)
                    if (candidate != null) put("candidate", candidate.toJson())
                    else before?.optJSONObject("candidate")?.takeIf { it.optString("id") == pick }?.let { put("candidate", it) }
                    // a line-up stays with its box
                    if (adjust != null) put("adjust", adjust.toJson())
                    else before?.optJSONObject("adjust")?.let { put("adjust", it) }
                    before?.optJSONObject("back")?.let { put("back", it) }
                    before?.optJSONObject("spine")?.let { put("spine", it) }
                })
            now.put("about", "Box art picked in the review app. \"none\" = show the TMDB poster.")
            val body = JSONObject().put("message", "Box art pick from phone").put("branch", "main")
                .put("content", Base64.encodeToString(now.toString(1).toByteArray(), Base64.NO_WRAP))
            if (s != null) body.put("sha", s)
            try { Http.request("$api/choices.json", "PUT", auth, body.toString(), 60000); return }
            catch (e: HttpError) { if (e.code != 409 && e.code != 422 || attempt == 2) throw e }
        }
    }
}

private class NoAccess : Exception("no access to the box art repository")

/** Box art screen state: what the bot found, what's on the TV now, and Brian's picks. */
class BoxArtModel(private val scope: CoroutineScope, private val token: () -> String) {
    var titles by mutableStateOf<List<ArtTitle>>(emptyList()); private set
    /** Brian's picks: title key → candidate id, or "none" for the TMDB poster. */
    var picks by mutableStateOf<Map<String, String>>(emptyMap()); private set
    /** What each movie shows on the TV right now (candidate id). */
    var onTv by mutableStateOf<Map<String, String>>(emptyMap()); private set
    /** The box each movie shows on the TV now, with its picture: title key → box. */
    private var tvBoxes: Map<String, Pair<ArtTitle, ArtCandidate>> = emptyMap()
    var status by mutableStateOf(""); private set
    var loaded by mutableStateOf(false); private set
    /** Separate back pictures Brian picked: title key → the picture. */
    var backs by mutableStateOf<Map<String, ArtCandidate>>(emptyMap()); private set

    fun setBack(key: String, c: ArtCandidate?) {
        val before = backs
        backs = if (c == null) backs - key else backs + (key to c)
        status = "Saving…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { BoxArtRepo(token()).saveBack(key, c) } }
            status = r.fold({ if (c == null) "Back removed. The box uses its own back again after the next refresh."
                else "Back saved. The TV shows it after its next refresh, in a few minutes." },
                { backs = before; "Couldn't save: ${it.message}" })
        }
    }

    /** Spines Brian marked on a picture: title key → the picture and where the spine is on it. */
    var spines by mutableStateOf<Map<String, Pair<ArtCandidate, Adjust>>>(emptyMap()); private set

    fun setSpine(key: String, c: ArtCandidate?, cut: Adjust?) {
        val before = spines
        spines = if (c == null || cut == null) spines - key else spines + (key to (c to cut))
        status = "Saving…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { BoxArtRepo(token()).saveSpine(key, c, cut) } }
            status = r.fold({ if (c == null) "Spine removed. The box goes back to its own spine after the next refresh."
                else "Spine saved. The TV shows it after its next refresh, in a few minutes." },
                { spines = before; "Couldn't save: ${it.message}" })
        }
    }

    /** Any picture listed for a movie (its boxes, its back, its spine, or a search result) by id. */
    fun picture(t: ArtTitle, id: String): ArtCandidate? =
        candidatesOf(t).firstOrNull { it.id == id } ?: searchResults.firstOrNull { it.id == id }

    /** TMDB facts per movie (looked up when a movie's box screen opens). */
    var info by mutableStateOf<Map<String, Tmdb.Summary>>(emptyMap()); private set
    /** Screenshots from each movie's own tape: title key → paths in the box art repository. */
    var shots by mutableStateOf<Map<String, List<String>>>(emptyMap()); private set

    fun loadInfo(t: ArtTitle) {
        val id = t.tmdb ?: return
        if (t.key in info || !Tmdb.available) return
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { Tmdb.summary(id) } }.getOrNull()?.let { info = info + (t.key to it) }
        }
    }

    /** A picture in the private box art repository, loaded with the GitHub key. */
    fun privateImage(ctx: android.content.Context, path: String): coil.request.ImageRequest =
        coil.request.ImageRequest.Builder(ctx)
            .data("https://api.github.com/repos/${BuildConfig.BOXART_REPO}/contents/$path?ref=main")
            .addHeader("Authorization", "Bearer ${token()}").addHeader("Accept", "application/vnd.github.raw")
            .crossfade(true).build()

    /** Brian's line-ups: title key → adjustment (only used while that box is the one shown). */
    var adjusts by mutableStateOf<Map<String, Adjust>>(emptyMap()); private set
    /** Line-ups the TV boxes were cut with. */
    private var tvAdjusts: Map<String, Adjust> = emptyMap()
    /** Boxes the bot found scanned the wrong way round and flipped: title key → that box's sleeve. */
    private var tvFlipped: Map<String, String> = emptyMap()

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
                    if (!repo.canOpen()) throw NoAccess()
                    val s = repo.read("shots.json")
                    if (s != null) shots = parseShots(s)
                    Triple(repo.read("candidates.json"), repo.read("choices.json"), repo.read("index.json"))
                }
            }
            r.onSuccess { (cands, choices, index) ->
                tvBoxes = index?.let { parseTvBoxes(it) } ?: emptyMap()
                // every movie the bot searched, plus every movie that already has a box on the TV
                val found = cands?.let { parseTitles(it) } ?: emptyList()
                val foundKeys = found.map { it.key }.toSet()
                titles = found + tvBoxes.filterKeys { it !in foundKeys }.values.map { it.first }
                picks = choices?.let { parsePicks(it) } ?: emptyMap()
                custom = choices?.let { parseCustom(it) } ?: emptyMap()
                adjusts = choices?.let { parseAdjusts(it) } ?: emptyMap()
                backs = choices?.let { parseBacks(it) } ?: emptyMap()
                spines = choices?.let { parseSpines(it) } ?: emptyMap()
                tvAdjusts = index?.let { parseTvAdjusts(it) } ?: emptyMap()
                tvFlipped = index?.let { parseTvFlipped(it) } ?: emptyMap()
                onTv = index?.let { parseOnTv(it) } ?: emptyMap()
                if (!loaded && filter == ArtFilter.REVIEW && count(ArtFilter.REVIEW) == 0) filter = ArtFilter.ON_TV
                loaded = true
                status = if (cands == null) "The bot is still on its first search for other boxes. Movies that already have one are listed." else ""
            }.onFailure { e ->
                status = if (e is NoAccess || e is HttpError && (e.code == 404 || e.code == 403))
                    "Your GitHub key can't see vhs-fever-boxart. On GitHub, open the key you use in this app " +
                        "and add vhs-fever-boxart to its repositories with Contents: Read and write. " +
                        "If it's a fine-grained key, check its owner is homelessbrian. Then tap reload."
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

    /** The line-up to start from for a box: Brian's saved one, else the one the TV box was cut with. */
    fun adjustFor(key: String, c: ArtCandidate): Adjust? =
        (adjusts[key] ?: tvAdjusts[key])?.takeIf { it.sleeve == c.sleeve }

    /** True when the left half of this wrap is used as the front (the scan was front | spine | back). */
    fun isFlipped(key: String, c: ArtCandidate): Boolean =
        adjustFor(key, c)?.flip ?: (tvFlipped[key] == c.sleeve)

    /** Swap which half of a wrap is the front and which is the back; the box is cut again for the TV. */
    fun flip(key: String, c: ArtCandidate) {
        val a = (adjustFor(key, c) ?: Adjust(c.sleeve, null)).copy(flip = !isFlipped(key, c))
        saveAdjust(key, c, a)
    }

    /** Save a line-up: the box is cut again (from the kept picture) and the TV shows it after its next refresh. */
    fun saveAdjust(key: String, c: ArtCandidate, a: Adjust) {
        val beforePicks = picks; val beforeAdj = adjusts
        picks = picks + (key to c.id)
        adjusts = adjusts + (key to a)
        status = "Saving…"
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { BoxArtRepo(token()).savePick(key, c.id, c.takeIf { it.source == "manual" }, a) }
            }
            status = r.fold({ "Saved. The box is cut again and the TV shows it after its next refresh, in a few minutes." },
                { picks = beforePicks; adjusts = beforeAdj; "Couldn't save: ${it.message}" })
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
    fun candidatesOf(t: ArtTitle): List<ArtCandidate> {
        var list = t.candidates
        // the box on the TV now, even if the bot's list doesn't have it
        tvBoxes[t.key]?.second?.let { c -> if (list.none { it.id == c.id }) list = listOf(c) + list }
        custom[t.key]?.let { c -> if (list.none { it.id == c.id }) list = listOf(c) + list }
        backs[t.key]?.let { c -> if (list.none { it.id == c.id }) list = list + c }
        spines[t.key]?.first?.let { c -> if (list.none { it.id == c.id }) list = list + c }
        return list
    }

    fun stateOf(t: ArtTitle): ArtFilter = when {
        t.key in picks -> ArtFilter.MINE
        t.key in onTv -> ArtFilter.ON_TV
        t.candidates.isEmpty() -> ArtFilter.NONE
        else -> ArtFilter.REVIEW
    }

    /** "videotapes.io box", "Bot's pick" or "Your pick": who chose the box the TV shows. */
    fun whoChose(t: ArtTitle): String? = tvBoxes[t.key]?.let { (_, c) ->
        when { c.id in custom.values.map { it.id } -> "Your pick"; c.source == "vt" -> "videotapes.io box"; else -> "Bot's pick" } }

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
                c.optStringOrNull("back_url") != null, item = c.optStringOrNull("item"),
                itemTitle = c.optStringOrNull("item_title"), folds = foldsOf(c.optJSONObject("folds")))
        }

        fun parsePicks(text: String): Map<String, String> {
            val o = JSONObject(text).optJSONObject("titles") ?: return emptyMap()
            return o.keys().asSequence().mapNotNull { k -> o.optJSONObject(k)?.optStringOrNull("pick")?.let { k to it } }.toMap()
        }

        /** index.json boxes, as listings with the picture of the box shown now. */
        fun parseTvBoxes(text: String): Map<String, Pair<ArtTitle, ArtCandidate>> {
            val root = JSONObject(text)
            // an older list has only "films", by TMDB id
            val boxes = root.optJSONObject("boxes")
            val o = boxes ?: root.optJSONObject("films") ?: return emptyMap()
            return o.keys().asSequence().mapNotNull { key ->
                val k = if (boxes != null) key else "tmdb:$key"
                val e = o.optJSONObject(key) ?: return@mapNotNull null
                val sleeve = e.optStringOrNull("sleeve") ?: return@mapNotNull null
                val img = e.optStringOrNull("source_image") ?: return@mapNotNull null
                val archive = sleeve.startsWith("ia-")
                val id = if (archive) "ia:" + sleeve.removePrefix("ia-") else "vt:$sleeve"
                val label = if (archive) e.optStringOrNull("title_on_sleeve") ?: e.optString("item")
                    else listOfNotNull("videotapes.io", e.optStringOrNull("region"), e.optStringOrNull("label"),
                        e.opt("tape_year")?.takeIf { it != JSONObject.NULL }?.toString()).joinToString(" · ")
                val kind = when (e.optStringOrNull("layout")) { "spread" -> "wrap"; "front_only" -> "front"; else -> "unknown" }
                val c = ArtCandidate(id, if (archive) "search" else "vt", img, img, kind, 0, emptyList(), label,
                    e.optStringOrNull("item")?.let { "https://archive.org/details/$it" }, e.optStringOrNull("back_image") != null,
                    item = e.optStringOrNull("item"), itemTitle = e.optStringOrNull("title_on_sleeve"),
                    folds = foldsOf(e.optJSONObject("folds")))
                val name = e.optStringOrNull("name") ?: e.optStringOrNull("title_on_sleeve")?.let(::titleCase) ?: k
                k to (ArtTitle(k, name, e.optStringOrNull("year"), k.removePrefix("tmdb:").toIntOrNull().takeIf { k.startsWith("tmdb:") },
                    emptyList(), null, false) to c)
            }.toMap()
        }

        private fun titleCase(s: String) = if (s != s.uppercase()) s else
            s.lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

        fun parseBacks(text: String): Map<String, ArtCandidate> {
            val o = JSONObject(text).optJSONObject("titles") ?: return emptyMap()
            return o.keys().asSequence().mapNotNull { k ->
                val b = o.optJSONObject(k)?.optJSONObject("back") ?: return@mapNotNull null
                val url = b.optStringOrNull("url") ?: return@mapNotNull null
                k to ArtCandidate(b.optString("id"), "manual", url, url, "front", 0, emptyList(), "Back", null, false)
            }.toMap()
        }

        fun parseSpines(text: String): Map<String, Pair<ArtCandidate, Adjust>> {
            val o = JSONObject(text).optJSONObject("titles") ?: return emptyMap()
            return o.keys().asSequence().mapNotNull { k ->
                val sp = o.optJSONObject(k)?.optJSONObject("spine") ?: return@mapNotNull null
                val url = sp.optStringOrNull("url") ?: return@mapNotNull null
                val id = sp.optString("id")
                val cut = Adjust.parse(sp, id) ?: return@mapNotNull null
                k to (ArtCandidate(id, "manual", url, url, "front", 0, emptyList(), "Spine", null, false) to cut)
            }.toMap()
        }

        fun parseShots(text: String): Map<String, List<String>> {
            val o = JSONObject(text).optJSONObject("titles") ?: return emptyMap()
            return o.keys().asSequence().associateWith { k ->
                o.optJSONObject(k)?.optJSONArray("files").let { a -> if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) } }
            }.filterValues { it.isNotEmpty() }
        }

        fun parseAdjusts(text: String): Map<String, Adjust> {
            val o = JSONObject(text).optJSONObject("titles") ?: return emptyMap()
            return o.keys().asSequence().mapNotNull { k -> Adjust.parse(o.optJSONObject(k)?.optJSONObject("adjust"))?.let { k to it } }.toMap()
        }

        fun parseTvAdjusts(text: String): Map<String, Adjust> {
            val o = JSONObject(text).optJSONObject("boxes") ?: return emptyMap()
            return o.keys().asSequence().mapNotNull { k ->
                val e = o.optJSONObject(k) ?: return@mapNotNull null
                Adjust.parse(e.optJSONObject("adjust"), e.optStringOrNull("sleeve"))?.let { k to it }
            }.toMap()
        }

        fun parseTvFlipped(text: String): Map<String, String> {
            val o = JSONObject(text).optJSONObject("boxes") ?: return emptyMap()
            return o.keys().asSequence().mapNotNull { k ->
                val e = o.optJSONObject(k) ?: return@mapNotNull null
                if (e.optBoolean("flipped")) e.optStringOrNull("sleeve")?.let { k to it } else null
            }.toMap()
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
        fun parseOnTv(text: String): Map<String, String> = parseTvBoxes(text).mapValues { it.value.second.id }


        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotBlank() }
    }
}
