package io.github.dilshodbekmx.passwordvault.core.model

import io.github.dilshodbekmx.passwordvault.core.model.EntryProblem.Kind
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.T0
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.Texts
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.entry
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.vault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The text rules of ux.md §2 besides the limits: control characters, surrogates, trimming, websites. */
class EntryRulesTest {
    private val lone = "\uD800"

    /** Each one-line field, with the problem it gives for a bad character at row 0 or -1. */
    private val oneLine: List<Pair<String, (String) -> DraftEntry>> = listOf(
        "name" to { s -> DraftEntry(title = "a${s}b") },
        "username" to { s -> DraftEntry(username = "a${s}b") },
        "password" to { s -> DraftEntry(password = "a${s}b") },
        "website" to { s -> DraftEntry(urls = listOf("https://a.example/${s}b")) },
        "field name" to { s -> DraftEntry(fields = listOf(CustomField("a${s}b", "v", secret = true))) },
    )
    private val oneLineFields = listOf(EntryField.TITLE, EntryField.USERNAME, EntryField.PASSWORD, EntryField.WEBSITE, EntryField.FIELD_NAME)

    @Test
    fun everyControlCharacterIsRefusedInOneLineFields() {
        val controls = (0x00..0x1F).map { it.toChar() } + '\u007F'
        for ((index, case) in oneLine.withIndex()) {
            val (name, form) = case
            val field = oneLineFields[index]
            val row = if (field == EntryField.WEBSITE || field == EntryField.FIELD_NAME) 0 else -1
            for (c in controls) {
                val problems = EntryRules.problems(form(c.toString()))
                assertEquals("$name with U+%04X".format(java.util.Locale.ROOT, c.code), listOf(EntryProblem(field, Kind.CONTROL_CHARACTER, row)), problems)
            }
        }
    }

    @Test
    fun lineBreaksAreAllowedInNotesAndFieldValuesOnly() {
        val notes = DraftEntry(title = "T", notes = "one\ntwo\n\nthree")
        assertTrue(EntryRules.problems(notes).isEmpty())
        val value = DraftEntry(title = "T", fields = listOf(CustomField("Answer", "line 1\nline 2", secret = true)))
        assertTrue(EntryRules.problems(value).isEmpty())
        // A tab or another control character is refused there too.
        assertEquals(
            listOf(EntryProblem(EntryField.NOTES, Kind.CONTROL_CHARACTER)),
            EntryRules.problems(DraftEntry(title = "T", notes = "a\tb")),
        )
        assertEquals(
            listOf(EntryProblem(EntryField.FIELD_VALUE, Kind.CONTROL_CHARACTER, 0)),
            EntryRules.problems(DraftEntry(title = "T", fields = listOf(CustomField("A", "x\u0000", secret = true)))),
        )
    }

    @Test
    fun carriageReturnsInNotesAndValuesBecomeLineFeeds() {
        val form = DraftEntry(
            title = "T",
            notes = "Windows\r\nline\rend",
            fields = listOf(CustomField("Answer", "a\r\nb", secret = true)),
        )
        assertTrue(EntryRules.problems(form).isEmpty())
        val ready = EntryRules.prepare(form)
        assertEquals("Windows\nline\nend", ready.notes)
        assertEquals("a\nb", ready.fields.single().value)
    }

    @Test
    fun unpairedSurrogatesAreRefusedEverywhere() {
        for ((index, case) in oneLine.withIndex()) {
            val (name, form) = case
            val field = oneLineFields[index]
            val row = if (field == EntryField.WEBSITE || field == EntryField.FIELD_NAME) 0 else -1
            assertEquals(name, listOf(EntryProblem(field, Kind.UNPAIRED_SURROGATE, row)), EntryRules.problems(form(lone)))
            assertEquals(name, listOf(EntryProblem(field, Kind.UNPAIRED_SURROGATE, row)), EntryRules.problems(form("\uDC00")))
            assertTrue("$name: a pair is a character", EntryRules.problems(form(ModelFixtures.KEY)).isEmpty())
        }
        assertEquals(listOf(EntryProblem(EntryField.NOTES, Kind.UNPAIRED_SURROGATE)), EntryRules.problems(DraftEntry(notes = "x$lone")))
        assertEquals(
            listOf(EntryProblem(EntryField.FIELD_VALUE, Kind.UNPAIRED_SURROGATE, 0)),
            EntryRules.problems(DraftEntry(fields = listOf(CustomField("A", lone, secret = true)))),
        )
    }

    @Test
    fun namesAreTrimmedAndPasswordsAndNotesAreNot() {
        val form = DraftEntry(
            title = "  Gmail \t",
            username = "  aziz@example.com　",
            password = "  spaces count  ",
            notes = "  indented\n",
            urls = listOf("  https://mail.google.com  "),
            fields = listOf(CustomField("  PIN code ", " 0042 ", secret = true)),
        )
        // The tab is trimmed away before the name is checked.
        assertTrue(EntryRules.problems(form).isEmpty())
        val stored = VaultEdits.add(form, Texts, ModelFixtures.random())(Vault(), T0).vault.entries.single()
        assertEquals("Gmail", stored.title)
        assertEquals("aziz@example.com", stored.username)
        assertEquals("  spaces count  ", stored.password)
        assertEquals("  indented\n", stored.notes)
        assertEquals(listOf("https://mail.google.com"), stored.urls)
        assertEquals(listOf(CustomField("PIN code", " 0042 ", secret = true)), stored.fields)
    }

    @Test
    fun problemsNameTheRowAsTyped() {
        val form = DraftEntry(
            title = "T",
            urls = listOf("", "https://ok.example", "   ", "https://bad.example/\u0001"),
            fields = listOf(
                CustomField("", "", secret = true), // dropped, still row 0
                CustomField("Good", "v", secret = false),
                CustomField("  ", "has a value", secret = true),
                CustomField("x".repeat(101), "v", secret = true),
            ),
        )
        assertEquals(
            listOf(
                EntryProblem(EntryField.WEBSITE, Kind.CONTROL_CHARACTER, 3),
                EntryProblem(EntryField.FIELD_NAME, Kind.BLANK, 2),
                EntryProblem(EntryField.FIELD_NAME, Kind.TOO_LONG, 3, 100),
            ),
            EntryRules.problems(form),
        )
        assertTrue(EntryRules.isValid(DraftEntry(title = "T")))
    }

    @Test
    fun theProblemsComeInTheOrderOfTheForm() {
        val form = DraftEntry(
            title = "a\nb",
            username = "u".repeat(501),
            password = "p\tq",
            notes = "n".repeat(10_001),
            urls = (1..11).map { "https://s$it.example" },
            fields = (1..21).map { CustomField("F$it", "v", secret = false) },
        )
        assertEquals(
            listOf(EntryField.TITLE, EntryField.USERNAME, EntryField.PASSWORD, EntryField.WEBSITES, EntryField.NOTES, EntryField.FIELDS),
            EntryRules.problems(form).map { it.field },
        )
    }

    @Test
    fun theWebsiteRule() {
        val cases = listOf(
            "gmail.com" to "https://gmail.com",
            "  mail.google.com/mail/u/0  " to "https://mail.google.com/mail/u/0",
            "www.Google.com" to "https://www.Google.com",
            "192.168.1.1:8443/admin" to "https://192.168.1.1:8443/admin",
            "пример.рф" to "https://пример.рф",
            "gmail.com/redirect?to=https://x.example" to "https://gmail.com/redirect?to=https://x.example",
            "https://mail.google.com" to "https://mail.google.com",
            "HTTP://EXAMPLE.COM" to "HTTP://EXAMPLE.COM",
            "android://Xw0Fd5Jx/Ng+K/GtH0==@com.google.android.gm/" to "android://Xw0Fd5Jx/Ng+K/GtH0==@com.google.android.gm/",
            "androidapp://com.sberbank.online" to "androidapp://com.sberbank.online",
            "mailto:me@example.com" to "mailto:me@example.com",
            "localhost" to "localhost",
            "localhost:3000" to "localhost:3000",
            "my bank" to "my bank",
            "Sberbank Online" to "Sberbank Online",
            "" to "",
            "   " to "",
        )
        for ((raw, normal) in cases) assertEquals(raw, normal, EntryRules.normalizeUrl(raw))
    }

    @Test
    fun theDuplicateQuestionComparesNameAndLoginWithoutCase() {
        val gmail = entry(1, title = "Gmail", username = "aziz@example.com")
        val deleted = entry(2, title = "Bank", username = "ali", deletedAt = T0)
        val v = vault(gmail, deleted, entry(3, title = "Gmail", username = "other"))
        assertSame(gmail, EntryRules.sameLogin(v, "  gmail ", "AZIZ@example.com", exceptId = null))
        assertNull("the entry being edited never counts", EntryRules.sameLogin(v, "Gmail", "aziz@example.com", exceptId = gmail.id))
        assertNull("Recently deleted does not count", EntryRules.sameLogin(v, "Bank", "ali", exceptId = null))
        assertNull(EntryRules.sameLogin(v, "Gmail", "nobody", exceptId = null))
    }
}
