package org.vhsfever.review

import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.ZipInputStream

class HttpError(val code: Int, msg: String) : Exception(msg)

object Http {
    fun request(
        url: String, method: String = "GET", headers: Map<String, String> = emptyMap(),
        body: String? = null, timeoutMs: Int = 30000,
    ): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "VHS-FEVER-Review-Android")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw HttpError(code, "HTTP $code: ${text.take(200)}")
        return text
    }

    fun download(url: String, headers: Map<String, String>, dest: File) {
        var target = url
        // GitHub artifact downloads redirect to storage that must not receive the token.
        repeat(5) {
            val c = URL(target).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 30000
            c.readTimeout = 120000
            if (target.startsWith("https://api.github.com")) headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.setRequestProperty("User-Agent", "VHS-FEVER-Review-Android")
            val code = c.responseCode
            if (code in 300..399) {
                target = c.getHeaderField("Location") ?: throw HttpError(code, "Redirect without location")
                return@repeat
            }
            if (code !in 200..299) throw HttpError(code, "Download failed: HTTP $code")
            dest.parentFile?.mkdirs()
            c.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
            return
        }
        throw HttpError(0, "Too many redirects")
    }
}

fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

object Tmdb {
    private const val API = "https://api.themoviedb.org/3"
    private const val IMG = "https://image.tmdb.org/t/p/w185"
    private val auth get() = mapOf("Authorization" to "Bearer ${BuildConfig.TMDB_TOKEN}", "Accept" to "application/json")
    val available get() = BuildConfig.TMDB_TOKEN.isNotBlank()

    /** Search by title (optionally "Title 1961"), or by IMDb ID / link. */
    fun search(query: String, type: String, ceiling: Int?): List<Movie> {
        val imdb = Regex("tt\\d{5,10}").find(query)?.value
        if (imdb != null) {
            val o = JSONObject(Http.request("$API/find/$imdb?external_source=imdb_id", headers = auth))
            val rows = o.optJSONArray("movie_results").objects().map { it to "movie" } +
                o.optJSONArray("tv_results").objects().map { it to "tv" }
            return rows.map { (r, t) -> toMovie(r, t, ceiling).copy(imdbId = imdb) }
        }
        var q = query.trim()
        var year: String? = null
        Regex("^(.*?)[\\s(]+((?:18|19|20)\\d\\d)\\)?$").find(q)?.let { m -> q = m.groupValues[1].trim(); year = m.groupValues[2] }
        val yearParam = year?.let { if (type == "tv") "&first_air_date_year=$it" else "&year=$it" } ?: ""
        val o = JSONObject(Http.request("$API/search/$type?include_adult=false&query=${enc(q)}$yearParam", headers = auth))
        return o.optJSONArray("results").objects().take(20).map { toMovie(it, type, ceiling) }
    }

    private fun toMovie(r: JSONObject, type: String, ceiling: Int?): Movie {
        val date = r.optStringOrNull("release_date") ?: r.optStringOrNull("first_air_date")
        val y = date?.take(4)
        return Movie(r.optInt("id"), type, r.optStringOrNull("title") ?: r.optString("name"), y,
            r.optStringOrNull("poster_path")?.let { IMG + it }, r.optStringOrNull("overview"), null,
            tooNew = ceiling != null && y?.toIntOrNull()?.let { it > ceiling } == true)
    }

    private val episodeCache = HashMap<Int, List<Episode>>()

    /** Every episode of a show, all seasons (specials last). */
    fun episodes(showId: Int): List<Episode> = synchronized(episodeCache) { episodeCache[showId] } ?: run {
        val show = JSONObject(Http.request("$API/tv/$showId", headers = auth))
        val out = ArrayList<Episode>()
        val seasons = show.optJSONArray("seasons").objects().filter { it.optInt("episode_count") > 0 }
            .sortedBy { it.optInt("season_number").let { n -> if (n == 0) 9999 else n } }
        for (s in seasons) {
            val n = s.optInt("season_number")
            val o = JSONObject(Http.request("$API/tv/$showId/season/$n", headers = auth))
            for (e in o.optJSONArray("episodes").objects()) {
                out += Episode(e.optIntOrNull("season_number"), e.optIntOrNull("episode_number"), e.optString("name"),
                    e.optStringOrNull("air_date"), e.optStringOrNull("still_path")?.let { "https://image.tmdb.org/t/p/w300$it" },
                    e.optStringOrNull("overview"),
                    "https://www.themoviedb.org/tv/$showId/season/${e.optInt("season_number")}/episode/${e.optInt("episode_number")}")
            }
        }
        val numbered = EpisodeMatch.numberAbsolute(out)
        synchronized(episodeCache) { episodeCache[showId] = numbered }
        numbered
    }

    data class Details(val imdbId: String?, val runtime: Int?, val physical: List<Pair<String, String>>)

    /** IMDb link, runtime and physical (VHS/DVD) release dates by country. */
    fun details(m: Movie): Details {
        if (m.source != "tmdb" || m.tmdbId == 0) return Details(m.imdbId, null, emptyList())
        val path = if (m.type == "tv") "tv/${m.tmdbId}?append_to_response=external_ids"
        else "movie/${m.tmdbId}?append_to_response=release_dates,external_ids"
        val o = JSONObject(Http.request("$API/$path", headers = auth))
        val imdb = o.optJSONObject("external_ids")?.optStringOrNull("imdb_id") ?: o.optStringOrNull("imdb_id")
        val physical = mutableListOf<Pair<String, String>>()
        for (c in o.optJSONObject("release_dates")?.optJSONArray("results").objects()) {
            for (d in c.optJSONArray("release_dates").objects()) {
                if (d.optInt("type") == 5) {
                    val note = d.optString("note").ifBlank { "" }
                    physical += (c.optString("iso_3166_1") + if (note.isNotEmpty()) " · $note" else "") to d.optString("release_date").take(10)
                }
            }
        }
        physical.sortBy { it.second }
        val runtime = if (o.has("runtime") && !o.isNull("runtime")) o.optInt("runtime") else null
        return Details(imdb, runtime, physical)
    }
}

/** TheTVDB, used as a backup when TMDB doesn't have a show or an episode. */
object Tvdb {
    private const val API = "https://api4.thetvdb.com/v4"
    val available get() = BuildConfig.TVDB_KEY.isNotBlank()
    @Volatile private var token: String? = null
    private val episodeCache = HashMap<Int, List<Episode>>()

    private fun auth(): Map<String, String> {
        val t = token ?: run {
            val body = JSONObject().put("apikey", BuildConfig.TVDB_KEY)
            if (BuildConfig.TVDB_PIN.isNotBlank()) body.put("pin", BuildConfig.TVDB_PIN)
            JSONObject(Http.request("$API/login", "POST", body = body.toString())).getJSONObject("data").getString("token")
                .also { token = it }
        }
        return mapOf("Authorization" to "Bearer $t", "Accept" to "application/json")
    }

    private fun get(path: String): JSONObject = try {
        JSONObject(Http.request("$API$path", headers = auth()))
    } catch (e: HttpError) {
        if (e.code == 401) { token = null; JSONObject(Http.request("$API$path", headers = auth())) } else throw e
    }

    private fun idOf(v: Any?): Int? = v?.toString()?.filter(Char::isDigit)?.toIntOrNull()

    fun searchShows(query: String, ceiling: Int?): List<Movie> {
        val q = query.trim().replace(Regex("(?<=\\w)[._](?=\\w)"), " ")
        return get("/search?type=series&limit=20&query=${enc(q)}").optJSONArray("data").objects().mapNotNull { r ->
            val id = idOf(r.opt("tvdb_id") ?: r.opt("id")) ?: return@mapNotNull null
            val name = r.optJSONObject("translations")?.optStringOrNull("eng") ?: r.optString("name")
            val y = r.optStringOrNull("year") ?: r.optStringOrNull("first_air_time")?.take(4)
            Movie(0, "tv", name, y, r.optStringOrNull("image_url") ?: r.optStringOrNull("thumbnail"),
                r.optJSONObject("overviews")?.optStringOrNull("eng") ?: r.optStringOrNull("overview"),
                tooNew = ceiling != null && y?.toIntOrNull()?.let { it > ceiling } == true, source = "tvdb", tvdbId = id)
        }
    }

    fun episodes(seriesId: Int): List<Episode> = synchronized(episodeCache) { episodeCache[seriesId] } ?: run {
        val out = ArrayList<Episode>()
        var page = 0
        while (page < 20) {
            val o = try { get("/series/$seriesId/episodes/default/eng?page=$page") }
                    catch (e: HttpError) { get("/series/$seriesId/episodes/default?page=$page") }
            val eps = o.optJSONObject("data")?.optJSONArray("episodes").objects()
            for (e in eps) {
                val img = e.optStringOrNull("image")?.let { if (it.startsWith("/")) "https://artworks.thetvdb.com$it" else it }
                out += Episode(e.optIntOrNull("seasonNumber"), e.optIntOrNull("number"), e.optString("name"),
                    e.optStringOrNull("aired"), img, e.optStringOrNull("overview"),
                    "https://thetvdb.com/series/$seriesId/episodes/${e.optInt("id")}",
                    absolute = e.optIntOrNull("absoluteNumber")?.takeIf { it > 0 })
            }
            if (eps.isEmpty() || o.optJSONObject("links")?.optStringOrNull("next") == null) break
            page++
        }
        out.sortWith(compareBy({ it.season.let { s -> if (s == null || s == 0) 9999 else s } }, { it.number ?: 0 }))
        val numbered = EpisodeMatch.numberAbsolute(out)
        synchronized(episodeCache) { episodeCache[seriesId] = numbered }
        numbered
    }
}

/** Lines a file up against a show's episode list (same rules as the catalog build). */
object EpisodeMatch {
    fun norm(s: String?): String {
        var t = java.text.Normalizer.normalize(s ?: "", java.text.Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
        t = t.lowercase().replace("&", " and ").replace(Regex("[^a-z0-9]+"), " ").trim()
        t = t.replace(Regex("^(the|a|an) "), "")
        return t.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun lev(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[b.length]
    }

    fun similarity(a: String?, b: String?): Double {
        val x = norm(a); val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        var r = 1.0 - lev(x, y).toDouble() / maxOf(x.length, y.length)
        val (short, long) = if (x.length <= y.length) x to y else y to x
        if (short.length >= 6 && ' ' in short && short.length >= 0.45 * long.length && Regex("\\b" + Regex.escape(short) + "\\b").containsMatchIn(long))
            r = maxOf(r, 0.86 + 0.14 * short.length / long.length)
        return r
    }

    /** "Story One / Story Two" -> whole title plus each story. */
    fun titlePieces(t: String?): List<String> {
        if (t.isNullOrBlank() || Regex("\\s*(chapter|episode|ep\\.?|part|pt\\.?)\\s*#?\\d+\\s*", RegexOption.IGNORE_CASE).matches(t)) return emptyList()
        val out = mutableListOf(t.trim())
        Regex("\\s+[A-Z]{2,5}$").replace(t.trim(), "").trim().let { if (it.isNotEmpty() && it != out[0]) out += it }
        t.split(Regex("\\s{2,}|\\s*/\\s*|\\s+[-\u2013]\\s+|\\s*;\\s*")).map { it.trim(' ', '-', '.', ',', '!') }
            .filter { it.length >= 3 && it !in out && !Regex("^(volume|vol\\.?|part)\\s*\\d+$", RegexOption.IGNORE_CASE).matches(it) }
            .forEach { out += it }
        return out
    }

    fun numberAbsolute(eps: List<Episode>): List<Episode> {
        var n = 0
        return eps.map { e -> if ((e.season ?: 0) >= 1) { n++; e.copy(absolute = e.absolute ?: n) } else e }
    }

    data class Hit(val episode: Episode, val basis: List<String>, val strong: Boolean)

    /** Best few episodes for what the file name says, best first. */
    fun match(eps: List<Episode>, hint: TvHint?): List<Hit> {
        if (hint == null) return emptyList()
        val titles = titlePieces(hint.episodeTitle)
        val points = HashMap<Int, Double>(); val basis = HashMap<Int, MutableList<String>>()
        val titleSim = HashMap<Int, Double>()
        fun add(i: Int, why: String, p: Double) {
            val b = basis.getOrPut(i) { mutableListOf() }
            if (why !in b) { b += why; points[i] = (points[i] ?: 0.0) + p }
        }
        val ep = hint.episode
        if (ep != null) {
            val exact = if (hint.season != null) eps.indices.filter { eps[it].season == hint.season && eps[it].number == ep } else emptyList()
            exact.forEach { add(it, "season and episode number", 2.0) }
            if (exact.isEmpty()) eps.indices.filter { eps[it].absolute == ep }.forEach { add(it, "episode number", 1.3) }
        }
        if (titles.isNotEmpty()) eps.indices.forEach { i ->
            val s = titles.maxOf { similarity(it, eps[i].name) }
            titleSim[i] = s
            if (s >= 0.84) add(i, "episode title", 2.5 * s)
        }
        hint.airDate?.let { d -> eps.indices.filter { eps[it].airDate == d }.forEach { add(it, "air date", 2.0) } }
        return points.keys.map { i ->
            val b = basis[i]!!
            val mismatch = titles.isNotEmpty() && "episode title" !in b && (titleSim[i] ?: 0.0) < 0.5
            val p = points[i]!! - if (mismatch) 1.0 else 0.0
            Triple(i, p, Hit(eps[i], if (mismatch) b + "title doesn’t match" else b,
                !mismatch && ("episode title" in b || ("air date" in b && b.size > 1) || (titles.isEmpty() && b.isNotEmpty()))))
        }.sortedByDescending { it.second }.take(3).map { it.third }
    }
}

/** Reads and writes review/decisions.json on the review-data branch. */
class GitHub(private val token: String) {
    private val repo = BuildConfig.REPO
    private val api = "https://api.github.com/repos/$repo"
    private val auth = mapOf("Authorization" to "Bearer $token", "Accept" to "application/vnd.github+json",
        "X-GitHub-Api-Version" to "2022-11-28")
    private val branch = "review-data"
    private val path = "review/decisions.json"

    fun whoAmI(): String = JSONObject(Http.request("https://api.github.com/user", headers = auth)).optString("login")

    /** Returns (content, sha) or null if the file doesn't exist yet. */
    fun readDecisions(): Pair<String, String>? {
        val meta = try {
            JSONObject(Http.request("$api/contents/$path?ref=$branch", headers = auth))
        } catch (e: HttpError) {
            if (e.code == 404) return null else throw e
        }
        val sha = meta.optString("sha")
        val raw = Http.request("$api/contents/$path?ref=$branch",
            headers = auth + ("Accept" to "application/vnd.github.raw+json"), timeoutMs = 60000)
        return raw to sha
    }

    fun writeDecisions(content: String, sha: String?) {
        ensureBranch()
        val body = JSONObject().put("message", "Review decisions from phone").put("branch", branch)
            .put("content", Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP))
        if (sha != null) body.put("sha", sha)
        Http.request("$api/contents/$path", "PUT", auth, body.toString(), 60000)
    }

    private fun ensureBranch() {
        try {
            Http.request("$api/git/ref/heads/$branch", headers = auth); return
        } catch (e: HttpError) {
            if (e.code != 404) throw e
        }
        val main = JSONObject(Http.request("$api/git/ref/heads/main", headers = auth)).getJSONObject("object").getString("sha")
        Http.request("$api/git/refs", "POST", auth, JSONObject().put("ref", "refs/heads/$branch").put("sha", main).toString())
    }

    data class Update(val runNumber: Int, val artifactUrl: String)

    /** Newest successful app build, if newer than this one. */
    fun findUpdate(current: Int): Update? {
        val runs = JSONObject(Http.request(
            "$api/actions/workflows/android-app.yml/runs?status=success&branch=${enc(BuildConfig.BUILD_BRANCH)}&per_page=1",
            headers = auth)).optJSONArray("workflow_runs").objects()
        val run = runs.firstOrNull() ?: return null
        if (run.optInt("run_number") <= current) return null
        val arts = JSONObject(Http.request(run.optString("artifacts_url"), headers = auth)).optJSONArray("artifacts").objects()
        val apk = arts.firstOrNull { it.optString("name").startsWith("vhs-fever-review") && !it.optBoolean("expired") } ?: return null
        return Update(run.optInt("run_number"), apk.optString("archive_download_url"))
    }

    fun downloadApk(update: Update, dir: File): File {
        val zip = File(dir, "update.zip")
        Http.download(update.artifactUrl, auth, zip)
        val apk = File(dir, "vhs-fever-review-${update.runNumber}.apk")
        ZipInputStream(zip.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name.endsWith(".apk")) { apk.outputStream().use { z.copyTo(it) }; break }
            }
        }
        zip.delete()
        if (!apk.exists()) throw IllegalStateException("No APK inside the build")
        return apk
    }
}
