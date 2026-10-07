package io.github.dilshodbekmx.passwordvault.core.strength

import io.github.dilshodbekmx.passwordvault.core.crypto.MasterPasswordBytes
import io.github.dilshodbekmx.passwordvault.core.generator.Passphrase
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The checklist of an own master password (ux.md §5.3, architecture.md D-6d and §4.6). */
class MasterPasswordRulesTest {

    private fun check(password: String) = MasterPasswordRules.check(password.toCharArray(), Meters.meter)

    @Test
    fun uxCheck18_theEightWeakExamplesAreRefusedAsHardToGuess() {
        for (password in StrengthMeterTest.CHECK_18) {
            val result = check(password)
            assertFalse(password, result.passes)
            assertFalse("$password must fail on strength, not only on length", result.hardToGuess)
            assertTrue(password, result.strength.score <= 2)
        }
    }

    @Test
    fun theSuggestedPassphrasePasses() {
        val random = FakeRandom(12)
        repeat(100) {
            val result = MasterPasswordRules.check(Passphrase.suggest(random).toCharArray(), Meters.meter)
            assertTrue(result.passes)
            assertFalse(result.mixesScripts)
        }
    }

    @Test
    fun anOwnPasswordThatIsLongAndHardToGuessPasses() {
        for (password in listOf("x7#Qm2!pL9@vRt", "Kecha tunda qor yogʻdi, ertalab quyosh chiqdi", "Олма дарахти 7 марта гуллади!")) {
            val result = check(password)
            assertTrue(password, result.passes)
            assertTrue(result.longEnough && result.hardToGuess && !result.unassignedCharacter)
        }
    }

    @Test
    fun twelveCharactersAreCountedAfterFolding() {
        // Spaces at the ends do not count, and a run of them inside counts once.
        assertFalse(check("  x7#Qm2!pL9@  ").longEnough)
        assertFalse(check("x7#Qm2\u00A0\u00A0\u00A0pL9@").longEnough) // 10 and one space
        assertTrue(check("x7#Qm2 !pL9@").longEnough) // one space between counts once
        // White space only has no characters at all.
        val blank = check(" \t\u3000 ".repeat(10))
        assertFalse(blank.longEnough)
        assertFalse(blank.passes)
        // Code points, not UTF-16 units: twelve emoji are twelve characters.
        assertTrue(check("🔑".repeat(12)).longEnough)
        assertFalse(check("🔑".repeat(11)).longEnough)
    }

    @Test
    fun theUserInputsOfTheAppAreCheap() {
        for (password in listOf("PasswordVault2026", "passwordvault1", "Parol12345678", "пароль12345678")) {
            assertFalse(password, check(password).hardToGuess)
        }
    }

    @Test
    fun anyApostropheGivesTheSameVerdict() {
        val spellings = listOf("Oʻgʻlim Toshkentda", "O'g'lim Toshkentda", "O‘g‘lim Toshkentda", "O`g`lim Toshkentda", "Oʼgʼlim Toshkentda")
        val verdicts = spellings.map { check(it) }
        assertEquals(1, verdicts.toSet().size)
    }

    @Test
    fun unassignedCodePointsAndLoneSurrogatesAreRefused() {
        val unassigned = "x7#Qm2!pL9@vRt\u0378" // U+0378 is unassigned
        assertTrue(check(unassigned).unassignedCharacter)
        assertFalse(check(unassigned).passes)
        val lone = "x7#Qm2!pL9@vRt".toCharArray() + '\uD800'
        val result = MasterPasswordRules.check(lone, Meters.meter)
        assertTrue(result.unassignedCharacter)
        assertFalse(result.passes)
        assertFalse(check("x7#Qm2!pL9@vRt").unassignedCharacter)
    }

    @Test
    fun mixedScriptsAndCyrillicAreReported() {
        assertTrue(check("Toshkent Тошкент 1991").mixesScripts)
        assertFalse(check("Toshkent 1991 qor").mixesScripts)
        assertFalse(check("Тошкент 1991 қор").mixesScripts)
        assertTrue(MasterPasswordRules.containsCyrillic("vowel-shiny-ырудд".toCharArray()))
        assertFalse(MasterPasswordRules.containsCyrillic("vowel-shiny-shell".toCharArray()))
        assertTrue(MasterPasswordRules.mixesLatinAndCyrillic("aб".toCharArray()))
    }

    @Test
    fun foldingIsMasterPasswordBytesReadBack() {
        val typed = "  Oʻgʻlim\u00A0\u00A0Toshkentda’  ".toCharArray()
        val folded = MasterPasswordRules.folded(typed)
        assertEquals("O'g'lim Toshkentda'", String(folded))
        assertArrayEquals(MasterPasswordBytes.of(typed), String(folded).toByteArray(Charsets.UTF_8))
        assertEquals("  Oʻgʻlim\u00A0\u00A0Toshkentda’  ", String(typed)) // the typed array is not changed
    }

    @Test
    fun theResultHoldsNoSecret() {
        val result = check("x7#Qm2!pL9@vRt")
        assertFalse(result.toString().contains("x7#Qm2"))
        assertFalse(result.toString().contains("14"))
    }
}
