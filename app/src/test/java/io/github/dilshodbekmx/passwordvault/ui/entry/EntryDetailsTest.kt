package io.github.dilshodbekmx.passwordvault.ui.entry

import io.github.dilshodbekmx.passwordvault.core.model.CustomField
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryKit.Companion.ADDED_MS
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryKit.Companion.DAY_MS
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryKit.Companion.NOTE
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryKit.Companion.OLD_PASSWORD
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryKit.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryKit.Companion.PIN
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryKit.Companion.bare
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryKit.Companion.gmail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build plan 3.7, [JVM]: an entry as its screen shows it (ux.md §5.12): which rows there are, what
 * a website row offers, the dates, and that a hidden secret is not in the rows at all.
 */
class EntryDetailsTest {

    private fun details(
        entry: Entry,
        revealed: Set<EntryValue> = emptySet(),
        sameAsMaster: Boolean = false,
        notesExpanded: Boolean = false,
        previousExpanded: Boolean = false,
    ) = EntryDetails.of(entry, sameAsMaster, revealed, notesExpanded, previousExpanded)

    @Test
    fun theRowsOfAnAccount() {
        val d = details(gmail())
        assertEquals("Gmail", d.title)
        assertEquals("aziz@gmail.com", d.username)
        assertEquals(listOf(SecretRow(EntryValue.Password, null)), d.passwords)
        assertEquals(NOTE, d.notes)
        assertEquals(
            listOf(FieldRow(0, "PIN code", secret = true, empty = false, shown = null), FieldRow(1, "Phone number", secret = false, empty = false, shown = "+998901234567")),
            d.fields,
        )
        assertEquals(2, d.previousCount)
        assertTrue("the previous passwords are closed", d.previous.isEmpty())
        assertFalse(d.empty)
        assertFalse(d.changePending)
        assertFalse(d.favourite)
        assertFalse(d.checkIgnored)
        assertFalse(d.sameAsMasterPassword)
        assertTrue(details(gmail(favourite = true, checkIgnored = true), sameAsMaster = true).run { favourite && checkIgnored && sameAsMasterPassword })
    }

    @Test
    fun onlyWhatIsShownIsInTheRows() {
        val entry = gmail(changePending = true, pendingOldPassword = OLD_PASSWORD)
        val hidden = details(entry, previousExpanded = true)
        assertEquals(listOf(SecretRow(EntryValue.OldPassword, null), SecretRow(EntryValue.Password, null)), hidden.passwords)
        assertNull(hidden.fields[0].shown)
        assertEquals(listOf(PreviousRow(0, ADDED_MS + 2 * DAY_MS, null), PreviousRow(1, ADDED_MS + DAY_MS, null)), hidden.previous)

        val all = setOf(EntryValue.OldPassword, EntryValue.Password, EntryValue.Field(0), EntryValue.Previous(0), EntryValue.Previous(1))
        val open = details(entry, revealed = all, previousExpanded = true)
        assertEquals(listOf(OLD_PASSWORD, PASSWORD), open.passwords.map { it.shown })
        assertEquals(PIN, open.fields[0].shown)
        assertEquals(listOf("winter-2025", "autumn-2024"), open.previous.map { it.shown })

        // Asked to show what the account does not have: nothing appears.
        val beyond = details(gmail(), revealed = setOf(EntryValue.OldPassword, EntryValue.Field(5), EntryValue.Previous(9)), previousExpanded = true)
        assertEquals(listOf(SecretRow(EntryValue.Password, null)), beyond.passwords)
        assertTrue(beyond.previous.all { it.shown == null })

        // Closed previous passwords have no rows, shown or not.
        assertTrue(details(entry, revealed = all, previousExpanded = false).previous.isEmpty())
        assertEquals(2, details(entry, revealed = all).previousCount)
    }

    @Test
    fun thePasswordRowsWhileAChangeIsPending() {
        // The old password, which the website may still use, then the new one (ux.md §5.12).
        val pending = details(gmail(changePending = true, pendingOldPassword = OLD_PASSWORD))
        assertEquals(listOf(EntryValue.OldPassword, EntryValue.Password), pending.passwords.map { it.value })
        assertEquals(ADDED_MS + 2 * DAY_MS, pending.changeStartedAtMs)
        assertNull(pending.passwordChangedAtMs)

        // The account had no password before the change: only the new one.
        for (old in listOf("", null)) {
            val fresh = details(gmail(changePending = true, pendingOldPassword = old))
            assertEquals(listOf(EntryValue.Password), fresh.passwords.map { it.value })
            assertTrue(fresh.changePending)
        }

        // No date of the password: the banner uses the day of the last change of the account.
        val undated = details(gmail(changePending = true, pendingOldPassword = OLD_PASSWORD).copy(passwordChangedAt = null))
        assertEquals(ADDED_MS + 3 * DAY_MS, undated.changeStartedAtMs)

        // Without a pending change: one row, no banner date, and "Password changed".
        val settled = details(gmail())
        assertNull(settled.changeStartedAtMs)
        assertEquals(ADDED_MS + 2 * DAY_MS, settled.passwordChangedAtMs)
    }

    @Test
    fun anAccountWithoutAPassword() {
        // It signs in by SMS code (ux.md §2): no password row, and no "Password changed".
        val d = details(gmail(password = ""))
        assertTrue(d.passwords.isEmpty())
        assertNull(d.passwordChangedAtMs)
        assertFalse("it still has a username, websites, notes and fields", d.empty)
    }

    @Test
    fun aNameAndNothingElseIsEmpty() {
        val d = details(bare())
        assertTrue(d.empty)
        assertEquals("Kapitalbank", d.title)
        assertNull("never edited since it was added", d.editedAtMs)
        assertEquals(ADDED_MS, d.createdAtMs)
        assertNull(d.passwordChangedAtMs)
        assertEquals(0, d.previousCount)

        // One value of any kind is enough for it not to be empty.
        assertFalse(details(bare().copy(username = "aziz")).empty)
        assertFalse(details(bare().copy(password = "x")).empty)
        assertFalse(details(bare().copy(urls = listOf("https://kapitalbank.uz"))).empty)
        assertFalse(details(bare().copy(notes = "n")).empty)
        assertFalse(details(bare().copy(fields = listOf(CustomField("PIN code", "", true)))).empty)
        // Blank websites are no rows.
        assertTrue(details(bare().copy(urls = listOf("", "   "))).empty)
    }

    @Test
    fun theDates() {
        val d = details(gmail())
        assertEquals(ADDED_MS, d.createdAtMs)
        assertEquals(ADDED_MS + 3 * DAY_MS, d.editedAtMs)
        assertEquals(ADDED_MS + 2 * DAY_MS, d.passwordChangedAtMs)
    }

    @Test
    fun whatAWebsiteRowOffers() {
        fun row(address: String) = WebsiteRow.of(0, address)!!

        // http and https open in the browser, whatever the case of the scheme; the host is shown bold.
        for (address in listOf("https://mail.google.com/mail/u/0", "HTTPS://Mail.Google.com/", "http://192.168.1.1:8080/admin", "Http://example.uz")) {
            assertEquals(address, WebsiteKind.WEB, row(address).kind)
        }
        assertEquals("mail.google.com", row("https://mail.google.com/mail/u/0").host)
        assertEquals("https://mail.google.com/mail/u/0", row("https://mail.google.com/mail/u/0").address)
        assertEquals("google.com", row("https://www.Google.com:443/accounts?x=1").host)
        assertEquals("почта.рф", row("https://xn--80a1acny.xn--p1ai/inbox").host)

        // An app's address from Chrome: "App: com.example", never opened (ux.md §2, §5.12).
        val app = row("android://kO3pZ9/hash==@com.google.android.gm/")
        assertEquals(WebsiteKind.APP, app.kind)
        assertEquals("com.google.android.gm", app.host)
        assertEquals(WebsiteKind.APP, row("androidapp://com.example.bank").kind)
        assertEquals("com.example.bank", row("androidapp://com.example.bank").host)

        // Anything else is shown and copied, never opened.
        for (address in listOf("ftp://files.example.uz", "javascript:alert(1)", "intent://x#Intent;end", "file:///sdcard/x", "content://x", "my router", "httpsx://a.uz", "https:/a.uz")) {
            assertEquals(address, WebsiteKind.OTHER, row(address).kind)
        }
        assertEquals("my router", row("my router").host)

        // Stored with spaces around it: trimmed; blank: no row.
        assertEquals("https://a.uz", row("  https://a.uz  ").address)
        assertNull(WebsiteRow.of(0, ""))
        assertNull(WebsiteRow.of(0, "  \n "))
    }

    @Test
    fun aRowKeepsItsPlaceInTheEntry() {
        // The second website is blank: the third keeps the place 2, which is what a copy asks the entry for.
        val entry = bare().copy(urls = listOf("https://a.uz", " ", "https://c.uz"))
        val d = details(entry)
        assertEquals(listOf(0, 2), d.websites.map { it.index })
        assertEquals("https://c.uz", entry.textOf(d.websites[1].value))
    }

    @Test
    fun anEmptyFieldHasNothingToShow() {
        val entry = bare().copy(fields = listOf(CustomField("PIN code", "", true), CustomField("Phone number", "", false)))
        val d = details(entry, revealed = setOf(EntryValue.Field(0)))
        assertEquals(
            listOf(FieldRow(0, "PIN code", secret = true, empty = true, shown = null), FieldRow(1, "Phone number", secret = false, empty = true, shown = "")),
            d.fields,
        )
    }

    @Test
    fun noToStringShowsATextOfTheAccount() {
        val entry = gmail(changePending = true, pendingOldPassword = OLD_PASSWORD)
        val all = setOf(EntryValue.OldPassword, EntryValue.Password, EntryValue.Field(0), EntryValue.Previous(0), EntryValue.Previous(1))
        val d = details(entry, revealed = all, previousExpanded = true)
        val printed = listOf(d.toString(), d.passwords.toString(), d.websites.toString(), d.fields.toString(), d.previous.toString(), EntryUiState.Shown(d).toString(), EntryUiState.Deleted("Gmail").toString())
        val texts = listOf(
            "Gmail", "aziz", PASSWORD, OLD_PASSWORD, PIN, "winter-2025", "autumn-2024", "Samarqand", "PIN code", "Phone number",
            "+998901234567", "mail.google.com", "com.google.android.gm",
        )
        for (line in printed) for (text in texts) assertFalse("\"$text\" in \"$line\"", text in line)
    }
}
