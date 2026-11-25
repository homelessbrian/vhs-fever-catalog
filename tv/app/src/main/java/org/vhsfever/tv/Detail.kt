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
    val c = colorsFor(t.key)
    val play = remember { FocusRequester() }
    LaunchedEffect(t.key) { runCatching { play.requestFocus() } }
    BackHandler { if (chooser) chooser = false else onBack() }

    val related = remember(t.key, catalog) {
        val same = catalog.titles.filter { it.key != t.key && it.genres.any { g -> g in t.genres } }
        (same + catalog.titles.filter { it.key != t.key && it !in same }).take(5)
    }

    Box(Modifier.fillMaxSize().background(Ink.Navy)) {
        Image(painterResource(R.drawable.store_blur), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().drawBehind {
            drawRect(Brush.radialGradient(listOf(Color(0x33FFF0CD), Color.Transparent), Offset(size.width * .2f, size.height * .28f), size.width * .45f))
            drawRect(Brush.radialGradient(listOf(c.c3.alpha(0.2f), Color.Transparent), Offset(size.width * .86f, size.height * .66f), size.width * .35f))
            drawRect(Brush.radialGradient(0.4f to Color.Transparent, 1f to Color(0xD9040514), center = Offset(size.width / 2, size.height * .45f), radius = size.width * .62f))
        })

        // top bar
        OutlineGoldButton("← BACK TO STORE", Modifier.offset(48.px, 26.px), onBack)
        Box(Modifier.align(Alignment.TopEnd).padding(top = 26.px, end = 48.px)
            .background(Brush.linearGradient(listOf(Ink.BadgeTop, Ink.BadgeBottom)), RoundedCornerShape(8.px))
            .border(2.px, Ink.Gold, RoundedCornerShape(8.px)).padding(horizontal = 16.px, vertical = 6.px)) {
            Text("VHS FEVER", style = TextStyle(fontFamily = Type.Oswald, fontWeight = FontWeight.Bold, fontSize = 24.fs,
                letterSpacing = 1f.fs, color = Ink.Gold, shadow = Shadow(Ink.BadgeShadow, Offset(2f, 2f), 0f)))
        }

        // left: the box on its shelf
        Column(Modifier.offset(48.px, 92.px).width(400.px)) {
            Box(Modifier.size(400.px, 420.px), contentAlignment = Alignment.Center) {
                Box(Modifier.align(Alignment.BottomCenter).offset(y = 0.px).size(432.px, 130.px).drawBehind {
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
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 60.px).size(200.px, 22.px).drawBehind {
                    drawOval(Brush.radialGradient(listOf(Color(0xD9000000), Color.Transparent)))
                })
                Box(Modifier.padding(bottom = 40.px)) {
                    Box3D(t, angle, tapeText = tapeRelease(tape)?.let { "$it release" } ?: "Edition unresolved")
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.px), horizontalArrangement = Arrangement.spacedBy(8.px, Alignment.CenterHorizontally)) {
                for ((label, a) in listOf("3/4" to 24f, "FRONT" to 0f, "SPINE" to 90f, "BACK" to 180f)) {
                    ViewButton(label, angle == a) { angle = a }
                }
            }
            Text("ALSO IN THE STORE", modifier = Modifier.padding(top = 14.px),
                style = TextStyle(fontFamily = Type.Mono, fontSize = 12.fs, letterSpacing = 1.7f.fs, color = Ink.Soft))
            Row(Modifier.padding(top = 6.px), horizontalArrangement = Arrangement.spacedBy(8.px)) {
                for (r in related) {
                    Surface(onClick = { onOpen(r) }, modifier = Modifier.size(44.px, 112.px),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(2.px)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
                        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.px, Ink.Gold), shape = RoundedCornerShape(2.px))),
                    ) { MiniSpine(r) }
                }
            }
        }

        // middle: the info card
        Column(Modifier.offset(488.px, 96.px).width(440.px)) {
            val genre = t.genres.take(2).joinToString(" · ").ifBlank { t.shelf }
            Text(genre.uppercase(), modifier = Modifier.border(1.dp, Ink.Gold, RoundedCornerShape(3.px)).padding(horizontal = 12.px, vertical = 3.px),
                style = TextStyle(fontFamily = Type.Mono, fontSize = 13.fs, letterSpacing = 1.6f.fs, color = Ink.Gold))
            Text(t.name.uppercase(), modifier = Modifier.padding(top = 12.px), maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Oswald, fontWeight = FontWeight.Bold, fontSize = 46.fs, lineHeight = 46.fs,
                    letterSpacing = 0.9f.fs, color = Color.White, shadow = Shadow(Color.Black, Offset(0f, 3f), 8f)))
            val meta = listOfNotNull(t.year?.let { "$it film" }, tapeRelease(tape)?.let { "$it tape" } ?: "tape edition unresolved",
                "${t.tapes.size} ${if (t.tapes.size == 1) "tape" else "tapes"} on file").joinToString(" · ")
            Text(meta, modifier = Modifier.padding(top = 10.px),
                style = TextStyle(fontFamily = Type.Mono, fontSize = 15.fs, color = Ink.Lavender))
            Text(t.overview ?: "No summary yet.", modifier = Modifier.padding(top = 16.px), maxLines = 3, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Barlow, fontSize = 22.fs, lineHeight = 29.fs, color = Ink.Body))
            Column(Modifier.padding(top = 20.px).drawBehind {
                drawLine(Color(0x4DCFD4FF), Offset.Zero, Offset(size.width, 0f), 1f)
            }) {
                val art = when {
                    t.poster == null -> "Placeholder · no tape scan yet"
                    t.posterIsSuggestion -> "TMDB poster (unconfirmed match)"
                    else -> "TMDB poster · no tape scan yet"
                }
                for ((k, v) in listOf(
                    "FILM" to (t.year ?: "—"),
                    "THIS TAPE" to listOfNotNull(tape?.label, tape?.minutes?.let { "$it min" }).joinToString(" · ").ifBlank { "—" },
                    "TAPES ON FILE" to t.tapes.size.toString(),
                    "BOX ART" to art,
                )) {
                    Row(Modifier.fillMaxWidth().drawBehind {
                        drawLine(Color(0x4DCFD4FF), Offset(0f, size.height), Offset(size.width, size.height), 1f)
                    }.padding(vertical = 9.px), horizontalArrangement = Arrangement.spacedBy(12.px), verticalAlignment = Alignment.CenterVertically) {
                        Text(k, style = TextStyle(fontFamily = Type.Mono, fontSize = 13.fs, letterSpacing = 1.3f.fs, color = Ink.Muted))
                        Text(v, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = TextStyle(fontFamily = Type.Barlow, fontWeight = FontWeight.Medium, fontSize = 21.fs, color = Color.White, textAlign = TextAlign.End))
                    }
                }
            }
            Row(Modifier.padding(top = 22.px), horizontalArrangement = Arrangement.spacedBy(12.px)) {
                GoldButton("▶ PLAY TAPE", Modifier.focusRequester(play)) {
                    if (tape != null) ctx.startActivity(PlayerActivity.intent(ctx, t.name, tape))
                }
                OutlineButton(if (onWatchlist) "✓ SAVED" else "+ WATCHLIST", onClick = onToggleWatchlist)
                if (t.tapes.size > 1) OutlineButton("TAPES (${t.tapes.size})") { chooser = true }
            }
        }

        // right: the CRT on the counter
        Box(Modifier.offset(964.px, 300.px)) { CrtTv(t, tape, previewOn && !chooser) }

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
                            Column(Modifier.weight(1f)) {
                                Text(tp.label, style = TextStyle(fontFamily = Type.Barlow, fontWeight = FontWeight.Bold, fontSize = 21.fs))
                                Text(tp.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(fontFamily = Type.Mono, fontSize = 12.fs))
                            }
                            Text(listOfNotNull(tp.minutes?.let { "$it min" }, if (sel) "SELECTED" else null).joinToString(" · "),
                                style = TextStyle(fontFamily = Type.Mono, fontSize = 13.fs))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewButton(label: String, on: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.height(44.px),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.px)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        colors = ClickableSurfaceDefaults.colors(containerColor = if (on) Ink.Gold else Color(0xA6080B22),
            focusedContainerColor = if (on) Ink.Gold else Color.White.alpha(0.18f),
            contentColor = if (on) Ink.Dark else Ink.RailText, focusedContentColor = if (on) Ink.Dark else Color.White),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (on) Ink.Gold else Color.White.alpha(0.45f)), shape = RoundedCornerShape(8.px)),
            focusedBorder = Border(BorderStroke(3.px, if (on) Color.White else Ink.Gold), shape = RoundedCornerShape(8.px))),
    ) {
        Box(Modifier.padding(horizontal = 16.px).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Text(label, style = TextStyle(fontFamily = Type.Condensed, fontWeight = FontWeight.Bold, fontSize = 18.fs, letterSpacing = 1.4f.fs))
        }
    }
}

@Composable
private fun OutlineGoldButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier.height(44.px),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.px)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xB3080B22), focusedContainerColor = Ink.Gold,
            contentColor = Ink.Gold, focusedContentColor = Ink.Dark),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(2.px, Ink.Gold), shape = RoundedCornerShape(8.px)),
            focusedBorder = Border(BorderStroke(3.px, Color.White), shape = RoundedCornerShape(8.px))),
    ) {
        Box(Modifier.padding(horizontal = 20.px).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Text(text, style = TextStyle(fontFamily = Type.Condensed, fontWeight = FontWeight.Bold, fontSize = 20.fs, letterSpacing = 1.6f.fs))
        }
    }
}
