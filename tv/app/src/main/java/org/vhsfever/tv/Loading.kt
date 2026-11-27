package org.vhsfever.tv

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.tv.material3.Text
import kotlin.math.cos
import kotlin.math.sin

/*
 * The app's own loading animations, in place of the plain spinning circle:
 *  - LoadingScreen: full screen while a movie starts (the rewinding tape or the tape going into the VCR, picked at random)
 *  - BufferingTape: a small tape over the movie when it stops to load more
 *  - ShelvesLoading: the neon sign and tapes dropping onto a shelf, while the shelves load the first time
 * All drawn on the 1280×720 mockup canvas (see Ui) and centred on the screen.
 */

/** The two movie-start screens; one is picked at random each time a movie opens. */
enum class LoadingLook { Rewind, Deck }

private val OsdGreen = Color(0xFF7CFF8A)
private val Cyan = Color(0xFF4FD8FF)
private val Hub = Color(0xFF0B0C10)
private val TapeBrown = Color(0xFF5A361C)
private val TapeDark = Color(0xFF3E2412)
private val Shell = Brush.verticalGradient(listOf(Color(0xFF22232B), Color(0xFF121319)))

/** Pixels per mockup pixel on this screen. */
@Composable private fun unitPx(): Float = with(LocalDensity.current) { 1.px.toPx() }

/** The 1280×720 mockup canvas, centred on the screen. */
@Composable
private fun Mockup(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(1280.px, 720.px), content = content)
    }
}

/** 0→1 over [ms], over and over. */
@Composable
private fun InfiniteTransition.loop(ms: Int, label: String) =
    animateFloat(0f, 1f, infiniteRepeatable(tween(ms, easing = LinearEasing)), label = label)

/** Navy store background with the faint light in the middle. */
private fun Modifier.storeGlow(y: Float = .4f) = drawBehind {
    drawRect(Ink.Navy)
    drawRect(Brush.radialGradient(listOf(Ink.NavyTop, Ink.Navy), Offset(size.width / 2, size.height * y), size.maxDimension * .55f))
}

/** Scanlines and the slow bright band rolling down, like the TV on the tape page. */
@Composable
private fun Scanlines(anim: InfiniteTransition) {
    val p = unitPx()
    val roll by anim.loop(3600, "roll")
    Canvas(Modifier.fillMaxSize()) {
        var y = 0f
        while (y < size.height) { drawRect(Color(0x47000000), Offset(0f, y), Size(size.width, 1.5f * p)); y += 4.5f * p }
        val top = (-140f + roll * 900f) * p
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0x0FFFFFFF), Color.Transparent), top, top + 140f * p),
            Offset(0f, top), Size(size.width, 140f * p))
    }
}

private fun vt(size: Float, color: Color = OsdGreen) = TextStyle(fontFamily = Type.Vt, fontSize = size.fs, letterSpacing = 2f.fs,
    color = color, shadow = Shadow(Color.Black, Offset(3f, 3f), 0f))

/** A word followed by a blinking bit, e.g. "REW" + "◀◀". */
@Composable
private fun Blinking(text: String, blink: String, style: TextStyle, on: () -> Boolean, modifier: Modifier = Modifier) {
    Row(modifier) {
        Text(text, style = style)
        Text(blink, style = style, modifier = Modifier.graphicsLayer { alpha = if (on()) 1f else 0f })
    }
}

/** The two lines under the picture: a gold headline (with blinking dots) and a quieter line. */
@Composable
private fun Caption(top: Int, headline: String, line: String, on: () -> Boolean) {
    Column(Modifier.fillMaxWidth().offset(y = top.px), horizontalAlignment = Alignment.CenterHorizontally) {
        Blinking(headline, "…", TextStyle(fontFamily = Type.Oswald, fontWeight = FontWeight.Bold, fontSize = 34.fs,
            letterSpacing = 6f.fs, color = Ink.Gold), on)
        Text(line, modifier = Modifier.padding(top = 10.px),
            style = TextStyle(fontFamily = Type.Barlow, fontWeight = FontWeight.Medium, fontSize = 18.fs, color = Ink.Muted))
    }
}

/** One tape reel: cream wheel, dark hub, three spokes. Turned by [turn] (degrees). */
@Composable
private fun Reel(size: Float, turn: () -> Float, hub: Color = Hub, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size.px).graphicsLayer { rotationZ = turn() }) {
        val r = this.size.minDimension / 2; val c = center
        drawCircle(Ink.Cream, r * 34f / 38f)
        drawCircle(hub, r * 11f / 38f)
        for (a in listOf(-90.0, 30.0, 150.0)) {
            val rad = Math.toRadians(a)
            drawLine(hub, Offset(c.x + (cos(rad) * r * 34 / 38).toFloat(), c.y + (sin(rad) * r * 34 / 38).toFloat()),
                Offset(c.x + (cos(rad) * r * 16 / 38).toFloat(), c.y + (sin(rad) * r * 16 / 38).toFloat()),
                strokeWidth = r * 7f / 38f, cap = StrokeCap.Butt)
        }
    }
}

/**
 * The VHS FEVER cassette with its reels turning and the tape winding from one side to the other.
 * Drawn 600 wide in the mockup and scaled to [width].
 */
@Composable
private fun Cassette(width: Int, anim: InfiniteTransition, modifier: Modifier = Modifier) {
    val k = width / 600f
    val p = unitPx()
    fun d(n: Float) = (n * k).px
    val spin by anim.loop(1100, "spin")
    val wind by anim.animateFloat(.52f, 1f, infiniteRepeatable(tween(4000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "wind")
    Box(modifier.size(d(600f), d(360f)).shadow(d(30f), RoundedCornerShape(d(18f))).background(Shell, RoundedCornerShape(d(18f)))) {
        // label
        Row(Modifier.offset(d(36f), d(26f)).size(d(528f), d(96f)).background(Ink.CoverCream, RoundedCornerShape(d(6f)))
            .padding(horizontal = d(26f)), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (k < .5f) Arrangement.Center else Arrangement.SpaceBetween) {
            Text("VHS FEVER", style = TextStyle(fontFamily = Type.Bowlby, fontSize = (if (k < .5f) 13f * k / .267f else 44f * k).fs,
                color = Ink.Pink, shadow = Shadow(Ink.Gold, Offset(3f * k * p, 3f * k * p), 0f)))
            if (k >= .5f) Column(horizontalAlignment = Alignment.End) {
                Text("T-120", style = TextStyle(fontFamily = Type.Oswald, fontWeight = FontWeight.Bold, fontSize = (18f * k).fs,
                    letterSpacing = (3f * k).fs, color = Ink.Dark))
                Text("BE KIND · REWIND", style = TextStyle(fontFamily = Type.Oswald, fontWeight = FontWeight.Medium,
                    fontSize = (13f * k).fs, letterSpacing = (2f * k).fs, color = Color(0xFF4A4E5A)))
            }
        }
        // window onto the tape
        val window = RoundedCornerShape(d(65f))
        Box(Modifier.offset(d(110f), d(150f)).size(d(380f), d(130f)).clip(window).background(Hub)
            .border((3f * k).coerceAtLeast(1f).px, Color(0xFF3A3E4A), window)) {
            val pack = Brush.radialGradient(listOf(TapeDark, TapeBrown, TapeDark))
            Box(Modifier.offset(d(8f), d(-20f)).size(d(170f)).graphicsLayer { scaleX = wind; scaleY = wind }.background(pack, CircleShape))
            Box(Modifier.offset(d(202f), d(-20f)).size(d(170f)).graphicsLayer { val s = 1.52f - wind; scaleX = s; scaleY = s }.background(pack, CircleShape))
            Reel(76f * k, { -360f * spin }, modifier = Modifier.offset(d(55f), d(27f)))
            Reel(76f * k, { -360f * spin }, modifier = Modifier.offset(d(249f), d(27f)))
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0x1AFFFFFF), Color.Transparent))))
        }
        Box(Modifier.offset(d(150f), d(316f)).size(d(300f), d(44f)).background(Color(0xFF0E0F16), RoundedCornerShape(topStart = d(10f), topEnd = d(10f))))
        Box(Modifier.offset(d(30f), d(322f)).size(d(14f)).background(Hub, CircleShape))
        Box(Modifier.offset(d(556f), d(322f)).size(d(14f)).background(Hub, CircleShape))
    }
}

/** Full screen while a movie starts. */
@Composable
fun LoadingScreen(look: LoadingLook) {
    val anim = rememberInfiniteTransition(label = "loading")
    val blink by anim.loop(1000, "blink")
    val on = { blink < .5f }
    Box(Modifier.fillMaxSize().storeGlow()) {
        Mockup {
            when (look) {
                LoadingLook.Rewind -> Rewind(anim, on)
                LoadingLook.Deck -> Deck(anim, on)
            }
            Text("SP", modifier = Modifier.align(Alignment.TopEnd).padding(end = 44.px, top = 38.px), style = vt(30f, Color.White))
        }
        Scanlines(anim)
    }
}

@Composable
private fun BoxScope.Rewind(anim: InfiniteTransition, on: () -> Boolean) {
    val p = unitPx()
    val wob by anim.loop(180, "wobble")
    Blinking("REW ", "◀◀", vt(44f), on, Modifier.padding(start = 44.px, top = 34.px))
    Cassette(600, anim, Modifier.offset(340.px, 150.px).graphicsLayer { translationX = if (wob < .5f) 0f else 2f * p })
    Caption(548, "REWINDING YOUR TAPE", "Hang tight, it’ll be ready in a second", on)
}

/** Where the tape is (mockup px, 0 = resting above the slot) at [t] 0..1 of the loop. */
private fun feedAt(t: Float): Float {
    fun ease(x: Float) = x * x * (3 - 2 * x)
    return when {
        t < .35f -> -150f + 140f * ease(t / .35f)
        t < .55f -> -10f + 180f * ease((t - .35f) / .2f)
        else -> 170f
    }
}

/** How open the slot's flap is (1 = shut) at [t]. */
private fun flapAt(t: Float): Float = when {
    t < .30f -> 1f
    t < .38f -> 1f - .85f * (t - .30f) / .08f
    t < .60f -> .15f
    t < .70f -> .15f + .85f * (t - .60f) / .1f
    else -> 1f
}

@Composable
private fun BoxScope.Deck(anim: InfiniteTransition, on: () -> Boolean) {
    val p = unitPx()
    val t by anim.loop(2800, "feed")
    Blinking("LOADING", "_", vt(44f), on, Modifier.padding(start = 44.px, top = 34.px))

    // the tape, going down into the slot
    Box(Modifier.offset(390.px, 96.px).size(500.px, 236.px).clipToBounds()) {
        Box(Modifier.align(Alignment.BottomStart).size(500.px, 150.px).graphicsLayer { translationY = feedAt(t) * p }
            .background(Shell, RoundedCornerShape(topStart = 12.px, topEnd = 12.px))) {
            Box(Modifier.offset(24.px, 18.px).size(452.px, 58.px).background(Ink.CoverCream, RoundedCornerShape(5.px)), contentAlignment = Alignment.Center) {
                Text("VHS FEVER", style = TextStyle(fontFamily = Type.Bowlby, fontSize = 32.fs, color = Ink.Pink,
                    shadow = Shadow(Ink.Gold, Offset(2f * p, 2f * p), 0f)))
            }
            Canvas(Modifier.offset(24.px, 92.px).size(452.px, 10.px)) {
                var x = 0f
                while (x < size.width) { drawRect(Color(0xFF3A3E4A), Offset(x, 0f), Size(14f * p, size.height)); x += 22f * p }
            }
        }
    }

    // the VCR
    val body = RoundedCornerShape(14.px)
    Box(Modifier.offset(190.px, 330.px).size(900.px, 230.px).shadow(30.px, body)
        .background(Brush.verticalGradient(listOf(Color(0xFF3A3E4A), Color(0xFF20222B), Color(0xFF181A24))), body)) {
        Box(Modifier.offset(200.px, 0.px).size(500.px, 30.px).background(Color(0xFF09090D), RoundedCornerShape(bottomStart = 6.px, bottomEnd = 6.px)))
        Box(Modifier.offset(204.px, 0.px).size(492.px, 26.px)
            .graphicsLayer { scaleY = flapAt(t); transformOrigin = TransformOrigin(.5f, 0f) }
            .background(Brush.verticalGradient(listOf(Color(0xFF4A4E5A), Color(0xFF2A2D36))), RoundedCornerShape(bottomStart = 4.px, bottomEnd = 4.px)))

        Column(Modifier.offset(40.px, 52.px)) {
            Text("VHS FEVER", style = TextStyle(fontFamily = Type.Bowlby, fontSize = 26.fs, color = Ink.Cream))
            Text("HQ VIDEO CASSETTE RECORDER", modifier = Modifier.padding(top = 4.px), style = TextStyle(fontFamily = Type.Oswald,
                fontWeight = FontWeight.Medium, fontSize = 12.fs, letterSpacing = 3f.fs, color = Color(0xFF9AA0AE)))
        }

        val glow = { s: Float -> TextStyle(fontFamily = Type.Vt, fontSize = s.fs, color = Cyan, shadow = Shadow(Cyan, Offset.Zero, 10f * p)) }
        Row(Modifier.offset(330.px, 60.px).size(300.px, 96.px).background(Color(0xFF05060A), RoundedCornerShape(8.px))
            .border(2.px, Color(0xFF2A2D36), RoundedCornerShape(8.px)).padding(horizontal = 22.px),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("VCR · SP", style = glow(20f))
                Text("▶ LOAD", style = glow(20f))
            }
            // every VCR nobody ever set the clock on
            Text("12:00", style = glow(64f), modifier = Modifier.graphicsLayer { alpha = if (on()) 1f else 0f })
        }

        Row(Modifier.align(Alignment.TopEnd).padding(end = 40.px, top = 64.px), verticalAlignment = Alignment.CenterVertically) {
            Text("REC", style = TextStyle(fontFamily = Type.Oswald, fontWeight = FontWeight.Medium, fontSize = 12.fs,
                letterSpacing = 2f.fs, color = Color(0xFF9AA0AE)))
            Canvas(Modifier.padding(start = 8.px).size(12.px)) {
                val lit = t >= .6f
                if (lit) drawCircle(Color(0x66FF3B3B), size.minDimension)
                drawCircle(if (lit) Color(0xFFFF3B3B) else Color(0xFF3A1010))
            }
        }

        Row(Modifier.offset(330.px, 174.px)) {
            listOf("◀◀" to Ink.Cream, "▶" to OsdGreen, "▶▶" to Ink.Cream, "■" to Ink.Cream).forEachIndexed { i, (label, c) ->
                Box(Modifier.padding(start = if (i == 0) 0.px else 10.px).size(66.px, 30.px)
                    .background(Brush.verticalGradient(listOf(Color(0xFF4A4E5A), Color(0xFF2A2D36))), RoundedCornerShape(4.px)),
                    contentAlignment = Alignment.Center) {
                    Text(label, style = TextStyle(fontFamily = Type.Oswald, fontSize = 13.fs, color = c))
                }
            }
        }
    }
    Caption(604, "LOADING TAPE", "Popping it in the deck", on)
}

/** Small, over the movie, while it stops to load more. The VCR's "TRACKING…" stays in the corner as before. */
@Composable
fun BufferingTape() {
    val anim = rememberInfiniteTransition(label = "buffering")
    val blink by anim.loop(1000, "blink")
    Mockup {
        Cassette(160, anim, Modifier.offset(560.px, 304.px))
        Blinking("BUFFERING", "…", TextStyle(fontFamily = Type.Vt, fontSize = 24.fs, letterSpacing = 4f.fs, color = Color.White,
            shadow = Shadow(Color.Black, Offset(2f, 2f), 0f)), { blink < .5f },
            Modifier.align(Alignment.TopCenter).padding(top = 414.px))
    }
}

private val SPINES = listOf(
    Triple(Color(0xFFB2362C), Color(0xFF2A0807), Color(0xFFFFC65A)) to 190,
    Triple(Color(0xFF2F66D4), Color(0xFF0A1A4A), Color(0xFFFF9A52)) to 176,
    Triple(Color(0xFF3F9A3A), Color(0xFF0B2A0E), Color(0xFFF5E06A)) to 196,
    Triple(Color(0xFF7A3FB0), Color(0xFF1D0F33), Color(0xFFFFD45E)) to 184,
    Triple(Color(0xFFE07418), Color(0xFF3D1A05), Color(0xFF5CD0F0)) to 170,
    Triple(Color(0xFF4A4A58), Color(0xFF0B0B10), Color(0xFFE6E0CC)) to 192,
    Triple(Color(0xFFC8303F), Color(0xFF33080F), Color(0xFF8FE2FF)) to 180,
    Triple(Color(0xFF2A8A7E), Color(0xFF05241F), Color(0xFFB4F060)) to 194,
    Triple(Color(0xFF2D6070), Color(0xFF051218), Color(0xFFFF7448)) to 174,
    Triple(Color(0xFF2F9444), Color(0xFF052A0E), Color(0xFFFF72B4)) to 188,
)

/** How far above the shelf a spine is (mockup px) and how visible, at [t] 0..1 of its own loop. */
private fun dropAt(t: Float): Pair<Float, Float> = when {
    t < .10f -> { val x = t / .10f; -300f * (1 - x) * (1 - x) + 6f * x to x }
    t < .13f -> 6f * (1 - (t - .10f) / .03f) to 1f
    t < .82f -> 0f to 1f
    t < .92f -> 0f to 1f - (t - .82f) / .10f
    else -> 0f to 0f
}

/** Neon sign flicker: mostly on, with a couple of quick dips. */
private fun neonAt(t: Float): Float = if (t in .18f..0.22f || t in .62f..0.64f) .25f else 1f
private fun openAt(t: Float): Float = when { t < .30f -> .15f; t < .32f -> 1f; t < .34f -> .3f; else -> 1f }

/** While the shelves load the first time the app opens. */
@Composable
fun ShelvesLoading() {
    val anim = rememberInfiniteTransition(label = "shelves")
    val blink by anim.loop(1000, "blink")
    val t by anim.loop(4000, "drop")
    val sign by anim.loop(3200, "neon")
    val p = unitPx()
    Box(Modifier.fillMaxSize().storeGlow(.3f)) {
        Mockup {
            Text("VHS FEVER", modifier = Modifier.fillMaxWidth().offset(y = 92.px).graphicsLayer { alpha = neonAt(sign) },
                style = TextStyle(fontFamily = Type.Bowlby, fontSize = 104.fs, color = Color(0xFFFFD3EC), textAlign = TextAlign.Center,
                    shadow = Shadow(Ink.Pink, Offset.Zero, 36f * p)))
            Box(Modifier.align(Alignment.TopCenter).offset(y = 226.px).graphicsLayer { alpha = openAt(t) }
                .shadow(16.px, RoundedCornerShape(12.px), ambientColor = Ink.Gold, spotColor = Ink.Gold)
                .background(Color(0xFF1A1508), RoundedCornerShape(12.px))
                .border(3.px, Ink.Gold, RoundedCornerShape(12.px)).padding(horizontal = 26.px, vertical = 6.px)) {
                Text("OPEN", style = TextStyle(fontFamily = Type.Oswald, fontWeight = FontWeight.Bold, fontSize = 30.fs,
                    letterSpacing = 10f.fs, color = Color(0xFFFFF1B8), shadow = Shadow(Ink.Gold, Offset.Zero, 14f * p)))
            }

            // tapes dropping onto the shelf, one after another
            Row(Modifier.align(Alignment.TopCenter).offset(y = 356.px).size(514.px, 200.px), verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.px)) {
                SPINES.forEachIndexed { i, (c, h) ->
                    val (c1, c2, c3) = c
                    Box(Modifier.size(46.px, h.px).graphicsLayer {
                        val (y, a) = dropAt(((t - i * .055f) % 1f + 1f) % 1f)
                        translationY = y * p; alpha = a
                    }.background(Brush.horizontalGradient(listOf(c2, c1, c2)), RoundedCornerShape(topStart = 3.px, topEnd = 3.px))) {
                        Box(Modifier.offset(6.px, 14.px).size(34.px, 8.px).background(c3))
                        Box(Modifier.offset(8.px, 40.px).size(30.px, (h - 80).px).background(Ink.CoverCream.copy(alpha = .92f), RoundedCornerShape(2.px)))
                        Box(Modifier.offset(6.px, (h - 22).px).size(34.px, 8.px).background(c3))
                    }
                }
            }
            Box(Modifier.offset(340.px, 556.px).size(600.px, 18.px).shadow(10.px, RoundedCornerShape(2.px))
                .background(Brush.verticalGradient(listOf(Color(0xFFA06A3B), Color(0xFF7B4E2A), Color(0xFF3E2412))), RoundedCornerShape(2.px)))

            Caption(608, "STOCKING THE SHELVES", "Unlocking the store, one sec", { blink < .5f })
        }
        Scanlines(anim)
    }
}
