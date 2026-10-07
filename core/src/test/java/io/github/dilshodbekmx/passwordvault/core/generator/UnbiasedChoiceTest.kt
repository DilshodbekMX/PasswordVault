package io.github.dilshodbekmx.passwordvault.core.generator

import io.github.dilshodbekmx.passwordvault.core.generator.GeneratorOptions.Kind
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * architecture.md §8.1, generator: "unbiased choice over 10⁶ draws" (crypto.md §9: `nextInt(bound)`,
 * never `% n`). Each test draws a million times through the generator and compares the counts
 * with what an unbiased choice gives ([ChiSquare]); the last two show that the same checks catch
 * the biases they are there for. The random source is `FakeRandom` with a fixed seed, whose `int`
 * is unbiased: what is tested is how the generator turns its draws into characters and words.
 */
class UnbiasedChoiceTest {

    private val list = Wordlist.effShort

    /** a–z without l: one kind, so no try is ever drawn again and each character is one draw. */
    private val letters = GeneratorOptions(length = 16, upper = false, lower = true, digits = false, symbols = false)
    private val letterAlphabet = "abcdefghijkmnopqrstuvwxyz"

    /** 0–9 and the symbols, look-alikes avoided: 8 digits and 12 symbols, 4 characters. */
    private val digitsAndSymbols = GeneratorOptions(length = 4, upper = false, lower = false, digits = true, symbols = true)
    private val digitAndSymbolAlphabet = "23456789!#\$%&*+-=?@_"

    @Test
    fun aMillionCharactersAreEquallyLikelyInEveryPlaceAndAfterEachOther() {
        assertEquals(letterAlphabet, PasswordGenerator.alphabet(letters))
        val counts = drawLetters(PasswordGenerator(FakeRandom(1_000_000)))
        assertEquals(1_000_000, counts.characters.sum())
        ChiSquare.assertUniform("a million characters", counts.characters)
        ChiSquare.assertUniform("the characters in each of the 16 places", counts.byPlace)
        ChiSquare.assertUniform("each character after each other one", counts.pairs)
    }

    @Test
    fun aMillionWordsAreEquallyLikelyInEveryPlace() {
        val options = GeneratorOptions(kind = Kind.WORDS, words = 10, capitalize = false, addNumber = false)
        val generator = PasswordGenerator(FakeRandom(1_295))
        val counts = IntArray(list.size)
        val byPlace = IntArray(10 * list.size)
        repeat(100_000) {
            for ((place, word) in String(generator.generate(options)).split('-').withIndex()) {
                val index = list.indexOf(word)
                counts[index]++
                byPlace[place * list.size + index]++
            }
        }
        assertEquals(1_000_000, counts.sum())
        ChiSquare.assertUniform("a million words", counts)
        ChiSquare.assertUniform("the words in each of the 10 places", byPlace)
    }

    /**
     * The guarantee of the Random kind: every password that has each chosen kind is equally likely,
     * and no other one comes out. Small enough to count each one: 20⁴ = 160,000 passwords of 4
     * characters over the 8 digits and 12 symbols, of which 135,168 have a digit and a symbol.
     */
    @Test
    fun aMillionPasswordsAreEquallyLikelyAmongAllThatHaveEveryChosenKind() {
        assertEquals(digitAndSymbolAlphabet, PasswordGenerator.alphabet(digitsAndSymbols))
        val generator = PasswordGenerator(FakeRandom(135_168))
        val counts = IntArray(160_000)
        repeat(1_000_000) { counts[cell(generator.generate(digitsAndSymbols))]++ }
        val valid = validCounts(counts)
        assertEquals(135_168, valid.size)
        assertEquals("no password without a digit or without a symbol", 1_000_000, valid.sum())
        ChiSquare.assertUniform("a million passwords of 4 digits and symbols", valid)
    }

    @Test
    fun theDigitAndTheWordItFollowsAreEquallyLikely() {
        val generator = PasswordGenerator(FakeRandom(60))
        val digits = IntArray(10)
        val places = IntArray(6)
        repeat(200_000) {
            val parts = String(generator.generate(GeneratorOptions(kind = Kind.WORDS))).split('-')
            val place = parts.indexOfFirst { it.last() in '0'..'9' }
            places[place]++
            digits[parts[place].last() - '0']++
        }
        ChiSquare.assertUniform("the digit", digits)
        ChiSquare.assertUniform("the word the digit follows", places)
    }

    /** The checks have teeth: a random byte `% 25` makes the first 6 letters 10 % more likely. */
    @Test
    fun theChecksCatchARandomByteModuloN() {
        val counts = drawLetters(PasswordGenerator(ModuloRandom(1_000_000)))
        assertTrue(ChiSquare.statistic(counts.characters) > ChiSquare.upperLimit(24))
    }

    /**
     * The checks have teeth: the usual other way to get every kind, one digit and one symbol first,
     * the rest from the whole alphabet, then shuffled, makes 2 digits and 2 symbols likelier than
     * they are among the valid passwords (0.48 against 0.41). The check of the Random kind fails it.
     */
    @Test
    fun theChecksCatchRequiredCharactersShuffledIn() {
        val random = FakeRandom(135_168)
        val digits = digitAndSymbolAlphabet.substring(0, 8)
        val symbols = digitAndSymbolAlphabet.substring(8)
        val counts = IntArray(160_000)
        repeat(1_000_000) {
            val password = charArrayOf(
                digits[random.int(digits.length)],
                symbols[random.int(symbols.length)],
                digitAndSymbolAlphabet[random.int(20)],
                digitAndSymbolAlphabet[random.int(20)],
            )
            for (i in 3 downTo 1) { // Fisher and Yates
                val j = random.int(i + 1)
                val c = password[i]
                password[i] = password[j]
                password[j] = c
            }
            counts[cell(password)]++
        }
        val valid = validCounts(counts)
        assertEquals(1_000_000, valid.sum())
        assertTrue(ChiSquare.statistic(valid) > ChiSquare.upperLimit(valid.size - 1))
    }

    private class LetterCounts(val characters: IntArray, val byPlace: IntArray, val pairs: IntArray)

    /** 62,500 passwords of 16 letters: a million characters, counted alone, by place and by pairs of neighbours. */
    private fun drawLetters(generator: PasswordGenerator): LetterCounts {
        val n = letterAlphabet.length
        val counts = LetterCounts(IntArray(n), IntArray(16 * n), IntArray(n * n))
        repeat(62_500) {
            var previous = -1
            for ((place, c) in generator.generate(letters).withIndex()) {
                val index = letterAlphabet.indexOf(c)
                counts.characters[index]++
                counts.byPlace[place * n + index]++
                if (previous >= 0) counts.pairs[previous * n + index]++
                previous = index
            }
        }
        return counts
    }

    /** The number of a password of 4 digits and symbols among all 20⁴, in the order of the alphabet. */
    private fun cell(password: CharArray): Int {
        var cell = 0
        for (c in password) {
            val index = digitAndSymbolAlphabet.indexOf(c)
            check(index >= 0) { "not a digit or symbol of the alphabet" }
            cell = cell * 20 + index
        }
        return cell
    }

    /** The counts of the passwords that have a digit (index below 8) and a symbol; the others must be 0. */
    private fun validCounts(counts: IntArray): IntArray {
        val valid = ArrayList<Int>(135_168)
        for (cell in counts.indices) {
            var rest = cell
            var digit = false
            var symbol = false
            repeat(4) {
                if (rest % 20 < 8) digit = true else symbol = true
                rest /= 20
            }
            if (digit && symbol) valid += counts[cell] else assertEquals("password $cell has no digit or no symbol", 0, counts[cell])
        }
        return valid.toIntArray()
    }
}
