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
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = Repo(applicationContext)
        val artRepo = ArtRepo(applicationContext)
        val boxRepo = BoxArtRepo(applicationContext)
        val posterColors = PosterColors(applicationContext)
        val prefs = applicationContext.getSharedPreferences("store", Context.MODE_PRIVATE)
        Shelf.load(applicationContext)
        val dm = resources.displayMetrics
        Ui.fit(dm.widthPixels / dm.density, dm.heightPixels / dm.density)
        setContent { StoreTheme { App(repo, artRepo, boxRepo, posterColors, prefs) } }
    }

    /** Leaving the app closes the red curtain again. */
    override fun onStop() {
        super.onStop()
        Curtain.open = false
    }
}

/** The red curtain in front of the adult tapes. Open only until the app is left; the PIN is kept on the TV. */
object Curtain {
    var open by mutableStateOf(false)
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
fun App(repo: Repo, artRepo: ArtRepo, boxRepo: BoxArtRepo, posterColors: PosterColors, prefs: android.content.SharedPreferences) {
    var raw by remember { mutableStateOf<Catalog?>(null) }
    val art = remember { mutableStateMapOf<String, Art>() }
    // Real VHS box scans by TMDB id (never for adult tapes, which stay plain boxes).
    val boxes = remember { mutableStateMapOf<Int, BoxScan>().apply { putAll(boxRepo.cached()) } }
    // Screenshots from each movie's own tape, and box colours picked from each poster.
    val shots = remember { mutableStateMapOf<String, List<java.io.File>>().apply { putAll(boxRepo.cachedShots()) } }
    val colors = remember { mutableStateMapOf<String, BoxColors>() }
    val catalog = raw?.let { c -> Catalog(c.titles.map { t ->
        t.withArt(art[t.key]).let { a -> if (a.adult || a.tmdbType != "movie") a else a.copy(box = a.tmdbId?.let { boxes[it] }) }
            .let { a -> a.copy(
                shots = shots[a.key] ?: a.tmdbId?.let { shots["tmdb:$it"] } ?: emptyList(),
                colors = a.poster?.let { p -> colors[p] ?: posterColors.cached(p) },
            ) }
    }, c.curtain) }
    var curtainPin by remember { mutableStateOf(prefs.getString("curtain_pin", null)) }
    var status by remember { mutableStateOf("Loading the shelves…") }
    var section by remember { mutableStateOf<Section>(Section.Home) }
    var openKey by remember { mutableStateOf<String?>(null) }
    var restoreKey by remember { mutableStateOf<String?>(null) }
    var watchlist by remember { mutableStateOf(prefs.getStringSet("watchlist", emptySet())!!.toSet()) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    // app updates: checked a few seconds after opening, and from Settings
    var update by remember { mutableStateOf<Updater.State>(Updater.State.Idle) }
    var updateDismissed by remember { mutableStateOf(false) }
    fun checkUpdate() { scope.launch { updateDismissed = false; Updater.check(ctx) { update = it } } }
    LaunchedEffect(Unit) { delay(4000); checkUpdate() }

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
        fun pickColors(poster: String?) {
            if (poster == null || colors.containsKey(poster)) return
            scope.launch { posterColors.colorsFor(poster)?.let { colors[poster] = it } }
        }
        c.titles.forEach { pickColors(it.poster ?: art[it.key]?.poster) }
        artRepo.loadAll(c.titles) { key, a -> scope.launch(Dispatchers.Main) { art[key] = a; pickColors(a.poster) } }
    }
    LaunchedEffect(raw) {
        val c = raw ?: return@LaunchedEffect
        val wanted = c.titles.filter { !it.adult }.mapNotNull { it.tmdbId }.toSet()
        runCatching { boxRepo.refresh(wanted) { id, b -> scope.launch(Dispatchers.Main) { boxes[id] = b } } }
        val keys = c.titles.filter { !it.adult }.flatMap { listOfNotNull(it.key, it.tmdbId?.let { id -> "tmdb:$id" }) }.toSet()
        runCatching { boxRepo.refreshShots(keys) { k, f -> scope.launch(Dispatchers.Main) { shots[k] = f } } }
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Ink.NavyTop, Ink.Navy)))) {
        val c = catalog
        val t = openKey?.let { k -> c?.titles?.firstOrNull { it.key == k }
            ?: c?.curtain?.firstOrNull { it.key == k && Curtain.open } }
        when {
            t != null && c != null -> DetailScreen(
                // "More like this" under an adult tape only ever shows other adult tapes.
                t, if (t.adult) Catalog(c.curtain) else c, onWatchlist = t.key in watchlist, previewOn = true,
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
                    onRefresh = { reload() },
                    update = update, onCheckUpdate = { checkUpdate() },
                    curtainPinSet = curtainPin != null,
                    onCurtainPin = { pin ->
                        when {
                            curtainPin == null -> { prefs.edit().putString("curtain_pin", pin).apply(); curtainPin = pin; Curtain.open = true; true }
                            pin == curtainPin -> { Curtain.open = true; true }
                            else -> false
                        }
                    },
                    onCurtainReset = { prefs.edit().remove("curtain_pin").apply(); curtainPin = null; Curtain.open = false })
            }
            else -> Text(status, modifier = Modifier.align(Alignment.Center).padding(48.px),
                style = TextStyle(fontFamily = Type.Barlow, fontSize = 26.fs, color = Ink.Cream, textAlign = TextAlign.Center))
        }
        val u = update
        if (u is Updater.State.Downloading) UpdateBadge("Downloading update… ${u.percent}%", Modifier.align(Alignment.BottomEnd))
        if (u is Updater.State.Ready && !updateDismissed) UpdateDialog(u.build,
            onInstall = { (ctx as? android.app.Activity)?.let { Updater.install(it, u.file) } ?: true },
            onLater = { updateDismissed = true })
    }
}
