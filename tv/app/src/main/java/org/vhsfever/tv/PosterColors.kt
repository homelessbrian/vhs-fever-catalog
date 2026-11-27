package org.vhsfever.tv

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Box colours picked from each movie's poster, so the printed spine, back and bands match the art:
 * c1 = the poster's main strong colour, c2 = its dark colour, c3 = a light accent.
 * Worked out once per poster and kept on the TV.
 */
class PosterColors(private val ctx: Context) {
    private val file = File(ctx.filesDir, "poster-colors-v1.json")
    private val cache = HashMap<String, BoxColors>()

    init {
        runCatching {
            val o = JSONObject(file.readText())
            for (k in o.keys()) o.optJSONArray(k)?.let { a ->
                // older saves have only the three colours; the poster edges are added the next time it's looked at
                cache[k] = BoxColors(Color(a.getInt(0)), Color(a.getInt(1)), Color(a.getInt(2)),
                    if (a.length() >= 5) Color(a.getInt(3)) else null, if (a.length() >= 5) Color(a.getInt(4)) else null)
            }
        }
    }

    fun cached(poster: String): BoxColors? = synchronized(cache) { cache[poster] }

    /** True when nothing more needs working out: posters also need their edge colours (box scans don't). */
    fun done(key: String): Boolean = cached(key)?.let { key.startsWith("box:") || it.top != null } == true

    private val gate = Semaphore(2)

    /** The cache key for a real box front saved on the TV (changes when the box is cut again). */
    fun keyOf(front: File) = "box:" + front.path + "@" + front.lastModified()

    /** Colours from a real VHS box front, so a made-up spine and back match the box. */
    suspend fun colorsFor(front: File): BoxColors? = colorsFor(keyOf(front), front)

    suspend fun colorsFor(poster: String): BoxColors? = colorsFor(poster, poster)

    private suspend fun colorsFor(poster: String, model: Any): BoxColors? {
        if (done(poster)) cached(poster)?.let { return it }
        val c = gate.withPermit {
            withContext(Dispatchers.IO) {
                runCatching {
                    val req = ImageRequest.Builder(ctx).data(model).allowHardware(false).size(96, 144).build()
                    val bmp = ((ctx.imageLoader.execute(req) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
                        ?: return@runCatching null
                    pick(Palette.from(bmp).maximumColorCount(16).generate()).copy(top = edge(bmp, true), bottom = edge(bmp, false))
                }.getOrNull()
            }
        } ?: return null
        synchronized(cache) { cache[poster] = c }
        saveSoon()
        return c
    }

    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saving = AtomicBoolean(false)

    /** Saves the colours once things go quiet, instead of rewriting the whole file after every poster. */
    private fun saveSoon() {
        if (!saving.compareAndSet(false, true)) return
        saveScope.launch { delay(3000); saving.set(false); save() }
    }

    private fun save() = runCatching {
        val o = JSONObject()
        synchronized(cache) {
            for ((k, c) in cache) o.put(k, JSONArray(listOfNotNull(c.c1.toArgb(), c.c2.toArgb(), c.c3.toArgb(),
                c.top?.toArgb(), c.bottom?.toArgb()).takeIf { it.size == 5 } ?: listOf(c.c1.toArgb(), c.c2.toArgb(), c.c3.toArgb())))
        }
        file.writeText(o.toString())
    }

    companion object {
        /** The average colour of a thin strip along the picture's top (or bottom) edge. */
        fun edge(bmp: android.graphics.Bitmap, top: Boolean): Color {
            val rows = maxOf(1, bmp.height / 16)
            var r = 0L; var g = 0L; var b = 0L; var n = 0L
            for (y in (if (top) 0 until rows else bmp.height - rows until bmp.height)) for (x in 0 until bmp.width) {
                val p = bmp.getPixel(x, y)
                r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
            }
            return Color((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        }

        /** Main colour dark enough for cream printing on it, a deep shade, and a light accent. */
        fun pick(p: Palette): BoxColors {
            val main = p.vibrantSwatch?.rgb ?: p.dominantSwatch?.rgb ?: 0xFF4A4A58.toInt()
            val dark = p.darkMutedSwatch?.rgb ?: p.darkVibrantSwatch?.rgb ?: shade(main, 0.25f)
            val light = p.lightVibrantSwatch?.rgb ?: p.lightMutedSwatch?.rgb ?: 0xFFF1EAD8.toInt()
            return BoxColors(Color(limitLightness(main, 0.45f)), Color(limitLightness(dark, 0.16f)), Color(light))
        }

        private fun shade(c: Int, l: Float): Int {
            val hsl = FloatArray(3); ColorUtils.colorToHSL(c, hsl); hsl[2] = l; return ColorUtils.HSLToColor(hsl)
        }

        private fun limitLightness(c: Int, max: Float): Int {
            val hsl = FloatArray(3); ColorUtils.colorToHSL(c, hsl)
            if (hsl[2] > max) hsl[2] = max
            return ColorUtils.HSLToColor(hsl)
        }
    }
}
