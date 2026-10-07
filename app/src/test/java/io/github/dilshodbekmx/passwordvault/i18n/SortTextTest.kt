package io.github.dilshodbekmx.passwordvault.i18n

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Build plan 2.9, [JVM]: the sort text of i18n.md §2.7, which `SortKeys` feeds to the collator and
 * to the letter index. The order itself comes from Android's ICU and is tested on a device
 * (`app/src/androidTest/.../i18n/SortKeysTest`).
 */
class SortTextTest {
    @Test
    fun everyApostropheAfterOOrGBecomesTheTurnedComma() {
        for (mark in listOf("'", "‘", "’", "`", "ʼ", "ʻ")) {
            assertEquals("Oʻzbekiston", sortTextOf("O${mark}zbekiston"))
            assertEquals("oʻzbek", sortTextOf("o${mark}zbek"))
            assertEquals("Gʻalaba", sortTextOf("G${mark}alaba"))
            assertEquals("gʻisht", sortTextOf("g${mark}isht"))
        }
        // The three spellings of i18n.md §2.7 sort together once the mark is the same.
        assertEquals(
            listOf("Oʻzbekiston pochtasi", "Oʻzbekneftgaz", "Oʻzbekiston banki"),
            listOf("O'zbekiston pochtasi", "O‘zbekneftgaz", "Oʻzbekiston banki").map(::sortTextOf),
        )
    }

    @Test
    fun nothingElseChanges() {
        // The tutuq belgisi after another letter, other apostrophes, other scripts.
        for (title in listOf("ma'lumot", "maʼlumot", "McDonald's", "Rock ’n’ roll", "Ўзбекистон", "Gmail", "", "'quoted'", "ogoh")) {
            assertEquals(title, sortTextOf(title))
        }
        assertEquals("Doʻstlar va gʻoyalar", sortTextOf("Do'stlar va g`oyalar"))
        assertEquals("the ´ is not one of the marks", "O´zbek", sortTextOf("O´zbek"))
    }
}
