package org.vhsfever.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

sealed interface Section {
    data object Home : Section
    data object Browse : Section
    data object Genres : Section
    data class Genre(val name: String) : Section
    data object NewArrivals : Section
    data object Watchlist : Section
    data object Search : Section
    data object Curtain : Section
    data object Settings : Section
}

private data class NavItem(val label: String, val section: Section, val icon: ImageVector)

private fun icon(build: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) = ImageVector.Builder(
    defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).path(stroke = SolidColor(Color.White), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round, fill = null, pathBuilder = build).build()

private val NAV = listOf(
    NavItem("Home", Section.Home, icon { moveTo(3f, 11f); lineTo(12f, 3f); lineTo(21f, 11f); moveTo(5f, 10f); verticalLineTo(20f); horizontalLineTo(19f); verticalLineTo(10f); moveTo(10f, 20f); verticalLineTo(14f); horizontalLineTo(14f); verticalLineTo(20f) }),
    NavItem("Browse All", Section.Browse, icon { for ((x, y) in listOf(3f to 4f, 13f to 4f, 3f to 13f, 13f to 13f)) { moveTo(x, y); horizontalLineTo(x + 8); verticalLineTo(y + 7); horizontalLineTo(x); close() } }),
    NavItem("Genres", Section.Genres, icon { moveTo(3f, 9f); horizontalLineTo(21f); verticalLineTo(20f); horizontalLineTo(3f); close(); moveTo(3f, 9f); lineTo(5f, 4f); lineTo(9f, 5f); lineTo(7f, 9f); moveTo(9f, 5f); lineTo(13f, 6f); lineTo(11f, 10f); moveTo(13f, 6f); lineTo(17f, 7f); lineTo(15f, 11f) }),
    NavItem("New Arrivals", Section.NewArrivals, icon { moveTo(12f, 3f); verticalLineTo(7f); moveTo(12f, 17f); verticalLineTo(21f); moveTo(3f, 12f); horizontalLineTo(7f); moveTo(17f, 12f); horizontalLineTo(21f); moveTo(6f, 6f); lineTo(8.5f, 8.5f); moveTo(15.5f, 15.5f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(15.5f, 8.5f); moveTo(8.5f, 15.5f); lineTo(6f, 18f) }),
    NavItem("My Watchlist", Section.Watchlist, icon { moveTo(6f, 3f); horizontalLineTo(18f); verticalLineTo(21f); lineTo(12f, 17f); lineTo(6f, 21f); close() }),
    NavItem("Search", Section.Search, icon { moveTo(18f, 11f); arcTo(7f, 7f, 0f, true, true, 4f, 11f); arcTo(7f, 7f, 0f, true, true, 18f, 11f); moveTo(16.5f, 16.5f); lineTo(21f, 21f) }),
    // The back room: two curtain drapes hanging from a rod.
    NavItem("Red Curtain", Section.Curtain, icon { moveTo(3f, 4f); horizontalLineTo(21f); moveTo(5f, 4f); curveTo(5f, 11f, 7f, 16f, 11f, 20f); horizontalLineTo(5f); close(); moveTo(19f, 4f); curveTo(19f, 11f, 17f, 16f, 13f, 20f); horizontalLineTo(19f); close() }),
    NavItem("Settings", Section.Settings, icon { moveTo(15f, 12f); arcTo(3f, 3f, 0f, true, true, 9f, 12f); arcTo(3f, 3f, 0f, true, true, 15f, 12f); moveTo(12f, 2f); verticalLineTo(5f); moveTo(12f, 19f); verticalLineTo(22f); moveTo(2f, 12f); horizontalLineTo(5f); moveTo(19f, 12f); horizontalLineTo(22f); moveTo(5f, 5f); lineTo(7f, 7f); moveTo(17f, 17f); lineTo(19f, 19f); moveTo(19f, 5f); lineTo(17f, 7f); moveTo(7f, 17f); lineTo(5f, 19f) }),
)

/** The tilted VHS FEVER sign in the top-left corner. */
@Composable
fun LogoBadge(modifier: Modifier = Modifier) {
    Column(modifier.width(196.px).rotate(-3f).shadow(9.px, RoundedCornerShape(10.px))
        .background(Brush.linearGradient(listOf(Ink.BadgeTop, Ink.BadgeBottom), Offset.Zero, Offset(200f, 400f)), RoundedCornerShape(10.px))
        .border(3.px, Ink.Gold, RoundedCornerShape(10.px))
        .padding(start = 10.px, end = 10.px, top = 9.px, bottom = 10.px)) {
        FitText("VHS FEVER", style = TextStyle(fontFamily = Type.Oswald, fontWeight = FontWeight.Bold, fontSize = 35.fs, lineHeight = 35.fs,
            letterSpacing = 1f.fs, color = Ink.Gold, shadow = Shadow(Ink.BadgeShadow, Offset(3f, 3f), 0f)))
        FitText("THE VIDEO RENTAL STORE", modifier = Modifier.padding(top = 3.px), style = TextStyle(fontFamily = Type.Condensed,
            fontWeight = FontWeight.Bold, fontSize = 13.fs, letterSpacing = 1.8f.fs, color = Color.White))
        Box(Modifier.padding(top = 5.px).fillMaxWidth().height(4.px).background(Ink.Pink, RoundedCornerShape(2.px)))
    }
}

/** Grain over the whole picture, like the mockups. */
@Composable
fun Grain() {
    Image(painterResource(R.drawable.grain), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.18f)
}

/*
 * Highlight rules, used on every screen:
 *  - where you are (focused): solid gold with dark text, a little bigger. Pictures (boxes, tiles, spines,
 *    the TV) get a gold ring and glow and lift instead, since they can't be filled.
 *  - chosen but you're elsewhere (the current menu page, the current box view, the current tape): gold tint, gold text.
 *  - everything else: dark with white text.
 */
object Hi {
    val tint = Ink.Gold.alpha(0.2f)
    val ring = 3
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun StoreScreen(
    catalog: Catalog, section: Section, status: String, watchlist: Set<String>, restoreKey: String?,
    onSection: (Section) -> Unit, onOpen: (Title) -> Unit, onRefresh: () -> Unit,
    update: Updater.State = Updater.State.Idle, onCheckUpdate: () -> Unit = {},
    curtainPinSet: Boolean = false, onCurtainPin: (String) -> Boolean = { false }, onCurtainReset: () -> Unit = {},
) {
    val selectedFr = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Ink.NavyTop, 0.6f to Ink.Navy))) {
        when (section) {
            Section.Home -> HomeContent(catalog, restoreKey, onOpen, onSection)
            Section.Browse -> GridContent("Browse All", "Every tape in the store", catalog.titles, restoreKey, onOpen)
            Section.Genres -> GenresContent(catalog, onSection)
            is Section.Genre -> GridContent(section.name, "Genre", catalog.titles.filter { it.section == section.name }, restoreKey, onOpen)
            Section.NewArrivals -> GridContent("New Arrivals", "Newest tapes marked good", catalog.newArrivals, restoreKey, onOpen)
            Section.Watchlist -> GridContent("My Watchlist", "Tapes you saved for later",
                catalog.titles.filter { it.key in watchlist }, restoreKey, onOpen,
                empty = "Nothing saved yet. Press + WATCHLIST on a tape to keep it here.")
            Section.Search -> SearchContent(catalog, restoreKey, onOpen)
            Section.Curtain -> if (Curtain.open) {
                Column(Modifier.fillMaxSize()) {
                    GridContent("Behind the Red Curtain", "Adult tapes · 18+", catalog.curtain, restoreKey, onOpen,
                        empty = "No adult tapes marked good yet. Review them in the phone app, behind its curtain.")
                }
                Row(Modifier.align(Alignment.TopEnd).padding(top = 40.px, end = 40.px), horizontalArrangement = Arrangement.spacedBy(12.px)) {
                    OutlineButton("CLOSE THE CURTAIN", onClick = { Curtain.open = false })
                    OutlineButton("CHANGE PIN", onClick = onCurtainReset)
                }
            } else PinPad(curtainPinSet, onCurtainPin)
            Section.Settings -> SettingsContent(catalog, status, onRefresh, update, onCheckUpdate)
        }
        LogoBadge(Modifier.offset(18.px, 16.px))
        // Coming back to the menu lands on the page you're on, not wherever the remote happens to point.
        Column(Modifier.offset(16.px, 140.px).width(204.px).focusRestorer { selectedFr }.focusGroup(),
            verticalArrangement = Arrangement.spacedBy(4.px)) {
            NAV.forEach { n ->
                val selected = when (section) { is Section.Genre -> n.section == Section.Genres; else -> n.section == section }
                RailButton(n, selected, if (selected) Modifier.focusRequester(selectedFr) else Modifier) { onSection(n.section) }
            }
        }
        if (status.isNotBlank() && section != Section.Settings) {
            Text(status, modifier = Modifier.align(Alignment.BottomStart).padding(start = 22.px, bottom = 18.px).width(200.px),
                style = TextStyle(fontFamily = Type.Mono, fontSize = 11.fs, color = Ink.Gold))
        }
        Grain()
    }
}

@Composable
private fun RailButton(n: NavItem, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(44.px),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.px)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Hi.tint else Color.Transparent, focusedContainerColor = Ink.Gold,
            contentColor = if (selected) Ink.Gold else Ink.RailText, focusedContentColor = Ink.Dark,
        ),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.px), verticalAlignment = Alignment.CenterVertically) {
            Icon(n.icon, null, Modifier.size(24.px))
            Spacer(Modifier.width(14.px))
            FitText(n.label, style = TextStyle(fontFamily = Type.Barlow, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = 19.fs))
        }
    }
}

/**
 * A box on the shelf. DVDs are wider than VHS, like the real cases. Highlighting lifts it with a gold ring;
 * only the drawing lifts, so Up/Down on the remote still go between rows.
 */
@Composable
fun ShelfBox(t: Title, vhsWidth: Int, h: Int, restore: Boolean, onOpen: (Title) -> Unit, onFocused: (Title) -> Unit = {}) {
    val fr = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    if (restore) LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    val w = boxWidth(t, vhsWidth)
    val shape = RoundedCornerShape(4.px)
    Box(Modifier.lift(if (focused) (-8).px else 0.px)) {
        Surface(
            onClick = { onOpen(t) },
            modifier = Modifier.size(w.px, h.px).focusRequester(fr).onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused(t)
            },
            shape = ClickableSurfaceDefaults.shape(shape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color.Black, focusedContainerColor = Color.Black),
            border = ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(1.dp, Color.White.alpha(0.2f)), shape = shape),
                focusedBorder = Border(BorderStroke(Hi.ring.px, Ink.Gold), shape = shape)),
            glow = ClickableSurfaceDefaults.glow(focusedGlow = androidx.tv.material3.Glow(Ink.Gold.alpha(0.5f), 26.px)),
        ) {
            BoxFront(t, scale = vhsWidth / 86f, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun RowHeader(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = TextStyle(fontFamily = Type.Barlow, fontWeight = FontWeight.Bold, fontSize = 23.fs, color = Color.White))
        Spacer(Modifier.width(8.px))
        Text("›", style = TextStyle(fontFamily = Type.Barlow, fontWeight = FontWeight.Bold, fontSize = 30.fs, color = Color.White))
    }
}

/*
 * Where the store banner sits and where the TV screen in it is, in mockup pixels.
 * The banner (2172×724) is scaled to 352 tall and runs to the right edge, so its TV sits at the right.
 */
private const val HERO_H = 352f
private const val PHOTO_SCALE = HERO_H / 724f
private const val HERO_W = 2172 * PHOTO_SCALE
private const val HERO_X = 1280 - HERO_W
private const val PHOTO_X = 0f
private const val PHOTO_Y = 0f
private const val TV_X = PHOTO_X + 1606 * PHOTO_SCALE
private const val TV_Y = PHOTO_Y + 200 * PHOTO_SCALE
private const val TV_W = 387 * PHOTO_SCALE
private const val TV_H = 272 * PHOTO_SCALE

/** The store photo at the top of Home. The highlighted movie plays (muted) on the TV in the photo. */
@Composable
private fun HeroPhoto(playing: Title?) {
    Box(Modifier.offset(HERO_X.px, 0.px).size(HERO_W.px, HERO_H.px).clipToBounds()) {
        Image(painterResource(R.drawable.store_hero), null, contentScale = ContentScale.FillBounds,
            modifier = Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).offset(PHOTO_X.px, PHOTO_Y.px)
                .size((2172 * PHOTO_SCALE).px, (724 * PHOTO_SCALE).px))
        // The highlighted movie's trailer; a clip of the tape only when it has no trailer YouTube will play here.
        val trailers = rememberTrailers(playing)
        var trailerFailed by remember(playing?.key) { mutableStateOf(false) }
        val trailerKeys = trailers?.takeIf { it.isNotEmpty() && !trailerFailed }
        val tape = playing?.tapes?.firstOrNull()?.takeIf { trailers != null && trailerKeys == null }
        if (trailerKeys != null || tape != null) {
            var showing by remember(playing?.key) { mutableStateOf(false) }
            Box(Modifier.offset(TV_X.px, TV_Y.px).size(TV_W.px, TV_H.px).clip(RoundedCornerShape(12.px))
                .graphicsLayer { alpha = if (showing) 1f else 0f }.background(Color.Black)) {
                if (trailerKeys != null) TrailerVideo(trailerKeys, onShowing = { showing = it }, onTime = {},
                    onFailed = { showing = false; trailerFailed = true })
                else if (tape != null) TapeVideo(tape, onShowing = { showing = it }, onPosition = {})
                CrtGlass(k = 0.8f)
                Text("PLAY ▶", modifier = Modifier.padding(start = 10.px, top = 6.px),
                    style = TextStyle(fontFamily = Type.Vt, fontSize = 18.fs, color = Color.White, shadow = Shadow(Color.Black, Offset(1f, 1f), 0f)))
            }
        }
        // fade the left under the menu and welcome text, and the bottom into the shelves
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
            0f to Ink.NavyTop, 0.08f to Color(0xE60C1340), 0.45f to Color(0x660C1340), 0.6f to Color(0x000C1340))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.72f to Color(0x00080B22), 1f to Ink.Navy)))
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun HomeContent(catalog: Catalog, restoreKey: String?, onOpen: (Title) -> Unit, onSection: (Section) -> Unit) {
    val first = remember { FocusRequester() }
    val rows = remember(catalog) {
        buildList {
            add("New Arrivals" to catalog.newArrivals.take(16))
            // Every genre gets its own shelf, biggest first.
            for ((g, list) in catalog.genreShelves) add(g to list)
        }.filter { it.second.isNotEmpty() }
    }
    val restoreRow = rows.indexOfFirst { r -> r.second.any { it.key == restoreKey } }
    // which row the remote is on (-1 = genre tiles); below the first row the tiles fold away to show more shelf
    var focusRow by remember { mutableIntStateOf(restoreRow.coerceAtLeast(0)) }
    var highlighted by remember { mutableStateOf(rows.getOrNull(restoreRow)?.second?.firstOrNull { it.key == restoreKey }) }
    var playing by remember { mutableStateOf<Title?>(null) }
    LaunchedEffect(highlighted?.key) {
        playing = null
        delay(900)            // wait for the remote to settle before loading a video
        playing = highlighted
    }
    // start on the first box (try for a moment, since the shelf may still be laying out)
    LaunchedEffect(Unit) {
        if (restoreKey == null) repeat(10) { if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect; delay(100) }
    }
    val collapsed = focusRow > 0
    // keep the shelf you're on lined up at the top, instead of a half-cut shelf above it
    val listState = rememberLazyListState()
    LaunchedEffect(focusRow) { if (focusRow >= 0) runCatching { listState.animateScrollToItem(focusRow) } }
    val rowsTop by animateDpAsState(if (collapsed) 352.px else 452.px, label = "rowsTop")
    val genres = catalog.genreShelves.map { it.first }.take(8)

    Box(Modifier.fillMaxSize()) {
        HeroPhoto(playing)
        Column(Modifier.offset(252.px, 112.px).width(440.px)) {
            val sh = Shadow(Color(0xB3000000), Offset(0f, 4f), 10f)
            Text("Welcome to", style = TextStyle(fontFamily = Type.Playfair, fontSize = 36.fs, lineHeight = 36.fs, color = Color.White, shadow = sh))
            FitText("VHS FEVER", style = TextStyle(fontFamily = Type.Playfair, fontSize = 58.fs, lineHeight = 60.fs, color = Color.White, shadow = sh))
            Box(Modifier.padding(top = 2.px).size(250.px, 5.px).background(Ink.Pink, RoundedCornerShape(3.px)))
            Text(highlighted?.let { h -> h.name + (h.year?.let { " · $it" } ?: "") }
                ?: "Classic, cult and forgotten tapes from the Internet Archive. Rewound and ready.",
                modifier = Modifier.padding(top = 16.px).width(400.px), maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Barlow, fontWeight = if (highlighted != null) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 18.fs, lineHeight = 24.fs, color = Color(0xFFE9EBFF), shadow = Shadow(Color(0xCC000000), Offset(0f, 2f), 6f)))
        }
        AnimatedVisibility(!collapsed && genres.isNotEmpty(), enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.offset(0.px, 352.px)) {
            LazyRow(Modifier.fillMaxWidth().padding(start = 232.px).clipToBounds().focusRestorer(),
                contentPadding = PaddingValues(start = 20.px, end = 40.px, top = 8.px, bottom = 10.px),
                horizontalArrangement = Arrangement.spacedBy(12.px)) {
                items(genres) { g ->
                    Box(Modifier.onFocusChanged { if (it.hasFocus) focusRow = -1 }) {
                        GenreTile(g, 150, 84) { onSection(Section.Genre(g)) }
                    }
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(top = rowsTop), state = listState, contentPadding = PaddingValues(top = 4.px, bottom = 400.px)) {
            rows.forEachIndexed { ri, (name, list) ->
                item(key = "row-$name") {
                    Column(Modifier.padding(bottom = 8.px)) {
                        Box(Modifier.padding(start = 252.px)) { RowHeader(name) }
                        // shelves stop at the menu's edge instead of sliding under it
                        LazyRow(Modifier.padding(start = 232.px).clipToBounds().focusRestorer(),
                            contentPadding = PaddingValues(start = 20.px, end = 60.px, top = 16.px, bottom = 16.px),
                            horizontalArrangement = Arrangement.spacedBy(14.px), verticalAlignment = Alignment.Bottom) {
                            items(list, key = { it.key }) { t ->
                                Box(if (ri == 0 && t == list.first()) Modifier.focusRequester(first) else Modifier) {
                                    ShelfBox(t, 86, 152, ri == restoreRow && t.key == restoreKey, onOpen,
                                        onFocused = { highlighted = it; focusRow = ri })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GenreTile(name: String, w: Int, h: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.px)
    Surface(
        onClick = onClick,
        modifier = Modifier.size(w.px, h.px),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(2.px, Color.White.alpha(0.55f)), shape = shape),
            focusedBorder = Border(BorderStroke(Hi.ring.px, Ink.Gold), shape = shape)),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = androidx.tv.material3.Glow(Ink.Gold.alpha(0.5f), 20.px)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Black, focusedContainerColor = Color.Black),
    ) { GenreArt(name, scale = w / 150f) }
}

@Composable
private fun SectionHeader(title: String, sub: String) {
    Column(Modifier.padding(start = 252.px, top = 40.px, bottom = 18.px, end = 40.px)) {
        Text(sub.uppercase(), style = TextStyle(fontFamily = Type.Mono, fontSize = 13.fs, letterSpacing = 1.6f.fs, color = Ink.Gold))
        FitText(title, style = TextStyle(fontFamily = Type.Playfair, fontSize = 52.fs, lineHeight = 56.fs, color = Color.White,
            shadow = Shadow(Color(0xB3000000), Offset(0f, 4f), 10f)))
        Box(Modifier.padding(top = 4.px).size(180.px, 5.px).background(Ink.Pink, RoundedCornerShape(3.px)))
    }
}

/** A grid of boxes; each cell fits a DVD, and VHS boxes stand in the middle of theirs. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BoxGrid(titles: List<Title>, restoreKey: String?, onOpen: (Title) -> Unit, first: FocusRequester?, top: Int) {
    // The grid starts where the menu ends (not under it), so Right from the menu finds it, and comes back to the box you left.
    LazyVerticalGrid(GridCells.Adaptive(150.px), Modifier.fillMaxSize().padding(start = 232.px).focusRestorer(),
        contentPadding = PaddingValues(start = 20.px, end = 40.px, top = top.px, bottom = 40.px),
        horizontalArrangement = Arrangement.spacedBy(10.px), verticalArrangement = Arrangement.spacedBy(26.px)) {
        items(titles, key = { it.key }) { t ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                Box(if (first != null && t == titles.first()) Modifier.focusRequester(first) else Modifier) {
                    ShelfBox(t, 112, 198, t.key == restoreKey, onOpen)
                }
            }
        }
    }
}

@Composable
private fun GridContent(title: String, sub: String, titles: List<Title>, restoreKey: String?, onOpen: (Title) -> Unit, empty: String = "No tapes here yet.") {
    val first = remember { FocusRequester() }
    LaunchedEffect(title) { if (restoreKey == null || titles.none { it.key == restoreKey }) runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize()) {
        SectionHeader(title, "$sub · ${titles.size}")
        if (titles.isEmpty()) Text(empty, modifier = Modifier.padding(start = 252.px),
            style = TextStyle(fontFamily = Type.Barlow, fontSize = 22.fs, color = Ink.Body))
        BoxGrid(titles, restoreKey, onOpen, first, 14)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun GenresContent(catalog: Catalog, onSection: (Section) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize()) {
        SectionHeader("Genres", "Pick a shelf · ${catalog.genreShelves.size}")
        if (catalog.genreShelves.isEmpty()) Text("Genres show up once the TMDB info has loaded.", modifier = Modifier.padding(start = 252.px),
            style = TextStyle(fontFamily = Type.Barlow, fontSize = 22.fs, color = Ink.Body))
        LazyVerticalGrid(GridCells.Adaptive(226.px), Modifier.fillMaxSize().padding(start = 232.px).focusRestorer(),
            contentPadding = PaddingValues(start = 20.px, end = 40.px, top = 10.px, bottom = 40.px),
            horizontalArrangement = Arrangement.spacedBy(20.px), verticalArrangement = Arrangement.spacedBy(20.px)) {
            items(catalog.genreShelves, key = { it.first }) { (g, list) ->
                Column(if (g == catalog.genreShelves.first().first) Modifier.focusRequester(first) else Modifier) {
                    GenreTile(g, 210, 118) { onSection(Section.Genre(g)) }
                    Text("${list.size} ${if (list.size == 1) "title" else "titles"}", modifier = Modifier.padding(top = 6.px),
                        style = TextStyle(fontFamily = Type.Mono, fontSize = 12.fs, color = Ink.Soft))
                }
            }
        }
    }
}

@Composable
private fun SearchContent(catalog: Catalog, restoreKey: String?, onOpen: (Title) -> Unit) {
    var q by remember { mutableStateOf("") }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (restoreKey == null) runCatching { field.requestFocus() } }
    val words = q.lowercase().split(' ').filter { it.isNotBlank() }
    val hits = if (words.isEmpty()) emptyList() else catalog.titles.filter { t ->
        val hay = (t.name + " " + t.tapes.joinToString(" ") { it.fileName } + " " + t.genres.joinToString(" ")).lowercase()
        words.all { it in hay }
    }
    Column(Modifier.fillMaxSize()) {
        SectionHeader("Search", if (words.isEmpty()) "Type a title" else "${hits.size} found")
        var focused by remember { mutableStateOf(false) }
        BasicTextField(q, { q = it }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            textStyle = TextStyle(fontFamily = Type.Barlow, fontWeight = FontWeight.Medium, fontSize = 26.fs, color = Color.White),
            cursorBrush = SolidColor(Ink.Gold),
            modifier = Modifier.padding(start = 252.px).width(640.px).focusRequester(field).onFocusChanged { focused = it.isFocused }
                .background(if (focused) Hi.tint else Color(0xB3080B22), RoundedCornerShape(10.px))
                .border(if (focused) Hi.ring.px else 2.px, if (focused) Ink.Gold else Color.White.alpha(0.5f), RoundedCornerShape(10.px))
                .padding(horizontal = 18.px, vertical = 12.px),
            decorationBox = { inner ->
                if (q.isEmpty()) Text("Title, year or file name…", style = TextStyle(fontFamily = Type.Barlow, fontSize = 26.fs, color = Ink.Muted))
                inner()
            })
        BoxGrid(hits, restoreKey, onOpen, null, 28)
    }
}

@Composable
private fun SettingsContent(catalog: Catalog, status: String, onRefresh: () -> Unit, update: Updater.State, onCheckUpdate: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val tapes = catalog.titles.sumOf { it.tapes.size }
    Column(Modifier.fillMaxSize()) {
        SectionHeader("Settings", "VHS FEVER TV")
        Column(Modifier.padding(start = 252.px).width(700.px)) {
            for ((k, v) in listOf(
                "IN THE STORE" to "${catalog.titles.size} titles · $tapes tapes",
                "WHERE THE TAPES COME FROM" to "Files marked good in the review app",
                "BOX ART" to "TMDB posters for now · tape scans coming",
                "APP VERSION" to "Build ${Updater.currentBuild}",
                "UPDATES" to when (update) {
                    Updater.State.Idle -> "Checked automatically when the app opens"
                    Updater.State.Checking -> "Checking…"
                    Updater.State.UpToDate -> "Up to date"
                    is Updater.State.Downloading -> "Downloading build ${update.build}… ${update.percent}%"
                    is Updater.State.Ready -> "Build ${update.build} ready to install"
                    is Updater.State.Failed -> update.message
                },
            )) {
                Row(Modifier.fillMaxWidth().drawBehind {
                    drawLine(Color(0x4DCFD4FF), Offset(0f, size.height), Offset(size.width, size.height), 1f)
                }.padding(vertical = 12.px), horizontalArrangement = Arrangement.spacedBy(16.px), verticalAlignment = Alignment.CenterVertically) {
                    Text(k, style = TextStyle(fontFamily = Type.Mono, fontSize = 13.fs, letterSpacing = 1.3f.fs, color = Ink.Muted))
                    Text(v, modifier = Modifier.weight(1f), style = TextStyle(fontFamily = Type.Barlow, fontWeight = FontWeight.Medium,
                        fontSize = 21.fs, color = Color.White, textAlign = TextAlign.End))
                }
            }
            Spacer(Modifier.height(26.px))
            Row(horizontalArrangement = Arrangement.spacedBy(12.px)) {
                GoldButton("↻ CHECK FOR NEW TAPES", Modifier.focusRequester(first), onRefresh)
                OutlineButton("⇩ CHECK FOR APP UPDATE", onClick = onCheckUpdate)
            }
            if (status.isNotBlank()) Text(status, modifier = Modifier.padding(top = 12.px),
                style = TextStyle(fontFamily = Type.Mono, fontSize = 13.fs, color = Ink.Gold))
        }
    }
}

/** The main action. Gold outline when you're elsewhere, solid gold when you're on it. */
@Composable
fun GoldButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
    StoreButton(text, modifier, Ink.Gold, Ink.Gold, onClick)

/** Other actions. White outline when you're elsewhere, solid gold when you're on it. */
@Composable
fun OutlineButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
    StoreButton(text, modifier, Color.White.alpha(0.6f), Color.White, onClick)

@Composable
fun StoreButton(text: String, modifier: Modifier, edge: Color, label: Color, onClick: () -> Unit, size: Int = 24, chosen: Boolean = false) {
    val shape = RoundedCornerShape(8.px)
    Surface(onClick = onClick, modifier = modifier.heightIn(min = 48.px),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        colors = ClickableSurfaceDefaults.colors(containerColor = if (chosen) Hi.tint else Color(0x99080B22), focusedContainerColor = Ink.Gold,
            contentColor = if (chosen) Ink.Gold else label, focusedContentColor = Ink.Dark),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(2.px, if (chosen) Ink.Gold else edge), shape = shape),
            focusedBorder = Border(BorderStroke(2.px, Ink.Gold), shape = shape)),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = androidx.tv.material3.Glow(Ink.Gold.alpha(0.4f), 24.px)),
    ) {
        Box(Modifier.padding(horizontal = 22.px, vertical = 8.px), contentAlignment = Alignment.Center) {
            Text(text, maxLines = 1, softWrap = false,
                style = TextStyle(fontFamily = Type.Condensed, fontWeight = FontWeight.Bold, fontSize = size.fs, letterSpacing = (size * 0.08f).fs))
        }
    }
}

/** Small note in the corner while an update downloads. */
@Composable
fun UpdateBadge(text: String, modifier: Modifier = Modifier) {
    Box(modifier.padding(24.px).background(Color(0xE6080B22), RoundedCornerShape(8.px))
        .border(2.px, Ink.Gold, RoundedCornerShape(8.px)).padding(horizontal = 16.px, vertical = 10.px)) {
        Text(text, style = TextStyle(fontFamily = Type.Mono, fontSize = 14.fs, color = Ink.Gold))
    }
}

/** "A new version is ready" with Install / Later. Install may first open the TV's permission screen. */
@Composable
fun UpdateDialog(build: Int, onInstall: () -> Boolean, onLater: () -> Unit) {
    val fr = remember { FocusRequester() }
    var needsPermission by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    androidx.activity.compose.BackHandler { onLater() }
    Box(Modifier.fillMaxSize().background(Color(0xCC04051A)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(620.px).background(Brush.verticalGradient(listOf(Ink.NavyTop, Ink.Navy)), RoundedCornerShape(12.px))
            .border(3.px, Ink.Gold, RoundedCornerShape(12.px)).padding(32.px)) {
            Text("NEW VERSION READY", style = TextStyle(fontFamily = Type.Mono, fontSize = 14.fs, letterSpacing = 1.6f.fs, color = Ink.Gold))
            Text("VHS FEVER build $build", style = TextStyle(fontFamily = Type.Oswald, fontWeight = FontWeight.Bold, fontSize = 36.fs, color = Color.White))
            Text(if (needsPermission) "Your TV opened its setting for installing apps. Turn it on for VHS FEVER, press Back, then press Install again."
                else "It’s downloaded. Install it now? The app will restart on the new version.",
                modifier = Modifier.padding(top = 10.px),
                style = TextStyle(fontFamily = Type.Barlow, fontSize = 20.fs, lineHeight = 27.fs, color = Ink.Body))
            Row(Modifier.padding(top = 24.px), horizontalArrangement = Arrangement.spacedBy(12.px)) {
                GoldButton("INSTALL", Modifier.focusRequester(fr)) { if (!onInstall()) needsPermission = true }
                OutlineButton("LATER", onClick = onLater)
            }
        }
    }
}

/**
 * In front of the adult tapes: a red curtain and a number pad for the PIN. The first time, the PIN
 * typed here becomes the PIN (4 to 8 numbers).
 */
@Composable
private fun PinPad(pinSet: Boolean, onPin: (String) -> Boolean) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val drape = Brush.horizontalGradient(listOf(Color(0xFF4A060B), Color(0xFF9E1B22), Color(0xFF5E0A10), Color(0xFFB02129),
        Color(0xFF4A060B), Color(0xFF9E1B22), Color(0xFF5E0A10)))
    Box(Modifier.fillMaxSize().padding(start = 236.px).background(drape)) {
        Column(Modifier.align(Alignment.Center).background(Color(0xE6080B22), RoundedCornerShape(16.px))
            .border(2.px, Ink.Gold, RoundedCornerShape(16.px)).padding(28.px),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.px)) {
            Text("ADULTS ONLY · 18+", style = TextStyle(fontFamily = Type.Mono, fontSize = 14.fs, letterSpacing = 2f.fs, color = Ink.Gold))
            Text(if (pinSet) "Enter the PIN" else "Pick a PIN for the curtain",
                style = TextStyle(fontFamily = Type.Barlow, fontWeight = FontWeight.Bold, fontSize = 28.fs, color = Color.White))
            Text(if (pin.isEmpty()) " " else "●".repeat(pin.length),
                style = TextStyle(fontFamily = Type.Mono, fontSize = 30.fs, letterSpacing = 8f.fs, color = Color.White))
            Text(when { wrong -> "Wrong PIN"; !pinSet -> "4 to 8 numbers. You'll need it each time you open the app."; else -> " " },
                style = TextStyle(fontFamily = Type.Barlow, fontSize = 17.fs, color = if (wrong) Ink.Pink else Ink.Soft))
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK")
            for (row in keys.chunked(3)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.px)) {
                    for (k in row) {
                        val mod = Modifier.width(92.px).let { if (k == "1") it.focusRequester(first) else it }
                        val press = {
                            wrong = false
                            when (k) {
                                "⌫" -> pin = pin.dropLast(1)
                                "OK" -> if (pin.length >= 4) { if (!onPin(pin)) wrong = true; pin = "" }
                                else -> if (pin.length < 8) pin += k
                            }
                        }
                        if (k == "OK") GoldButton(k, mod, press) else OutlineButton(k, mod, press)
                    }
                }
            }
        }
    }
}
