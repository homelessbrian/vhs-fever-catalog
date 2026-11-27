package org.vhsfever.tv

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import coil.compose.AsyncImage

/*
 * The printed bands around a TMDB poster on a VHS-sized sleeve. Posters (2:3) are wider than a VHS front, so the
 * whole poster sits full width and the room left above and below is printed like a real rental sleeve.
 * There are 14 looks; each movie always gets the same one (picked from its key), so the shelf has variety
 * but a box never changes from one visit to the next. All colours come from the movie's own poster.
 */

/** The sleeve looks, in the order they were designed. */
enum class SleeveStyle { Classic, StudioHeader, ColorFade, Spectrum, Framed, BigStudio, CornerSash, SynthGrid,
    GoldFoil, RentalStickers, WhiteLabel, Halftone, Neon, DiagonalSplit }

fun sleeveStyle(t: Title): SleeveStyle =
    SleeveStyle.values().let { it[Math.floorMod(t.key.hashCode() * 131 + 29, it.size)] }

/** Only for the screen-picture tests, where posters can't be downloaded: a stand-in picture. */
val LocalPosterStandIn = compositionLocalOf<Painter?> { null }

private val Cream = Ink.CoverCream
private val Gold = Color(0xFFD9B25A)
private val NeonPink = Color(0xFFFF3FB4)
private val NeonBlue = Color(0xFF45E3FF)
private val Paper = Color(0xFFF1ECE0)
private val PaperInk = Color(0xFF121114)

/** Everything one sleeve needs, worked out once. */
private class Sleeve(val t: Title, val c: BoxColors, val s: Float) {
    val art = t.art
    val year = yearText(t)
    val info = listOfNotNull(year.takeIf { it.isNotBlank() }, art?.runtime?.let { "$it MIN" }).joinToString(" · ")
    val rating = art?.rating?.takeIf { it.isNotBlank() }
    val studio = (art?.studio ?: "HOME VIDEO").uppercase()
    fun mono(color: Color = Cream, size: Float = 6.2f) = TextStyle(fontFamily = Type.Mono, fontSize = (size * s).fs,
        lineHeight = (size * s).fs, letterSpacing = (0.7f * s).fs, color = color)
    fun bold(color: Color = Cream, size: Float = 7.5f, family: FontFamily = Type.Oswald, spacing: Float = 0.5f) =
        TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = (size * s).fs, lineHeight = (size * s).fs,
            letterSpacing = (spacing * s).fs, color = color)
}

/** Cream on dark colours, near-black on light ones. */
private fun inkOn(bg: Color) = if (bg.luminance() > 0.55f) PaperInk else Cream

@Composable
internal fun PosterSleeve(t: Title, c: BoxColors, scale: Float, dvd: Boolean, style: SleeveStyle = sleeveStyle(t)) {
    // A DVD case is wider than a poster, so there it just fills the case.
    if (dvd) { PosterPicture(t, Modifier.fillMaxSize(), ContentScale.Crop); return }
    val k = Sleeve(t, c, scale)
    when (style) {
        SleeveStyle.Classic -> Classic(k)
        SleeveStyle.StudioHeader -> StudioHeader(k)
        SleeveStyle.ColorFade -> ColorFade(k)
        SleeveStyle.Spectrum -> Spectrum(k)
        SleeveStyle.Framed -> Framed(k)
        SleeveStyle.BigStudio -> BigStudio(k)
        SleeveStyle.CornerSash -> CornerSash(k)
        SleeveStyle.SynthGrid -> SynthGrid(k)
        SleeveStyle.GoldFoil -> GoldFoil(k)
        SleeveStyle.RentalStickers -> RentalStickers(k)
        SleeveStyle.WhiteLabel -> WhiteLabel(k)
        SleeveStyle.Halftone -> Halftone(k)
        SleeveStyle.Neon -> Neon(k)
        SleeveStyle.DiagonalSplit -> DiagonalSplit(k)
    }
}

// ---- shared pieces ----

@Composable
private fun PosterPicture(t: Title, modifier: Modifier, scale: ContentScale = ContentScale.Crop) {
    val standIn = LocalPosterStandIn.current
    if (standIn != null) Image(standIn, contentDescription = t.name, contentScale = scale, modifier = modifier)
    else AsyncImage(model = t.poster, contentDescription = t.name, contentScale = scale, modifier = modifier)
}

/** The poster at full width, with anything [over] it (stickers, a sash, fades). */
@Composable
private fun ColumnScope.Poster(k: Sleeve, over: @Composable BoxScope.() -> Unit = {}) {
    Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clipToBounds()) {
        PosterPicture(k.t, Modifier.fillMaxSize())
        over()
    }
}

/** A band filling its share of the room left by the poster. */
@Composable
private fun ColumnScope.Band(k: Sleeve, share: Float, modifier: Modifier = Modifier, gap: Float = 5f,
                             arrange: Arrangement.Horizontal = Arrangement.spacedBy((gap * k.s).px),
                             content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().weight(share).padding(horizontal = (5 * k.s).px),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = arrange, content = content)
}

@Composable
private fun VhsTag(k: Sleeve, bg: Color = Cream, fg: Color = PaperInk) {
    Text("VHS", style = k.bold(fg), modifier = Modifier.background(bg).padding(horizontal = (3 * k.s).px))
}

@Composable
private fun RatingBox(k: Sleeve, color: Color = Cream) {
    k.rating?.let {
        Text(it, style = k.bold(color, 7f), maxLines = 1,
            modifier = Modifier.border((0.8f * k.s).px, color).padding(horizontal = (2.5f * k.s).px))
    }
}

@Composable
private fun RowScope.Info(k: Sleeve, color: Color = Cream, text: String = k.info) {
    Text(text, style = k.mono(color), maxLines = 1, overflow = TextOverflow.Clip, modifier = Modifier.weight(1f))
}

/** The studio: its logo printed in one colour, or its name when TMDB has no logo. */
@Composable
private fun Studio(k: Sleeve, color: Color, style: TextStyle = k.mono(color), logoHeight: Float = 0.62f,
                   logoWidth: Float = 0.42f, modifier: Modifier = Modifier) {
    val logo = k.art?.studioLogo
    if (logo != null && LocalPosterStandIn.current == null) AsyncImage(model = logo, contentDescription = k.art?.studio,
        colorFilter = ColorFilter.tint(color), contentScale = ContentScale.Fit,
        modifier = modifier.fillMaxHeight(logoHeight).fillMaxWidth(logoWidth))
    else Text(k.studio, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

@Composable
private fun RowScope.Push() = Spacer(Modifier.weight(1f))

/** Thin stripes across the box, top to bottom in the order given. */
@Composable
private fun Stripes(k: Sleeve, colors: List<Color>) {
    Column(Modifier.fillMaxWidth().padding(vertical = (0.6f * k.s).px), verticalArrangement = Arrangement.spacedBy((0.5f * k.s).px)) {
        for (c in colors) Box(Modifier.fillMaxWidth().height((0.9f * k.s).px).background(c))
    }
}

// ---- the looks ----

/** 1 · The original: VHS and studio on the poster's dark colour, rating and year on its main colour. */
@Composable
private fun Classic(k: Sleeve) = Column(Modifier.fillMaxSize()) {
    Band(k, 0.36f, Modifier.background(k.c.c2), arrange = Arrangement.SpaceBetween) {
        VhsTag(k, fg = k.c.c2)
        Studio(k, Cream)
    }
    Poster(k)
    Box(Modifier.fillMaxWidth().height((1.5f * k.s).px).background(k.c.c3))
    Band(k, 0.64f, Modifier.background(k.c.c1)) {
        RatingBox(k); Info(k); Text("HI-FI", style = k.mono(k.c.c3))
    }
}

/** 2 · Black label bars with a two-colour rule, like the big studios' 80s sleeves. */
@Composable
private fun StudioHeader(k: Sleeve) = Column(Modifier.fillMaxSize().background(Color(0xFF0B0B0C))) {
    Band(k, 0.34f, arrange = Arrangement.SpaceBetween) {
        VhsTag(k)
        Studio(k, Cream, k.bold(Cream, 6.5f, spacing = 1.2f).copy(fontWeight = FontWeight.Medium))
    }
    Box(Modifier.fillMaxWidth().height((0.8f * k.s).px).background(k.c.c1))
    Box(Modifier.fillMaxWidth().height((0.8f * k.s).px).background(k.c.c3))
    Poster(k)
    Box(Modifier.fillMaxWidth().height((1f * k.s).px).background(k.c.c1))
    Band(k, 0.66f) {
        RatingBox(k); Info(k)
        Text("HI-FI", style = k.bold(Cream, 6f), modifier = Modifier.background(k.c.c1).padding(horizontal = (2 * k.s).px))
    }
}

/** 3 · No hard edges: the poster melts into its own top and bottom colours. */
@Composable
private fun ColorFade(k: Sleeve) {
    val top = k.c.top ?: k.c.c1
    val bottom = k.c.bottom ?: k.c.c2
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to top, 0.5f to lerp(top, bottom, 0.5f), 1f to bottom))) {
        Band(k, 0.36f, arrange = Arrangement.SpaceBetween) {
            VhsTag(k, bg = inkOn(top), fg = top)
            Studio(k, inkOn(top), k.bold(inkOn(top), 6f, spacing = 1.2f).copy(fontWeight = FontWeight.Medium))
        }
        Poster(k) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(0.1f).align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(top, Color.Transparent))))
            Box(Modifier.fillMaxWidth().fillMaxHeight(0.16f).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, bottom))))
        }
        Band(k, 0.64f) {
            RatingBox(k, inkOn(bottom)); Info(k, inkOn(bottom))
            Text("HI-FI", style = k.mono(inkOn(bottom)))
        }
    }
}

/** 4 · Thin stripes in the poster's colours above and below. */
@Composable
private fun Spectrum(k: Sleeve) {
    val stripes = listOf(k.c.c1, lerp(k.c.c1, k.c.c3, 0.5f), k.c.c3)
    Column(Modifier.fillMaxSize().background(Color(0xFF0D0C0E))) {
        Band(k, 0.27f, arrange = Arrangement.SpaceBetween) { VhsTag(k); Studio(k, Cream) }
        Stripes(k, stripes)
        Poster(k)
        Stripes(k, stripes.reversed())
        Band(k, 0.5f) { RatingBox(k); Info(k); Text("HI-FI", style = k.mono(k.c.c3)) }
    }
}

/** 5 · The poster in a cream frame on a dark sleeve, like the smaller labels did. */
@Composable
private fun Framed(k: Sleeve) = Column(Modifier.fillMaxSize().background(k.c.c2)) {
    val side = (4 * k.s).px
    Band(k, 0.34f, arrange = Arrangement.SpaceBetween) {
        VhsTag(k, fg = k.c.c2)
        Text(listOfNotNull(k.rating, k.year.takeIf { it.isNotBlank() }).joinToString(" · "), style = k.mono(), maxLines = 1)
    }
    Box(Modifier.padding(horizontal = side).fillMaxWidth().border((1.1f * k.s).px, Cream).padding((1.1f * k.s).px)) {
        Column { Poster(k) }
    }
    Band(k, 0.66f, arrange = Arrangement.SpaceBetween) {
        Studio(k, Cream, k.bold(Cream, 6f, spacing = 0.9f).copy(fontWeight = FontWeight.Medium))
        Text(listOfNotNull(k.art?.runtime?.let { "$it MIN" }, "HI-FI").joinToString(" · "), style = k.mono(), maxLines = 1)
    }
}

/** 6 · The studio's name big across a solid top band in the poster's main colour. */
@Composable
private fun BigStudio(k: Sleeve) = Column(Modifier.fillMaxSize().background(k.c.c2)) {
    Band(k, 0.46f, Modifier.background(k.c.c1), arrange = Arrangement.Center) {
        Studio(k, Cream, k.bold(Cream, 8.5f, Type.Anton, 1.6f).copy(fontWeight = FontWeight.Normal), logoHeight = 0.8f, logoWidth = 0.7f)
    }
    Poster(k)
    Band(k, 0.54f) { VhsTag(k, fg = k.c.c2); RatingBox(k); Info(k) }
}

/** 7 · A "VHS" ribbon across the top corner of the art, slim dark bands. */
@Composable
private fun CornerSash(k: Sleeve) = Column(Modifier.fillMaxSize().background(k.c.c2)) {
    Band(k, 0.3f, arrange = Arrangement.End) { Studio(k, Cream) }
    Poster(k) {
        Box(Modifier.offset((-13 * k.s).px, (6 * k.s).px).size((48 * k.s).px, (7 * k.s).px).rotate(-45f)
            .background(k.c.c1), contentAlignment = Alignment.Center) {
            Text("VHS", style = k.bold(Cream, 5.2f, spacing = 1f))
        }
    }
    Box(Modifier.fillMaxWidth().height((0.7f * k.s).px).background(k.c.c3))
    Band(k, 0.7f) { RatingBox(k); Info(k); Text("HI-FI", style = k.mono(k.c.c3)) }
}

/** 8 · Glowing purple grid bands, late-80s sci-fi and music video. */
@Composable
private fun SynthGrid(k: Sleeve) {
    val grid = Modifier.background(Brush.verticalGradient(listOf(Color(0xFF1B0B2E), Color(0xFF3A0F4A)))).drawBehind {
        val line = Color(0x8CFF3CC8); val w = maxOf(1f, 0.3f * k.s * Ui.unit * density)
        val dx = 5.5f * k.s * Ui.unit * density; val dy = 2.7f * k.s * Ui.unit * density
        var x = 0f; while (x < size.width) { drawLine(line, Offset(x, 0f), Offset(x, size.height), w); x += dx }
        var y = 0f; while (y < size.height) { drawLine(line, Offset(0f, y), Offset(size.width, y), w); y += dy }
    }
    val cyan = Color(0xFF59E6FF)
    Column(Modifier.fillMaxSize().background(Color(0xFF1B0B2E))) {
        Band(k, 0.36f, grid, arrange = Arrangement.SpaceBetween) {
            Text("VHS", style = k.bold(cyan, 6.5f, Type.Orbitron, 1f).copy(fontWeight = FontWeight.ExtraBold,
                shadow = Shadow(cyan, Offset.Zero, 6f)))
            Studio(k, Color(0xFFFFD6F0))
        }
        Poster(k)
        Band(k, 0.64f, grid) { RatingBox(k, cyan); Info(k, Color(0xFFFFD6F0)) }
    }
}

/** 9 · Black with double gold rules and italic serif, like the "classics" collection sleeves. */
@Composable
private fun GoldFoil(k: Sleeve) {
    fun Modifier.rules(atTop: Boolean) = drawBehind {
        val w = maxOf(1f, 0.5f * k.s * Ui.unit * density); val gap = 1.2f * k.s * Ui.unit * density
        val y0 = if (atTop) w / 2 else size.height - w / 2
        val y1 = if (atTop) y0 + gap else y0 - gap
        drawLine(Gold, Offset(0f, y0), Offset(size.width, y0), w)
        drawLine(Gold, Offset(0f, y1), Offset(size.width, y1), w)
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF0A0A0A))) {
        Band(k, 0.38f, Modifier.rules(atTop = false), arrange = Arrangement.Center) {
            Studio(k, Gold, TextStyle(fontFamily = Type.Playfair, fontWeight = FontWeight.Black,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontSize = (6.2f * k.s).fs, lineHeight = (6.2f * k.s).fs,
                color = Gold), logoWidth = 0.6f)
        }
        Poster(k)
        Band(k, 0.62f, Modifier.rules(atTop = true), arrange = Arrangement.spacedBy((5 * k.s).px, Alignment.CenterHorizontally)) {
            RatingBox(k, Gold)
            Text(listOfNotNull(k.info.takeIf { it.isNotBlank() }, "VHS").joinToString(" · "), style = k.mono(Gold), maxLines = 1)
        }
    }
}

private val PRICES = listOf("$1.99", "$2.49", "$2.99", "$3.49", "99¢")

/** 10 · Plain bands with store stickers slapped on: a rental price dot and BE KIND REWIND. */
@Composable
private fun RentalStickers(k: Sleeve) = Column(Modifier.fillMaxSize().background(k.c.c2)) {
    Band(k, 0.36f, arrange = Arrangement.SpaceBetween) { VhsTag(k, fg = k.c.c2); Studio(k, Cream) }
    Poster(k) {
        Column(Modifier.align(Alignment.TopEnd).offset((-3 * k.s).px, (21 * k.s).px).rotate(12f).size((17.5f * k.s).px)
            .clip(CircleShape).background(Color(0xFFFFD23A)), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("RENT", style = k.bold(Color(0xFFB3151A), 3f, spacing = 0.3f))
            Text(PRICES[Math.floorMod(k.t.key.hashCode(), PRICES.size)], style = k.bold(Color(0xFFB3151A), 5.6f, spacing = 0f))
        }
        Text("BE KIND REWIND", style = k.bold(Color(0xFF0A4DBB), 3.4f, spacing = 0.3f), maxLines = 1,
            modifier = Modifier.align(Alignment.BottomStart).offset((3 * k.s).px, (-4 * k.s).px).rotate(-4f)
                .background(Color.White).border((0.6f * k.s).px, Color(0xFF0A4DBB)).padding(horizontal = (2 * k.s).px, vertical = (0.8f * k.s).px))
    }
    Band(k, 0.64f) { RatingBox(k); Info(k) }
}

/** 11 · Cream bands with dark print and a coloured line, like budget and TV-movie labels. */
@Composable
private fun WhiteLabel(k: Sleeve) = Column(Modifier.fillMaxSize().background(Paper)) {
    Band(k, 0.36f, arrange = Arrangement.SpaceBetween) { VhsTag(k, bg = PaperInk, fg = Paper); Studio(k, PaperInk) }
    Poster(k)
    Box(Modifier.fillMaxWidth().height((1.2f * k.s).px).background(k.c.c1))
    Band(k, 0.64f) { RatingBox(k, PaperInk); Info(k, PaperInk); Text("HI-FI", style = k.mono(k.c.c1)) }
}

/** 12 · Poster-colour bands with a printed dot texture, like cheap 80s print runs. */
@Composable
private fun Halftone(k: Sleeve) {
    val dots = Modifier.background(k.c.c1).drawBehind {
        val step = 2.1f * k.s * Ui.unit * density; val r = 0.42f * k.s * Ui.unit * density
        val dot = Color.Black.alpha(0.3f)
        var y = step / 2; var row = 0
        while (y < size.height) {
            var x = if (row % 2 == 0) step / 2 else step
            while (x < size.width) { drawCircle(dot, r, Offset(x, y)); x += step }
            y += step; row++
        }
    }
    Column(Modifier.fillMaxSize().background(k.c.c1)) {
        Band(k, 0.36f, dots, arrange = Arrangement.SpaceBetween) {
            VhsTag(k, bg = k.c.c2, fg = Cream)
            Studio(k, Cream, k.bold(Cream, 6.5f, spacing = 1f))
        }
        Poster(k)
        Band(k, 0.64f, dots) { RatingBox(k); Info(k) }
    }
}

/** 13 · Black bands edged with pink and blue neon, and a glowing VHS sign. */
@Composable
private fun Neon(k: Sleeve) {
    fun Modifier.tube(color: Color, atTop: Boolean) = drawBehind {
        val u = k.s * Ui.unit * density
        val y = if (atTop) 0f else size.height
        for ((w, a) in listOf(3.2f to 0.12f, 2f to 0.25f, 0.7f to 1f)) drawLine(color.alpha(a), Offset(0f, y), Offset(size.width, y), maxOf(1f, w * u))
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF07060C))) {
        Band(k, 0.36f, Modifier.tube(NeonPink, atTop = false), arrange = Arrangement.SpaceBetween) {
            Text("VHS", style = k.bold(Color(0xFFFFD6F0), 7f, spacing = 1f).copy(shadow = Shadow(NeonPink, Offset.Zero, 8f)))
            Studio(k, Color(0xFFBFF4FF))
        }
        Poster(k)
        Band(k, 0.64f, Modifier.tube(NeonBlue, atTop = true)) { RatingBox(k, Color(0xFFFFD6F0)); Info(k, Color(0xFFBFF4FF)) }
    }
}

/** 14 · Two poster colours split on a slant, flipped between top and bottom. */
@Composable
private fun DiagonalSplit(k: Sleeve) {
    fun Modifier.split(first: Color, second: Color, at: Float) = drawBehind {
        drawRect(second)
        val lean = size.height * 0.47f
        val x = size.width * at
        drawPath(Path().apply {
            moveTo(0f, 0f); lineTo(x + lean / 2, 0f); lineTo(x - lean / 2, size.height); lineTo(0f, size.height); close()
        }, first)
    }
    Column(Modifier.fillMaxSize().background(k.c.c2)) {
        Band(k, 0.36f, Modifier.split(k.c.c1, k.c.c2, 0.46f), arrange = Arrangement.SpaceBetween) {
            VhsTag(k, fg = k.c.c1)
            Studio(k, Cream, k.bold(Cream, 6.5f, spacing = 1f))
        }
        Poster(k)
        Band(k, 0.64f, Modifier.split(k.c.c2, k.c.c1, 0.54f)) { RatingBox(k); Info(k); Text("HI-FI", style = k.mono()) }
    }
}
