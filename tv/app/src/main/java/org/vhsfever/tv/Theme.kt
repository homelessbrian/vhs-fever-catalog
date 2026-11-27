package org.vhsfever.tv

import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The mockups are drawn on a 1280×720 canvas. TVs report different sizes (960×540 dp on many,
 * 1280×720 dp on others), so one mockup pixel is worth [Ui.unit] dp, worked out from the real
 * screen when the app opens. `.px` and `.fs` convert mockup sizes. The app also ignores the TV's
 * "large text" setting (see MainActivity), so text always fits the space drawn for it.
 */
object Ui {
    @Volatile var unit = 0.75f
    fun fit(widthDp: Float, heightDp: Float) { unit = minOf(widthDp / 1280f, heightDp / 720f) }
}
val Int.px: Dp get() = (this * Ui.unit).dp
val Float.px: Dp get() = (this * Ui.unit).dp
val Int.fs: TextUnit get() = (this * Ui.unit).sp
val Float.fs: TextUnit get() = (this * Ui.unit).sp

object Ink {
    val Navy = Color(0xFF080B22)
    val NavyTop = Color(0xFF0C1340)
    val Gold = Color(0xFFF7C52B)
    val Pink = Color(0xFFFF3FA4)
    val Cream = Color(0xFFF1EAD8)
    val CoverCream = Color(0xFFFFF6DF)
    val RailText = Color(0xFFF1F2FF)
    val Lavender = Color(0xFFD5D9FF)
    val Muted = Color(0xFFAEB4EE)
    val Soft = Color(0xFFC9CDF5)
    val Body = Color(0xFFEEF0FF)
    val BadgeTop = Color(0xFF2A4FE0)
    val BadgeBottom = Color(0xFF13287E)
    val BadgeShadow = Color(0xFF0A1450)
    val Dark = Color(0xFF14120A)
}

object Type {
    val Oswald = FontFamily(Font(R.font.oswald_bold, FontWeight.Bold), Font(R.font.oswald_medium, FontWeight.Medium))
    val Barlow = FontFamily(
        Font(R.font.barlow_regular, FontWeight.Normal),
        Font(R.font.barlow_medium, FontWeight.Medium),
        Font(R.font.barlow_bold, FontWeight.Bold),
    )
    val Condensed = FontFamily(Font(R.font.barlow_condensed_bold, FontWeight.Bold))
    val Mono = FontFamily(Font(R.font.plex_mono_regular, FontWeight.Normal), Font(R.font.plex_mono_medium, FontWeight.Medium))
    val Vt = FontFamily(Font(R.font.vt323))
    val Playfair = FontFamily(Font(R.font.playfair_black_italic, FontWeight.Black, FontStyle.Italic))
    val Anton = FontFamily(Font(R.font.anton))
    val Bangers = FontFamily(Font(R.font.bangers))
    val Creepster = FontFamily(Font(R.font.creepster))
    val Orbitron = FontFamily(Font(R.font.orbitron_extrabold, FontWeight.ExtraBold))
    val Bowlby = FontFamily(Font(R.font.bowlby_one))
}

/** Box colours (body top, body bottom, accent) from the mockups, picked per title so each box keeps its colours. */
/** [top] and [bottom]: the colours along the poster's top and bottom edges, when known (for sleeves that fade into them). */
data class BoxColors(val c1: Color, val c2: Color, val c3: Color, val top: Color? = null, val bottom: Color? = null)

private val PALETTE = listOf(
    BoxColors(Color(0xFF4A4A58), Color(0xFF0B0B10), Color(0xFFE6E0CC)),
    BoxColors(Color(0xFFB2362C), Color(0xFF2A0807), Color(0xFFFFC65A)),
    BoxColors(Color(0xFF3F9A3A), Color(0xFF0B2A0E), Color(0xFFF5E06A)),
    BoxColors(Color(0xFFE07418), Color(0xFF3D1A05), Color(0xFF5CD0F0)),
    BoxColors(Color(0xFF2F66D4), Color(0xFF0A1A4A), Color(0xFFFF9A52)),
    BoxColors(Color(0xFF7A3FB0), Color(0xFF1D0F33), Color(0xFFFFD45E)),
    BoxColors(Color(0xFFC8303F), Color(0xFF33080F), Color(0xFF8FE2FF)),
    BoxColors(Color(0xFF2D6070), Color(0xFF051218), Color(0xFFFF7448)),
    BoxColors(Color(0xFF2A8A7E), Color(0xFF05241F), Color(0xFFB4F060)),
    BoxColors(Color(0xFF6C6844), Color(0xFF0B0B06), Color(0xFFE4D296)),
    BoxColors(Color(0xFF2F9444), Color(0xFF052A0E), Color(0xFFFF72B4)),
    BoxColors(Color(0xFF4A56B8), Color(0xFF0A0C2E), Color(0xFFDCE4FF)),
    BoxColors(Color(0xFF8A36A0), Color(0xFF1D0626), Color(0xFFFFD45E)),
)

fun colorsFor(key: String): BoxColors = PALETTE[Math.floorMod(key.hashCode(), PALETTE.size)]

/** A box's colours: taken from its poster when that has been looked at, otherwise the stand-in set for the title. */
fun colorsOf(t: Title): BoxColors = t.colors ?: colorsFor(t.key)

fun Color.alpha(a: Float) = copy(alpha = a)

/**
 * Lifts something off the shelf when highlighted. Only the drawing moves, not its place on screen,
 * so the remote still sees the boxes in a straight row (a real move made Down jump to the next box).
 */
fun androidx.compose.ui.Modifier.lift(dy: Dp): androidx.compose.ui.Modifier = drawWithContent {
    translate(0f, dy.toPx()) { this@drawWithContent.drawContent() }
}
