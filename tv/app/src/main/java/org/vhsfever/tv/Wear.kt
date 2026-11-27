package org.vhsfever.tv

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.min
import kotlin.random.Random

/**
 * Shelf wear on the made-up boxes (the ones with no real VHS scan), so they look like they've been
 * rented a few hundred times: scuffed corners, white showing along the folds, a crease, scratches,
 * a torn rental sticker, old tape gone yellow, sun fading, a rubbed spine.
 *
 * Every box gets its own mix, picked from its title, so the same box always looks the same and
 * no two shelves look alike. A few boxes are left nearly clean.
 */
enum class WearFace { Front, Spine, Back }

/** One mark of wear. Places and sizes are fractions of the face (0..1); [s] is how strong it is. */
sealed class WearMark {
    abstract val s: Float
    /** White card showing at a corner (0 top left, 1 top right, 2 bottom right, 3 bottom left). */
    data class Corner(val corner: Int, val size: Float, override val s: Float, val seed: Int) : WearMark()
    /** Flecks of white along a fold (edge 0 top, 1 right, 2 bottom, 3 left), between [from] and [to]. */
    data class Edge(val edge: Int, val from: Float, val to: Float, override val s: Float, val seed: Int) : WearMark()
    /** A fold line pressed into the card. */
    data class Crease(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val bend: Float, override val s: Float) : WearMark()
    data class Scratch(val x1: Float, val y1: Float, val x2: Float, val y2: Float, override val s: Float) : WearMark()
    /** Sun fading coming in from one edge. */
    data class Fade(val edge: Int, val reach: Float, override val s: Float) : WearMark()
    /** What's left of a rental sticker that was peeled off: a torn scrap, or just the glue mark. */
    data class Sticker(val x: Float, val y: Float, val w: Float, val h: Float, val tilt: Float, val scrap: Boolean,
                       override val s: Float, val seed: Int) : WearMark()
    /** A strip of old sticky tape, gone yellow, holding a split edge. */
    data class Tape(val x: Float, val y: Float, val w: Float, val h: Float, val tilt: Float, override val s: Float) : WearMark()
    /** Grubby fingers. */
    data class Smudge(val x: Float, val y: Float, val r: Float, override val s: Float) : WearMark()
    /** The spine worn pale where it was pulled off the shelf, between [from] and [to] down its length. */
    data class Rub(val from: Float, val to: Float, override val s: Float, val seed: Int) : WearMark()
}

object WearPlan {
    /** How worn the whole box is: 0 = nearly new, 1 = well loved. Same for every side of the box. */
    fun condition(key: String): Float {
        val r = Random(key.hashCode() * 7919 + 13)
        val roll = r.nextFloat()
        return when {
            roll < 0.12f -> 0.12f + r.nextFloat() * 0.15f        // nearly clean
            roll < 0.70f -> 0.45f + r.nextFloat() * 0.3f         // ordinary rental
            else -> 0.78f + r.nextFloat() * 0.22f                // well loved
        }
    }

    /** Left in the window: one edge of the box sun-faded (mostly the spine, which faced out). */
    private fun sunFaded(key: String) = Random(key.hashCode() * 104729 + 3).nextFloat() < 0.22f

    fun plan(key: String, face: WearFace): List<WearMark> {
        val cond = condition(key)
        val r = Random(key.hashCode() * 31 + face.ordinal * 977 + 5)
        fun str(lo: Float = 0.55f) = (cond * (lo + r.nextFloat() * (1f - lo))).coerceIn(0.08f, 1f)
        fun f(lo: Float, hi: Float) = lo + r.nextFloat() * (hi - lo)
        val out = ArrayList<WearMark>()

        // Corners: always the most worn part of a box. More of them on a well-loved one.
        val corners = (0..3).shuffled(r).take(
            when { cond < 0.3f -> r.nextInt(0, 2); cond < 0.75f -> r.nextInt(1, 3); else -> r.nextInt(2, 5) })
        val cornerSize = if (face == WearFace.Spine) 0.22f else 0.07f
        for (c in corners) {
            // a spine only has top and bottom ends to wear
            out += WearMark.Corner(c, cornerSize * f(0.6f, 1.4f), str(), r.nextInt())
        }

        // Then a few other things, a different handful on every box.
        val pool = when (face) {
            WearFace.Front -> listOf("edge", "edge", "crease", "scratch", "sticker", "tape", "smudge", "scratch")
            WearFace.Back -> listOf("edge", "edge", "crease", "sticker", "smudge", "scratch")
            WearFace.Spine -> listOf("edge", "rub", "rub", "scratch")
        }
        val extra = when { cond < 0.3f -> r.nextInt(0, 2); cond < 0.75f -> r.nextInt(1, 3); else -> r.nextInt(2, 4) }
        val picked = pool.shuffled(r).distinct().take(extra)
        for (kind in picked) out += when (kind) {
            "edge" -> {
                val from = f(0f, 0.5f)
                WearMark.Edge(if (face == WearFace.Spine) listOf(1, 3).random(r) else r.nextInt(4),
                    from, (from + f(0.25f, 0.7f)).coerceAtMost(1f), str(), r.nextInt())
            }
            "crease" -> {
                // across a corner, like the box was bent in a return slot
                val c = r.nextInt(4)
                val a = f(0.08f, 0.35f); val b = f(0.08f, 0.3f)
                val (x1, y1, x2, y2) = when (c) {
                    0 -> listOf(a, 0f, 0f, b)
                    1 -> listOf(1f - a, 0f, 1f, b)
                    2 -> listOf(1f, 1f - b, 1f - a, 1f)
                    else -> listOf(0f, 1f - b, a, 1f)
                }
                WearMark.Crease(x1, y1, x2, y2, f(-0.03f, 0.03f), str(0.6f))
            }
            "scratch" -> {
                val x = f(0.1f, 0.9f); val y = f(0.1f, 0.9f); val len = f(0.08f, 0.3f); val ang = f(0f, 6.283f)
                WearMark.Scratch(x, y, x + len * kotlin.math.cos(ang), y + len * kotlin.math.sin(ang), str(0.4f))
            }
            "sticker" -> {
                val top = r.nextBoolean()
                WearMark.Sticker(f(0.06f, 0.6f), if (top) f(0.03f, 0.18f) else f(0.72f, 0.86f), f(0.22f, 0.34f), f(0.08f, 0.12f),
                    f(-12f, 12f), r.nextFloat() < 0.6f, str(), r.nextInt())
            }
            "tape" -> {
                // across one of the side edges
                val left = r.nextBoolean()
                WearMark.Tape(if (left) f(-0.06f, 0f) else f(0.82f, 0.88f), f(0.15f, 0.8f), f(0.16f, 0.24f), f(0.05f, 0.07f),
                    f(-15f, 15f), str())
            }
            "smudge" -> WearMark.Smudge(f(0.15f, 0.85f), f(0.55f, 0.95f), f(0.12f, 0.25f), str())
            else -> {
                val from = f(0.25f, 0.55f)
                WearMark.Rub(from, (from + f(0.15f, 0.35f)).coerceAtMost(0.95f), str(), r.nextInt())
            }
        }

        if (sunFaded(key)) out += when (face) {
            WearFace.Spine -> WearMark.Fade(2, 1f, 0.9f)                  // the whole spine faced the window
            WearFace.Front -> WearMark.Fade(3, f(0.25f, 0.5f), 0.7f)     // from the spine side
            WearFace.Back -> WearMark.Fade(1, f(0.2f, 0.4f), 0.5f)
        }
        return out
    }
}

/**
 * Draws [t]'s wear over a face of its box. Only for made-up VHS boxes: real scans have their own
 * wear already, and plastic DVD cases don't fray.
 */
fun Modifier.boxWear(t: Title, face: WearFace, on: Boolean = t.box == null && !isDvd(t)): Modifier =
    if (!on) this else drawWithCache {
        val marks = WearPlan.plan(t.key, face)
        onDrawWithContent { drawContent(); clipRect { drawWear(marks) } }
    }

private val Card = Color(0xFFF3EEE2)       // the white card under the print
private val Glue = Color(0xFF8A7A5C)
private val OldTape = Color(0xFFE6C36A)
private val Grime = Color(0xFF2B2118)

fun DrawScope.drawWear(marks: List<WearMark>) {
    val w = size.width; val h = size.height
    val u = min(w, h)
    for (m in marks) when (m) {
        is WearMark.Fade -> {
            val (start, end) = when (m.edge) {
                0 -> Offset(0f, 0f) to Offset(0f, h * m.reach)
                1 -> Offset(w, 0f) to Offset(w * (1 - m.reach), 0f)
                2 -> Offset(0f, h) to Offset(0f, h * (1 - m.reach))
                else -> Offset(0f, 0f) to Offset(w * m.reach, 0f)
            }
            drawRect(Brush.linearGradient(listOf(Color(0xFFEFE6CF).copy(alpha = 0.3f * m.s), Color.Transparent), start, end))
        }
        is WearMark.Smudge -> drawCircle(Brush.radialGradient(listOf(Grime.copy(alpha = 0.22f * m.s), Color.Transparent),
            center = Offset(w * m.x, h * m.y), radius = u * m.r), radius = u * m.r, center = Offset(w * m.x, h * m.y))
        is WearMark.Rub -> {
            val r = Random(m.seed)
            val y0 = h * m.from; val y1 = h * m.to
            drawRect(Card.copy(alpha = 0.08f * m.s), Offset(0f, y0), Size(w, y1 - y0))
            repeat(40) {
                val y = y0 + r.nextFloat() * (y1 - y0)
                val x = r.nextFloat() * w * 0.5f
                drawLine(Card.copy(alpha = (0.1f + r.nextFloat() * 0.25f) * m.s), Offset(x, y),
                    Offset((x + w * (0.3f + r.nextFloat() * 0.7f)).coerceAtMost(w), y), strokeWidth = 0.5f + r.nextFloat() * u * 0.02f)
            }
        }
        is WearMark.Scratch -> drawLine(Card.copy(alpha = 0.3f * m.s), Offset(w * m.x1, h * m.y1), Offset(w * m.x2, h * m.y2),
            strokeWidth = maxOf(0.8f, u * 0.006f), cap = StrokeCap.Round)
        is WearMark.Crease -> {
            val p = Path().apply {
                moveTo(w * m.x1, h * m.y1)
                val mx = (m.x1 + m.x2) / 2 + m.bend; val my = (m.y1 + m.y2) / 2 + m.bend
                quadraticBezierTo(w * mx, h * my, w * m.x2, h * m.y2)
            }
            val sw = maxOf(1f, u * 0.008f)
            drawPath(p, Card.copy(alpha = 0.55f * m.s), style = Stroke(sw, cap = StrokeCap.Round))
            translate(sw * 0.9f, sw * 0.9f) { drawPath(p, Color.Black.copy(alpha = 0.3f * m.s), style = Stroke(sw, cap = StrokeCap.Round)) }
        }
        is WearMark.Tape -> rotate(m.tilt, Offset(w * (m.x + m.w / 2), h * (m.y + m.h / 2))) {
            val tl = Offset(w * m.x, h * m.y); val sz = Size(w * m.w, h * m.h)
            drawRect(OldTape.copy(alpha = 0.3f * m.s), tl, sz)
            drawRect(Color.White.copy(alpha = 0.18f * m.s), tl, Size(sz.width, sz.height * 0.25f))
            drawRect(OldTape.copy(alpha = 0.45f * m.s), tl, sz, style = Stroke(maxOf(0.6f, u * 0.004f)))
        }
        is WearMark.Sticker -> rotate(m.tilt, Offset(w * (m.x + m.w / 2), h * (m.y + m.h / 2))) {
            val r = Random(m.seed)
            val x0 = w * m.x; val y0 = h * m.y; val sw = w * m.w; val sh = h * m.h
            // the glue left behind, a little bigger than the sticker was
            drawRoundRect(Glue.copy(alpha = 0.2f * m.s), Offset(x0 - sw * 0.04f, y0 - sh * 0.1f), Size(sw * 1.08f, sh * 1.2f),
                androidx.compose.ui.geometry.CornerRadius(u * 0.01f))
            if (m.scrap) {
                // a torn scrap still stuck on: straight along the bottom, ripped along the top
                val p = Path().apply {
                    moveTo(x0, y0 + sh)
                    lineTo(x0 + sw * (0.55f + r.nextFloat() * 0.45f), y0 + sh)
                    var x = x0 + sw * (0.55f + r.nextFloat() * 0.4f)
                    lineTo(x, y0 + sh * (0.3f + r.nextFloat() * 0.4f))
                    val steps = 7
                    for (i in 1..steps) {
                        x -= (x - x0) / (steps - i + 1)
                        lineTo(x, y0 + sh * (r.nextFloat() * 0.6f))
                    }
                    close()
                }
                drawPath(p, Card.copy(alpha = 0.9f * minOf(1f, m.s + 0.3f)))
                drawPath(p, Glue.copy(alpha = 0.35f * m.s), style = Stroke(maxOf(0.6f, u * 0.004f)))
            }
        }
        is WearMark.Edge -> {
            val r = Random(m.seed)
            val along = if (m.edge == 0 || m.edge == 2) w else h
            val a0 = along * m.from; val a1 = along * m.to
            val depth = u * 0.03f
            // a faint pale line where the fold has rubbed, then flecks of white card
            line(m.edge, a0, a1, 0f, Card.copy(alpha = 0.25f * m.s), maxOf(0.8f, u * 0.008f))
            val n = (12 + (a1 - a0) / u * 30).toInt()
            repeat(n) {
                val p = a0 + r.nextFloat() * (a1 - a0)
                val len = u * (0.005f + r.nextFloat() * 0.035f)
                val inset = r.nextFloat() * r.nextFloat() * depth
                line(m.edge, p, (p + len).coerceAtMost(a1), inset, Card.copy(alpha = (0.35f + r.nextFloat() * 0.55f) * m.s),
                    maxOf(0.6f, u * (0.003f + r.nextFloat() * 0.01f)))
            }
        }
        is WearMark.Corner -> {
            val r = Random(m.seed)
            val c = when (m.corner) { 0 -> Offset(0f, 0f); 1 -> Offset(w, 0f); 2 -> Offset(w, h); else -> Offset(0f, h) }
            val rad = u * m.size
            drawCircle(Brush.radialGradient(listOf(Card.copy(alpha = 0.65f * m.s), Card.copy(alpha = 0.2f * m.s), Color.Transparent),
                center = c, radius = rad), radius = rad, center = c)
            // ragged flecks running off along both sides of the corner
            val dx = if (c.x == 0f) 1f else -1f; val dy = if (c.y == 0f) 1f else -1f
            repeat(10) {
                val alongX = r.nextBoolean()
                val d = rad * (0.4f + r.nextFloat() * 1.6f)
                val off = r.nextFloat() * rad * 0.18f
                val p = if (alongX) Offset(c.x + dx * d, c.y + dy * off) else Offset(c.x + dx * off, c.y + dy * d)
                drawCircle(Card.copy(alpha = (0.3f + r.nextFloat() * 0.6f) * m.s), radius = maxOf(0.6f, u * (0.002f + r.nextFloat() * 0.008f)), center = p)
            }
            // the very tip worn right through
            drawCircle(Card.copy(alpha = 0.85f * m.s), radius = maxOf(1f, rad * 0.18f), center = c)
        }
    }
}

/** A short line along [edge] from [a0] to [a1], [inset] in from it. */
private fun DrawScope.line(edge: Int, a0: Float, a1: Float, inset: Float, color: Color, width: Float) {
    val w = size.width; val h = size.height
    val (s, e) = when (edge) {
        0 -> Offset(a0, inset) to Offset(a1, inset)
        1 -> Offset(w - inset, a0) to Offset(w - inset, a1)
        2 -> Offset(a0, h - inset) to Offset(a1, h - inset)
        else -> Offset(inset, a0) to Offset(inset, a1)
    }
    drawLine(color, s, e, strokeWidth = width, cap = StrokeCap.Round)
}

