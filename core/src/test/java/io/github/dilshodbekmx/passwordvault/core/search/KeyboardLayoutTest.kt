package io.github.dilshodbekmx.passwordvault.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The ЙЦУКЕН ↔ QWERTY key map of search.md §2 (architecture.md D-11). */
class KeyboardLayoutTest {

    private val qwerty = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`"
    private val jcuken = "йцукенгшщзхъфывапролджэячсмитьбюё"

    @Test
    fun theExamplesOfSearchMd() {
        assertEquals("inst", KeyboardLayout.otherLayout("штые"))
        assertEquals("привет", KeyboardLayout.otherLayout("ghbdtn"))
    }

    @Test
    fun everyKeyOfTheTableBothWays() {
        assertEquals(jcuken, KeyboardLayout.otherLayout(qwerty))
        assertEquals(qwerty, KeyboardLayout.otherLayout(jcuken))
        for (i in qwerty.indices) {
            val q = qwerty[i].toString()
            val j = jcuken[i].toString()
            assertEquals(q, j, KeyboardLayout.otherLayout(q))
            assertEquals(j, q, KeyboardLayout.otherLayout(j))
        }
    }

    @Test
    fun capitalsGiveCapitals() {
        assertEquals("Привет", KeyboardLayout.otherLayout("Ghbdtn"))
        assertEquals("INST", KeyboardLayout.otherLayout("ШТЫЕ"))
        assertEquals("ЙЦУКЕНГШЩЗхъФЫВАПРОЛДжэЯЧСМИТЬбюё", KeyboardLayout.otherLayout("QWERTYUIOP[]ASDFGHJKL;'ZXCVBNM,.`"))
        // Х Ъ Ж Э Б Ю Ё give their key's character, which has no capital.
        assertEquals("QWERTYUIOP[]ASDFGHJKL;'ZXCVBNM,.`", KeyboardLayout.otherLayout("ЙЦУКЕНГШЩЗХЪФЫВАПРОЛДЖЭЯЧСМИТЬБЮЁ"))
    }

    @Test
    fun theDirectionFollowsTheScriptWithMoreLetters() {
        // More Latin letters: the QWERTY keys change, the Cyrillic letters stay.
        assertEquals("привет мир", KeyboardLayout.otherLayout("ghbdtn мир"))
        // More Cyrillic letters: the Cyrillic letters change, the Latin ones stay.
        assertEquals("ghbdtn wo", KeyboardLayout.otherLayout("привет wo"))
        // As many of each: the QWERTY keys change.
        assertEquals("фи вг", KeyboardLayout.otherLayout("ab вг"))
        // No letters at all: the QWERTY keys change.
        assertEquals("жэ", KeyboardLayout.otherLayout(";'"))
    }

    @Test
    fun everythingElseIsKept() {
        assertEquals("пьфшд 2024 @+!", KeyboardLayout.otherLayout("gmail 2024 @+!"))
        // ў қ ғ ҳ sit on different keys in different Uzbek layouts: they stay, and still count as
        // Cyrillic letters for the direction.
        assertEquals("ўp,tr", KeyboardLayout.otherLayout("ўзбек"))
        assertEquals("ҳjr,tr", KeyboardLayout.otherLayout("ҳокбек"))
        // Letters whose lower case is a key's letter but which are not its capital stay.
        val kelvin = Char(0x212A).toString()
        assertEquals("İш", KeyboardLayout.otherLayout("İi"))
        assertEquals(kelvin + "л", KeyboardLayout.otherLayout(kelvin + "k"))
    }

    @Test
    fun nothingToChangeGivesTheSameString() {
        for (q in listOf("", "   ", "2024", "+998", "ўқғҳ", "🔑", "微信")) assertSame(q, KeyboardLayout.otherLayout(q))
    }
}
