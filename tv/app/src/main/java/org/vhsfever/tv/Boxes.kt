package org.vhsfever.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextAlign
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
/** Width ÷ height of a plain VHS box. */
const val VHS_ASPECT = 86f / 152f

/**
 * Width ÷ height of this title's box front. A real box scan sets its own shape (sleeves weren't all the
 * same width), kept within sensible limits so a badly cut scan can't make a giant box; anything outside
 * them is squeezed a little to fit rather than having its edges cut off.
 */
fun frontAspect(t: Title): Float = when {
    isDvd(t) -> VHS_ASPECT * DVD_WIDER
    t.box != null -> t.box.aspect.coerceIn(0.53f, 0.63f)
    else -> VHS_ASPECT
}

fun boxWidth(t: Title, vhsWidth: Int): Int =
    if (!isDvd(t) && t.box == null) vhsWidth else Math.round(vhsWidth * frontAspect(t) / VHS_ASPECT)

private val coverShadow = Shadow(Color(0xD9000000), Offset(0f, 3f), 5f)

/**
 * The front of a rental box: the poster (or the box colours and title when there is no art),
 * a dark fade at the bottom, little VHS/year printing at the top and the plastic-sleeve shine.
 * [scale] is 1 for the 86×152 shelf box and grows with bigger boxes.
 */
@Composable
fun BoxFront(t: Title, scale: Float, modifier: Modifier = Modifier, dvd: Boolean = isDvd(t)) {
    val c = colorsOf(t)
    Box(modifier.background(Brush.verticalGradient(listOf(c.c1, c.c2)))) {
        Box(Modifier.fillMaxSize().drawBehind {
            drawRect(Brush.radialGradient(listOf(c.c3.alpha(0.47f), Color.Transparent),
                center = Offset(size.width / 2, size.height * 0.22f), radius = size.width * 0.9f))
        })
        val scan = t.box
        when {
            // the real sleeve: it has its own title and printing, so nothing is drawn over it but the shine
            // the whole front, never cut: the box takes the scan's shape, so at most it's squeezed a touch
            scan != null -> AsyncImage(model = scan.front, contentDescription = t.name,
                contentScale = if (dvd) ContentScale.Crop else ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize())
            t.poster != null -> PosterSleeve(t, c, scale, dvd)
            else -> {
                Row(Modifier.fillMaxWidth().padding(horizontal = (6 * scale).px, vertical = (6 * scale).px),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    val st = TextStyle(fontFamily = Type.Mono, fontSize = (7 * scale).fs, letterSpacing = (0.6f * scale).fs,
                        color = Color.White.alpha(0.85f), shadow = Shadow(Color.Black, Offset(0f, 1f), 3f))
                    Text(if (dvd) "DVD" else "VHS", style = st)
                    Text(yearText(t), style = st)
                }
                // no art: the title printed on the box, shrunk to fit rather than broken mid-word
                FitText(coverText(t).uppercase(), modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().fillMaxHeight(0.6f)
                    .padding(start = (7 * scale).px, end = (7 * scale).px, bottom = (10 * scale).px),
                    style = TextStyle(fontFamily = Type.Oswald, fontWeight = FontWeight.Bold, fontSize = (15 * scale).fs,
                        lineHeight = (15 * scale).fs, color = Ink.CoverCream, shadow = coverShadow),
                    maxLines = 5, minScale = 0.55f, wordsWhole = true, align = Alignment.BottomStart)
            }
        }
        // shelf wear on made-up boxes (real scans have their own)
        if (scan == null && !dvd) Box(Modifier.fillMaxSize().boxWear(t, WearFace.Front))
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
fun BoxSpine(t: Title, w: Dp, h: Dp, titleSize: Int = 22, modifier: Modifier = Modifier, dvd: Boolean = isDvd(t),
             style: SleeveStyle = sleeveStyle(t)) {
    val c = colorsOf(t)
    val art = t.art
    if (style.kids && !dvd) {
        Box(modifier.size(w, h).boxWear(t, WearFace.Spine, t.box == null)) {
            KidsSpineBody(t, kidsTheme(style, c), w, mini = false, titleSize = titleSize); SpineSheen()
        }
        return
    }
    Box(modifier.size(w, h).background(Brush.horizontalGradient(listOf(c.c1, c.c2))).boxWear(t, WearFace.Spine, t.box == null && !dvd)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            SpineCap(dvd, if (dvd) 28.px else 40.px, big = true)
            // the studio's logo, printed in cream like a real spine
            if (art?.studioLogo != null) AsyncImage(model = art.studioLogo, contentDescription = art.studio,
                colorFilter = ColorFilter.tint(Ink.CoverCream), contentScale = ContentScale.Fit,
                modifier = Modifier.padding(top = 12.px).size(w * 0.74f, 28.px))
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.px), contentAlignment = Alignment.Center) {
                SpineTitle(t, if (dvd) 0.86f else 0.84f, titleSize, maxLines = 2)
            }
            // the age rating in a little box, like the real ones
            art?.rating?.let { r ->
                Box(Modifier.heightIn(min = if (dvd) 18.px else 26.px).widthIn(min = if (dvd) 18.px else 26.px)
                    .border(2.px, Ink.CoverCream).padding(horizontal = 3.px), contentAlignment = Alignment.Center) {
                    Text(r, maxLines = 1, softWrap = false, style = TextStyle(fontFamily = Type.Oswald, fontWeight = FontWeight.Bold,
                        fontSize = ((if (dvd) 10 else 15) * (if (r.length > 2) 0.62f else 1f)).fs, color = Ink.CoverCream))
                }
            }
            Text(yearText(t), modifier = Modifier.padding(top = 10.px), style = TextStyle(fontFamily = Type.Mono,
                fontSize = (if (dvd) 8 else 11).fs, letterSpacing = 1f.fs, color = Ink.CoverCream))
            Barcode(barcodeText(t), Modifier.padding(top = 12.px, bottom = 14.px).size(if (dvd) 16.px else 30.px, 64.px), vertical = true)
        }
        SpineSheen()
    }
}

/** The cream "VHS" (or "DVD") cap at the top of a spine, with the gold stripe under it. */
@Composable
private fun SpineCap(dvd: Boolean, height: Dp, big: Boolean) {
    Column(Modifier.fillMaxWidth().height(height).background(Ink.CoverCream),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(if (dvd) "DVD" else "VHS", style = TextStyle(fontFamily = Type.Oswald, fontWeight = FontWeight.Bold,
            fontSize = (if (big) (if (dvd) 11 else 16) else 9).fs, letterSpacing = (if (big) 2f else 1f).fs, color = Color(0xFF111111)))
        if (big && !dvd) Text("HI-FI", maxLines = 1, softWrap = false, style = TextStyle(fontFamily = Type.Mono,
            fontSize = 7.fs, letterSpacing = 1.5f.fs, color = Color(0xFF3A3A3A)))
    }
    Box(Modifier.fillMaxWidth().height(if (big) 4.px else 2.px).background(Ink.Gold))
}

/**
 * The movie's title running down a spine: its TMDB title logo in the logo's own colours,
 * or, with no logo, the name in bold type that shrinks to fit (never cut off with "…").
 */
@Composable
internal fun SpineTitle(t: Title, across: Float, titleSize: Int, maxLines: Int) {
    val logo = t.art?.titleLogo
    if (logo != null) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AsyncImage(model = logo, contentDescription = t.name, contentScale = ContentScale.Fit,
                modifier = Modifier.requiredSize(maxHeight, maxWidth * across).graphicsLayer { rotationZ = 90f })
        }
    } else {
        FitText(coverText(t).uppercase(), modifier = Modifier.sideways(), maxLines = maxLines, minScale = 0.4f,
            textAlign = TextAlign.Center, align = Alignment.Center,
            style = TextStyle(fontFamily = Type.Oswald, fontWeight = FontWeight.Bold, fontSize = titleSize.fs,
                lineHeight = (titleSize * 1.08f).fs, letterSpacing = (titleSize * 0.04f).fs, color = Ink.CoverCream,
                shadow = Shadow(Color(0xCC000000), Offset(0f, 2f), 3f)))
    }
}

/** Light catching the plastic sleeve: brighter on the left edge, a touch darker on the right. */
@Composable
private fun SpineSheen() {
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
        0f to Color.White.alpha(0.2f), 0.3f to Color.Transparent, 0.75f to Color.Transparent, 1f to Color.Black.alpha(0.22f))))
}

/**
 * A real barcode of [text] (Code 128), drawn to fill its box: bars run across a vertical one.
 * Each movie's comes from its IMDb id, so no two boxes share one.
 */
@Composable
fun Barcode(text: String, modifier: Modifier, vertical: Boolean = false, paper: Color = Color(0xFFF1F1EE)) {
    val widths = remember(text) { Code128.widths(text) }
    Box(modifier.background(paper).drawBehind {
        val quiet = 4f  // blank modules each side
        val total = widths.sum() + quiet * 2
        val along = if (vertical) size.height else size.width
        val unit = along / total
        var p = quiet * unit
        widths.forEachIndexed { i, wMod ->
            val bw = wMod * unit
            if (i % 2 == 0) {
                if (vertical) drawRect(Color(0xFF111111), Offset(size.width * 0.12f, p),
                    androidx.compose.ui.geometry.Size(size.width * 0.76f, bw))
                else drawRect(Color(0xFF111111), Offset(p, size.height * 0.12f), androidx.compose.ui.geometry.Size(bw, size.height * 0.76f))
            }
            p += bw
        }
    })
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
    val h = 336f
    val w = if (dvd) 238f else if (t.box != null) h * frontAspect(t) else 190f
    val d = if (dvd) 26f else 54f
    val density = LocalDensity.current.density
    val rad = Math.toRadians(a.toDouble())
    val toPx = Ui.unit * density
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
                    "spine" -> t.box?.spine?.let { AsyncImage(model = it, contentDescription = null,
                        contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
                        ?: BoxSpine(t, d.px, h.px, titleSize = if (dvd) 14 else 22, dvd = dvd)
                    "back" -> t.box?.back?.let { AsyncImage(model = it, contentDescription = null,
                        contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
                        ?: BoxBack(t, tapeText, Modifier.fillMaxSize())
                    else -> Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xFF16161A), Color(0xFF0B0B0D)))))
                }
                // darken sides turned away from the light
                Box(Modifier.fillMaxSize().background(Color.Black.alpha((abs(f) / 90f) * 0.45f)))
            }
        }
    }
}

fun miniSpineWidth(t: Title) = if (isDvd(t)) 30 else 48
const val MINI_SPINE_HEIGHT = 156

/** The little upright spine used in "Similar movies" (DVDs thinner, like the real thing). */
@Composable
fun MiniSpine(t: Title, modifier: Modifier = Modifier) {
    val c = colorsOf(t)
    val dvd = isDvd(t)
    val style = sleeveStyle(t)
    if (style.kids && !dvd) {
        Box(modifier.size(miniSpineWidth(t).px, MINI_SPINE_HEIGHT.px).clip(RoundedCornerShape(2.px)).boxWear(t, WearFace.Spine)) {
            KidsSpineBody(t, kidsTheme(style, c), miniSpineWidth(t).px, mini = true, titleSize = 13); SpineSheen()
        }
        return
    }
    Box(modifier.size(miniSpineWidth(t).px, MINI_SPINE_HEIGHT.px).clip(RoundedCornerShape(2.px))
        .background(Brush.horizontalGradient(listOf(c.c1, c.c2))).boxWear(t, WearFace.Spine)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            SpineCap(dvd, 16.px, big = false)
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.px), contentAlignment = Alignment.Center) {
                SpineTitle(t, 0.84f, titleSize = if (dvd) 10 else 13, maxLines = 2)
            }
            Text(yearText(t), style = TextStyle(fontFamily = Type.Mono, fontSize = 8.fs, color = Ink.CoverCream))
            Barcode(barcodeText(t), Modifier.padding(top = 5.px, bottom = 7.px).size(if (dvd) 12.px else 18.px, 22.px), vertical = true)
        }
        SpineSheen()
    }
}
