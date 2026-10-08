package io.github.dilshodbekmx.passwordvault.core.formats.importer

import io.github.dilshodbekmx.passwordvault.core.formats.ColumnMeaning
import io.github.dilshodbekmx.passwordvault.core.formats.ImportChange
import io.github.dilshodbekmx.passwordvault.core.formats.Importer
import io.github.dilshodbekmx.passwordvault.core.formats.IsoTime
import io.github.dilshodbekmx.passwordvault.core.formats.TextDecoding
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.NOW
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.file
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.hidden
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.plan
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.resource
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.visible
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import io.github.dilshodbekmx.passwordvault.core.util.Uuid
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real files of formats.md §8.5 (Proton Pass's test fixtures: a Chrome CSV, its Windows variant
 * and a Bitwarden JSON with logins, cards, an identity, a note and an SSH key) and the Uzbek Cyrillic
 * header file of formats-review P6, all in `core/src/test/resources/formats/` (`SOURCES.md` there),
 * read through `Importer.read` and planned into an empty vault.
 */
class ImportFixturesTest {
    @Test
    fun theChromeCsvIsReadAsGooglesLayout() {
        val file = file(resource("proton/chrome.csv"))
        assertEquals(Importer.Format.GOOGLE_CSV, file.format)
        assertEquals(TextDecoding.Encoding.UTF_8, file.decoding!!.encoding)
        assertEquals(TextDecoding.Why.VALID_UTF8, file.decoding!!.why)
        assertEquals(',', file.delimiter)
        assertEquals(
            listOf(ColumnMeaning.TITLE, ColumnMeaning.WEBSITE, ColumnMeaning.USERNAME, ColumnMeaning.PASSWORD, ColumnMeaning.NOTES),
            file.columns!!.meanings,
        )
        val e = file.accounts.map { it.entry }
        assertEquals(listOf("proton.me", "missing url", "missing password", "broken url"), e.map { it.title })
        assertEquals(listOf("nobody@proton.me", "missingurl@proton.me", "missingpw@proton.me", "brokenurl@proton.me"), e.map { it.username })
        assertEquals(listOf("proton123", "proton123", "", ""), e.map { it.password })
        assertEquals(
            listOf(listOf("https://account.proton.me/switch"), emptyList(), listOf("https://account.proton.me/switch"),
                listOf("htt:/proton.me/switch")), // not host-like: kept as written
            e.map { it.urls },
        )
        assertEquals(listOf("", "somenote", "", ""), e.map { it.notes })
        assertEquals(listOf(listOf(2), listOf(3), listOf(4), listOf(5)), file.accounts.map { it.lines })
        assertTrue(e.all { it.fields.isEmpty() && it.createdAt == NOW && it.updatedAt == NOW && it.passwordChangedAt == null })
        assertTrue(file.leftOut.isEmpty())

        val plan = plan(file)
        assertEquals(4, plan.found)
        assertEquals(4, plan.accounts.size)
        assertEquals(2, plan.withoutPassword) // imported and counted, as SMS logins are
        assertEquals(0, plan.tidied) // proton.me has no ending the rule drops
        assertTrue(plan.fits)
    }

    @Test
    fun itsWindowsVariantToo() {
        val file = file(resource("proton/chrome-windows.csv"))
        assertEquals(Importer.Format.GOOGLE_CSV, file.format)
        assertEquals(listOf("proton.me", "missing url", "missing password"), file.accounts.map { it.entry.title })
        assertEquals(3, plan(file).accounts.size)
    }

    @Test
    fun theBitwardenJsonIsMappedAndPlanned() {
        val file = file(resource("proton/bitwarden.json"))
        assertEquals(Importer.Format.BITWARDEN_JSON, file.format)
        assertFalse(file.newerVersion)
        val e = file.accounts.map { it.entry }
        assertEquals(
            listOf("LoginItemMultipleWebsites", "Credit Card Y", "IdentityItem", "NoteItem", "LoginItemEmptyFields", "LoginItemBrokenUrl",
                "Credit Card Y", "LoginItemMultipleWebsites", "LoginWithCustomFields", "test ssh", "DeepLogin", "ArchivedLogin"),
            e.map { it.title },
        )
        assertEquals((1..12).toList(), file.accounts.map { it.position })
        assertTrue(e.all { Uuid.isValid(it.id) })
        assertEquals(12, e.map { it.id }.toSet().size) // a foreign file's ids are new, each once

        val login = e[0]
        assertEquals(listOf("https://test.url1/", "https://test.url2", "androidapp://ch.protonmail.android"), login.urls)
        assertEquals(
            listOf(visible("Text 1", "Text 1 content"), hidden("Hidden 1", "Hidden 1 content"),
                hidden("TOTP", "otpauth://totp/test?secret=proton333&issuer=proton")),
            login.fields,
        )
        assertEquals(
            listOf(visible("cardholderName", "A B"), visible("brand", "Amex"), hidden("number", "374242424242424"),
                visible("expMonth", "1"), visible("expYear", "2025"), hidden("code", "123"), visible("Folder", "custom folder")),
            e[1].fields,
        )
        assertEquals(listOf(visible("firstName", "John"), visible("middleName", "F"), visible("lastName", "Kennedy")), e[2].fields)
        assertEquals("note content", e[3].notes)
        assertEquals(listOf("htt:/test.url1/"), e[5].urls) // not host-like: kept as written
        assertEquals(listOf(ImportChange.Kind.LINKED_FIELDS_NOT_IMPORTED), file.accounts[8].changes.map { it.kind })
        assertEquals(IsoTime.parse("2025-02-17T09:30:27.266Z"), e[9].createdAt)
        assertEquals("-----BEGIN TEST -----\n00000000000000\n-----END TEST-----\n", e[9].fields[0].value)
        assertEquals(listOf(visible("Folder", "custom folder/nested/deep")), e[10].fields)
        assertTrue(file.leftOut.isEmpty())

        val plan = plan(file)
        assertEquals(12, plan.found)
        // The second card repeats the first exactly (same name, no login, its values among the first's): imported once.
        assertEquals(1, plan.repeatedInFile)
        assertEquals(11, plan.accounts.size)
        assertEquals(6, plan.withoutPassword) // a card, the identity, the note, two logins without one, the SSH key
        assertEquals(0, plan.relatedRows) // items of a JSON file are never joined
        val vault = VaultEdits.addImported(plan.entries, FakeRandom(2), ImportFixtures.Texts::fromFile)(Vault(), NOW).vault
        assertEquals(11, vault.entries.map { it.id }.toSet().size)
    }

    @Test
    fun theUzbekCyrillicHeadersOfP6AreReadAsSourcesMdSays() {
        val file = file(resource("uzbek-cyrillic-headers.csv"))
        assertEquals(Importer.Format.GENERIC_CSV, file.format)
        assertTrue(file.columns!!.ambiguous.isEmpty())
        val e = file.accounts.map { it.entry }
        assertEquals(listOf("Почта", "Банк", "Davlat xizmatlari"), e.map { it.title })
        assertEquals(listOf("aziz", "+998901234567", "AA1234567"), e.map { it.username })
        // The passwords exactly as written: the two apostrophe letters of row 2 are not folded.
        assertEquals(listOf("Парол-2026!", "O\u02BBzbek\u20181!", "Toshkent#2026"), e.map { it.password })
        assertEquals(listOf(listOf("https://pochta.uz"), listOf("https://bank.uz"), listOf("https://my.gov.uz")), e.map { it.urls })
        assertEquals(listOf("шахсий", "карта PIN коди\nалоҳида дафтарда", ""), e.map { it.notes })
        assertTrue(e.all { it.fields.isEmpty() })
        assertEquals(listOf(listOf(2), listOf(3), listOf(5)), file.accounts.map { it.lines }) // row 2's note spans two lines

        val plan = plan(file)
        assertEquals(3, plan.accounts.size)
        assertEquals(0, plan.tidied) // no name is a host name
    }
}
