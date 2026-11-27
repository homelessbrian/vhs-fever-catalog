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

/** A real VHS sleeve scan (from videotapes.io), saved on the TV. Spine and back are missing for front-only scans. */
data class BoxScan(
    val sleeve: String,
    val front: File,
    val spine: File?,
    val back: File?,
    val label: String?,
    val region: String?,
    val tapeYear: String?,
    val source: String = "videotapes.io",
    val aspect: Float = VHS_ASPECT,   // the front's width ÷ height, so the box can take its shape
)

/**
 * VHS box art from the private vhs-fever-boxart repository, read with the TV app's read-only key.
 * Each box is downloaded once and kept on the TV; it's only fetched again if the scan is redone.
 */
class BoxArtRepo(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "boxart").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val token = BuildConfig.BOXART_TOKEN
    private val base = "https://raw.githubusercontent.com/${BuildConfig.BOXART_REPO}/main/"
    val available get() = token.isNotBlank()

    /** TMDB id → the sleeve to use and its panel paths in the repo. */
    private data class Entry(val sleeve: String, val files: Map<String, String>, val label: String?,
                             val region: String?, val tapeYear: String?, val source: String, val rev: String = "")

    /**
     * Title key ("tmdb:603" or "title:…") → its box. Picks from the review app are already applied:
     * this is exactly the box each movie should show.
     */
    private fun parse(text: String): Map<String, Entry> {
        val o = JSONObject(text)
        val boxes = o.optJSONObject("boxes")
        val films = boxes ?: o.optJSONObject("films") ?: return emptyMap()
        val out = HashMap<String, Entry>()
        for (k in films.keys()) {
            val e = films.optJSONObject(k) ?: continue
            val f = e.optJSONObject("files") ?: continue
            val files = f.keys().asSequence().associateWith { f.optString(it) }.filterValues { it.isNotBlank() }
            if ("front" !in files) continue
            val id = if (boxes != null) k else "tmdb:$k"
            if (e.optString("sleeve").let { it.isBlank() || it.contains("/") || it.contains("..") }) continue
            out[id] = Entry(e.optString("sleeve"), files, e.str("label"), e.str("region"),
                e.opt("tape_year")?.takeIf { it != JSONObject.NULL }?.toString(),
                if (e.str("source") == "archive") "Internet Archive" else "videotapes.io", e.str("rev") ?: "")
        }
        return out
    }

    private fun local(e: Entry, part: String) = e.files[part]?.let { File(dir, "${e.sleeve}/$part.jpg") }

    private fun scan(e: Entry): BoxScan? {
        val front = local(e, "front")?.takeIf { it.exists() } ?: return null
        return BoxScan(e.sleeve, front, local(e, "spine")?.takeIf { it.exists() },
            local(e, "back")?.takeIf { it.exists() }, e.label, e.region, e.tapeYear, e.source, aspectOf(front))
    }

    /** Width ÷ height of a saved picture, read from its header only (quick, no full decode). */
    private fun aspectOf(f: File): Float = runCatching {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth > 0 && o.outHeight > 0) o.outWidth.toFloat() / o.outHeight else VHS_ASPECT
    }.getOrDefault(VHS_ASPECT)

    /** Boxes already on the TV, by TMDB id. */
    fun cached(): Map<String, BoxScan> = runCatching {
        parse(indexFile.readText()).mapNotNull { (id, e) -> scan(e)?.let { id to it } }.toMap()
    }.getOrDefault(emptyMap())

    /**
     * Gets the latest list, downloads boxes the TV doesn't have yet (a few at a time, only for
     * the movies in [wanted]) and calls [onBox] as each one is ready.
     */
    suspend fun refresh(wanted: Set<String>, onBox: (String, BoxScan) -> Unit) = withContext(Dispatchers.IO) {
        if (!available) return@withContext
        val text = runCatching { get(base + "index.json").decodeToString() }.getOrNull()
        if (text != null) {
            runCatching { parse(text) }.getOrNull() ?: return@withContext
            indexFile.writeText(text)
        }
        val index = runCatching { parse(indexFile.readText()) }.getOrDefault(emptyMap())
        // Boxes no longer in the list (taken down or replaced) are deleted from the TV too.
        val keep = index.values.map { it.sleeve }.toSet()
        dir.listFiles()?.filter { it.isDirectory && it.name !in keep }?.forEach { it.deleteRecursively() }

        // a box cut again (lined up in the review app) is fetched again
        for (e in index.values.distinctBy { it.sleeve }) {
            val marker = File(dir, "${e.sleeve}/.rev")
            if (e.rev.isEmpty() || !File(dir, e.sleeve).exists()) continue
            // boxes saved before boxes could be lined up are the current cut
            if (!marker.exists()) marker.writeText(e.rev)
            else if (marker.readText() != e.rev) File(dir, e.sleeve).listFiles()?.forEach { it.delete() }
        }
        val gate = Semaphore(3)
        index.filterKeys { it in wanted }.map { (id, e) ->
            async {
                scan(e)?.takeIf { _ -> e.files.keys.all { local(e, it)?.exists() == true } }?.let { onBox(id, it); return@async }
                gate.withPermit {
                    runCatching {
                        for ((part, path) in e.files) {
                            val f = local(e, part) ?: continue
                            if (f.exists()) continue
                            f.parentFile?.mkdirs()
                            val tmp = File(f.parentFile, f.name + ".part")
                            tmp.writeBytes(get(base + path))
                            tmp.renameTo(f)
                        }
                        if (e.rev.isNotEmpty()) File(dir, "${e.sleeve}/.rev").writeText(e.rev)
                    }
                }
                scan(e)?.let { onBox(id, it) }
            }
        }.awaitAll()
    }

    // ---------------------------------------------------------------- tape screenshots

    private val shotsDir = File(ctx.filesDir, "shots").apply { mkdirs() }
    private val shotsIndex = File(shotsDir, "shots.json")

    /** Title key ("tmdb:603") → its screenshot paths in the repository. */
    private fun parseShots(text: String): Map<String, List<String>> {
        val titles = JSONObject(text).optJSONObject("titles") ?: return emptyMap()
        return titles.keys().asSequence().associateWith { k ->
            titles.optJSONObject(k)?.optJSONArray("files")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                ?.filter { it.startsWith("shots/") && !it.contains("..") } ?: emptyList()
        }.filterValues { it.isNotEmpty() }
    }

    private fun localShot(path: String) = File(shotsDir, path.removePrefix("shots/"))

    /** Screenshots already on the TV, by title key. */
    fun cachedShots(): Map<String, List<File>> = runCatching {
        parseShots(shotsIndex.readText()).mapValues { (_, ps) -> ps.map(::localShot).filter { it.exists() } }
            .filterValues { it.isNotEmpty() }
    }.getOrDefault(emptyMap())

    /** Gets the screenshot list and downloads the screenshots of the titles in [wanted] (each once). */
    suspend fun refreshShots(wanted: Set<String>, onShots: (String, List<File>) -> Unit) = withContext(Dispatchers.IO) {
        if (!available) return@withContext
        runCatching { get(base + "shots.json").decodeToString() }.getOrNull()?.let { text ->
            if (runCatching { parseShots(text) }.isSuccess) shotsIndex.writeText(text)
        }
        val index = runCatching { parseShots(shotsIndex.readText()) }.getOrDefault(emptyMap())
        // screenshots of movies no longer listed are deleted
        val keep = index.values.flatten().map { localShot(it).parentFile?.name }.toSet()
        shotsDir.listFiles()?.filter { it.isDirectory && it.name !in keep }?.forEach { it.deleteRecursively() }
        val gate = Semaphore(3)
        index.filterKeys { it in wanted }.map { (key, paths) ->
            async {
                gate.withPermit {
                    for (p in paths) {
                        val f = localShot(p)
                        if (f.exists()) continue
                        runCatching {
                            f.parentFile?.mkdirs()
                            val tmp = File(f.parentFile, f.name + ".part")
                            tmp.writeBytes(get(base + p))
                            tmp.renameTo(f)
                        }
                    }
                }
                paths.map(::localShot).filter { it.exists() }.takeIf { it.isNotEmpty() }?.let { onShots(key, it) }
            }
        }.awaitAll()
    }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 60000
        c.setRequestProperty("Authorization", "token $token")
        c.setRequestProperty("User-Agent", "VHS-FEVER-TV")
        if (c.responseCode !in 200..299) throw Exception("box art HTTP ${c.responseCode}")
        return c.inputStream.use { it.readBytes() }
    }
}

private fun JSONObject.str(k: String): String? =
    if (isNull(k)) null else optString(k).takeIf { it.isNotBlank() && it != "null" }
