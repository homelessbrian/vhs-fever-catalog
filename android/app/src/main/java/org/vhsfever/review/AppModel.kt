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
}

enum class QueueMode(val label: String) { REVIEW("To review"), DONE("Done"), ALL("All") }

class AppModel(private val ctx: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = ctx.getSharedPreferences("vhsfever", Context.MODE_PRIVATE)
    /** Which results to review: "latest" (newest batch) or "main" (the current catalog). */
    var source by mutableStateOf(prefs.getString("source", "latest") ?: "latest"); private set
    private val raw = "https://raw.githubusercontent.com/${BuildConfig.REPO}"
    private val feedRaw get() = if (source == "main") "$raw/catalog-model-data/reviewer-feed.json" else "$raw/batch-data/latest/reviewer-feed.json"
    private val audioRaw get() = if (source == "main") "$raw/audio-data/audio-checks.jsonl" else "$raw/batch-data/latest/audio-checks.jsonl"
    private val robotRaw get() = if (source == "main") "$raw/auto-sort-data/auto-sort.jsonl" else "$raw/batch-data/latest/auto-sort.jsonl"
    private val batchInfoRaw get() = "$raw/batch-data/latest/batch.json"

    // ---- loaded data
    var files by mutableStateOf<List<FileItem>>(emptyList()); private set
    var byId: Map<String, FileItem> = emptyMap(); private set
    var audio by mutableStateOf<Map<String, AudioCheck>>(emptyMap()); private set
    /** The auto-sort's guess per file. */
    var robot by mutableStateOf<Map<String, RobotGuess>>(emptyMap()); private set
    /** Name of the batch being reviewed, when reviewing the latest batch. */
    var batchName by mutableStateOf<String?>(null); private set
    var loadStatus by mutableStateOf("Loading catalog…"); private set

    // ---- decisions (local copy is the source of truth on the phone)
    var decisions by mutableStateOf<Map<String, FileDecision>>(emptyMap()); private set
    var releaseDecisions by mutableStateOf<Map<String, ReleaseDecision>>(emptyMap()); private set
    var customShelves by mutableStateOf<List<Shelf>>(emptyList()); private set
    var tags by mutableStateOf<List<String>>(emptyList()); private set
    private var settingsUpdatedAt = ""
    var dirty by mutableStateOf(false); private set
    var syncStatus by mutableStateOf("Not synced yet"); private set
    /** False until saved reviews have been checked against GitHub once after opening the app. */
    var firstSyncDone by mutableStateOf(false); private set
    private var syncJob: Job? = null

    // ---- navigation & queue
    val stack = mutableStateListOf<Screen>(Screen.Queue)
    var mode by mutableStateOf(QueueMode.REVIEW)
    var shelfFilter by mutableStateOf<String?>(null)
    var tagFilter by mutableStateOf<String?>(null)
    var soundOnly by mutableStateOf(false)
    /** Show only files the robot called good / bad / review (unsure). */
    var robotFilter by mutableStateOf<String?>(null)
    /** Show only one kind of video (a [BUCKETS] id). */
    var kindFilter by mutableStateOf<String?>(null)
    var search by mutableStateOf("")
    /** Hide files that look non-English (on by default). */
    var englishOnly by mutableStateOf(prefs.getBoolean("english_only", true)); private set
    /** Archive item -> its language tags, read from archive.org. */
    var itemLanguages by mutableStateOf<Map<String, List<String>>>(emptyMap()); private set
    /** File id -> why it looks non-English. Files missing here look English. */
    var foreign by mutableStateOf<Map<String, String>>(emptyMap()); private set
    /** Hide videos that are only one piece of a split video (on by default). */
    var wholeOnly by mutableStateOf(prefs.getBoolean("whole_only", true)); private set
    /** File id -> why it looks like one piece of a split video. */
    var split by mutableStateOf<Map<String, String>>(emptyMap()); private set
    private val undo = ArrayDeque<Pair<String, FileDecision?>>()
    var lastUndoLabel by mutableStateOf<String?>(null)

    var token by mutableStateOf(prefs.getString("github_token", "") ?: "")
        private set
    var githubUser by mutableStateOf(prefs.getString("github_user", "") ?: "")
        private set
    var updateStatus by mutableStateOf<String?>(null)
    var pendingUpdate by mutableStateOf<GitHub.Update?>(null)

    val shelves get() = BUILT_IN_SHELVES + customShelves
    fun shelfName(id: String?) = shelves.firstOrNull { it.id == id }?.name ?: (id ?: "—")

    init {
        loadLocalDecisions()
        loadJob = scope.launch { loadCatalog() }
        if (token.isNotBlank()) scope.launch { sync(); checkForUpdate(quiet = true) } else firstSyncDone = true
    }

    // ------------------------------------------------------------------ loading
    /** Which list is on screen: "Batch 5 · built Nov 25, 7:02 PM". */
    var listInfo by mutableStateOf<String?>(null); private set
    /** Set while a newer list is being downloaded. What's on screen may be old until it's done. */
    var updating by mutableStateOf<String?>(null); private set
    /** Why the newest list couldn't be loaded, shown until it works. */
    var updateError by mutableStateOf<String?>(null); private set
    private var lastCheck = 0L
    private var loadJob: Job? = null

    private fun stampLabel(batch: String?, builtAt: String?): String? {
        val b = batch?.removePrefix("batch-")?.trimStart('0')?.let { "Batch $it" }
        val t = builtAt?.let { runCatching {
            java.time.format.DateTimeFormatter.ofPattern("MMM d, h:mm a")
                .format(Instant.parse(it).atZone(java.time.ZoneId.systemDefault()))
        }.getOrNull() }
        return listOfNotNull(b, t?.let { "built $it" }).joinToString(" · ").ifEmpty { null }
    }

    private suspend fun loadCatalog(force: Boolean = false) {
        val prefix = if (source == "main") "" else "latest-"
        val feedCache = File(ctx.filesDir, "${prefix}reviewer-feed.json")
        val audioCache = File(ctx.filesDir, "${prefix}audio-checks.jsonl")
        val robotCache = File(ctx.filesDir, "${prefix}auto-sort.jsonl")
        val stampKey = "${prefix}built_at"
        lastCheck = System.currentTimeMillis()
        // 1. Show what's saved on the phone right away.
        if (files.isEmpty()) withContext(Dispatchers.IO) {
            if (feedCache.exists()) runCatching { publish(parseFeed(feedCache.readText())) }
            if (audioCache.exists()) runCatching { val a = parseAudioFile(audioCache); withContext(Dispatchers.Main) { audio = a } }
            if (robotCache.exists()) runCatching { val r = parseRobotFile(robotCache); withContext(Dispatchers.Main) { robot = r } }
        }
        if (listInfo == null) listInfo = prefs.getString("${prefix}list_info", null)
        loadStatus = if (files.isEmpty()) "Downloading catalog…" else ""
        // 2. Is there a newer list on GitHub? (The batch list says when it was built.)
        var batch: String? = null
        var builtAt: String? = null
        var batches = 0
        if (source != "main") runCatching {
            val info = JSONObject(withContext(Dispatchers.IO) { Http.request("$batchInfoRaw?t=${System.currentTimeMillis() / 60000}", timeoutMs = 20000) })
            batch = info.optStringOrNull("batch"); builtAt = info.optStringOrNull("built_at")
            batches = info.optJSONArray("batches")?.length() ?: 0
        }
        if (!force && builtAt != null && builtAt == prefs.getString(stampKey, null) && files.isNotEmpty() && robot.isNotEmpty()) {
            updateError = null
            return   // already showing the newest list
        }
        // 3. Download all three files side by side, check they read correctly, then switch over at once.
        updating = "Getting the newest list" + (stampLabel(batch, null)?.let { " ($it)" } ?: "") + "…"
        try {
            val result = withContext(Dispatchers.IO) {
                // The minute stamp makes GitHub hand over the fresh copy, not one it saved a few minutes ago.
                val fresh = "?t=" + (System.currentTimeMillis() / 60000)
                val jobs = listOf(feedRaw to feedCache, robotRaw to robotCache, audioRaw to audioCache).map { (u, dest) ->
                    val url = u + fresh
                    async {
                        val part = File(dest.path + ".part")
                        runCatching { Http.download(url, emptyMap(), part); part }
                    }
                }
                val (feedPart, robotPart, audioPart) = jobs.map { it.await() }
                val newFiles = parseFeed(feedPart.getOrThrow().readText())
                val newRobot = robotPart.map { parseRobotFile(it) }.getOrElse { if (source == "main") emptyMap() else throw it }
                val newAudio = audioPart.map { parseAudioFile(it) }.getOrNull()
                feedPart.getOrNull()?.renameTo(feedCache); robotPart.getOrNull()?.renameTo(robotCache); audioPart.getOrNull()?.renameTo(audioCache)
                Triple(newFiles, newRobot, newAudio)
            }
            publish(result.first)
            robot = result.second
            result.third?.let { audio = it }
            val label = stampLabel(batch, builtAt)?.let { if (batches > 1) "$it · $batches batches" else it }
            listInfo = label
            prefs.edit().putString(stampKey, builtAt).putString("${prefix}list_info", label).apply()
            batchName = if (batches > 0) "$batches batch${if (batches == 1) "" else "es"}" else batch
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
            if (files.isEmpty()) loadStatus = "Couldn’t download the catalog: $why"
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

    private suspend fun publish(list: List<FileItem>) {
        withContext(Dispatchers.Main) {
            files = list
            byId = list.associateBy { it.id }
        }
        val s = withContext(Dispatchers.Default) { Split.find(list) }
        withContext(Dispatchers.Main) { split = s }
        recomputeLanguage()
    }

    // ------------------------------------------------------------------ language
    private val langCache get() = File(ctx.filesDir, "item-languages.json")

    fun changeEnglishOnly(on: Boolean) { englishOnly = on; prefs.edit().putBoolean("english_only", on).apply() }

    fun foreignReason(f: FileItem): String? = foreign[f.id]

    fun changeWholeOnly(on: Boolean) { wholeOnly = on; prefs.edit().putBoolean("whole_only", on).apply() }

    fun splitReason(f: FileItem): String? = split[f.id]

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
            val ids = files.map { it.archiveItem }.distinct()
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

    fun reloadCatalog() {
        if (loadJob?.isActive == true) return
        loadJob = scope.launch { loadCatalog(force = true) }
    }

    /** Switch between the newest batch and the current catalog. Reviews are kept either way. */
    fun changeSource(s: String) {
        if (s == source) return
        source = s; prefs.edit().putString("source", s).apply()
        files = emptyList(); byId = emptyMap(); audio = emptyMap(); robot = emptyMap(); split = emptyMap(); batchName = null
        robotFilter = null; shelfFilter = null; tagFilter = null; kindFilter = null; listInfo = null; updateError = null
        loadStatus = "Loading…"
        loadJob?.cancel()
        loadJob = scope.launch { loadCatalog(force = true) }
    }

    fun robotFor(f: FileItem): RobotGuess? = robot[f.id] ?: f.originalId?.let { robot[it] }

    /** Take the robot's call for one file. Unsure files have nothing to agree with. */
    fun agreeWithRobot(fileId: String, bulk: Boolean = false): Boolean {
        val f = byId[fileId] ?: return false
        val g = robotFor(f) ?: return false
        if (g.verdict != "good" && g.verdict != "bad") return false
        update(fileId, if (bulk) null else "Agreed with robot") {
            it.copy(status = g.verdict, kind = it.kind ?: g.kind.takeIf { k -> k != "unknown" }, reasons = if (g.verdict == "bad") listOfNotNull(g.reason) else emptyList(),
                otherReason = if (g.verdict == "bad" && g.reason == null) "robot: " + g.why.firstOrNull().orEmpty() else "",
                robot = if (bulk) "agreed_all" else "agreed")
        }
        return true
    }

    /** "Agree with all shown": the robot's call for every unreviewed file in [list]. */
    fun agreeWithRobotAll(list: List<FileItem>): Int {
        val stamp = now()
        val add = HashMap<String, FileDecision>()
        for (f in list) {
            if (decisionFor(f)?.status != null) continue
            val g = robotFor(f) ?: continue
            if (g.verdict != "good" && g.verdict != "bad") continue
            val base = decisions[f.id] ?: FileDecision()
            add[f.id] = base.copy(status = g.verdict, kind = base.kind ?: g.kind.takeIf { it != "unknown" },
                reasons = if (g.verdict == "bad") listOfNotNull(g.reason) else emptyList(),
                robot = "agreed_all", updatedAt = stamp)
        }
        if (add.isEmpty()) return 0
        decisions = decisions + add
        lastBulk = add.keys.toList()
        changed()
        return add.size
    }

    /** Files changed by the last "agree with all", so it can be taken back. */
    var lastBulk by mutableStateOf<List<String>>(emptyList()); private set

    fun undoBulk() {
        if (lastBulk.isEmpty()) return
        val stamp = now()
        decisions = decisions + lastBulk.associateWith { FileDecision(updatedAt = stamp) }
        lastBulk = emptyList()
        changed()
    }

    // ------------------------------------------------------------------ effective values
    /** A copy follows its original unless it was reviewed itself. */
    fun decisionFor(f: FileItem): FileDecision? = decisions[f.id] ?: f.originalId?.let { decisions[it] }

    fun shelfFor(f: FileItem): String = decisionFor(f)?.shelf ?: when (f.modelShelf) {
        "manual_review" -> if (f.modelReasons.any { it.startsWith("TV episode") }) "tv_recording" else "manual_review"
        else -> f.modelShelf
    }

    fun movieFor(f: FileItem): Pair<Movie?, Boolean> {
        decisionFor(f)?.movie?.let { return it to true }
        f.editionId?.let { releaseDecisions[it]?.movie }?.let { return it to true }
        val s = f.suggestions.firstOrNull { !it.tooNew }
        return s to false
    }

    fun releaseFor(f: FileItem): ReleaseInfo {
        decisionFor(f)?.release?.let { return it }
        f.editionId?.let { releaseDecisions[it]?.release }?.let { return it }
        return ReleaseInfo(f.media, f.releaseYear, null, null, f.editionNotes.joinToString("; ").ifEmpty { null })
    }

    fun soundFor(f: FileItem): AudioCheck? = audio[f.id]

    /** The kind of video: the reviewer's pick, else the robot's guess. */
    fun kindFor(f: FileItem): String? = decisionFor(f)?.kind ?: robotFor(f)?.kind?.takeIf { it != "unknown" }

    /** The release format: the reviewer's pick, else the catalog's, else the robot's guess. */
    fun formatFor(f: FileItem): String? = releaseFor(f).media?.uppercase()?.let { if (it == "BROADCAST") "TV" else it }
        ?: robotFor(f)?.format?.takeIf { it != "unknown" }

    private fun isCustomShelf(id: String?) = id != null && customShelves.any { it.id == id }

    /** One tap on "What is it?": saves the kind and moves the file to the matching shelf. */
    fun setBucket(f: FileItem, kind: String) {
        update(f.id, "Changed kind") {
            it.copy(kind = kind, shelf = if (isCustomShelf(it.shelf)) it.shelf else shelfForBucket(kind, formatFor(f)))
        }
    }

    fun setFormat(f: FileItem, format: String) {
        val rel = releaseFor(f).copy(media = format)
        update(f.id, "Changed format") {
            it.copy(release = rel, shelf = if (isCustomShelf(it.shelf)) it.shelf else shelfForBucket(kindFor(f), format))
        }
    }

    /** Puts the file on one of the user's own shelves (tap again to take it off). */
    fun toggleCustomShelf(f: FileItem, id: String) {
        update(f.id, "Changed shelf") {
            it.copy(shelf = if (it.shelf == id) shelfForBucket(kindFor(f), formatFor(f)) else id)
        }
    }

    fun markGood(f: FileItem, notes: String) {
        update(f.id, "Marked good") {
            it.copy(status = "good", reasons = emptyList(), otherReason = "", notes = notes, kind = it.kind ?: kindFor(f))
        }
    }

    fun markBad(f: FileItem, reasons: List<String>, other: String, notes: String) {
        update(f.id, "Marked bad") {
            it.copy(status = "bad", reasons = reasons, otherReason = other, notes = notes, kind = it.kind ?: kindFor(f))
        }
    }

    /** Copies are hidden unless their sound/picture check differs from the original's. */
    private fun visibleInQueue(f: FileItem): Boolean {
        if (englishOnly && f.id in foreign) return false
        if (wholeOnly && f.id in split) return false
        if (!f.isCopy) return true
        val orig = f.originalId?.let { byId[it] } ?: return true
        val a = audio[f.id]?.verdict
        val b = audio[orig.id]?.verdict
        return a != null && b != null && a != b
    }

    fun queue(): List<FileItem> {
        val q = search.trim().lowercase()
        return files.asSequence().filter { visibleInQueue(it) }.filter { f ->
            val d = decisionFor(f)
            when (mode) {
                QueueMode.REVIEW -> d?.status == null
                QueueMode.DONE -> d?.status != null
                QueueMode.ALL -> true
            }
        }.filter { shelfFilter == null || shelfFor(it) == shelfFilter }
            .filter { tagFilter == null || decisionFor(it)?.tags?.contains(tagFilter) == true }
            .filter { !soundOnly || audio[it.id]?.verdict in SOUND_PROBLEMS }
            .filter { robotFilter == null || robotFor(it)?.verdict == robotFilter }
            .filter { kindFilter == null || kindFor(it) == kindFilter }
            .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.titleName.lowercase().contains(q) || it.archiveItem.lowercase().contains(q) }
            .sortedWith(compareBy({ it.titleName.lowercase() }, { it.titleId }, { it.media != "VHS" }, { it.releaseYear ?: "9999" }, { it.name }))
            .toList()
    }

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
        val new = change(base).copy(updatedAt = now())
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

    fun addShelf(name: String) {
        val clean = name.trim(); if (clean.isEmpty()) return
        val id = "custom_" + clean.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        if (shelves.any { it.id == id }) return
        customShelves = customShelves + Shelf(id, clean, false); settingsChanged()
    }

    fun renameShelf(id: String, name: String) {
        customShelves = customShelves.map { if (it.id == id) it.copy(name = name.trim()) else it }; settingsChanged()
    }

    fun deleteShelf(id: String) {
        customShelves = customShelves.filterNot { it.id == id }
        // files on a deleted shelf go back to the catalog's suggestion
        decisions = decisions.mapValues { (_, d) -> if (d.shelf == id) d.copy(shelf = null, updatedAt = now()) else d }
        if (shelfFilter == id) shelfFilter = null
        settingsChanged()
    }

    fun addTag(name: String) {
        val clean = name.trim(); if (clean.isEmpty() || clean in tags) return
        tags = tags + clean; settingsChanged()
    }

    fun deleteTag(name: String) {
        tags = tags - name
        decisions = decisions.mapValues { (_, d) -> if (name in d.tags) d.copy(tags = d.tags - name, updatedAt = now()) else d }
        if (tagFilter == name) tagFilter = null
        settingsChanged()
    }

    private fun settingsChanged() { settingsUpdatedAt = now(); changed() }

    private fun changed() {
        dirty = true
        saveLocal()
        scheduleSync()
    }

    // ------------------------------------------------------------------ navigation
    fun open(s: Screen) { stack.add(s) }
    fun back(): Boolean { if (stack.size <= 1) return false; stack.removeAt(stack.lastIndex); return true }
    fun replaceTop(s: Screen) { stack[stack.lastIndex] = s }

    /** Next file in the current queue after [fileId], skipping ones already reviewed. */
    fun nextAfter(fileId: String, onlyUnreviewed: Boolean = true): FileItem? {
        val q = files.asSequence().filter { visibleInQueue(it) }
            .filter { shelfFilter == null || shelfFor(it) == shelfFilter }
            .filter { tagFilter == null || decisionFor(it)?.tags?.contains(tagFilter) == true }
            .filter { !soundOnly || audio[it.id]?.verdict in SOUND_PROBLEMS }
            .filter { robotFilter == null || robotFor(it)?.verdict == robotFilter }
            .filter { kindFilter == null || kindFor(it) == kindFilter }
            .sortedWith(compareBy({ it.titleName.lowercase() }, { it.titleId }, { it.media != "VHS" }, { it.releaseYear ?: "9999" }, { it.name }))
            .toList()
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
        val shelvesJ = JSONArray(); customShelves.forEach { shelvesJ.put(JSONObject().put("id", it.id).put("name", it.name)) }
        return JSONObject().put("schema", "vhsfever-review-decisions-v2").put("files", filesJ).put("releases", relJ)
            .put("settings", JSONObject().put("shelves", shelvesJ).put("tags", JSONArray(tags)).put("updated_at", settingsUpdatedAt))
    }

    private data class Parsed(val files: Map<String, FileDecision>, val releases: Map<String, ReleaseDecision>,
                              val shelves: List<Shelf>, val tags: List<String>, val settingsAt: String)

    private fun parse(text: String): Parsed {
        val o = JSONObject(text)
        val f = HashMap<String, FileDecision>()
        o.optJSONObject("files")?.let { j -> j.keys().forEach { k -> f[k] = FileDecision.fromJson(j.getJSONObject(k)) } }
        val r = HashMap<String, ReleaseDecision>()
        o.optJSONObject("releases")?.let { j -> j.keys().forEach { k -> r[k] = ReleaseDecision.fromJson(j.getJSONObject(k)) } }
        val s = o.optJSONObject("settings") ?: JSONObject()
        val shelves = s.optJSONArray("shelves").objects().map { Shelf(it.optString("id"), it.optString("name"), false) }
        return Parsed(f, r, shelves, s.optJSONArray("tags").strings(), s.optString("updated_at", ""))
    }

    private fun loadLocalDecisions() {
        val text = listOf(localFile, File(ctx.filesDir, "decisions.json.bak"))
            .firstNotNullOfOrNull { f -> runCatching { f.readText().also { parse(it) } }.getOrNull() } ?: return
        runCatching {
            val p = parse(text)
            decisions = p.files; releaseDecisions = p.releases; customShelves = p.shelves; tags = p.tags
            settingsUpdatedAt = p.settingsAt
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
                    saveLocal()   // keep reviews made on other runs/devices on this phone too
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
        if (remote.settingsAt > settingsUpdatedAt) {
            customShelves = remote.shelves; tags = remote.tags; settingsUpdatedAt = remote.settingsAt
        }
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
