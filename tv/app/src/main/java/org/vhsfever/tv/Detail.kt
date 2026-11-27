package org.vhsfever.tv

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

fun tapeRelease(tape: Tape?): String? =
    if (tape == null) null else listOfNotNull(tape.releaseYear, tape.media).joinToString(" ").ifBlank { null }

/*
 * The tape page is the store itself: the store photo fills the screen, and the movie's trailer plays on the real
 * TV on the counter. The photo (2172×724 units) is scaled to the screen's height and lined up on the right,
 * so its TV sits right of middle. Where the TV's screen is, in mockup pixels:
 */
private const val DETAIL_PHOTO_SCALE = 720f / 724f
private const val DETAIL_PHOTO_X = 1280f - 2172f * DETAIL_PHOTO_SCALE
private const val DETAIL_TV_X = DETAIL_PHOTO_X + 1606f * DETAIL_PHOTO_SCALE
private const val DETAIL_TV_Y = 200f * DETAIL_PHOTO_SCALE
private const val DETAIL_TV_W = 387f * DETAIL_PHOTO_SCALE
private const val DETAIL_TV_H = 272f * DETAIL_PHOTO_SCALE

/** The views of the box, in the order the turn button goes through them. */
private val BOX_VIEWS = listOf(24f, 0f, 90f, 180f)

@Composable
fun DetailScreen(
    t: Title, catalog: Catalog, onWatchlist: Boolean, previewOn: Boolean,
    onBack: () -> Unit, onOpen: (Title) -> Unit, onToggleWatchlist: () -> Unit, startAngle: Float = 24f,
) {
    val ctx = LocalContext.current
    var tapeIndex by remember(t.key) { mutableIntStateOf(0) }
    var angle by remember(t.key) { mutableFloatStateOf(startAngle) }
    var chooser by remember(t.key) { mutableStateOf(false) }
    val tape = t.tapes.getOrNull(tapeIndex)
    val dvd = tape?.media?.let { it == "DVD" } ?: isDvd(t)
    val play = remember { FocusRequester() }
    LaunchedEffect(t.key) { runCatching { play.requestFocus() } }
    BackHandler { if (chooser) chooser = false else onBack() }

    // the movies most like this one (same series, TMDB pairings, director, stars, themes), topped up from its shelf
    val related = remember(t.key, catalog) { Similar.pick(t, catalog.titles, count = 9) }

    Box(Modifier.fillMaxSize().clipToBounds().background(Ink.Navy)) {
        // the store, with its counter and TV on the right
        Image(painterResource(R.drawable.store_hero), null, contentScale = ContentScale.FillBounds,
            modifier = Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).offset(DETAIL_PHOTO_X.px, 0.px)
                .size((2172f * DETAIL_PHOTO_SCALE).px, 720.px))
        // darken the aisles on the left for the box and the writing, and the floor for the shelf of spines
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
            0f to Ink.Navy.alpha(0.97f), 0.38f to Ink.Navy.alpha(0.92f), 0.52f to Ink.Navy.alpha(0.5f), 0.6f to Ink.Navy.alpha(0f))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.7f to Ink.Navy.alpha(0f), 1f to Ink.Navy.alpha(0.85f))))

        // the trailer on the counter TV; select it to watch full screen
        Box(Modifier.offset(DETAIL_TV_X.px, DETAIL_TV_Y.px)) {
            CounterTv(t, tape, previewOn && !chooser, DETAIL_TV_W, DETAIL_TV_H) { pos ->
                if (tape != null) ctx.startActivity(PlayerActivity.intent(ctx, t.name, tape, startMs = pos))
            }
        }

        // top bar
        Row(Modifier.offset(48.px, 24.px), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.px)) {
            StoreButton("← BACK TO STORE", Modifier, Ink.Gold, Ink.Gold, onBack, size = 18)
            val genre = t.section.takeIf { it != Catalog.OTHER && !t.adult } ?: t.shelf
            Text("${genre.uppercase()} SHELF", style = TextStyle(fontFamily = Type.Mono, fontSize = 13.fs, letterSpacing = 1.6f.fs, color = Ink.Soft))
        }

        // left: the box, and a button to turn it
        Column(Modifier.offset(36.px, 92.px).width(232.px), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(232.px, 318.px), contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.padding(bottom = 2.px).size(200.px, 20.px).drawBehind {
                    drawOval(Brush.radialGradient(listOf(Color(0xD9000000), Color.Transparent)))
                })
                Box(Modifier.padding(bottom = 10.px).graphicsLayer {
                    scaleX = 0.88f; scaleY = 0.88f
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                }) {
                    Box3D(t, angle, tapeText = tapeRelease(tape)?.let { "$it release" } ?: "Edition unresolved", dvd = dvd)
                }
            }
            val view = when (angle) { 0f -> "FRONT"; 90f -> "SPINE"; 180f -> "BACK"; else -> "3/4" }
            StoreButton("↻ TURN BOX · $view", Modifier.padding(top = 12.px), Color.White.alpha(0.45f), Ink.RailText, {
                angle = BOX_VIEWS[(BOX_VIEWS.indexOf(angle) + 1) % BOX_VIEWS.size]
            }, size = 15)
        }

        // middle: the writing. Everything shrinks to fit rather than being cut off.
        Column(Modifier.offset(282.px, 92.px).width(404.px).height(414.px)) {
            FitText(t.name.uppercase(), modifier = Modifier.fillMaxWidth(), maxLines = 2, minScale = 0.5f,
                style = TextStyle(fontFamily = Type.Oswald, fontWeight = FontWeight.Bold, fontSize = 60.fs, lineHeight = 62.fs,
                    letterSpacing = 1f.fs, color = Color.White, shadow = Shadow(Color.Black, Offset(0f, 3f), 10f)))
            val art = t.art
            val minutes = art?.runtime ?: tape?.minutes
            val meta = listOfNotNull(t.year, art?.rating, minutes?.let { "$it MIN" }, art?.director?.uppercase()).joinToString(" · ")
            if (meta.isNotBlank()) Text(meta, modifier = Modifier.padding(top = 8.px), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Mono, fontSize = 14.fs, color = Ink.Lavender))
            FitText(t.overview ?: "No summary yet.", modifier = Modifier.padding(top = 12.px).fillMaxWidth().weight(1f, fill = false),
                maxLines = 9, minScale = 0.62f,
                style = TextStyle(fontFamily = Type.Barlow, fontSize = 19.fs, lineHeight = 27.fs, color = Ink.Body))
            val cast = art?.cast.orEmpty().take(3)
            if (cast.isNotEmpty()) Text(buildAnnotatedString {
                append("Starring ")
                withStyle(SpanStyle(color = Color.White)) { append(cast.joinToString(", ")) }
            }, modifier = Modifier.padding(top = 10.px), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Barlow, fontSize = 16.fs, color = Ink.Muted))

            // which tape plays; with more than one on file, select it to pick another
            val tapeText = listOfNotNull(tape?.let { tapeName(t, tapeIndex) }, tape?.minutes?.let { "$it min" }).joinToString(" · ").ifBlank { "—" }
            val many = t.tapes.size > 1
            var tf by remember { mutableStateOf(false) }
            val shape = RoundedCornerShape(8.px)
            Surface(onClick = { if (many) chooser = true }, modifier = Modifier.padding(top = 14.px).fillMaxWidth().onFocusChanged { tf = it.isFocused },
                shape = ClickableSurfaceDefaults.shape(shape),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
                colors = ClickableSurfaceDefaults.colors(containerColor = Color.White.alpha(0.05f), focusedContainerColor = Ink.Gold,
                    contentColor = Color.White, focusedContentColor = Ink.Dark),
                border = ClickableSurfaceDefaults.border(border = Border(BorderStroke(1.px, Color(0x4DCFD4FF)), shape = shape)),
            ) {
                Row(Modifier.padding(horizontal = 14.px, vertical = 10.px), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.px)) {
                    Text(if (dvd) "DISC" else "TAPE", style = TextStyle(fontFamily = Type.Mono, fontSize = 12.fs, letterSpacing = 1.4f.fs,
                        color = if (tf) Ink.Dark else Ink.Muted))
                    Text(tapeText, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = Type.Barlow, fontWeight = FontWeight.Medium, fontSize = 17.fs))
                    Text(if (many) "${tapeIndex + 1} OF ${t.tapes.size} ›" else "1 OF 1",
                        style = TextStyle(fontFamily = Type.Mono, fontSize = 12.fs, color = if (tf) Ink.Dark else Ink.Gold))
                }
            }
            Row(Modifier.padding(top = 16.px), horizontalArrangement = Arrangement.spacedBy(12.px)) {
                GoldButton("▶ PLAY TAPE", Modifier.focusRequester(play)) {
                    if (tape != null) ctx.startActivity(PlayerActivity.intent(ctx, t.name, tape))
                }
                StoreButton(if (onWatchlist) "✓ ON WATCHLIST" else "+ WATCHLIST", Modifier, Color.White.alpha(0.6f), Color.White,
                    onToggleWatchlist, chosen = onWatchlist)
            }
        }

        // bottom: spines of similar movies, standing on the shelf
        if (related.isNotEmpty()) Column(Modifier.offset(48.px, 512.px)) {
            Text("ALSO ON THE SHELF", style = TextStyle(fontFamily = Type.Mono, fontSize = 12.fs, letterSpacing = 1.7f.fs, color = Ink.Soft))
            Row(Modifier.padding(top = 10.px), horizontalArrangement = Arrangement.spacedBy(12.px)) {
                for (r in related) {
                    var f by remember(r.key) { mutableStateOf(false) }
                    Box(Modifier.lift(if (f) (-6).px else 0.px)) {
                        Surface(onClick = { onOpen(r) }, modifier = Modifier.size(miniSpineWidth(r).px, MINI_SPINE_HEIGHT.px).onFocusChanged { f = it.isFocused },
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(2.px)),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                            colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
                            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.px, Ink.Gold.alpha(0.85f)), shape = RoundedCornerShape(2.px))),
                            glow = ClickableSurfaceDefaults.glow(focusedGlow = androidx.tv.material3.Glow(Ink.Gold.alpha(0.22f), 8.px)),
                        ) { MiniSpine(r) }
                    }
                }
            }
        }

        if (chooser) TapeChooser(t, tapeIndex, onPick = { tapeIndex = it; chooser = false })
        Grain()
    }
}

@Composable
private fun TapeChooser(t: Title, current: Int, onPick: (Int) -> Unit) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0xCC04051A)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(760.px).fillMaxHeight(0.86f)
            .background(Brush.verticalGradient(listOf(Color(0xFF0C1340), Ink.Navy)), RoundedCornerShape(12.px))
            .border(3.px, Ink.Gold, RoundedCornerShape(12.px)).padding(28.px)) {
            Text("PICK A TAPE", style = TextStyle(fontFamily = Type.Mono, fontSize = 13.fs, letterSpacing = 1.6f.fs, color = Ink.Gold))
            Text(t.name.uppercase(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Oswald, fontWeight = FontWeight.Bold, fontSize = 34.fs, color = Color.White))
            Spacer(Modifier.height(14.px))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.px), contentPadding = PaddingValues(4.px)) {
                itemsIndexed(t.tapes, key = { _, tp -> tp.fileId }) { i, tp ->
                    val sel = i == current
                    Surface(onClick = { onPick(i) }, modifier = (if (sel) Modifier.focusRequester(fr) else Modifier).fillMaxWidth(),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.px)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                        colors = ClickableSurfaceDefaults.colors(containerColor = if (sel) Ink.Gold.alpha(0.16f) else Color.White.alpha(0.06f),
                            focusedContainerColor = Ink.Gold, contentColor = Color.White, focusedContentColor = Ink.Dark),
                    ) {
                        Row(Modifier.padding(horizontal = 18.px, vertical = 10.px), verticalAlignment = Alignment.CenterVertically) {
                            Text(tapeName(t, i), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = TextStyle(fontFamily = Type.Barlow, fontWeight = FontWeight.Bold, fontSize = 21.fs))
                            Text(listOfNotNull(tp.minutes?.let { "$it min" }, if (sel) "SELECTED" else null).joinToString(" · "),
                                style = TextStyle(fontFamily = Type.Mono, fontSize = 13.fs))
                        }
                    }
                }
            }
        }
    }
}


/**
 * What a tape is called on screen: its description ("1992 VHS · Version 2"), plus "Copy 2" when other tapes
 * of the movie have the same description. File names are never shown (they're often just numbers).
 */
fun tapeName(t: Title, i: Int): String {
    val label = t.tapes.getOrNull(i)?.label ?: return "Tape"
    val same = t.tapes.withIndex().filter { it.value.label == label }
    if (same.size < 2) return label
    return "$label · Copy ${same.indexOfFirst { it.index == i } + 1}"
}

/** "PARAMOUNT HOME VIDEO" → "Paramount Home Video"; labels already in mixed case are left alone. */
fun titleCase(s: String): String = if (s != s.uppercase()) s else
    s.lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
