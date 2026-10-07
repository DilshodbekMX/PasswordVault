package io.github.dilshodbekmx.passwordvault.core.model

import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.MINUTE
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.T0
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.Texts
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.assertSound
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.entry
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.id
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.random
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.vault
import io.github.dilshodbekmx.passwordvault.core.util.Uuid
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Entries from outside the vault: an import (formats.md §5.2, §8.4, formats-review P1, D-15) and
 * another vault's entries (ux.md §5.21 "Backup file into an existing vault", architecture.md §4.11).
 */
class ImportAndMergeTest {
    private fun import(entries: List<Entry>, into: Vault, random: FakeRandom = random()) =
        VaultEdits.addImported(entries, random, Texts::fromFile)(into, T0 + MINUTE)

    @Test
    fun formatsReviewP1ExportEditOneEntryImportGivesNoDuplicateId() {
        // The trigger of formats-review P1: the vault holds Gmail and Bank; export; change Gmail's
        // password in the app; import the export.
        val gmail = entry(140, title = "Gmail", password = "old")
        val bank = entry(141, title = "Bank")
        val exported = listOf(gmail, bank)
        val form = DraftEntry(gmail.title, gmail.username, "new", gmail.notes, gmail.urls, gmail.fields, gmail.favourite)
        val edited = VaultEdits.update(gmail.id, form, Texts)(vault(gmail, bank), T0).vault
        val imported = import(exported, edited)
        assertSound(imported.vault)
        val ids = imported.vault.entries.map { it.id }
        assertEquals(4, ids.size)
        // The accounts that were here keep their ids and are untouched.
        assertSame(edited.entries[0], imported.vault.entries[0])
        assertSame(edited.entries[1], imported.vault.entries[1])
        // The file's Gmail differs from the one holding its id: added beside it as "(from file)".
        val oldGmail = imported.vault.entries[2]
        assertNotEquals(gmail.id, oldGmail.id)
        assertEquals("Gmail (fayldan)", oldGmail.title)
        assertEquals("old", oldGmail.password)
        // The file's Bank is the same record (the importer's plan would have skipped it): a new id only.
        assertEquals("Bank", imported.vault.entries[3].title)
        assertEquals(2, imported.value.newIds)
        assertEquals(1, imported.value.renamed)
        assertEquals(ids.drop(2), imported.value.addedIds)
        // Undo puts back exactly the vault as it was before the import.
        assertSame(edited, imported.value.before)
        assertSame(edited, VaultEdits.replaceVault(imported.value.before)(imported.vault, T0 + 2 * MINUTE).vault)
    }

    @Test
    fun anIdIsKeptOnlyWhenItIsAValidUuidThatIsFree() {
        val here = vault(entry(1), entry(2, deletedAt = T0))
        val incoming = listOf(
            entry(3, title = "Free"), // kept
            entry(4, title = "Twice A"), // kept
            entry(4, title = "Twice B"), // second in the file with one id: a new id, no new name
            entry(2, title = "Was deleted here"), // a deleted entry holds its id too
            entry(5, title = "Upper").copy(id = "0000000a-0000-4000-8000-00000000000b".uppercase()), // not lower case: a new id
            entry(6, title = "No id").copy(id = ""),
            entry(7, title = "Foreign").copy(id = "b5a8c1d2"),
        )
        val result = import(incoming, here, FakeRandom().queueUuids(id(4), id(1), id(10), id(11), id(12), id(13), id(14)))
        assertSound(result.vault)
        val added = result.vault.entries.drop(2)
        assertEquals(listOf("Free", "Twice A", "Twice B", "Was deleted here (fayldan)", "Upper", "No id", "Foreign"), added.map { it.title })
        assertEquals(id(3), added[0].id)
        assertEquals(id(4), added[1].id)
        // id(4) and id(1) from the source are taken, so the next free ones are drawn.
        assertEquals(listOf(id(10), id(11), id(12), id(13), id(14)), added.drop(2).map { it.id })
        assertEquals(5, result.value.newIds)
        assertEquals(1, result.value.renamed)
        assertTrue(added.all { Uuid.isValid(it.id) })
    }

    @Test
    fun anImportChangesNothingElseAndKeepsTheTimesItCameWith() {
        val here = vault(entry(1)).copy(draft = Draft(null, T0, DraftEntry(title = "x")), masterPasswordMatches = listOf(id(1)))
        val old = entry(2, createdAt = T0 - 400 * ModelFixtures.DAY, updatedAt = T0 - 300 * ModelFixtures.DAY)
        val result = import(listOf(old), here)
        assertEquals(here.copy(entries = here.entries + old), result.vault)
        val none = import(emptyList(), here)
        assertSame(here, none.vault)
        assertTrue(none.value.addedIds.isEmpty())
        assertSame(here, none.value.before)
    }

    @Test
    fun incomingTextWithAnUnpairedSurrogateIsMadeSound() {
        val broken = entry(2, title = "T\uD800", password = "p\uDC00").copy(
            fields = listOf(CustomField("F\uD800", "v", secret = false)),
            passwordHistory = listOf(HistoryItem("h\uD800", T0)),
            extras = mapOf("k" to "\"\uD800\""),
        )
        val imported = import(listOf(broken), Vault()).vault
        assertSound(imported)
        assertEquals("T�", imported.entries.single().title)
        assertEquals("p�", imported.entries.single().password)
        assertEquals("\"�\"", imported.entries.single().extras["k"])
        val merged = VaultEdits.mergeEntries(listOf(broken), Texts, random())(Vault(), T0).vault
        assertSound(merged)
        // A sound entry comes in as the same object.
        val sound = entry(3)
        assertSame(sound, import(listOf(sound), Vault()).vault.entries.single())
    }

    @Test
    fun aMergeAddsNewIdsSkipsIdenticalEntriesAndRenamesTheOthers() {
        val gmail = entry(1, title = "Gmail")
        val bank = entry(2, title = "Bank")
        val here = vault(gmail, bank)
        val backup = listOf(
            gmail.copy(favourite = true, checkIgnored = true, updatedAt = T0 - 5, deletedAt = T0), // identical: how it was used differs
            bank.copy(password = "changed since"), // other content: added beside it
            entry(3, title = "Only in the backup"),
            entry(4, title = "Deleted in the backup", deletedAt = T0 - 5),
            entry(5, title = "Bad id").copy(id = "x"),
        )
        val result = VaultEdits.mergeEntries(backup, Texts, FakeRandom().queueUuids(id(20), id(21)))(here, T0 + MINUTE)
        assertSound(result.vault)
        assertEquals(1, result.value.skipped)
        assertEquals(1, result.value.renamed)
        val added = result.vault.entries.drop(2)
        assertEquals(listOf("Bank (zaxiradan)", "Only in the backup", "Deleted in the backup", "Bad id"), added.map { it.title })
        assertEquals(listOf(id(20), id(3), id(4), id(21)), added.map { it.id })
        assertEquals(added.map { it.id }, result.value.addedIds)
        assertEquals("changed since", added[0].password)
        assertEquals("a deleted entry stays in Recently deleted", T0 - 5, added[2].deletedAt)
        // Nothing here is overwritten.
        assertSame(gmail, result.vault.entries[0])
        assertSame(bank, result.vault.entries[1])
        // The merged entries keep their times.
        assertEquals(backup[1].updatedAt, added[0].updatedAt)
    }

    @Test
    fun aMergeOfOnlyIdenticalEntriesSavesNothing() {
        val here = vault(entry(1), entry(2))
        val result = VaultEdits.mergeEntries(here.entries, Texts, random())(here, T0)
        assertSame(here, result.vault)
        assertEquals(2, result.value.skipped)
        assertTrue(result.value.addedIds.isEmpty())
    }

    @Test
    fun anIdRepeatedInTheOtherVaultIsAddedOnce() {
        val e = entry(3)
        val result = VaultEdits.mergeEntries(listOf(e, e, e.copy(password = "other")), Texts, random())(vault(entry(1)), T0)
        assertSound(result.vault)
        val added = result.vault.entries.drop(1)
        assertEquals(2, added.size)
        assertEquals(id(3), added[0].id)
        assertEquals(e.title, added[1].title) // not "(from backup)": no account here held the id
        assertEquals(1, result.value.skipped)
    }
}
