package org.vhsfever.review

import org.json.JSONArray
import org.json.JSONObject

/** One video file of a VHS movie, from the movie list the bot publishes. */
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
    val titleId: String,
    val titleName: String,          // cleaned-up name (TMDB's name when the bot is sure)
    val rawName: String,            // the name as the bot read it from the file
    val editionId: String?,
    val releaseYear: String?,       // year the tape came out
    val editionNotes: List<String>,
    val tmdbCeiling: Int?,
    val suggestions: List<Movie>,
    val botMovie: Movie?,           // the movie the bot is sure about, if any
    val guess: Movie?,              // TMDB's best guess when the bot isn't sure
    val genres: List<String>,
    val botSure: Boolean,           // the bot put this file on the shelves by itself
    val adult: Boolean = false,     // adult tape: only shown behind the red curtain
    val tapeName: String? = null,   // the bot's name for the tape this file is ("1992 VHS · Full screen")
    val tapeRank: Int? = null,      // the bot's order for the movie: lowest plays first
    var copyIds: List<String> = emptyList(),   // Archive copies made from this file
    var originalId: String? = null,            // for a copy: the file it was made from
) {
    val isCopy get() = role == "archive_derivative"
    val ext get() = name.substringAfterLast('.', "").lowercase()
    val phonePlayable get() = ext in setOf("mp4", "m4v", "mkv", "webm", "mov", "3gp", "ogv", "ts")
}

data class Movie(
    val tmdbId: Int,
    val type: String,       // always "movie" now; kept so older saved reviews still read
    val title: String,
    val year: String?,
    val poster: String?,
    val overview: String? = null,
    val imdbId: String? = null,
    val tooNew: Boolean = false,
    val source: String = "tmdb",    // tmdb | manual (named by the reviewer) | none (reviewer said no match)
    val genres: List<String> = emptyList(),
) {
    /** The reviewer said nothing on TMDB matches this file. */
    val isNoMatch get() = source == "none"
    /** The reviewer typed the name in themselves. */
    val isManual get() = source == "manual"
    val heading get() = if (isNoMatch) "No match" else title + (year?.let { " ($it)" } ?: "")
    val pageUrl get() = if (isNoMatch || isManual) null else "https://www.themoviedb.org/movie/$tmdbId"
    val key get() = when (source) {
        "manual", "none" -> "$source:$title:$year"
        else -> "$source:$tmdbId"
    }

    fun toJson(): JSONObject = JSONObject().put("tmdb_id", tmdbId).put("type", type).put("title", title)
        .put("year", year).put("poster", poster).put("imdb_id", imdbId).put("source", source)
        .put("genres", JSONArray(genres))

    companion object {
        /** "Nothing matches": saved as a real choice so the bot's suggestion stops coming back. */
        fun noMatch() = Movie(0, "movie", "", null, null, source = "none")

        /** A name the reviewer typed in for a movie that isn't on TMDB. */
        fun manual(title: String, year: String?) =
            Movie(0, "movie", title.trim(), year?.trim()?.ifBlank { null }, null, source = "manual")

        fun fromJson(o: JSONObject?): Movie? = o?.let {
            Movie(it.optInt("tmdb_id"), it.optString("type", "movie"), it.optString("title"),
                it.optStringOrNull("year"), it.optStringOrNull("poster"), null, it.optStringOrNull("imdb_id"),
                source = it.optString("source", "tmdb"), genres = it.optJSONArray("genres").strings())
        }

        /** The bot's movie info (tmdb_id, title, year, genres, poster, overview). */
        fun fromBot(o: JSONObject?): Movie? = o?.takeIf { it.optInt("tmdb_id") > 0 }?.let {
            Movie(it.optInt("tmdb_id"), "movie", it.optString("title"), it.optStringOrNull("year"),
                it.optStringOrNull("poster"), it.optStringOrNull("overview"), genres = it.optJSONArray("genres").strings())
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

/**
 * What the reviewer decided about one file. Anything else an older version of the app saved
 * (shelves, kinds, tags) is kept as it was in [extra], so nothing saved before is lost.
 */
data class FileDecision(
    val status: String? = null,            // good | bad
    val reasons: List<String> = emptyList(),
    val otherReason: String = "",
    val notes: String = "",
    val movie: Movie? = null,
    val release: ReleaseInfo? = null,
    val updatedAt: String = "",
    /** "agreed": took the bot's call on this file; "agreed_all": part of "agree with all". */
    val robot: String? = null,
    /** Brian chose this copy to play first on the TV. */
    val playFirst: Boolean = false,
    /** Brian put this movie on the Manager's Picks shelf. */
    val managerPick: Boolean = false,
    val extra: JSONObject? = null,
) {
    fun toJson(): JSONObject {
        val o = JSONObject(extra?.toString() ?: "{}")
        return o.put("status", status).put("reasons", JSONArray(reasons))
            .put("other_reason", otherReason).put("notes", notes)
            .put("movie", movie?.toJson()).put("release", release?.toJson()).put("updated_at", updatedAt)
            .put("robot", robot).apply { if (playFirst) put("play_first", true) else remove("play_first") }
            .apply { if (managerPick) put("manager_pick", true) else remove("manager_pick") }
    }

    companion object {
        private val OWN = setOf("status", "reasons", "other_reason", "notes", "movie", "release", "updated_at", "robot", "play_first", "manager_pick")

        fun fromJson(o: JSONObject): FileDecision {
            val extra = JSONObject()
            o.keys().forEach { k -> if (k !in OWN) extra.put(k, o.get(k)) }
            return FileDecision(
                o.optStringOrNull("status"), o.optJSONArray("reasons").strings(), o.optString("other_reason", ""),
                o.optString("notes", ""), Movie.fromJson(o.optJSONObject("movie")),
                ReleaseInfo.fromJson(o.optJSONObject("release")), o.optString("updated_at", ""),
                o.optStringOrNull("robot"), o.optBoolean("play_first"), o.optBoolean("manager_pick"), extra.takeIf { it.length() > 0 })
        }
    }
}

/** The bot's call for one file (from auto-sort.jsonl). */
data class RobotGuess(
    val verdict: String,          // good (bot is sure) | bad | review
    val reason: String?,          // bad reason, in the app's own words
    val why: List<String>,
    val formatConfidence: String,
    val formatClues: List<String>,
) {
    val label get() = when (verdict) { "good" -> "Sure it's good"; "bad" -> "Bad" + (reason?.let { ": $it" } ?: ""); else -> "Not sure" }
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
            o.optString("verdict"), o.optStringOrNull("reason"), o.optJSONArray("verdict_why").strings(),
            o.optString("format_confidence"), o.optJSONArray("format_clues").strings())
    }
    return m
}

/** Changes made for every file of one tape release ("apply to all"). */
data class ReleaseDecision(val movie: Movie? = null, val release: ReleaseInfo? = null, val updatedAt: String = "") {
    fun toJson(): JSONObject = JSONObject().put("movie", movie?.toJson()).put("release", release?.toJson())
        .put("updated_at", updatedAt)

    companion object {
        fun fromJson(o: JSONObject) = ReleaseDecision(Movie.fromJson(o.optJSONObject("movie")),
            ReleaseInfo.fromJson(o.optJSONObject("release")), o.optString("updated_at", ""))
    }
}

data class AudioCheck(val verdict: String, val hasAudio: Boolean?, val width: Int?, val height: Int?)

val BAD_REASONS = listOf("Not a movie", "Not VHS", "Cut off / partial", "No audio", "No video", "Out of sync",
    "Wrong movie", "Foreign language", "Poor quality", "Won’t play", "Duplicate")

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

/** Flatten the movie list (Movie -> Tape release -> Recording -> File) into reviewable files. */
fun parseFeed(text: String): List<FileItem> {
    val root = JSONObject(text)
    val out = ArrayList<FileItem>(2000)
    for (t in root.getJSONArray("titles").objects()) {
        val tm = t.optJSONObject("tmdb") ?: JSONObject()
        val ceiling = if (tm.isNull("ceiling")) null else tm.optInt("ceiling")
        val suggestions = tm.optJSONArray("c").objects()
            .filter { it.optString("media_type", "movie") == "movie" && it.optInt("tmdb_id") > 0 }
            .map { c ->
                Movie(c.optInt("tmdb_id"), "movie", c.optString("title"),
                    c.optStringOrNull("release_date")?.take(4), c.optStringOrNull("poster"), c.optStringOrNull("overview"),
                    null, c.has("year_consistent") && !c.isNull("year_consistent") && !c.optBoolean("year_consistent"))
            }
        val botMovie = Movie.fromBot(t.optJSONObject("movie"))
        val guess = Movie.fromBot(t.optJSONObject("guess"))
        val genres = t.optJSONArray("genres").strings()
        val adult = t.optBoolean("adult")
        fun addRec(r: JSONObject, edId: String?, year: String?, notes: List<String>) {
            for (s in r.optJSONArray("segs").objects()) {
                val files = s.optJSONArray("src").objects().map { x ->
                    FileItem(
                        id = x.optString("id").ifEmpty { x.optString("item") + "/" + x.optString("n") },
                        name = x.optString("n"), archiveItem = x.optString("item"), role = x.optString("role"),
                        format = x.optStringOrNull("fmt"), url = x.optString("url"),
                        durationSec = if (x.isNull("dur")) null else x.optDouble("dur"),
                        recordingId = r.optString("id"), versionLabel = r.optStringOrNull("ver"),
                        titleId = t.optString("id"), titleName = t.optString("t"), rawName = t.optString("raw", t.optString("t")),
                        editionId = edId, releaseYear = year, editionNotes = notes,
                        tmdbCeiling = ceiling, suggestions = suggestions, botMovie = botMovie, guess = guess,
                        genres = genres, botSure = x.optBoolean("ok"), adult = adult,
                        tapeName = x.optStringOrNull("lab"), tapeRank = x.optIntOrNull("pk"),
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
                addRec(r, e.optString("id"), e.optStringOrNull("year"), e.optJSONArray("notes").strings())
        }
        for (r in t.optJSONArray("un").objects()) addRec(r, null, null, emptyList())
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
