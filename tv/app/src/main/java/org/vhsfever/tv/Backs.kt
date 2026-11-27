package org.vhsfever.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Text
import coil.compose.AsyncImage

/*
 * The printed back of a box that has no real scan. Five layouts, like the different looks rental
 * backs had; each movie always gets the same one (picked from its key), so the store looks varied
 * but a box never changes. Colours come from the movie's poster, words from TMDB, and the
 * screenshots from the movie's own tape (TMDB stills when the tape gave none).
 */

private val Paper = Color(0xFFF3EAD8)
private val PaperInk = Color(0xFF2A261F)
private val Night = Color(0xFF13130F)

/** Everything a back prints, gathered once. */
private class BackText(t: Title, val tapeText: String) {
    val art = t.art
    val name = t.name.uppercase()
    val tagline = art?.tagline?.takeIf { it.isNotBlank() }
    val overview = t.overview ?: art?.overview ?: "No summary yet."
    val cast = art?.cast.orEmpty()
    val director = art?.director
    val rating = art?.rating?.takeIf { it.isNotBlank() }
    val facts = listOfNotNull(
        "COLOR",
        (art?.runtime ?: t.tapes.firstNotNullOfOrNull { it.minutes })?.let { "$it MIN" },
        yearText(t).takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    val pictures: List<Any> = (t.shots + art?.stills.orEmpty() + listOfNotNull(art?.backdrop ?: t.backdrop)).distinct()
    val barcode = barcodeText(t)
    fun pic(i: Int): Any? = pictures.getOrNull(i) ?: pictures.firstOrNull()
}

fun backLayout(t: Title): Int = Math.floorMod(t.key.hashCode() * 31 + 7, 5)

@Composable
fun BoxBack(t: Title, tapeText: String, modifier: Modifier = Modifier, layout: Int = backLayout(t)) {
    val c = colorsOf(t)
    val b = BackText(t, tapeText)
    Box(modifier) {
        when (if (b.pictures.isEmpty()) 2 else layout) {
            0 -> BackClassic(b, c)
            1 -> BackBigStill(b, c)
            2 -> BackColorBand(b, c)
            3 -> BackFilmStrip(b, c)
            else -> BackSnapshots(b, c)
        }
        Sheen()
    }
}

// ------------------------------------------------------------------ type

private fun tagStyle(color: Color, size: Float = 12.5f) = TextStyle(fontFamily = Type.Oswald, fontWeight = FontWeight.Bold,
    fontSize = size.fs, lineHeight = (size * 1.12f).fs, color = color)
private fun titleStyle(color: Color, size: Float = 19f) = TextStyle(fontFamily = Type.Oswald, fontWeight = FontWeight.Bold,
    fontSize = size.fs, lineHeight = (size * 1.0f).fs, letterSpacing = 0.4f.fs, color = color)
private fun bodyStyle(color: Color) = TextStyle(fontFamily = Type.Barlow, fontSize = 10.5f.fs, lineHeight = 13.5f.fs, color = color)
private fun monoStyle(color: Color) = TextStyle(fontFamily = Type.Mono, fontSize = 7f.fs, letterSpacing = 0.4f.fs, color = color)

/** "STARRING Peter Billingsley · Darren McGavin" with the label in the accent colour. */
@Composable
private fun BackCredits(b: BackText, label: Color, text: Color, modifier: Modifier = Modifier, stacked: Boolean = false,
                    align: TextAlign = TextAlign.Start) {
    if (b.cast.isEmpty() && b.director == null) return
    val lab = SpanStyle(fontFamily = Type.Oswald, fontWeight = FontWeight.Medium, color = label, letterSpacing = 0.6f.fs)
    val s = buildAnnotatedString {
        if (b.cast.isNotEmpty()) {
            withStyle(lab) { append("STARRING") }
            append(if (stacked) "\n" else "  ")
            append(b.cast.joinToString(if (stacked) "\n" else " · "))
        }
        if (b.director != null) {
            if (b.cast.isNotEmpty()) append("\n")
            withStyle(lab) { append("DIRECTED BY") }
            append(if (stacked) "\n" else "  ")
            append(b.director)
        }
    }
    Text(s, modifier = modifier, maxLines = if (stacked) 7 else 3, overflow = TextOverflow.Ellipsis,
        style = TextStyle(fontFamily = Type.Barlow, fontSize = 8.5f.fs, lineHeight = 11f.fs, color = text, textAlign = align))
}

@Composable
private fun BackRating(b: BackText, color: Color) {
    b.rating?.let {
        Text(it, modifier = Modifier.border(1.px, color).padding(horizontal = 3.px),
            style = TextStyle(fontFamily = Type.Oswald, fontWeight = FontWeight.Bold, fontSize = 10.fs, color = color))
    }
}

@Composable
private fun BackStudio(b: BackText, color: Color, modifier: Modifier = Modifier) {
    val art = b.art
    if (art?.studioLogo != null) AsyncImage(model = art.studioLogo, contentDescription = art.studio,
        colorFilter = ColorFilter.tint(color), contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
        modifier = modifier.size(52.px, 16.px))
    else art?.studio?.let {
        FitText(it.uppercase(), style = monoStyle(color), maxLines = 2, minScale = 0.6f, wordsWhole = true,
            modifier = modifier.width(84.px))
    }
}

/** Rating, facts and studio on the left; the barcode and VHS mark on the right. */
@Composable
private fun BackInfoRow(b: BackText, ink: Color, modifier: Modifier = Modifier, paper: Color = Color(0xFFF1F1EE)) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.px)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.px)) {
                BackRating(b, ink)
                FitText(b.facts, style = monoStyle(ink), minScale = 0.6f)
            }
            Text(b.tapeText, style = monoStyle(ink.alpha(0.7f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BackStudio(b, ink)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.px)) {
            Barcode(b.barcode, Modifier.size(52.px, 22.px), paper = paper)
            Text("VHS", modifier = Modifier.background(ink).padding(horizontal = 4.px),
                style = TextStyle(fontFamily = Type.Oswald, fontWeight = FontWeight.Bold, fontSize = 8.fs, letterSpacing = 1.5f.fs,
                    color = if (ink == Night || ink == PaperInk) Paper else Night))
        }
    }
}

@Composable
private fun BackPic(model: Any?, modifier: Modifier, frame: Color? = null) {
    val m = if (frame != null) modifier.border(1.5f.px, frame) else modifier
    Box(m.background(Night)) {
        if (model != null) AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun ColumnScope.BackOverview(b: BackText, color: Color, maxLines: Int = 12) {
    FitText(b.overview, style = bodyStyle(color), maxLines = maxLines, minScale = 0.72f,
        modifier = Modifier.fillMaxWidth().weight(1f, fill = true))
}

// ------------------------------------------------------------------ 1. classic

@Composable
private fun BackClassic(b: BackText, c: BoxColors) {
    Column(Modifier.fillMaxSize().background(Paper).padding(10.px), verticalArrangement = Arrangement.spacedBy(6.px)) {
        b.tagline?.let { FitText(it.uppercase(), style = tagStyle(c.c1), maxLines = 2, minScale = 0.55f, modifier = Modifier.fillMaxWidth()) }
        Row(Modifier.fillMaxWidth().background(c.c2).padding(3.px), horizontalArrangement = Arrangement.spacedBy(3.px)) {
            BackPic(b.pic(0), Modifier.weight(1.85f).height(66.px), Paper)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.px)) {
                BackPic(b.pic(1), Modifier.fillMaxWidth().height(31.5f.px), Paper)
                BackPic(b.pic(2), Modifier.fillMaxWidth().height(31.5f.px), Paper)
            }
        }
        FitText(b.name, style = titleStyle(c.c2, 17f), maxLines = 2, minScale = 0.6f, modifier = Modifier.fillMaxWidth())
        BackOverview(b, PaperInk)
        BackCredits(b, c.c1, PaperInk)
        BackInfoRow(b, Night)
    }
}

// ------------------------------------------------------------------ 2. big still

@Composable
private fun BackBigStill(b: BackText, c: BoxColors) {
    Column(Modifier.fillMaxSize().background(Night)) {
        Box(Modifier.fillMaxWidth().height(112.px)) {
            BackPic(b.pic(0), Modifier.fillMaxSize())
            Box(Modifier.fillMaxWidth().height(70.px).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Night))))
            FitText(b.name, style = titleStyle(Paper, 21f).copy(shadow = Shadow(c.c1, Offset(0f, 2f), 0f)), maxLines = 2,
                minScale = 0.55f, modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 10.px, vertical = 4.px),
                align = Alignment.BottomStart)
        }
        Column(Modifier.fillMaxSize().padding(start = 10.px, end = 10.px, top = 4.px, bottom = 10.px),
            verticalArrangement = Arrangement.spacedBy(6.px)) {
            b.tagline?.let { FitText(it.uppercase(), style = tagStyle(c.c3, 11f), maxLines = 2, minScale = 0.55f, modifier = Modifier.fillMaxWidth()) }
            BackOverview(b, Color(0xFFDDD5BF), 10)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.px)) {
                BackPic(b.pic(1), Modifier.weight(1f).height(40.px), c.c3)
                BackPic(b.pic(2), Modifier.weight(1f).height(40.px), c.c3)
            }
            BackCredits(b, c.c3, Color(0xFFA39A86), Modifier.fillMaxWidth(), align = TextAlign.Center)
            BackInfoRow(b, Paper)
        }
    }
}

// ------------------------------------------------------------------ 3. colour band

@Composable
private fun BackColorBand(b: BackText, c: BoxColors) {
    Column(Modifier.fillMaxSize().background(Paper)) {
        Column(Modifier.fillMaxWidth().background(c.c1).padding(10.px), verticalArrangement = Arrangement.spacedBy(4.px)) {
            FitText(b.name, style = titleStyle(Paper, 24f), maxLines = 2, minScale = 0.5f, wordsWhole = true,
                modifier = Modifier.fillMaxWidth())
            b.tagline?.let { FitText(it.uppercase(), style = tagStyle(c.c3, 10f), maxLines = 2, minScale = 0.55f, modifier = Modifier.fillMaxWidth()) }
        }
        if (b.pictures.isNotEmpty()) Row(Modifier.fillMaxWidth().background(c.c2).padding(2.px), horizontalArrangement = Arrangement.spacedBy(2.px)) {
            for (i in 0..2) BackPic(b.pic(i), Modifier.weight(1f).height(42.px))
        }
        Column(Modifier.weight(1f).fillMaxWidth().padding(10.px), verticalArrangement = Arrangement.spacedBy(6.px)) {
            BackOverview(b, PaperInk)
            BackCredits(b, c.c1, PaperInk)
        }
        BackInfoRow(b, Paper, Modifier.background(Night).padding(horizontal = 10.px, vertical = 6.px))
    }
}

// ------------------------------------------------------------------ 4. film strip

@Composable
private fun BackFilmStrip(b: BackText, c: BoxColors) {
    Row(Modifier.fillMaxSize().background(c.c2)) {
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 9.px, end = 7.px, top = 10.px, bottom = 10.px),
            verticalArrangement = Arrangement.spacedBy(6.px)) {
            b.tagline?.let { FitText(it.uppercase(), style = tagStyle(c.c3, 11f), maxLines = 3, minScale = 0.55f, modifier = Modifier.fillMaxWidth()) }
            FitText(b.name, style = titleStyle(Paper, 16f), maxLines = 2, minScale = 0.55f, wordsWhole = true, modifier = Modifier.fillMaxWidth())
            BackOverview(b, Color(0xFFE6DEC9), 14)
            Box(Modifier.fillMaxWidth().height(1.px).background(c.c3))
            BackCredits(b, c.c3, Paper, stacked = true)
            Column(verticalArrangement = Arrangement.spacedBy(4.px)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.px)) {
                    BackRating(b, Paper); FitText(b.facts, style = monoStyle(Paper), minScale = 0.55f)
                }
                BackStudio(b, Paper)
                Barcode(b.barcode, Modifier.size(52.px, 20.px))
            }
        }
        // the strip: sprocket holes down both edges, three frames
        Row(Modifier.width(66.px).fillMaxHeight().background(Night)) {
            Sprockets()
            Column(Modifier.weight(1f).fillMaxHeight().padding(vertical = 6.px), verticalArrangement = Arrangement.SpaceEvenly) {
                for (i in 0..2) BackPic(b.pic(i), Modifier.fillMaxWidth().height(86.px))
            }
            Sprockets()
        }
    }
}

@Composable
private fun Sprockets() {
    Box(Modifier.width(7.px).fillMaxHeight().drawBehind {
        val hole = size.width * 0.55f
        val step = hole * 2.2f
        var y = step / 2
        while (y < size.height) {
            drawRect(Paper.alpha(0.85f), Offset((size.width - hole) / 2, y), androidx.compose.ui.geometry.Size(hole, hole * 1.2f))
            y += step
        }
    })
}

// ------------------------------------------------------------------ 5. snapshots

@Composable
private fun BackSnapshots(b: BackText, c: BoxColors) {
    Column(Modifier.fillMaxSize().background(Paper)) {
        Box(Modifier.fillMaxWidth().height(6.px).background(c.c1))
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.px, vertical = 8.px), verticalArrangement = Arrangement.spacedBy(5.px)) {
            FitText(b.name, style = titleStyle(c.c1, 20f), maxLines = 2, minScale = 0.55f, wordsWhole = true, modifier = Modifier.fillMaxWidth())
            b.tagline?.let { FitText(it.uppercase(), style = tagStyle(c.c2, 9.5f), maxLines = 2, minScale = 0.55f, modifier = Modifier.fillMaxWidth()) }
            BoxWithConstraints(Modifier.fillMaxWidth().height(100.px)) {
                val pw = maxWidth * 0.46f
                PhotoPrint(b.pic(0), pw, -5f, Modifier.offset(x = 2.px, y = 3.px))
                PhotoPrint(b.pic(1), pw, 4f, Modifier.offset(x = maxWidth - pw - 3.px, y = 8.px))
                PhotoPrint(b.pic(2), pw, -2f, Modifier.offset(x = maxWidth * 0.27f, y = 38.px))
            }
            BackOverview(b, PaperInk)
            BackCredits(b, c.c1, PaperInk)
            BackInfoRow(b, Night)
        }
        Box(Modifier.fillMaxWidth().height(6.px).background(c.c2))
    }
}

/** A photo print: white border, thicker at the bottom, a little tilted. */
@Composable
private fun PhotoPrint(model: Any?, width: Dp, tilt: Float, modifier: Modifier) {
    Column(modifier.width(width).rotate(tilt).shadow(3.px).background(Color.White)
        .padding(start = 3.px, end = 3.px, top = 3.px, bottom = 9.px)) {
        BackPic(model, Modifier.fillMaxWidth().height(width * 0.66f))
    }
}
