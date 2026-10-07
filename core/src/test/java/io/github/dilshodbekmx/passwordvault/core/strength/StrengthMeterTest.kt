package io.github.dilshodbekmx.passwordvault.core.strength

import io.github.dilshodbekmx.passwordvault.core.generator.GeneratorOptions
import io.github.dilshodbekmx.passwordvault.core.generator.Passphrase
import io.github.dilshodbekmx.passwordvault.core.generator.PasswordGenerator
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The strength meter with the bundled word list (ux.md §5.3, §12 check 18; architecture.md §8.1
 * "strength"). Every test runs under the Turkish and the Persian default locale too (tools/check.sh).
 */
class StrengthMeterTest {

    private val meter get() = Meters.meter

    @Test
    fun uxCheck18_theEightWeakExamplesScoreTwoOrLess() {
        for (password in CHECK_18) {
            val strength = meter.measure(password)
            assertTrue("$password scores ${strength.score}", strength.score <= 2)
            assertTrue(password, strength.easyToGuess)
            assertTrue(password, meter.isEasyToGuess(password))
        }
    }

    @Test
    fun uxCheck18_withoutTheListTheyWouldPass() {
        // The reason for the list (ux.md §5.3): zxcvbn's English lists alone let most of them through.
        val english = com.nulabinc.zxcvbn.Zxcvbn()
        val passing = CHECK_18.count { english.measure(it).score >= 3 }
        assertTrue("only $passing of 8 score 3 or more without the list", passing >= 6)
    }

    @Test
    fun uxCheck18_theSuggestedPassphraseScoresFour() {
        val random = FakeRandom(2026)
        repeat(300) {
            val chars = Passphrase.suggest(random).toCharArray()
            assertEquals("suggestion $it", 4, meter.measure(String(chars)).score)
        }
    }

    @Test
    fun generatedPasswordsScoreFour() {
        val generator = PasswordGenerator(FakeRandom(16))
        repeat(300) {
            val password = String(generator.generate(GeneratorOptions()))
            assertEquals(4, meter.measure(password).score)
            assertFalse(meter.isEasyToGuess(password))
        }
        val words = PasswordGenerator(FakeRandom(6))
        repeat(100) { assertEquals(4, meter.measure(String(words.generate(GeneratorOptions(kind = GeneratorOptions.Kind.WORDS)))).score) }
    }

    @Test
    fun theListCatchesMoreThanTheEight() {
        // Places, names and words that come from the sourced files, not from common.txt, in both
        // scripts, in Russian transliteration and typed on the wrong keyboard layout.
        val weak = listOf(
            "Qoʻngʻirot1990", "Beruniy2015", "Нурафшон2021", "Хоразм2005", "Nargiza1992", "Odiljon1977",
            "Gulshoda1999", "Mehnat1999", "hukumat2000", "Ташкилот2010", "kitoblar2020", "работа2020",
            "Dengi1985", "schastlivyy2012", "gfhjkm2020", "Самарқанд2024", "Qo'ng'irot1990", "Qoʼngʼirot1990",
        )
        for (password in weak) {
            val strength = meter.measure(password)
            assertTrue("$password scores ${strength.score}", strength.score <= 2)
        }
    }

    @Test
    fun theSearchKeyReadingCountsWhenTheTypedOneDoesNot() {
        // The ʻ of Oʻzbekiston hides the word from zxcvbn; its search key "ozbekiston2024" does not.
        val english = com.nulabinc.zxcvbn.Zxcvbn()
        assertEquals(4, english.measure("Oʻzbekiston2024").score)
        assertTrue(meter.measure("Oʻzbekiston2024").score <= 1)
        // Uzbek Cyrillic and Russian spellings meet the Latin ones.
        assertTrue(meter.measure("Тошкент2020").score <= 1)
        assertTrue(meter.measure("ТАШКЕНТ2020").score <= 1)
    }

    @Test
    fun hintsFollowWhatWasFound() {
        assertEquals(StrengthHint.AVOID_NAMES_AND_YEARS, meter.measure("Toshkent2020").hint)
        assertEquals(StrengthHint.AVOID_NAMES_AND_YEARS, meter.measure("Shahzoda2003!").hint)
        assertEquals(StrengthHint.AVOID_NAMES_AND_YEARS, meter.measure("Michael1984").hint) // zxcvbn's own names
        // Keyboard walks, also those zxcvbn knows only as common passwords.
        for (walk in listOf("zxcvfdsa", "1qaz2wsx", "qwerty123", "asdfghjkl;", "йцукенгшщз", "фывапролдж")) {
            assertEquals(walk, StrengthHint.AVOID_KEYBOARD_ROWS, meter.measure(walk).hint)
        }
        assertEquals(StrengthHint.ADD_WORD, meter.measure("password1").hint)
        assertEquals(StrengthHint.ADD_WORD, meter.measure("abcdefgh").hint)
        assertEquals(StrengthHint.ADD_WORD, meter.measure("").hint)
        assertNull(meter.measure("vowel-shiny-shell-crisp-shaft-cadet").hint)
    }

    @Test
    fun userInputsMakeAPasswordWeaker() {
        // Two made-up words, so that only the user inputs can find them.
        val password = "QwzrtplmXvbnkj"
        assertEquals(4, meter.measure(password).score)
        assertTrue(meter.measure(password, listOf("qwzrtplm", "xvbnkj")).score <= 1)
        // Compared in lower case, and through their search keys: Хвбнкж is "xvbnkj".
        assertTrue(meter.measure(password, listOf("QWZRTPLM", "Хвбнкж")).score <= 1)
        // zxcvbn counts any password of three parts (word, space, word) as 10^8 guesses or more.
        assertEquals(3, meter.measure("Qwzrtplm Xvbnkj", listOf("qwzrtplm", "xvbnkj")).score)
    }

    @Test
    fun anEmptyPasswordScoresZero() {
        assertEquals(Strength(0, StrengthHint.ADD_WORD), meter.measure(""))
        assertTrue(meter.isEasyToGuess(""))
    }

    @Test
    fun onlyTheFirstHundredCharactersAreScored() {
        val random = FakeRandom(100)
        val alphabet = PasswordGenerator.alphabet(GeneratorOptions())
        val long = String(CharArray(1_000) { alphabet[random.int(alphabet.length)] })
        val started = System.nanoTime()
        assertEquals(4, meter.measure(long).score)
        assertTrue("a 1,000-character password took too long", System.nanoTime() - started < 2_000_000_000L)
        // The cut never splits a surrogate pair: 99 letters and an emoji are scored without a lone half.
        assertEquals(4, meter.measure(long.substring(0, 99) + "🔑" + long).score)
    }

    @Test
    fun allSpellingsOfAnEntryShareItsRank() {
        val ranks = Meters.ranks
        val samarqand = ranks.getValue("samarqand")
        assertEquals(samarqand, ranks["самарқанд"])
        assertEquals(samarqand, ranks["samarkand"])
        assertTrue("a city among the first entries", samarqand <= 5)
        // Russian spelling with у к г х, the wrong keyboard layout, and a phrase without its spaces.
        assertEquals(ranks.getValue("пароль"), ranks["gfhjkm"])
        assertEquals(ranks.getValue("шаҳзода"), ranks["шахзода"])
        assertEquals(ranks.getValue("assalomu alaykum"), ranks["assalomualaykum"])
        // Forms the matcher compares: lower case and search keys only.
        assertNull(ranks["Toshkent"])
        assertEquals(ranks.getValue("toshkent"), ranks["тошкент"])
    }

    @Test
    fun formsAreLowerCaseSearchKeysLayoutsAndJoinedPhrases() {
        assertEquals(setOf("парол", "parol", "gfhjk"), WordList.forms("Парол"))
        assertTrue("ozbekiston" in WordList.forms("Oʻzbekiston"))
        assertTrue("oʻzbekiston" in WordList.forms("Oʻzbekiston"))
        assertTrue("mensenisevaman" in WordList.forms("men seni sevaman"))
        assertTrue(WordList.forms("ёж ёж").none { it.codePointCount(0, it.length) < WordList.MIN_LENGTH })
    }

    @Test
    fun levels() {
        assertEquals(StrengthLevel.VERY_WEAK, Strength(0, StrengthHint.ADD_WORD).level)
        assertEquals(StrengthLevel.VERY_WEAK, Strength(1, StrengthHint.ADD_WORD).level)
        assertEquals(StrengthLevel.WEAK, Strength(2, StrengthHint.ADD_WORD).level)
        assertEquals(StrengthLevel.GOOD, Strength(3, StrengthHint.ADD_WORD).level)
        assertEquals(StrengthLevel.STRONG, Strength(4, null).level)
        assertTrue(Strength(2, StrengthHint.ADD_WORD).easyToGuess)
        assertFalse(Strength(3, StrengthHint.ADD_WORD).easyToGuess)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aScoreBelowFourNeedsAHint() {
        Strength(3, null)
    }

    @Test
    fun theMeterIsSharedAndSafeAcrossThreads() {
        val results = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val threads = (CHECK_18 + listOf("vowel-shiny-shell-crisp-shaft-cadet", "x7#Qm2!pL9@vRt")).map { password ->
            Thread { repeat(20) { results.merge(password, meter.measure(password).score) { a, b -> if (a == b) a else -1 } } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue(results.values.none { it < 0 })
        assertEquals(CHECK_18.size + 2, results.size)
    }

    companion object {
        /** ux.md §12 check 18. */
        val CHECK_18 = listOf(
            "Toshkent2020", "Tashkent2020", "Ташкент2020", "Mustaqillik1991", "Samarqand2024!", "Oʻzbekiston2024",
            "Shahzoda2003!", "Assalomu alaykum",
        )
    }
}

/** One meter for all the tests of this package: it takes a moment to build. */
internal object Meters {
    val meter: StrengthMeter by lazy { StrengthMeter.create() }
    val ranks: Map<String, Int> by lazy { WordList.load() }
}
