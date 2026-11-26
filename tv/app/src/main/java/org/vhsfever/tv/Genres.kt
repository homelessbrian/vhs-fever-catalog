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
    "Sci-Fi" -> GenreLook(lin(0f to Color(0xFF0A1A5A), 1f to Color(0xFF050B26)),
        Type.Orbitron, 26, Color(0xFFCFE6FF), shadow = Shadow(Color(0xFF4A8BFF), Offset.Zero, 10f), skew = -8f)
    "Drama" -> GenreLook(lin(0f to Color(0xFFE08A4A), 0.6f to Color(0xFF6A2C3A), 1f to Color(0xFF2A1030)),
        Type.Playfair, 34, Color(0xFFF6D9B0), shadow = Shadow(Color(0x80000000), Offset(0f, 2f), 4f), upper = false)
    "Cult Classics" -> GenreLook({ _, _ -> Brush.verticalGradient(listOf(Color(0xFF09090D), Color(0xFF09090D))) },
        Type.Bowlby, 22, textBrush = Brush.horizontalGradient(listOf(Color(0xFFFF3FB1), Color(0xFF38FF8A))), tilt = -5f)
    "Animation" -> GenreLook({ w, h -> Brush.linearGradient(listOf(Color(0xFFFF5FA2), Color(0xFF7A2BD6)), Offset(0f, 0f), Offset(w, h)) },
        Type.Bowlby, 22, Color.White, shadow = Shadow(Color(0xFF3A0A5A), Offset(2f, 3f), 0f), tilt = -3f)
    "Family" -> GenreLook(lin(0f to Color(0xFFFFD34D), 1f to Color(0xFFFF8A3D)),
        Type.Bangers, 36, Color.White, shadow = Shadow(Color(0xFFB5400F), Offset(3f, 3f), 0f), tilt = 3f)
    "Adventure" -> GenreLook({ w, h -> Brush.radialGradient(listOf(Color(0xFFFFD27A), Color(0xFF2E8B57), Color(0xFF0B3A24)), Offset(w * .8f, h * .2f), w * .9f) },
        Type.Anton, 30, Color(0xFFFFF1C4), shadow = Shadow(Color(0xFF0B3A24), Offset(2f, 3f), 0f), skew = -6f)
    "Fantasy" -> GenreLook({ w, h -> Brush.radialGradient(listOf(Color(0xFF6A4BD6), Color(0xFF1A0B3A)), Offset(w / 2, h / 2), w * .6f) },
        Type.Playfair, 32, Color(0xFFFFE6A8), shadow = Shadow(Color(0xFFFFC94A), Offset.Zero, 12f), upper = false)
    "Music" -> GenreLook(lin(0f to Color(0xFF111118), 1f to Color(0xFF050507)),
        Type.Orbitron, 26, Color(0xFFFF3FA4), shadow = Shadow(Color(0xFFFF3FA4), Offset.Zero, 12f))
    "Documentary" -> GenreLook(lin(0f to Color(0xFF34343A), 1f to Color(0xFF111114)),
        Type.Mono, 20, Color(0xFFE9E3D0), letter = 2f)
    "Romance" -> GenreLook(lin(0f to Color(0xFFFF7A9A), 1f to Color(0xFF7A1A3A)),
        Type.Playfair, 32, Color(0xFFFFF0F4), shadow = Shadow(Color(0x80000000), Offset(0f, 2f), 4f), upper = false)
    "Thriller", "Mystery", "Crime" -> GenreLook(lin(0f to Color(0xFF26262C), 0.7f to Color(0xFF0A0A0C), 1f to Color(0xFF3A0A0A)),
        Type.Oswald, 30, Color(0xFFE9E3D0), letter = 2f, shadow = Shadow(Color(0xFFB00000), Offset(2f, 2f), 0f))
    "Western" -> GenreLook(lin(0f to Color(0xFFE6A15A), 1f to Color(0xFF5A2A10)),
        Type.Playfair, 32, Color(0xFF2A1206), upper = false)
    "Kids" -> GenreLook(lin(0f to Color(0xFF5CD0F0), 1f to Color(0xFF2F66D4)),
        Type.Bangers, 38, Color(0xFFFFD23F), shadow = Shadow(Color(0xFF0A1A4A), Offset(3f, 3f), 0f), tilt = -4f)
    else -> GenreLook(lin(0f to Color(0xFF2A4FE0), 1f to Color(0xFF13287E)),
        Type.Oswald, 26, Ink.Gold, shadow = Shadow(Ink.BadgeShadow, Offset(2f, 2f), 0f))
}

/** The painted genre sign on a tile (the tile's frame and focus ring are drawn by the caller). */
@Composable
fun GenreArt(name: String, scale: Float = 1f) {
    val g = genreLook(name)
    Box(Modifier.fillMaxSize().drawBehind { drawRect(g.bg(size.width, size.height)) }, contentAlignment = Alignment.Center) {
        val label = if (name == "Cult Classics") "CULT\nCLASSICS" else if (g.upper) name.uppercase() else name
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
