package io.github.dilshodbekmx.passwordvault.ui.common

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.style.Hyphens

/** The layout of the text that the `Text` node [node] draws. */
fun textLayoutOf(node: SemanticsNode): TextLayoutResult {
    val results = mutableListOf<TextLayoutResult>()
    checkNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action) { "no text layout: $node" }.invoke(results)
    return results.single()
}

/**
 * The lines of [laidOut], other than its last, that are drawn wider than their own characters: the
 * characters of each line, measured alone in the same style, take less room than the line, so the
 * line ends with a glyph that is not in the text (the hyphen that hyphenation draws). A value the
 * owner reads to type it elsewhere, a password or a user name, must have none (build plan 8.1's
 * review: the theme hyphenates every language since 8.1).
 */
fun linesWithAGlyphNotInTheText(laidOut: TextLayoutResult): List<String> {
    val input = laidOut.layoutInput
    val measurer = TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection)
    val plain = input.style.copy(hyphens = Hyphens.None)
    fun width(text: String): Float = measurer.measure(text, plain, softWrap = false, maxLines = 1).let { it.getLineRight(0) - it.getLineLeft(0) }
    val hyphen = width("-")
    val text = input.text.text
    return (0 until laidOut.lineCount - 1).mapNotNull { line ->
        val start = laidOut.getLineStart(line)
        val end = laidOut.getLineEnd(line, visibleEnd = true)
        val own = width(text.substring(start, end))
        val drawn = laidOut.getLineRight(line) - laidOut.getLineLeft(line)
        if (drawn > own + hyphen / 2) {
            "line ${line + 1}, \"${text.substring(start, end)}\", is drawn ${drawn.toInt()} px wide, its characters take ${own.toInt()} px"
        } else {
            null
        }
    }
}
