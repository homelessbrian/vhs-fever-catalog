package org.vhsfever.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws each screen into build/screens so the look can be checked without a TV, with the TV's
 * "large text" setting on (the app should ignore it). Posters don't load here, so boxes show their colours.
 * Runs twice: a TV that reports 1280×720 (like Brian's TCL) and one that reports 960×540.
 */
abstract class ScreensBase(private val tag: String) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private val genres = listOf("Animation", "Family", "Kids", "Comedy", "Adventure", "Music", "Fantasy", "Horror", "Documentary")
    protected val catalog = Catalog.build(res("feed-sample.json"), res("decisions-sample.json")).let { c ->
        Catalog(c.titles.mapIndexed { i, t ->
            t.copy(poster = null).withArt(Art(null, null, t.overview ?: "A tape from the VHS FEVER shelves.", t.year ?: "1990",
                listOf(genres[i % genres.size], genres[(i + 3) % genres.size])))
        })
    }

    @Before fun bigText() { RuntimeEnvironment.setFontScale(1.3f) }

    protected fun shot(name: String) {
        // The TV and focus animations never stop, so draw the window directly instead of waiting for idle.
        rule.mainClock.advanceTimeBy(1500)
        lateinit var bmp: Bitmap
        rule.runOnUiThread {
            val v = rule.activity.window.decorView
            bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
        }
        val dir = File("build/screens").apply { mkdirs() }
        File(dir, "$tag-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    protected fun show(content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent { StoreTheme { content() } }
    }

    private fun focusedBounds(): Rect = rule.onNode(isFocused()).getBoundsInRoot().let {
        Rect(it.left.value, it.top.value, it.right.value, it.bottom.value)
    }

    @Test fun home() {
        show { StoreScreen(catalog, Section.Home, "", emptySet(), null, {}, {}, {}) }
        shot("1-home")
    }

    /** Down on the remote must go to the next shelf, not sideways to the next box. */
    @OptIn(ExperimentalTestApi::class)
    @Test fun downGoesToNextRow() {
        show { StoreScreen(catalog, Section.Home, "", emptySet(), null, {}, {}, {}) }
        rule.mainClock.advanceTimeBy(1000)
        val before = focusedBounds()
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
        rule.mainClock.advanceTimeBy(1000)
        val after = focusedBounds()
        assertTrue("focus went from $before to $after", after.top > before.bottom - 1 || after.top > before.top + 100)
        shot("1-home-down")
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
        rule.mainClock.advanceTimeBy(1000)
        val third = focusedBounds()
        assertTrue("second Down went from $after to $third", third.top > after.top + 20 || third.top >= after.top)
        shot("1-home-down2")
    }

    @Test fun genres() {
        show { StoreScreen(catalog, Section.Genres, "", emptySet(), null, {}, {}, {}) }
        shot("3-genres")
    }

    @Test fun browse() {
        show { StoreScreen(catalog, Section.Browse, "", emptySet(), null, {}, {}, {}) }
        shot("4-browse")
    }

    private fun detail(match: String, angle: Float, name: String, longText: Boolean = false) {
        var t = catalog.titles.first { it.name.contains(match) }
        if (longText) t = t.copy(name = "Aaahh!!! Real Monsters: Meet the Monsters and Friends", overview = (t.overview ?: "") +
            " Ickis, Oblina and Krumm are three young monsters at monster school under the city dump, learning to scare " +
            "humans while getting into trouble with the Gromble. This tape collects two episodes with bonus previews from the era.")
        val tt = t
        show { DetailScreen(tt, catalog, false, previewOn = false, onBack = {}, onOpen = {}, onToggleWatchlist = {}, startAngle = angle) }
        shot(name)
    }

    @Test fun detailThreeQuarter() = detail("Bug", 24f, "2-detail-34")
    @Test fun detailSpine() = detail("Bug", 90f, "2-detail-spine")
    @Test fun detailLongText() = detail("Monsters", 24f, "2-detail-long", longText = true)
    @Test fun detailDvd() = detail("Playhouse", 24f, "2-detail-dvd")
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-land-hdpi")
class Screens1280Test : ScreensBase("tv1280")

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class Screens960Test : ScreensBase("tv960")
