package org.vhsfever.tv

import android.content.Context
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Text
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** How the shelves look. Picked in Settings and kept on the TV. */
enum class ShelfLook(val label: String) { Wood("Wood"), Metal("Black metal"), Rental("Rental store blue") }

object Shelf {
    var look by mutableStateOf(ShelfLook.Wood)

    fun load(ctx: Context) {
        val name = ctx.getSharedPreferences("store", Context.MODE_PRIVATE).getString("shelf_look", null)
        look = ShelfLook.entries.firstOrNull { it.name == name } ?: ShelfLook.Wood
    }

    fun next(ctx: Context) {
        look = ShelfLook.entries[(look.ordinal + 1) % ShelfLook.entries.size]
        ctx.getSharedPreferences("store", Context.MODE_PRIVATE).edit().putString("shelf_look", look.name).apply()
    }
}

private class ShelfColors(val wall: Brush, val plank: Brush, val lip: Brush, val lipInk: Color, val labelBg: Color, val labelInk: Color)

private val WOOD = ShelfColors(
    wall = Brush.verticalGradient(listOf(Color(0xFF120E26), Color(0xFF1B1534), Color(0xFF221A3C))),
    plank = Brush.verticalGradient(listOf(Color(0xFFA06A3B), Color(0xFF7A4C27))),
    lip = Brush.verticalGradient(listOf(Color(0xFF7B4E2A), Color(0xFF5A361C), Color(0xFF3E2412))),
    lipInk = Color(0x8CFFE6BE), labelBg = Ink.Cream, labelInk = Ink.Dark,
)
private val METAL = ShelfColors(
    wall = Brush.verticalGradient(listOf(Color(0xFF0E0F16), Color(0xFF181A24))),
    plank = Brush.verticalGradient(listOf(Color(0xFF9AA0AE), Color(0xFF4A4E5A))),
    lip = Brush.verticalGradient(listOf(Color(0xFF3A3E4A), Color(0xFF20222B), Color(0xFF121319))),
    lipInk = Color(0x80C8CDE1), labelBg = Ink.Gold, labelInk = Ink.Dark,
)
private val RENTAL = ShelfColors(
    wall = Brush.verticalGradient(listOf(Color(0xFF0B1338), Color(0xFF142060))),
    plank = Brush.verticalGradient(listOf(Color(0xFFE9E4D6), Color(0xFFBDB7A6))),
    lip = Brush.verticalGradient(listOf(Color(0xFF2A4FE0), Color(0xFF1B3AB0), Color(0xFF13287E))),
    lipInk = Color(0x99E6EBFF), labelBg = Ink.Pink, labelInk = Color.White,
)

private fun colors() = when (Shelf.look) { ShelfLook.Wood -> WOOD; ShelfLook.Metal -> METAL; ShelfLook.Rental -> RENTAL }

/** The back of the shelf: the wall behind the boxes, with the shadow of the shelf above. */
fun Modifier.shelfWall(): Modifier = drawBehind {
    val c = colors()
    drawRect(c.wall)
    drawRect(Brush.verticalGradient(listOf(Color(0x8C000000), Color.Transparent), endY = 30.px.toPx()), size = Size(size.width, 30.px.toPx()))
    // a faint grain on wood, slats on metal
    if (Shelf.look == ShelfLook.Metal) {
        var x = 0f; val step = 14.px.toPx()
        while (x < size.width) { drawRect(Color(0x06FFFFFF), Offset(x, 0f), Size(1f, size.height)); x += step }
    }
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0x59000000)), startY = size.height - 18.px.toPx()))
}

/** The shelf the boxes stand on: the top of the plank, its front edge (with the shelf's label) and its shadow. */
@Composable
fun ShelfLip(label: String?, modifier: Modifier = Modifier) {
    val c = colors()
    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(7.px).background(c.plank))
        Row(Modifier.fillMaxWidth().height(24.px).background(c.lip).drawBehind {
            drawRect(Color(0x2EFFFFFF), size = Size(size.width, 1f))
            if (Shelf.look == ShelfLook.Wood) {
                // wood grain
                var x = 0f; var i = 0
                val step = 3.px.toPx()
                while (x < size.width) {
                    if (i % 3 == 0) drawRect(Color(0x1A000000), Offset(x, 0f), Size(2.px.toPx(), size.height))
                    if (i % 5 == 2) drawRect(Color(0x0AFFFFFF), Offset(x, 0f), Size(2.px.toPx(), size.height))
                    x += step; i++
                }
            }
        }, verticalAlignment = Alignment.CenterVertically) {
            if (label != null) {
                Box(Modifier.padding(start = 20.px).height(16.px).background(c.labelBg, RoundedCornerShape(2.px)).padding(horizontal = 8.px),
                    contentAlignment = Alignment.Center) {
                    Text(label.uppercase(), maxLines = 1, style = TextStyle(fontFamily = Type.Condensed, fontWeight = FontWeight.Bold,
                        fontSize = 12.fs, letterSpacing = 1.5f.fs, color = c.labelInk))
                }
                Spacer(Modifier.weight(1f))
                Text("BE KIND · PLEASE REWIND", modifier = Modifier.padding(end = 80.px),
                    style = TextStyle(fontFamily = Type.Mono, fontSize = 9.fs, letterSpacing = 2f.fs, color = c.lipInk))
            }
        }
        Box(Modifier.fillMaxWidth().height(14.px).background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent))))
    }
}

/** Keeps boxes from sliding under the menu, but lets a highlighted box rise above its row. */
fun Modifier.clipSides(): Modifier = drawWithContent {
    clipRect(left = 0f, top = -10_000f, right = size.width, bottom = size.height + 10_000f) { this@drawWithContent.drawContent() }
}

private val pop = CubicBezierEasing(.3f, .7f, .2f, 1.15f)

/** A small, steady lean for each box (from its key), so the shelf looks stocked by hand. */
private fun leanFor(key: String): Float {
    val x = sin(key.hashCode() * 91.7 + 13.3) * 43758.5453
    return ((x - Math.floor(x)).toFloat() - 0.5f) * 2.6f
}

/*
 * The box in 3D: corners worked out in real perspective and each face's drawing mapped onto its corners, so the
 * front and the side always meet. x runs from the box's middle, y down from its top, z back from its front.
 */
private class BoxShape(val w: Float, val h: Float, val d: Float) {
    fun corners(angle: Float, grow: Float, lift: Float, face: Int): FloatArray {
        val r = Math.toRadians(angle.toDouble()); val c = cos(r).toFloat(); val s = sin(r).toFloat()
        val far = 6f * w; val eye = h * 0.42f; val zc = d / 2
        val p = when (face) {
            0 -> floatArrayOf(-w / 2, 0f, 0f, w / 2, 0f, 0f, w / 2, h, 0f, -w / 2, h, 0f)        // front
            1 -> floatArrayOf(-w / 2, 0f, d, -w / 2, 0f, 0f, -w / 2, h, 0f, -w / 2, h, d)        // left side
            else -> floatArrayOf(w / 2, 0f, 0f, w / 2, 0f, d, w / 2, h, d, w / 2, h, 0f)         // right side
        }
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val x = p[i * 3]; val y = p[i * 3 + 1]; val z = p[i * 3 + 2] - zc
            val rx = x * c - z * s
            val rz = x * s + z * c + zc
            val k = far / (far + rz)
            val sx = w / 2 + rx * k
            val sy = eye + (y - eye) * k
            out[i * 2] = w / 2 + (sx - w / 2) * grow
            out[i * 2 + 1] = h + (sy - h) * grow - lift
        }
        return out
    }
}

// Drawing happens on the main thread only, so one matrix and corner list are reused for every box and frame.
private val ontoMatrix = android.graphics.Matrix()
private val ontoSrc = FloatArray(8)

private fun ContentDrawScope.drawOnto(q: FloatArray) {
    val src = ontoSrc
    src[0] = 0f; src[1] = 0f; src[2] = size.width; src[3] = 0f; src[4] = size.width; src[5] = size.height; src[6] = 0f; src[7] = size.height
    val m = ontoMatrix
    if (!m.setPolyToPoly(src, 0, q, 0, 4)) return
    val c = drawContext.canvas.nativeCanvas
    c.save(); c.concat(m); drawContent(); c.restore()
}

private fun quadPath(q: FloatArray) = Path().apply {
    moveTo(q[0], q[1]); lineTo(q[2], q[3]); lineTo(q[4], q[5]); lineTo(q[6], q[7]); close()
}

/**
 * A box on the shelf. DVDs are wider than VHS, like the real cases. Highlighting lifts it off the shelf,
 * makes it a little bigger and turns it a little to the left or right (picked at random each time), so its
 * side shows. Only the drawing moves, so Up/Down on the remote still go straight between rows.
 */
@Composable
fun ShelfBox(t: Title, vhsWidth: Int, h: Int, restore: Boolean, onOpen: (Title) -> Unit, onFocused: (Title) -> Unit = {},
             leftEdge: Boolean = false, entry: Boolean = false) {
    val fr = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var tilt by remember { mutableFloatStateOf(14f) }
    if (restore) LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    val dvd = isDvd(t)
    val w = boxWidth(t, vhsWidth)
    val depth = if (dvd) w * 0.11f else vhsWidth * 0.24f
    val anim = tween<Float>(300, easing = pop)
    val angle = animateFloatAsState(if (focused) tilt else 0f, anim, label = "tilt")
    val grow = animateFloatAsState(if (focused) 1.14f else 1f, anim, label = "grow")
    val lift = animateFloatAsState(if (focused) 12f else 0f, anim, label = "lift")
    val glow = animateFloatAsState(if (focused) 1f else 0f, tween(220), label = "glow")
    val lean by animateFloatAsState(if (focused) 0f else leanFor(t.key), tween(260), label = "lean")
    val sides by remember { derivedStateOf { abs(angle.value) > 0.4f } }
    val shape = remember(w, h, depth) { BoxShape(w.toFloat(), h.toFloat(), depth) }
    val unit = Ui.unit
    Box(Modifier.size(w.px, h.px)
        .graphicsLayer { rotationZ = lean; transformOrigin = TransformOrigin(0.5f, 1f) }
        .onFocusChanged {
            if (it.isFocused && !focused) {
                tilt = (if (Random.nextBoolean()) 1f else -1f) * (10f + Random.nextFloat() * 6f)
                onFocused(t)
            }
            focused = it.isFocused
        }
        .pageItem(leftEdge, entry, fr)
        .onKeyEvent { e ->
            val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
            if (ok && e.type == KeyEventType.KeyUp) onOpen(t)
            ok
        }
        .semantics { onClick { onOpen(t); true } }
        .focusable()
        .drawWithContent {
            val s = density * unit
            val front = shape.corners(angle.value, grow.value, lift.value, 0).scaled(s)
            // shadow on the shelf, softer and wider while the box is lifted
            val g = glow.value
            drawOval(Brush.radialGradient(listOf(Color.Black.alpha(0.7f - 0.3f * g), Color.Transparent),
                center = Offset(size.width / 2, size.height), radius = size.width * (0.6f + 0.15f * g)),
                topLeft = Offset(-size.width * (0.08f + 0.12f * g), size.height - 7.px.toPx()),
                size = Size(size.width * (1.16f + 0.24f * g), 14.px.toPx()))
            if (g > 0.01f) {
                val path = quadPath(front)
                // a soft, thin halo (it used to be wide and bright)
                for ((wd, a) in listOf(9f to 0.07f, 5f to 0.12f)) drawPath(path, Ink.Gold.alpha(a * g), style = Stroke(wd.px.toPx()))
            }
            drawContent()
            if (g > 0.01f) drawPath(quadPath(front), Ink.Gold.alpha(0.85f * g), style = Stroke(2.px.toPx()))
            else drawPath(quadPath(front), Color.White.alpha(0.2f), style = Stroke(1f))
        }) {
        val faceMod = { face: Int -> Modifier.drawWithContent {
            val s = density * unit
            drawOnto(shape.corners(angle.value, grow.value, lift.value, face).scaled(s))
        } }
        if (sides) {
            Box(faceMod(1).size(depth.px, h.px)) {
                ShelfSpine(t, depth.px, h.px, dvd, vhsWidth / 86f)
                Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black.alpha(0.3f)) })
            }
            Box(faceMod(2).size(depth.px, h.px)) {
                ShelfSpine(t, depth.px, h.px, dvd, vhsWidth / 86f)
                Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black.alpha(0.3f)) })
            }
        }
        Box(faceMod(0).size(w.px, h.px)) {
            BoxFront(t, scale = vhsWidth / 86f, modifier = Modifier.fillMaxSize(), dvd = dvd)
            // the front darkens a touch as it turns from the light
            Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black.alpha(abs(angle.value) / 90f * 0.5f)) })
        }
    }
}

private fun FloatArray.scaled(s: Float) = FloatArray(size) { this[it] * s }

/** The narrow side of a box on the shelf: a coloured band at the top and the title running down it. */
@Composable
fun ShelfSpine(t: Title, w: Dp, h: Dp, dvd: Boolean, scale: Float) {
    val c = colorsOf(t)
    Column(Modifier.size(w, h).background(Brush.horizontalGradient(listOf(c.c2, c.c1))).boxWear(t, WearFace.Spine, t.box == null && !dvd),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height((18 * scale).px).background(c.c3), contentAlignment = Alignment.Center) {
            Text(if (dvd) "DVD" else "VHS", maxLines = 1, softWrap = false, style = TextStyle(fontFamily = Type.Mono,
                fontSize = ((if (dvd) 5 else 7) * scale).fs, color = Color(0xFF111111)))
        }
        Box(Modifier.weight(1f).padding(top = (6 * scale).px), contentAlignment = Alignment.TopCenter) {
            Text(coverText(t).uppercase(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.sideways(),
                style = TextStyle(fontFamily = Type.Oswald, fontWeight = FontWeight.Bold, fontSize = ((if (dvd) 7 else 11) * scale).fs,
                    letterSpacing = 0.8f.fs, color = Ink.CoverCream, shadow = Shadow(Color(0xCC000000), Offset(0f, 1f), 2f)))
        }
    }
}
