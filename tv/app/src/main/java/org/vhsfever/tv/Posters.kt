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
import java.util.concurrent.ConcurrentHashMap

/**
 * Posters from btttr.cc, looked up by IMDb id, for movies with no real VHS box scan.
 * TMDB's poster is the backup when btttr.cc has none.
 *
 * Each poster is downloaded once and kept on the TV, so their server is asked about a movie only once
 * and the boxes stay put even if the site goes down. A movie btttr.cc has no poster for is asked about
 * again after two weeks. btttr.cc answers a movie it has no poster for by sending the app elsewhere
 * (a redirect), so only a straight picture answer counts as a poster.
 */
class PosterRepo(ctx: Context) {
    private val dir = File(ctx.filesDir, "posters").apply { mkdirs() }
    private val missingFile = File(dir, "missing.json")
    private val missing = ConcurrentHashMap<String, Long>()
    private val busy: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        runCatching {
            val o = JSONObject(missingFile.readText())
            for (k in o.keys()) missing[k] = o.optLong(k)
        }
    }

    private fun file(imdb: String) = File(dir, "$imdb.img")

    /** Posters already on the TV, by IMDb id. */
    fun cached(): Map<String, File> = dir.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".img") }
        ?.associate { it.name.removeSuffix(".img") to it } ?: emptyMap()

    /**
     * Downloads the posters not on the TV yet for [imdbIds] (a few at a time) and calls [onPoster] as each
     * one is saved. Movies btttr.cc had nothing for in the last two weeks are skipped.
     */
    suspend fun fetch(imdbIds: Collection<String>, now: Long = System.currentTimeMillis(), onPoster: (String, File) -> Unit) =
        withContext(Dispatchers.IO) {
            val todo = imdbIds.toSet().filter { id ->
                validImdb(id) && !file(id).exists() && missing[id]?.let { now - it < RETRY_MISSING_MS } != true
            }
            if (todo.isEmpty()) return@withContext
            val gate = Semaphore(3)
            val changed = java.util.concurrent.atomic.AtomicBoolean(false)
            todo.map { id ->
                async {
                    if (!busy.add(id)) return@async
                    try {
                        when (val got = gate.withPermit { runCatching { download(id) }.getOrDefault(Got.TryLater) }) {
                            is Got.Poster -> {
                                val f = file(id)
                                val tmp = File(dir, "$id.part")
                                tmp.writeBytes(got.bytes)
                                if (tmp.renameTo(f)) { missing.remove(id); onPoster(id, f) }
                            }
                            Got.None -> { missing[id] = now; changed.set(true) }
                            Got.TryLater -> Unit
                        }
                    } finally { busy.remove(id) }
                }
            }.awaitAll()
            if (changed.get()) saveMissing()
        }

    private fun download(imdb: String): Got {
        val c = URL(url(imdb)).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false   // a redirect means "no poster here"
        c.connectTimeout = 20000; c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "VHS-FEVER-TV")
        c.setRequestProperty("Accept", "image/*")
        try {
            val code = c.responseCode
            val bytes = if (code == 200) c.inputStream.use { it.readBytes() } else null
            val got = judge(code, c.contentType, bytes)
            // the size of the picture is checked too, so a broken download is never kept
            if (got is Got.Poster && !decodes(got.bytes)) return Got.TryLater
            return got
        } finally { c.disconnect() }
    }

    private fun decodes(b: ByteArray): Boolean {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, o)
        return o.outWidth >= 100 && o.outHeight >= 100
    }

    private fun saveMissing() = runCatching {
        val o = JSONObject()
        for ((k, v) in missing) o.put(k, v)
        missingFile.writeText(o.toString())
    }

    /** What one answer from btttr.cc means. */
    sealed class Got {
        class Poster(val bytes: ByteArray) : Got()
        /** btttr.cc has no poster for this movie. */
        object None : Got()
        /** Something went wrong (site down, cut-off download): ask again next time. */
        object TryLater : Got()
    }

    companion object {
        private const val RETRY_MISSING_MS = 14L * 24 * 60 * 60 * 1000

        fun url(imdb: String) = "https://btttr.cc/poster-n/imdb/poster-default/$imdb.jpg?tag=none"

        fun validImdb(id: String) = Regex("tt\\d{5,10}").matches(id)

        /** A poster only when the answer is a picture of a sensible size; a redirect or "not found" means none. */
        fun judge(code: Int, contentType: String?, bytes: ByteArray?): Got = when {
            code == 200 && contentType?.startsWith("image/") == true && bytes != null && bytes.size >= 2048 -> Got.Poster(bytes)
            code in 300..399 || code == 404 || code == 410 -> Got.None
            else -> Got.TryLater
        }
    }
}
