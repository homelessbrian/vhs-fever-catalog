package org.vhsfever.tv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Poster, background and blurb from TMDB. Stand-in art until real VHS box scans are wired up. */
data class Art(val poster: String?, val backdrop: String?, val overview: String?, val year: String?,
    val genres: List<String> = emptyList())

class ArtRepo(ctx: Context) {
    private val cacheFile = File(ctx.filesDir, "tmdb-art-v2.json")
    private val cache = HashMap<String, Art>()
    val available get() = BuildConfig.TMDB_TOKEN.isNotBlank()

    init {
        runCatching {
            val o = JSONObject(cacheFile.readText())
            for (k in o.keys()) o.optJSONObject(k)?.let {
                val g = it.optJSONArray("genres")
                cache[k] = Art(it.s("poster"), it.s("backdrop"), it.s("overview"), it.s("year"),
                    if (g == null) emptyList() else (0 until g.length()).map { i -> g.optString(i) })
            }
        }
    }

    private fun key(t: Title) = t.tmdbId?.let { "${t.tmdbType}:$it" } ?: "search:${t.searchName}|${t.searchYear}"

    fun cached(t: Title): Art? = synchronized(cache) { cache[key(t)] }

    /** Looks up art for every title (a few at a time), calling [onArt] as each one arrives. */
    suspend fun loadAll(titles: List<Title>, onArt: (String, Art) -> Unit) = withContext(Dispatchers.IO) {
        if (!available) return@withContext
        val gate = Semaphore(4)
        titles.map { t ->
            async {
                val k = key(t)
                synchronized(cache) { cache[k] }?.let { onArt(t.key, it); return@async }
                val art = gate.withPermit { runCatching { fetch(t) }.getOrNull() } ?: return@async
                synchronized(cache) { cache[k] = art }
                onArt(t.key, art)
            }
        }.awaitAll()
        save()
    }

    private fun fetch(t: Title): Art? {
        val o: JSONObject = if (t.tmdbId != null) {
            JSONObject(get("$API/${if (t.tmdbType == "tv") "tv" else "movie"}/${t.tmdbId}"))
        } else {
            // "Wallace & Gromit - The Wrong Trousers": if the whole name finds nothing, try the part after the dash.
            val names = listOf(t.searchName) + t.searchName.split(" - ").drop(1).map { it.trim() }.filter { it.length > 2 }
            names.firstNotNullOfOrNull { search(it, t.searchYear) } ?: return Art(null, null, null, null)
        }
        return Art(
            poster = o.s("poster_path")?.let { "$IMG/w342$it" },
            backdrop = o.s("backdrop_path")?.let { "$IMG/w1280$it" },
            overview = o.s("overview"),
            year = (o.s("release_date") ?: o.s("first_air_date"))?.take(4),
            genres = genresOf(o),
        )
    }

    private fun search(name: String, year: String?): JSONObject? {
        val q = URLEncoder.encode(name, "UTF-8")
        val y = year?.let { "&year=$it" } ?: ""
        return JSONObject(get("$API/search/movie?include_adult=false&query=$q$y")).optJSONArray("results")?.optJSONObject(0)
            ?: JSONObject(get("$API/search/tv?include_adult=false&query=$q")).optJSONArray("results")?.optJSONObject(0)
    }

    private fun save() {
        val o = JSONObject()
        synchronized(cache) {
            for ((k, a) in cache) o.put(k, JSONObject().put("poster", a.poster).put("backdrop", a.backdrop)
                .put("overview", a.overview).put("year", a.year).put("genres", org.json.JSONArray(a.genres)))
        }
        runCatching { cacheFile.writeText(o.toString()) }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 20000
        c.setRequestProperty("Authorization", "Bearer ${BuildConfig.TMDB_TOKEN}")
        c.setRequestProperty("Accept", "application/json")
        if (c.responseCode !in 200..299) throw Exception("TMDB HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun genresOf(o: JSONObject): List<String> {
        o.optJSONArray("genres")?.let { a -> return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.s("name") }.map(::shortGenre) }
        val ids = o.optJSONArray("genre_ids") ?: return emptyList()
        return (0 until ids.length()).mapNotNull { GENRES[ids.optInt(it)] }.map(::shortGenre)
    }

    companion object {
        /** TMDB genre ids (movies and TV) to names. */
        private val GENRES = mapOf(
            28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime", 99 to "Documentary",
            18 to "Drama", 10751 to "Family", 14 to "Fantasy", 36 to "History", 27 to "Horror", 10402 to "Music",
            9648 to "Mystery", 10749 to "Romance", 878 to "Science Fiction", 10770 to "TV Movie", 53 to "Thriller",
            10752 to "War", 37 to "Western", 10759 to "Action & Adventure", 10762 to "Kids", 10765 to "Sci-Fi & Fantasy",
            10764 to "Reality", 10767 to "Talk", 10763 to "News", 10766 to "Soap", 10768 to "War & Politics",
        )
        fun shortGenre(g: String) = when (g) {
            "Science Fiction", "Sci-Fi & Fantasy" -> "Sci-Fi"
            "Action & Adventure" -> "Action"
            else -> g
        }

        private const val API = "https://api.themoviedb.org/3"
        private const val IMG = "https://image.tmdb.org/t/p"
    }
}

private fun JSONObject.s(k: String): String? =
    if (isNull(k)) null else optString(k).takeIf { it.isNotBlank() && it != "null" }
