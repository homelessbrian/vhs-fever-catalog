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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.tv.material3.Text
import coil.compose.AsyncImage

/*
 * The printed bands around a TMDB poster on a VHS-sized sleeve. Posters (2:3) are wider than a VHS front, so the
 * whole poster sits full width and the room left above and below is printed like a real rental sleeve.
 * There are 14 grown-up looks and 6 kids' ones; each movie always gets the same one (picked from its key, or its series so sequels
 * match), so the shelf has variety but a box never changes from one visit to the next. All colours come from the movie's own poster.
 */

/** The sleeve looks, in the order they were designed: 14 grown-up ones, then 6 for kids' and family movies. */
enum class SleeveStyle { Classic, StudioHeader, ColorFade, Spectrum, Framed, BigStudio, CornerSash, SynthGrid,
    GoldFoil, RentalStickers, WhiteLabel, Halftone, Neon, DiagonalSplit,
    Crayon, Rainbow, Storybook, Confetti, Starburst, CloudySky;

    val kids: Boolean get() = this in KIDS_LOOKS
}

val KIDS_LOOKS = listOf(SleeveStyle.Crayon, SleeveStyle.Rainbow, SleeveStyle.Storybook, SleeveStyle.Confetti,
    SleeveStyle.Starburst, SleeveStyle.CloudySky)
val GROWN_UP_LOOKS = SleeveStyle.values().filter { it !in KIDS_LOOKS }

/** Kids' and family boxes pick from the kids' looks, everything else from the grown-up ones. */
fun sleeveStyle(t: Title): SleeveStyle =
    (if (t.kidsLook) KIDS_LOOKS else GROWN_UP_LOOKS).let { it[Math.floorMod(lookKey(t).hashCode() * 131 + 29, it.size)] }

/**
 * What picks a made-up box's look: the TMDB collection when the movie has one, so a movie and all its
 * sequels share the same sleeve, back layout and rental sticker; otherwise the movie itself.
 */
fun lookKey(t: Title): String = t.art?.collection?.let { "collection:$it" } ?: t.key

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
    /** Pixels per mockup unit, for drawing. */
    fun u(d: androidx.compose.ui.unit.Density) = s * Ui.unit * d.density
    val art = t.art
    val year = yearText(t)
    /** Year and running time; [short] is the year alone, for bands that also print HI-FI. */
    val info = listOfNotNull(year.takeIf { it.isNotBlank() }, art?.runtime?.let { "$it MIN" }).joinToString(" · ")
    val short = year
    val rating = art?.rating?.takeIf { it.isNotBlank() }
    val studio = (art?.studio ?: "HOME VIDEO").uppercase()
    fun mono(color: Color = Cream, size: Float = 5.6f) = TextStyle(fontFamily = Type.Mono, fontSize = (size * s).fs,
        lineHeight = (size * s).fs, letterSpacing = (0.5f * s).fs, color = color, lineHeightStyle = Tight, platformStyle = NoPadding)
    fun bold(color: Color = Cream, size: Float = 6f, family: FontFamily = Type.Oswald, spacing: Float = 0.5f) =
        TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = (size * s).fs, lineHeight = (size * s).fs,
            letterSpacing = (spacing * s).fs, color = color, lineHeightStyle = Tight, platformStyle = NoPadding)
}

/** Text boxes only as tall as the letters, so they sit inside the thin bands. */
private val Tight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)
private val NoPadding = PlatformTextStyle(includeFontPadding = false)

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
        else -> KidsFront(k, style)
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
    Label("VHS", k.bold(fg), k, Modifier.background(bg))
}

/**
 * A printed tag (VHS, the rating, HI-FI): a fixed-height block with the word centred in it. Fonts carry extra
 * room above and below the letters, so sizing the block from the text made it taller than the thin bands.
 */
@Composable
private fun Label(text: String, style: TextStyle, k: Sleeve, modifier: Modifier, pad: Float = 2.5f, room: Float = 1.2f) {
    Box(modifier.height((style.fontSize.value / Ui.unit + room * k.s).px).padding(horizontal = (pad * k.s).px),
        contentAlignment = Alignment.Center) {
        // measured at its natural height and centred on the block, letting the font's spare room spill over
        Text(text, style = style, maxLines = 1, softWrap = false, modifier = Modifier.wrapContentHeight(unbounded = true))
    }
}

@Composable
private fun RatingBox(k: Sleeve, color: Color = Cream) {
    k.rating?.let {
        Label(it, k.bold(color, 5.4f), k, Modifier.border((0.7f * k.s).px, color), pad = 2f, room = 2.4f)
    }
}

@Composable
private fun RowScope.Info(k: Sleeve, color: Color = Cream, text: String = k.info, align: Alignment = Alignment.CenterStart) {
    FitText(text, k.mono(color), Modifier.weight(1f), minScale = 0.55f, align = align)
}

/** The studio: its logo printed in one colour, or its name when TMDB has no logo. */
@Composable
private fun RowScope.Studio(k: Sleeve, color: Color, style: TextStyle = k.mono(color), logoHeight: Float = 0.62f,
                            logoWidth: Float = 0.42f, modifier: Modifier = Modifier) {
    val logo = k.art?.studioLogo
    if (logo != null && LocalPosterStandIn.current == null) AsyncImage(model = logo, contentDescription = k.art?.studio,
        colorFilter = ColorFilter.tint(color), contentScale = ContentScale.Fit,
        modifier = modifier.fillMaxHeight(logoHeight).fillMaxWidth(logoWidth))
    else FitText(k.studio, style, modifier.weight(1f, fill = false), minScale = 0.5f, align = Alignment.Center)
}

@Composable
private fun RowScope.Push() = Spacer(Modifier.weight(1f))

/** Thin stripes across the box, top to bottom in the order given. */
@Composable
private fun Stripes(k: Sleeve, colors: List<Color>) {
    Column(Modifier.fillMaxWidth().padding(vertical = (0.4f * k.s).px), verticalArrangement = Arrangement.spacedBy((0.4f * k.s).px)) {
        for (c in colors) Box(Modifier.fillMaxWidth().height((0.7f * k.s).px).background(c))
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
        RatingBox(k); Info(k, text = k.short); Text("HI-FI", style = k.mono(k.c.c3))
    }
}

/** 2 · Black label bars with a two-colour rule, like the big studios' 80s sleeves. */
@Composable
private fun StudioHeader(k: Sleeve) = Column(Modifier.fillMaxSize().background(Color(0xFF0B0B0C))) {
    Band(k, 0.38f, arrange = Arrangement.SpaceBetween) {
        VhsTag(k)
        Studio(k, Cream, k.bold(Cream, 5.6f, spacing = 1f).copy(fontWeight = FontWeight.Medium))
    }
    Box(Modifier.fillMaxWidth().height((0.8f * k.s).px).background(k.c.c1))
    Box(Modifier.fillMaxWidth().height((0.8f * k.s).px).background(k.c.c3))
    Poster(k)
    Box(Modifier.fillMaxWidth().height((1f * k.s).px).background(k.c.c1))
    Band(k, 0.62f) {
        RatingBox(k); Info(k, text = k.short)
        Label("HI-FI", k.bold(Cream, 5.2f), k, Modifier.background(k.c.c1), pad = 2f)
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
            Studio(k, inkOn(top), k.bold(inkOn(top), 5.6f, spacing = 1f).copy(fontWeight = FontWeight.Medium))
        }
        Poster(k) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(0.1f).align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(top, Color.Transparent))))
            Box(Modifier.fillMaxWidth().fillMaxHeight(0.16f).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, bottom))))
        }
        Band(k, 0.64f) {
            RatingBox(k, inkOn(bottom)); Info(k, inkOn(bottom), k.short)
            Text("HI-FI", style = k.mono(inkOn(bottom)))
        }
    }
}

/** 4 · Thin stripes in the poster's colours above and below. */
@Composable
private fun Spectrum(k: Sleeve) {
    val stripes = listOf(k.c.c1, lerp(k.c.c1, k.c.c3, 0.5f), k.c.c3)
    Column(Modifier.fillMaxSize().background(Color(0xFF0D0C0E))) {
        Band(k, 0.42f, arrange = Arrangement.SpaceBetween) { VhsTag(k); Studio(k, Cream) }
        Stripes(k, stripes)
        Poster(k)
        Stripes(k, stripes.reversed())
        Band(k, 0.58f) { RatingBox(k); Info(k, text = k.short); Text("HI-FI", style = k.mono(k.c.c3)) }
    }
}

/** 5 · The poster in a cream frame on a dark sleeve, like the smaller labels did. */
@Composable
private fun Framed(k: Sleeve) = Column(Modifier.fillMaxSize().background(k.c.c2)) {
    val side = (4 * k.s).px
    Band(k, 0.34f, arrange = Arrangement.SpaceBetween) {
        VhsTag(k, fg = k.c.c2)
        Info(k, text = listOfNotNull(k.rating, k.year.takeIf { it.isNotBlank() }).joinToString(" · "), align = Alignment.CenterEnd)
    }
    Box(Modifier.padding(horizontal = side).fillMaxWidth().border((1.1f * k.s).px, Cream).padding((1.1f * k.s).px)) {
        Column { Poster(k) }
    }
    Band(k, 0.66f, arrange = Arrangement.SpaceBetween) {
        Studio(k, Cream, k.bold(Cream, 5.4f, spacing = 0.8f).copy(fontWeight = FontWeight.Medium))
        Text(k.art?.runtime?.let { "$it MIN" } ?: "HI-FI", style = k.mono(), maxLines = 1,
            modifier = Modifier.padding(start = (3 * k.s).px))
    }
}

/** 6 · The studio's name big across a solid top band in the poster's main colour. */
@Composable
private fun BigStudio(k: Sleeve) = Column(Modifier.fillMaxSize().background(k.c.c2)) {
    Band(k, 0.46f, Modifier.background(k.c.c1), arrange = Arrangement.Center) {
        Studio(k, Cream, k.bold(Cream, 8.5f, Type.Anton, 1.6f).copy(fontWeight = FontWeight.Normal), logoHeight = 0.8f, logoWidth = 0.7f)
    }
    Poster(k)
    Band(k, 0.54f) { VhsTag(k, fg = k.c.c2); RatingBox(k); Info(k, text = k.short) }
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
    Band(k, 0.7f) { RatingBox(k); Info(k, text = k.short); Text("HI-FI", style = k.mono(k.c.c3)) }
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
            Text("VHS", style = k.bold(cyan, 5.6f, Type.Orbitron, 0.8f).copy(fontWeight = FontWeight.ExtraBold,
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
            FitText(listOfNotNull(k.info.takeIf { it.isNotBlank() }, "VHS").joinToString(" · "), k.mono(Gold),
                Modifier.weight(1f, fill = false), minScale = 0.55f)
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
            Text(PRICES[Math.floorMod(lookKey(k.t).hashCode(), PRICES.size)], style = k.bold(Color(0xFFB3151A), 5.6f, spacing = 0f))
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
    Band(k, 0.64f) { RatingBox(k, PaperInk); Info(k, PaperInk, k.short); Text("HI-FI", style = k.mono(k.c.c1)) }
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
            Studio(k, Cream, k.bold(Cream, 5.6f, spacing = 0.8f))
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
            Text("VHS", style = k.bold(Color(0xFFFFD6F0), 6.2f, spacing = 1f).copy(shadow = Shadow(NeonPink, Offset.Zero, 8f)))
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
            Studio(k, Cream, k.bold(Cream, 5.6f, spacing = 0.8f))
        }
        Poster(k)
        Band(k, 0.64f, Modifier.split(k.c.c2, k.c.c1, 0.54f)) { RatingBox(k); Info(k, text = k.short); Text("HI-FI", style = k.mono()) }
    }
}

// ---- the kids' looks (their backs and spines are in Kids.kt) ----

@Composable
private fun KidsFront(k: Sleeve, style: SleeveStyle) {
    val th = kidsTheme(style, k.c)
    when (style) {
        SleeveStyle.Crayon -> Crayon(k, th)
        SleeveStyle.Rainbow -> RainbowLook(k, th)
        SleeveStyle.Storybook -> Storybook(k, th)
        SleeveStyle.Confetti -> ConfettiPop(k, th)
        SleeveStyle.Starburst -> Starburst(k, th)
        else -> CloudySky(k, th)
    }
}

/** Kids' lettering on a sleeve. */
private fun Sleeve.kid(th: KidsTheme, color: Color, size: Float, family: FontFamily = th.font) =
    th.type(color, size, s, family).copy(lineHeight = (size * s).fs, lineHeightStyle = Tight, platformStyle = NoPadding)

@Composable
private fun KidTag(k: Sleeve, th: KidsTheme, size: Float = 5.2f) =
    Label("VHS", k.kid(th, th.tagFg, size), k, Modifier.clip(RoundedCornerShape((1.6f * k.s).px)).background(th.tagBg), pad = 2.6f)

@Composable
private fun KidRating(k: Sleeve, th: KidsTheme, color: Color) {
    k.rating?.let {
        Label(it, k.kid(th, color, 5f), k, Modifier.border((0.7f * k.s).px, color, RoundedCornerShape((1.5f * k.s).px)), pad = 1.8f, room = 2.4f)
    }
}

/** 15 · Sunny yellow with a scribbled crayon edge and chunky round letters. */
@Composable
private fun Crayon(k: Sleeve, th: KidsTheme) = Column(Modifier.fillMaxSize().background(Sunny)) {
    Band(k, 0.36f, Modifier.clipToBounds().drawBehind { scribble(th.trimColor, k.u(this), atTop = false) }, arrange = Arrangement.SpaceBetween) {
        KidTag(k, th); Studio(k, th.ink, k.kid(th, th.ink, 4.6f))
    }
    Poster(k)
    Band(k, 0.64f, Modifier.clipToBounds().drawBehind { scribble(th.ink, k.u(this), atTop = true) }) {
        KidRating(k, th, th.ink); Text(k.short, style = k.kid(th, th.ink, 5f)); Push()
        Text("HI-FI", style = k.kid(th, th.accent.let(::deepen), 4.6f))
    }
}

/** 16 · White sleeve with a rainbow hugging the picture, top and bottom. */
@Composable
private fun RainbowLook(k: Sleeve, th: KidsTheme) = Column(Modifier.fillMaxSize().background(Snow)) {
    Band(k, 0.36f, arrange = Arrangement.SpaceBetween) { KidTag(k, th, 4.6f); Studio(k, th.ink, k.kid(th, th.ink, 4.4f)) }
    Box(Modifier.fillMaxWidth().height((4.2f * k.s).px).drawBehind { rainbow(reversed = false) })
    Poster(k)
    Box(Modifier.fillMaxWidth().height((4.2f * k.s).px).drawBehind { rainbow(reversed = true) })
    Band(k, 0.64f) { KidRating(k, th, RainbowColors[0]); Info(k, th.ink) }
}

/** 17 · Cream paper with a stitched border and a gold ribbon, like a bedtime-story collection. */
@Composable
private fun Storybook(k: Sleeve, th: KidsTheme) = Column(Modifier.fillMaxSize().background(StoryCream)) {
    val serif = k.kid(th, StoryBrown, 5.6f, Type.Playfair)
    Band(k, 0.4f, arrange = Arrangement.Center) {
        FitText("A Family Favorite", serif, Modifier.weight(1f), minScale = 0.6f, align = Alignment.Center)
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = (3.4f * k.s).px).drawWithContent {
        drawContent(); val u = k.u(this)
        // the stitching sits just outside the picture
        drawRect(Color(0xFFA5753C), Offset(-u, -u), androidx.compose.ui.geometry.Size(size.width + 2 * u, size.height + 2 * u),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = maxOf(1f, 0.55f * u),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(2f * u, 1.3f * u))))
    }) {
        Column {
            Poster(k) {
                Box(Modifier.align(Alignment.TopEnd).offset(0.px, (9 * k.s).px).clip(RibbonShape).background(StoryGold)
                    .padding(start = (3.6f * k.s).px, end = (2.6f * k.s).px)) {
                    Label("VHS", k.bold(Color(0xFF4A2A0A), 4f), k, Modifier, pad = 0f)
                }
            }
        }
    }
    Band(k, 0.6f, arrange = Arrangement.Center) {
        KidRating(k, th, StoryBrown)
        val words = listOfNotNull(k.year.takeIf { it.isNotBlank() }, k.art?.studio ?: "Home Video").joinToString(" · ")
        FitText(words, serif.copy(fontSize = (4.8f * k.s).fs), Modifier.weight(1f, fill = false), minScale = 0.55f, align = Alignment.Center)
    }
}

/** 18 · Bright poster-colour bands with 90s confetti squiggles and comic letters. */
@Composable
private fun ConfettiPop(k: Sleeve, th: KidsTheme) {
    val bits = listOf(Color.White, Sunny, th.tagFg)
    val pop = Shadow(deepen(th.tagFg), Offset(0.6f * k.s * Ui.unit, 0.6f * k.s * Ui.unit), 0f)
    Column(Modifier.fillMaxSize().background(th.flat)) {
        Band(k, 0.36f, Modifier.drawBehind { confetti(k.u(this), bits, 0.3f, 0.62f, 0) }, arrange = Arrangement.SpaceBetween) {
            Text("VHS", style = k.kid(th, th.ink, 6.4f).copy(shadow = pop))
            Studio(k, th.ink, k.kid(th, th.ink, 5.6f))
        }
        Poster(k)
        Band(k, 0.64f, Modifier.drawBehind { confetti(k.u(this), bits, 0.6f, 0.97f, 1) }) {
            KidRating(k, th, th.ink)
            Text(listOfNotNull(k.short.takeIf { it.isNotBlank() }, "HI-FI").joinToString(" · "), style = k.kid(th, th.ink, 6f).copy(shadow = pop))
        }
    }
}

/** 19 · Checkerboard trim and a yellow "fun for the whole family" burst on the art. */
@Composable
private fun Starburst(k: Sleeve, th: KidsTheme) = Column(Modifier.fillMaxSize().background(th.flat)) {
    val board = Modifier.fillMaxWidth().height((2.4f * k.s).px).drawBehind { checker(k.u(this)) }
    Band(k, 0.36f, arrange = Arrangement.SpaceBetween) { KidTag(k, th); Studio(k, Color.White, k.kid(th, Color.White, 4.6f)) }
    Box(board)
    Poster(k) {
        Box(Modifier.align(Alignment.BottomStart).offset((2 * k.s).px, (-4 * k.s).px).size((30 * k.s).px).rotate(-12f)
            .clip(StarburstShape).background(Sunny), contentAlignment = Alignment.Center) {
            Text("FUN FOR\nTHE WHOLE\nFAMILY!", textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = k.kid(th, Color(0xFFD81F2C), 4.2f, Type.Bangers).copy(lineHeight = (4.3f * k.s).fs, lineHeightStyle = null))
        }
    }
    Box(board)
    Band(k, 0.64f) { KidRating(k, th, Sunny); Info(k, Color.White) }
}

/** 20 · Sky-blue bands with puffy clouds and a little sun for the VHS tag. */
@Composable
private fun CloudySky(k: Sleeve, th: KidsTheme) = Column(Modifier.fillMaxSize().background(SkyTop)) {
    Band(k, 0.36f, Modifier.background(Brush.verticalGradient(listOf(SkyTop, SkyLow))).drawBehind { clouds(k.u(this), atTop = false, r = 3.2f) },
        arrange = Arrangement.SpaceBetween) {
        Box(Modifier.size((8.4f * k.s).px).clip(CircleShape).background(Sunny), contentAlignment = Alignment.Center) {
            Text("VHS", style = k.kid(th, th.tagFg, 2.9f))
        }
        Studio(k, SkyInk, k.kid(th, SkyInk, 4.4f))
    }
    Poster(k)
    Band(k, 0.64f, Modifier.background(Brush.verticalGradient(listOf(SkyLow, SkyTop))).drawBehind { clouds(k.u(this), atTop = true, r = 3.2f) }) {
        KidRating(k, th, Color.White); Text(k.short, style = k.kid(th, Color.White, 5f)); Push()
        Text("HI-FI", style = k.kid(th, SkyInk, 4.6f))
    }
}
