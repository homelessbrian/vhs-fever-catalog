package org.vhsfever.tv

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws each screen at TV size (1920×1080) into build/screens so the look can be checked
 * against the mockups without a TV. Posters don't load here, so boxes show their colours.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class ScreensTest {
    @get:Rule val rule = createComposeRule()

    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private val genres = listOf("Animation", "Family", "Comedy", "Adventure", "Music", "Fantasy", "Horror", "Sci-Fi", "Drama")
    private val catalog = Catalog.build(res("feed-sample.json"), res("decisions-sample.json")).let { c ->
        Catalog(c.titles.mapIndexed { i, t ->
            t.withArt(Art(null, null, t.overview ?: "A tape from the VHS FEVER shelves.", t.year ?: "1990",
                listOf(genres[i % genres.size], genres[(i + 3) % genres.size])))
        })
    }

    private fun shot(name: String) {
        rule.mainClock.advanceTimeBy(1500)
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Ink.Gold, surface = Ink.Navy)) { content() } }
    }

    @Test fun home() {
        show { StoreScreen(catalog, Section.Home, "", emptySet(), null, {}, {}, {}) }
        shot("1-home")
    }

    @Test fun genres() {
        show { StoreScreen(catalog, Section.Genres, "", emptySet(), null, {}, {}, {}) }
        shot("3-genres")
    }

    @Test fun browse() {
        show { StoreScreen(catalog, Section.Browse, "", emptySet(), null, {}, {}, {}) }
        shot("4-browse")
    }

    private fun detail(angle: Float, name: String) {
        val t = catalog.titles.first { it.name.contains("Bug") }
        show { DetailScreen(t, catalog, false, previewOn = false, onBack = {}, onOpen = {}, onToggleWatchlist = {}, startAngle = angle) }
        shot(name)
    }

    @Test fun detailThreeQuarter() = detail(24f, "2-detail-34")
    @Test fun detailFront() = detail(0f, "2-detail-front")
    @Test fun detailSpine() = detail(90f, "2-detail-spine")
    @Test fun detailBack() = detail(180f, "2-detail-back")
}
