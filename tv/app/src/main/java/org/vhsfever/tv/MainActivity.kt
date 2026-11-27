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
import androidx.compose.runtime.mutableIntStateOf
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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

/**
 * Things the store fills in after the movie list has loaded, kept by title key. Written from loading threads,
 * read when the shelves are put together.
 */
class Extras(cachedBoxes: Map<String, BoxScan>, cachedShots: Map<String, List<java.io.File>>) {
    val art = ConcurrentHashMap<String, Art>()
    val boxes = ConcurrentHashMap(cachedBoxes)
    val shots = ConcurrentHashMap(cachedShots)
    val picking: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val pending = AtomicBoolean(false)

    /** A movie with its TMDB info, real box (never for adult tapes, which stay plain boxes), screenshots and colours. */
    fun dress(t: Title, artRepo: ArtRepo, posterColors: PosterColors): Title {
        val a = t.withArt(art[t.key] ?: artRepo.cached(t))
            .let { a -> if (a.adult) a else a.copy(box = boxes[a.key] ?: a.tmdbId?.takeIf { a.tmdbType == "movie" }?.let { boxes["tmdb:$it"] }) }
        return a.copy(
            shots = shots[a.key] ?: a.tmdbId?.let { shots["tmdb:$it"] } ?: emptyList(),
            // colours for a made-up spine and back: from the real box front when there is one, else the poster
            colors = a.box?.front?.let { f -> posterColors.cached(posterColors.keyOf(f)) } ?: a.poster?.let { posterColors.cached(it) },
        )
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
    // What arrives while the store loads (TMDB info, real box scans, tape screenshots, box colours) is collected here,
    // and the shelves are refreshed from it every so often instead of once for every single movie.
    val extras = remember { Extras(boxRepo.cached(), boxRepo.cachedShots()) }
    var version by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val changed: () -> Unit = remember {
        { if (extras.pending.compareAndSet(false, true)) scope.launch(Dispatchers.Main) { delay(1500); extras.pending.set(false); version++ } }
    }
    // Movies whose info hasn't changed keep the very same box, so only the boxes that did change are redrawn.
    val kept = remember { HashMap<String, Title>() }
    val last = remember { arrayOfNulls<Catalog>(1) }
    val catalog = remember(raw, version) {
        raw?.let { c ->
            fun keep(t: Title) = kept[t.key]?.takeIf { it == t } ?: t.also { kept[t.key] = it }
            val built = Catalog(c.titles.map { t -> keep(extras.dress(t, artRepo, posterColors)) }, c.curtain.map(::keep))
            last[0]?.takeIf { it == built } ?: built
        }.also { last[0] = it }
    }
    var curtainPin by remember { mutableStateOf(prefs.getString("curtain_pin", null)) }
    var status by remember { mutableStateOf("Loading the shelves…") }
    var section by remember { mutableStateOf<Section>(Section.Home) }
    var openKey by remember { mutableStateOf<String?>(null) }
    var restoreKey by remember { mutableStateOf<String?>(null) }
    var watchlist by remember { mutableStateOf(prefs.getStringSet("watchlist", emptySet())!!.toSet()) }
    val ctx = LocalContext.current
    // app updates: checked a few seconds after opening, and from Settings
    var update by remember { mutableStateOf<Updater.State>(Updater.State.Idle) }
    var updateDismissed by remember { mutableStateOf(false) }
    fun checkUpdate() { scope.launch { updateDismissed = false; Updater.check(ctx) { update = it } } }
    LaunchedEffect(Unit) { delay(4000); checkUpdate() }

    fun reload() {
        scope.launch {
            status = if (raw == null) "Loading the shelves…" else "Checking for new tapes…"
            try { repo.refresh()?.let { raw = it }; status = "" } catch (e: Exception) {
                status = if (raw == null) "Couldn’t load the catalog: ${e.message}" else "Couldn’t refresh: ${e.message}"
            }
        }
    }
    LaunchedEffect(Unit) {
        repo.cached()?.let { raw = it }
        reload()
    }
    // TMDB posters, summaries and genres for movies not looked up yet, and box colours picked from each poster.
    LaunchedEffect(raw) {
        val c = raw ?: return@LaunchedEffect
        fun pickColors(key: String, model: Any) {
            if (posterColors.cached(key) != null || !extras.picking.add(key)) return
            scope.launch {
                val got = if (model is java.io.File) posterColors.colorsFor(model) else posterColors.colorsFor(model as String)
                if (got != null) changed()
                extras.picking.remove(key)
            }
        }
        c.titles.forEach { t -> (t.poster ?: artRepo.cached(t)?.poster)?.let { pickColors(it, it) } }
        extras.boxes.values.forEach { b -> pickColors(posterColors.keyOf(b.front), b.front) }
        artRepo.loadAll(c.titles) { key, a -> extras.art[key] = a; changed(); a.poster?.let { pickColors(it, it) } }
    }
    LaunchedEffect(raw) {
        val c = raw ?: return@LaunchedEffect
        val keys = c.titles.flatMap { listOfNotNull(it.key, it.tmdbId?.let { id -> "tmdb:$id" }) }.toSet()
        runCatching { boxRepo.refresh(keys) { id, b ->
            if (extras.boxes.put(id, b) != b) changed()
            if (posterColors.cached(posterColors.keyOf(b.front)) == null) scope.launch(Dispatchers.Main) {
                if (posterColors.colorsFor(b.front) != null) changed()
            }
        } }
        runCatching { boxRepo.refreshShots(keys) { k, f -> if (extras.shots.put(k, f) != f) changed() } }
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
