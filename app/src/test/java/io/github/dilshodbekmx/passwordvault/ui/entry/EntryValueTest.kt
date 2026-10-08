package io.github.dilshodbekmx.passwordvault.ui.entry

import io.github.dilshodbekmx.passwordvault.core.model.CustomField
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
 * Build plan 3.7, [JVM]: the names of an account's values (architecture.md §3.6: the field names of
 * `Route.BigLetters`), and what each is in an entry: its text, whether it is hidden, whether a copy
 * of it is flagged (ux.md §3, §6).
 */
class EntryValueTest {

    @Test
    fun theFieldNamesAreThoseOfTheRoute() {
        assertEquals("username", EntryValue.Username.field)
        assertEquals("password", EntryValue.Password.field)
        assertEquals("old-password", EntryValue.OldPassword.field)
        assertEquals("previous-0", EntryValue.Previous(0).field)
        assertEquals("previous-4", EntryValue.Previous(4).field)
        assertEquals("field-0", EntryValue.Field(0).field)
        assertEquals("field-19", EntryValue.Field(19).field)
        assertNull("a website is never shown in big letters", EntryValue.Website(0).field)
        assertNull("nor are the notes", EntryValue.Notes.field)
    }

    @Test
    fun aNameIsReadBackToItsValue() {
        val values = listOf(
            EntryValue.Username, EntryValue.Password, EntryValue.OldPassword,
            EntryValue.Previous(0), EntryValue.Previous(4), EntryValue.Field(0), EntryValue.Field(7), EntryValue.Field(19), EntryValue.Field(250),
        )
        for (value in values) assertEquals(value, EntryValue.of(value.field!!))
    }

    @Test
    fun anyOtherTextNamesNothing() {
        val wrong = listOf(
            "", "Password", "PASSWORD", "password ", " password", "old_password", "oldpassword", "new-password",
            "previous", "previous-", "previous--1", "previous-+1", "previous-1.0", "previous-one", "previous-01", "previous- 1",
            "field", "field-", "field-x", "field-00", "field-1e3", "field-99999", "field-2147483648", "field-٣", "field-1-2",
            "website-0", "notes", "title", "username-0", "password-0",
        )
        for (name in wrong) assertNull("\"$name\"", EntryValue.of(name))
    }

    @Test
    fun theTextOfEachValue() {
        val entry = gmail()
        assertEquals("aziz@gmail.com", entry.textOf(EntryValue.Username))
        assertEquals(PASSWORD, entry.textOf(EntryValue.Password))
        assertEquals("winter-2025", entry.textOf(EntryValue.Previous(0)))
        assertEquals("autumn-2024", entry.textOf(EntryValue.Previous(1)))
        assertEquals(PIN, entry.textOf(EntryValue.Field(0)))
        assertEquals("+998901234567", entry.textOf(EntryValue.Field(1)))
        assertEquals("https://mail.google.com/mail/u/0", entry.textOf(EntryValue.Website(0)))
        assertEquals(NOTE, entry.textOf(EntryValue.Notes))

        // What the account does not have: a place past the end, an empty text.
        assertNull(entry.textOf(EntryValue.Previous(2)))
        assertNull(entry.textOf(EntryValue.Field(2)))
        assertNull(entry.textOf(EntryValue.Field(-1)))
        assertNull(entry.textOf(EntryValue.Website(2)))
        val nothing = bare()
        for (value in listOf(EntryValue.Username, EntryValue.Password, EntryValue.OldPassword, EntryValue.Notes, EntryValue.Website(0))) {
            assertNull("$value of an account with a name only", nothing.textOf(value))
        }
        assertNull("an empty field value", bare().copy(fields = listOf(CustomField("PIN code", "", true))).textOf(EntryValue.Field(0)))
    }

    @Test
    fun theOldPasswordExistsOnlyWhileAChangeIsPending() {
        assertNull(gmail().textOf(EntryValue.OldPassword))
        assertEquals(OLD_PASSWORD, gmail(changePending = true, pendingOldPassword = OLD_PASSWORD).textOf(EntryValue.OldPassword))
        // A kept password without a pending change (never written by this app) is not shown as one.
        assertNull(gmail(changePending = false, pendingOldPassword = OLD_PASSWORD).textOf(EntryValue.OldPassword))
        // The account had no password when the change started: there is no old one to show.
        assertNull(gmail(changePending = true, pendingOldPassword = "").textOf(EntryValue.OldPassword))
        assertNull(gmail(changePending = true, pendingOldPassword = null).textOf(EntryValue.OldPassword))
    }

    @Test
    fun whatIsHiddenAndWhatIsFlagged() {
        val entry = gmail(changePending = true, pendingOldPassword = OLD_PASSWORD)
        // Hidden until "Show": every password and the fields marked as hidden (ux.md §3).
        val hidden = listOf(EntryValue.Password, EntryValue.OldPassword, EntryValue.Previous(0), EntryValue.Previous(1), EntryValue.Field(0))
        val plain = listOf(EntryValue.Username, EntryValue.Website(0), EntryValue.Website(1), EntryValue.Field(1), EntryValue.Notes, EntryValue.Field(9))
        for (value in hidden) assertTrue("$value is hidden", entry.hides(value))
        for (value in plain) assertFalse("$value is not hidden", entry.hides(value))

        // Flagged on the clipboard: everything hidden, and the notes, which often hold security answers (ux.md §6 item 1).
        for (value in hidden + EntryValue.Notes) assertTrue("a copy of $value is sensitive", entry.copiesAsSensitive(value))
        for (value in plain - EntryValue.Notes) assertFalse("a copy of $value is not sensitive", entry.copiesAsSensitive(value))
    }
}
