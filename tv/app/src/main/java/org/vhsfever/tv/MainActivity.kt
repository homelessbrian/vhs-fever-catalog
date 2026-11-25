package org.vhsfever.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

val Pink = Color(0xFFFF2E88)
val Amber = Color(0xFFFFB000)
val Cyan = Color(0xFF00D1FF)
val Cream = Color(0xFFF4F1E8)
val Dim = Color(0xFFA8A3B8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = Repo(applicationContext)
        val artRepo = ArtRepo(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Pink, surface = Color(0xFF15131D))) {
                App(repo, artRepo)
            }
        }
    }
}

@Composable
fun App(repo: Repo, artRepo: ArtRepo) {
    var raw by remember { mutableStateOf<Catalog?>(null) }
    val art = remember { mutableStateMapOf<String, Art>() }
    val catalog = raw?.let { c -> Catalog(c.titles.map { it.withArt(art[it.key]) }) }
    var status by remember { mutableStateOf("Loading the shelves…") }
    var openKey by remember { mutableStateOf<String?>(null) }
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
    // TMDB posters for every box (stand-in until real VHS box art is hooked up).
    LaunchedEffect(raw) {
        val c = raw ?: return@LaunchedEffect
        c.titles.forEach { t -> artRepo.cached(t)?.let { art[t.key] = it } }
        artRepo.loadAll(c.titles) { key, a -> scope.launch(Dispatchers.Main) { art[key] = a } }
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF1C1030), Color(0xFF0B0A12), Color(0xFF07060B)))
        )
    ) {
        val c = catalog
        val t = openKey?.let { k -> c?.titles?.firstOrNull { it.key == k } }
        when {
            t != null -> {
                BackHandler { openKey = null }
                DetailScreen(t)
            }
            c != null -> HomeScreen(c, status, onOpen = { openKey = it.key }, onRefresh = { reload() })
            else -> Text(status, color = Cream, fontSize = 22.sp, modifier = Modifier.align(Alignment.Center).padding(48.dp),
                textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun Logo() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("VHS", color = Cream, fontSize = 34.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.SansSerif)
        Spacer(Modifier.width(8.dp))
        Text("FEVER", color = Pink, fontSize = 34.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.SansSerif)
        Spacer(Modifier.width(16.dp))
        Column {
            Box(Modifier.size(70.dp, 4.dp).background(Pink))
            Spacer(Modifier.height(3.dp))
            Box(Modifier.size(70.dp, 4.dp).background(Amber))
            Spacer(Modifier.height(3.dp))
            Box(Modifier.size(70.dp, 4.dp).background(Cyan))
        }
    }
}

@Composable
fun HomeScreen(catalog: Catalog, status: String, onOpen: (Title) -> Unit, onRefresh: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val tapes = catalog.titles.sumOf { it.tapes.size }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Logo()
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f)) {
                    Text("Playback test · ${catalog.titles.size} titles · $tapes tapes marked good", color = Dim, fontSize = 15.sp)
                    if (status.isNotBlank()) Text(status, color = Amber, fontSize = 13.sp)
                }
                Surface(
                    onClick = onRefresh,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                    colors = ClickableSurfaceDefaults.colors(containerColor = Color(0x22FFFFFF), focusedContainerColor = Pink,
                        contentColor = Cream, focusedContentColor = Color.White),
                ) { Text("Refresh", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) }
            }
        }
        if (catalog.titles.isEmpty()) item {
            Text("No tapes are marked good yet.", color = Cream, fontSize = 20.sp, modifier = Modifier.padding(48.dp))
        }
        catalog.shelves.forEachIndexed { si, (shelf, titles) ->
            item(key = "shelf-$shelf") {
                Column {
                    Row(Modifier.padding(start = 48.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp, 22.dp).background(if (si % 3 == 0) Pink else if (si % 3 == 1) Amber else Cyan))
                        Spacer(Modifier.width(10.dp))
                        Text(shelf.uppercase(), color = Cream, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Spacer(Modifier.width(10.dp))
                        Text("${titles.size}", color = Dim, fontSize = 15.sp)
                    }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(22.dp),
                    ) {
                        itemsIndexed(titles, key = { _, t -> t.key }) { i, t ->
                            val m = if (si == 0 && i == 0) Modifier.focusRequester(first) else Modifier
                            Column(m.width(132.dp)) {
                                VhsBox(t, width = 132.dp, onClick = { onOpen(t) })
                                Spacer(Modifier.height(8.dp))
                                Text(t.name, color = Cream, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(tapeCount(t), color = Dim, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

fun tapeCount(t: Title) = if (t.tapes.size == 1) "1 tape" else "${t.tapes.size} tapes"

/** A rental box: poster (or a blank store box), a spine on the left, plastic-sleeve sheen on top. */
@Composable
fun VhsBox(t: Title, width: Dp, onClick: (() -> Unit)?) {
    val h = width * 1.75f
    val shape = RoundedCornerShape(3.dp)
    val inner: @Composable () -> Unit = {
        Box(Modifier.size(width, h).background(Color(0xFF0E0D14))) {
            if (t.poster != null) {
                AsyncImage(model = t.poster, contentDescription = t.name, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize())
            } else BlankBox(t)
            Box(Modifier.fillMaxHeight().width(width * 0.07f).background(
                Brush.horizontalGradient(listOf(Color(0xCC000000), Color(0x33000000), Color.Transparent))))
            Box(Modifier.fillMaxSize().background(
                Brush.linearGradient(listOf(Color(0x33FFFFFF), Color.Transparent, Color.Transparent, Color(0x18FFFFFF)))))
        }
    }
    if (onClick == null) Box(Modifier.size(width, h)) { inner() } else Card(
        onClick = onClick,
        modifier = Modifier.size(width, h),
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1.1f),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Pink), shape = shape)),
        glow = CardDefaults.glow(),
    ) { inner() }
}

@Composable
fun BlankBox(t: Title) {
    Column(Modifier.fillMaxSize().background(Color(0xFF111018)).padding(10.dp)) {
        Box(Modifier.fillMaxWidth().height(5.dp).background(Pink))
        Spacer(Modifier.height(3.dp))
        Box(Modifier.fillMaxWidth().height(5.dp).background(Amber))
        Spacer(Modifier.height(3.dp))
        Box(Modifier.fillMaxWidth().height(5.dp).background(Cyan))
        Spacer(Modifier.weight(1f))
        Text(t.name.uppercase(), color = Cream, fontSize = 14.sp, fontWeight = FontWeight.Black, lineHeight = 16.sp,
            maxLines = 6, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        Text("VIDEOCASSETTE", color = Dim, fontSize = 8.sp, letterSpacing = 2.sp)
    }
}

@Composable
fun DetailScreen(t: Title) {
    val ctx = LocalContext.current
    val first = remember { FocusRequester() }
    LaunchedEffect(t.key) { runCatching { first.requestFocus() } }
    t.backdrop?.let {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                listOf(Color(0xF20B0A12), Color(0xE60B0A12), Color(0x990B0A12)))))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                listOf(Color.Transparent, Color(0xCC07060B)))))
        }
    }
    Row(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 40.dp)) {
        Column {
            VhsBox(t, width = 220.dp, onClick = null)
            if (t.poster != null && t.posterIsSuggestion) {
                Spacer(Modifier.height(8.dp))
                Text("TMDB art (suggested match,\nnot confirmed yet)", color = Dim, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.width(40.dp))
        Column(Modifier.weight(1f)) {
            Text(t.name, color = Cream, fontSize = 34.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(t.year, t.shelf, tapeCount(t)).joinToString("  ·  "), color = Dim, fontSize = 15.sp)
            t.overview?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = Cream.copy(alpha = 0.85f), fontSize = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(18.dp))
            Text("PICK A TAPE TO PLAY", color = Pink, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)) {
                itemsIndexed(t.tapes, key = { _, tp -> tp.fileId }) { i, tape ->
                    Surface(
                        onClick = { ctx.startActivity(PlayerActivity.intent(ctx, t.name, tape)) },
                        modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).fillMaxWidth(),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0x14FFFFFF),
                            focusedContainerColor = Color(0x33FF2E88), contentColor = Cream, focusedContentColor = Color.White),
                        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Pink))),
                    ) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("▶", color = Pink, fontSize = 18.sp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(tape.label, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                Text(tape.fileName, color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(listOfNotNull(tape.minutes?.let { "$it min" },
                                if (tape.sources.size > 1) "+${tape.sources.size - 1} backup" else null).joinToString("  ·  "),
                                color = Dim, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

