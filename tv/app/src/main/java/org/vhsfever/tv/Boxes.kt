package org.vhsfever.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

fun coverText(t: Title) = t.name
fun yearText(t: Title) = t.year ?: t.tapes.firstNotNullOfOrNull { it.releaseYear } ?: ""
/** VHS or DVD: whichever most of this title's good tapes are. */
fun mediaTag(t: Title): String {
    val m = t.tapes.mapNotNull { it.media }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    return if (m == "DVD") "DVD" else "VHS"
}
fun isDvd(t: Title) = mediaTag(t) == "DVD"

/**
 * Real boxes: a VHS sleeve is about 7.5 × 4.2 in and fat; a DVD case is 7.5 × 5.3 in and thin.
 * Same height on the shelf, DVDs about a quarter wider.
 */
const val DVD_WIDER = 1.25f
fun boxWidth(t: Title, vhsWidth: Int): Int = if (isDvd(t)) Math.round(vhsWidth * DVD_WIDER) else vhsWidth

private val coverShadow = Shadow(Color(0xD9000000), Offset(0f, 3f), 5f)

/**
 * The front of a rental box: the TMDB poster (or the box colours and title when there is no art),
 * a dark fade at the bottom, little VHS/year printing at the top and the plastic-sleeve shine.
 * [scale] is 1 for the 86×152 shelf box and grows with bigger boxes.
 */
@Composable
fun BoxFront(t: Title, scale: Float, modifier: Modifier = Modifier, dvd: Boolean = isDvd(t)) {
    val c = colorsFor(t.key)
    Box(modifier.background(Brush.verticalGradient(listOf(c.c1, c.c2)))) {
        Box(Modifier.fillMaxSize().drawBehind {
            drawRect(Brush.radialGradient(listOf(c.c3.alpha(0.47f), Color.Transparent),
                center = Offset(size.width / 2, size.height * 0.22f), radius = size.width * 0.9f))
        })
        if (t.poster != null) {
            AsyncImage(model = t.poster, contentDescription = t.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.64f).align(Alignment.BottomCenter)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB8000000)))))
        Row(Modifier.fillMaxWidth().padding(horizontal = (6 * scale).px, vertical = (6 * scale).px),
            horizontalArrangement = Arrangement.SpaceBetween) {
            val st = TextStyle(fontFamily = Type.Mono, fontSize = (7 * scale).fs, letterSpacing = (0.6f * scale).fs,
                color = Color.White.alpha(0.85f), shadow = Shadow(Color.Black, Offset(0f, 1f), 3f))
            Text(if (dvd) "DVD" else "VHS", style = st)
            Text(yearText(t), style = st)
        }
        if (t.poster == null) {
            Text(coverText(t).uppercase(), modifier = Modifier.align(Alignment.BottomStart)
                .padding(start = (7 * scale).px, end = (7 * scale).px, bottom = (10 * scale).px),
                style = TextStyle(fontFamily = Type.Oswald, fontWeight = FontWeight.Bold, fontSize = (15 * scale).fs,
                    lineHeight = (15 * scale).fs, color = Ink.CoverCream, shadow = coverShadow),
                maxLines = 5, overflow = TextOverflow.Ellipsis)
        }
        if (dvd) {
            // the plastic keep-case: dark rim and the hinge down the left side
            Box(Modifier.fillMaxSize().border((3 * scale).px, Color(0xFF0B1030), RoundedCornerShape((4 * scale).px)))
            Box(Modifier.fillMaxHeight().width((5 * scale).px).background(
                Brush.horizontalGradient(listOf(Color(0xFF0B1030), Color(0x660B1030), Color.Transparent))))
        }
        Sheen()
    }
}

@Composable
fun Sheen() {
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(
        0f to Color.White.alpha(0.24f), 0.36f to Color.Transparent, 0.7f to Color.Transparent, 1f to Color.White.alpha(0.08f),
        start = Offset(0f, 0f), end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY))))
}

/** Lays text out sideways, reading top to bottom like a box spine. */
fun Modifier.sideways(): Modifier = this
    .layout { m, c ->
        val p = m.measure(Constraints(maxWidth = c.maxHeight, maxHeight = c.maxWidth))
        layout(p.height, p.width) { p.place(-(p.width - p.height) / 2, (p.width - p.height) / 2) }
    }
    .graphicsLayer { rotationZ = 90f }

@Composable
fun BoxSpine(t: Title, w: Dp, h: Dp, titleSize: Int = 22, modifier: Modifier = Modifier, dvd: Boolean = isDvd(t)) {
    val c = colorsFor(t.key)
    Column(modifier.size(w, h).background(Brush.horizontalGradient(listOf(c.c1, c.c2))),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(28.px).background(c.c3), contentAlignment = Alignment.Center) {
            Text(if (dvd) "DVD" else "VHS", style = TextStyle(fontFamily = Type.Mono, fontWeight = FontWeight.Medium,
                fontSize = (if (dvd) 9 else 11).fs, letterSpacing = 1.1f.fs, color = Color(0xFF111111)))
        }
        Spacer(Modifier.height(14.px))
        Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
            Text(coverText(t).uppercase(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.sideways(),
                style = TextStyle(fontFamily = Type.Oswald, fontWeight = FontWeight.Bold, fontSize = titleSize.fs,
                    letterSpacing = 0.9f.fs, color = Ink.CoverCream, shadow = Shadow(Color(0xCC000000), Offset(0f, 2f), 3f)))
        }
        Barcode(Modifier.padding(vertical = 14.px).size(if (dvd) 16.px else 28.px, 54.px), vertical = true)
    }
}

@Composable
fun Barcode(modifier: Modifier, vertical: Boolean = false) {
    Box(modifier.background(Color(0xFFF1F1EE)).drawBehind {
        val bars = intArrayOf(2, 1, 3, 1, 1, 2, 1, 3, 2, 1, 1, 2, 3, 1, 2, 1, 1, 3, 1, 2)
        var p = 0f
        var i = 0
        val total = if (vertical) size.height else size.width
        val unit = total / 52f
        while (p < total) {
            val bw = bars[i % bars.size] * unit
            if (i % 2 == 0) {
                if (vertical) drawRect(Color(0xFF111111), Offset(0f, p), androidx.compose.ui.geometry.Size(size.width, bw))
                else drawRect(Color(0xFF111111), Offset(p, size.height * 0.15f), androidx.compose.ui.geometry.Size(bw, size.height * 0.7f))
            }
            p += bw + unit; i++
        }
    })
}

@Composable
fun BoxBack(t: Title, tapeText: String, modifier: Modifier = Modifier) {
    val c = colorsFor(t.key)
    Box(modifier.background(Brush.verticalGradient(listOf(c.c2, Color(0xFF09090B)))).padding(16.px)) {
        Column {
            Text(t.name.uppercase(), maxLines = 3, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Oswald,
                fontWeight = FontWeight.Bold, fontSize = 18.fs, letterSpacing = 0.5f.fs, color = Ink.Cream))
            Spacer(Modifier.height(12.px))
            Text(t.overview ?: "No summary yet.", maxLines = 9, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Barlow, fontSize = 14.fs, lineHeight = 19.fs, color = Color(0xFFDDD5BF)))
        }
        Text(tapeText, modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 42.px),
            style = TextStyle(fontFamily = Type.Mono, fontSize = 10.fs, letterSpacing = 0.6f.fs, color = Color(0xFFB9AE92)))
        Barcode(Modifier.align(Alignment.BottomStart).size(110.px, 34.px))
    }
}

/**
 * The 3D rental box on the detail page. [angle] is how far the box is turned:
 * 24 = three-quarter view, 0 = front, 90 = spine, 180 = back.
 * Each side is squeezed by how far it faces away (a flat, even view of the box) and slid into place,
 * so the edges meet. This draws the same on every TV, unlike real 3D layers.
 */
@Composable
fun Box3D(t: Title, angle: Float, tapeText: String, dvd: Boolean, modifier: Modifier = Modifier) {
    val a by animateFloatAsState(angle, tween(700, easing = CubicBezierEasing(.3f, .7f, .2f, 1f)), label = "turn")
    val w = if (dvd) 238f else 190f; val d = if (dvd) 26f else 46f; val h = 336f
    val density = LocalDensity.current.density
    val rad = Math.toRadians(a.toDouble())
    val toPx = 0.75f * density
    // where each face's middle ends up (mockup pixels), and which way it faces
    val faces = listOf(
        Triple("front", (d / 2) * sin(rad).toFloat(), a),
        Triple("spine", -(w / 2) * cos(rad).toFloat(), a - 90f),
        Triple("back", -(d / 2) * sin(rad).toFloat(), a - 180f),
        Triple("edge", (w / 2) * cos(rad).toFloat(), a + 90f),
    )
    Box(modifier.size(w.px, h.px), contentAlignment = Alignment.Center) {
        for ((name, cx, face) in faces) {
            val f = ((face % 360f) + 540f) % 360f - 180f   // -180..180
            if (abs(f) >= 90f) continue
            val faceW = if (name == "front" || name == "back") w else d
            Box(Modifier.size(faceW.px, h.px).graphicsLayer {
                translationX = cx * toPx
                scaleX = cos(Math.toRadians(f.toDouble())).toFloat()
                clip = true
            }) {
                when (name) {
                    "front" -> Box(Modifier.fillMaxSize()) {
                        BoxFront(t, scale = 1.36f, modifier = Modifier.fillMaxSize(), dvd = dvd)
                        Box(Modifier.fillMaxSize().border(1.dp, Color.White.alpha(0.12f)))
                    }
                    "spine" -> BoxSpine(t, d.px, h.px, titleSize = if (dvd) 14 else 22, dvd = dvd)
                    "back" -> BoxBack(t, tapeText, Modifier.fillMaxSize())
                    else -> Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xFF16161A), Color(0xFF0B0B0D)))))
                }
                // darken sides turned away from the light
                Box(Modifier.fillMaxSize().background(Color.Black.alpha((abs(f) / 90f) * 0.45f)))
            }
        }
    }
}

fun miniSpineWidth(t: Title) = if (isDvd(t)) 28 else 44

/** The little upright spine used in "Also in the store" (DVDs thinner, like the real thing). */
@Composable
fun MiniSpine(t: Title, modifier: Modifier = Modifier) {
    val c = colorsFor(t.key)
    Box(modifier.size(miniSpineWidth(t).px, 112.px).clip(RoundedCornerShape(2.px))
        .background(Brush.horizontalGradient(listOf(c.c1, c.c2))), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxHeight().width(3.px).align(Alignment.CenterStart).background(c.c3))
        Text(coverText(t).uppercase(), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.offset(x = 1.px).sideways(),
            style = TextStyle(fontFamily = Type.Oswald, fontWeight = FontWeight.Bold, fontSize = 12.fs, letterSpacing = 0.5f.fs,
                color = Ink.CoverCream, shadow = Shadow(Color.Black, Offset(0f, 1f), 2f)))
    }
}
