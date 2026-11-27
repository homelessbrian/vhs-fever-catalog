package org.vhsfever.tv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * What TMDB knows about a movie: poster, background and blurb, plus what the box back and spine
 * print (tagline, cast, director, rating, running time, studio and title logos, film stills, IMDb id).
 */
data class Art(
    val poster: String?, val backdrop: String?, val overview: String?, val year: String?,
    val genres: List<String> = emptyList(),
    val tagline: String? = null,
    val cast: List<String> = emptyList(),
    val director: String? = null,
    val rating: String? = null,        // US certificate: G, PG, PG-13, R…
    val runtime: Int? = null,          // minutes
    val studio: String? = null,
    val studioLogo: String? = null,    // transparent PNG
    val titleLogo: String? = null,     // transparent PNG of the movie's title
    val stills: List<String> = emptyList(),  // film stills with no text on them
    val imdbId: String? = null,
    val collection: Int? = null,       // TMDB collection (a movie and its sequels), so the series' boxes match
    val collectionChecked: Boolean = true,  // false for art saved before collections were looked up
    // for "Similar movies": the movie's own TMDB id, more of its cast, its theme tags, and what TMDB pairs it with
    val id: Int? = null,
    val people: List<String> = emptyList(),
    val keywords: List<Int> = emptyList(),
    val recs: List<Int> = emptyList(),      // TMDB "people also liked", best first
    val similar: List<Int> = emptyList(),   // TMDB "similar", best first
    val matchChecked: Boolean = true,       // false for art saved before the similar-movie info was looked up
    val tags: List<String> = emptyList(),   // TMDB's theme tags by name, lower case ("christmas", "halloween"…)
    val tagsChecked: Boolean = true,        // false for art saved before the tag names were kept
) {
    fun toJson(): JSONObject = JSONObject().put("poster", poster).put("backdrop", backdrop).put("overview", overview)
        .put("year", year).put("genres", JSONArray(genres)).put("tagline", tagline).put("cast", JSONArray(cast))
        .put("director", director).put("rating", rating).put("runtime", runtime).put("studio", studio)
        .put("studioLogo", studioLogo).put("titleLogo", titleLogo).put("stills", JSONArray(stills)).put("imdbId", imdbId)
        .put("collection", collection ?: 0)
        .put("id", id ?: 0).put("people", JSONArray(people)).put("keywords", JSONArray(keywords))
        .put("recs", JSONArray(recs)).put("similar", JSONArray(similar)).put("tags", JSONArray(tags))

    companion object {
        fun fromJson(o: JSONObject) = Art(
            o.s("poster"), o.s("backdrop"), o.s("overview"), o.s("year"), o.optJSONArray("genres").list(),
            o.s("tagline"), o.optJSONArray("cast").list(), o.s("director"), o.s("rating"),
            o.optInt("runtime").takeIf { it > 0 }, o.s("studio"), o.s("studioLogo"), o.s("titleLogo"),
            o.optJSONArray("stills").list(), o.s("imdbId"),
            collection = o.optInt("collection").takeIf { it > 0 }, collectionChecked = o.has("collection"),
            id = o.optInt("id").takeIf { it > 0 }, people = o.optJSONArray("people").list(),
            keywords = o.optJSONArray("keywords").ints(), recs = o.optJSONArray("recs").ints(),
            similar = o.optJSONArray("similar").ints(), matchChecked = o.has("recs"),
            tags = o.optJSONArray("tags").list(), tagsChecked = o.has("tags"),
        )
    }
}

class ArtRepo(ctx: Context) {
    private val cacheFile = File(ctx.filesDir, "tmdb-art-v4.json")
    private val cache = HashMap<String, Art>()
    val available get() = BuildConfig.TMDB_TOKEN.isNotBlank()

    init {
        // older caches (v3 picked stacked title logos that don't fit a spine)
        for (old in listOf("tmdb-art-v2.json", "tmdb-art-v3.json")) File(ctx.filesDir, old).delete()
        runCatching {
            val o = JSONObject(cacheFile.readText())
            for (k in o.keys()) o.optJSONObject(k)?.let { cache[k] = Art.fromJson(it) }
        }
    }

    private fun key(t: Title) = t.tmdbId?.let { "${t.tmdbType}:$it" } ?: "search:${t.searchName}|${t.searchYear}"

    fun cached(t: Title): Art? = synchronized(cache) { cache[key(t)] }

    /**
     * Looks up art for every title not looked up yet (a few at a time), calling [onArt] as each one arrives.
     * Titles already on the TV are skipped: [cached] has them.
     */
    suspend fun loadAll(titles: List<Title>, onArt: (String, Art) -> Unit) = withContext(Dispatchers.IO) {
        if (!available) return@withContext
        // art saved before collections, similar-movie info or tag names were looked up is fetched again (the old art shows meanwhile)
        val todo = titles.filter { cached(it).let { a -> a == null || !a.collectionChecked || !a.matchChecked || !a.tagsChecked } }
        if (todo.isEmpty()) return@withContext
        val gate = Semaphore(4)
        var sinceSave = 0
        todo.map { t ->
            async {
                val art = gate.withPermit { runCatching { fetch(t) }.getOrNull() } ?: return@async
                synchronized(cache) { cache[key(t)] = art }
                onArt(t.key, art)
                // saved now and then, so a long first load isn't lost if the app is closed
                if (synchronized(cache) { ++sinceSave } % 50 == 0) save()
            }
        }.awaitAll()
        save()
    }

    private fun fetch(t: Title): Art? {
        val (type, id) = if (t.tmdbId != null) (if (t.tmdbType == "tv") "tv" else "movie") to t.tmdbId else {
            // "Wallace & Gromit - The Wrong Trousers": if the whole name finds nothing, try the part after the dash.
            val names = listOf(t.searchName) + t.searchName.split(" - ").drop(1).map { it.trim() }.filter { it.length > 2 }
            names.firstNotNullOfOrNull { search(it, t.searchYear) } ?: return Art(null, null, null, null)
        }
        // One call brings everything: details, logos and stills, cast and crew, ratings.
        val extra = if (type == "tv") "images,aggregate_credits,content_ratings,external_ids,keywords,recommendations,similar"
            else "images,credits,release_dates,keywords,recommendations,similar"
        val o = JSONObject(get("$API/$type/$id?append_to_response=$extra&include_image_language=en,null"))
        val images = o.optJSONObject("images")
        val credits = o.optJSONObject("credits") ?: o.optJSONObject("aggregate_credits")
        val company = o.optJSONArray("production_companies").objs().let { cs -> cs.firstOrNull { it.s("logo_path") != null } ?: cs.firstOrNull() }
        return Art(
            poster = o.s("poster_path")?.let { "$IMG/w342$it" },
            backdrop = o.s("backdrop_path")?.let { "$IMG/w1280$it" },
            overview = o.s("overview"),
            year = (o.s("release_date") ?: o.s("first_air_date"))?.take(4),
            genres = genresOf(o),
            tagline = o.s("tagline"),
            cast = credits?.optJSONArray("cast").objs().take(3).mapNotNull { it.s("name") },
            director = credits?.optJSONArray("crew").objs().firstOrNull { it.s("job") == "Director" }?.s("name"),
            rating = usRating(o),
            runtime = (o.optInt("runtime").takeIf { it > 0 } ?: o.optJSONArray("episode_run_time")?.optInt(0)?.takeIf { it > 0 }),
            studio = company?.s("name"),
            studioLogo = company?.s("logo_path")?.let { "$IMG/w185$it" },
            // the widest English logo: a one-line logo fits down a spine, a stacked one shrinks to a smudge
            titleLogo = images?.optJSONArray("logos").objs()
                .filter { it.s("iso_639_1") == "en" && it.s("file_path")?.endsWith(".png") == true }
                .maxByOrNull { it.optDouble("aspect_ratio", 0.0) }
                ?.s("file_path")?.let { "$IMG/w500$it" },
            // stills without any words on them (no language) make the best screenshots
            stills = images?.optJSONArray("backdrops").objs().filter { it.s("iso_639_1") == null }
                .take(4).mapNotNull { it.s("file_path")?.let { p -> "$IMG/w780$p" } },
            imdbId = o.s("imdb_id") ?: o.optJSONObject("external_ids")?.s("imdb_id"),
            collection = o.optJSONObject("belongs_to_collection")?.optInt("id")?.takeIf { it > 0 },
            id = id,
            people = credits?.optJSONArray("cast").objs().take(8).mapNotNull { it.s("name") },
            // movies keep their tags under "keywords", TV under "results"
            keywords = o.optJSONObject("keywords")?.let { k -> k.optJSONArray("keywords") ?: k.optJSONArray("results") }
                .objs().mapNotNull { it.optInt("id").takeIf { n -> n > 0 } },
            tags = o.optJSONObject("keywords")?.let { k -> k.optJSONArray("keywords") ?: k.optJSONArray("results") }
                .objs().mapNotNull { it.s("name")?.lowercase() },
            recs = o.optJSONObject("recommendations")?.optJSONArray("results").objs().mapNotNull { it.optInt("id").takeIf { n -> n > 0 } },
            similar = o.optJSONObject("similar")?.optJSONArray("results").objs().mapNotNull { it.optInt("id").takeIf { n -> n > 0 } },
        )
    }

    private fun usRating(o: JSONObject): String? {
        o.optJSONObject("release_dates")?.optJSONArray("results").objs().firstOrNull { it.s("iso_3166_1") == "US" }
            ?.optJSONArray("release_dates").objs().mapNotNull { it.s("certification") }.firstOrNull()?.let { return it }
        return o.optJSONObject("content_ratings")?.optJSONArray("results").objs()
            .firstOrNull { it.s("iso_3166_1") == "US" }?.s("rating")
    }

    /** The best TMDB match for a name: ("movie" or "tv", id). */
    private fun search(name: String, year: String?): Pair<String, Int>? {
        val q = URLEncoder.encode(name, "UTF-8")
        val y = year?.let { "&year=$it" } ?: ""
        JSONObject(get("$API/search/movie?include_adult=false&query=$q$y")).optJSONArray("results")?.optJSONObject(0)
            ?.optInt("id")?.takeIf { it > 0 }?.let { return "movie" to it }
        return JSONObject(get("$API/search/tv?include_adult=false&query=$q")).optJSONArray("results")?.optJSONObject(0)
            ?.optInt("id")?.takeIf { it > 0 }?.let { "tv" to it }
    }

    private fun save() {
        val o = JSONObject()
        synchronized(cache) { for ((k, a) in cache) o.put(k, a.toJson()) }
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

private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
private fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else (0 until length()).map { optInt(it) }.filter { it > 0 }
private fun JSONArray?.list(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
