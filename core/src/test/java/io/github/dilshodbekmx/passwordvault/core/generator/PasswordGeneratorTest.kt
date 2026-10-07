package io.github.dilshodbekmx.passwordvault.core.generator

import io.github.dilshodbekmx.passwordvault.core.generator.GeneratorOptions.Kind
import io.github.dilshodbekmx.passwordvault.core.generator.GeneratorOptions.Separator
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import java.util.Locale
import kotlin.math.log2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generator sheet of ux.md §5.15 (architecture.md §8.1, generator): at least one character of
 * each chosen kind, the Words kind, the bounds, and the order of the draws that tests of the screens
 * can queue. The million-draw checks of an unbiased choice are in [UnbiasedChoiceTest].
 */
class PasswordGeneratorTest {

    private val defaults = GeneratorOptions()
    private val wordsDefaults = GeneratorOptions(kind = Kind.WORDS)
    private val list = Wordlist.effShort

    /** The options with the kinds of [mask]: 1 A–Z, 2 a–z, 4 0–9, 8 symbols. */
    private fun options(mask: Int, length: Int, avoid: Boolean) = GeneratorOptions(
        length = length,
        upper = (mask and 1) != 0,
        lower = (mask and 2) != 0,
        digits = (mask and 4) != 0,
        symbols = (mask and 8) != 0,
        avoidLookAlike = avoid,
    )

    /** The chosen kinds as ux.md §5.15 names them, without the look-alikes when they are avoided. */
    private fun kinds(options: GeneratorOptions): List<String> =
        listOf(UPPER to options.upper, LOWER to options.lower, DIGITS to options.digits, SYMBOLS to options.symbols)
            .filter { it.second }
            .map { (kind, _) -> if (options.avoidLookAlike) kind.filterNot { it in LOOK_ALIKE } else kind }

    @Test
    fun theDefaultsDraw16CharactersFrom69() {
        assertEquals("ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!#\$%&*+-=?@_", PasswordGenerator.alphabet(defaults))
        assertEquals(69, PasswordGenerator.alphabet(defaults).length)
        assertEquals(UPPER + LOWER + DIGITS + SYMBOLS, PasswordGenerator.alphabet(defaults.copy(avoidLookAlike = false)))
        assertEquals(16, PasswordGenerator(FakeRandom()).generate(defaults).size)
        assertEquals(UPPER, PasswordGenerator.UPPER)
        assertEquals(LOWER, PasswordGenerator.LOWER)
        assertEquals(DIGITS, PasswordGenerator.DIGITS)
        assertEquals(SYMBOLS, PasswordGenerator.SYMBOLS)
        assertEquals(LOOK_ALIKE, PasswordGenerator.LOOK_ALIKE)
    }

    /** architecture.md §8.1: "at least one character of each chosen kind". */
    @Test
    fun atLeastOneCharacterOfEachChosenKindAndNoOtherCharacter() {
        val generator = PasswordGenerator(FakeRandom(15))
        for (mask in 1..15) for (avoid in listOf(true, false)) for (length in listOf(4, 5, 16, 64)) {
            val options = options(mask, length, avoid)
            val kinds = kinds(options)
            repeat(100) {
                val password = generator.generate(options)
                assertEquals(length, password.size)
                for (c in password) assertTrue("$options: a character of a chosen kind", kinds.any { c in it })
                for (kind in kinds) assertTrue("$options: one of $kind", password.any { it in kind })
            }
        }
    }

    @Test
    fun atTheShortestLengthTheFourKindsComeInEveryOrderAlike() {
        // 4 characters and 4 kinds: one of each; each of the 24 orders as often as the others.
        val orders = HashMap<String, Int>()
        for (a in 0..3) for (b in 0..3) for (c in 0..3) for (d in 0..3) {
            if (setOf(a, b, c, d).size == 4) orders["$a$b$c$d"] = orders.size
        }
        val options = defaults.copy(length = 4)
        val kinds = kinds(options)
        val generator = PasswordGenerator(FakeRandom(24))
        val counts = IntArray(24)
        repeat(120_000) {
            val order = generator.generate(options).joinToString("") { c -> kinds.indexOfFirst { c in it }.toString() }
            counts[orders.getValue(order)]++
        }
        ChiSquare.assertUniform("the orders of the four kinds", counts)
    }

    @Test
    fun aTryWithoutEveryKindIsThrownAwayWholeAndMadeAgain() {
        // AAAA lacks three kinds; the next four draws make the password, nothing of the first try stays.
        val random = RecordingRandom(FakeRandom().queueInts(0, 0, 0, 0, 0, 24, 49, 57))
        assertEquals("Aa2!", String(PasswordGenerator(random).generate(defaults.copy(length = 4))))
        assertEquals(List(8) { 69 }, random.bounds)
    }

    @Test
    fun randomDrawsOnlyIntsOverTheWholeAlphabetOnePerCharacter() {
        val random = RecordingRandom(FakeRandom(3))
        val generator = PasswordGenerator(random)
        repeat(1_000) { generator.generate(defaults) }
        assertTrue(random.bounds.all { it == 69 })
        assertEquals(0, random.bounds.size % 16)
    }

    @Test
    fun wordsWithTheDefaultsOfTheSheet() {
        // 6 words with capital letters, one digit right after one of them, joined by hyphens.
        val shape = Regex("[A-Z][a-z]{2,4}[0-9]?(-[A-Z][a-z]{2,4}[0-9]?){5}")
        val generator = PasswordGenerator(FakeRandom(6))
        repeat(2_000) {
            val text = String(generator.generate(wordsDefaults))
            assertTrue(text, shape.matches(text))
            assertEquals(1, text.count { it in '0'..'9' })
            for (part in text.split('-')) assertTrue(part.lowercase().trimEnd { it in '0'..'9' } in list)
        }
    }

    @Test
    fun wordsWithEverySeparatorCountAndSwitch() {
        val generator = PasswordGenerator(FakeRandom(12))
        for (separator in Separator.values()) for (count in 4..12) for (capitalize in listOf(true, false)) {
            for (addNumber in listOf(true, false)) {
                val options = wordsDefaults.copy(words = count, separator = separator, capitalize = capitalize, addNumber = addNumber)
                repeat(20) {
                    val text = String(generator.generate(options))
                    val parts = text.split(separator.char)
                    assertEquals("$options", count, parts.size)
                    assertEquals("$options", if (addNumber) 1 else 0, text.count { it in '0'..'9' })
                    for (part in parts) {
                        val word = part.trimEnd { it in '0'..'9' }
                        assertTrue("$options", word.isNotEmpty() && word.substring(1).all { it in 'a'..'z' })
                        assertEquals("$options", capitalize, word[0] in 'A'..'Z')
                        assertTrue("$options", word.lowercase() in list)
                    }
                }
            }
        }
    }

    @Test
    fun wordsDrawEachWordThenThePlaceOfTheNumberThenTheDigit() {
        val random = RecordingRandom(FakeRandom().queueInts(0, 1_294, 567, 1, 2, 3, 2, 7))
        assertEquals("Acid-Zoom-Icing7-Acorn-Acre-Acts", String(PasswordGenerator(random).generate(wordsDefaults)))
        assertEquals(List(6) { 1_295 } + listOf(6, 10), random.bounds)

        val plain = RecordingRandom(FakeRandom().queueInts(0, 1, 2, 3))
        val options = wordsDefaults.copy(words = 4, separator = Separator.SPACE, capitalize = false, addNumber = false)
        assertEquals("acid acorn acre acts", String(PasswordGenerator(plain).generate(options)))
        assertEquals(List(4) { 1_295 }, plain.bounds)
    }

    @Test
    fun capitalLettersAndTheDigitDoNotDependOnTheDefaultLocale() {
        val saved = Locale.getDefault()
        try {
            for (tag in listOf("tr-TR", "az-AZ", "fa-IR", "ar-EG", "hi-IN")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                val random = FakeRandom().queueInts(567, 567, 567, 567, 0, 9)
                val text = String(PasswordGenerator(random).generate(wordsDefaults.copy(words = 4)))
                assertEquals("under $tag", "Icing9-Icing-Icing-Icing", text)
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun eachPasswordIsANewArraySoTheCallerCanWipeIt() {
        val generator = PasswordGenerator(FakeRandom(2))
        val first = generator.generate(defaults)
        val second = generator.generate(defaults)
        assertFalse(first === second)
        first.fill('\u0000')
        assertTrue(second.none { it == '\u0000' })
    }

    @Test
    fun optionsOutsideTheBoundsAreRefused() {
        val generator = PasswordGenerator(FakeRandom())
        val noKind = defaults.copy(upper = false, lower = false, digits = false, symbols = false)
        val refused = listOf(
            defaults.copy(length = 3),
            defaults.copy(length = 65),
            defaults.copy(words = 3),
            defaults.copy(words = 13),
            noKind,
            noKind.copy(kind = Kind.WORDS),
            wordsDefaults.copy(length = 0),
            wordsDefaults.copy(words = 0),
        )
        for (options in refused) {
            assertFalse("$options", options.isValid())
            assertThrows("$options", IllegalArgumentException::class.java) { generator.generate(options) }
            assertThrows("$options", IllegalArgumentException::class.java) { PasswordGenerator.bits(options) }
        }
        val taken = listOf(
            defaults,
            wordsDefaults,
            defaults.copy(length = 4),
            defaults.copy(length = 64),
            wordsDefaults.copy(words = 4),
            wordsDefaults.copy(words = 12),
        ) + (1..15).map { options(it, 4, avoid = true) }
        for (options in taken) {
            assertTrue("$options", options.isValid())
            generator.generate(options)
        }
    }

    @Test
    fun aBrokenRandomSourceFailsInsteadOfLoopingForEver() {
        val stuck = StuckRandom()
        assertThrows(IllegalStateException::class.java) { PasswordGenerator(stuck).generate(defaults) }
        assertEquals(PasswordGenerator.MAX_TRIES * 16, stuck.calls)
        // One kind cannot be missed: a stuck source makes a (bad) password instead of failing.
        assertEquals("aaaa", String(PasswordGenerator(StuckRandom()).generate(options(2, 4, avoid = true))))
    }

    @Test
    fun bitsCountEveryPasswordThatCanComeOut() {
        assertEquals(97.443, PasswordGenerator.bits(defaults), 0.001) // ux.md §5.15: "about 97 bits"
        assertTrue(PasswordGenerator.bits(defaults) < 16 * log2(69.0))
        assertEquals(16 * log2(25.0), PasswordGenerator.bits(options(2, 16, avoid = true)), 1e-9)
        // Counted another way, one sequence of kinds at a time, for every set of kinds and short lengths.
        for (mask in 1..15) for (avoid in listOf(true, false)) for (length in 4..8) {
            val options = options(mask, length, avoid)
            val expected = log2(countByKindSequences(kinds(options).map { it.length }, length).toDouble())
            assertEquals("$options", expected, PasswordGenerator.bits(options), 1e-9)
        }
        assertTrue(PasswordGenerator.bits(defaults.copy(length = 64, avoidLookAlike = false)) < 64 * log2(74.0))
        assertEquals(Passphrase.BITS, PasswordGenerator.bits(wordsDefaults.copy(capitalize = false, addNumber = false)), 1e-12)
        assertEquals(62.032, PasswordGenerator.bits(wordsDefaults.copy(addNumber = false)), 0.001)
        assertEquals(6 * log2(1_295.0) + log2(60.0), PasswordGenerator.bits(wordsDefaults), 1e-12)
        assertEquals(67.939, PasswordGenerator.bits(wordsDefaults), 0.001)
    }

    /** The passwords of [length] over kinds of [sizes] that use every kind, one kind sequence at a time. */
    private fun countByKindSequences(sizes: List<Int>, length: Int): Long {
        var sequences = 1
        repeat(length) { sequences *= sizes.size }
        var total = 0L
        for (s in 0 until sequences) {
            var rest = s
            var seen = 0
            var product = 1L
            repeat(length) {
                val kind = rest % sizes.size
                rest /= sizes.size
                seen = seen or (1 shl kind)
                product *= sizes[kind]
            }
            if (seen == (1 shl sizes.size) - 1) total += product
        }
        return total
    }

    private companion object {
        // ux.md §5.15, written out here rather than taken from the generator.
        const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        const val LOWER = "abcdefghijklmnopqrstuvwxyz"
        const val DIGITS = "0123456789"
        const val SYMBOLS = "!#\$%&*+-=?@_"
        const val LOOK_ALIKE = "Il1O0"
    }
}
