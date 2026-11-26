package org.vhsfever.review

import org.json.JSONArray
import org.json.JSONObject

/** One video file the user reviews. Built from the published reviewer feed. */
data class FileItem(
    val id: String,
    val name: String,
    val archiveItem: String,
    val role: String,               // original | unverified_original | archive_derivative
    val format: String?,
    val url: String,
    val durationSec: Double?,
    val recordingId: String,
    val versionLabel: String?,
    val partLabel: String?,         // "Part 3 of 20"
    val modelShelf: String,         // shelf suggested by the catalog build
    val modelReasons: List<String>,
    val titleId: String,
    val titleName: String,
    val editionId: String?,
    val media: String?,
    val releaseYear: String?,
    val editionNotes: List<String>,
    val tmdbStatus: String?,
    val tmdbCeiling: Int?,
    val suggestions: List<Movie>,
    val tv: TvHint? = null,             // what the file name says about a TV episode
    var copyIds: List<String> = emptyList(),   // Archive copies made from this file
    var originalId: String? = null,            // for a copy: the file it was made from
) {
    val isCopy get() = role == "archive_derivative"
    val ext get() = name.substringAfterLast('.', "").lowercase()
    val phonePlayable get() = ext in setOf("mp4", "m4v", "mkv", "webm", "mov", "3gp", "ogv", "ts")
}

/** What the file name says about a TV episode (from the catalog build). */
data class TvHint(
    val show: String?,          // null when the file name has no show name
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val airDate: String?,
    val fromDash: Boolean,      // "Show Name - Episode Name"
)

/** One episode of a show, from TMDB or TheTVDB. */
data class Episode(
    val season: Int?,
    val number: Int?,
    val name: String,
    val airDate: String? = null,
    val still: String? = null,
    val overview: String? = null,
    val url: String? = null,
    val basis: List<String> = emptyList(),   // why it was suggested
    val absolute: Int? = null,
) {
    val code get() = (season?.let { "S%02d".format(it) } ?: "") + (number?.let { "E%02d".format(it) } ?: "")
    val label get() = listOf(code, name).filter { it.isNotBlank() }.joinToString(" · ")

    fun toJson(): JSONObject = JSONObject().put("season", season).put("episode", number).put("name", name)
        .put("air_date", airDate).put("still", still).put("url", url)

    companion object {
        fun fromJson(o: JSONObject?): Episode? = o?.let {
            Episode(it.optIntOrNull("season"), it.optIntOrNull("episode"), it.optString("name"),
                it.optStringOrNull("air_date"), it.optStringOrNull("still"), it.optStringOrNull("overview"),
                it.optStringOrNull("url"), it.optJSONArray("basis").strings())
        }
    }
}

data class Movie(
    val tmdbId: Int,
    val type: String,       // movie | tv
    val title: String,
    val year: String?,
    val poster: String?,
    val overview: String? = null,
    val imdbId: String? = null,
    val tooNew: Boolean = false,
    val episode: Episode? = null,   // set when this is one episode of a show
    val source: String = "tmdb",    // tmdb | tvdb | manual (named by the reviewer) | none (reviewer said no match)
    val tvdbId: Int? = null,
) {
    val isEpisode get() = episode != null
    /** The reviewer said nothing on TMDB or TheTVDB matches this file. */
    val isNoMatch get() = source == "none"
    /** The reviewer typed the name in themselves. */
    val isManual get() = source == "manual"
    /** Shown name: "Show (1994)", or "Show · S02E15 · Episode name". */
    val heading get() = if (isNoMatch) "No match" else title + (year?.let { " ($it)" } ?: "")
    val pageUrl get() = when {
        isNoMatch || isManual -> null
        source == "tvdb" && tvdbId != null -> episode?.url ?: "https://thetvdb.com/dereferrer/series/$tvdbId"
        episode?.url != null && source == "tmdb" -> episode.url
        else -> "https://www.themoviedb.org/$type/$tmdbId"
    }
    val key get() = when (source) {
        "tvdb" -> "tvdb:$tvdbId"
        "manual", "none" -> "$source:$title:$year"
        else -> "$source:$tmdbId"
    }

    fun toJson(): JSONObject = JSONObject().put("tmdb_id", tmdbId).put("type", type).put("title", title)
        .put("year", year).put("poster", poster).put("imdb_id", imdbId).put("source", source)
        .put("tvdb_id", tvdbId).put("episode", episode?.toJson())

    companion object {
        /** "Nothing matches": saved as a real choice so the robot's suggestion stops coming back. */
        fun noMatch() = Movie(0, "movie", "", null, null, source = "none")

        /** A name the reviewer typed in for something that isn't on TMDB or TheTVDB. */
        fun manual(title: String, year: String?, type: String = "movie") =
            Movie(0, type, title.trim(), year?.trim()?.ifBlank { null }, null, source = "manual")

        fun fromJson(o: JSONObject?): Movie? = o?.let {
            Movie(it.optInt("tmdb_id"), it.optString("type", "movie"), it.optString("title"),
                it.optStringOrNull("year"), it.optStringOrNull("poster"), null, it.optStringOrNull("imdb_id"),
                episode = Episode.fromJson(it.optJSONObject("episode")), source = it.optString("source", "tmdb"),
                tvdbId = it.optIntOrNull("tvdb_id"))
        }
    }
}

data class ReleaseInfo(
    val media: String? = null, val year: String? = null, val region: String? = null,
    val distributor: String? = null, val notes: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("media", media).put("year", year).put("region", region)
        .put("distributor", distributor).put("notes", notes)

    companion object {
        fun fromJson(o: JSONObject?): ReleaseInfo? = o?.let {
            ReleaseInfo(it.optStringOrNull("media"), it.optStringOrNull("year"), it.optStringOrNull("region"),
                it.optStringOrNull("distributor"), it.optStringOrNull("notes"))
        }
    }
}

/** What the user decided about one file. */
data class FileDecision(
    val status: String? = null,            // good | bad
    val reasons: List<String> = emptyList(),
    val otherReason: String = "",
    val shelf: String? = null,
    val tags: List<String> = emptyList(),
    val notes: String = "",
    val movie: Movie? = null,
    val release: ReleaseInfo? = null,
    val updatedAt: String = "",
    /** "agreed": tapped Agree on this file; "agreed_all": part of "agree with all shown". */
    val robot: String? = null,
    /** What sort of video it is (one of [BUCKETS]), picked or confirmed by the reviewer. */
    val kind: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("status", status).put("reasons", JSONArray(reasons))
        .put("other_reason", otherReason).put("shelf", shelf).put("tags", JSONArray(tags)).put("notes", notes)
        .put("movie", movie?.toJson()).put("release", release?.toJson()).put("updated_at", updatedAt)
        .put("robot", robot).put("kind", kind)

    companion object {
        fun fromJson(o: JSONObject) = FileDecision(
            o.optStringOrNull("status"), o.optJSONArray("reasons").strings(), o.optString("other_reason", ""),
            o.optStringOrNull("shelf"), o.optJSONArray("tags").strings(), o.optString("notes", ""),
            Movie.fromJson(o.optJSONObject("movie")), ReleaseInfo.fromJson(o.optJSONObject("release")),
            o.optString("updated_at", ""), o.optStringOrNull("robot"), o.optStringOrNull("kind"))
    }
}

/** The kinds of video found so far. Same ids the auto-sort uses. */
data class Bucket(val id: String, val label: String)

/** Every kind, grouped the way you'd find them on a thrift-store shelf. Shown in full under "More…". */
val BUCKET_GROUPS: List<Pair<String, List<Bucket>>> = listOf(
    "Movies" to listOf(Bucket("movie", "Movie"), Bucket("tv_movie", "Made-for-TV movie"),
        Bucket("direct_to_video", "Direct-to-video"), Bucket("foreign_film", "Foreign film"), Bucket("classic_film", "Classic film")),
    "Kids" to listOf(Bucket("short", "Short / cartoon"), Bucket("kids_show", "Kids’ show"),
        Bucket("animated_feature", "Animated movie"), Bucket("kids_learning", "Kids’ learning")),
    "TV" to listOf(Bucket("tv_episode", "TV episode"), Bucket("tv_recording", "Off-air TV"),
        Bucket("tv_special", "TV special / miniseries")),
    "Fitness & how-to" to listOf(Bucket("instructional", "How-to"), Bucket("workout", "Workout"), Bucket("hobby", "Hobby lessons")),
    "Real life" to listOf(Bucket("documentary", "Documentary / nature"), Bucket("travel", "Travel / scenery"),
        Bucket("news", "News"), Bucket("sports", "Sports"), Bucket("sports_bloopers", "Sports bloopers"),
        Bucket("standup", "Stand-up comedy"), Bucket("bloopers", "Funny videos")),
    "Music" to listOf(Bucket("music", "Music"), Bucket("karaoke", "Karaoke")),
    "Faith & info" to listOf(Bucket("religious", "Religious"), Bucket("infomercial", "Infomercial / sales"),
        Bucket("educational", "Training / industrial"), Bucket("school", "School film")),
    "Personal" to listOf(Bucket("home_video", "Home video"), Bucket("recital", "Recital / event")),
    "Tape bits" to listOf(Bucket("extra", "Bonus / extra"), Bucket("tape_opening", "Tape opening"), Bucket("trailer", "Trailer"),
        Bucket("commercial", "Commercial"), Bucket("commercial_compilation", "Ad reel"),
        Bucket("promo_or_bump", "Promo / bump"), Bucket("clip", "Clip / piece")),
)

val BUCKETS: List<Bucket> = BUCKET_GROUPS.flatMap { it.second }

/** Always on the review screen (3 rows of 4). Everything else is one tap away under "More…". */
val QUICK_BUCKETS = listOf("movie", "tv_episode", "tv_recording", "short", "kids_show", "music",
    "instructional", "sports", "home_video", "commercial", "tape_opening", "trailer")

/**
 * The 4th row: whatever this file needs that isn't already up top (your pick, the robot's
 * guess), then the kinds you picked most recently, then "More…" in the last spot.
 */
fun extraBuckets(picked: String?, robot: String?, recent: List<String>, slots: Int = 3): List<String> =
    (listOfNotNull(picked, robot) + recent)
        .filter { it != "unknown" && it !in QUICK_BUCKETS && BUCKETS.any { b -> b.id == it } }
        .distinct().take(slots)

fun bucketLabel(id: String?) = BUCKETS.firstOrNull { it.id == id }?.label ?: id?.replace('_', ' ')?.replaceFirstChar { it.uppercase() } ?: "?"

/** Release formats, in the same words as the catalog (VHS, DVD, BLU-RAY, ...). */
val FORMATS = listOf("VHS" to "VHS", "DVD" to "DVD", "BLU-RAY" to "Blu-ray", "TV" to "TV", "WEB" to "Web", "LASERDISC" to "LaserDisc")

/** Which shelf a kind of video goes on, given its format. */
fun shelfForBucket(kind: String?, format: String?): String = when (kind) {
    "tape_opening" -> "vhs_opening_or_previews"
    "home_video", "recital" -> "home_video"
    "commercial", "commercial_compilation", "promo_or_bump", "trailer", "clip", "extra" -> "not_catalog"
    "tv_recording" -> "tv_recording"
    else -> when (format?.uppercase()) {
        "VHS", "BETAMAX", "TAPE_UNSPECIFIED" -> "vhs_release"
        "TV", "BROADCAST" -> "tv_recording"
        "DVD", "BLU-RAY", "WEB", "LASERDISC", "VCD" -> "other_media_release"
        else -> "manual_review"
    }
}

/** The auto-sort's best guess for one file (from auto-sort.jsonl). */
data class RobotGuess(
    val verdict: String,          // good | bad | review
    val reason: String?,          // bad reason, in the app's own words
    val confidence: String,       // high | medium | low
    val why: List<String>,
    val kind: String,             // movie, tv_episode, commercial, clip, ...
    val kindWhy: List<String>,
    val format: String,           // VHS, DVD, BLU-RAY, TV, unknown, ...
    val formatConfidence: String,
    val formatClues: List<String>,
    val shelf: String?,
    val commercialsInside: String?,
) {
    val label get() = when (verdict) { "good" -> "Good"; "bad" -> "Bad" + (reason?.let { ": $it" } ?: ""); else -> "Unsure" }
    val kindLabel get() = bucketLabel(kind)
}

fun parseRobot(text: String): Map<String, RobotGuess> = parseRobotLines(text.lineSequence())

/** Reads the file line by line, so a big list never has to sit in memory as one piece of text. */
fun parseRobotFile(f: java.io.File): Map<String, RobotGuess> = f.bufferedReader().useLines { parseRobotLines(it) }

fun parseAudioFile(f: java.io.File): Map<String, AudioCheck> = f.bufferedReader().useLines { parseAudioLines(it) }

private fun parseRobotLines(lines: Sequence<String>): Map<String, RobotGuess> {
    val m = HashMap<String, RobotGuess>()
    lines.filter { it.isNotBlank() }.forEach { line ->
        val o = JSONObject(line)
        m[o.optString("file_id")] = RobotGuess(
            o.optString("verdict"), o.optStringOrNull("reason"), o.optString("confidence"),
            o.optJSONArray("verdict_why").strings(), o.optString("kind"), o.optJSONArray("kind_why").strings(),
            o.optString("format"), o.optString("format_confidence"), o.optJSONArray("format_clues").strings(),
            o.optStringOrNull("shelf"), o.optStringOrNull("builtin_commercials"))
    }
    return m
}

/** Changes made for every file of one release ("apply to all"). */
data class ReleaseDecision(val movie: Movie? = null, val release: ReleaseInfo? = null, val updatedAt: String = "") {
    fun toJson(): JSONObject = JSONObject().put("movie", movie?.toJson()).put("release", release?.toJson())
        .put("updated_at", updatedAt)

    companion object {
        fun fromJson(o: JSONObject) = ReleaseDecision(Movie.fromJson(o.optJSONObject("movie")),
            ReleaseInfo.fromJson(o.optJSONObject("release")), o.optString("updated_at", ""))
    }
}

data class Shelf(val id: String, val name: String, val builtIn: Boolean)

data class AudioCheck(val verdict: String, val hasAudio: Boolean?, val width: Int?, val height: Int?)

val BUILT_IN_SHELVES = listOf(
    Shelf("vhs_release", "VHS release", true),
    Shelf("other_media_release", "Other media", true),
    Shelf("vhs_opening_or_previews", "Opening / previews", true),
    Shelf("tv_recording", "TV recording", true),
    Shelf("home_video", "Home video", true),
    Shelf("manual_review", "Manual review", true),
    Shelf("not_catalog", "Not catalog", true),
)

val BAD_REASONS = listOf("Clip", "No audio", "No video", "Out of sync", "Cuts off early", "Wrong movie", "Foreign language", "Poor quality", "Won’t play")

val SOUND_PROBLEMS = setOf("no_audio_track", "silent_audio", "could_not_open", "no_video_track")

fun soundLabel(v: String?): String? = when (v) {
    "no_audio_track" -> "No audio track"
    "silent_audio" -> "Silent audio"
    "no_video_track" -> "No picture"
    "could_not_open" -> "Couldn’t open"
    "ok" -> "Sound OK"
    else -> null
}

fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k) || !has(k)) null else optString(k).ifEmpty { null }
fun JSONObject.optIntOrNull(k: String): Int? = if (isNull(k) || !has(k)) null else optInt(k)
fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

/** Flatten the nested Movie -> Release -> Recording -> File feed into reviewable files. */
fun parseFeed(text: String): List<FileItem> {
    val root = JSONObject(text)
    val out = ArrayList<FileItem>(13000)
    for (t in root.getJSONArray("titles").objects()) {
        val tm = t.optJSONObject("tmdb") ?: JSONObject()
        val ceiling = if (tm.isNull("ceiling")) null else tm.optInt("ceiling")
        val suggestions = tm.optJSONArray("c").objects().map { c ->
            Movie(c.optInt("tmdb_id"), c.optString("media_type", "movie"), c.optString("title"),
                c.optStringOrNull("release_date")?.take(4), c.optStringOrNull("poster"), c.optStringOrNull("overview"),
                null, c.has("year_consistent") && !c.isNull("year_consistent") && !c.optBoolean("year_consistent"),
                episode = Episode.fromJson(c.optJSONObject("episode")), source = c.optString("source", "tmdb"),
                tvdbId = c.optIntOrNull("tvdb_id"))
        }
        val tv = t.optJSONObject("series")?.let { se ->
            TvHint(se.optStringOrNull("series_title"), se.optIntOrNull("season"), se.optIntOrNull("episode"),
                se.optStringOrNull("episode_title"), se.optStringOrNull("air_date"), false)
        } ?: t.optJSONObject("dash")?.let { d ->
            TvHint(d.optStringOrNull("show"), null, null, d.optStringOrNull("episode_title"), null, true)
        }
        fun addRec(r: JSONObject, edId: String?, media: String?, year: String?, notes: List<String>) {
            val segs = r.optJSONArray("segs").objects()
            for (s in segs) {
                val part = if (segs.size > 1) "Part ${s.optInt("o")} of ${segs.size}" else null
                val files = s.optJSONArray("src").objects().map { x ->
                    FileItem(
                        id = x.optString("id").ifEmpty { x.optString("item") + "/" + x.optString("n") },
                        name = x.optString("n"), archiveItem = x.optString("item"), role = x.optString("role"),
                        format = x.optStringOrNull("fmt"), url = x.optString("url"),
                        durationSec = if (x.isNull("dur")) null else x.optDouble("dur"),
                        recordingId = r.optString("id"), versionLabel = r.optStringOrNull("ver"), partLabel = part,
                        modelShelf = r.optString("cat", "manual_review"), modelReasons = r.optJSONArray("why").strings(),
                        titleId = t.optString("id"), titleName = t.optString("t"),
                        editionId = edId, media = media, releaseYear = year, editionNotes = notes,
                        tmdbStatus = tm.optStringOrNull("status"), tmdbCeiling = ceiling, suggestions = suggestions,
                        tv = tv,
                    )
                }
                val originals = files.filter { !it.isCopy }
                val copies = files.filter { it.isCopy }
                originals.firstOrNull()?.let { o ->
                    o.copyIds = copies.map { it.id }
                    copies.forEach { it.originalId = o.id }
                }
                out.addAll(files)
            }
        }
        for (e in t.optJSONArray("eds").objects()) {
            for (r in e.optJSONArray("recs").objects())
                addRec(r, e.optString("id"), e.optStringOrNull("media"), e.optStringOrNull("year"), e.optJSONArray("notes").strings())
        }
        for (r in t.optJSONArray("un").objects()) addRec(r, null, null, null, emptyList())
    }
    return out
}

fun parseAudio(text: String): Map<String, AudioCheck> = parseAudioLines(text.lineSequence())

private fun parseAudioLines(lines: Sequence<String>): Map<String, AudioCheck> {
    val m = HashMap<String, AudioCheck>()
    lines.filter { it.isNotBlank() }.forEach { line ->
        val o = JSONObject(line)
        val key = o.optString("file_id")
        m[key] = AudioCheck(o.optString("verdict"), if (o.has("has_audio_track")) o.optBoolean("has_audio_track") else null,
            if (o.isNull("width")) null else o.optInt("width"), if (o.isNull("height")) null else o.optInt("height"))
    }
    return m
}
