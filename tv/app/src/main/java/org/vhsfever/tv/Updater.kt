package org.vhsfever.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps the app up to date by itself. Each build on GitHub puts the app and a version.txt
 * (its build number) on the tv-latest release. When a newer build is there, the app downloads it
 * and hands it to the TV's installer, which asks once to confirm.
 */
object Updater {
    private const val BASE = "https://github.com/${BuildConfig.REPO}/releases/download/tv-latest"

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Downloading(val build: Int, val percent: Int) : State
        data class Ready(val build: Int, val file: File) : State
        data class Failed(val message: String) : State
    }

    /** The build number on GitHub, or null if it couldn't be read. */
    suspend fun latestBuild(): Int? = withContext(Dispatchers.IO) {
        runCatching { get("$BASE/version.txt").trim().toInt() }.getOrNull()
    }

    val currentBuild get() = BuildConfig.VERSION_CODE

    /** Checks GitHub and, if there's a newer build, downloads it, reporting progress along the way. */
    suspend fun check(ctx: Context, report: (State) -> Unit) {
        report(State.Checking)
        val latest = latestBuild() ?: return report(State.Failed("Couldn’t reach GitHub to check for updates"))
        if (latest <= currentBuild) return report(State.UpToDate)
        try {
            val file = withContext(Dispatchers.IO) {
                val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val out = File(dir, "vhs-fever-tv-$latest.apk")
                val c = open("$BASE/vhs-fever-tv.apk")
                val total = c.contentLengthLong
                var done = 0L
                var last = -1
                c.inputStream.use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n); done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else 0
                            if (pct != last) { last = pct; withContext(Dispatchers.Main) { report(State.Downloading(latest, pct)) } }
                        }
                    }
                }
                out
            }
            report(State.Ready(latest, file))
        } catch (e: Exception) {
            report(State.Failed("Update download failed: ${e.message}"))
        }
    }

    /**
     * Opens the TV's installer for the downloaded update. The first time, Android needs permission for
     * VHS FEVER to install apps; then this opens that setting instead and returns false.
     */
    fun install(activity: Activity, file: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            runCatching {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            }
            return false
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 20000; c.readTimeout = 60000
        c.setRequestProperty("User-Agent", "VHS-FEVER-TV")
        if (c.responseCode !in 200..299) throw Exception("HTTP ${c.responseCode}")
        return c
    }

    private fun get(url: String): String = open(url).inputStream.bufferedReader().use { it.readText() }
}
