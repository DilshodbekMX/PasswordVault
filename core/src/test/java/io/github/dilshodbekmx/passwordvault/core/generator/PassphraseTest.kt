package io.github.dilshodbekmx.passwordvault.core.generator

import io.github.dilshodbekmx.passwordvault.core.crypto.MasterPasswordBytes
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import kotlin.math.log2
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The suggested master password of ux.md §5.3 (architecture.md D-6a, D-6b): 6 words from 1,295. */
class PassphraseTest {

    private val list = Wordlist.effShort

    @Test
    fun sixWordsFrom1295InLowerCaseJoinedByHyphens() {
        val random = FakeRandom(26)
        repeat(10_000) {
            val passphrase = Passphrase.suggest(random)
            assertEquals(6, Passphrase.WORDS)
            assertEquals(Passphrase.WORDS, passphrase.words.size)
            for (word in passphrase.words) assertTrue("a word of the list", word in list)
            assertEquals(passphrase.words.joinToString("-"), String(passphrase.toCharArray()))
        }
        assertEquals('-', Passphrase.SEPARATOR)
    }

    @Test
    fun sixWordsFrom1295Make62Bits() {
        assertEquals(6 * log2(1_295.0), Passphrase.BITS, 1e-12)
        assertEquals(62.03, Passphrase.BITS, 0.005)
    }

    @Test
    fun drawsOneIntOf1295PerWordInOrder() {
        val random = RecordingRandom(FakeRandom().queueInts(0, 1_294, 567, 1, 2, 3))
        val passphrase = Passphrase.suggest(random)
        assertEquals(listOf("acid", "zoom", "icing", "acorn", "acre", "acts"), passphrase.words)
        assertEquals("acid-zoom-icing-acorn-acre-acts", String(passphrase.toCharArray()))
        assertEquals(List(6) { 1_295 }, random.bounds)
    }

    /** architecture.md §8.1, "unbiased choice over 10⁶ draws", for the six words of setup. */
    @Test
    fun unbiasedChoiceOverAMillionWordsOfSuggestions() {
        val random = FakeRandom(1_000_003)
        val counts = IntArray(list.size)
        val byPlace = IntArray(Passphrase.WORDS * list.size)
        repeat(166_667) {
            val words = Passphrase.suggest(random).words
            for ((place, word) in words.withIndex()) {
                val index = list.indexOf(word)
                counts[index]++
                byPlace[place * list.size + index]++
            }
        }
        assertEquals(1_000_002, counts.sum())
        ChiSquare.assertUniform("the words of a million draws", counts)
        ChiSquare.assertUniform("the words in each of the six places", byPlace)
    }

    @Test
    fun theMasterPasswordByteRuleKeepsItAsItIs() {
        // No white space, no apostrophe, nothing NFC changes: the bytes are the ASCII of the text.
        val random = FakeRandom(43)
        repeat(1_000) {
            val chars = Passphrase.suggest(random).toCharArray()
            assertArrayEquals(String(chars).toByteArray(Charsets.US_ASCII), MasterPasswordBytes.of(chars))
            assertNull(MasterPasswordBytes.firstUnassigned(chars))
        }
    }

    @Test
    fun eachCharArrayIsNewSoTheCallerCanWipeIt() {
        val passphrase = Passphrase.suggest(FakeRandom(8))
        val first = passphrase.toCharArray()
        val text = String(first)
        first.fill('\u0000')
        assertEquals(text, String(passphrase.toCharArray()))
    }

    @Test
    fun toStringNamesNoWord() {
        val passphrase = Passphrase.suggest(FakeRandom(9))
        // A fixed text: "word" is itself in the list, so the test does not look for each word.
        assertEquals("Passphrase(6 words, hidden)", passphrase.toString())
        assertFalse(String(passphrase.toCharArray()) in passphrase.toString())
    }
}
