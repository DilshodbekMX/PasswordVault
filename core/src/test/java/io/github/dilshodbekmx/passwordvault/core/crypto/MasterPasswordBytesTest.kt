package io.github.dilshodbekmx.passwordvault.core.crypto

import java.text.Normalizer
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `MasterPasswordBytes` (architecture.md §4.6, lock.md §8.1): the vectors of lock.md §4.3 and
 * architecture.md §4.13, the two fold lists, the properties of lock.md T2 over 10,000 random strings,
 * and the refusals at creation (architecture.md §8.1, "crypto").
 */
class MasterPasswordBytesTest {

    private fun of(text: String): ByteArray = MasterPasswordBytes.of(text.toCharArray())
    private fun hexOf(text: String): String = hex(of(text))

    // --- lock.md §4.3 ---

    @Test
    fun lockMd43_everySpellingOfTheUzbekWordGivesTheListedBytes() {
        val spellings = listOf(
            "oʻgʻlim-Toʻra", // U+02BB, the Uzbek letter
            "o‘g‘lim-To‘ra", // U+2018
            "o'g'lim-To'ra", // U+0027
            "o’gʼlim-To`ra", // U+2019, U+02BC, U+0060
            "o´g＇lim-To′ra", // U+00B4, U+FF07, U+2032
            "oʹg`lim-To´ra", // the Greek signs, which NFC turns into U+02B9, U+0060, U+00B4
        )
        for (spelling in spellings) assertEquals(spelling, "6f2767276c696d2d546f277261", hexOf(spelling))
    }

    @Test
    fun lockMd43_theGreekSpellingIsNotFoldedBeforeNfc() {
        // lock.md §4.3: folding before NFC would give these bytes.
        assertNotEquals("6fcab967606c696d2d546fc2b47261", hexOf("oʹg`lim-To´ra"))
    }

    @Test
    fun lockMd43_theDecomposedSpellingGivesTheComposedBytes() {
        val decomposed = "Köz-Ўzbek" // o + U+0308, У + U+0306
        val composed = "Köz-Ўzbek"
        assertEquals("4bc3b67a2dd08e7a62656b", hexOf(decomposed))
        assertEquals("4bc3b67a2dd08e7a62656b", hexOf(composed))
    }

    // --- architecture.md §4.13 ---

    @Test
    fun a413_whiteSpaceGivesTheBytesOfCorrectHorseBatteryStaple() {
        val typed = " correct  horse battery staple "
        val bytes = of(typed)
        assertEquals(28, bytes.size)
        assertEquals("636f727265637420686f727365206261747465727920737461706c65", hex(bytes))
    }

    @Test
    fun a413_greekSignsGiveTheBytesOfU0027() {
        assertEquals("6f2767276c69276d", hexOf("oʹg`li´m"))
        assertEquals(hexOf("o'g'li'm"), hexOf("oʹg`li´m"))
        assertNotEquals("6fcab967606c69c2b46d", hexOf("oʹg`li´m")) // folded before NFC
    }

    @Test
    fun a413_onlyWhiteSpaceGivesZeroBytes() {
        assertEquals(0, of("  \u0009　 ").size)
        assertEquals(0, of("").size)
    }

    // --- the two lists ---

    @Test
    fun eachOfThe25WhiteSpaceCodePointsBecomesOneSpace() {
        assertEquals(25, WHITE_SPACE.size)
        for (c in WHITE_SPACE) {
            val s = String(Character.toChars(c))
            val name = "U+%04X".format(java.util.Locale.ROOT, c)
            assertEquals(name, "a b", String(of("a${s}b"), Charsets.UTF_8))
            assertEquals(name, "a b", String(of("$s$s a $s$s ${s}b$s"), Charsets.UTF_8)) // runs, ends
        }
    }

    @Test
    fun eachOfThe17ApostrophesBecomesU0027() {
        assertEquals(17, APOSTROPHES.size)
        for (c in APOSTROPHES) {
            val s = String(Character.toChars(c))
            assertEquals("U+%04X".format(java.util.Locale.ROOT, c), "o'g", String(of("o${s}g"), Charsets.UTF_8))
        }
        assertEquals("'", String(of("ʻ"), Charsets.UTF_8)) // alone, too
    }

    @Test
    fun neighboursOfTheListsAreKeptAsTheyAre() {
        // Not white space for this function: zero-width space and joiner, word joiner, Mongolian vowel
        // separator, byte-order mark, soft hyphen, the line and paragraph separators' neighbours.
        // Not apostrophes: the double quotes and primes, U+02BA, U+02EE, U+055A, U+05F3, U+FF40.
        val kept = listOf(
            0x200B, 0x200C, 0x200D, 0x2060, 0x180E, 0xFEFF, 0x00AD, 0x2027, 0x202A, 0x205E, 0x3001,
            0x22, 0x201C, 0x201D, 0x201A, 0x2033, 0x2036, 0x02BA, 0x02C8, 0x02EE, 0x055A, 0x05F3, 0xFF40,
        )
        for (c in kept) {
            val s = "a" + String(Character.toChars(c)) + "b"
            assertEquals("U+%04X".format(java.util.Locale.ROOT, c), hex(s.toByteArray(Charsets.UTF_8)), hexOf(s))
        }
    }

    @Test
    fun caseIsKept() {
        assertEquals(hex("ICH bin Iı İi".toByteArray(Charsets.UTF_8)), hexOf("ICH bin Iı İi"))
        assertFalse(MasterPasswordBytes.same("Toshkent".toCharArray(), "toshkent".toCharArray()))
    }

    @Test
    fun longAndNonAsciiPasswordsAreTheirUtf8() {
        val words = listOf(
            "Ўзбекистон", "Oʻzbekiston".replace('ʻ', 'x'), "пароль", "Ташкент", "中文", "🔑𝐀😀", "naïve", "ǖ",
        )
        for (word in words) {
            val nfc = Normalizer.normalize(word, Normalizer.Form.NFC)
            assertEquals(word, hex(nfc.toByteArray(Charsets.UTF_8)), hexOf(word))
        }
        val long = buildString { repeat(20_000) { append("abcdefghij"[it % 10]) } }
        assertArrayEquals(long.toByteArray(Charsets.US_ASCII), of(long))
        val longMixed = buildString { repeat(2_000) { append("Ўз 🔑 oʻ ") } }
        assertArrayEquals(reference(longMixed), of(longMixed))
    }

    @Test
    fun anUnpairedSurrogateIsWrittenAsTheReplacementCharacter() {
        assertEquals("61efbfbd62", hexOf("a\uD800b"))
        assertEquals("efbfbd", hexOf("\uDC00"))
        assertEquals("efbfbdefbfbd", hexOf("\uDE11\uD83D")) // a pair in the wrong order is two unpaired halves
        assertEquals("f09f9491", hexOf("🔑")) // the right order is one character, U+1F511
        assertArrayEquals(of("�"), of("\uD800"))
    }

    @Test
    fun theTypedCharactersAreNotChanged() {
        val typed = " oʻgʹ  x ".toCharArray()
        val copy = typed.copyOf()
        MasterPasswordBytes.of(typed)
        MasterPasswordBytes.firstUnassigned(typed)
        MasterPasswordBytes.same(typed, copy.copyOf())
        assertArrayEquals(copy, typed)
    }

    // --- lock.md T2's properties over 10,000 random strings ---

    @Test
    fun overRandomStrings_itIsTheFunctionOfAppendixC_idempotent_andTheSameForNfcAndNfd() {
        val random = Random(20261006)
        repeat(10_000) { n ->
            val x = randomText(random)
            val label = "string $n: " + x.codePoints().toArray().joinToString(" ") { "%04X".format(java.util.Locale.ROOT, it) }
            val bytes = of(x)
            assertEquals(label, hex(reference(x)), hex(bytes))
            assertEquals("$label (twice)", hex(bytes), hex(of(String(bytes, Charsets.UTF_8))))
            val nfd = Normalizer.normalize(x, Normalizer.Form.NFD)
            val nfc = Normalizer.normalize(x, Normalizer.Form.NFC)
            assertEquals("$label (NFD)", hex(bytes), hex(of(nfd)))
            assertEquals("$label (NFC)", hex(bytes), hex(of(nfc)))
            // The result is itself NFC, and holds no white space but single inner spaces.
            val out = String(bytes, Charsets.UTF_8)
            assertEquals(label, out, Normalizer.normalize(out, Normalizer.Form.NFC))
            assertFalse(label, out.startsWith(" ") || out.endsWith(" ") || out.contains("  "))
        }
    }

    // --- creation: unassigned code points [V-C9] ---

    @Test
    fun firstUnassigned_isNullForAssignedText() {
        val fine = listOf(
            "", "correct horse battery staple", "oʻgʻlim-Toʻra", "Köz-Ўzbek",
            "🔑 中文 ", // a surrogate pair, CJK, private use: all assigned
            "ʹ`´  　",
        )
        for (text in fine) assertNull(text, MasterPasswordBytes.firstUnassigned(text.toCharArray()))
    }

    @Test
    fun firstUnassigned_givesTheFirstNoncharacterOrUnassignedCodePoint() {
        // Noncharacters are permanently unassigned (general category Cn).
        assertEquals(0xFFFF, MasterPasswordBytes.firstUnassigned("abc￿".toCharArray()))
        assertEquals(0xFDD0, MasterPasswordBytes.firstUnassigned("a﷐b￿".toCharArray()))
        assertEquals(0x10FFFF, MasterPasswordBytes.firstUnassigned("x􏿿y".toCharArray()))
        assertEquals(0x1FFFE, MasterPasswordBytes.firstUnassigned(String(Character.toChars(0x1FFFE)).toCharArray()))
        for (c in listOf(0xFFFE, 0xFDEF, 0x2FFFF, 0x10FFFE)) {
            val chars = ("pass" + String(Character.toChars(c)) + "word").toCharArray()
            assertEquals("U+%04X".format(java.util.Locale.ROOT, c), c, MasterPasswordBytes.firstUnassigned(chars))
        }
    }

    @Test
    fun firstUnassigned_refusesAnUnpairedSurrogate() {
        assertEquals(0xD800, MasterPasswordBytes.firstUnassigned("a\uD800b".toCharArray()))
        assertEquals(0xDC00, MasterPasswordBytes.firstUnassigned("a\uDC00".toCharArray()))
        assertEquals(0xD83D, MasterPasswordBytes.firstUnassigned("key\uD83D".toCharArray()))
        assertEquals(0xDE11, MasterPasswordBytes.firstUnassigned("\uDE11\uD83D".toCharArray()))
    }

    // --- the two typings ---

    @Test
    fun same_comparesTheBytesOfBothTypings() {
        assertTrue(MasterPasswordBytes.same("oʻgʻlim".toCharArray(), "o'g'lim".toCharArray()))
        assertTrue(MasterPasswordBytes.same(" correct  horse ".toCharArray(), "correct horse".toCharArray()))
        assertTrue(MasterPasswordBytes.same("Köz".toCharArray(), "Köz".toCharArray()))
        assertFalse(MasterPasswordBytes.same("abc".toCharArray(), "abd".toCharArray()))
        assertFalse(MasterPasswordBytes.same("abc".toCharArray(), "abcd".toCharArray()))
        assertFalse(MasterPasswordBytes.same("abcd".toCharArray(), "abc".toCharArray()))
        assertFalse(MasterPasswordBytes.same("".toCharArray(), "a".toCharArray()))
        assertFalse(MasterPasswordBytes.same("a b".toCharArray(), "ab".toCharArray()))
        // Both are zero bytes, so they are the same password; the length rule refuses it at creation.
        assertTrue(MasterPasswordBytes.same(" ".toCharArray(), "　\t".toCharArray()))
    }

    // --- helpers ---

    /** lock.md appendix C's `mp_bytes`, written the plain way, on a String: the oracle. */
    private fun reference(s: String): ByteArray {
        val nfc = Normalizer.normalize(s, Normalizer.Form.NFC) // 1 NFC
        val spaced = StringBuilder()
        nfc.codePoints().forEach { c -> if (c in WHITE_SPACE) spaced.append(' ') else spaced.appendCodePoint(c) }
        val words = spaced.toString().split(' ').filter { it.isNotEmpty() } // 2 white space
        val folded = StringBuilder()
        words.joinToString(" ").codePoints().forEach { c -> folded.appendCodePoint(if (c in APOSTROPHES) 0x27 else c) } // 3
        return folded.toString().toByteArray(Charsets.UTF_8) // 4 UTF-8
    }

    /** Up to 24 code points drawn from both lists, the Greek signs, combining marks and letters. */
    private fun randomText(random: Random): String {
        val length = random.nextInt(25)
        val text = StringBuilder()
        repeat(length) { text.appendCodePoint(ALPHABET[random.nextInt(ALPHABET.size)]) }
        return text.toString()
    }

    private fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v shr 4]).append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    private companion object {
        const val HEX = "0123456789abcdef"

        /** architecture.md §4.6 step 2, as the documents list them. */
        val WHITE_SPACE: Set<Int> = ((0x09..0x0D) + 0x20 + 0x85 + 0xA0 + 0x1680 + (0x2000..0x200A) +
            0x2028 + 0x2029 + 0x202F + 0x205F + 0x3000).toSet()

        /** lock.md §8.1 step 3, as the documents list them. */
        val APOSTROPHES: Set<Int> = setOf(
            0x27, 0x60, 0xB4, 0x2018, 0x2019, 0x201B, 0x2032, 0x2035,
            0x02B9, 0x02BB, 0x02BC, 0x02BD, 0x02BE, 0x02BF, 0xA78B, 0xA78C, 0xFF07,
        )

        val ALPHABET: IntArray = (
            WHITE_SPACE + APOSTROPHES +
                listOf(0x0374, 0x1FEF, 0x1FFD) + // the Greek signs that NFC turns into apostrophes
                (0x0300..0x0308) + listOf(0x030C, 0x0313, 0x0314, 0x0323, 0x0327, 0x0328, 0x0345, 0x05B0, 0x093C) +
                "aboqzKATo-!.09".map { it.code } +
                listOf(0x00E9, 0x00F6, 0x00C5, 0x212B, 0x212A, 0x2126, 0x1FF7, 0x1F00, 0x01D6, 0x015F, 0x00E7, 0x011F) +
                listOf(0x040E, 0x0423, 0x0443, 0x0439, 0x0451, 0x049B, 0x0493, 0x04B3, 0x0448) +
                listOf(0xAC00, 0x1100, 0x1161, 0x11A8, 0x4E2D, 0x0915, 0x0928) + // Hangul jamo compose under NFC
                listOf(0x200B, 0x200D, 0x201C, 0x2033, 0x02BA, 0xFF40, 0xFFFD) +
                listOf(0x1F511, 0x1D400, 0x1F600, 0x1D15E) // outside the BMP; U+1D15E decomposes
            ).toIntArray()
    }
}
