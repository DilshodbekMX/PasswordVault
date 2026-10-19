package io.github.dilshodbekmx.passwordvault.ui.common

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified

/**
 * A short label in a narrow place (a tab of the bottom bar) whose words are never broken: as large
 * as [style] makes it while every word fits on a line and the whole label in [maxLines] lines, and
 * smaller only as far as it must, down to the size it has with the phone's font at 100 %. i18n.md
 * §4.5 rule 5: the bar's three labels must fit at 320 dp and 200 % in every language ("Xavfsizlik",
 * "Безопасность"); a label that still does not fit there wraps whole words onto more lines rather
 * than being cut.
 *
 * With [cut], a hint that a field shows inside its own fixed height (the list's search field): a
 * label that does not fit in [maxLines] lines even at its smallest is cut there with an ellipsis,
 * and marked [MayTruncate] (what it says is a hint; TalkBack reads it whole).
 *
 * [alongside]: the labels this one stands in a row with, each as wide as it (the bar's other tabs):
 * all of them take the size the one that needs the smallest gets, so that the row's labels match.
 */
@Composable
fun FittedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    textAlign: TextAlign? = null,
    maxLines: Int = 2,
    cut: Boolean = false,
    alongside: List<String> = emptyList(),
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val size = remember(text, alongside, style, width, density, direction, cut) {
            (listOf(text) + alongside).map { label ->
                fittingSize(label, style, width, maxLines, density.fontScale, cut) { candidate, lines ->
                    val shown = style.copy(fontSize = candidate)
                    val words = label.split(' ', ' ').filter { it.isNotEmpty() }
                    val wordsFit = words.all { word ->
                        measurer.measure(word, shown, maxLines = 1, softWrap = false, density = density, layoutDirection = direction).size.width <= width
                    }
                    val all = measurer.measure(label, shown, constraints = Constraints(maxWidth = width), density = density, layoutDirection = direction)
                    wordsFit to (all.lineCount <= lines)
                }
            }.minBy { it.value }
        }
        Text(
            text = text,
            style = style.copy(fontSize = size),
            textAlign = textAlign,
            maxLines = if (cut) maxLines else Int.MAX_VALUE,
            overflow = if (cut) TextOverflow.Ellipsis else TextOverflow.Clip,
            modifier = if (cut) Modifier.mayTruncate() else Modifier,
        )
    }
}

/**
 * The size for [text]: the largest of [style]'s size and the steps below it, to the size the text has
 * at a font scale of 1, whose words fit and whose lines are at most [maxLines] ([fit] answers both);
 * else, unless the text is [cut] at [maxLines], the largest whose words fit; else the smallest step.
 */
private fun fittingSize(
    text: String,
    style: TextStyle,
    width: Int,
    maxLines: Int,
    fontScale: Float,
    cut: Boolean,
    fit: (TextUnit, Int) -> Pair<Boolean, Boolean>,
): TextUnit {
    val base = style.fontSize
    if (!base.isSpecified || width == Constraints.Infinity || text.isEmpty()) return base
    val smallest = (1f / fontScale).coerceIn(MIN_FACTOR, 1f)
    val steps = generateSequence(1f) { it - STEP }.takeWhile { it >= smallest - 0.001f }.toList().ifEmpty { listOf(1f) }
    var wordsOnly: TextUnit? = null
    for (factor in steps) {
        val candidate = base * factor
        val (wordsFit, linesFit) = fit(candidate, maxLines)
        if (wordsFit && linesFit) return candidate
        if (wordsFit && wordsOnly == null) wordsOnly = candidate
    }
    return wordsOnly?.takeUnless { cut } ?: base * steps.last()
}

private const val STEP = 0.05f
private const val MIN_FACTOR = 0.5f
