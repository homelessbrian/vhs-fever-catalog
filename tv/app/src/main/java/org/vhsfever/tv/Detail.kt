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
import androidx.compose.ui.text.TextStyle
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
    val c = colorsOf(t)
    val play = remember { FocusRequester() }
    LaunchedEffect(t.key) { runCatching { play.requestFocus() } }
    BackHandler { if (chooser) chooser = false else onBack() }

    val related = remember(t.key, catalog) {
        val same = catalog.titles.filter { it.key != t.key && it.section == t.section }
        (same + catalog.titles.filter { it.key != t.key && it !in same }).take(6)
    }

    Box(Modifier.fillMaxSize().background(Ink.Navy)) {
        Image(painterResource(R.drawable.store_blur), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().drawBehind {
            drawRect(Brush.radialGradient(listOf(Color(0x33FFF0CD), Color.Transparent), Offset(size.width * .17f, size.height * .3f), size.width * .4f))
            drawRect(Brush.radialGradient(listOf(c.c3.alpha(0.22f), Color.Transparent), Offset(size.width * .82f, size.height * .45f), size.width * .35f))
            drawRect(Brush.radialGradient(0.4f to Color.Transparent, 1f to Color(0xD9040514), center = Offset(size.width / 2, size.height * .45f), radius = size.width * .62f))
        })

        // top bar
        StoreButton("← BACK TO STORE", Modifier.offset(48.px, 22.px), Ink.Gold, Ink.Gold, onBack, size = 20)
        Box(Modifier.align(Alignment.TopEnd).padding(top = 24.px, end = 40.px)
            .background(Brush.linearGradient(listOf(Ink.BadgeTop, Ink.BadgeBottom)), RoundedCornerShape(8.px))
            .border(2.px, Ink.Gold, RoundedCornerShape(8.px)).padding(horizontal = 16.px, vertical = 6.px)) {
            Text("VHS FEVER", style = TextStyle(fontFamily = Type.Oswald, fontWeight = FontWeight.Bold, fontSize = 24.fs,
                letterSpacing = 1f.fs, color = Ink.Gold, shadow = Shadow(Ink.BadgeShadow, Offset(2f, 2f), 0f)))
        }

        // left: the box on its shelf, the view buttons, and a few other spines
        Column(Modifier.offset(40.px, 84.px).width(352.px)) {
            Box(Modifier.size(352.px, 410.px), contentAlignment = Alignment.Center) {
                Box(Modifier.align(Alignment.BottomCenter).size(380.px, 124.px).drawBehind {
                    val p = Path().apply {
                        moveTo(size.width * .1f, 0f); lineTo(size.width * .9f, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close()
                    }
                    drawPath(p, Brush.verticalGradient(listOf(Color(0xFF2A46C8), Color(0xFF101D66))))
                    val gh = 9.px.toPx()
                    val inset = size.width * .1f * (1f - gh / size.height)
                    val top = Path().apply {
                        moveTo(size.width * .1f, 0f); lineTo(size.width * .9f, 0f)
                        lineTo(size.width - inset, gh); lineTo(inset, gh); close()
                    }
                    drawPath(top, Ink.Gold)
                    drawPath(p, Brush.radialGradient(listOf(Color(0x4DFFEBBE), Color.Transparent), Offset(size.width / 2, 0f), size.width * .45f))
                })
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 56.px).size(220.px, 22.px).drawBehind {
                    drawOval(Brush.radialGradient(listOf(Color(0xD9000000), Color.Transparent)))
                })
                Box(Modifier.padding(bottom = 36.px)) {
                    Box3D(t, angle, tapeText = tapeRelease(tape)?.let { "$it release" } ?: "Edition unresolved", dvd = dvd)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.px), horizontalArrangement = Arrangement.spacedBy(8.px, Alignment.CenterHorizontally)) {
                for ((label, a) in listOf("3/4" to 24f, "FRONT" to 0f, "SPINE" to 90f, "BACK" to 180f)) {
                    StoreButton(label, Modifier, Color.White.alpha(0.45f), Ink.RailText, { angle = a }, size = 17, chosen = angle == a)
                }
            }
            Text("ALSO IN THE STORE", modifier = Modifier.padding(top = 16.px),
                style = TextStyle(fontFamily = Type.Mono, fontSize = 12.fs, letterSpacing = 1.7f.fs, color = Ink.Soft))
            Row(Modifier.padding(top = 8.px), horizontalArrangement = Arrangement.spacedBy(10.px)) {
                for (r in related) {
                    var f by remember(r.key) { mutableStateOf(false) }
                    Box(Modifier.lift(if (f) (-6).px else 0.px)) {
                        Surface(onClick = { onOpen(r) }, modifier = Modifier.size(miniSpineWidth(r).px, 112.px).onFocusChanged { f = it.isFocused },
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(2.px)),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
                            colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
                            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.px, Ink.Gold.alpha(0.85f)), shape = RoundedCornerShape(2.px))),
                            glow = ClickableSurfaceDefaults.glow(focusedGlow = androidx.tv.material3.Glow(Ink.Gold.alpha(0.22f), 8.px)),
                        ) { MiniSpine(r) }
                    }
                }
            }
        }

        // middle: the info card. Everything shrinks to fit rather than being cut off.
        Column(Modifier.offset(414.px, 84.px).width(406.px).height(612.px)) {
            val genre = t.section.takeIf { it != Catalog.OTHER && !t.adult } ?: t.shelf
            Text(genre.uppercase(), modifier = Modifier.border(1.dp, Ink.Gold, RoundedCornerShape(3.px)).padding(horizontal = 12.px, vertical = 3.px),
                style = TextStyle(fontFamily = Type.Mono, fontSize = 13.fs, letterSpacing = 1.6f.fs, color = Ink.Gold))
            FitText(t.name.uppercase(), modifier = Modifier.padding(top = 10.px).fillMaxWidth(), maxLines = 3, minScale = 0.5f,
                style = TextStyle(fontFamily = Type.Oswald, fontWeight = FontWeight.Bold, fontSize = 44.fs, lineHeight = 46.fs,
                    letterSpacing = 0.9f.fs, color = Color.White, shadow = Shadow(Color.Black, Offset(0f, 3f), 8f)))
            val meta = listOfNotNull(t.year?.let { "$it film" }, tapeRelease(tape)?.let { "$it " + if (dvd) "disc" else "tape" } ?: "edition unresolved",
                "${t.tapes.size} ${if (t.tapes.size == 1) "tape" else "tapes"} on file").joinToString(" · ")
            Text(meta, modifier = Modifier.padding(top = 8.px),
                style = TextStyle(fontFamily = Type.Mono, fontSize = 14.fs, lineHeight = 20.fs, color = Ink.Lavender))
            FitText(t.overview ?: "No summary yet.", modifier = Modifier.padding(top = 12.px).fillMaxWidth().weight(1f, fill = false),
                maxLines = 30, minScale = 0.62f,
                style = TextStyle(fontFamily = Type.Barlow, fontSize = 21.fs, lineHeight = 28.fs, color = Ink.Body))
            Column(Modifier.padding(top = 14.px).drawBehind {
                drawLine(Color(0x4DCFD4FF), Offset.Zero, Offset(size.width, 0f), 1f)
            }) {
                val scan = t.box
                val art = when {
                    scan != null -> listOfNotNull("VHS box scan", scan.label?.let(::titleCase), scan.region, scan.tapeYear,
                        if (t.posterIsSuggestion) "match not confirmed" else null, scan.source).joinToString(" · ")
                    t.poster == null -> "Placeholder · no tape scan yet"
                    t.posterIsSuggestion -> "TMDB poster · match not confirmed"
                    else -> "TMDB poster · no tape scan yet"
                }
                InfoRow("FILM", t.year ?: "—")
                val tapeText = listOfNotNull(tape?.let { tapeName(t, tapeIndex) }, tape?.minutes?.let { "$it min" }).joinToString(" · ").ifBlank { "—" }
                if (t.tapes.size > 1) {
                    // pick which of the tapes on file to play
                    var f by remember { mutableStateOf(false) }
                    Surface(onClick = { chooser = true }, modifier = Modifier.fillMaxWidth().onFocusChanged { f = it.isFocused },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.px)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Ink.Gold,
                            contentColor = Color.White, focusedContentColor = Ink.Dark),
                    ) { Box(Modifier.padding(horizontal = if (f) 8.px else 0.px)) { InfoRow("THIS TAPE", tapeText, action = "CHANGE ›", onGold = f) } }
                } else InfoRow("THIS TAPE", tapeText)
                InfoRow("TAPES ON FILE", t.tapes.size.toString())
                InfoRow("BOX ART", art)
            }
            Row(Modifier.padding(top = 16.px), horizontalArrangement = Arrangement.spacedBy(12.px)) {
                GoldButton("▶ PLAY TAPE", Modifier.focusRequester(play)) {
                    if (tape != null) ctx.startActivity(PlayerActivity.intent(ctx, t.name, tape))
                }
                StoreButton(if (onWatchlist) "✓ ON WATCHLIST" else "+ WATCHLIST", Modifier, Color.White.alpha(0.6f), Color.White,
                    onToggleWatchlist, chosen = onWatchlist)
            }
        }

        // right: the big CRT on the counter; select it to watch full screen from the same spot
        Box(Modifier.offset(842.px, 112.px)) {
            CrtTv(t, tape, previewOn && !chooser, width = 400) { pos ->
                if (tape != null) ctx.startActivity(PlayerActivity.intent(ctx, t.name, tape, startMs = pos))
            }
        }

        if (chooser) TapeChooser(t, tapeIndex, onPick = { tapeIndex = it; chooser = false })
        Grain()
    }
}

@Composable
private fun InfoRow(k: String, v: String, action: String? = null, onGold: Boolean = false) {
    Row(Modifier.fillMaxWidth().drawBehind {
        drawLine(Color(0x4DCFD4FF), Offset(0f, size.height), Offset(size.width, size.height), 1f)
    }.padding(vertical = 9.px), horizontalArrangement = Arrangement.spacedBy(12.px), verticalAlignment = Alignment.CenterVertically) {
        Text(k, style = TextStyle(fontFamily = Type.Mono, fontSize = 13.fs, letterSpacing = 1.3f.fs, color = if (onGold) Ink.Dark else Ink.Muted))
        Text(v, modifier = Modifier.weight(1f), maxLines = 2,
            style = TextStyle(fontFamily = Type.Barlow, fontWeight = FontWeight.Medium, fontSize = 20.fs, lineHeight = 24.fs,
                color = if (onGold) Ink.Dark else Color.White, textAlign = TextAlign.End))
        if (action != null) Text(action, style = TextStyle(fontFamily = Type.Condensed, fontWeight = FontWeight.Bold, fontSize = 16.fs,
            letterSpacing = 1.2f.fs, color = if (onGold) Ink.Dark else Ink.Gold))
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
