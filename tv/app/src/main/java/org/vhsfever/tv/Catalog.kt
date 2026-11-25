package org.vhsfever.tv

import org.json.JSONArray
import org.json.JSONObject

/** One playable file on archive.org. */
data class Source(
    val id: String,
    val name: String,
    val url: String,
    val format: String,
    val isArchiveCopy: Boolean,
)

/** One tape (one file Brian marked good), plus backup copies of the same recording. */
data class Tape(
    val fileId: String,
    val label: String,          // "1992 VHS · Version 2 · Canadian Copy"
    val fileName: String,
    val archiveItem: String,
    val minutes: Int?,
    val sources: List<Source>,  // the marked file first, then backups to try if it won't play
    val media: String? = null,
    val releaseYear: String? = null,
    val addedAt: String = "",   // when it was marked good
)

/** One box on the shelf: a movie or show, with every good tape of it. */
data class Title(
    val key: String,
    val name: String,
    val year: String?,
    val poster: String?,
    val posterIsSuggestion: Boolean,  // poster from a TMDB guess nobody has confirmed yet
    val overview: String?,
    val shelf: String,
    val tapes: List<Tape>,
    val tmdbId: Int? = null,        // confirmed or suggested TMDB entry, used to fetch art
    val tmdbType: String = "movie",
    val searchName: String = name,  // what to search TMDB for when there is no TMDB entry yet
    val searchYear: String? = null,
    val confirmed: Boolean = false,  // Brian picked the TMDB match in the review app
    val backdrop: String? = null,
    val genres: List<String> = emptyList(),
    val addedAt: String = "",        // when the newest tape of it was marked good
) {
    /** Fills in TMDB art and blurb; the poster counts as confirmed only when the TMDB match is. */
    fun withArt(a: Art?): Title = if (a == null) this else copy(
        poster = poster ?: a.poster,
        posterIsSuggestion = if (poster != null) posterIsSuggestion else !confirmed,
        overview = overview ?: a.overview,
        year = year ?: a.year,
        backdrop = a.backdrop,
        genres = a.genres,
    )
}

data class Catalog(val titles: List<Title>) {
    /** Newest tapes marked good first. */
    val newArrivals: List<Title> get() = titles.sortedByDescending { it.addedAt }

    /** Each genre with its titles, biggest shelf first. */
    val genreShelves: List<Pair<String, List<Title>>>
        get() = titles.flatMap { it.genres }.distinct()
            .map { g -> g to titles.filter { g in it.genres } }
            .sortedWith(compareByDescending<Pair<String, List<Title>>> { it.second.size }.thenBy { it.first })

    val shelves: List<Pair<String, List<Title>>>
        get() = SHELF_ORDER.mapNotNull { s -> titles.filter { it.shelf == s }.takeIf { it.isNotEmpty() }?.let { s to it } } +
            titles.filter { it.shelf !in SHELF_ORDER }.groupBy { it.shelf }.toList()

    companion object {
        val SHELF_ORDER = listOf("VHS Tapes", "DVDs", "Other Releases", "Openings & Previews", "Extras")

        fun shelfName(id: String?, media: String?): String = when {
            id == "vhs_opening_or_previews" -> "Openings & Previews"
            id == "other_media_release" -> "Other Releases"
            id == "not_catalog" -> "Extras"
            media == "DVD" -> "DVDs"
            else -> "VHS Tapes"
        }

        /** Builds the shelf from the catalog feed and the saved review decisions. */
        fun build(feedText: String, decisionsText: String): Catalog {
            val decisions = JSONObject(decisionsText).optJSONObject("files") ?: JSONObject()
            val good = HashMap<String, JSONObject>()
            for (k in decisions.keys()) {
                val d = decisions.optJSONObject(k) ?: continue
                if (d.optString("status") == "good") good[k] = d
            }
            val feed = JSONObject(feedText)
            val found = mutableListOf<Triple<String, Tape, Meta>>()

            for (t in feed.optJSONArray("titles").objs()) {
                // For now any TMDB suggestion is used for art (marked as a suggestion on screen).
                val candidate = t.optJSONObject("tmdb")?.optJSONArray("c")?.optJSONObject(0)
                    ?.takeIf { it.optInt("tmdb_id") > 0 }
                val recs = t.optJSONArray("un").objs().map { null to it } +
                    t.optJSONArray("eds").objs().flatMap { e -> e.optJSONArray("recs").objs().map { e to it } }
                for ((ed, rec) in recs) {
                    for (seg in rec.optJSONArray("segs").objs()) {
                        // Include files a web browser can't play (MKV, MPEG-2, AVI): the TV player may still manage them.
                        val srcs = seg.optJSONArray("src").objs()
                        for (src in srcs) {
                            val id = src.optString("id")
                            val d = good[id] ?: continue
                            val movie = d.optJSONObject("movie")
                            val release = d.optJSONObject("release")
                            val media = release?.str("media") ?: ed?.str("media")
                            val year = release?.str("year") ?: ed?.str("year")
                            val shelf = shelfName(d.str("shelf") ?: rec.str("cat"), media)
                            val tape = Tape(
                                fileId = id,
                                label = tapeLabel(media, year, rec.str("ver"), rec.optJSONArray("notes").strings() +
                                    listOfNotNull(release?.str("notes"))),
                                fileName = src.optString("n"),
                                archiveItem = src.optString("item"),
                                minutes = src.optDouble("dur").takeIf { !it.isNaN() && it > 0 }?.let { Math.round(it / 60).toInt() },
                                sources = orderSources(id, srcs),
                                media = media?.takeIf { it != "TAPE_UNSPECIFIED" },
                                releaseYear = year,
                                addedAt = d.optString("updated_at"),
                            )
                            val meta = if (movie != null && movie.optString("title").isNotBlank()) Meta(
                                key = "tmdb:" + movie.optInt("tmdb_id"), name = movie.optString("title"),
                                year = movie.str("year"), poster = movie.str("poster"), suggested = false,
                                overview = null, shelf = shelf,
                                tmdbId = movie.optInt("tmdb_id").takeIf { it > 0 }, tmdbType = movie.optString("type", "movie"),
                            ) else Meta(
                                key = "title:" + t.optString("id"), name = candidate?.str("title") ?: cleanName(t.optString("t")),
                                year = candidate?.str("release_date")?.take(4), poster = candidate?.str("poster"),
                                suggested = true, overview = candidate?.str("overview"), shelf = shelf,
                                tmdbId = candidate?.optInt("tmdb_id"), tmdbType = candidate?.str("media_type") ?: "movie",
                                feedName = t.optString("t"),
                            )
                            found += Triple(t.optString("id"), tape, meta)
                        }
                    }
                }
            }

            // A movie confirmed on one tape applies to the other tapes the catalog grouped with it.
            val confirmed = HashMap<String, Meta>()
            for ((tid, _, m) in found) if (m.key.startsWith("tmdb:")) confirmed.putIfAbsent(tid, m)
            val groups = LinkedHashMap<String, MutableList<Pair<Tape, Meta>>>()
            for ((tid, tape, m) in found) {
                val meta = if (m.key.startsWith("title:")) confirmed[tid]?.copy(shelf = m.shelf) ?: m else m
                groups.getOrPut(meta.key) { mutableListOf() } += tape to meta
            }

            val titles = groups.values.map { list ->
                val metas = list.map { it.second }
                val best = metas.firstOrNull { !it.suggested && it.poster != null } ?: metas.first()
                Title(
                    key = best.key, name = best.name, year = best.year,
                    poster = metas.firstNotNullOfOrNull { it.poster },
                    posterIsSuggestion = metas.none { !it.suggested && it.poster != null },
                    overview = metas.firstNotNullOfOrNull { it.overview },
                    shelf = metas.groupingBy { it.shelf }.eachCount().maxBy { it.value }.key,
                    tapes = list.map { it.first }.distinctBy { it.fileId }.sortedWith(compareBy({ it.label }, { it.fileName })),
                    tmdbId = (metas.firstOrNull { !it.suggested && it.tmdbId != null } ?: metas.firstOrNull { it.tmdbId != null })?.tmdbId,
                    tmdbType = (metas.firstOrNull { !it.suggested && it.tmdbId != null } ?: metas.firstOrNull { it.tmdbId != null })?.tmdbType ?: "movie",
                    confirmed = metas.any { !it.suggested },
                    addedAt = list.maxOf { it.first.addedAt },
                    searchName = searchQuery(metas.firstNotNullOfOrNull { it.feedName } ?: best.name).first,
                    // Tape years are release years of the tape, not the film, so they are not used here.
                    searchYear = searchQuery(metas.firstNotNullOfOrNull { it.feedName } ?: best.name).second,
                )
            }.sortedBy { sortKey(it.name) }
            return Catalog(titles)
        }

        private data class Meta(
            val key: String, val name: String, val year: String?, val poster: String?,
            val suggested: Boolean, val overview: String?, val shelf: String,
            val tmdbId: Int? = null, val tmdbType: String = "movie",
            val feedName: String? = null,
        )

        fun tapeLabel(media: String?, year: String?, version: String?, notes: List<String>): String {
            val m = media?.takeIf { it != "TAPE_UNSPECIFIED" } ?: if (media == "TAPE_UNSPECIFIED") "Tape" else null
            val parts = mutableListOf<String>()
            listOfNotNull(year, m).joinToString(" ").takeIf { it.isNotBlank() }?.let { parts += it }
            version?.takeIf { it.isNotBlank() }?.let { parts += "Version $it" }
            parts += notes.filter { it.isNotBlank() }.distinct()
            return parts.joinToString(" · ").ifBlank { "Tape" }
        }

        /** The file Brian marked first; then Archive's own MP4 copies; Ogg copies last. */
        fun orderSources(markedId: String, srcs: List<JSONObject>): List<Source> {
            fun rank(o: JSONObject): Int {
                if (o.optString("id") == markedId) return 0
                val n = o.optString("n").lowercase()
                return when {
                    n.endsWith(".mp4") || n.endsWith(".m4v") -> 1
                    n.endsWith(".mkv") || n.endsWith(".webm") -> 2
                    n.endsWith(".ogv") -> 4
                    else -> 3
                }
            }
            return srcs.sortedBy { rank(it) }.map {
                Source(it.optString("id"), it.optString("n"), it.optString("url"), it.optString("fmt"),
                    it.optString("role") == "archive_derivative")
            }.distinctBy { it.url }
        }

        /** "0 JeffLynnesEloHydeParkHq mp4" -> "0 JeffLynnesEloHydeParkHq"; keeps the rest as written. */
        fun cleanName(s: String): String =
            s.replace(Regex("\\s+(mp4|mkv|avi|mpg|ia)\\s*$", RegexOption.IGNORE_CASE), "").trim().ifBlank { s }

        /**
         * A TMDB search from a catalog name: drops "(1992, UK VHS)", "[UPSCALED]", track numbers like "02 ",
         * a trailing "new"; a leading year ("1996 Sonic…") becomes the search year.
         */
        fun searchQuery(name: String): Pair<String, String?> {
            var q = name.replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
            q = q.replace(Regex("^0\\d\\s+"), "").replace(Regex("\\s+new\\s*$", RegexOption.IGNORE_CASE), "")
            var year: String? = null
            Regex("^((?:19|20)\\d\\d)\\s+(.+)$").find(q.trim())?.let { m -> year = m.groupValues[1]; q = m.groupValues[2] }
            return q.replace(Regex("\\s+"), " ").trim().trimEnd('.', '-', ' ').ifBlank { name } to year
        }

        fun sortKey(name: String): String =
            name.lowercase().removePrefix("the ").removePrefix("a ").removePrefix("an ").trim()
    }
}

private fun JSONObject.str(k: String): String? =
    if (isNull(k)) null else optString(k).takeIf { it.isNotBlank() && it != "null" }

private fun JSONArray?.objs(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { opt(it) as? String }
