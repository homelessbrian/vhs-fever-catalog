package org.vhsfever.tv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Downloads the catalog feed and Brian's review decisions from GitHub (cached on the TV). */
class Repo(private val ctx: Context) {
    private val repo = BuildConfig.REPO
    private val feedUrl = "https://raw.githubusercontent.com/$repo/catalog-model-data/reviewer-feed.json"
    // The contents API is never stale; raw.githubusercontent can lag a few minutes behind a save.
    private val decisionsApi = "https://api.github.com/repos/$repo/contents/review/decisions.json?ref=review-data"
    private val decisionsRaw = "https://raw.githubusercontent.com/$repo/review-data/review/decisions.json"

    private val feedCache get() = File(ctx.filesDir, "reviewer-feed.json")
    private val decisionsCache get() = File(ctx.filesDir, "decisions.json")

    suspend fun cached(): Catalog? = withContext(Dispatchers.Default) {
        if (!feedCache.exists() || !decisionsCache.exists()) return@withContext null
        runCatching { Catalog.build(feedCache.readText(), decisionsCache.readText()) }.getOrNull()
    }

    suspend fun refresh(): Catalog = withContext(Dispatchers.IO) {
        val decisions = runCatching { get(decisionsApi, mapOf("Accept" to "application/vnd.github.raw")) }
            .getOrElse { get(decisionsRaw) }
        val feed = runCatching { get(feedUrl, timeoutMs = 120000) }.getOrElse { e ->
            if (feedCache.exists()) feedCache.readText() else throw e
        }
        val catalog = withContext(Dispatchers.Default) { Catalog.build(feed, decisions) }
        feedCache.writeText(feed)
        decisionsCache.writeText(decisions)
        catalog
    }

    private fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 30000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", "VHS-FEVER-TV")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        val code = c.responseCode
        if (code !in 200..299) throw Exception("HTTP $code from ${URL(url).host}")
        return c.inputStream.bufferedReader().use { it.readText() }
    }
}
