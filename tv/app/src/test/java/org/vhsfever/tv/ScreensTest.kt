package org.vhsfever.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
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
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalInspectionMode provides true) {
                StoreTheme { content() }
            }
        }
    }

    /** The highlighted thing on screen (the smallest focused node, so a box rather than the row it sits in). */
    /** Which thing is highlighted (its id) and where it is on screen. */
    private fun focused(): Pair<Int, Rect> {
        val nodes = rule.onAllNodes(isFocused()).fetchSemanticsNodes()
        assertTrue("nothing is highlighted", nodes.isNotEmpty())
        val n = nodes.minBy { it.boundsInRoot.width * it.boundsInRoot.height }
        return n.id to n.boundsInRoot
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
        // put the highlight on the first box of the first shelf (the top-left box-shaped button)
        val clickable = rule.onAllNodes(hasClickAction())
        val nodes = clickable.fetchSemanticsNodes()
        val tall = nodes.maxOf { it.boundsInRoot.height } * 0.8f     // shelf boxes are the tallest buttons on Home
        val box = nodes.withIndex().filter { (_, n) -> n.boundsInRoot.height >= tall && n.boundsInRoot.width > 10f }
            .minWith(compareBy({ it.value.boundsInRoot.top }, { it.value.boundsInRoot.left })).index
        clickable[box].performSemanticsAction(SemanticsActions.RequestFocus)
        rule.mainClock.advanceTimeBy(1000)
        shot("1-home-start")
        val (b0, before) = focused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.mainClock.advanceTimeBy(1000)
        shot("1-home-down")
        val (b1, after) = focused()
        // a straight move down: a different box, same column (the shelf slides up, so it can land in the same spot)
        assertTrue("Down didn't move", b1 != b0)
        assertTrue("Down moved sideways, from $before to $after", kotlin.math.abs(after.left - before.left) < before.width / 2)
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.mainClock.advanceTimeBy(1000)
        shot("1-home-down2")
        val (b2, third) = focused()
        assertTrue("second Down didn't move", b2 != b1 && b2 != b0)
        assertTrue("second Down moved sideways, from $after to $third", kotlin.math.abs(third.left - after.left) < after.width / 2)
    }

    /** Puts the highlight on the top-left box of the page (boxes are the tallest buttons). */
    private fun focusFirstBox() {
        rule.mainClock.advanceTimeBy(1000)
        val clickable = rule.onAllNodes(hasClickAction())
        val nodes = clickable.fetchSemanticsNodes()
        val tall = nodes.maxOf { it.boundsInRoot.height } * 0.8f
        val boxes = nodes.withIndex().filter { (_, n) -> n.boundsInRoot.height >= tall && n.boundsInRoot.width > 10f }
        // boxes lean a little, so the top shelf is every box within a few pixels of the highest one
        val top = boxes.minOf { it.value.boundsInRoot.top }
        val box = boxes.filter { it.value.boundsInRoot.top < top + tall / 4 }.minBy { it.value.boundsInRoot.left }.index
        clickable[box].performSemanticsAction(SemanticsActions.RequestFocus)
        rule.mainClock.advanceTimeBy(1000)
    }

    @OptIn(ExperimentalTestApi::class)
    private fun press(k: Key) { rule.onRoot().performKeyInput { pressKey(k) }; rule.mainClock.advanceTimeBy(800) }

    /**
     * Scrolled down the page: Right one box, Left back to the first box, Left again into the menu, then Right
     * must land on that same box again. Left from a box in the first column must never move up or down a row.
     */
    private fun menuRoundTrip(section: Section, name: String) {
        show { StoreScreen(catalog, section, "", emptySet(), null, {}, {}, {}) }
        focusFirstBox()
        val (start, startAt) = focused()
        press(Key.DirectionDown)
        val (down1, down1At) = focused()
        assertTrue("Down didn't move", down1 != start)
        assertTrue("Down moved sideways, from $startAt to $down1At", kotlin.math.abs(down1At.left - startAt.left) < startAt.width / 2)
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        val (second, secondAt) = focused()
        press(Key.DirectionLeft)
        val (firstBox, firstAt) = focused()
        assertTrue("Left didn't move to the box beside it", firstBox != second)
        assertTrue("Left moved to another row, from $secondAt to $firstAt", kotlin.math.abs(firstAt.top - secondAt.top) < secondAt.height / 4)
        press(Key.DirectionLeft)
        shot("$name-menu")
        val (menu, menuAt) = focused()
        val screen = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("Left from the first box didn't go to the menu (on $menuAt)", menu != firstBox && menuAt.right < screen.width * 0.2f)
        press(Key.DirectionRight)
        shot("$name-back")
        val (back, backAt) = focused()
        assertTrue("Right from the menu didn't come back to the box (on $backAt, expected $firstAt)", back == firstBox)
    }

    @Test fun homeMenuAndBack() = menuRoundTrip(Section.Home, "8-nav-home")
    @Test fun browseMenuAndBack() = menuRoundTrip(Section.Browse, "8-nav-browse")
    @Test fun genreMenuAndBack() = menuRoundTrip(Section.Genre(catalog.genreShelves.first().first), "8-nav-genre")

    @Test fun genrePage() {
        show { StoreScreen(catalog, Section.Genre(catalog.genreShelves.first().first), "", emptySet(), null, {}, {}, {}) }
        shot("3-genre-page")
    }

    /** The five printed backs for a box with no VHS scan (pictures don't load here, so they show as dark frames). */
    @Test fun backs() {
        val t = catalog.titles.first().copy(name = "A Christmas Story", year = "1983",
            colors = BoxColors(androidx.compose.ui.graphics.Color(0xFFB8261F), androidx.compose.ui.graphics.Color(0xFF1E3A26),
                androidx.compose.ui.graphics.Color(0xFFE0B24F)),
            shots = listOf(File("a.jpg"), File("b.jpg"), File("c.jpg")))
            .let { it.copy(art = Art(null, null, "The comic mishaps and adventures of a young boy named Ralph, trying to convince " +
                "his parents, teachers, and Santa that a Red Ryder B.B. gun really is the perfect Christmas gift for the 1940s.", "1983",
                tagline = "A tribute to the original, traditional, one-hundred-percent, red-blooded, two-fisted, all-American Christmas.",
                cast = listOf("Peter Billingsley", "Darren McGavin", "Melinda Dillon"), director = "Bob Clark", rating = "PG",
                runtime = 93, studio = "Metro-Goldwyn-Mayer", imdbId = "tt0085334")) }
        show {
            androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.fillMaxSize().background(Ink.Navy)
                .then(androidx.compose.ui.Modifier.padding(24.px)), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.px)) {
                for (i in 0..4) BoxBack(t, "1983 VHS release", androidx.compose.ui.Modifier.size(190.px, 336.px), layout = i)
            }
        }
        shot("6-backs")
    }

    /** All 14 grown-up printed sleeve looks for TMDB posters, at 1.5× shelf size and at real shelf size, over a stand-in poster. */
    @Test fun sleeves() {
        val bmp = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888).apply {
            val cv = Canvas(this); val p = android.graphics.Paint()
            p.shader = android.graphics.LinearGradient(0f, 0f, 0f, 300f, intArrayOf(0xFF1F8BE8.toInt(), 0xFF8EC8F2.toInt(),
                0xFF3B3328.toInt(), 0xFF18171A.toInt()), floatArrayOf(0f, 0.45f, 0.75f, 1f), android.graphics.Shader.TileMode.CLAMP)
            cv.drawRect(0f, 0f, 200f, 300f, p); p.shader = null
            p.color = 0xFFD7262B.toInt(); cv.drawRect(20f, 30f, 180f, 70f, p)          // a title block
            p.color = 0xFF2A2522.toInt(); cv.drawOval(60f, 110f, 140f, 290f, p)         // a figure
            p.color = 0xFFF0E6D8.toInt(); cv.drawCircle(100f, 105f, 22f, p)
        }
        val standIn = androidx.compose.ui.graphics.painter.BitmapPainter(bmp.asImageBitmap())
        val t = catalog.titles.first().copy(name = "Armed and Dangerous", year = "1986", poster = "stand-in", box = null,
            colors = BoxColors(androidx.compose.ui.graphics.Color(0xFF9E2A26), androidx.compose.ui.graphics.Color(0xFF1A1418),
                androidx.compose.ui.graphics.Color(0xFF8EC8F2), androidx.compose.ui.graphics.Color(0xFF1F8BE8), androidx.compose.ui.graphics.Color(0xFF18171A)))
            .let { it.copy(art = Art(null, null, "", "1986", rating = "PG-13", runtime = 88, studio = "Columbia Pictures")) }
        val styles = GROWN_UP_LOOKS
        show {
            androidx.compose.runtime.CompositionLocalProvider(LocalPosterStandIn provides standIn) {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize().background(Ink.Navy).padding(16.px),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.px)) {
                    for (row in styles.chunked(7)) androidx.compose.foundation.layout.Row(
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.px)) {
                        for (st in row) androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(129.px, 228.px)) {
                            PosterSleeve(t, colorsOf(t), 1.5f, false, st); Sheen()
                        }
                    }
                    androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.px)) {
                        for (st in styles) androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(86.px, 152.px)) {
                            PosterSleeve(t, colorsOf(t), 1f, false, st); Sheen()
                        }
                    }
                }
            }
        }
        shot("7-sleeves")
    }

    private fun kidsStandIn(): androidx.compose.ui.graphics.painter.BitmapPainter {
        val bmp = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888).apply {
            val cv = Canvas(this); val p = android.graphics.Paint()
            p.shader = android.graphics.LinearGradient(0f, 0f, 0f, 300f, intArrayOf(0xFF58C7F3.toInt(), 0xFFBFEEFF.toInt()),
                null, android.graphics.Shader.TileMode.CLAMP)
            cv.drawRect(0f, 0f, 200f, 300f, p); p.shader = null
            p.color = 0xFFFFD23A.toInt(); cv.drawCircle(150f, 78f, 30f, p)                 // a sun
            p.color = 0xFF5FC04A.toInt(); cv.drawOval(-60f, 200f, 260f, 380f, p)          // a hill
            p.color = 0xFFFF4F7B.toInt(); cv.drawOval(70f, 130f, 130f, 200f, p)           // a little creature
            p.color = 0xFFFF4F7B.toInt(); cv.drawRect(25f, 22f, 175f, 52f, p)             // a title block
        }
        return androidx.compose.ui.graphics.painter.BitmapPainter(bmp.asImageBitmap())
    }

    private fun kidsTitle() = catalog.titles.first().copy(name = "Moonbeam Meadow", year = "1991", poster = "stand-in", box = null,
        kidsLook = true, shots = listOf(File("a.jpg"), File("b.jpg"), File("c.jpg")),
        colors = BoxColors(androidx.compose.ui.graphics.Color(0xFFFF4F7B), androidx.compose.ui.graphics.Color(0xFF1F5FAE),
            androidx.compose.ui.graphics.Color(0xFFFFD23A), androidx.compose.ui.graphics.Color(0xFF58C7F3), androidx.compose.ui.graphics.Color(0xFF2F8F3A)))
        .let { it.copy(art = Art(null, null, "A shy little creature leaves the meadow for the first time to bring the moon back " +
            "to the night sky, with a band of new friends along for the ride.", "1991", tagline = "The biggest adventure starts with the smallest friend!",
            cast = listOf("Sunny Day", "Pip Holloway", "Marla Brook"), director = "Ann Fields", rating = "G", runtime = 78,
            studio = "Meadowlark Home Video", imdbId = "tt0000001")) }

    /** The 6 kids' looks: fronts at 1.5× shelf size, then each look's matching spine. */
    @Test fun kidsFrontsAndSpines() {
        val standIn = kidsStandIn()
        val t = kidsTitle()
        show {
            androidx.compose.runtime.CompositionLocalProvider(LocalPosterStandIn provides standIn) {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize().background(Ink.Navy).padding(16.px),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.px)) {
                    androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.px)) {
                        for (st in KIDS_LOOKS) androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(129.px, 228.px)) {
                            PosterSleeve(t, colorsOf(t), 1.5f, false, st); Sheen()
                        }
                    }
                    androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.px)) {
                        for (st in KIDS_LOOKS) BoxSpine(t, 54.px, 336.px, dvd = false, style = st)
                        for (st in KIDS_LOOKS) MiniSpineFor(t, st)
                    }
                }
            }
        }
        shot("8-kids-fronts")
    }

    @Composable private fun MiniSpineFor(t: Title, st: SleeveStyle) {
        // the small upright spine follows the movie's own look, so give each one a key that lands on [st]
        val k = (0 until 5000).map { "kids:$it" }.first { key -> sleeveStyle(t.copy(key = key)) == st }
        MiniSpine(t.copy(key = k))
    }

    /** Each kids' look's back, cycling through the three kids' back layouts. */
    @Test fun kidsBacks() {
        val t = kidsTitle()
        show {
            androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.fillMaxSize().background(Ink.Navy).padding(16.px),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.px)) {
                KIDS_LOOKS.forEachIndexed { i, st ->
                    BoxBack(t, "1991 VHS release", androidx.compose.ui.Modifier.size(190.px, 336.px), style = st, kidsLayout = i % 3)
                }
            }
        }
        shot("8-kids-backs")
    }

    /** Made-up boxes with their shelf wear: fronts, spines and backs of different movies, so no two match. */
    @Test fun worn() {
        val bmp = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888).apply {
            val cv = Canvas(this); val p = android.graphics.Paint()
            p.shader = android.graphics.LinearGradient(0f, 0f, 0f, 300f, intArrayOf(0xFF2B1A4A.toInt(), 0xFFB0342C.toInt(),
                0xFF1A1018.toInt()), floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP)
            cv.drawRect(0f, 0f, 200f, 300f, p); p.shader = null
            p.color = 0xFFF2C94C.toInt(); cv.drawRect(20f, 230f, 180f, 275f, p)
            p.color = 0xFF120C10.toInt(); cv.drawOval(55f, 60f, 145f, 230f, p)
        }
        val standIn = androidx.compose.ui.graphics.painter.BitmapPainter(bmp.asImageBitmap())
        val base = catalog.titles.first().copy(name = "Night of the Creeps", year = "1986", poster = "stand-in", box = null,
            colors = BoxColors(androidx.compose.ui.graphics.Color(0xFF8E2A2A), androidx.compose.ui.graphics.Color(0xFF1A1016),
                androidx.compose.ui.graphics.Color(0xFFF2C94C), androidx.compose.ui.graphics.Color(0xFF2B1A4A), androidx.compose.ui.graphics.Color(0xFF1A1018)))
            .let { it.copy(art = Art(null, null, "A college campus is invaded by alien slugs.", "1986", rating = "R", runtime = 88,
                studio = "TriStar Pictures", imdbId = "tt0091630")) }
        val boxes = (0 until 8).map { i -> base.copy(key = "tmdb:${1000 + i * 7919}") }
        show {
            androidx.compose.runtime.CompositionLocalProvider(LocalPosterStandIn provides standIn) {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize().background(Ink.Navy).padding(16.px),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.px)) {
                    androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.px)) {
                        for (t in boxes) BoxFront(t, 1.5f, androidx.compose.ui.Modifier.size(129.px, 228.px), dvd = false)
                    }
                    androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.px)) {
                        for (t in boxes) BoxSpine(t, 54.px, 336.px, dvd = false)
                        for (t in boxes.take(3)) BoxBack(t, "1986 VHS release", androidx.compose.ui.Modifier.size(190.px, 336.px), layout = 2)
                    }
                }
            }
        }
        shot("7-worn")
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

    /** The VCR deck over a movie; [menu] opens one of its on-screen menus. */
    private fun player(name: String, menu: VcrMenu?) {
        val s = VcrState("Night of the Creeps").apply {
            loading = false; positionMs = 2_537_000; durationMs = 5_280_000; aspect = Aspect.Four3
            audio = listOf(TrackChoice(0, "ENGLISH", "STEREO", true))
            this.menu = menu
        }
        show {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(androidx.compose.ui.graphics.Color(0xFF2B1446), androidx.compose.ui.graphics.Color(0xFFE0703A))))) {
                VcrOverlay(s, VcrActions())
            }
        }
        shot(name)
    }

    @Test fun player() = player("5-player", null)
    @Test fun playerAspectMenu() = player("5-player-aspect", VcrMenu.Aspect)

    // The loading animations (Loading.kt)
    @Test fun loadingRewind() { show { LoadingScreen(LoadingLook.Rewind) }; shot("6-loading-rewind") }
    @Test fun loadingDeck() { show { LoadingScreen(LoadingLook.Deck) }; shot("6-loading-deck") }
    @Test fun loadingShelves() { show { ShelvesLoading() }; shot("6-loading-shelves") }
    @Test fun buffering() {
        show {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(androidx.compose.ui.graphics.Color(0xFF2B1446), androidx.compose.ui.graphics.Color(0xFFE0703A))))) {
                VcrOverlay(VcrState("Night of the Creeps").apply { visible = false }, VcrActions())
                BufferingTape()
            }
        }
        shot("6-buffering")
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
