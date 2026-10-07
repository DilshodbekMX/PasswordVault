package io.github.dilshodbekmx.passwordvault.ui.entry

import androidx.compose.ui.unit.dp
import io.github.dilshodbekmx.passwordvault.ui.entry.BigCell.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Build plan 3.7, [JVM]: the boxes of "Show in big letters" (ux.md §5.13): one per character as a
 * person reads and types it, each with its kind, and no box that looks empty.
 */
class BigCellTest {

    private fun kinds(value: String) = BigCell.of(value).map { it.kind }

    private fun texts(value: String) = BigCell.of(value).map { it.text }

    @Test
    fun oneBoxPerCharacterWithItsKind() {
        val cells = BigCell.of("Tr0ub4dor&3-xyz!")
        assertEquals(16, cells.size)
        assertEquals("Tr0ub4dor&3-xyz!", cells.joinToString("") { it.text })
        assertEquals(
            listOf(
                Kind.UPPER, Kind.LOWER, Kind.DIGIT, Kind.LOWER, Kind.LOWER, Kind.DIGIT, Kind.LOWER, Kind.LOWER, Kind.LOWER,
                Kind.SYMBOL, Kind.DIGIT, Kind.SYMBOL, Kind.LOWER, Kind.LOWER, Kind.LOWER, Kind.SYMBOL,
            ),
            cells.map { it.kind },
        )
        assertEquals(emptyList<BigCell>(), BigCell.of(""))
    }

    @Test
    fun lookAlikesAreToldApartByKind() {
        // O and 0, l and 1 and I: the kind (and with it the colour and the spoken word) differs.
        assertEquals(listOf(Kind.UPPER, Kind.DIGIT, Kind.LOWER, Kind.DIGIT, Kind.UPPER, Kind.SYMBOL), kinds("O0l1I|"))
    }

    @Test
    fun uzbekAndRussianLetters() {
        // Oʻzbekiston: o and the turned comma U+02BB are two boxes, as they are two keys; the comma is a letter without a case.
        assertEquals(listOf("O", "ʻ", "z"), texts("Oʻz"))
        assertEquals(listOf(Kind.UPPER, Kind.LETTER, Kind.LOWER), kinds("Oʻz"))
        // Uzbek Cyrillic and Russian.
        assertEquals(listOf(Kind.UPPER, Kind.LOWER, Kind.LOWER, Kind.LOWER, Kind.LOWER, Kind.UPPER, Kind.LOWER), kinds("ЎқғҳёЙй"))
        // The 2026 letters.
        assertEquals(listOf(Kind.UPPER, Kind.LOWER, Kind.UPPER, Kind.LOWER), kinds("ÖğŞç"))
    }

    @Test
    fun aLetterWithACombiningMarkIsOneBox() {
        // и + U+0306 (decomposed й): one shape on the screen, one box, with both code points in it.
        val cells = BigCell.of("йa")
        assertEquals(2, cells.size)
        assertEquals("й", cells[0].text)
        assertEquals(Kind.LOWER, cells[0].kind)
        // A character outside the basic plane (two UTF-16 units) is one box.
        val emoji = BigCell.of("a🔑b")
        assertEquals(listOf("a", "🔑", "b"), emoji.map { it.text })
        assertEquals(Kind.SYMBOL, emoji[1].kind)
    }

    @Test
    fun aSpaceIsABoxThatSaysSo() {
        val cells = BigCell.of("correct horse")
        assertEquals(13, cells.size)
        assertEquals(BigCell("", Kind.SPACE), cells[7])
        // Leading and trailing spaces count: a password is never trimmed (ux.md §2).
        assertEquals(listOf(Kind.SPACE, Kind.LOWER, Kind.SPACE, Kind.SPACE), kinds(" a  "))
    }

    @Test
    fun whatCannotBeSeenShowsItsCode() {
        // A no-break space is not typed with the space bar: it must not pass for a space.
        assertEquals(listOf(BigCell("a", Kind.LOWER), BigCell("U+00A0", Kind.INVISIBLE), BigCell("b", Kind.LOWER)), BigCell.of("a b"))
        assertEquals(BigCell("U+200B", Kind.INVISIBLE), BigCell.of("​x")[0])
        assertEquals(BigCell("U+2009", Kind.INVISIBLE), BigCell.of(" ")[0])
        assertEquals(BigCell("U+0009", Kind.INVISIBLE), BigCell.of("\t")[0])
        assertEquals(BigCell("U+FEFF", Kind.INVISIBLE), BigCell.of("﻿x")[0])
    }

    @Test
    fun aLineBreakIsOneBox() {
        assertEquals(listOf(Kind.LOWER, Kind.LINE_BREAK, Kind.LOWER), kinds("a\nb"))
        assertEquals("a line break written as two characters", listOf(Kind.LOWER, Kind.LINE_BREAK, Kind.LOWER), kinds("a\r\nb"))
        assertEquals(listOf(Kind.LINE_BREAK, Kind.LINE_BREAK), kinds("\n\n"))
        assertEquals(BigCell("", Kind.LINE_BREAK), BigCell.of("\n")[0])
    }

    @Test
    fun digitsOfOtherScripts() {
        assertEquals(listOf(Kind.DIGIT, Kind.DIGIT), kinds("٣7"))
    }

    @Test
    fun toStringShowsNoCharacter() {
        assertEquals("[BigCell(UPPER), BigCell(DIGIT), BigCell(SYMBOL), BigCell(INVISIBLE)]", BigCell.of("T0&\u200B").toString())
    }

    @Test
    fun theLimitIs128() {
        assertEquals(128, BigCell.MAX)
    }

    @Test
    fun rowsOfFourToEight() {
        // A box needs 52 dp at the normal font size; 8 dp between boxes.
        assertEquals("a 360 dp phone, 16 dp at each side", 5, columnsFor(328.dp, 52.dp))
        assertEquals("a 411 dp phone", 6, columnsFor(379.dp, 52.dp))
        assertEquals("a small 320 dp phone", 4, columnsFor(288.dp, 52.dp))
        assertEquals("never more than 8, however wide", 8, columnsFor(608.dp, 52.dp))
        assertEquals(8, columnsFor(2000.dp, 52.dp))
        assertEquals("never fewer than 4, however large the font", 4, columnsFor(328.dp, 104.dp))
        assertEquals(4, columnsFor(100.dp, 52.dp))
        assertEquals(7, columnsFor(412.dp, 52.dp))
    }
}
