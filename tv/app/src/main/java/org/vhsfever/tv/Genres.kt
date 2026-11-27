package org.vhsfever.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.tv.material3.Text

/** How a genre tile is painted: background, lettering, and a slant or tilt like a store shelf sign. */
data class GenreLook(
    val bg: (Float, Float) -> Brush,
    val font: FontFamily,
    val size: Int,
    val color: Color = Color.White,
    val textBrush: Brush? = null,
    val shadow: Shadow? = null,
    val skew: Float = 0f,
    val tilt: Float = 0f,
    val upper: Boolean = true,
    val letter: Float = 0f,
)

private fun lin(vararg stops: Pair<Float, Color>) = { _: Float, _: Float -> Brush.verticalGradient(*stops) }

fun genreLook(name: String): GenreLook = when (name) {
    "Action" -> GenreLook(lin(0f to Color(0xFFFFB347), 0.55f to Color(0xFFE2521D), 0.56f to Color(0xFF3A1208), 1f to Color(0xFF14070A)),
        Type.Anton, 34, shadow = Shadow(Color(0xFF3A1208), Offset(2f, 3f), 0f), skew = -10f)
    "Comedy" -> GenreLook({ w, h -> Brush.linearGradient(listOf(Color(0xFF3FA9FF), Color(0xFF1B3FBF)), Offset(0f, 0f), Offset(w * .4f, h)) },
        Type.Bangers, 36, Color(0xFFFFD23F), shadow = Shadow(Color(0xFF1A1A6A), Offset(3f, 3f), 0f), tilt = -4f)
    "Horror" -> GenreLook({ w, h -> Brush.radialGradient(listOf(Color(0xFF6A0F1A), Color(0xFF0A0408)), Offset(w / 2, h * .45f), w * .55f) },
        Type.Creepster, 36, Color(0xFFFF2B2B), shadow = Shadow(Color(0xFFAA0000), Offset.Zero, 10f))
    "Science Fiction", "Sci-Fi" -> GenreLook(lin(0f to Color(0xFF0A1A5A), 1f to Color(0xFF050B26)),
        Type.Orbitron, 26, Color(0xFFCFE6FF), shadow = Shadow(Color(0xFF4A8BFF), Offset.Zero, 10f), skew = -8f)
    "Drama" -> GenreLook(lin(0f to Color(0xFFE08A4A), 0.6f to Color(0xFF6A2C3A), 1f to Color(0xFF2A1030)),
        Type.Playfair, 34, Color(0xFFF6D9B0), shadow = Shadow(Color(0x80000000), Offset(0f, 2f), 4f), upper = false)
    // old Hollywood: gold lettering on black, like a movie-palace marquee
    "Classics" -> GenreLook({ w, h -> Brush.radialGradient(listOf(Color(0xFF3A2E1A), Color(0xFF0B0906)), Offset(w / 2, h / 2), w * .6f) },
        Type.Playfair, 32, textBrush = Brush.verticalGradient(listOf(Color(0xFFFFF1B8), Color(0xFFD9A43A), Color(0xFF8A5A12))),
        shadow = Shadow(Color(0xFF000000), Offset(0f, 2f), 4f), upper = false)
    "Animation" -> GenreLook({ w, h -> Brush.linearGradient(listOf(Color(0xFFFF5FA2), Color(0xFF7A2BD6)), Offset(0f, 0f), Offset(w, h)) },
        Type.Bowlby, 22, Color.White, shadow = Shadow(Color(0xFF3A0A5A), Offset(2f, 3f), 0f), tilt = -3f)
    "Family" -> GenreLook(lin(0f to Color(0xFFFFD34D), 1f to Color(0xFFFF8A3D)),
        Type.Bangers, 36, Color.White, shadow = Shadow(Color(0xFFB5400F), Offset(3f, 3f), 0f), tilt = 3f)
    "Adventure" -> GenreLook({ w, h -> Brush.radialGradient(listOf(Color(0xFFFFD27A), Color(0xFF2E8B57), Color(0xFF0B3A24)), Offset(w * .8f, h * .2f), w * .9f) },
        Type.Anton, 30, Color(0xFFFFF1C4), shadow = Shadow(Color(0xFF0B3A24), Offset(2f, 3f), 0f), skew = -6f)
    "Fantasy" -> GenreLook({ w, h -> Brush.radialGradient(listOf(Color(0xFF6A4BD6), Color(0xFF1A0B3A)), Offset(w / 2, h / 2), w * .6f) },
        Type.Playfair, 32, Color(0xFFFFE6A8), shadow = Shadow(Color(0xFFFFC94A), Offset.Zero, 12f), upper = false)
    "Music & Musicals", "Music" -> GenreLook(lin(0f to Color(0xFF111118), 1f to Color(0xFF050507)),
        Type.Orbitron, 22, Color(0xFFFF3FA4), shadow = Shadow(Color(0xFFFF3FA4), Offset.Zero, 12f))
    "Documentary" -> GenreLook(lin(0f to Color(0xFF34343A), 1f to Color(0xFF111114)),
        Type.Mono, 20, Color(0xFFE9E3D0), letter = 2f)
    "Romance" -> GenreLook(lin(0f to Color(0xFFFF7A9A), 1f to Color(0xFF7A1A3A)),
        Type.Playfair, 32, Color(0xFFFFF0F4), shadow = Shadow(Color(0x80000000), Offset(0f, 2f), 4f), upper = false)
    "Thriller" -> GenreLook(lin(0f to Color(0xFF26262C), 0.7f to Color(0xFF0A0A0C), 1f to Color(0xFF3A0A0A)),
        Type.Oswald, 30, Color(0xFFE9E3D0), letter = 2f, shadow = Shadow(Color(0xFFB00000), Offset(2f, 2f), 0f))
    // a foggy green streetlamp glow
    "Mystery" -> GenreLook({ w, h -> Brush.radialGradient(listOf(Color(0xFF2F5A4A), Color(0xFF0A1412)), Offset(w * .7f, h * .3f), w * .7f) },
        Type.Playfair, 32, Color(0xFFDDF3E4), shadow = Shadow(Color(0xFF7FFFC4), Offset.Zero, 10f), upper = false, tilt = -2f)
    // police-tape yellow on asphalt
    "Crime" -> GenreLook(lin(0f to Color(0xFF1C1C20), 1f to Color(0xFF09090B)),
        Type.Anton, 32, Color(0xFFFFD400), letter = 3f, shadow = Shadow(Color(0xFF000000), Offset(2f, 2f), 0f), skew = -6f)
    // olive drab and stencil
    "War" -> GenreLook(lin(0f to Color(0xFF5A5F2E), 1f to Color(0xFF23250F)),
        Type.Oswald, 32, Color(0xFFE6DFC0), letter = 4f, shadow = Shadow(Color(0xFF14150A), Offset(2f, 2f), 0f))
    // the holiday displays
    "Christmas" -> GenreLook({ w, h -> Brush.radialGradient(listOf(Color(0xFFD42A2A), Color(0xFF5A0A12)), Offset(w / 2, h * .4f), w * .7f) },
        Type.Playfair, 32, Color.White, shadow = Shadow(Color(0xFF0E5A2A), Offset(2f, 2f), 0f), upper = false)
    "Halloween" -> GenreLook({ w, h -> Brush.radialGradient(listOf(Color(0xFFFF8A1E), Color(0xFF3A0E00), Color(0xFF0A0404)), Offset(w / 2, h * .5f), w * .6f) },
        Type.Creepster, 34, Color(0xFF1A0A00), shadow = Shadow(Color(0xFFFFB347), Offset.Zero, 8f))
    "Thanksgiving" -> GenreLook(lin(0f to Color(0xFFE09A3A), 0.6f to Color(0xFFA2481A), 1f to Color(0xFF4A1E0A)),
        Type.Playfair, 26, Color(0xFFFFF1D6), shadow = Shadow(Color(0xFF3A1206), Offset(1f, 2f), 3f), upper = false)
    "New Year's" -> GenreLook(lin(0f to Color(0xFF0B0B1E), 1f to Color(0xFF1A1440)),
        Type.Bowlby, 26, textBrush = Brush.verticalGradient(listOf(Color(0xFFFFF1B8), Color(0xFFD9A43A))), tilt = -3f)
    "Valentine's Day" -> GenreLook(lin(0f to Color(0xFFFF6A8E), 1f to Color(0xFFB0123E)),
        Type.Playfair, 28, Color.White, shadow = Shadow(Color(0xFF6A0A22), Offset(1f, 2f), 3f), upper = false)
    "Easter" -> GenreLook(lin(0f to Color(0xFFBFE8FF), 0.5f to Color(0xFFE8D4FF), 1f to Color(0xFFFFE3F1)),
        Type.Bangers, 38, Color(0xFF7A3AD6), shadow = Shadow(Color.White, Offset(2f, 2f), 0f), tilt = 3f)
    "St. Patrick's Day" -> GenreLook(lin(0f to Color(0xFF2FB34A), 1f to Color(0xFF0B4A1A)),
        Type.Bangers, 30, Color(0xFFFFE36A), shadow = Shadow(Color(0xFF06280E), Offset(2f, 3f), 0f), tilt = -3f)
    "4th of July" -> GenreLook(lin(0f to Color(0xFF1B2F8A), 0.55f to Color(0xFF0B1650), 0.56f to Color(0xFFB0122A), 1f to Color(0xFF6A0A18)),
        Type.Anton, 32, Color.White, shadow = Shadow(Color(0xFF0B1650), Offset(2f, 3f), 0f), skew = -8f)
    "Western" -> GenreLook(lin(0f to Color(0xFFE6A15A), 1f to Color(0xFF5A2A10)),
        Type.Playfair, 32, Color(0xFF2A1206), upper = false)
    "Kids" -> GenreLook(lin(0f to Color(0xFF5CD0F0), 1f to Color(0xFF2F66D4)),
        Type.Bangers, 38, Color(0xFFFFD23F), shadow = Shadow(Color(0xFF0A1A4A), Offset(3f, 3f), 0f), tilt = -4f)
    else -> GenreLook(lin(0f to Color(0xFF2A4FE0), 1f to Color(0xFF13287E)),
        Type.Oswald, 26, Ink.Gold, shadow = Shadow(Ink.BadgeShadow, Offset(2f, 2f), 0f))
}

/** Long names, broken over two lines on the sign instead of shrunk to a smudge. */
private val TWO_LINES = mapOf(
    "Science Fiction" to "Science\nFiction", "Music & Musicals" to "Music &\nMusicals",
    "Valentine's Day" to "Valentine's\nDay", "St. Patrick's Day" to "St. Patrick's\nDay",
)

/** The painted genre sign on a tile (the tile's frame and focus ring are drawn by the caller). */
@Composable
fun GenreArt(name: String, scale: Float = 1f) {
    val g = genreLook(name)
    Box(Modifier.fillMaxSize().drawBehind { drawRect(g.bg(size.width, size.height)) }, contentAlignment = Alignment.Center) {
        val shown = TWO_LINES[name] ?: name
        val label = if (g.upper) shown.uppercase() else shown
        val style = TextStyle(fontFamily = g.font, fontSize = (g.size * scale).fs,
            lineHeight = (g.size * scale).fs, color = g.color, shadow = g.shadow, letterSpacing = g.letter.fs,
            textAlign = TextAlign.Center).let { st -> if (g.textBrush != null) st.copy(brush = g.textBrush) else st }
        // shrink long names ("ANIMATION", "DOCUMENTARY") to one line instead of breaking the word
        FitText(label, style = style, maxLines = if (label.contains('\n')) 2 else 1, minScale = 0.4f,
            modifier = Modifier.padding(horizontal = (14 * scale).px)
                .graphicsLayer { rotationZ = g.tilt }
                .skewX(g.skew))
    }
}

/** Slants the content sideways (CSS skewX). */
fun Modifier.skewX(deg: Float): Modifier = if (deg == 0f) this else drawWithContent {
    val m = Matrix()
    m.values[Matrix.SkewX] = Math.tan(Math.toRadians(deg.toDouble())).toFloat()
    withTransform({
        translate(size.width / 2, size.height / 2)
        transform(m)
        translate(-size.width / 2, -size.height / 2)
    }) { this@drawWithContent.drawContent() }
}
