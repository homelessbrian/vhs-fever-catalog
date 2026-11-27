package org.vhsfever.tv

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

/*
 * How the remote moves between the menu on the left and the page:
 *  - Left from anything in the first column of a page goes straight to the menu, onto the page you're on.
 *    (Left is never left to the shelves themselves to work out: they would scroll looking for a box further
 *    left and jump up and down a row instead.)
 *  - Right from the menu goes back to exactly the box you came from, however far down the page you were.
 *    If that box is gone (the page changed), it lands on the page's first box.
 *  - Up from the top of the menu and Down from the bottom of it go nowhere, so they never jump into the page.
 */
class StoreNav {
    /** The menu's button for the page you're on. */
    val rail = FocusRequester()
    /** What you were last on in the page. */
    var last: FocusRequester? = null
    /** Where a page wants you to land when there's nothing to go back to (its first box). */
    var entry: FocusRequester? = null

    /** Moves from the menu into the page. False when there's nothing there to go to. */
    fun enterPage(): Boolean {
        for (fr in listOfNotNull(last, entry)) if (runCatching { fr.requestFocus() }.isSuccess) return true
        return false
    }
}

val LocalNav = staticCompositionLocalOf { StoreNav() }

/**
 * Marks something on the page you can land on: the menu's Right comes back to it, and when it's on the
 * left edge of the page ([leftEdge]) Left takes you to the menu. [entry] makes it where the page starts.
 */
@Composable
fun Modifier.pageItem(leftEdge: Boolean, entry: Boolean = false, fr: FocusRequester = remember { FocusRequester() }): Modifier {
    val nav = LocalNav.current
    DisposableEffect(fr, entry) {
        if (entry) nav.entry = fr
        onDispose {
            if (nav.last === fr) nav.last = null
            if (nav.entry === fr) nav.entry = null
        }
    }
    return this
        .focusProperties { if (leftEdge) left = nav.rail }
        .focusRequester(fr)
        .onFocusChanged { if (it.isFocused) nav.last = fr }
}

/**
 * A row of boxes that scrolls so the highlighted box always keeps some room ([start] on the left, [end] on the
 * right) from the edge of the screen. Without it the row only scrolls just far enough to show the box, so the
 * last boxes sit right up against the screen's edge.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RowWithEdgeRoom(start: Int, end: Int, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    val spec = remember(d.density, start, end) {
        val s = start * Ui.unit * d.density
        val e = end * Ui.unit * d.density
        object : BringIntoViewSpec {
            override val scrollAnimationSpec = spring<Float>(stiffness = Spring.StiffnessMediumLow)
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
                offset < s -> offset - s
                offset + size > containerSize - e -> offset + size - (containerSize - e)
                else -> 0f
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}
