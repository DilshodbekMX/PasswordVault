package io.github.dilshodbekmx.passwordvault.core.model

import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.MINUTE
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.T0
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.entry
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.id
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.vault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The vault-level data of architecture.md §4.3: the one draft, the files saved, the security marks, undo, restore. */
class VaultDataTest {
    private val form = DraftEntry(title = "Yangi", password = "gen-🔑")

    @Test
    fun thereIsOneDraftAndTheNewestWins() {
        val first = Draft(null, T0, form)
        val v1 = VaultEdits.setDraft(first)(Vault(), T0).vault
        assertEquals(first, v1.draft)
        val second = Draft(id(1), T0 + MINUTE, DraftEntry(title = "Edited"))
        val v2 = VaultEdits.setDraft(second)(vault(entry(1)).copy(draft = first), T0 + MINUTE).vault
        assertEquals(second, v2.draft)
        assertEquals(1, v2.entries.size)
    }

    @Test
    fun theSameDraftAgainSavesNothingWhateverItsTime() {
        val v = VaultEdits.setDraft(Draft(null, T0, form))(Vault(), T0).vault
        val again = VaultEdits.setDraft(Draft(null, T0 + 9 * MINUTE, form))(v, T0 + 9 * MINUTE)
        assertSame(v, again.vault)
        assertEquals(T0, again.vault.draft?.savedAt)
        // The same fields for another entry are another draft.
        assertEquals(id(3), VaultEdits.setDraft(Draft(id(3), T0, form))(v, T0).vault.draft?.entryId)
    }

    @Test
    fun aDraftIsKeptAsTypedButNeverWithAnUnpairedSurrogate() {
        val typed = DraftEntry(title = " a\tb ", password = "  x  ", notes = "n\r\n", urls = listOf(" gmail.com "))
        assertEquals(typed, VaultEdits.setDraft(Draft(null, T0, typed))(Vault(), T0).vault.draft?.entry)
        val broken = DraftEntry(title = "T\uD800", fields = listOf(CustomField("F", "v\uDC00", secret = true)))
        val stored = VaultEdits.setDraft(Draft(null, T0, broken))(Vault(), T0).vault
        assertEquals("T�", stored.draft?.entry?.title)
        assertEquals("v�", stored.draft?.entry?.fields?.single()?.value)
        ModelFixtures.assertSound(stored)
    }

    @Test
    fun clearTheDraft() {
        val v = Vault(draft = Draft(null, T0, form))
        assertNull(VaultEdits.clearDraft()(v, T0).vault.draft)
        val none = Vault()
        assertSame(none, VaultEdits.clearDraft()(none, T0).vault)
    }

    @Test
    fun plainExportsAndBackupsAreNotedAndForgotten() {
        val uri = "content://com.android.providers.downloads.documents/document/17"
        val v1 = VaultEdits.notePlainExport(uri, "passwords-2026-10-07.csv")(Vault(), T0).vault
        assertEquals(listOf(FileRef(uri, "passwords-2026-10-07.csv", T0)), v1.plainExports)
        // Saved again to the same document: one note, the newest.
        val v2 = VaultEdits.notePlainExport(uri, "passwords-2026-10-08.csv")(v1, T0 + MINUTE).vault
        assertEquals(listOf(FileRef(uri, "passwords-2026-10-08.csv", T0 + MINUTE)), v2.plainExports)
        val forgotten = VaultEdits.forgetPlainExport(uri)(v2, T0)
        assertTrue(forgotten.value)
        assertTrue(forgotten.vault.plainExports.isEmpty())
        val nothing = VaultEdits.forgetPlainExport(uri)(forgotten.vault, T0)
        assertFalse(nothing.value)
        assertSame(forgotten.vault, nothing.vault)

        val b1 = VaultEdits.noteBackup("content://drive/1", "PasswordVault-backup-2026-10-06.pvault")(Vault(), T0).vault
        val b2 = VaultEdits.noteBackup("content://drive/2", "PasswordVault-backup-2026-10-07.pvault")(b1, T0 + MINUTE).vault
        assertEquals(listOf("content://drive/1", "content://drive/2"), b2.backups.map { it.uri })
        assertEquals(listOf(T0, T0 + MINUTE), b2.backups.map { it.savedAt })
        val b3 = VaultEdits.forgetBackup("content://drive/1")(b2, T0)
        assertTrue(b3.value)
        assertEquals(listOf("content://drive/2"), b3.vault.backups.map { it.uri })
        assertSame(b3.vault, VaultEdits.forgetBackup("content://drive/1")(b3.vault, T0).vault)
    }

    @Test
    fun theMasterPasswordListFollowsTheVault() {
        val v = vault(entry(1), entry(2), entry(3))
        val set = VaultEdits.setMasterPasswordMatches(setOf(id(3), id(1), id(9)))(v, T0)
        assertEquals(listOf(id(1), id(3)), set.vault.masterPasswordMatches)
        assertSame(set.vault, VaultEdits.setMasterPasswordMatches(listOf(id(1), id(3), id(1)))(set.vault, T0).vault)
        assertTrue(VaultEdits.setMasterPasswordMatches(emptyList())(set.vault, T0).vault.masterPasswordMatches.isEmpty())
    }

    /**
     * The owner's decision 23 (build-plan.md §12): an account found to share the master password is
     * a new result even when it was last edited before the last visit of the Security tab (after a
     * master-password change made it equal to an old account's password, say), so a list that gains
     * an entry resets [Vault.securitySeenAt]: the dot shows. Losing one, or the same list, keeps it.
     */
    @Test
    fun aNewMasterPasswordMatch_resetsTheSecurityTabsVisit() {
        val v = vault(entry(1), entry(2), entry(3)).copy(masterPasswordMatches = listOf(id(1)), securitySeenAt = T0 + MINUTE)
        val gained = VaultEdits.setMasterPasswordMatches(listOf(id(1), id(2)))(v, T0 + 2 * MINUTE).vault
        assertEquals(listOf(id(1), id(2)), gained.masterPasswordMatches)
        assertNull("a new result: the dot shows again", gained.securitySeenAt)
        val swapped = VaultEdits.setMasterPasswordMatches(listOf(id(3)))(v, T0).vault
        assertNull("one lost and one gained is still a new one", swapped.securitySeenAt)
        val lost = VaultEdits.setMasterPasswordMatches(emptyList())(v, T0).vault
        assertEquals("nothing new: the visit stands", T0 + MINUTE, lost.securitySeenAt)
        assertSame("the same list: nothing written", v, VaultEdits.setMasterPasswordMatches(listOf(id(1), id(9)))(v, T0).vault)
    }

    @Test
    fun securitySeen() {
        val seen = VaultEdits.markSecuritySeen()(Vault(), T0 + MINUTE).vault
        assertEquals(T0 + MINUTE, seen.securitySeenAt)
        assertSame(seen, VaultEdits.markSecuritySeen()(seen, T0 + MINUTE).vault)
    }

    @Test
    fun aRestoreKeepsTheEntriesAndForgetsTheOldInstallationsFiles() {
        // architecture.md §4.12: keeps entries, draft, masterPasswordMatches and unknown top-level
        // keys; resets plainExports and backups to [] and securitySeenAt to null.
        val backup = Vault(
            entries = listOf(entry(1), entry(2, deletedAt = T0)),
            draft = Draft(null, T0, form),
            plainExports = listOf(FileRef("content://x/1", "passwords.csv", T0)),
            backups = listOf(FileRef("content://x/2", "backup.pvault", T0)),
            masterPasswordMatches = listOf(id(1)),
            securitySeenAt = T0,
            extras = mapOf("futureTopKey" to "{\"n\":1.50}"),
        )
        val restored = VaultEdits.forRestore(backup)
        assertEquals(backup.copy(plainExports = emptyList(), backups = emptyList(), securitySeenAt = null), restored)
    }

    @Test
    fun undoPutsBackExactlyTheVaultItIsGiven() {
        val before = vault(entry(1), entry(2)).copy(draft = Draft(id(1), T0, form), backups = listOf(FileRef("u", "n", T0)))
        val after = before.copy(entries = before.entries + entry(3))
        val undone = VaultEdits.replaceVault(before)(after, T0 + MINUTE)
        assertTrue(undone.value)
        assertSame(before, undone.vault)
        val same = VaultEdits.replaceVault(before.copy())(before, T0)
        assertSame("an equal vault is nothing to save", before, same.vault)
        // A vault with two entries of one id is never put in place.
        val twice = vault(entry(1), entry(1, title = "copy"))
        val refused = VaultEdits.replaceVault(twice)(after, T0)
        assertFalse(refused.value)
        assertSame(after, refused.vault)
    }
}
