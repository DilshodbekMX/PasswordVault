package io.github.dilshodbekmx.passwordvault.ui.edit

import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import io.github.dilshodbekmx.passwordvault.core.model.EntryRules

/**
 * What typing and pasting may put into one field of the form (ux.md §2, §5.14), as the field's
 * `InputTransformation`:
 *
 * - **Control characters are refused on input** (ux.md §2): U+0000 to U+001F and U+007F that were
 *   just typed or pasted are dropped. Where [lineBreaks] are allowed (notes, an extra field's value),
 *   a line feed stays and a carriage return becomes one (Windows text); elsewhere both are dropped.
 *   Text that was already in the field is left as it is, so a stored password is never changed
 *   behind the user's back: Save reports it instead (`EntryRules.problems`).
 * - **Long input stops at the limit** (ux.md §5.14): at most [max] Unicode code points, counted as
 *   `EntryRules` counts them (an emoji is one). Of what was just typed or pasted, as much is kept as
 *   fits, and never half of a surrogate pair. A field that already held more (an entry from an
 *   import) may shrink but not grow.
 *
 * What was "just typed or pasted" is the part of the new text between the longest common start and
 * end it shares with the text before the change; everything outside it was there before.
 */
class FieldInput(val max: Int, val lineBreaks: Boolean) : InputTransformation {
    init {
        require(max > 0) { "a field holds at least one character" }
    }

    override fun TextFieldBuffer.transformInput() {
        val original = originalText
        val text = asCharSequence()
        // The changed part of the new text: [start, end).
        var start = 0
        val shortest = minOf(text.length, original.length)
        while (start < shortest && text[start] == original[start]) start++
        var tail = 0
        while (tail < shortest - start && text[text.length - 1 - tail] == original[original.length - 1 - tail]) tail++
        var end = text.length - tail
        // Never split a surrogate pair at either edge.
        if (start in 1 until text.length && Character.isLowSurrogate(text[start]) && Character.isHighSurrogate(text[start - 1])) start--
        if (end in 1 until text.length && Character.isLowSurrogate(text[end]) && Character.isHighSurrogate(text[end - 1])) end++
        if (start >= end) return

        end = dropControls(this, start, end)

        val count = EntryRules.length(asCharSequence())
        val allowed = maxOf(max, EntryRules.length(original))
        if (count <= allowed) return
        var excess = count - allowed
        var cut = end
        val now = asCharSequence()
        while (excess > 0 && cut > start) {
            cut = Character.offsetByCodePoints(now, cut, -1).coerceAtLeast(start)
            excess--
        }
        if (excess > 0) {
            revertAllChanges()
            return
        }
        replace(cut, end, "")
    }

    /** Drops the control characters of [start, end) in [buffer]; returns the new end of that range. */
    private fun dropControls(buffer: TextFieldBuffer, start: Int, end: Int): Int {
        var stop = end
        var i = end - 1
        while (i >= start) {
            val c = buffer.charAt(i)
            when {
                c == '\n' -> if (!lineBreaks) {
                    buffer.replace(i, i + 1, "")
                    stop--
                }
                c == '\r' -> if (lineBreaks && (i + 1 >= buffer.length || buffer.charAt(i + 1) != '\n')) {
                    buffer.replace(i, i + 1, "\n")
                } else {
                    buffer.replace(i, i + 1, "")
                    stop--
                }
                c < ' ' || c == '\u007f' -> {
                    buffer.replace(i, i + 1, "")
                    stop--
                }
            }
            i--
        }
        return stop
    }
}

/**
 * The counter under a field that is nearly full ("10,000 / 10,000", ux.md §5.14): [count] code points
 * of [max]. The screen formats both numbers for the language.
 */
data class Counter(val count: Int, val max: Int) {
    val full: Boolean get() = count >= max

    companion object {
        /**
         * The counter of a field holding [text] with the limit [max]: shown from nine tenths of the
         * limit on, so that the stop at the limit never comes as a surprise; null below.
         */
        fun of(text: CharSequence, max: Int): Counter? {
            val count = EntryRules.length(text)
            return if (count * 10L >= max * 9L) Counter(count, max) else null
        }
    }
}
