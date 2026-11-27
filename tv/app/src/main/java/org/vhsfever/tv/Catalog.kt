package org.vhsfever.tv

import org.json.JSONArray
import java.io.File
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
    val group: String = fileId, // copies of the same recording share this; the bot names it "tg"
    val rank: Int = Int.MAX_VALUE,  // the bot's order: lowest plays first
    val hidden: Boolean = false,    // no sound: only listed when there is no other tape
    val pinnedAt: String? = null,   // Brian chose this copy to play first (when)
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
    val adult: Boolean = false,      // adult tape: only behind the red curtain, never on the shelves
    val box: BoxScan? = null,        // real VHS sleeve scan (videotapes.io), when there is one
    val art: Art? = null,            // everything TMDB knows, for the printed back and spine
    val shots: List<File> = emptyList(),  // screenshots taken from this movie's own tape
    val colors: BoxColors? = null,   // colours picked from the poster
    val posterFrom: String = "TMDB", // where the poster came from: "btttr.cc" first, TMDB as backup
    val kidsLook: Boolean = false,   // made-up box uses the kids' looks (see withKidsLooks)
) {
    /** The one store section this tape sits in (like a real rental store, never two). */
    val section: String by lazy { storeSection(genres) }

    /** Fills in TMDB art and blurb; the poster counts as confirmed only when the TMDB match is. */
    fun withArt(a: Art?): Title = if (a == null) this else copy(
        art = a,
        poster = poster ?: a.poster,
        posterIsSuggestion = if (poster != null) posterIsSuggestion else !confirmed,
        overview = overview ?: a.overview,
        year = year ?: a.year,
        backdrop = backdrop ?: a.backdrop,
        genres = genres.ifEmpty { a.genres },
    )
}

/** [titles] are the shelves; [curtain] holds adult tapes, shown only behind the red curtain. */
data class Catalog(val titles: List<Title>, val curtain: List<Title> = emptyList()) {
    /** Newest tapes marked good first. */
    val newArrivals: List<Title> by lazy { titles.sortedByDescending { it.addedAt } }

    /**
     * Each store section with its titles, biggest first; movies TMDB gives no genre go on "Other", last.
     * A movie is on one shelf only, like a real rental store.
     */
    val genreShelves: List<Pair<String, List<Title>>> by lazy {
        val bySection = titles.groupBy { it.section }
        bySection.filterKeys { it != OTHER }.toList()
            .sortedWith(compareByDescending<Pair<String, List<Title>>> { it.second.size }.thenBy { it.first }) +
            listOfNotNull(bySection[OTHER]?.let { OTHER to it })
    }

    companion object {
        const val OTHER = "Other"

        /** Everything on the shelves is a VHS movie now. */
        const val SHELF = "VHS Movies"

        /** Builds the shelf from the movie list and the saved review decisions. */
        fun build(feedText: String, decisionsText: String): Catalog = build(listOf { feedText }, decisionsText)

        /**
         * Builds the shelf from the movie list and the saved review decisions. A file is on the
         * shelf when Brian marked it good, or when the bot is sure about it ("ok") and Brian
         * hasn't marked it bad. Several lists can be given (tests use this); a file in more
         * than one is only added once.
         */
        fun build(feeds: List<() -> String>, decisionsText: String): Catalog {
            val decisions = JSONObject(decisionsText).optJSONObject("files") ?: JSONObject()
            val good = HashMap<String, JSONObject>()
            val bad = HashSet<String>()
            val pinned = HashMap<String, String>()   // file -> when Brian chose it to play first
            for (k in decisions.keys()) {
                val d = decisions.optJSONObject(k) ?: continue
                when (d.optString("status")) { "good" -> good[k] = d; "bad" -> bad += k }
                if (d.optBoolean("play_first")) pinned[k] = d.optString("updated_at")
            }
            val found = mutableListOf<Triple<String, Tape, Meta>>()
            val seen = HashSet<String>()
            // Every copy of each recording, whether or not it was marked: they are the backups.
            val groupNames = HashMap<String, String>()
            val groupFiles = HashMap<String, MutableList<JSONObject>>()

            for (readFeed in feeds) {
                val feed = JSONObject(readFeed())
                val listDate = feed.optString("generated_at")
                for (t in feed.optJSONArray("titles").objs()) {
                    // For now any TMDB suggestion is used for art (marked as a suggestion on screen).
                    val candidate = t.optJSONObject("tmdb")?.optJSONArray("c")?.optJSONObject(0)
                        ?.takeIf { it.optInt("tmdb_id") > 0 }
                    // The movie the bot is sure about, with its genres.
                    val botMovie = t.optJSONObject("movie")?.takeIf { it.optInt("tmdb_id") > 0 }
                    val feedGenres = t.optJSONArray("genres").strings()
                    val adult = t.optBoolean("adult")
                    val recs = t.optJSONArray("un").objs().map { null to it } +
                        t.optJSONArray("eds").objs().flatMap { e -> e.optJSONArray("recs").objs().map { e to it } }
                    for ((ed, rec) in recs) {
                        for (seg in rec.optJSONArray("segs").objs()) {
                            // Include files a web browser can't play (MKV, MPEG-2, AVI): the TV player may still manage them.
                            val srcs = seg.optJSONArray("src").objs()
                            for (src in srcs) {
                                src.str("tg")?.let { g ->
                                    src.str("lab")?.let { groupNames[g] = it }
                                    if (src.optString("id") !in bad) groupFiles.getOrPut(g) { mutableListOf() } += src
                                }
                            }
                            for (src in srcs) {
                                val id = src.optString("id")
                                // The Archive's own copy is only a backup of the original, not a tape of its own.
                                val hasOriginal = srcs.any { it.optString("role") != "archive_derivative" }
                                val botSure = src.optBoolean("ok") && botMovie != null &&
                                    !(src.optString("role") == "archive_derivative" && hasOriginal)
                                val d = good[id] ?: (if (botSure && id !in bad) JSONObject() else null) ?: continue
                                if (!seen.add(id)) continue
                                val movie = d.optJSONObject("movie")
                                val release = d.optJSONObject("release")
                                val media = release?.str("media") ?: ed?.str("media")
                                val year = release?.str("year") ?: ed?.str("year")
                                val shelf = SHELF
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
                                    addedAt = d.optString("updated_at").ifBlank { listDate },
                                    group = src.str("tg") ?: id,
                                    rank = if (src.has("pk")) src.optInt("pk") else Int.MAX_VALUE,
                                    hidden = src.optBoolean("hide"),
                                    pinnedAt = pinned[id],
                                )
                                val meta = if (adult) Meta(
                                    // Behind the curtain: a plain box with the name. No TMDB art is ever looked up.
                                    key = "adult:" + t.optString("id"),
                                    name = movie?.str("title")?.takeIf { movie.optString("source") != "none" } ?: cleanName(t.optString("t")),
                                    year = movie?.str("year"), poster = null, suggested = false, overview = null, shelf = shelf,
                                    adult = true,
                                ) else if (movie != null && movie.optString("title").isNotBlank()) Meta(
                                    key = "tmdb:" + movie.optInt("tmdb_id"), name = movie.optString("title"),
                                    year = movie.str("year"), poster = movie.str("poster"), suggested = false,
                                    overview = null, shelf = shelf,
                                    tmdbId = movie.optInt("tmdb_id").takeIf { it > 0 }, tmdbType = movie.optString("type", "movie"),
                                    genres = movie.optJSONArray("genres").strings().ifEmpty {
                                        if (botMovie?.optInt("tmdb_id") == movie.optInt("tmdb_id")) feedGenres else emptyList() },
                                ) else if (botMovie != null) Meta(
                                    key = "tmdb:" + botMovie.optInt("tmdb_id"), name = botMovie.str("title") ?: t.optString("t"),
                                    year = botMovie.str("year"), poster = botMovie.str("poster"), suggested = false,
                                    overview = botMovie.str("overview"), shelf = shelf,
                                    tmdbId = botMovie.optInt("tmdb_id"), tmdbType = "movie",
                                    genres = feedGenres, backdrop = botMovie.str("backdrop"),
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
            }

            // A movie confirmed on one tape applies to the other tapes the catalog grouped with it.
            val confirmed = HashMap<String, Meta>()
            for ((tid, _, m) in found) if (m.key.startsWith("tmdb:")) confirmed.putIfAbsent(tid, m)
            val groups = LinkedHashMap<String, MutableList<Pair<Tape, Meta>>>()
            for ((tid, tape, m) in found) {
                val meta = if (m.key.startsWith("title:")) confirmed[tid]?.copy(shelf = m.shelf) ?: m else m
                groups.getOrPut(meta.key) { mutableListOf() } += tape to meta
            }
            // An unmatched copy whose TMDB guess is a movie already on the shelf, under the same
            // name, is the same movie: one box, not two.
            for (k in groups.keys.filter { it.startsWith("title:") }) {
                val list = groups[k] ?: continue
                val m = list.first().second
                val into = m.tmdbId?.let { groups["tmdb:$it"] } ?: continue
                if (sameName(m.name, into.first().second.name)) { into += list; groups.remove(k) }
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
                    tapes = tapesOf(list.map { it.first }.distinctBy { it.fileId }, groupNames, groupFiles),
                    tmdbId = (metas.firstOrNull { !it.suggested && it.tmdbId != null } ?: metas.firstOrNull { it.tmdbId != null })?.tmdbId,
                    tmdbType = (metas.firstOrNull { !it.suggested && it.tmdbId != null } ?: metas.firstOrNull { it.tmdbId != null })?.tmdbType ?: "movie",
                    confirmed = metas.any { !it.suggested },
                    genres = metas.firstOrNull { it.genres.isNotEmpty() }?.genres ?: emptyList(),
                    backdrop = metas.firstNotNullOfOrNull { it.backdrop },
                    addedAt = list.maxOf { it.first.addedAt },
                    searchName = searchQuery(metas.firstNotNullOfOrNull { it.feedName } ?: best.name).first,
                    // Tape years are release years of the tape, not the film, so they are not used here.
                    searchYear = searchQuery(metas.firstNotNullOfOrNull { it.feedName } ?: best.name).second,
                    adult = metas.any { it.adult },
                )
            }.sortedBy { sortKey(it.name) }
            return Catalog(titles.filter { !it.adult }, titles.filter { it.adult })
        }

        private data class Meta(
            val key: String, val name: String, val year: String?, val poster: String?,
            val suggested: Boolean, val overview: String?, val shelf: String,
            val tmdbId: Int? = null, val tmdbType: String = "movie",
            val feedName: String? = null,
            val genres: List<String> = emptyList(),
            val backdrop: String? = null,
            val adult: Boolean = false,
        )

        fun tapeLabel(media: String?, year: String?, version: String?, notes: List<String>): String {
            val m = media?.takeIf { it != "TAPE_UNSPECIFIED" } ?: if (media == "TAPE_UNSPECIFIED") "Tape" else null
            val parts = mutableListOf<String>()
            listOfNotNull(year, m).joinToString(" ").takeIf { it.isNotBlank() }?.let { parts += it }
            version?.takeIf { it.isNotBlank() }?.let { parts += "Version $it" }
            parts += notes.filter { it.isNotBlank() }.distinct()
            return parts.joinToString(" · ").ifBlank { "Tape" }
        }

        /**
         * Copies of the same recording become one tape: named by the bot, with every copy behind it
         * as a backup (the TV tries the next one when a copy won't play). The copy Brian chose to play
         * first leads; then the bot's order. A tape with no sound is only listed when it's the only one.
         * Lists from before the bot named tapes have no groups: then each file is its own tape.
         */
        fun tapesOf(tapes: List<Tape>, names: Map<String, String>, files: Map<String, List<JSONObject>>): List<Tape> {
            val merged = tapes.groupBy { it.group }.map { (g, members) ->
                val ms = members.sortedBy { it.rank }
                val lead = ms.firstOrNull { it.pinnedAt != null } ?: ms.first()
                val backups = files[g].orEmpty().sortedBy { if (it.has("pk")) it.optInt("pk") else Int.MAX_VALUE }
                // backups keep the bot's order (best recording first); Ogg copies go last
                val extra = backups.flatMap { orderSources("", listOf(it)) }.partition { !it.name.lowercase().endsWith(".ogv") }
                val sources = (listOf(lead) + ms.filter { it !== lead }).flatMap { it.sources } + extra.first + extra.second
                lead.copy(
                    label = names[g] ?: lead.label,
                    sources = sources.distinctBy { it.url },
                    pinnedAt = ms.mapNotNull { it.pinnedAt }.maxOrNull(),
                    hidden = ms.all { it.hidden },
                    addedAt = ms.maxOf { it.addedAt },
                )
            }
            val listed = merged.filter { !it.hidden }.ifEmpty { merged }
            return listed.sortedWith(
                compareBy<Tape>({ it.pinnedAt == null }).thenByDescending { it.pinnedAt ?: "" }
                    .thenBy { it.rank }.thenBy { it.label }.thenBy { it.fileName })
        }

        private fun sameName(a: String, b: String): Boolean {
            fun n(s: String) = s.lowercase().replace(Regex("^(the|a|an) "), "").replace(Regex("[^a-z0-9]"), "")
            val x = n(a); val y = n(b)
            return x.isNotEmpty() && y.isNotEmpty() && (x == y || x.contains(y) || y.contains(x))
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
                Source(it.optString("id"), it.optString("n"),
                    it.optString("url").ifBlank { archiveUrl(it.optString("item"), it.optString("n")) }, it.optString("fmt"),
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

        /**
         * The Archive's download link for a file, the same link the bot writes (the TV's movie list leaves
         * it out to stay small). Spaces and other characters are %-encoded; slashes in the name are kept.
         */
        fun archiveUrl(item: String, name: String): String {
            val sb = StringBuilder("https://archive.org/download/").append(item).append('/')
            for (b in name.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xFF
                val ch = c.toChar()
                if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "_.-~/") sb.append(ch)
                else sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
            }
            return sb.toString()
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

/**
 * Where a rental store would file a movie with these TMDB genres, thinking like a Blockbuster clerk:
 * what will the customer feel watching it? TMDB tags a lot of comedies with "Sci-Fi" or "Fantasy"
 * just for the premise (Dude, Where's My Car?, Back to the Future), so those go in Comedy.
 *  - Cartoons go in Animation, kids' and family movies in Family (Home Alone, the Muppets).
 *  - Concert and band tapes go in Music; other documentaries in Documentary.
 *  - Anything scary goes in Horror, even a horror comedy.
 *  - A comedy (Comedy first, or second behind anything but Drama) goes in Comedy:
 *    action comedies, rom-coms, spoofs, stand-up specials.
 *  - Then Western, then Sci-Fi (Star Wars, Total Recall).
 *  - Crime and mysteries go in Thriller, or Action if it's an action movie, or Drama if it's a crime drama.
 *  - War movies go in Action or Drama; Adventure in Action; Romance and History in Drama.
 */
fun storeSection(genres: List<String>): String {
    val g = genres.map { ArtRepo.shortGenre(it) }.filter { it != "TV Movie" }
    val main = g.firstOrNull() ?: return Catalog.OTHER
    val second = g.getOrNull(1)
    return when {
        "Animation" in g -> "Animation"
        "Family" in g || "Kids" in g -> "Family"
        "Music" in g && ("Documentary" in g || g == listOf("Music")) -> "Music"
        "Documentary" in g -> "Documentary"
        "Horror" in g -> "Horror"
        main == "Comedy" || (second == "Comedy" && main != "Drama") -> "Comedy"
        "Western" in g -> "Western"
        "Sci-Fi" in g -> "Sci-Fi"
        main == "Crime" || main == "Mystery" || main == "Thriller" -> when {
            main != "Thriller" && second == "Action" -> "Action"
            "Thriller" in g || main == "Mystery" || "Drama" !in g -> "Thriller"
            else -> "Drama"
        }
        main == "Fantasy" -> if ("Action" in g) "Action" else "Family"
        main == "Adventure" -> if ("Fantasy" in g) "Family" else "Action"
        main == "War" -> if ("Action" in g) "Action" else "Drama"
        main == "Romance" || main == "History" -> "Drama"
        else -> main
    }
}
