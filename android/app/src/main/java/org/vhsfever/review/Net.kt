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

    /** TMDB genre ids to the names the TV app's shelves use. */
    private val GENRES = mapOf(
        28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime", 99 to "Documentary",
        18 to "Drama", 10751 to "Family", 14 to "Fantasy", 36 to "History", 27 to "Horror", 10402 to "Music",
        9648 to "Mystery", 10749 to "Romance", 878 to "Sci-Fi", 10770 to "TV Movie", 53 to "Thriller",
        10752 to "War", 37 to "Western",
    )

    /** Search movies by title (optionally "Title 1961"), or by IMDb ID / link. */
    fun search(query: String, ceiling: Int?): List<Movie> {
        val imdb = Regex("tt\\d{5,10}").find(query)?.value
        if (imdb != null) {
            val o = JSONObject(Http.request("$API/find/$imdb?external_source=imdb_id", headers = auth))
            return o.optJSONArray("movie_results").objects().map { toMovie(it, ceiling).copy(imdbId = imdb) }
        }
        var q = query.trim()
        var year: String? = null
        Regex("^(.*?)[\\s(]+((?:18|19|20)\\d\\d)\\)?$").find(q)?.let { m -> q = m.groupValues[1].trim(); year = m.groupValues[2] }
        val yearParam = year?.let { "&year=$it" } ?: ""
        val o = JSONObject(Http.request("$API/search/movie?include_adult=false&query=${enc(q)}$yearParam", headers = auth))
        return o.optJSONArray("results").objects().take(20).map { toMovie(it, ceiling) }
    }

    private fun toMovie(r: JSONObject, ceiling: Int?): Movie {
        val y = r.optStringOrNull("release_date")?.take(4)
        val ids = r.optJSONArray("genre_ids")
        val genres = if (ids == null) emptyList() else (0 until ids.length()).mapNotNull { GENRES[ids.optInt(it)] }
        return Movie(r.optInt("id"), "movie", r.optStringOrNull("title") ?: r.optString("name"), y,
            r.optStringOrNull("poster_path")?.let { IMG + it }, r.optStringOrNull("overview"), null,
            tooNew = ceiling != null && y?.toIntOrNull()?.let { it > ceiling } == true, genres = genres)
    }

    data class Details(val imdbId: String?, val runtime: Int?, val genres: List<String>, val physical: List<Pair<String, String>>)

    /** IMDb link, runtime, genres and physical (VHS/DVD) release dates by country. */
    fun details(m: Movie): Details {
        if (m.source != "tmdb" || m.tmdbId == 0) return Details(m.imdbId, null, m.genres, emptyList())
        val o = JSONObject(Http.request("$API/movie/${m.tmdbId}?append_to_response=release_dates,external_ids", headers = auth))
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
        val genres = o.optJSONArray("genres").objects().mapNotNull { it.optStringOrNull("name") }
            .map { if (it == "Science Fiction") "Sci-Fi" else it }
        return Details(imdb, runtime, genres, physical)
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
            "$api/actions/workflows/android-app.yml/runs?status=success&branch=main&per_page=1",
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
