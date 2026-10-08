package io.github.dilshodbekmx.passwordvault.ui.transfer.backup

import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.model.EditTexts
import io.github.dilshodbekmx.passwordvault.core.model.FileRef
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.data.BackupResult
import io.github.dilshodbekmx.passwordvault.data.SaveProblem
import io.github.dilshodbekmx.passwordvault.files.Cleanup
import io.github.dilshodbekmx.passwordvault.files.DocRef
import io.github.dilshodbekmx.passwordvault.files.Place
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.lock.LockReason
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupMerge
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupNotes
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupOffer
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupStep
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupTexts
import io.github.dilshodbekmx.passwordvault.ui.transfer.MergeOutcome
import io.github.dilshodbekmx.passwordvault.ui.transfer.NoteDeleted
import io.github.dilshodbekmx.passwordvault.ui.transfer.RestoreProblem
import io.github.dilshodbekmx.passwordvault.ui.transfer.backup.BackupRig.Companion.BACKUP_BYTES
import io.github.dilshodbekmx.passwordvault.ui.transfer.backup.BackupRig.Companion.DOC
import io.github.dilshodbekmx.passwordvault.ui.transfer.backup.BackupRig.Companion.ENTRIES
import io.github.dilshodbekmx.passwordvault.ui.transfer.backup.BackupRig.Companion.NOW
import io.github.dilshodbekmx.passwordvault.ui.transfer.backup.BackupRig.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.ui.transfer.backup.BackupRig.Companion.PASSWORD_BYTES
import io.github.dilshodbekmx.passwordvault.ui.transfer.backup.BackupRig.Companion.entry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Build plan 5.2, [JVM]: the parts of the backup screens that are not a ViewModel. [BackupOffer]
 * (the entry of "Make a backup now?" and of a flow that hands its own over), [BackupMerge] ("Backup
 * file into an existing vault", ux.md §5.21), [BackupNotes] (the vault forgets a backup and its
 * permission goes, architecture.md §3.5) and the texts of a saved backup for each place (ux.md §5.19
 * step 5 and the owner's decision 8).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupPartsTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.rig(vault: Vault = Vault(entries = ENTRIES)) = BackupRig(this, temp.newFolder(), vault)

    // ---- BackupOffer ----------------------------------------------------------------------------

    @Test
    fun aProofHandedOverIsGivenOutOnceAndDroppedWhenTheAppLocks() = runTest {
        val rig = rig()
        val offers = BackupOffer(rig.fakeLock.state, backgroundScope)
        runCurrent()
        val changed = rig.proof()
        offers.offer(changed) // the end of a change of the master password (lock.md F8 step 6)
        assertSame(changed, offers.take())
        assertNull(offers.take())

        val unused = rig.proof()
        offers.offer(unused)
        rig.fakeLock.lock(LockReason.SCREEN_OFF)
        runCurrent()
        assertTrue("dropped at the lock", dropped(unused))
        assertNull(offers.take())
    }

    @Test
    fun anOfferIsOnlyEverTheEntryItWasShownWith() = runTest {
        val rig = rig()
        val offers = BackupOffer(rig.fakeLock.state, backgroundScope)
        val first = rig.proof()
        rig.fakeLock.state.value = LockUiState.Unlocked(listOf(Notice.BackupNow(1)), first)
        runCurrent()
        // The app locked and was opened again with another master password, without an offer.
        rig.fakeLock.state.value = LockUiState.Locked(rig.fakeLock.lockScreen)
        runCurrent()
        val second = rig.proof()
        rig.fakeLock.state.value = LockUiState.Unlocked(emptyList(), second)
        runCurrent()
        assertNull(offers.take())
    }

    // ---- BackupMerge ----------------------------------------------------------------------------

    @Test
    fun aBackupFileIsMergedByItsRulesWithItsOwnPassword() = runTest {
        val gmail = entry("00000000-0000-4000-8000-00000000000a", "Gmail")
        val bank = entry("00000000-0000-4000-8000-00000000000b", "Bank")
        val rig = rig(Vault(entries = listOf(gmail, bank)))
        val merge = BackupMerge(rig.repository, FakeRandom())
        val bankChanged = bank.copy(password = "another", updatedAt = NOW)
        val brandNew = entry("00000000-0000-4000-8000-0000000000ff", "New")
        rig.vault.backupResults.add(BackupResult.Opened(Vault(entries = listOf(gmail, bankChanged, brandNew)), NOW))
        val outcome = merge.merge(BACKUP_BYTES, PASSWORD.toCharArray(), Texts)
        val merged = (outcome as MergeOutcome.Merged).merged
        assertEquals("the identical one", 1, merged.skipped)
        assertEquals("the one with an id of the vault and other content", 1, merged.renamed)
        assertEquals(2, merged.addedIds.size)
        assertEquals(listOf("Gmail", "Bank", "Bank (from backup)", "New"), rig.vault.stored.entries.map { it.title })
        assertEquals("nothing is overwritten", bank, rig.vault.stored.entries[1])
        assertArrayEquals(PASSWORD_BYTES, rig.repository.openedWith.single().second)
    }

    @Test
    fun aBackupThatDoesNotOpenGivesTheRestoresMessage() = runTest {
        val rig = rig()
        val merge = BackupMerge(rig.repository, FakeRandom())
        for ((answer, problem) in listOf(
            BackupResult.Wrong to RestoreProblem.WRONG,
            BackupResult.WrongOrDamaged to RestoreProblem.WRONG_OR_DAMAGED,
            BackupResult.Damaged to RestoreProblem.DAMAGED,
            BackupResult.Newer to RestoreProblem.NEWER,
            BackupResult.NotOurs to RestoreProblem.NOT_OURS_LOCKED,
            BackupResult.NoMemory to RestoreProblem.NO_MEMORY,
        )) {
            rig.vault.backupResults.add(answer)
            assertEquals(MergeOutcome.NotOpened(problem), merge.merge(BACKUP_BYTES, PASSWORD.toCharArray(), Texts))
        }
        rig.vault.backupResults.add(BackupResult.Opened(Vault(entries = listOf(entry("00000000-0000-4000-8000-0000000000ee", "New"))), NOW))
        rig.vault.failures.add(SaveProblem.TOO_LARGE)
        assertEquals(MergeOutcome.NotSaved(SaveProblem.TOO_LARGE), merge.merge(BACKUP_BYTES, PASSWORD.toCharArray(), Texts))
        assertEquals(ENTRIES, rig.vault.stored.entries)
    }

    // ---- BackupNotes ----------------------------------------------------------------------------

    @Test
    fun aBackupTheVaultForgetsGivesUpItsPermission() = runTest {
        val noted = FileRef(DOC, "PasswordVault-backup-2026-10-08.pvault", NOW)
        val other = FileRef("$DOC-2", "PasswordVault-backup-2026-10-01.pvault", NOW - 1)
        val rig = rig(Vault(entries = ENTRIES, backups = listOf(other, noted)))
        rig.fakeDocuments.files[DOC] = BACKUP_BYTES
        rig.fakeDocuments.kept += DOC
        val notes = BackupNotes(rig.repository, rig.files)

        assertEquals(NoteDeleted.DELETED, notes.delete(noted))
        assertFalse(DOC in rig.fakeDocuments.files)
        assertEquals(listOf(other), rig.vault.stored.backups)
        assertEquals(listOf(DOC), rig.fakeDocuments.released)

        // A place that does not allow deleting: noted as before, permission kept.
        rig.fakeDocuments.notDeletable += other.uri
        assertEquals(NoteDeleted.NOT_DELETABLE, notes.delete(other))
        assertEquals(listOf(other), rig.vault.stored.backups)
        assertEquals(listOf(DOC), rig.fakeDocuments.released)

        // A save that fails keeps the note and the permission.
        rig.vault.failures.add(SaveProblem.FAILED)
        assertFalse(notes.forget(other))
        assertEquals(listOf(other), rig.vault.stored.backups)
        assertTrue(notes.forget(other))
        assertEquals(listOf(DOC, other.uri), rig.fakeDocuments.released)
    }

    // ---- The texts of a saved backup --------------------------------------------------------------

    @Test
    fun aSavedBackupSaysWhereItIsAndWhetherThePlaceMayUploadItLater() {
        val name = "PasswordVault-backup-2026-10-08.pvault"
        val saved = UiText.plural(R.plurals.backup_saved, 52, 52, name)
        assertEquals(
            listOf(saved, UiText.of(R.string.backup_place_phone)),
            BackupTexts.saved(BackupStep.Saved(name, 52, Place.PhoneStorage)),
        )
        assertEquals(
            listOf(saved, UiText.of(R.string.backup_place_drive), UiText.of(R.string.backup_upload_later)),
            BackupTexts.saved(BackupStep.Saved(name, 52, Place.GoogleDrive)),
        )
        assertEquals(
            listOf(saved, UiText.of(R.string.backup_place_other, "SD card"), UiText.of(R.string.backup_upload_later)),
            BackupTexts.saved(BackupStep.Saved(name, 52, Place.Other("SD card"))),
        )
    }

    @Test
    fun whatBecameOfTheFileIsSaidForEachPlace() {
        val name = "PasswordVault-backup-2026-10-08.pvault"
        val doc = DocRef(DOC)
        assertEquals(UiText.of(R.string.backup_file_left_phone, name), BackupTexts.failedFile(Cleanup.Left(doc, Place.PhoneStorage), name))
        assertEquals(UiText.of(R.string.backup_file_left_other, name, "USB"), BackupTexts.failedFile(Cleanup.Left(doc, Place.Other("USB")), name))
        assertNull(BackupTexts.failedFile(Cleanup.Kept, null))
        assertNull(BackupTexts.failedFile(null, null))
        assertEquals(UiText.of(R.string.backup_stopped_left_phone, name), BackupTexts.stopped(Cleanup.Left(doc, Place.PhoneStorage), name))
        assertEquals(UiText.of(R.string.backup_stopped_left_drive, name), BackupTexts.stopped(Cleanup.Left(doc, Place.GoogleDrive), name))
        assertNull("an existing file of the user's is not mentioned", BackupTexts.stopped(Cleanup.Kept, null))
        assertNull(BackupTexts.stopped(Cleanup.NoDocument, null))
    }

    private fun dropped(proof: MasterProof): Boolean = try {
        proof.useBytes { }
        false
    } catch (e: IllegalStateException) {
        true
    }

    private object Texts : EditTexts {
        override fun untitled(nowMs: Long) = "Untitled"

        override fun fromBackup(title: String) = "$title (from backup)"

        override fun fromFile(title: String) = "$title (from file)"
    }
}
