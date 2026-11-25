package org.vhsfever.tv

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
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
) {
    BoxWithConstraints(modifier) {
        val measurer = rememberTextMeasurer()
        val maxW = constraints.maxWidth
        val maxH = if (constraints.hasBoundedHeight) constraints.maxHeight else Constraints.Infinity
        val scale = remember(text, style, maxW, maxH, maxLines) {
            var s = 1f
            while (s > minScale) {
                val r = measurer.measure(text, scaled(style, s), overflow = TextOverflow.Clip, softWrap = maxLines > 1,
                    maxLines = maxLines, constraints = Constraints(maxWidth = maxW, maxHeight = maxH))
                if (!r.hasVisualOverflow) break
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
    fun TextUnit.times(k: Float) = if (isSpecified) (value * k).sp else this
    return st.copy(fontSize = st.fontSize.times(s), lineHeight = st.lineHeight.times(s), letterSpacing = st.letterSpacing.times(s))
}
