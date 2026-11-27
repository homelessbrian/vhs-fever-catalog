package org.vhsfever.review

import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlin.math.abs
import kotlin.math.roundToInt

/** The lines that can be dragged: the two spine lines (wraps only) and the four edges. */
private enum class Line(val label: String, val vertical: Boolean) {
    FOLD_L("Spine: left side", true), FOLD_R("Spine: right side", true),
    LEFT("Left edge", true), RIGHT("Right edge", true), TOP("Top edge", false), BOTTOM("Bottom edge", false)
}

private val DEFAULT_FOLDS = 0.442f to 0.558f   // standard VHS sleeve proportions
private const val STEP = 0.002f                  // one nudge

/**
 * Line up a box: drag the spine lines onto the folds and the edges in to trim borders, with the
 * front, spine and back shown as they'll be cut. Saved with the pick; the box job cuts it again.
 */
@Composable
fun BoxAlignScreen(m: AppModel, key: String) {
    val b = m.boxArt
    val t = b.titles.firstOrNull { it.key == key }
    val c = t?.let { b.shown(it) }
    if (t == null || c == null) { LaunchedEffect(key) { m.back() }; return }
    val start = remember(key, c.id) {
        b.adjustFor(key, c) ?: Adjust(c.sleeve, if (c.kind == "wrap") c.folds ?: DEFAULT_FOLDS else null)
    }
    var a by remember(key, c.id) { mutableStateOf(start) }
    var selected by remember { mutableStateOf<Line?>(null) }
    var original by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    LaunchedEffect(c.url) {
        val r = ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(c.url).allowHardware(false).size(2400).build())
        val bitmap = ((r as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
        if (bitmap != null) original = bitmap else loadError = "Couldn't load the picture."
    }
    // the picture as it will be cut: turned the way Brian turned it
    val bmp = remember(original, a.rotate) {
        original?.let { o ->
            if (a.rotate == 0) o.asImageBitmap()
            else android.graphics.Bitmap.createBitmap(o, 0, 0, o.width, o.height,
                android.graphics.Matrix().apply { postRotate(a.rotate.toFloat()) }, true).asImageBitmap()
        }
    }

    fun set(line: Line, v: Float) {
        val gap = 0.01f
        a = when (line) {
            Line.LEFT -> a.copy(left = v.coerceIn(0f, (a.folds?.first ?: a.right) - gap))
            Line.RIGHT -> a.copy(right = v.coerceIn((a.folds?.second ?: a.left) + gap, 1f))
            Line.TOP -> a.copy(top = v.coerceIn(0f, a.bottom - 0.2f))
            Line.BOTTOM -> a.copy(bottom = v.coerceIn(a.top + 0.2f, 1f))
            Line.FOLD_L -> a.folds?.let { f -> a.copy(folds = v.coerceIn(a.left + gap, f.second - gap) to f.second) } ?: a
            Line.FOLD_R -> a.folds?.let { f -> a.copy(folds = f.first to v.coerceIn(f.first + gap, a.right - gap)) } ?: a
        }
    }
    fun valueOf(line: Line): Float = when (line) {
        Line.LEFT -> a.left; Line.RIGHT -> a.right; Line.TOP -> a.top; Line.BOTTOM -> a.bottom
        Line.FOLD_L -> a.folds?.first ?: 0f; Line.FOLD_R -> a.folds?.second ?: 0f
    }
    val lines = Line.entries.filter { a.folds != null || (it != Line.FOLD_L && it != Line.FOLD_R) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { m.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = C.text) }
            Column(Modifier.weight(1f)) {
                Text("Line up the box", color = C.text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text(t.name, color = C.muted, fontSize = 12.sp)
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (a.folds != null) "Drag the yellow lines onto the folds on each side of the spine, and the blue lines in to trim any border. Pinch to zoom in."
                else "Drag the blue lines in to trim any border around the front. Pinch to zoom in.", color = C.muted, fontSize = 13.sp)
            val img = bmp
            if (img == null) Text(loadError ?: "Loading the picture…", color = C.muted, fontSize = 13.sp)
            else {
                val state = rememberUpdatedState(a)
                // zoom: 1 = whole picture; pan offset in pixels of the view
                var zoom by remember(c.id, a.rotate) { mutableStateOf(1f) }
                var pan by remember(c.id, a.rotate) { mutableStateOf(Offset.Zero) }
                var viewSize by remember { mutableStateOf(IntSize.Zero) }
                fun clampPan(p: Offset, z: Float): Offset {
                    val w = viewSize.width.toFloat(); val h = viewSize.height.toFloat()
                    return Offset(p.x.coerceIn(w - w * z, 0f), p.y.coerceIn(h - h * z, 0f))
                }
                fun zoomAround(focus: Offset, factor: Float) {
                    val nz = (zoom * factor).coerceIn(1f, 8f)
                    val f = nz / zoom
                    pan = clampPan(focus - (focus - pan) * f, nz)
                    zoom = nz
                }
                Box(Modifier.fillMaxWidth().aspectRatio(img.width.toFloat() / img.height).clipToBounds()
                    .onSizeChanged { viewSize = it }) {
                    Canvas(Modifier.fillMaxSize().pointerInput(img) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // one finger near a line moves it; anywhere else it moves the picture; two fingers zoom
                            val cur = state.value
                            val w = size.width.toFloat(); val h = size.height.toFloat()
                            fun screen(l: Line) = if (l.vertical) pan.x + valueOfLine(cur, l) * w * zoom
                                else pan.y + valueOfLine(cur, l) * h * zoom
                            fun dist(l: Line) = abs((if (l.vertical) down.position.x else down.position.y) - screen(l))
                            val grabbed = Line.entries.filter { cur.folds != null || (it != Line.FOLD_L && it != Line.FOLD_R) }
                                .minByOrNull(::dist)?.takeIf { dist(it) < 40.dp.toPx() }
                            if (grabbed != null) selected = grabbed
                            var pinching = false
                            do {
                                val event = awaitPointerEvent()
                                val fingers = event.changes.count { it.pressed }
                                if (fingers >= 2) {
                                    pinching = true
                                    val z = event.calculateZoom()
                                    val p = event.calculatePan()
                                    if (z != 1f) zoomAround(event.calculateCentroid(), z)
                                    pan = clampPan(pan + p, zoom)
                                } else if (fingers == 1 && !pinching) {
                                    val ch = event.changes.first { it.pressed }
                                    val d = ch.position - ch.previousPosition
                                    if (grabbed != null) {
                                        val delta = if (grabbed.vertical) d.x / (w * zoom) else d.y / (h * zoom)
                                        set(grabbed, valueOfLine(state.value, grabbed) + delta)
                                    } else if (zoom > 1f) pan = clampPan(pan + d, zoom)
                                }
                                event.changes.forEach { if (it.position != it.previousPosition) it.consume() }
                            } while (event.changes.any { it.pressed })
                        }
                    }) {
                        val w = size.width; val h = size.height
                        val iw = w * zoom; val ih = h * zoom
                        fun x(v: Float) = pan.x + v * iw
                        fun y(v: Float) = pan.y + v * ih
                        drawImage(img, dstOffset = IntOffset(pan.x.roundToInt(), pan.y.roundToInt()),
                            dstSize = IntSize(iw.roundToInt(), ih.roundToInt()))
                        // dim what gets trimmed away
                        val dim = Color.Black.copy(alpha = 0.55f)
                        drawRect(dim, Offset(x(0f), y(0f)), Size(a.left * iw, ih))
                        drawRect(dim, Offset(x(a.right), y(0f)), Size((1 - a.right) * iw, ih))
                        drawRect(dim, Offset(x(a.left), y(0f)), Size((a.right - a.left) * iw, a.top * ih))
                        drawRect(dim, Offset(x(a.left), y(a.bottom)), Size((a.right - a.left) * iw, (1 - a.bottom) * ih))
                        for (l in lines) {
                            val v = valueOf(l)
                            val col = if (l == Line.FOLD_L || l == Line.FOLD_R) C.gold else C.blue
                            val stroke = if (l == selected) 3.dp.toPx() else 1.5.dp.toPx()
                            if (l.vertical) drawLine(col, Offset(x(v), 0f), Offset(x(v), h), stroke)
                            else drawLine(col, Offset(0f, y(v)), Offset(w, y(v)), stroke)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Zoom ${"%.1f".format(zoom)}×  ·  pinch, or", color = C.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    val mid = Offset(viewSize.width / 2f, viewSize.height / 2f)
                    // zoom towards the line being moved, so it stays in view
                    fun focus(): Offset = selected?.let { l ->
                        if (l.vertical) Offset(pan.x + valueOf(l) * viewSize.width * zoom, mid.y)
                        else Offset(mid.x, pan.y + valueOf(l) * viewSize.height * zoom)
                    } ?: mid
                    OutlinedButton(onClick = { zoomAround(focus(), 1 / 1.6f) }) { Text("−", color = C.text) }
                    OutlinedButton(onClick = { zoomAround(focus(), 1.6f) }) { Text("+", color = C.text) }
                    OutlinedButton(onClick = { zoom = 1f; pan = Offset.Zero }) { Text("Fit", color = C.text) }
                }
                // fine nudges for the line last touched
                val sel = selected
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(sel?.label ?: "Touch a line to move it", color = C.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (sel != null) {
                        OutlinedButton(onClick = { set(sel, valueOf(sel) - STEP) }) { Text(if (sel.vertical) "◀" else "▲", color = C.text) }
                        OutlinedButton(onClick = { set(sel, valueOf(sel) + STEP) }) { Text(if (sel.vertical) "▶" else "▼", color = C.text) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // a sideways or upside-down scan: turn it a quarter at a time (the lines start over)
                    Chip("Rotate ⟳", false, {
                        val turned = Math.floorMod(a.rotate + 90, 360)
                        val o = original
                        val wide = o != null && (if (turned % 180 == 0) o.width > o.height * 1.15f else o.height > o.width * 1.15f)
                        a = Adjust(c.sleeve, if (wide) c.folds?.takeIf { turned == 0 } ?: DEFAULT_FOLDS else null, rotate = turned)
                        selected = null
                    })
                    Chip(if (a.folds != null) "Whole wrap: back, spine, front" else "Front only", a.folds != null, {
                        a = if (a.folds != null) a.copy(folds = null) else a.copy(folds = c.folds ?: DEFAULT_FOLDS)
                        selected = null
                    })
                }
                SectionLabel("How it will look")
                Row(Modifier.fillMaxWidth().height(170.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    val f = a.folds
                    if (f != null) {
                        Panel(img, a.left, f.first, a.top, a.bottom, "Back")
                        Panel(img, f.first, f.second, a.top, a.bottom, "Spine")
                        Panel(img, f.second, a.right, a.top, a.bottom, "Front")
                    } else Panel(img, a.left, a.right, a.top, a.bottom, "Front")
                }
            }
            if (b.status.isNotEmpty()) Text(b.status, color = C.muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 24.dp)) {
                Button(onClick = { b.saveAdjust(key, c, a) }, enabled = bmp != null,
                    colors = ButtonDefaults.buttonColors(containerColor = C.gold, contentColor = Color(0xFF1A1206))) { Text("Save") }
                OutlinedButton(onClick = { a = start; selected = null }) { Text("Undo changes", color = C.text) }
                OutlinedButton(onClick = { a = Adjust(c.sleeve, if (c.kind == "wrap") c.folds ?: DEFAULT_FOLDS else null); selected = null }) {
                    Text("Start over", color = C.text)
                }
            }
        }
    }
}

private fun valueOfLine(a: Adjust, l: Line): Float = when (l) {
    Line.LEFT -> a.left; Line.RIGHT -> a.right; Line.TOP -> a.top; Line.BOTTOM -> a.bottom
    Line.FOLD_L -> a.folds?.first ?: 0f; Line.FOLD_R -> a.folds?.second ?: 0f
}

/** One cut piece of the picture, drawn at its own shape and full height. */
@Composable
private fun Panel(img: ImageBitmap, x0: Float, x1: Float, y0: Float, y1: Float, label: String) {
    val sx = (x0 * img.width).roundToInt().coerceIn(0, img.width - 1)
    val sw = ((x1 - x0) * img.width).roundToInt().coerceIn(1, img.width - sx)
    val sy = (y0 * img.height).roundToInt().coerceIn(0, img.height - 1)
    val sh = ((y1 - y0) * img.height).roundToInt().coerceIn(1, img.height - sy)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.weight(1f).aspectRatio(sw.toFloat() / sh).background(Color.Black)) {
            drawImage(img, srcOffset = IntOffset(sx, sy), srcSize = IntSize(sw, sh),
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
        }
        Text(label, color = C.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp).width(60.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
