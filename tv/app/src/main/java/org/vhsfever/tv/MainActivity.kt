package org.vhsfever.tv

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = Repo(applicationContext)
        val artRepo = ArtRepo(applicationContext)
        val prefs = applicationContext.getSharedPreferences("store", Context.MODE_PRIVATE)
        val dm = resources.displayMetrics
        Ui.fit(dm.widthPixels / dm.density, dm.heightPixels / dm.density)
        setContent { StoreTheme { App(repo, artRepo, prefs) } }
    }
}

/** Our colours, and text at its designed size whatever the TV's text-size setting is. */
@Composable
fun StoreTheme(content: @Composable () -> Unit) {
    val d = LocalDensity.current
    // Size everything from this TV's screen (and again if the screen size ever changes).
    val conf = LocalConfiguration.current
    val w = conf.screenWidthDp; val h = conf.screenHeightDp
    remember(w, h) { if (w > 0 && h > 0) Ui.fit(w.toFloat(), h.toFloat()) }
    CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 1f)) {
        MaterialTheme(colorScheme = darkColorScheme(primary = Ink.Gold, surface = Ink.Navy)) { key(w, h) { content() } }
    }
}

@Composable
fun App(repo: Repo, artRepo: ArtRepo, prefs: android.content.SharedPreferences) {
    var raw by remember { mutableStateOf<Catalog?>(null) }
    val art = remember { mutableStateMapOf<String, Art>() }
    val catalog = raw?.let { c -> Catalog(c.titles.map { it.withArt(art[it.key]) }) }
    var status by remember { mutableStateOf("Loading the shelves…") }
    var section by remember { mutableStateOf<Section>(Section.Home) }
    var openKey by remember { mutableStateOf<String?>(null) }
    var restoreKey by remember { mutableStateOf<String?>(null) }
    var watchlist by remember { mutableStateOf(prefs.getStringSet("watchlist", emptySet())!!.toSet()) }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            status = if (raw == null) "Loading the shelves…" else "Checking for new tapes…"
            try { raw = repo.refresh(); status = "" } catch (e: Exception) {
                status = if (raw == null) "Couldn’t load the catalog: ${e.message}" else "Couldn’t refresh: ${e.message}"
            }
        }
    }
    LaunchedEffect(Unit) {
        repo.cached()?.let { raw = it }
        reload()
    }
    // TMDB posters, summaries and genres (stand-in until real VHS box art is hooked up).
    LaunchedEffect(raw) {
        val c = raw ?: return@LaunchedEffect
        c.titles.forEach { t -> artRepo.cached(t)?.let { art[t.key] = it } }
        artRepo.loadAll(c.titles) { key, a -> scope.launch(Dispatchers.Main) { art[key] = a } }
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Ink.NavyTop, Ink.Navy)))) {
        val c = catalog
        val t = openKey?.let { k -> c?.titles?.firstOrNull { it.key == k } }
        when {
            t != null && c != null -> DetailScreen(
                t, c, onWatchlist = t.key in watchlist, previewOn = true,
                onBack = { restoreKey = t.key; openKey = null },
                onOpen = { openKey = it.key },
                onToggleWatchlist = {
                    watchlist = if (t.key in watchlist) watchlist - t.key else watchlist + t.key
                    prefs.edit().putStringSet("watchlist", watchlist).apply()
                },
            )
            c != null -> {
                if (section != Section.Home) BackHandler {
                    restoreKey = null
                    section = if (section is Section.Genre) Section.Genres else Section.Home
                }
                StoreScreen(c, section, status, watchlist, restoreKey,
                    onSection = { restoreKey = null; section = it },
                    onOpen = { restoreKey = it.key; openKey = it.key },
                    onRefresh = { reload() })
            }
            else -> Text(status, modifier = Modifier.align(Alignment.Center).padding(48.px),
                style = TextStyle(fontFamily = Type.Barlow, fontSize = 26.fs, color = Ink.Cream, textAlign = TextAlign.Center))
        }
    }
}
