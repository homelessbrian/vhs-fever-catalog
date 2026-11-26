package org.vhsfever.tv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the movie list and Brian's review decisions from GitHub (cached on the TV).
 *
 * The one movie list is published by the "Find VHS movies" bot on the movies-data branch.
 */
class Repo(private val ctx: Context) {
    private val repo = BuildConfig.REPO
    private val feeds = listOf(
        "https://raw.githubusercontent.com/$repo/movies-data/reviewer-feed.json" to "movies-feed.json",
    )
    // The contents API is never stale; raw.githubusercontent can lag a few minutes behind a save.
    private val decisionsApi = "https://api.github.com/repos/$repo/contents/review/decisions.json?ref=review-data"
    private val decisionsRaw = "https://raw.githubusercontent.com/$repo/review-data/review/decisions.json"

    private val decisionsCache get() = File(ctx.filesDir, "decisions.json")
    private fun cacheFile(name: String) = File(ctx.filesDir, name)

    /** Feeds that are saved on the TV, read one at a time when the shelf is built. */
    private fun savedFeeds(): List<() -> String> =
        feeds.map { cacheFile(it.second) }.filter { it.exists() }.map { f -> { f.readText() } }

    suspend fun cached(): Catalog? = withContext(Dispatchers.Default) {
        val saved = savedFeeds()
        if (saved.isEmpty() || !decisionsCache.exists()) return@withContext null
        runCatching { Catalog.build(saved, decisionsCache.readText()) }.getOrNull()
    }

    suspend fun refresh(): Catalog = withContext(Dispatchers.IO) {
        val decisions = runCatching { get(decisionsApi, mapOf("Accept" to "application/vnd.github.raw")) }
            .getOrElse { get(decisionsRaw) }
        // Each feed is saved straight to a file; if one can't be downloaded, the copy already on the TV is used.
        var lastError: Exception? = null
        for ((url, name) in feeds) {
            try { download(url, cacheFile(name)) } catch (e: Exception) { lastError = e }
        }
        val saved = savedFeeds()
        if (saved.isEmpty()) throw lastError ?: Exception("No catalog downloaded")
        val catalog = withContext(Dispatchers.Default) { Catalog.build(saved, decisions) }
        decisionsCache.writeText(decisions)
        catalog
    }

    private fun open(url: String, headers: Map<String, String>, timeoutMs: Int): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", "VHS-FEVER-TV")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        val code = c.responseCode
        if (code !in 200..299) throw Exception("HTTP $code from ${URL(url).host}")
        return c
    }

    private fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 30000): String =
        open(url, headers, timeoutMs).inputStream.bufferedReader().use { it.readText() }

    /** Saves a download to [dest], replacing it only once the whole file has arrived. */
    private fun download(url: String, dest: File, timeoutMs: Int = 120000) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        open(url, emptyMap(), timeoutMs).inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
    }
}
