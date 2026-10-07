package io.github.dilshodbekmx.passwordvault.core.util

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TextHygieneTest {
    @Test
    fun controlCharactersAreU0000ToU001FAndU007F() {
        for (code in 0..0xFFFF) {
            assertEquals("U+%04X".format(java.util.Locale.ROOT, code), code < 0x20 || code == 0x7F, TextHygiene.isControl(code.toChar()))
        }
    }

    @Test
    fun firstControlAllowsALineFeedOnlyWhereLineBreaksAre() {
        assertEquals(-1, TextHygiene.firstControl("Oʻzbekiston 🔑 «пароль»", lineBreaks = false))
        assertEquals(4, TextHygiene.firstControl("line\nbreak", lineBreaks = false))
        assertEquals(-1, TextHygiene.firstControl("line\nbreak", lineBreaks = true))
        assertEquals(4, TextHygiene.firstControl("line\r\nbreak", lineBreaks = true)) // CR is a control character
        assertEquals(3, TextHygiene.firstControl("tab\there", lineBreaks = true))
        assertEquals(1, TextHygiene.firstControl("a\u007Fb", lineBreaks = true))
        assertEquals(0, TextHygiene.firstControl("\u0000", lineBreaks = true))
        // Not control characters by ux.md §2: C1 controls, NEL, the Unicode line separators, NBSP.
        assertEquals(-1, TextHygiene.firstControl("\u0080\u0085\u009F\u2028\u2029\u00A0", lineBreaks = false))
    }

    @Test
    fun unpairedSurrogatesAreFoundWhereverTheyAre() {
        val cases = mapOf(
            "" to -1,
            "abc" to -1,
            "🔑" to -1,
            "a🔑b🙂" to -1,
            "\uD800" to 0,
            "a\uDC00" to 1,
            "🙂\uD800x" to 2,
            "\uDE00\uD83D" to 0, // a pair in the wrong order is two unpaired surrogates
            "x\uD83D" to 1, // a high surrogate at the end
            "\uD83D😀" to 0, // a high surrogate followed by a pair
            "😀\uDE00" to 2, // a pair followed by a low surrogate
        )
        for ((s, index) in cases) {
            assertEquals(escape(s), index, TextHygiene.firstUnpairedSurrogate(s))
            assertEquals(escape(s), index >= 0, TextHygiene.hasUnpairedSurrogate(s))
        }
    }

    @Test
    fun isCleanIsTheInputRule() {
        assertTrue(TextHygiene.isClean("Gmail – Oʻzbekiston Ўзбекистон 🔑", lineBreaks = false))
        assertTrue(TextHygiene.isClean("first line\nsecond line", lineBreaks = true))
        assertFalse(TextHygiene.isClean("first line\nsecond line", lineBreaks = false))
        assertFalse(TextHygiene.isClean("tab\there", lineBreaks = true))
        assertFalse(TextHygiene.isClean("lone \uD800", lineBreaks = true))
    }

    @Test
    fun replaceUnpairedSurrogatesReplacesEachOneAndCountsIt() {
        val counter = TextHygiene.Counter()
        assertEquals("T\uFFFDx", TextHygiene.replaceUnpairedSurrogates("T\uD800x", counter))
        assertEquals("n\uFFFD\uFFFDz", TextHygiene.replaceUnpairedSurrogates("n\uDE00\uD83Dz", counter))
        assertEquals("p🔑\uFFFD", TextHygiene.replaceUnpairedSurrogates("p🔑\uD83D", counter))
        assertEquals(4, counter.count)
        assertEquals("\uFFFD🙂\uFFFD", TextHygiene.replaceUnpairedSurrogates("\uDC00🙂\uD800", counter))
        assertEquals(6, counter.count)
    }

    @Test
    fun cleanTextIsReturnedAsItIs() {
        val s = "Oʻzbek 🔑 parol"
        val counter = TextHygiene.Counter()
        assertSame(s, TextHygiene.replaceUnpairedSurrogates(s, counter))
        assertSame(s, TextHygiene.removeControls(s, lineBreaks = false, counter = counter))
        assertSame(s, TextHygiene.normalizeLineBreaks(s))
        assertEquals(0, counter.count)
    }

    @Test
    fun lineBreaksBecomeLineFeeds() {
        assertEquals("a\nb\nc\nd", TextHygiene.normalizeLineBreaks("a\r\nb\rc\nd"))
        assertEquals("\n\n\n", TextHygiene.normalizeLineBreaks("\r\r\n\n"))
    }

    @Test
    fun removeControlsKeepsLineBreaksWhereTheyAreAllowedAndCountsTheRest() {
        val counter = TextHygiene.Counter()
        assertEquals("title", TextHygiene.removeControls("ti\u0001tle\t", lineBreaks = false, counter = counter))
        assertEquals(2, counter.count)
        assertEquals("a\nb\nc", TextHygiene.removeControls("a\r\nb\u0007\rc", lineBreaks = true, counter = counter))
        assertEquals(3, counter.count) // the bell; the line breaks were made LF, not removed
        assertEquals("ab", TextHygiene.removeControls("a\r\nb", lineBreaks = false, counter = counter))
        assertEquals(5, counter.count)
        // Unpaired surrogates are the writers' rule, not this one.
        assertEquals("x\uD800", TextHygiene.removeControls("x\uD800", lineBreaks = false))
    }

    @Test
    fun randomTextAgreesWithAModelOfEachRule() {
        val random = Random(20261007)
        val alphabet = charArrayOf('a', 'ʻ', 'Ж', ' ', '\t', '\n', '\r', '\u0000', '\u001F', '\u007F', '\u0080', '\uD83D', '\uDE00', '\uD800', '\uDFFF', '\uFFFD')
        repeat(20_000) {
            val s = String(CharArray(random.nextInt(12)) { alphabet[random.nextInt(alphabet.size)] })
            // A low surrogate is paired exactly when a high one precedes it; a high one, when a low one follows.
            fun unpaired(i: Int) = when {
                Character.isHighSurrogate(s[i]) -> !(i + 1 < s.length && Character.isLowSurrogate(s[i + 1]))
                Character.isLowSurrogate(s[i]) -> !(i > 0 && Character.isHighSurrogate(s[i - 1]))
                else -> false
            }
            val lone = s.indices.filter { unpaired(it) }
            assertEquals(escape(s), lone.firstOrNull() ?: -1, TextHygiene.firstUnpairedSurrogate(s))
            val counter = TextHygiene.Counter()
            val replaced = TextHygiene.replaceUnpairedSurrogates(s, counter)
            assertEquals(escape(s), lone.size, counter.count)
            assertEquals(escape(s), String(CharArray(s.length) { if (it in lone) '\uFFFD' else s[it] }), replaced)
            assertFalse(TextHygiene.hasUnpairedSurrogate(replaced))
            for (lineBreaks in listOf(false, true)) {
                val expected = s.indices.firstOrNull { s[it].code < 0x20 && !(lineBreaks && s[it] == '\n') || s[it].code == 0x7F } ?: -1
                assertEquals(escape(s), expected, TextHygiene.firstControl(s, lineBreaks))
                val removed = TextHygiene.removeControls(s, lineBreaks)
                assertEquals(escape(s), -1, TextHygiene.firstControl(removed, lineBreaks))
            }
        }
    }

    private fun escape(s: String) = s.map { if (it.code in 0x21..0x7E) it.toString() else "\\u%04X".format(java.util.Locale.ROOT, it.code) }.joinToString("")
}
