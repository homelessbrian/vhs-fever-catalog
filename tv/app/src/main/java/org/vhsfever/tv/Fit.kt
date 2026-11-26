package org.vhsfever.tv

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.tv.material3.Text

/**
 * Text that shrinks until it fits its space (in [maxLines] lines, and in the height it is given),
 * instead of wrapping into a clipped second line or being cut off with "…".
 * Only as a last resort, at [minScale] of the size, does it cut off.
 */
@Composable
fun FitText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    minScale: Float = 0.5f,
    textAlign: TextAlign? = null,
    wordsWhole: Boolean = false,   // also shrink until no single word is too wide for a line
    align: Alignment = Alignment.TopStart,
) {
    BoxWithConstraints(modifier, contentAlignment = align) {
        val measurer = rememberTextMeasurer()
        val maxW = constraints.maxWidth
        val maxH = if (constraints.hasBoundedHeight) constraints.maxHeight else Constraints.Infinity
        val scale = remember(text, style, maxW, maxH, maxLines, wordsWhole) {
            var s = 1f
            while (s > minScale) {
                val r = measurer.measure(text, scaled(style, s), overflow = TextOverflow.Clip, softWrap = maxLines > 1,
                    maxLines = maxLines, constraints = Constraints(maxWidth = maxW, maxHeight = maxH))
                val wordTooWide = wordsWhole && text.split(' ').any { w ->
                    measurer.measure(w, scaled(style, s), softWrap = false, maxLines = 1).size.width > maxW
                }
                if (!r.hasVisualOverflow && !wordTooWide) break
                s -= 0.04f
            }
            s.coerceAtLeast(minScale)
        }
        Text(text, style = scaled(style, scale).let { if (textAlign != null) it.copy(textAlign = textAlign) else it },
            maxLines = maxLines, softWrap = maxLines > 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun scaled(st: TextStyle, s: Float): TextStyle {
    if (s == 1f) return st
    // TextUnit has its own times() that throws on "unspecified", so scale by hand
    fun TextUnit.scaleBy(k: Float): TextUnit = if (isSpecified) TextUnit(value * k, type) else this
    return st.copy(fontSize = st.fontSize.scaleBy(s), lineHeight = st.lineHeight.scaleBy(s), letterSpacing = st.letterSpacing.scaleBy(s))
}
