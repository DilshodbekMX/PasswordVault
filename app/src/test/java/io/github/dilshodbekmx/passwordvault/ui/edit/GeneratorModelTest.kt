package io.github.dilshodbekmx.passwordvault.ui.edit

import io.github.dilshodbekmx.passwordvault.core.generator.GeneratorOptions
import io.github.dilshodbekmx.passwordvault.core.generator.PasswordGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build plan 3.6, [JVM]: the generator sheet's model (ux.md §5.15) as the add and edit form owns it:
 * the last choices, the rule that one kind of character stays on, the bounds of the two sliders,
 * the Words kind, and what is remembered and dropped.
 */
class GeneratorModelTest {

    @Test
    fun theSheetOpensWithTheLastChoicesAndMakesAPasswordFromThem() = EditKit.run {
        settings.generator.value = GeneratorOptions(length = 20, symbols = false)
        val generator = form(repository()).generator
        generator.open()
        settle()
        val state = generator.state.value
        assertTrue(state.open)
        assertEquals(20, state.options.length)
        val preview = state.preview
        assertNotNull(preview)
        assertEquals(20, preview!!.length)
        assertTrue("symbols are off", preview.none { it in PasswordGenerator.SYMBOLS })
        assertTrue("look-alikes are avoided", preview.none { it in PasswordGenerator.LOOK_ALIKE })
        assertEquals(preview, generator.password())
        assertNotNull("the strength word under it", state.strength)
        assertTrue("the state never prints the password", preview !in state.toString())
    }

    @Test
    fun choicesThatCannotBeUsedFallBackToUxDefaults() = EditKit.run {
        settings.generator.value = GeneratorOptions(length = 2, upper = false, lower = false, digits = false, symbols = false)
        val generator = form(repository()).generator
        generator.open()
        settle()
        assertEquals(GeneratorOptions(), generator.state.value.options)
        assertEquals("16 characters by default", 16, generator.password()!!.length)
    }

    @Test
    fun theLastKindOfCharacterCannotBeSwitchedOff() = EditKit.run {
        val generator = form(repository()).generator
        generator.open()
        generator.setUpper(false)
        generator.setLower(false)
        generator.setDigits(false)
        settle()
        assertFalse(generator.state.value.oneKindNeeded)
        generator.setSymbols(false)
        settle()
        val state = generator.state.value
        assertTrue("\"At least one kind of character is needed.\"", state.oneKindNeeded)
        assertTrue("the last kind stays on", state.options.symbols)
        assertTrue(generator.password()!!.all { it in PasswordGenerator.SYMBOLS })
        generator.setDigits(true)
        settle()
        assertFalse(generator.state.value.oneKindNeeded)
    }

    @Test
    fun theLengthStaysWithin4And64() = EditKit.run {
        val generator = form(repository()).generator
        generator.open()
        generator.setLength(2)
        assertEquals(GeneratorOptions.MIN_LENGTH, generator.state.value.options.length)
        generator.shorter()
        assertEquals(GeneratorOptions.MIN_LENGTH, generator.state.value.options.length)
        generator.setLength(100)
        assertEquals(GeneratorOptions.MAX_LENGTH, generator.state.value.options.length)
        generator.longer()
        assertEquals(GeneratorOptions.MAX_LENGTH, generator.state.value.options.length)
        generator.shorter()
        settle()
        assertEquals(63, generator.password()!!.length)
    }

    @Test
    fun wordsAreJoinedByTheSeparatorWithCapitalsAndOneNumber() = EditKit.run {
        val generator = form(repository()).generator
        generator.open()
        generator.setKind(GeneratorOptions.Kind.WORDS)
        settle()
        val words = generator.password()!!.split('-')
        assertEquals("6 words by default", 6, words.size)
        assertTrue("capital letters", words.all { it.first().isUpperCase() })
        assertEquals("one number", 1, words.sumOf { word -> word.count { it.isDigit() } })

        generator.setSeparator(GeneratorOptions.Separator.SPACE)
        generator.setCapitalize(false)
        generator.setAddNumber(false)
        generator.setWords(13)
        settle()
        val spaced = generator.password()!!.split(' ')
        assertEquals("at most 12 words", GeneratorOptions.MAX_WORDS, spaced.size)
        assertTrue(spaced.all { word -> word.all { it.isLowerCase() } })
        generator.setWords(1)
        generator.fewerWords()
        settle()
        assertEquals("at least 4 words", GeneratorOptions.MIN_WORDS, generator.password()!!.split(' ').size)
    }

    @Test
    fun newPasswordMakesAnotherOne() = EditKit.run {
        val generator = form(repository()).generator
        generator.open()
        settle()
        val first = generator.password()
        generator.again()
        settle()
        assertNotEquals(first, generator.password())
    }

    @Test
    fun closingDropsThePasswordAndRemembersOnlyChangedChoices() = EditKit.run {
        val generator = form(repository()).generator
        generator.open()
        settle()
        generator.close()
        settle()
        assertEquals("unchanged choices are not written", 0, settings.writes)
        assertNull(generator.password())
        assertNull(generator.state.value.preview)
        assertFalse(generator.state.value.open)

        generator.open()
        generator.setLength(20)
        generator.setSymbols(false)
        settle()
        generator.close()
        settle()
        assertEquals(1, settings.writes)
        assertEquals(GeneratorOptions(length = 20, symbols = false), settings.generator.value)
        generator.open()
        assertEquals("the sheet opens with them next time", 20, generator.state.value.options.length)
    }

    @Test
    fun nothingChangesWhileTheSheetIsClosed() = EditKit.run {
        val generator = form(repository()).generator
        generator.setLength(30)
        generator.again()
        settle()
        assertFalse(generator.state.value.open)
        assertNull(generator.password())
        assertEquals(GeneratorOptions(), generator.state.value.options)
    }
}
