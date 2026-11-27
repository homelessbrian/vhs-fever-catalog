package org.vhsfever.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/** How the picture fills the screen. */
enum class Aspect(val label: String, val short: String, val hint: String) {
    Auto("AUTO", "AUTO", "as recorded"),
    Four3("4:3 ORIGINAL", "4:3", "side bars"),
    Wide("16:9 STRETCH", "16:9", "fills the width"),
    Fill("FILL SCREEN", "FILL", "crops top & bottom"),
}

val ZOOMS = listOf(100, 110, 125, 150)

enum class VcrMenu { Aspect, Zoom, Subtitles, Audio }

/** One audio or subtitle choice on the tape. */
data class TrackChoice(val id: Int, val label: String, val detail: String, val selected: Boolean)

/** Everything the VCR deck shows. The player keeps it up to date; the deck only reads it. */
class VcrState(val title: String) {
    var visible by mutableStateOf(true)
    var playing by mutableStateOf(true)
    var stopped by mutableStateOf(false)
    var loading by mutableStateOf(true)
    var ended by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var aspect by mutableStateOf(Aspect.Auto)
    var zoom by mutableIntStateOf(100)
    var menu by mutableStateOf<VcrMenu?>(null)
    var audio by mutableStateOf<List<TrackChoice>>(emptyList())
    var subs by mutableStateOf<List<TrackChoice>>(emptyList())
    /** Shown big in the top-left, like a VCR's on-screen display (e.g. "◀◀ REW"); null shows PLAY/PAUSE/STOP. */
    var flash by mutableStateOf<String?>(null)
    var flashCount by mutableIntStateOf(0)
    /** Which key to land on when the deck appears ("play", "pause", "bar" …), and a counter to ask again. */
    var focus by mutableStateOf("pause")
    var focusAsk by mutableIntStateOf(0)
    /** Bumped on every key press, so the deck stays up while the remote is in use. */
    var touched by mutableIntStateOf(0)

    fun show(target: String? = null) {
        if (target != null) focus = target
        visible = true; focusAsk++; touched++
    }
    fun flash(text: String) { flash = text; flashCount++ }
}

class VcrActions(
    val play: () -> Unit = {}, val pause: () -> Unit = {}, val stop: () -> Unit = {},
    val rew: () -> Unit = {}, val ff: () -> Unit = {}, val eject: () -> Unit = {},
    val seekBy: (Long) -> Unit = {},
    val aspect: (Aspect) -> Unit = {}, val zoom: (Int) -> Unit = {},
    val audio: (Int) -> Unit = {}, val subs: (Int?) -> Unit = {},
)

/** The tape counter: always H:MM:SS, like a VCR. */
private fun counter(ms: Long) = clock(ms.coerceAtLeast(0))

private val Vfd = Color(0xFF5CF2E0)
private val Amber = Color(0xFFFFB64A)
private val OsdBlue = Color(0xFF1B2FC9)
private fun glow(c: Color) = Shadow(c.alpha(0.8f), Offset.Zero, 10f)

/** Everything drawn over the movie: the on-screen display, the VCR menus and the VCR deck itself. */
@Composable
fun VcrOverlay(s: VcrState, act: VcrActions) {
    // the VCR's own on-screen display, top left
    val osd = s.flash ?: when {
        s.ended -> "END ■"
        s.stopped -> "STOP ■"
        s.loading -> "TRACKING…"
        s.playing -> "PLAY ▶"
        else -> "PAUSE ❚❚"
    }
    var flashOn by remember { mutableStateOf(false) }
    LaunchedEffect(s.flashCount) {
        if (s.flash != null) { flashOn = true; kotlinx.coroutines.delay(1400); flashOn = false; s.flash = null }
    }
    val showOsd = s.visible || flashOn || !s.playing || s.loading
    Box(Modifier.fillMaxSize()) {
        if (showOsd) {
            Text(osd, modifier = Modifier.padding(start = 44.px, top = 34.px),
                style = TextStyle(fontFamily = Type.Vt, fontSize = 44.fs, letterSpacing = 2f.fs, color = Color(0xFF7CFF8A),
                    shadow = Shadow(Color.Black, Offset(3f, 3f), 0f)))
            Text("SP", modifier = Modifier.align(Alignment.TopEnd).padding(end = 44.px, top = 38.px),
                style = TextStyle(fontFamily = Type.Vt, fontSize = 30.fs, letterSpacing = 2f.fs, color = Color.White,
                    shadow = Shadow(Color.Black, Offset(3f, 3f), 0f)))
        }
        AnimatedVisibility(s.visible, modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it / 3 }, exit = fadeOut() + slideOutVertically { it / 3 }) {
            Deck(s, act)
        }
        val m = s.menu
        if (m != null && s.visible) OsdMenu(s, m, act, Modifier.align(Alignment.BottomEnd).padding(end = 64.px, bottom = 234.px))
    }
}

@Composable
private fun Deck(s: VcrState, act: VcrActions) {
    val req = remember { mutableMapOf<String, FocusRequester>() }
    fun fr(id: String) = req.getOrPut(id) { FocusRequester() }
    LaunchedEffect(s.focusAsk, s.menu) {
        if (s.menu == null) repeat(5) {
            if (runCatching { fr(s.focus).requestFocus() }.isSuccess) return@LaunchedEffect
            kotlinx.coroutines.delay(60)
        }
    }
    Box(Modifier.padding(start = 28.px, end = 28.px, bottom = 20.px).fillMaxWidth().height(184.px)
        .background(Brush.verticalGradient(0f to Color(0xFF34363E), 0.22f to Color(0xFF1C1D22), 0.7f to Color(0xFF141519), 1f to Color(0xFF0C0D10)),
            RoundedCornerShape(10.px))
        .border(1.dp, Color.Black, RoundedCornerShape(10.px))) {
        Column(Modifier.fillMaxSize()) {
            // brushed silver strip along the top
            Row(Modifier.fillMaxWidth().height(30.px)
                .background(Brush.verticalGradient(listOf(Color(0xFFC9CCD3), Color(0xFF8E929C))), RoundedCornerShape(topStart = 10.px, topEnd = 10.px))
                .drawBehind {
                    var x = 0f; val step = 3.px.toPx()
                    while (x < size.width) { drawRect(Color(0x0DFFFFFF), Offset(x, 0f), Size(1f, size.height)); x += step }
                    drawRect(Color.Black, Offset(0f, size.height - 1f), Size(size.width, 1f))
                }
                .padding(horizontal = 22.px), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.px)) {
                Text("VHS FEVER", style = TextStyle(fontFamily = Type.Oswald, fontWeight = FontWeight.Bold, fontSize = 17.fs,
                    letterSpacing = 3f.fs, color = Color(0xFF26272C), shadow = Shadow(Color.White.alpha(0.6f), Offset(0f, 1f), 0f)))
                Text("VIDEO CASSETTE RECORDER · 4 HEAD HI-FI STEREO", style = TextStyle(fontFamily = Type.Mono, fontSize = 10.fs,
                    letterSpacing = 2f.fs, color = Color(0xFF2E3036)))
                Spacer(Modifier.weight(1f))
                Lamp("POWER", Color(0xFF4CFF6A), true)
                Lamp("REC", Color(0xFFFF3A3A), false)
            }
            Row(Modifier.padding(start = 22.px, end = 22.px, top = 14.px), horizontalArrangement = Arrangement.spacedBy(18.px)) {
                TapeDoor()
                Display(s)
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.px)) {
                        TransportKey("REW", REW, fr("rew"), onHold = { act.rew() }, onClick = act.rew)
                        TransportKey("PLAY", PLAY, fr("play"), onClick = act.play)
                        TransportKey("PAUSE", PAUSE, fr("pause"), onClick = act.pause)
                        TransportKey("STOP", STOP, fr("stop"), onClick = act.stop)
                        TransportKey("F.FWD", FF, fr("ff"), onHold = { act.ff() }, onClick = act.ff)
                        TransportKey("EJECT", EJECT, fr("eject"), onClick = act.eject)
                    }
                    Row(Modifier.padding(top = 10.px), horizontalArrangement = Arrangement.spacedBy(8.px)) {
                        val subsOn = s.subs.firstOrNull { it.selected }
                        RubberKey("ASPECT", s.aspect.short, fr("aspect")) { s.menu = VcrMenu.Aspect; s.focus = "aspect" }
                        RubberKey("ZOOM", "${s.zoom}%", fr("zoom")) { s.menu = VcrMenu.Zoom; s.focus = "zoom" }
                        RubberKey("SUBTITLES", if (s.subs.isEmpty()) "NONE" else if (subsOn != null) "ON" else "OFF", fr("subs")) {
                            s.menu = VcrMenu.Subtitles; s.focus = "subs"
                        }
                        RubberKey("AUDIO", s.audio.firstOrNull { it.selected }?.detail ?: s.audio.firstOrNull()?.detail ?: "—", fr("audio")) {
                            s.menu = VcrMenu.Audio; s.focus = "audio"
                        }
                    }
                }
            }
        }
        TapeBar(s, act, fr("bar"), Modifier.align(Alignment.BottomStart).padding(start = 22.px, end = 22.px, bottom = 10.px))
    }
}

@Composable
private fun Lamp(label: String, color: Color, on: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.px)) {
        Text(label, style = TextStyle(fontFamily = Type.Mono, fontSize = 9.fs, letterSpacing = 1f.fs, color = Color(0xFF2E3036)))
        Box(Modifier.size(8.px).drawBehind {
            if (on) drawCircle(color.alpha(0.45f), radius = size.minDimension)
            drawCircle(Color(0xFF1A1A1E))
            drawCircle(if (on) color else color.alpha(0.25f))
        })
    }
}

@Composable
private fun TapeDoor() {
    Column(Modifier.size(230.px, 92.px)
        .background(Brush.verticalGradient(listOf(Color(0xFF0B0B0E), Color(0xFF18191E))), RoundedCornerShape(4.px))
        .border(1.dp, Color.White.alpha(0.06f), RoundedCornerShape(4.px)),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(196.px, 30.px).background(Brush.verticalGradient(listOf(Color(0xFF26272D), Color(0xFF0F1013))), RoundedCornerShape(2.px)),
            contentAlignment = Alignment.Center) {
            Box(Modifier.size(150.px, 4.px).background(Color(0xFF050506), RoundedCornerShape(2.px)))
        }
        Text("VHS", modifier = Modifier.padding(top = 6.px), style = TextStyle(fontFamily = Type.Oswald, fontWeight = FontWeight.Bold,
            fontSize = 15.fs, letterSpacing = 4f.fs, color = Color(0xFF5E616B)))
    }
}

/** The glowing display: what the deck is doing, the tape counter, aspect and zoom, and the tape's title. */
@Composable
private fun Display(s: VcrState) {
    val lit = TextStyle(fontFamily = Type.Vt, color = Vfd, shadow = glow(Vfd))
    val dim = TextStyle(fontFamily = Type.Vt, color = Vfd.alpha(0.12f))
    Column(Modifier.size(340.px, 92.px)
        .background(Brush.radialGradient(listOf(Color(0xFF0B1E1C), Color(0xFF030807))), RoundedCornerShape(4.px))
        .border(2.dp, Color.Black, RoundedCornerShape(4.px))
        .padding(horizontal = 14.px, vertical = 6.px), verticalArrangement = Arrangement.SpaceBetween) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.px)) {
            val playing = s.playing && !s.stopped && !s.ended
            Text("▶ PLAY", style = (if (playing) lit else dim).copy(fontSize = 17.fs))
            Text("❚❚ PAUSE", style = (if (!playing && !s.stopped && !s.ended) lit else dim).copy(fontSize = 17.fs))
            Text("■ STOP", style = (if (s.stopped || s.ended) lit else dim).copy(fontSize = 17.fs))
            Spacer(Modifier.weight(1f))
            Text(s.aspect.short, style = lit.copy(fontSize = 17.fs))
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.px)) {
            Box {
                Text("8:88:88", style = dim.copy(fontSize = 44.fs, lineHeight = 40.fs, letterSpacing = 3f.fs))
                Text(counter(s.positionMs), style = lit.copy(fontSize = 44.fs, lineHeight = 40.fs, letterSpacing = 3f.fs))
            }
            Column(Modifier.padding(bottom = 4.px)) {
                val a = TextStyle(fontFamily = Type.Vt, fontSize = 17.fs, lineHeight = 17.fs, color = Amber, shadow = glow(Amber))
                Text("ZOOM", style = a)
                Text("${s.zoom}%", style = a)
            }
        }
        Text(s.title.uppercase(), maxLines = 1, overflow = TextOverflow.Clip, softWrap = false,
            style = lit.copy(fontSize = 15.fs, lineHeight = 15.fs, letterSpacing = 2f.fs))
    }
}

/* Key pictures (drawn on a 22×16 grid). */
private const val REW = "M11 2 L1 8 L11 14 Z M21 2 L11 8 L21 14 Z"
private const val PLAY = "M5 1 L19 8 L5 15 Z"
private const val PAUSE = "M4 1 H9 V15 H4 Z M13 1 H18 V15 H13 Z"
private const val STOP = "M4 2 H18 V14 H4 Z"
private const val FF = "M1 2 L11 8 L1 14 Z M11 2 L21 8 L11 14 Z"
private const val EJECT = "M11 1 L20 10 H2 Z M2 12 H20 V15 H2 Z"

@Composable
private fun keyFocus(shape: RoundedCornerShape) = ClickableSurfaceDefaults.border(
    border = Border(BorderStroke(0.dp, Color.Transparent), shape = shape),
    focusedBorder = Border(BorderStroke(Hi.ring.px, Ink.Gold), shape = shape))

/** A chunky silver deck key with its name printed on the deck above it. Holding it (REW, F.FWD) keeps going. */
@Composable
private fun TransportKey(label: String, icon: String, fr: FocusRequester, onHold: (() -> Unit)? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(5.px)
    val path = remember(icon) { PathParser().parsePathString(icon).toPath() }
    var on by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.px)) {
        Text(label, style = TextStyle(fontFamily = Type.Mono, fontSize = 9.fs, letterSpacing = 1f.fs, color = Color(0xFFB9BCC6)))
        Surface(onClick = onClick,
            modifier = Modifier.size(86.px, 44.px).focusRequester(fr).onFocusChanged { on = it.isFocused }.semantics { contentDescription = label }
                .onPreviewKeyEvent { e ->
                    // holding OK on REW / F.FWD scans through the tape
                    val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                    if (onHold != null && ok && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0) { onHold(); true } else false
                },
            shape = ClickableSurfaceDefaults.shape(shape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f, pressedScale = 0.96f),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent,
                pressedContainerColor = Color.Transparent),
            border = keyFocus(shape),
            glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(Ink.Gold.alpha(0.55f), 18.px)),
        ) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0xFFF1F2F5), 0.45f to Color(0xFFC3C6CE), 1f to Color(0xFF9A9EA8)), shape)
                .border(if (on) Hi.ring.px else 1.dp, if (on) Ink.Gold else Color.Black.alpha(0.5f), shape),
                contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(22.px, 16.px)) {
                    scale(size.width / 22f, size.height / 16f, pivot = Offset.Zero) { drawPath(path, Color(0xFF1A1B20)) }
                }
            }
        }
    }
}

/** A black rubber key for the picture and sound settings, showing what it's set to in amber. */
@Composable
private fun RubberKey(label: String, value: String, fr: FocusRequester, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.px)
    var on by remember { mutableStateOf(false) }
    Surface(onClick = onClick, modifier = Modifier.height(32.px).focusRequester(fr).onFocusChanged { on = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent,
            pressedContainerColor = Color.Transparent),
        border = keyFocus(shape),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(Ink.Gold.alpha(0.5f), 16.px)),
    ) {
        Row(Modifier.fillMaxHeight().background(Brush.verticalGradient(listOf(Color(0xFF3A3C44), Color(0xFF1E1F24))), shape)
            .border(if (on) Hi.ring.px else 1.dp, if (on) Ink.Gold else Color.White.alpha(0.08f), shape)
            .padding(horizontal = 14.px), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = TextStyle(fontFamily = Type.Condensed, fontWeight = FontWeight.Bold, fontSize = 14.fs,
                letterSpacing = 1.5f.fs, color = Color(0xFFE6E8EE)))
            Text(value, modifier = Modifier.padding(start = 6.px), maxLines = 1,
                style = TextStyle(fontFamily = Type.Vt, fontSize = 18.fs, color = Amber))
        }
    }
}

/** Where you are on the tape. Highlight it and press Left/Right to jump back or ahead 10 seconds. */
@Composable
private fun TapeBar(s: VcrState, act: VcrActions, fr: FocusRequester, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val t = TextStyle(fontFamily = Type.Vt, fontSize = 18.fs, color = if (focused) Ink.Gold else Color(0xFFB9BCC6))
    Row(modifier.fillMaxWidth().height(24.px), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.px)) {
        Text(counter(s.positionMs), style = t)
        Box(Modifier.weight(1f).fillMaxHeight().focusRequester(fr).onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { act.seekBy(-10_000); true }
                    Key.DirectionRight -> { act.seekBy(10_000); true }
                    Key.DirectionCenter, Key.Enter -> { if (s.playing) act.pause() else act.play(); true }
                    else -> false
                }
            }
            .semantics { contentDescription = "Tape position" }
            .focusable()
            .drawBehind {
                val h = 8.px.toPx(); val y = (size.height - h) / 2
                val frac = if (s.durationMs > 0) (s.positionMs.toFloat() / s.durationMs).coerceIn(0f, 1f) else 0f
                if (focused) drawRoundRect(Ink.Gold.alpha(0.35f), Offset(-4.px.toPx(), y - 4.px.toPx()),
                    Size(size.width + 8.px.toPx(), h + 8.px.toPx()), CornerRadius(8.px.toPx()))
                drawRoundRect(Color(0xFF050506), Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
                drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFFB8860B), Ink.Gold)), Offset(0f, y), Size(size.width * frac, h), CornerRadius(h / 2))
                val r = (if (focused) 12 else 9).px.toPx()
                val cx = size.width * frac
                drawCircle(Color.Black.alpha(0.6f), r, Offset(cx, size.height / 2 + 2f))
                drawCircle(Brush.radialGradient(listOf(Color(0xFFF2F3F6), Color(0xFF8E929C)), center = Offset(cx - r / 3, size.height / 2 - r / 3), radius = r * 1.4f),
                    r, Offset(cx, size.height / 2))
                if (focused) drawCircle(Ink.Gold, r, Offset(cx, size.height / 2), style = Stroke(Hi.ring.px.toPx()))
            })
        Text(if (s.durationMs > 0) counter(s.durationMs) else "-:--:--", style = t)
    }
}

/** The blue on-screen menu an old VCR showed: pick an aspect, a zoom, subtitles or the sound track. */
@Composable
private fun OsdMenu(s: VcrState, m: VcrMenu, act: VcrActions, modifier: Modifier) {
    data class Row_(val label: String, val hint: String, val chosen: Boolean, val enabled: Boolean, val pick: () -> Unit)
    val (title, rows) = when (m) {
        VcrMenu.Aspect -> "ASPECT RATIO" to Aspect.entries.map { a -> Row_(a.label, a.hint, a == s.aspect, true) { act.aspect(a) } }
        VcrMenu.Zoom -> "ZOOM" to ZOOMS.map { z -> Row_("$z%", if (z == 100) "normal" else "closer", z == s.zoom, true) { act.zoom(z) } }
        VcrMenu.Subtitles -> "SUBTITLES" to (listOf(Row_("OFF", "", s.subs.none { it.selected }, true) { act.subs(null) }) +
            if (s.subs.isEmpty()) listOf(Row_("NONE ON THIS TAPE", "", false, false) {})
            else s.subs.map { c -> Row_(c.label, c.detail, c.selected, true) { act.subs(c.id) } })
        VcrMenu.Audio -> "AUDIO" to (if (s.audio.isEmpty()) listOf(Row_("NO SOUND TRACK FOUND", "", false, false) {})
            else s.audio.map { c -> Row_(c.label, c.detail, c.selected, true) { act.audio(c.id) } })
    }
    val first = remember(m) { FocusRequester() }
    val firstIndex = rows.indexOfFirst { it.chosen && it.enabled }.takeIf { it >= 0 } ?: rows.indexOfFirst { it.enabled }
    LaunchedEffect(m) {
        repeat(5) { if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect; kotlinx.coroutines.delay(60) }
    }
    Column(modifier.width(360.px).background(OsdBlue).border(3.px, Color.White).padding(start = 18.px, end = 18.px, top = 12.px, bottom = 14.px)) {
        Text(title, modifier = Modifier.fillMaxWidth().drawBehind {
            drawRect(Color.White.alpha(0.6f), Offset(0f, size.height - 2.px.toPx()), Size(size.width, 2.px.toPx()))
        }.padding(bottom = 6.px), style = TextStyle(fontFamily = Type.Vt, fontSize = 30.fs, letterSpacing = 3f.fs, color = Color.White))
        Spacer(Modifier.height(8.px))
        rows.forEachIndexed { i, r ->
            var on by remember { mutableStateOf(false) }
            val shape = RoundedCornerShape(0.px)
            Surface(onClick = { if (r.enabled) r.pick() }, enabled = r.enabled,
                modifier = Modifier.fillMaxWidth().height(34.px).onFocusChanged { on = it.isFocused }
                    .let { if (i == firstIndex) it.focusRequester(first) else it },
                shape = ClickableSurfaceDefaults.shape(shape),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.White,
                    contentColor = Color.White, focusedContentColor = OsdBlue,
                    disabledContainerColor = Color.Transparent, disabledContentColor = Color.White.alpha(0.6f)),
            ) {
                Row(Modifier.fillMaxSize().background(if (on) Color.White else Color.Transparent).padding(horizontal = 6.px),
                    verticalAlignment = Alignment.CenterVertically) {
                    val c = if (on) OsdBlue else if (r.enabled) Color.White else Color.White.alpha(0.6f)
                    Text(if (r.chosen) "▶" else "", modifier = Modifier.width(24.px), style = TextStyle(fontFamily = Type.Vt, fontSize = 26.fs, color = c))
                    Text(r.label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = Type.Vt, fontSize = 26.fs, letterSpacing = 2f.fs, color = c))
                    Text(r.hint, maxLines = 1, style = TextStyle(fontFamily = Type.Vt, fontSize = 19.fs, color = c.alpha(0.75f)))
                }
            }
        }
        Text("▲▼ SELECT · OK SET · BACK EXIT", modifier = Modifier.padding(top = 8.px),
            style = TextStyle(fontFamily = Type.Vt, fontSize = 18.fs, letterSpacing = 1f.fs, color = Color.White.alpha(0.75f)))
    }
}
