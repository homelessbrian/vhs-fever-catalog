package org.vhsfever.tv

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
    NavItem("Settings", Section.Settings, icon { moveTo(15f, 12f); arcTo(3f, 3f, 0f, true, true, 9f, 12f); arcTo(3f, 3f, 0f, true, true, 15f, 12f); moveTo(12f, 2f); verticalLineTo(5f); moveTo(12f, 19f); verticalLineTo(22f); moveTo(2f, 12f); horizontalLineTo(5f); moveTo(19f, 12f); horizontalLineTo(22f); moveTo(5f, 5f); lineTo(7f, 7f); moveTo(17f, 17f); lineTo(19f, 19f); moveTo(19f, 5f); lineTo(17f, 7f); moveTo(7f, 17f); lineTo(5f, 19f) }),
)

/** The tilted VHS FEVER sign in the top-left corner. */
@Composable
fun LogoBadge(modifier: Modifier = Modifier) {
    Column(modifier.width(196.px).rotate(-3f).shadow(9.px, RoundedCornerShape(10.px))
        .background(Brush.linearGradient(listOf(Ink.BadgeTop, Ink.BadgeBottom), Offset.Zero, Offset(200f, 400f)), RoundedCornerShape(10.px))
        .border(3.px, Ink.Gold, RoundedCornerShape(10.px))
        .padding(start = 10.px, end = 10.px, top = 9.px, bottom = 10.px)) {
        Text("VHS FEVER", style = TextStyle(fontFamily = Type.Oswald, fontWeight = FontWeight.Bold, fontSize = 35.fs, lineHeight = 35.fs,
            letterSpacing = 1f.fs, color = Ink.Gold, shadow = Shadow(Ink.BadgeShadow, Offset(3f, 3f), 0f)))
        Text("THE VIDEO RENTAL STORE", modifier = Modifier.padding(top = 3.px), style = TextStyle(fontFamily = Type.Condensed,
            fontWeight = FontWeight.Bold, fontSize = 13.fs, letterSpacing = 1.8f.fs, color = Color.White))
        Box(Modifier.padding(top = 5.px).fillMaxWidth().height(4.px).background(Ink.Pink, RoundedCornerShape(2.px)))
    }
}

/** Grain over the whole picture, like the mockups. */
@Composable
fun Grain() {
    Image(painterResource(R.drawable.grain), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.35f)
}

@Composable
fun StoreScreen(
    catalog: Catalog, section: Section, status: String, watchlist: Set<String>, restoreKey: String?,
    onSection: (Section) -> Unit, onOpen: (Title) -> Unit, onRefresh: () -> Unit,
) {
    val railFocus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Ink.NavyTop, 0.6f to Ink.Navy))) {
        when (section) {
            Section.Home -> HomeContent(catalog, restoreKey, onOpen, onSection)
            Section.Browse -> GridContent("Browse All", "Every tape in the store", catalog.titles, restoreKey, onOpen)
            Section.Genres -> GenresContent(catalog, onSection)
            is Section.Genre -> GridContent(section.name, "Genre", catalog.titles.filter { section.name in it.genres }, restoreKey, onOpen)
            Section.NewArrivals -> GridContent("New Arrivals", "Newest tapes marked good", catalog.newArrivals, restoreKey, onOpen)
            Section.Watchlist -> GridContent("My Watchlist", "Tapes you saved for later",
                catalog.titles.filter { it.key in watchlist }, restoreKey, onOpen,
                empty = "Nothing saved yet. Press + WATCHLIST on a tape to keep it here.")
            Section.Search -> SearchContent(catalog, restoreKey, onOpen)
            Section.Settings -> SettingsContent(catalog, status, onRefresh)
        }
        LogoBadge(Modifier.offset(18.px, 16.px))
        Column(Modifier.offset(16.px, 134.px).width(204.px), verticalArrangement = Arrangement.spacedBy(2.px)) {
            NAV.forEachIndexed { i, n ->
                val selected = when (section) { is Section.Genre -> n.section == Section.Genres; else -> n.section == section }
                RailButton(n, selected, if (i == 0) Modifier.focusRequester(railFocus) else Modifier) { onSection(n.section) }
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
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Ink.Gold else Color.Transparent,
            focusedContainerColor = if (selected) Ink.Gold else Color.White.alpha(0.14f),
            contentColor = if (selected) Ink.Dark else Ink.RailText,
            focusedContentColor = if (selected) Ink.Dark else Color.White,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.px, if (selected) Color.White else Ink.Gold), shape = RoundedCornerShape(10.px))),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.px), verticalAlignment = Alignment.CenterVertically) {
            Icon(n.icon, null, Modifier.size(24.px))
            Spacer(Modifier.width(14.px))
            Text(n.label, style = TextStyle(fontFamily = Type.Barlow, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = 19.fs))
        }
    }
}

/** A box on the shelf. Focus lifts it off the shelf with a gold ring, like the mockup. */
@Composable
fun ShelfBox(t: Title, w: Int, h: Int, restore: Boolean, onOpen: (Title) -> Unit) {
    val fr = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    if (restore) LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Surface(
        onClick = { onOpen(t) },
        modifier = Modifier.size(w.px, h.px).focusRequester(fr).onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.offset(y = (-8).px) else Modifier),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.px)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Black, focusedContainerColor = Color.Black),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Color.White.alpha(0.2f)), shape = RoundedCornerShape(4.px)),
            focusedBorder = Border(BorderStroke(3.px, Ink.Gold), shape = RoundedCornerShape(4.px))),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = androidx.tv.material3.Glow(Ink.Gold.alpha(0.5f), 26.px)),
    ) {
        BoxFront(t, scale = w / 86f, modifier = Modifier.fillMaxSize())
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

@Composable
private fun HomeContent(catalog: Catalog, restoreKey: String?, onOpen: (Title) -> Unit, onSection: (Section) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (restoreKey == null) runCatching { first.requestFocus() } }
    val rows = buildList {
        add("New Arrivals" to catalog.newArrivals.take(14))
        for ((g, list) in catalog.genreShelves.take(3)) add(g to list)
        for ((shelf, list) in catalog.shelves) add(shelf to list)
    }.filter { it.second.isNotEmpty() }
    val restoreRow = rows.indexOfFirst { r -> r.second.any { it.key == restoreKey } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.px)) {
        item(key = "hero") {
            Box(Modifier.fillMaxWidth().height(452.px)) {
                Box(Modifier.offset(330.px, 0.px).size(950.px, 346.px)) {
                    Image(painterResource(R.drawable.store_hero), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                        alignment = androidx.compose.ui.BiasAlignment(0f, -0.1f))
                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                        0f to Ink.NavyTop, 0.12f to Color(0xD90C1340), 0.55f to Color(0x000C1340))))
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.62f to Color(0x00080B22), 1f to Ink.Navy)))
                }
                Column(Modifier.offset(252.px, 112.px).width(480.px)) {
                    val sh = Shadow(Color(0xB3000000), Offset(0f, 4f), 10f)
                    Text("Welcome to", style = TextStyle(fontFamily = Type.Playfair, fontSize = 36.fs, lineHeight = 36.fs, color = Color.White, shadow = sh))
                    Text("VHS FEVER", style = TextStyle(fontFamily = Type.Playfair, fontSize = 58.fs, lineHeight = 60.fs, color = Color.White, shadow = sh))
                    Box(Modifier.padding(top = 2.px).size(250.px, 5.px).shadow(6.px, RoundedCornerShape(3.px), ambientColor = Ink.Pink, spotColor = Ink.Pink)
                        .background(Ink.Pink, RoundedCornerShape(3.px)))
                    Text("Classic, cult and forgotten tapes from the Internet Archive. Rewound and ready.",
                        modifier = Modifier.padding(top = 16.px).width(400.px),
                        style = TextStyle(fontFamily = Type.Barlow, fontSize = 18.fs, lineHeight = 24.fs, color = Color(0xFFE9EBFF),
                            shadow = Shadow(Color(0xCC000000), Offset(0f, 2f), 6f)))
                }
                val genres = catalog.genreShelves.map { it.first }.take(6)
                if (genres.isNotEmpty()) LazyRow(Modifier.offset(0.px, 352.px).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 252.px, end = 40.px), horizontalArrangement = Arrangement.spacedBy(12.px)) {
                    items(genres) { g -> GenreTile(g, 150, 84) { onSection(Section.Genre(g)) } }
                }
            }
        }
        rows.forEachIndexed { ri, (name, list) ->
            item(key = "row-$name") {
                Column(Modifier.padding(bottom = 16.px)) {
                    Box(Modifier.padding(start = 252.px)) { RowHeader(name) }
                    LazyRow(contentPadding = PaddingValues(start = 252.px, end = 40.px, top = 16.px, bottom = 12.px),
                        horizontalArrangement = Arrangement.spacedBy(14.px)) {
                        items(list, key = { it.key }) { t ->
                            val r = ri == restoreRow && t.key == restoreKey
                            Box(if (ri == 0 && t == list.first()) Modifier.focusRequester(first) else Modifier) {
                                ShelfBox(t, 86, 152, r, onOpen)
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
    Surface(
        onClick = onClick,
        modifier = Modifier.size(w.px, h.px),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.px)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(2.px, Color.White.alpha(0.55f)), shape = RoundedCornerShape(10.px)),
            focusedBorder = Border(BorderStroke(3.px, Ink.Gold), shape = RoundedCornerShape(10.px))),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Black, focusedContainerColor = Color.Black),
    ) { GenreArt(name, scale = w / 150f) }
}

@Composable
private fun SectionHeader(title: String, sub: String) {
    Column(Modifier.padding(start = 252.px, top = 40.px, bottom = 18.px)) {
        Text(sub.uppercase(), style = TextStyle(fontFamily = Type.Mono, fontSize = 13.fs, letterSpacing = 1.6f.fs, color = Ink.Gold))
        Text(title, style = TextStyle(fontFamily = Type.Playfair, fontSize = 52.fs, lineHeight = 56.fs, color = Color.White,
            shadow = Shadow(Color(0xB3000000), Offset(0f, 4f), 10f)))
        Box(Modifier.padding(top = 4.px).size(180.px, 5.px).background(Ink.Pink, RoundedCornerShape(3.px)))
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
        LazyVerticalGrid(GridCells.Adaptive(126.px), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 252.px, end = 40.px, top = 14.px, bottom = 40.px),
            horizontalArrangement = Arrangement.spacedBy(22.px), verticalArrangement = Arrangement.spacedBy(26.px)) {
            items(titles, key = { it.key }) { t ->
                Box(if (t == titles.first()) Modifier.focusRequester(first) else Modifier) {
                    ShelfBox(t, 112, 198, t.key == restoreKey, onOpen)
                }
            }
        }
    }
}

@Composable
private fun GenresContent(catalog: Catalog, onSection: (Section) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize()) {
        SectionHeader("Genres", "Pick a shelf · ${catalog.genreShelves.size}")
        if (catalog.genreShelves.isEmpty()) Text("Genres show up once the TMDB info has loaded.", modifier = Modifier.padding(start = 252.px),
            style = TextStyle(fontFamily = Type.Barlow, fontSize = 22.fs, color = Ink.Body))
        LazyVerticalGrid(GridCells.Adaptive(226.px), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 252.px, end = 40.px, top = 10.px, bottom = 40.px),
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
                .background(Color(0xB3080B22), RoundedCornerShape(10.px))
                .border(if (focused) 3.px else 2.px, if (focused) Ink.Gold else Color.White.alpha(0.5f), RoundedCornerShape(10.px))
                .padding(horizontal = 18.px, vertical = 12.px),
            decorationBox = { inner ->
                if (q.isEmpty()) Text("Title, year or file name…", style = TextStyle(fontFamily = Type.Barlow, fontSize = 26.fs, color = Ink.Muted))
                inner()
            })
        LazyVerticalGrid(GridCells.Adaptive(126.px), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 252.px, end = 40.px, top = 28.px, bottom = 40.px),
            horizontalArrangement = Arrangement.spacedBy(22.px), verticalArrangement = Arrangement.spacedBy(26.px)) {
            items(hits, key = { it.key }) { t -> ShelfBox(t, 112, 198, t.key == restoreKey, onOpen) }
        }
    }
}

@Composable
private fun SettingsContent(catalog: Catalog, status: String, onRefresh: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val tapes = catalog.titles.sumOf { it.tapes.size }
    Column(Modifier.fillMaxSize()) {
        SectionHeader("Settings", "VHS FEVER TV")
        Column(Modifier.padding(start = 252.px).width(640.px)) {
            for ((k, v) in listOf(
                "IN THE STORE" to "${catalog.titles.size} titles · $tapes tapes",
                "WHERE THE TAPES COME FROM" to "Files marked good in the review app",
                "BOX ART" to "TMDB posters for now · tape scans coming",
                "VERSION" to BuildConfig.VERSION_NAME,
            )) {
                Row(Modifier.fillMaxWidth().drawBehind {
                    drawLine(Color(0x4DCFD4FF), Offset(0f, size.height), Offset(size.width, size.height), 1f)
                }.padding(vertical = 12.px), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(k, style = TextStyle(fontFamily = Type.Mono, fontSize = 13.fs, letterSpacing = 1.3f.fs, color = Ink.Muted))
                    Text(v, style = TextStyle(fontFamily = Type.Barlow, fontWeight = FontWeight.Medium, fontSize = 21.fs, color = Color.White))
                }
            }
            Spacer(Modifier.height(26.px))
            GoldButton("↻ CHECK FOR NEW TAPES", Modifier.focusRequester(first), onRefresh)
            if (status.isNotBlank()) Text(status, modifier = Modifier.padding(top = 12.px),
                style = TextStyle(fontFamily = Type.Mono, fontSize = 13.fs, color = Ink.Gold))
        }
    }
}

@Composable
fun GoldButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier.height(48.px),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.px)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(containerColor = Ink.Gold.alpha(0.85f), focusedContainerColor = Ink.Gold,
            contentColor = Ink.Dark, focusedContentColor = Ink.Dark),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.px, Color.White), shape = RoundedCornerShape(8.px))),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = androidx.tv.material3.Glow(Ink.Gold.alpha(0.45f), 30.px)),
    ) {
        Box(Modifier.padding(horizontal = 30.px).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Text(text, style = TextStyle(fontFamily = Type.Condensed, fontWeight = FontWeight.Bold, fontSize = 24.fs, letterSpacing = 1.9f.fs))
        }
    }
}

@Composable
fun OutlineButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier.height(48.px),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.px)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0x99080B22), focusedContainerColor = Color.White.alpha(0.18f),
            contentColor = Color.White, focusedContentColor = Color.White),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Color.White.alpha(0.55f)), shape = RoundedCornerShape(8.px)),
            focusedBorder = Border(BorderStroke(3.px, Ink.Gold), shape = RoundedCornerShape(8.px))),
    ) {
        Box(Modifier.padding(horizontal = 24.px).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Text(text, style = TextStyle(fontFamily = Type.Condensed, fontWeight = FontWeight.Bold, fontSize = 24.fs, letterSpacing = 1.9f.fs))
        }
    }
}

