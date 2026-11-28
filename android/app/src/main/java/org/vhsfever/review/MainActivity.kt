package org.vhsfever.review

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.FileProvider
import java.io.File

object C {
    val bg = Color(0xFF121014)
    val panel = Color(0xFF1C191F)
    val panel2 = Color(0xFF2A2530)
    val line = Color(0xFF3A333C)
    val text = Color(0xFFF1EAE0)
    val muted = Color(0xFFB9AEA3)
    val gold = Color(0xFFF2A93B)
    val good = Color(0xFF5CC48A)
    val bad = Color(0xFFE86A5C)
    val blue = Color(0xFFA9D0FF)
    val blueBg = Color(0xFF1E3550)
    val warnBg = Color(0xFF4A3A14)
    val warn = Color(0xFFFFD98A)
    val curtain = Color(0xFF9E1B22)
}

class MainActivity : ComponentActivity() {
    companion object { private var model: AppModel? = null }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val m = model ?: AppModel(applicationContext).also { model = it }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = C.gold, background = C.bg, surface = C.panel,
                onSurface = C.text, onBackground = C.text, secondary = C.blue)) {
                Box(Modifier.fillMaxSize().background(C.bg).safeDrawingPadding()) { App(m, ::installApk) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model?.refreshIfStale()
    }

    override fun onPause() {
        super.onPause()
        model?.let { if (it.dirty) it.syncNow() }
    }

    private fun installApk(apk: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val uri = FileProvider.getUriForFile(this, "org.vhsfever.review.files", apk)
        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
fun App(m: AppModel, installApk: (File) -> Unit) {
    BackHandler(enabled = m.stack.size > 1) { m.back() }
    when (val s = m.stack.last()) {
        Screen.Queue -> QueueScreen(m)
        Screen.VidRockTest -> VidRockTestScreen()
        is Screen.Review -> ReviewScreen(m, s.fileId)
        is Screen.MovieEdit -> MovieScreen(m, s.fileId)
        Screen.Settings -> SettingsScreen(m, installApk)
        Screen.BoxArt -> BoxArtListScreen(m)
        is Screen.BoxArtPick -> BoxArtPickScreen(m, s.key)
        is Screen.BoxAlign -> BoxAlignScreen(m, s.key)
        is Screen.BoxSpine -> BoxAlignScreen(m, s.key, spineFrom = s.id)
    }
}
