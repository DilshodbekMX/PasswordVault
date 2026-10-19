package io.github.dilshodbekmx.passwordvault.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A text with a button at its end (i18n.md §4.5 rule 1: a row of a text and a button becomes a stack
 * when they do not fit): side by side while the text keeps at least half the width (and [minText]),
 * the button vertically centred; else the text takes the whole width and the button goes under it, at
 * the end. A long button label in Uzbek or Russian, or the phone's font at 200 %, would otherwise
 * squeeze the text to a column of single letters (build plan 6.2 found the clipboard row, whose
 * "Clear now" left its text no width at all, grown taller than the screen). Start and end follow the
 * language (ux.md §9): right to left, the text is at the right and the button at the left.
 */
@Composable
fun TextBeside(
    text: @Composable () -> Unit,
    action: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    gap: Dp = 8.dp,
    minText: Dp = 120.dp,
) {
    Layout(contents = listOf(text, action ?: {}), modifier = modifier) { (texts, actions), constraints ->
        val width = constraints.maxWidth
        val loose = Constraints(maxWidth = width)
        val button = actions.firstOrNull()?.measure(loose)?.takeIf { it.width > 0 }
        val besideWidth = width - (button?.let { it.width + gap.roundToPx() } ?: 0)
        val beside = button == null || besideWidth >= maxOf(width / 2, minText.roundToPx())
        val words = texts.map { it.measure(loose.copy(maxWidth = if (beside) besideWidth.coerceAtLeast(0) else width)) }
        val textHeight = words.maxOfOrNull { it.height } ?: 0
        // Placed relative to the layout direction: right to left, the start is the right.
        if (beside) {
            val height = maxOf(textHeight, button?.height ?: 0)
            layout(width, height) {
                words.forEach { it.placeRelative(0, (height - it.height) / 2) }
                button?.placeRelative(width - button.width, (height - button.height) / 2)
            }
        } else {
            val height = textHeight + button!!.height
            layout(width, height) {
                words.forEach { it.placeRelative(0, 0) }
                button.placeRelative(width - button.width, textHeight)
            }
        }
    }
}
