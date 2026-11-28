package org.vhsfever.review

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

sealed interface Screen {
    data object Queue : Screen
    data class Review(val fileId: String) : Screen
    data class MovieEdit(val fileId: String) : Screen
    data object Settings : Screen
    data object BoxArt : Screen
    data class BoxArtPick(val key: String) : Screen
    data class BoxAlign(val key: String) : Screen
    /** Mark the spine on one picture (id), often the front's or the back's own scan. */
    data class BoxSpine(val key: String, val id: String) : Screen
}

enum class QueueMode(val label: String) {
    REVIEW("To review"), SURE("Bot sure"), DONE("Done"), ALL("All"),
    /** Adult tapes. Only there while the curtain is open; never in the other lists. */
    CURTAIN("Behind the curtain")
}

class AppModel(private val ctx: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = ctx.getSharedPreferences("vhsfever", Context.MODE_PRIVATE)
    /** The one movie list, published by the "Find VHS movies" bot. */
    private val list = "https://raw.githubusercontent.com/${BuildConfig.REPO}/movies-data"
    private val feedRaw = "$list/reviewer-feed.json"
    private val audioRaw = "$list/audio-checks.jsonl"
    private val robotRaw = "$list/auto-sort.jsonl"
    private val summaryRaw = "$list/summary.json"

    // ---- loaded data
    var files by mutableStateOf<List<FileItem>>(emptyList()); private set
    var byId: Map<String, FileItem> = emptyMap(); private set
    var audio by mutableStateOf<Map<String, AudioCheck>>(emptyMap()); private set
    /** The bot's call per file. */
    var robot by mutableStateOf<Map<String, RobotGuess>>(emptyMap()); private set
    var loadStatus by mutableStateOf("Loading movies…"); private set
    /** "Built Nov 25, 7:02 PM · 6,500 Archive pages read". */
    var listInfo by mutableStateOf<String?>(prefs.getString("movies_list_info", null)); private set
    /** Set while a newer list is being downloaded. What's on screen may be old until it's done. */
    var updating by mutableStateOf<String?>(null); private set
    /** Why the newest list couldn't be loaded, shown until it works. */
    var updateError by mutableStateOf<String?>(null); private set
    private var lastCheck = 0L
    private var loadJob: Job? = null

    // ---- decisions (local copy is the source of truth on the phone)
    var decisions by mutableStateOf<Map<String, FileDecision>>(emptyMap()); private set
    var releaseDecisions by mutableStateOf<Map<String, ReleaseDecision>>(emptyMap()); private set
    /** Settings an older version of the app saved (its shelves and tags), kept as they were. */
    private var oldSettings: JSONObject = JSONObject()
    var dirty by mutableStateOf(false); private set
    var syncStatus by mutableStateOf("Not synced yet"); private set
    /** False until saved reviews have been checked against GitHub once after opening the app. */
    var firstSyncDone by mutableStateOf(false); private set
    private var syncJob: Job? = null

    // ---- navigation & queue
    val stack = mutableStateListOf<Screen>(Screen.Queue)
    var mode by mutableStateOf(QueueMode.REVIEW)
    var search by mutableStateOf("")
    /** Hide files that look non-English (on by default). */
    var englishOnly by mutableStateOf(prefs.getBoolean("english_only", true)); private set
    /** Archive item -> its language tags, read from archive.org. */
    var itemLanguages by mutableStateOf<Map<String, List<String>>>(emptyMap()); private set
    /** File id -> why it looks non-English. Files missing here look English. */
    var foreign by mutableStateOf<Map<String, String>>(emptyMap()); private set
    private val undo = ArrayDeque<Pair<String, FileDecision?>>()
    var lastUndoLabel by mutableStateOf<String?>(null)

    // ---- the red curtain (adult tapes)
    /** Is a curtain PIN set on this phone? */
    var curtainPinSet by mutableStateOf(!prefs.getString("curtain_pin", null).isNullOrEmpty()); private set
    /** Open only until the app is closed; never saved. */
    var curtainOpen by mutableStateOf(false); private set

    /** Sets the PIN the first time, and opens the curtain. */
    fun setCurtainPin(pin: String): String? {
        if (!pin.matches(Regex("\\d{4,8}"))) return "Use 4 to 8 numbers."
        prefs.edit().putString("curtain_pin", pin).apply()
        curtainPinSet = true
        curtainOpen = true
        return null
    }

    fun openCurtain(pin: String): Boolean {
        val ok = pin == prefs.getString("curtain_pin", null)
        if (ok) curtainOpen = true
        return ok
    }

    fun closeCurtain() {
        curtainOpen = false
        if (mode == QueueMode.CURTAIN) mode = QueueMode.REVIEW
        // Leave any adult tape that's open on screen.
        while (stack.size > 1 && stack.last().let { s -> (s as? Screen.Review)?.fileId ?: (s as? Screen.MovieEdit)?.fileId }
                ?.let { byId[it]?.adult } == true) stack.removeAt(stack.lastIndex)
    }

    /** Forget the PIN (needs the old one), so a new one can be set. */
    fun clearCurtainPin(pin: String): Boolean {
        if (pin != prefs.getString("curtain_pin", null)) return false
        prefs.edit().remove("curtain_pin").apply()
        curtainPinSet = false
        closeCurtain()
        return true
    }

    /** The lists to show as chips: the curtain only while it's open. */
    fun modes(): List<QueueMode> = QueueMode.entries.filter { it != QueueMode.CURTAIN || curtainOpen }

    var token by mutableStateOf(prefs.getString("github_token", "") ?: "")
        private set

    /** The tapes on file for a box art entry ("tmdb:603" or "title:…"), for telling versions apart. */
    fun tapesFor(key: String): List<FileItem> {
        val tmdb = key.removePrefix("tmdb:").toIntOrNull().takeIf { key.startsWith("tmdb:") }
        val title = key.removePrefix("title:").takeIf { key.startsWith("title:") }
        return files.filter { f ->
            if (f.isCopy) return@filter false
            if (title != null) return@filter f.titleId == title
            val chosen = decisions[f.id]?.movie
            (chosen?.tmdbId ?: f.botMovie?.tmdbId ?: f.guess?.tmdbId) == tmdb && decisions[f.id]?.status != "bad"
        }.sortedBy { it.name }
    }

    /** Box art: what the finder bot found for each movie, and Brian's picks. */
    val boxArt = BoxArtModel(scope) { token }
    var githubUser by mutableStateOf(prefs.getString("github_user", "") ?: "")
        private set
    var updateStatus by mutableStateOf<String?>(null)
    var pendingUpdate by mutableStateOf<GitHub.Update?>(null)

    init {
        loadLocalDecisions()
        loadJob = scope.launch { loadCatalog() }
        if (token.isNotBlank()) scope.launch { sync(); checkForUpdate(quiet = true) } else firstSyncDone = true
    }

    // ------------------------------------------------------------------ loading

    private fun stampLabel(builtAt: String?, pages: Int?): String? {
        val t = builtAt?.let { runCatching {
            java.time.format.DateTimeFormatter.ofPattern("MMM d, h:mm a")
                .format(Instant.parse(it).atZone(java.time.ZoneId.systemDefault()))
        }.getOrNull() }
        return listOfNotNull(t?.let { "Built $it" }, pages?.let { "%,d Archive pages read".format(it) })
            .joinToString(" · ").ifEmpty { null }
    }

    private suspend fun loadCatalog(force: Boolean = false) {
        val feedCache = File(ctx.filesDir, "movies-feed.json")
        val audioCache = File(ctx.filesDir, "movies-audio.jsonl")
        val robotCache = File(ctx.filesDir, "movies-bot.jsonl")
        lastCheck = System.currentTimeMillis()
        // 1. Show what's saved on the phone right away.
        if (files.isEmpty()) withContext(Dispatchers.IO) {
            if (feedCache.exists()) runCatching { publish(parseFeed(feedCache.readText())) }
            if (audioCache.exists()) runCatching { val a = parseAudioFile(audioCache); withContext(Dispatchers.Main) { audio = a } }
            if (robotCache.exists()) runCatching { val r = parseRobotFile(robotCache); withContext(Dispatchers.Main) { robot = r } }
        }
        loadStatus = if (files.isEmpty()) "Downloading the movie list…" else ""
        // 2. Is there a newer list on GitHub? (summary.json says when it was built.)
        val fresh = "?t=" + (System.currentTimeMillis() / 60000)
        var builtAt: String? = null
        var pages: Int? = null
        runCatching {
            val info = JSONObject(withContext(Dispatchers.IO) { Http.request(summaryRaw + fresh, timeoutMs = 20000) })
            builtAt = info.optStringOrNull("built_at"); pages = info.optIntOrNull("pages_read")
        }
        if (!force && builtAt != null && builtAt == prefs.getString("movies_built_at", null) && files.isNotEmpty()) {
            updateError = null
            if (files.isNotEmpty()) loadStatus = ""
            return   // already showing the newest list
        }
        // 3. Download all three files side by side, check they read correctly, then switch over at once.
        updating = "Getting the newest movie list…"
        try {
            val result = withContext(Dispatchers.IO) {
                val jobs = listOf(feedRaw to feedCache, robotRaw to robotCache, audioRaw to audioCache).map { (u, dest) ->
                    async {
                        val part = File(dest.path + ".part")
                        runCatching { Http.download(u + fresh, emptyMap(), part); part }
                    }
                }
                val (feedPart, robotPart, audioPart) = jobs.map { it.await() }
                val newFiles = parseFeed(feedPart.getOrThrow().readText())
                val newRobot = parseRobotFile(robotPart.getOrThrow())
                val newAudio = audioPart.map { parseAudioFile(it) }.getOrNull()
                feedPart.getOrNull()?.renameTo(feedCache); robotPart.getOrNull()?.renameTo(robotCache); audioPart.getOrNull()?.renameTo(audioCache)
                Triple(newFiles, newRobot, newAudio)
            }
            publish(result.first)
            robot = result.second
            result.third?.let { audio = it }
            listInfo = stampLabel(builtAt, pages)
            prefs.edit().putString("movies_built_at", builtAt).putString("movies_list_info", listInfo).apply()
            updateError = null
            loadItemLanguages()
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val why = when (e) {
                is OutOfMemoryError -> "the phone ran out of memory reading it"
                is java.net.SocketTimeoutException -> "the download timed out"
                is java.net.UnknownHostException -> "no internet connection"
                else -> e.message?.take(80) ?: e.javaClass.simpleName
            }
            updateError = "Couldn’t get the newest list: $why"
            if (files.isEmpty()) loadStatus = "Couldn’t download the movie list: $why"
        } finally {
            updating = null
            if (files.isNotEmpty()) loadStatus = ""
        }
    }

    /** Check for a newer list when the app comes back to the front (at most once a minute). */
    fun refreshIfStale() {
        if (loadJob?.isActive == true || System.currentTimeMillis() - lastCheck < 60_000) return
        loadJob = scope.launch { loadCatalog() }
    }

    fun reloadCatalog() {
        if (loadJob?.isActive == true) return
        loadJob = scope.launch { loadCatalog(force = true) }
    }

    private suspend fun publish(list: List<FileItem>) {
        withContext(Dispatchers.Main) {
            files = list
            byId = list.associateBy { it.id }
        }
        recomputeLanguage()
    }

    // ------------------------------------------------------------------ language
    private val langCache get() = File(ctx.filesDir, "item-languages.json")

    fun changeEnglishOnly(on: Boolean) { englishOnly = on; prefs.edit().putBoolean("english_only", on).apply() }

    fun foreignReason(f: FileItem): String? = foreign[f.id]

    private suspend fun recomputeLanguage() {
        val list = files
        val langs = itemLanguages
        val out = withContext(Dispatchers.Default) {
            val m = HashMap<String, String>()
            for (f in list) Language.foreignReason(f.name, langs[f.archiveItem] ?: emptyList())?.let { m[f.id] = it }
            m
        }
        withContext(Dispatchers.Main) { foreign = out }
    }

    /** Reads each Archive item's language tag (cached on the phone, refreshed in the background). */
    private suspend fun loadItemLanguages() {
        withContext(Dispatchers.IO) {
            if (itemLanguages.isEmpty() && langCache.exists()) runCatching {
                val o = JSONObject(langCache.readText())
                val m = o.keys().asSequence().associateWith { k -> o.optJSONArray(k).strings() }
                withContext(Dispatchers.Main) { itemLanguages = m }
                recomputeLanguage()
            }
            val ids = files.map { it.archiveItem }.distinct().filter { it !in itemLanguages }
            if (ids.isEmpty()) return@withContext
            val fetched = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
            val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
            try {
                ids.map { id ->
                    pool.submit {
                        runCatching {
                            val text = Http.request("https://archive.org/metadata/${java.net.URLEncoder.encode(id, "UTF-8")}/metadata/language", timeoutMs = 20000)
                            val r = JSONObject(text).opt("result")
                            fetched[id] = when (r) {
                                is JSONArray -> r.strings()
                                is String -> r.split(';', ',').map { it.trim() }.filter { it.isNotEmpty() }
                                else -> emptyList()
                            }
                        }
                    }
                }.forEach { runCatching { it.get() } }
            } finally { pool.shutdown() }
            if (fetched.isEmpty()) return@withContext
            val merged = itemLanguages + fetched
            val j = JSONObject(); merged.forEach { (k, v) -> j.put(k, JSONArray(v)) }
            runCatching { langCache.writeText(j.toString()) }
            withContext(Dispatchers.Main) { itemLanguages = merged }
            recomputeLanguage()
        }
    }

    // ------------------------------------------------------------------ the bot's calls
    fun robotFor(f: FileItem): RobotGuess? = robot[f.id] ?: f.originalId?.let { robot[it] }

    /** Take the bot's call for one file. Files it isn't sure about have nothing to agree with. */
    fun agreeWithRobot(fileId: String): Boolean {
        val f = byId[fileId] ?: return false
        val g = robotFor(f) ?: return false
        if (g.verdict != "good" && g.verdict != "bad") return false
        val shown = if (g.verdict == "good") shownMovieToSave(f) else null
        update(fileId, "Agreed with the bot") {
            it.copy(status = g.verdict, movie = it.movie ?: shown, reasons = if (g.verdict == "bad") listOfNotNull(g.reason) else emptyList(),
                otherReason = if (g.verdict == "bad" && g.reason == null) "bot: " + g.why.firstOrNull().orEmpty() else "",
                robot = "agreed")
        }
        return true
    }

    // ------------------------------------------------------------------ effective values
    /** A copy follows its original unless it was reviewed itself. */
    fun decisionFor(f: FileItem): FileDecision? = decisions[f.id] ?: f.originalId?.let { decisions[it] }

    /** The movie: your pick, else one picked for the whole tape, else the bot's. Second = confirmed by you. */
    fun movieFor(f: FileItem): Pair<Movie?, Boolean> {
        decisionFor(f)?.movie?.let { return it to true }
        f.editionId?.let { releaseDecisions[it]?.movie }?.let { return it to true }
        f.botMovie?.let { return it to false }
        val s = f.guess?.takeIf { g -> f.suggestions.firstOrNull()?.tmdbId == g.tmdbId }
            ?: f.suggestions.firstOrNull { !it.tooNew }
        return s to false
    }

    /** Genres for the movie on screen: the bot's, the guess's, or what was saved with your pick. */
    fun genresFor(f: FileItem): List<String> {
        val (mv, _) = movieFor(f)
        return when {
            mv == null || mv.isNoMatch || mv.isManual -> emptyList()
            mv.genres.isNotEmpty() -> mv.genres
            f.botMovie?.tmdbId == mv.tmdbId -> f.botMovie.genres
            f.guess?.tmdbId == mv.tmdbId -> f.guess.genres
            else -> emptyList()
        }
    }

    fun releaseFor(f: FileItem): ReleaseInfo {
        decisionFor(f)?.release?.let { return it }
        f.editionId?.let { releaseDecisions[it]?.release }?.let { return it }
        return ReleaseInfo("VHS", f.releaseYear, null, null, f.editionNotes.joinToString("; ").ifEmpty { null })
    }

    fun soundFor(f: FileItem): AudioCheck? = audio[f.id]

    /** The movie on screen, if it's only the bot's guess: saved with "good" so the box finder and TV see it too. */
    private fun shownMovieToSave(f: FileItem): Movie? {
        val (mv, confirmed) = movieFor(f)
        if (confirmed || mv == null || mv.tmdbId <= 0) return null
        return mv.copy(genres = genresFor(f))
    }

    fun markGood(f: FileItem, notes: String) {
        val shown = shownMovieToSave(f)
        update(f.id, "Marked good") { it.copy(status = "good", reasons = emptyList(), otherReason = "", notes = notes,
            movie = it.movie ?: shown) }
    }

    /**
     * Play this copy first on the TV (or stop doing that). One copy per movie: choosing one
     * clears the others. Choosing a copy also marks it good, since it has to be on the shelf.
     */
    fun togglePlayFirst(f: FileItem) {
        val on = decisionFor(f)?.playFirst != true
        if (on) {
            val movie = movieFor(f).first?.tmdbId
            files.filter { it.id != f.id && decisions[it.id]?.playFirst == true }
                .filter { o -> if (movie != null) movieFor(o).first?.tmdbId == movie else o.titleId == f.titleId }
                .forEach { o -> update(o.id) { it.copy(playFirst = false) } }
        }
        val shown = shownMovieToSave(f)
        update(f.id, if (on) "Play first" else "Not first") {
            if (on) it.copy(playFirst = true, status = "good", reasons = emptyList(), otherReason = "", movie = it.movie ?: shown)
            else it.copy(playFirst = false)
        }
    }

    /** Whether this file's movie is on the Manager's Picks shelf (any copy of it counts). */
    fun isManagerPick(f: FileItem): Boolean {
        if (decisions[f.id]?.managerPick == true) return true
        val movie = movieFor(f).first?.tmdbId ?: return false
        return files.any { it.id != f.id && decisions[it.id]?.managerPick == true && movieFor(it).first?.tmdbId == movie }
    }

    /**
     * Put this movie on the Manager's Picks shelf (or take it off). Putting it there marks this copy
     * good, since it has to be on the shelf; taking it off clears every copy of the movie.
     */
    fun toggleManagerPick(f: FileItem) {
        val on = !isManagerPick(f)
        if (!on) {
            val movie = movieFor(f).first?.tmdbId
            files.filter { it.id != f.id && decisions[it.id]?.managerPick == true }
                .filter { o -> if (movie != null) movieFor(o).first?.tmdbId == movie else o.titleId == f.titleId }
                .forEach { o -> update(o.id) { it.copy(managerPick = false) } }
        }
        val shown = shownMovieToSave(f)
        update(f.id, if (on) "Manager's pick" else "Not a pick") {
            if (on) it.copy(managerPick = true, status = "good", reasons = emptyList(), otherReason = "", movie = it.movie ?: shown)
            else it.copy(managerPick = false)
        }
    }

    fun markBad(f: FileItem, reasons: List<String>, other: String, notes: String) {
        update(f.id, "Marked bad") { it.copy(status = "bad", reasons = reasons, otherReason = other, notes = notes, playFirst = false, managerPick = false) }
    }

    /** Copies are hidden unless their sound/picture check differs from the original's. */
    private fun visibleInQueue(f: FileItem, inList: QueueMode = mode): Boolean {
        // Adult tapes only ever show in the curtain list, and only while it's open.
        if (f.adult != (inList == QueueMode.CURTAIN) || (f.adult && !curtainOpen)) return false
        if (englishOnly && f.id in foreign) return false
        if (!f.isCopy) return true
        val orig = f.originalId?.let { byId[it] } ?: return true
        val a = audio[f.id]?.verdict
        val b = audio[orig.id]?.verdict
        return a != null && b != null && a != b
    }

    private fun inMode(f: FileItem, mode: QueueMode): Boolean {
        val d = decisionFor(f)?.status
        val sure = robotFor(f)?.verdict == "good"
        return when (mode) {
            QueueMode.REVIEW -> d == null && !sure
            QueueMode.SURE -> d == null && sure
            QueueMode.DONE -> d != null
            QueueMode.ALL -> true
            QueueMode.CURTAIN -> f.adult && curtainOpen
        }
    }

    private val order = compareBy<FileItem>({ it.titleName.lowercase() }, { it.titleId }, { it.releaseYear ?: "9999" }, { it.name })

    fun queue(): List<FileItem> {
        val q = search.trim().lowercase()
        return files.asSequence().filter { visibleInQueue(it) }.filter { inMode(it, mode) }
            .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.titleName.lowercase().contains(q) || it.archiveItem.lowercase().contains(q) }
            .sortedWith(order).toList()
    }

    fun count(mode: QueueMode): Int = files.count { visibleInQueue(it, mode) && inMode(it, mode) }

    fun progress(): Triple<Int, Int, Int> {
        val visible = files.filter { visibleInQueue(it) }
        val good = visible.count { decisionFor(it)?.status == "good" }
        val bad = visible.count { decisionFor(it)?.status == "bad" }
        return Triple(good, bad, visible.size)
    }

    // ------------------------------------------------------------------ editing
    private fun now() = Instant.now().toString()

    fun update(fileId: String, label: String? = null, change: (FileDecision) -> FileDecision) {
        val old = decisions[fileId]
        val base = old ?: byId[fileId]?.originalId?.let { decisions[it] }?.copy(updatedAt = "") ?: FileDecision()
        val edited = change(base)
        // "Added" is when the tape was first marked good; a pick, play-first or any other edit keeps it.
        val added = if (edited.status != "good") "" else
            edited.addedAt.ifBlank { base.addedAt.ifBlank { if (base.status == "good") base.updatedAt else "" } }.ifBlank { now() }
        val new = edited.copy(updatedAt = now(), addedAt = added)
        if (label != null) {
            undo.addLast(fileId to old)
            while (undo.size > 20) undo.removeFirst()
            lastUndoLabel = label
        }
        decisions = decisions + (fileId to new)
        changed()
    }

    fun undoLast(): String? {
        val (id, prev) = undo.removeLastOrNull() ?: return null
        decisions = if (prev == null) decisions - id else decisions + (id to prev.copy(updatedAt = now()))
        // a removed decision must also be removed on GitHub: keep an empty marker
        if (prev == null) decisions = decisions + (id to FileDecision(updatedAt = now()))
        lastUndoLabel = undo.lastOrNull()?.let { "previous change" }
        changed()
        return id
    }

    fun setReleaseDecision(editionId: String, movie: Movie?, release: ReleaseInfo?) {
        releaseDecisions = releaseDecisions + (editionId to ReleaseDecision(movie, release, now()))
        changed()
    }

    private fun changed() {
        dirty = true
        saveLocal()
        scheduleSync()
    }

    // ------------------------------------------------------------------ navigation
    fun open(s: Screen) { stack.add(s) }
    fun back(): Boolean { if (stack.size <= 1) return false; stack.removeAt(stack.lastIndex); return true }
    fun replaceTop(s: Screen) { stack[stack.lastIndex] = s }

    /** Next file in the current list after [fileId], skipping ones already decided. */
    fun nextAfter(fileId: String, onlyUnreviewed: Boolean = true): FileItem? {
        val q = files.asSequence().filter { visibleInQueue(it) }
            .filter { mode == QueueMode.ALL || mode == QueueMode.DONE || mode == QueueMode.CURTAIN || inMode(it, mode) || it.id == fileId }
            .sortedWith(order).toList()
        val i = q.indexOfFirst { it.id == fileId }
        return q.drop(i + 1).firstOrNull { !onlyUnreviewed || decisionFor(it)?.status == null }
    }

    fun previousBefore(fileId: String): FileItem? {
        val q = queue().ifEmpty { files }
        val i = q.indexOfFirst { it.id == fileId }
        return if (i > 0) q[i - 1] else null
    }

    fun siblingsCount(f: FileItem): Pair<Int, Int> {
        val same = files.filter { it.titleId == f.titleId && visibleInQueue(it) }
        return (same.indexOfFirst { it.id == f.id } + 1) to same.size
    }

    // ------------------------------------------------------------------ local storage
    private val localFile get() = File(ctx.filesDir, "decisions.json")

    private fun toJson(): JSONObject {
        val filesJ = JSONObject(); decisions.forEach { (k, v) -> filesJ.put(k, v.toJson()) }
        val relJ = JSONObject(); releaseDecisions.forEach { (k, v) -> relJ.put(k, v.toJson()) }
        return JSONObject().put("schema", "vhsfever-review-decisions-v2").put("files", filesJ).put("releases", relJ)
            .put("settings", oldSettings)
    }

    private data class Parsed(val files: Map<String, FileDecision>, val releases: Map<String, ReleaseDecision>,
                              val settings: JSONObject)

    private fun parse(text: String): Parsed {
        val o = JSONObject(text)
        val f = HashMap<String, FileDecision>()
        o.optJSONObject("files")?.let { j -> j.keys().forEach { k -> f[k] = FileDecision.fromJson(j.getJSONObject(k)) } }
        val r = HashMap<String, ReleaseDecision>()
        o.optJSONObject("releases")?.let { j -> j.keys().forEach { k -> r[k] = ReleaseDecision.fromJson(j.getJSONObject(k)) } }
        return Parsed(f, r, o.optJSONObject("settings") ?: JSONObject())
    }

    private fun loadLocalDecisions() {
        val text = listOf(localFile, File(ctx.filesDir, "decisions.json.bak"))
            .firstNotNullOfOrNull { f -> runCatching { f.readText().also { parse(it) } }.getOrNull() } ?: return
        runCatching {
            val p = parse(text)
            decisions = p.files; releaseDecisions = p.releases; oldSettings = p.settings
            dirty = prefs.getBoolean("dirty", false)
        }
    }

    private val saveLock = Any()

    /** Writes one save at a time, through a temp file, so a save can never leave a half-written file behind. */
    private fun saveLocal() {
        val text = toJson().toString()
        scope.launch(Dispatchers.IO) {
            synchronized(saveLock) {
                val tmp = File(ctx.filesDir, "decisions.json.tmp")
                tmp.writeText(text)
                if (localFile.exists()) localFile.copyTo(File(ctx.filesDir, "decisions.json.bak"), overwrite = true)
                if (!tmp.renameTo(localFile)) { localFile.writeText(text); tmp.delete() }
            }
        }
        prefs.edit().putBoolean("dirty", dirty).apply()
    }

    // ------------------------------------------------------------------ GitHub sync
    private fun scheduleSync() {
        if (token.isBlank()) { syncStatus = "Saved on this phone · add GitHub in Settings to back up"; return }
        syncJob?.cancel()
        syncJob = scope.launch { delay(8000); sync() }
    }

    fun syncNow() { syncJob?.cancel(); scope.launch { sync() } }

    suspend fun sync() {
        if (token.isBlank()) return
        syncStatus = "Saving to GitHub…"
        val gh = GitHub(token)
        try {
            repeat(3) { attempt ->
                val remote = withContext(Dispatchers.IO) { gh.readDecisions() }
                val merged = if (remote == null) toJson().toString() else mergeWith(parse(remote.first))
                if (remote != null && !dirty && merged == remote.first) {
                    saveLocal()   // keep reviews made on other devices on this phone too
                    syncStatus = "Up to date · ${clock()}"; return
                }
                try {
                    withContext(Dispatchers.IO) { gh.writeDecisions(merged, remote?.second) }
                    dirty = false; saveLocal()
                    syncStatus = "Saved to GitHub · ${clock()}"
                    return
                } catch (e: HttpError) {
                    if (e.code != 409 && e.code != 422 || attempt == 2) throw e   // someone else saved first: re-read
                }
            }
        } catch (e: Exception) {
            syncStatus = "Not saved to GitHub yet (${e.message?.take(60)}) · kept on phone"
        } finally {
            firstSyncDone = true
        }
    }

    /** Newest change wins, per file and per release. Returns the merged JSON text. */
    private fun mergeWith(remote: Parsed): String {
        val f = HashMap(remote.files)
        decisions.forEach { (k, v) -> val r = f[k]; if (r == null || v.updatedAt >= r.updatedAt) f[k] = v }
        val r = HashMap(remote.releases)
        releaseDecisions.forEach { (k, v) -> val o = r[k]; if (o == null || v.updatedAt >= o.updatedAt) r[k] = v }
        decisions = f; releaseDecisions = r
        if (remote.settings.length() > 0) oldSettings = remote.settings
        return toJson().toString()
    }

    private fun clock() = java.time.LocalTime.now().withNano(0).withSecond(0).toString()

    fun saveToken(t: String, onDone: (String?) -> Unit) {
        scope.launch {
            val clean = t.trim()
            val result = withContext(Dispatchers.IO) { runCatching { GitHub(clean).whoAmI() } }
            result.onSuccess { login ->
                token = clean; githubUser = login
                prefs.edit().putString("github_token", clean).putString("github_user", login).apply()
                onDone(null); sync(); checkForUpdate(quiet = true)
            }.onFailure { onDone("That token didn’t work: ${it.message?.take(80)}") }
        }
    }

    fun signOut() {
        token = ""; githubUser = ""
        prefs.edit().remove("github_token").remove("github_user").apply()
        syncStatus = "Saved on this phone · add GitHub in Settings to back up"
    }

    // ------------------------------------------------------------------ app updates
    fun checkForUpdate(quiet: Boolean = false) {
        if (token.isBlank()) { if (!quiet) updateStatus = "Add your GitHub token first"; return }
        scope.launch {
            if (!quiet) updateStatus = "Checking…"
            val r = withContext(Dispatchers.IO) { runCatching { GitHub(token).findUpdate(BuildConfig.VERSION_CODE) } }
            r.onSuccess { u ->
                pendingUpdate = u
                updateStatus = if (u != null) "Version ${u.runNumber} is ready to install" else if (quiet) null else "You have the latest version"
            }.onFailure { if (!quiet) updateStatus = "Couldn’t check: ${it.message?.take(60)}" }
        }
    }

    fun downloadUpdate(onApk: (File) -> Unit) {
        val u = pendingUpdate ?: return
        scope.launch {
            updateStatus = "Downloading version ${u.runNumber}…"
            val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
            val r = withContext(Dispatchers.IO) { runCatching { GitHub(token).downloadApk(u, dir) } }
            r.onSuccess { updateStatus = "Installing…"; onApk(it) }
                .onFailure { updateStatus = "Download failed: ${it.message?.take(60)}" }
        }
    }

    fun launch(block: suspend CoroutineScope.() -> Unit) = scope.launch(block = block)
}
