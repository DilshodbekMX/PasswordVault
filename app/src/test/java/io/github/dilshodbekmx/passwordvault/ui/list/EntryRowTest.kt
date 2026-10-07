package io.github.dilshodbekmx.passwordvault.ui.list

import io.github.dilshodbekmx.passwordvault.core.model.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Build plan 3.5, [JVM]: what a row of the list shows (ux.md §5.11 "Row"): the badge's letter and
 * tone, the second line, the address "Open website" uses, and nothing of the password.
 */
class EntryRowTest {
    private fun entry(title: String, username: String = "", password: String = "", urls: List<String> = emptyList()) =
        Entry(id = "id", title = title, username = username, password = password, urls = urls, createdAt = 0, updatedAt = 0)

    @Test
    fun theBadgeShowsTheFirstLetterAsOneGrapheme() {
        assertEquals("G", EntryRow.initialOf("gmail"))
        assertEquals("O", EntryRow.initialOf("Oʻzbekiston pochtasi"))
        assertEquals("Ў", EntryRow.initialOf("ўзбектелеком"))
        assertEquals("I", EntryRow.initialOf("«Ipak Yoʻli»"))
        assertEquals("1", EntryRow.initialOf("123 Bank"))
        assertEquals("Й", EntryRow.initialOf("йшчи"))
        assertEquals("I", EntryRow.initialOf("instagram")) // not "İ", whatever the default locale
        assertEquals("signs before the first letter are skipped", "H", EntryRow.initialOf("@home"))
        assertEquals("no letter or digit: the first grapheme", "★", EntryRow.initialOf("★ ★"))
        assertEquals("#", EntryRow.initialOf(""))
    }

    @Test
    fun theInitialIgnoresATurkishDefaultLocale() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("I", EntryRow.initialOf("instagram"))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun theToneIsOneOfEightAndTheSameForTheSameTitle() {
        val titles = listOf("Gmail", "Telegram", "Ўзбектелеком", "", "Oʻzbekiston", "a", "b", "c", "d", "e", "f", "g")
        for (t in titles) {
            val tone = EntryRow.toneOf(t)
            assertTrue(tone in 0 until EntryRow.BADGE_TONES)
            assertEquals(tone, EntryRow.toneOf(String(t.toCharArray())))
        }
        assertTrue("the tones are spread", titles.map(EntryRow::toneOf).toSet().size >= 4)
    }

    @Test
    fun theSecondLineIsTheUsernameElseTheFirstHost() {
        assertEquals("aziz@gmail.com", EntryRow.of(entry("Gmail", username = "aziz@gmail.com", urls = listOf("https://mail.google.com"))).detail)
        assertEquals("mail.google.com", EntryRow.of(entry("Gmail", urls = listOf("https://mail.google.com/u/0", "https://x.uz"))).detail)
        assertEquals("com.sberbank.online", EntryRow.of(entry("Sber", urls = listOf("android://hash/with=slash@com.sberbank.online/"))).detail)
        assertEquals("", EntryRow.of(entry("Note only")).detail)
    }

    @Test
    fun openWebsiteTakesTheFirstWebAddressOnly() {
        assertEquals("https://mail.google.com", EntryRow.of(entry("Gmail", urls = listOf("android://h@com.google.android.gm/", " https://mail.google.com "))).website)
        assertEquals("HTTP://old.example.uz", EntryRow.of(entry("Old", urls = listOf("HTTP://old.example.uz"))).website)
        assertNull("an app link is never opened", EntryRow.of(entry("App", urls = listOf("android://h@com.example/"))).website)
        assertNull(EntryRow.of(entry("None")).website)
        assertFalse(EntryRow.isWebAddress("javascript:alert(1)"))
        assertFalse(EntryRow.isWebAddress("file:///sdcard/x"))
    }

    @Test
    fun aRowSaysWhetherThereIsAPasswordNeverWhichOne() {
        val row = EntryRow.of(entry("Gmail", username = "aziz", password = "s3cret-Gmail"))
        assertTrue(row.hasPassword)
        assertTrue(row.hasUsername)
        assertFalse("s3cret" in row.toString())
        assertFalse("Gmail" in row.toString())
        assertFalse("aziz" in row.toString())
        assertFalse(EntryRow.of(entry("Bank by SMS")).hasPassword)
    }
}
