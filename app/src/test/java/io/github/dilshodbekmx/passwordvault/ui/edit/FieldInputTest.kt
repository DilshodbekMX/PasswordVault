package io.github.dilshodbekmx.passwordvault.ui.edit

import androidx.compose.foundation.text.input.TextFieldState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Build plan 3.6, [JVM]: what typing and pasting may put into a field of the form (ux.md §2,
 * §5.14), the rule under the counters: the limit in code points, never half a surrogate pair,
 * control characters refused on input, and text that was there before left alone.
 */
class FieldInputTest {

    /** Replaces [start, end) of [state] with [text] as the keyboard or a paste would, through [input]. */
    private fun change(state: TextFieldState, input: FieldInput, start: Int, end: Int, text: String) {
        state.edit {
            replace(start, end, text)
            with(input) { transformInput() }
        }
    }

    @Test
    fun anInsertionInTheMiddleKeepsWhatFits() {
        val state = TextFieldState("abcdef")
        change(state, FieldInput(8, lineBreaks = false), 3, 3, "XYZ")
        assertEquals("abcXYdef", state.text.toString())
    }

    @Test
    fun aReplacedSelectionMayGrowUpToTheLimit() {
        val state = TextFieldState("abcdef")
        change(state, FieldInput(7, lineBreaks = false), 1, 3, "1234")
        assertEquals("\"bc\" became as much of \"1234\" as fits in 7", "a123def", state.text.toString())
    }

    @Test
    fun typingAtTheLimitAddsNothingAndDeletingAlwaysWorks() {
        val input = FieldInput(3, lineBreaks = false)
        val state = TextFieldState("abc")
        change(state, input, 3, 3, "d")
        assertEquals("abc", state.text.toString())
        change(state, input, 1, 2, "")
        assertEquals("ac", state.text.toString())
    }

    @Test
    fun aFieldAlreadyOverItsLimitMayShrinkButNotGrow() {
        val input = FieldInput(3, lineBreaks = false)
        val state = TextFieldState("abcdef")
        change(state, input, 6, 6, "g")
        assertEquals("abcdef", state.text.toString())
        change(state, input, 0, 1, "")
        assertEquals("bcdef", state.text.toString())
        change(state, input, 0, 1, "XY")
        assertEquals("a change that keeps the length is allowed", "Xcdef", state.text.toString())
    }

    @Test
    fun theLimitCountsCodePointsAndNeverSplitsASurrogatePair() {
        val input = FieldInput(3, lineBreaks = false)
        val smile = "😀"
        val state = TextFieldState("ab")
        change(state, input, 2, 2, smile + smile)
        assertEquals("ab$smile", state.text.toString())
        val full = TextFieldState("$smile$smile")
        change(full, input, 2, 2, "x$smile")
        assertEquals("${smile}x$smile", full.text.toString())
    }

    @Test
    fun controlCharactersOfWhatWasTypedAreDropped() {
        val oneLine = FieldInput(100, lineBreaks = false)
        val state = TextFieldState("")
        change(state, oneLine, 0, 0, "a\tb\u0000c\u007fd\ne\r\nf")
        assertEquals("abcdef", state.text.toString())
        val multi = FieldInput(100, lineBreaks = true)
        val notes = TextFieldState("")
        change(notes, multi, 0, 0, "1\r\n2\r3\n4\u001b")
        assertEquals("1\n2\n3\n4", notes.text.toString())
    }

    @Test
    fun aControlCharacterThatWasAlreadyThereStays() {
        val state = TextFieldState("pass\tword")
        change(state, FieldInput(100, lineBreaks = false), 9, 9, "!")
        assertEquals("pass\tword!", state.text.toString())
    }

    @Test
    fun controlCharactersDoNotCountAgainstTheLimit() {
        val state = TextFieldState("")
        change(state, FieldInput(3, lineBreaks = false), 0, 0, "\t\ta\tb\tc\td")
        assertEquals("abc", state.text.toString())
    }

    @Test
    fun theCounterShowsFromNineTenthsOfTheLimit() {
        assertNull(Counter.of("a".repeat(89), 100))
        assertEquals(Counter(90, 100), Counter.of("a".repeat(90), 100))
        assertEquals(Counter(100, 100), Counter.of("a".repeat(100), 100))
        assertEquals("code points", Counter(90, 100), Counter.of("😀".repeat(90), 100))
    }
}
