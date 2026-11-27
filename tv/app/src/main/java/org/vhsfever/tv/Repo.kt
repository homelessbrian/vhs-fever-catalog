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
 * The movie list is published by the "Find VHS movies" bot on the movies-data branch.
 */
class Repo(private val ctx: Context) {
    private val repo = BuildConfig.REPO
    // The TV's own smaller copy of the movie list first; the full one only until the bot has made the small one.
    private val feedUrls = listOf(
        "https://raw.githubusercontent.com/$repo/movies-data/tv-feed.json",
        "https://raw.githubusercontent.com/$repo/movies-data/reviewer-feed.json",
    )
    private val feedFile get() = File(ctx.filesDir, "movies-feed.json")
    // The contents API is never stale; raw.githubusercontent can lag a few minutes behind a save.
    private val decisionsApi = "https://api.github.com/repos/$repo/contents/review/decisions.json?ref=review-data"
    private val decisionsRaw = "https://raw.githubusercontent.com/$repo/review-data/review/decisions.json"

    private val decisionsCache get() = File(ctx.filesDir, "decisions.json")
    // "version tags" GitHub gives each download, so an unchanged list isn't downloaded or read again
    private val tags = ctx.getSharedPreferences("downloads", Context.MODE_PRIVATE)

    suspend fun cached(): Catalog? = withContext(Dispatchers.Default) {
        if (!feedFile.exists() || !decisionsCache.exists()) return@withContext null
        runCatching { Catalog.build(feedFile.readText(), decisionsCache.readText()) }.getOrNull()
    }

    /** The newest shelf, or null when nothing has changed since the shelf on the TV was built. */
    suspend fun refresh(): Catalog? = withContext(Dispatchers.IO) {
        var changed = !feedFile.exists() || !decisionsCache.exists()
        val decisions = runCatching { fetch(decisionsApi, "decisions", mapOf("Accept" to "application/vnd.github.raw")) }
            .getOrElse { runCatching { fetch(decisionsRaw, "decisions") }.getOrNull() }
        when {
            decisions == null -> if (!decisionsCache.exists()) throw Exception("Couldn’t download the review decisions")
            decisions.isNotEmpty() -> { decisionsCache.writeText(decisions); changed = true }  // empty = not changed
        }
        var lastError: Exception? = null
        var got = false
        for (url in feedUrls) {
            try { if (download(url, feedFile)) changed = true; got = true; break } catch (e: Exception) { lastError = e }
        }
        if (!got && !feedFile.exists()) throw lastError ?: Exception("No catalog downloaded")
        if (!changed) return@withContext null
        withContext(Dispatchers.Default) { Catalog.build(feedFile.readText(), decisionsCache.readText()) }
    }

    private fun open(url: String, tag: String?, headers: Map<String, String>, timeoutMs: Int): HttpURLConnection? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", "VHS-FEVER-TV")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        tag?.let { tags.getString(it, null) }?.takeIf { tags.getString("$tag:url", null) == url }
            ?.let { c.setRequestProperty("If-None-Match", it) }
        val code = c.responseCode
        if (code == 304) return null
        if (code !in 200..299) throw Exception("HTTP $code from ${URL(url).host}")
        return c
    }

    private fun remember(tag: String, url: String, c: HttpURLConnection) {
        c.getHeaderField("ETag")?.let { tags.edit().putString(tag, it).putString("$tag:url", url).apply() }
    }

    /** The text at [url], or "" when it hasn't changed since last time. */
    private fun fetch(url: String, tag: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 30000): String {
        val c = open(url, tag.takeIf { decisionsCache.exists() }, headers, timeoutMs) ?: return ""
        val text = c.inputStream.bufferedReader().use { it.readText() }
        remember(tag, url, c)
        return text
    }

    /** Saves a download to [dest], replacing it only once the whole file has arrived. False when it hadn't changed. */
    private fun download(url: String, dest: File, timeoutMs: Int = 120000): Boolean {
        val c = open(url, "feed".takeIf { dest.exists() }, emptyMap(), timeoutMs) ?: return false
        val tmp = File(dest.parentFile, dest.name + ".part")
        c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
        remember("feed", url, c)
        return true
    }
}
