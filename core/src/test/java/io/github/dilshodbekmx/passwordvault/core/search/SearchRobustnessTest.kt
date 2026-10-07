package io.github.dilshodbekmx.passwordvault.core.search

import io.github.dilshodbekmx.passwordvault.core.text.UzbekScript
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Random text in every script search meets, with apostrophes, the 2026 letters, combining marks,
 * joiners and emoji (fixed seed), searched with random queries, with pieces of the title and with
 * pieces of it typed on the other layout: nothing throws, the bold ranges lie inside the text
 * without splitting a character, a title that is found has bold text, and a query that finds
 * something is never retried on the other layout.
 */
class SearchRobustnessTest {

    private val pools: List<List<Int>> = listOf(
        ('a'..'z').map { it.code } + ('A'..'Z').map { it.code },
        ('0'..'9').map { it.code },
        " .,-'`@+_/".map { it.code },
        listOf(0x02BB, 0x02BC, 0x2018, 0x2019, 0x00B4), // apostrophes
        "абвгдеёжзийклмнопрстуфхцчшщъыьэюяЁЙЦУКЕН".map { it.code },
        "ўқғҳЎҚҒҲ".map { it.code },
        "şçğöüŞÇĞÖÜİıșȘ".map { it.code },
        listOf(0x0301, 0x0306, 0x0327, 0x200D, 0x200B, 0xFE0F, 0x00AD), // marks, joiners, invisible
        listOf(0x1F511, 0x1F468, 0x1F469, 0x2764), // emoji, two of them outside the BMP
        "微信银行بنك".map { it.code },
    )

    private fun text(r: Random, maxLength: Int): String {
        val sb = StringBuilder()
        repeat(r.nextInt(maxLength + 1)) {
            val pool = pools[r.nextInt(pools.size)]
            sb.appendCodePoint(pool[r.nextInt(pool.size)])
        }
        return sb.toString()
    }

    private fun splitsACharacter(s: String, index: Int): Boolean =
        index in 1 until s.length && Character.isHighSurrogate(s[index - 1]) && Character.isLowSurrogate(s[index])

    @Test
    fun randomTextAndQueries() {
        val r = Random(2026)
        var found = 0
        var retried = 0
        repeat(3000) {
            val title = text(r, 24)
            val piece = title.substring(r.nextInt(title.length + 1)).let { it.take(1 + r.nextInt(8)) }
            val query = when (r.nextInt(4)) {
                0 -> text(r, 6)
                1 -> KeyboardLayout.otherLayout(piece)
                else -> piece
            }
            val index = SearchIndex(listOf(SearchFields(title, text(r, 12), listOf(text(r, 12)), listOf(text(r, 8)), text(r, 30))))
            val hits = index.search(query)
            assertTrue(hits.all { it.index == 0 })

            val ranges = matchedRanges(title, query)
            var end = -1
            for (range in ranges) {
                assertTrue("[$title] [$query] $ranges", range.first > end && range.last < title.length && range.first <= range.last)
                assertFalse("[$title] [$query] $ranges", splitsACharacter(title, range.first) || splitsACharacter(title, range.last + 1))
                end = range.last + 1 // merged ranges never touch
            }
            val titleOnly = SearchIndex(listOf(SearchFields(title))).search(query)
            val hasWord = searchKey(query).split(' ').any { word -> word.any { Character.isLetterOrDigit(it) } }
            if (titleOnly.isNotEmpty() && hasWord) assertTrue("[$title] [$query]", ranges.isNotEmpty())

            val other = KeyboardLayout.otherLayout(query)
            assertEquals(query.length, other.length)
            val outcome = index.searchOrRetry(query)
            if (hits.isNotEmpty()) assertEquals(SearchOutcome(query, hits, retried = false), outcome)
            if (outcome.retried) assertEquals(other, outcome.query)
            if (titleOnly.isNotEmpty() && hasWord) found++
            if (outcome.retried) retried++

            UzbekScript.cyrillicToLatin(title)
            UzbekScript.latinToCyrillic(title)
        }
        // With this seed 1,086 queries are found by the title and 156 retried: the checks above ran.
        assertTrue("found by the title: $found", found >= 500)
        assertTrue("retried: $retried", retried >= 50)
    }
}
