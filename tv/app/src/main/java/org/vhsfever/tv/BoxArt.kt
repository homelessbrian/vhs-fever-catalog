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
)

/**
 * VHS box art from the private vhs-fever-boxart repository, read with the TV app's read-only key.
 * Each box is downloaded once and kept on the TV; it's only fetched again if the scan is redone.
 */
class BoxArtRepo(ctx: Context) {
    private val dir = File(ctx.filesDir, "boxart").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val token = BuildConfig.BOXART_TOKEN
    private val base = "https://raw.githubusercontent.com/${BuildConfig.BOXART_REPO}/main/"
    val available get() = token.isNotBlank()

    /** TMDB id → the sleeve to use and its panel paths in the repo. */
    private data class Entry(val sleeve: String, val files: Map<String, String>, val label: String?,
                             val region: String?, val tapeYear: String?)

    private fun parse(text: String): Map<Int, Entry> {
        val films = JSONObject(text).optJSONObject("films") ?: return emptyMap()
        val out = HashMap<Int, Entry>()
        for (k in films.keys()) {
            val e = films.optJSONObject(k) ?: continue
            val f = e.optJSONObject("files") ?: continue
            val files = f.keys().asSequence().associateWith { f.optString(it) }.filterValues { it.isNotBlank() }
            if ("front" !in files) continue
            val id = k.toIntOrNull() ?: continue
            out[id] = Entry(e.optString("sleeve"), files, e.str("label"), e.str("region"),
                e.opt("tape_year")?.takeIf { it != JSONObject.NULL }?.toString())
        }
        return out
    }

    private fun local(e: Entry, part: String) = e.files[part]?.let { File(dir, "${e.sleeve}/$part.jpg") }

    private fun scan(e: Entry): BoxScan? {
        val front = local(e, "front")?.takeIf { it.exists() } ?: return null
        return BoxScan(e.sleeve, front, local(e, "spine")?.takeIf { it.exists() },
            local(e, "back")?.takeIf { it.exists() }, e.label, e.region, e.tapeYear)
    }

    /** Boxes already on the TV, by TMDB id. */
    fun cached(): Map<Int, BoxScan> = runCatching {
        parse(indexFile.readText()).mapNotNull { (id, e) -> scan(e)?.let { id to it } }.toMap()
    }.getOrDefault(emptyMap())

    /**
     * Gets the latest list, downloads boxes the TV doesn't have yet (a few at a time, only for
     * the movies in [wanted]) and calls [onBox] as each one is ready.
     */
    suspend fun refresh(wanted: Set<Int>, onBox: (Int, BoxScan) -> Unit) = withContext(Dispatchers.IO) {
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
                    }
                }
                scan(e)?.let { onBox(id, it) }
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
