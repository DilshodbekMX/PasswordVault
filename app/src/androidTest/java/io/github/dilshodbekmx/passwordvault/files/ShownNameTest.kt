package io.github.dilshodbekmx.passwordvault.files

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 5.1 (review): a name from a place is another app's text. What [ShownName] keeps of it
 * for the app's own sentences and for the vault: one line, nothing that turns the text around or
 * hides in it, and an end.
 */
@RunWith(AndroidJUnit4::class)
class ShownNameTest {
    @Test
    fun aRealNameComesBackAsItIs() {
        val names = listOf(
            "PasswordVault-backup-2026-10-06.pvault",
            "PasswordVault-backup-2026-10-06 (1).pvault",
            "passwords-2026-10-06-part2.csv",
            "Oʻzbekcha parollar.csv", // U+02BB
            "O‘zbekcha gʻoyalar.json", // U+2018, U+02BB
            "Пароли ўзбекча ҳисоб қайдлари.xlsx",
            "a b.csv", // a no-break space inside a name stays what it is
            "résumé – копия.json",
            "写真 📷.pvault",
            "x".repeat(255),
        )
        for (name in names) assertEquals(name, ShownName.of(name))
        assertSame("no name", null, ShownName.of(null))
    }

    @Test
    fun lineBreaksAndControlCharactersBecomeSpaces() {
        assertEquals("a b", ShownName.of("a\nb"))
        assertEquals("a  b", ShownName.of("a\r\nb"))
        assertEquals("a b c d e f", ShownName.of("a\u0000b\tc\u007Fd\u0085e\u001Bf"))
        assertEquals("a b c", ShownName.of("a b c"))
        assertEquals("the ends are trimmed", "a b", ShownName.of(" \n a b\n\n  "))
    }

    @Test
    fun whatTurnsTextAroundOrHidesInItIsLeftOut() {
        // Direction marks, embeddings, overrides and isolates; zero-width characters; the soft hyphen; tags.
        val marks = "‎‏؜‪‫‬‭‮⁦⁧⁨⁩​‌‍⁠﻿­"
        for (mark in marks) assertEquals("U+" + Integer.toHexString(mark.code), "ab", ShownName.of("a" + mark + "b"))
        assertEquals("ab", ShownName.of("a" + String(Character.toChars(0xE0041)) + "b")) // a tag character
        assertEquals("invoice.exe", ShownName.of("invoice‮.exe")) // not shown as "invoiceexe."
        assertEquals("passwords.csv", ShownName.of(marks + "passwords.csv" + marks))
    }

    @Test
    fun anUnpairedSurrogateBecomesTheReplacementCharacter() {
        assertEquals("a�b", ShownName.of("a\uD83Db"))
        assertEquals("a�b", ShownName.of("a\uDCF7b"))
        assertEquals("�", ShownName.of("\uD83D"))
        assertEquals("a pair stays", "a📷b", ShownName.of("a📷b"))
    }

    @Test
    fun nothingLeftIsNoName() {
        for (empty in listOf("", " ", "\n", "​", " ‮\t  ﻿ ")) assertNull(ShownName.of(empty))
    }

    @Test
    fun aLongNameIsCutAndSaysSo() {
        assertEquals(255, ShownName.MAX_LENGTH)
        assertEquals("x".repeat(254) + "…", ShownName.of("x".repeat(256)))
        assertEquals("x".repeat(254) + "…", ShownName.of("x".repeat(3_000_000)))
        assertEquals("255 characters and space after them fit", "x".repeat(255), ShownName.of("x".repeat(255) + "   \n"))
        assertEquals("what does not count does not cut", "x".repeat(255), ShownName.of("​".repeat(500) + "x".repeat(255) + "‮".repeat(500)))

        // Cut at a whole character: a pair is not split, and no space is left before the mark.
        val camera = "📷"
        val cutInAPair = ShownName.of("x".repeat(253) + camera + "yyy")!!
        assertEquals("x".repeat(253) + "…", cutInAPair)
        assertEquals("x".repeat(250) + "…", ShownName.of("x".repeat(250) + "    yyyy"))
        assertEquals("spaces without end are no name", null, ShownName.of(" ".repeat(100_000)))
        assertEquals("ab", ShownName.of("a" + "​".repeat(100_000) + "b"))
        assertEquals("a" + " ".repeat(253) + "b", ShownName.of("a" + " ".repeat(253) + "b"))
        assertEquals("a…", ShownName.of("a" + " ".repeat(254) + "b"))
        assertTrue(ShownName.of("a" + " ".repeat(100_000) + "b")!!.length <= ShownName.MAX_LENGTH)
    }
}
