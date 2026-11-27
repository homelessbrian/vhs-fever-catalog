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
import kotlinx.coroutines.Dispatchers
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
                cache[k] = BoxColors(Color(a.getInt(0)), Color(a.getInt(1)), Color(a.getInt(2)))
            }
        }
    }

    fun cached(poster: String): BoxColors? = synchronized(cache) { cache[poster] }

    private val gate = Semaphore(2)

    suspend fun colorsFor(poster: String): BoxColors? {
        cached(poster)?.let { return it }
        val c = gate.withPermit {
            withContext(Dispatchers.IO) {
                runCatching {
                    val req = ImageRequest.Builder(ctx).data(poster).allowHardware(false).size(96, 144).build()
                    val bmp = ((ctx.imageLoader.execute(req) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
                        ?: return@runCatching null
                    pick(Palette.from(bmp).maximumColorCount(16).generate())
                }.getOrNull()
            }
        } ?: return null
        synchronized(cache) { cache[poster] = c }
        save()
        return c
    }

    private fun save() = runCatching {
        val o = JSONObject()
        synchronized(cache) { for ((k, c) in cache) o.put(k, JSONArray(listOf(c.c1.toArgb(), c.c2.toArgb(), c.c3.toArgb()))) }
        file.writeText(o.toString())
    }

    companion object {
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
