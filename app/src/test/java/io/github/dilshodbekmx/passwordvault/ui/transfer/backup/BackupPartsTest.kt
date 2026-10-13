package io.github.dilshodbekmx.passwordvault.ui.transfer.backup

import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.model.FileRef
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.data.SaveProblem
import io.github.dilshodbekmx.passwordvault.files.Cleanup
import io.github.dilshodbekmx.passwordvault.files.DocRef
import io.github.dilshodbekmx.passwordvault.files.Place
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.lock.LockReason
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupNotes
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupOffer
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupStep
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupTexts
import io.github.dilshodbekmx.passwordvault.ui.transfer.NoteDeleted
import io.github.dilshodbekmx.passwordvault.ui.transfer.backup.BackupRig.Companion.BACKUP_BYTES
import io.github.dilshodbekmx.passwordvault.ui.transfer.backup.BackupRig.Companion.DOC
import io.github.dilshodbekmx.passwordvault.ui.transfer.backup.BackupRig.Companion.ENTRIES
import io.github.dilshodbekmx.passwordvault.ui.transfer.backup.BackupRig.Companion.NOW
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
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
 * (the entry of "Make a backup now?" and of a flow that hands its own over), [BackupNotes] (the vault
 * forgets a backup and its permission goes, architecture.md §3.5) and the texts of a saved backup for
 * each place (ux.md §5.19 step 5 and the owner's decision 8).
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

    /**
     * The owner's decision 20 (build-plan.md §12): a place that is not the phone shows one line only,
     * that it may upload the file later (decision 8). Before it Google Drive also said "Saved to
     * Google Drive. It is uploaded when the phone is online.", which repeated it, and another place
     * "Saved to SD card.".
     */
    @Test
    fun aSavedBackupSaysWhereItIsOrThatThePlaceMayUploadItLater_inOneLine() {
        val name = "PasswordVault-backup-2026-10-08.pvault"
        val saved = UiText.plural(R.plurals.backup_saved, 52, 52, name)
        assertEquals(
            listOf(saved, UiText.of(R.string.backup_place_phone)),
            BackupTexts.saved(BackupStep.Saved(name, 52, Place.PhoneStorage)),
        )
        for (place in listOf(Place.GoogleDrive, Place.Other("SD card"))) {
            assertEquals("$place", listOf(saved, UiText.of(R.string.backup_upload_later)), BackupTexts.saved(BackupStep.Saved(name, 52, place)))
        }
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
}
