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
) {
    val sourceName get() = when (source) { "vt" -> "videotapes.io"; "page" -> "This tape's page"; else -> "Archive" }
}

data class ArtTitle(
    val key: String, val name: String, val year: String?, val tmdb: Int?,
    val candidates: List<ArtCandidate>, val botPick: String?, val sure: Boolean,
)

enum class ArtFilter(val label: String) { REVIEW("Needs a look"), BOT("Bot picked"), MINE("Your picks"), NONE("Nothing found"), ALL("All") }

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
    fun savePick(key: String, pick: String?) {
        repeat(3) { attempt ->
            val s = sha("choices.json")
            val now = (if (s != null) read("choices.json") else null)?.let { JSONObject(it) } ?: JSONObject()
            val titles = now.optJSONObject("titles") ?: JSONObject().also { now.put("titles", it) }
            if (pick == null) titles.remove(key)
            else titles.put(key, JSONObject().put("pick", pick).put("at", Instant.now().toString()))
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
    fun pick(key: String, id: String?) {
        val before = picks
        picks = if (id == null) picks - key else picks + (key to id)
        status = "Saving…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { BoxArtRepo(token()).savePick(key, id) } }
            status = r.fold({ "Saved. The TV shows it after its next refresh, in a few minutes." },
                { picks = before; "Couldn't save: ${it.message}" })
        }
    }

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
        return t.candidates.firstOrNull { it.id == id }
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
