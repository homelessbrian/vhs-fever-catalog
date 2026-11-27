package org.vhsfever.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Text
import kotlin.math.cos
import kotlin.math.sin

/*
 * Kids' and family boxes. Cartoons and family movies (and every movie in a series that has one) pick from
 * six bright looks instead of the grown-up ones. Each look has a matching back and spine: the same paper
 * colour, lettering and trim (crayon scribble, rainbow, stitching, confetti, checkerboard or clouds).
 */

/** Cartoons and family movies get the kids' box looks. */
fun isKidsMovie(t: Title) = t.section == "Animation" || t.section == "Family"

/** Marks the boxes that get kids' looks: kids' movies, and every movie in a series that has one, so a series still matches. */
fun withKidsLooks(titles: List<Title>): List<Title> {
    val kidsSeries = titles.filter(::isKidsMovie).mapNotNull { it.art?.collection }.toSet()
    return titles.map { t ->
        val kids = isKidsMovie(t) || t.art?.collection?.let { it in kidsSeries } == true
        if (kids == t.kidsLook) t else t.copy(kidsLook = kids)
    }
}

enum class KidsTrim { Scribble, Rainbow, Stitch, Confetti, Checker, Clouds }

/** The paper, inks and trim one kids' look uses on its front, back and spine. */
class KidsTheme(
    val bg: Brush, val flat: Color, val ink: Color, val accent: Color, val tagBg: Color, val tagFg: Color,
    val font: FontFamily, val trim: KidsTrim, val trimColor: Color, val card: Color, val cardInk: Color,
    val italic: Boolean = false,
    val burst: Boolean = false,   // the "fun for the whole family" starburst
)

internal val Sunny = Color(0xFFFFD83A)
internal val Snow = Color(0xFFFBFAF5)
internal val StoryCream = Color(0xFFF3E7C9)
internal val StoryBrown = Color(0xFF7A4A1F)
internal val StoryGold = Color(0xFFD8A63A)
internal val SkyTop = Color(0xFF3FA5EE)
internal val SkyLow = Color(0xFF8FD3FF)
internal val SkyInk = Color(0xFF0E4F8A)
internal val RainbowColors = listOf(Color(0xFFE8343A), Color(0xFFFF8F1F), Color(0xFFFFD21F), Color(0xFF3CB44A), Color(0xFF2F7BE0), Color(0xFF7A4FD0))
private val KidInk = Color(0xFF222222)

/** A poster colour dark enough to print on light paper. */
internal fun deepen(c: Color) = if (c.luminance() < 0.2f) c else lerp(c, Color.Black, 0.55f)
/** A poster colour bright enough to read as a kids' colour. */
internal fun brighten(c: Color) = if (c.luminance() > 0.12f) c else lerp(c, Color.White, 0.35f)
private fun inkOnKid(bg: Color) = if (bg.luminance() > 0.55f) KidInk else Color.White

fun kidsTheme(style: SleeveStyle, c: BoxColors): KidsTheme = when (style) {
    SleeveStyle.Crayon -> brighten(c.c1).let { a ->
        KidsTheme(SolidColor(Sunny), Sunny, deepen(c.c2), a, a, inkOnKid(a), Type.Bowlby, KidsTrim.Scribble, a,
            Color.White.alpha(0.85f), KidInk)
    }
    SleeveStyle.Rainbow -> KidsTheme(SolidColor(Snow), Snow, KidInk, RainbowColors[4], RainbowColors[4], Color.White,
        Type.Bowlby, KidsTrim.Rainbow, RainbowColors[0], Color(0xFFEFEDE4), KidInk)
    SleeveStyle.Storybook -> KidsTheme(SolidColor(StoryCream), StoryCream, StoryBrown, StoryGold, StoryGold, Color(0xFF4A2A0A),
        Type.Playfair, KidsTrim.Stitch, Color(0xFFA5753C), Color(0xFFFBF4E2), StoryBrown, italic = true)
    SleeveStyle.Confetti -> brighten(c.c1).let { a ->
        KidsTheme(SolidColor(a), a, inkOnKid(a), Sunny, Sunny, deepen(c.c2), Type.Bangers, KidsTrim.Confetti, Sunny,
            Color.White.alpha(0.9f), KidInk)
    }
    SleeveStyle.Starburst -> deepen(c.c2).let { d ->
        KidsTheme(SolidColor(d), d, Color.White, Sunny, Sunny, d, Type.Bowlby, KidsTrim.Checker, Color.White,
            Color.White.alpha(0.92f), KidInk, burst = true)
    }
    else -> KidsTheme(Brush.verticalGradient(listOf(SkyTop, SkyLow)), SkyTop, Color.White, Sunny, Sunny, Color(0xFFC2410C),
        Type.Bowlby, KidsTrim.Clouds, Color.White, Color.White.alpha(0.9f), SkyInk)
}

/** Kids' lettering at [size] mockup units (times [scale]). */
internal fun KidsTheme.type(color: Color, size: Float, scale: Float = 1f, family: FontFamily = font) = TextStyle(
    fontFamily = family, fontSize = (size * scale).fs, lineHeight = (size * scale * 1.05f).fs, color = color,
    fontWeight = if (family == Type.Playfair) FontWeight.Black else FontWeight.Normal,
    fontStyle = if (family == Type.Playfair) FontStyle.Italic else FontStyle.Normal,
    letterSpacing = (if (family == Type.Bangers) 0.5f * scale else 0.2f * scale).fs,
)

// ------------------------------------------------------------------ trims, drawn in mockup units [u] (pixels per unit)

/** A wobbly crayon line along the top or bottom edge. */
internal fun DrawScope.scribble(color: Color, u: Float, atTop: Boolean, inset: Float = 1.6f) {
    val y0 = if (atTop) inset * u else size.height - inset * u
    val amp = 0.9f * u; val period = 6f * u
    val p = Path().apply {
        moveTo(0f, y0)
        var x = 0f
        while (x < size.width) {
            quadraticBezierTo(x + period / 4, y0 - amp * 2, x + period / 2, y0)
            quadraticBezierTo(x + period * 3 / 4, y0 + amp * 2, x + period, y0)
            x += period
        }
    }
    drawPath(p, color, style = Stroke(width = maxOf(1f, 0.6f * u), cap = StrokeCap.Round))
}

/** Six rainbow stripes filling the area, top to bottom (or flipped). */
internal fun DrawScope.rainbow(reversed: Boolean) {
    val cs = if (reversed) RainbowColors.reversed() else RainbowColors
    val h = size.height / cs.size
    cs.forEachIndexed { i, c -> drawRect(c, Offset(0f, i * h), Size(size.width, h + 0.5f)) }
}

/** A black-and-white checkerboard filling the area. */
internal fun DrawScope.checker(u: Float, dark: Color = Color(0xFF111111)) {
    drawRect(Color.White)
    val sq = size.height / 2
    var x = 0f; var col = 0
    while (x < size.width) {
        drawRect(dark, Offset(x, if (col % 2 == 0) 0f else sq), Size(sq, sq))
        x += sq; col++
    }
}

/** Puffy cloud scallops along an edge. */
internal fun DrawScope.clouds(u: Float, atTop: Boolean, color: Color = Color.White, r: Float = 2.6f) {
    val rr = r * u
    val y = if (atTop) 0f else size.height
    var x = rr * 0.4f; var i = 0
    while (x < size.width + rr) {
        val big = if (i % 2 == 0) 1f else 0.72f
        drawCircle(color, rr * big, Offset(x, y + (if (atTop) -1 else 1) * rr * 0.25f))
        x += rr * 1.45f; i++
    }
}

/** A dashed stitch line around the area, [inset] units in. */
internal fun DrawScope.stitch(color: Color, u: Float, inset: Float) {
    val i = inset * u
    drawRect(color, Offset(i, i), Size(size.width - 2 * i, size.height - 2 * i),
        style = Stroke(width = maxOf(1f, 0.6f * u), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.2f * u, 1.4f * u))))
}

private val CONFETTI = listOf(
    0.06f to 0.2f, 0.18f to 0.75f, 0.31f to 0.3f, 0.42f to 0.82f, 0.55f to 0.18f, 0.63f to 0.6f, 0.74f to 0.28f,
    0.86f to 0.78f, 0.95f to 0.35f, 0.5f to 0.5f,
)

/** Confetti dots, triangles and squiggles, only between [from] and [to] across (where there's no lettering). */
internal fun DrawScope.confetti(u: Float, colors: List<Color>, from: Float = 0f, to: Float = 1f, seed: Int = 0) {
    CONFETTI.forEachIndexed { n, (fx, fy) ->
        val x = size.width * (from + (to - from) * ((fx + seed * 0.37f) % 1f)); val y = size.height * fy
        val c = colors[(n + seed) % colors.size]
        when ((n + seed) % 3) {
            0 -> drawCircle(c, 0.8f * u, Offset(x, y))
            1 -> drawPath(Path().apply { moveTo(x, y); lineTo(x + 2.2f * u, y - 1.3f * u); lineTo(x + 0.9f * u, y + 1.4f * u); close() }, c)
            else -> drawPath(Path().apply {
                moveTo(x, y); quadraticBezierTo(x + 0.9f * u, y - 1.3f * u, x + 1.8f * u, y)
                quadraticBezierTo(x + 2.7f * u, y + 1.3f * u, x + 3.6f * u, y)
            }, c, style = Stroke(width = maxOf(1f, 0.5f * u), cap = StrokeCap.Round))
        }
    }
}

/** A cartoon starburst, filling its box. */
internal val StarburstShape = GenericShape { size, _ ->
    val cx = size.width / 2; val cy = size.height / 2
    val points = 12
    for (i in 0 until points * 2) {
        val r = (if (i % 2 == 0) 0.5f else 0.36f) * minOf(size.width, size.height)
        val a = Math.PI * i / points - Math.PI / 2
        val x = cx + (r * cos(a)).toFloat(); val y = cy + (r * sin(a)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** A ribbon tag with a notched left end. */
internal val RibbonShape = GenericShape { size, _ ->
    moveTo(size.width * 0.12f, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height)
    lineTo(size.width * 0.12f, size.height); lineTo(0f, size.height / 2); close()
}

// ------------------------------------------------------------------ the back

/** Which of the three kids' backs a movie gets (a series shares one). */
fun kidsBackLayout(t: Title): Int = Math.floorMod(lookKey(t).hashCode() * 17 + 3, 3)

@Composable
internal fun KidsBack(b: BackText, c: BoxColors, th: KidsTheme, layout: Int) {
    val unit = Ui.unit * androidx.compose.ui.platform.LocalDensity.current.density
    Box(Modifier.fillMaxSize().background(th.bg)) {
        Column(Modifier.fillMaxSize()) {
            KidsStrip(th, top = true)
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 11.px, vertical = 9.px),
                verticalArrangement = Arrangement.spacedBy(6.px)) {
                when (layout) {
                    0 -> KidsBackPictures(b, th)
                    1 -> KidsBackBigPicture(b, th)
                    else -> KidsBackBubbles(b, th)
                }
            }
            KidsStrip(th, top = false)
        }
        // trims over the whole back
        Box(Modifier.fillMaxSize().drawBehind {
            when (th.trim) {
                KidsTrim.Stitch -> stitch(th.trimColor, unit, 4.5f)
                KidsTrim.Confetti -> { confetti(unit * 1.4f, listOf(Color.White, Sunny, th.tagFg), 0.05f, 0.95f, 0) }
                else -> Unit
            }
        })
    }
}

/** The trim band across the top or bottom of a back. */
@Composable
private fun KidsStrip(th: KidsTheme, top: Boolean) {
    val unit = Ui.unit * androidx.compose.ui.platform.LocalDensity.current.density
    when (th.trim) {
        KidsTrim.Rainbow -> Box(Modifier.fillMaxWidth().height(9.px).drawBehind { rainbow(reversed = !top) })
        KidsTrim.Checker -> Box(Modifier.fillMaxWidth().height(7.px).drawBehind { checker(unit) })
        KidsTrim.Scribble -> Box(Modifier.fillMaxWidth().height(7.px).drawBehind {
            scribble(th.trimColor, unit * 1.3f, atTop = !top, inset = 2.4f); scribble(th.ink.alpha(0.6f), unit, atTop = !top, inset = 4.6f)
        })
        KidsTrim.Clouds -> Box(Modifier.fillMaxWidth().height(9.px).drawBehind { clouds(unit, atTop = !top, r = 4f) })
        KidsTrim.Stitch -> Spacer(Modifier.height(5.px))
        KidsTrim.Confetti -> Spacer(Modifier.height(4.px))
    }
}

@Composable
private fun KidsTitle(b: BackText, th: KidsTheme, size: Float = 21f, align: TextAlign = TextAlign.Start, color: Color = th.ink) {
    val shadow = if (th.trim == KidsTrim.Confetti || th.trim == KidsTrim.Clouds) Shadow(deepen(th.tagFg), Offset(0f, 2f), 0f) else null
    FitText(b.name, style = th.type(color, size).copy(shadow = shadow, textAlign = align), maxLines = 2, minScale = 0.5f,
        wordsWhole = true, textAlign = align, modifier = Modifier.fillMaxWidth(),
        align = if (align == TextAlign.Center) Alignment.Center else Alignment.TopStart)
}

@Composable
private fun KidsTagline(b: BackText, th: KidsTheme, align: TextAlign = TextAlign.Start) {
    b.tagline?.let {
        val col = if (th.trim == KidsTrim.Rainbow) RainbowColors[0] else if (th.trim == KidsTrim.Stitch) StoryBrown else th.accent
        FitText(if (th.italic) it else it.uppercase(), style = th.type(col, 10f).copy(textAlign = align), maxLines = 2,
            minScale = 0.55f, textAlign = align, modifier = Modifier.fillMaxWidth(),
            align = if (align == TextAlign.Center) Alignment.Center else Alignment.TopStart)
    }
}

/** The story on a rounded card, so it reads on any paper colour. */
@Composable
private fun ColumnScope.KidsStory(b: BackText, th: KidsTheme, maxLines: Int = 11) {
    Column(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.px)).background(th.card).padding(7.px),
        verticalArrangement = Arrangement.spacedBy(5.px)) {
        FitText(b.overview, style = TextStyle(fontFamily = Type.Barlow, fontWeight = FontWeight.Medium, fontSize = 10.5f.fs,
            lineHeight = 13.5f.fs, color = th.cardInk), maxLines = maxLines, minScale = 0.72f,
            modifier = Modifier.fillMaxWidth().weight(1f, fill = true))
        BackCredits(b, if (th.trim == KidsTrim.Stitch) StoryGold else RainbowColors[4], th.cardInk)
    }
}

/** A picture with rounded corners and a white border, like a kids' photo sticker. */
@Composable
private fun KidsPic(model: Any?, modifier: Modifier, round: Dp = 7.px, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(round)) {
    Box(modifier.clip(shape).background(Color.White).padding(2.5f.px).clip(shape)) {
        BackPic(model, Modifier.fillMaxSize())
    }
}

@Composable
private fun KidsInfo(b: BackText, th: KidsTheme) {
    // the shared info row prints in near-black or white; kids' paper picks whichever reads
    val ink = if (th.flat.luminance() > 0.5f) BackNight else BackPaper
    BackInfoRow(b, ink)
}

/** 1 · Title, a row of three rounded pictures, the story on a card. */
@Composable
private fun ColumnScope.KidsBackPictures(b: BackText, th: KidsTheme) {
    KidsTitle(b, th)
    KidsTagline(b, th)
    if (b.pictures.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.px)) {
        for (i in 0..2) KidsPic(b.pic(i), Modifier.weight(1f).height(44.px), shape = RoundedCornerShape(7.px))
    }
    KidsStory(b, th)
    KidsInfo(b, th)
}

/** 2 · A big rounded picture with the title on a tilted tag; Starburst boxes get their burst too. */
@Composable
private fun ColumnScope.KidsBackBigPicture(b: BackText, th: KidsTheme) {
    Box(Modifier.fillMaxWidth().height(108.px)) {
        KidsPic(b.pic(0), Modifier.fillMaxSize(), round = 10.px)
        if (th.burst) {
            Box(Modifier.align(Alignment.TopEnd).offset(6.px, (-6).px).size(52.px).rotate(10f).clip(StarburstShape).background(Sunny),
                contentAlignment = Alignment.Center) {
                Text("FUN FOR\nTHE WHOLE\nFAMILY!", textAlign = TextAlign.Center,
                    style = TextStyle(fontFamily = Type.Bangers, fontSize = 7.6f.fs, lineHeight = 7.6f.fs, color = Color(0xFFD81F2C)))
            }
        }
        Box(Modifier.align(Alignment.BottomStart).offset((-3).px, 10.px).rotate(-3f).clip(RoundedCornerShape(5.px))
            .background(th.tagBg).padding(horizontal = 7.px, vertical = 3.px).widthIn(max = 150.px)) {
            FitText(b.name, style = th.type(th.tagFg, 15f), maxLines = 2, minScale = 0.5f, wordsWhole = true)
        }
    }
    Spacer(Modifier.height(4.px))
    KidsTagline(b, th)
    KidsStory(b, th, 9)
    if (b.pictures.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.px)) {
        for (i in 1..2) KidsPic(b.pic(i), Modifier.weight(1f).height(36.px))
    }
    KidsInfo(b, th)
}

/** 3 · The title centred, three round "bubble" pictures, the story on a card. */
@Composable
private fun ColumnScope.KidsBackBubbles(b: BackText, th: KidsTheme) {
    KidsTitle(b, th, 22f, TextAlign.Center)
    KidsTagline(b, th, TextAlign.Center)
    if (b.pictures.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically) {
        KidsPic(b.pic(0), Modifier.size(42.px), shape = CircleShape)
        KidsPic(b.pic(1), Modifier.size(50.px), shape = CircleShape)
        KidsPic(b.pic(2), Modifier.size(42.px), shape = CircleShape)
    }
    KidsStory(b, th)
    KidsInfo(b, th)
}

// ------------------------------------------------------------------ the spine

/** A kids' spine: the look's paper and trim, its VHS tag, the title in its lettering. [mini] for the small upright ones. */
@Composable
internal fun KidsSpineBody(t: Title, th: KidsTheme, w: Dp, mini: Boolean, titleSize: Int) {
    val unit = Ui.unit * androidx.compose.ui.platform.LocalDensity.current.density
    val m = if (mini) 0.45f else 1f
    Box(Modifier.fillMaxSize().background(th.bg)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height((7 * m).px))
            // the VHS tag: a sun on the sky look, a ribbon on the storybook, a rounded label otherwise
            when (th.trim) {
                KidsTrim.Clouds -> Box(Modifier.size((30 * m).px).clip(CircleShape).background(Sunny), contentAlignment = Alignment.Center) {
                    Text("VHS", style = th.type(th.tagFg, 10f * m))
                }
                KidsTrim.Stitch -> Box(Modifier.fillMaxWidth(0.92f).clip(RibbonShape).background(StoryGold).padding(vertical = (3 * m).px),
                    contentAlignment = Alignment.Center) { Text("VHS", style = th.type(th.tagFg, 13f * m, family = Type.Oswald)) }
                else -> Box(Modifier.clip(RoundedCornerShape((5 * m).px)).background(th.tagBg)
                    .padding(horizontal = (5 * m).px, vertical = (2 * m).px)) {
                    Text("VHS", style = th.type(th.tagFg, (if (th.font == Type.Bangers) 17f else 13f) * m))
                }
            }
            Spacer(Modifier.height((7 * m).px))
            SpineTrim(th, unit, m, top = true)
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = (10 * m).px), contentAlignment = Alignment.Center) {
                KidsSpineTitle(t, th, titleSize)
            }
            SpineTrim(th, unit, m, top = false)
            if (!mini) t.art?.rating?.let { r ->
                Box(Modifier.padding(top = 8.px).heightIn(min = 24.px).widthIn(min = 24.px).border(2.px, th.ink, RoundedCornerShape(5.px))
                    .padding(horizontal = 3.px), contentAlignment = Alignment.Center) {
                    Text(r, maxLines = 1, softWrap = false, style = th.type(th.ink, 13f * (if (r.length > 2) 0.62f else 1f)))
                }
            }
            Text(yearText(t), modifier = Modifier.padding(top = (8 * m).px), style = th.type(th.ink, if (mini) 8f else 11f))
            Barcode(barcodeText(t), Modifier.padding(top = (10 * m).px, bottom = (12 * m).px).size(if (mini) 18.px else 30.px,
                if (mini) 22.px else 60.px), vertical = true)
        }
        if (th.trim == KidsTrim.Stitch) Box(Modifier.fillMaxSize().drawBehind { stitch(th.trimColor, unit, 3f * m) })
        if (th.trim == KidsTrim.Confetti) Box(Modifier.fillMaxSize().drawBehind {
            confetti(unit * 1.2f * m, listOf(Color.White, Sunny, th.tagFg), 0.1f, 0.9f, 1)
        })
    }
}

@Composable
private fun SpineTrim(th: KidsTheme, unit: Float, m: Float, top: Boolean) {
    when (th.trim) {
        KidsTrim.Rainbow -> Box(Modifier.fillMaxWidth().height((9 * m).px).drawBehind { rainbow(reversed = !top) })
        KidsTrim.Checker -> Box(Modifier.fillMaxWidth().height((6 * m).px).drawBehind { checker(unit) })
        KidsTrim.Scribble -> Box(Modifier.fillMaxWidth().height((6 * m).px).drawBehind {
            scribble(th.trimColor, unit * 1.2f * m, atTop = true, inset = 3f)
        })
        KidsTrim.Clouds -> Box(Modifier.fillMaxWidth().height((8 * m).px).drawBehind { clouds(unit * m, atTop = !top, r = 4f) })
        else -> Unit
    }
}

/** The title down a kids' spine: its TMDB logo when there is one, otherwise the name in the look's lettering. */
@Composable
private fun KidsSpineTitle(t: Title, th: KidsTheme, titleSize: Int) {
    if (t.art?.titleLogo != null) { SpineTitle(t, 0.84f, titleSize, 2); return }
    val shadow = if (th.ink == Color.White) Shadow(Color(0x99000000), Offset(0f, 2f), 2f) else null
    FitText(coverText(t).uppercase(), modifier = Modifier.sideways(), maxLines = 2, minScale = 0.4f,
        textAlign = TextAlign.Center, align = Alignment.Center,
        style = th.type(th.ink, titleSize * 0.95f).copy(shadow = shadow))
}
