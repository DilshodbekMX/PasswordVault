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
     * The owner's decision 20 (build-plan.md §12) with its correction of 2026-10-13: a place that is
     * not the phone says where the file was saved, "Saved to Google Drive." or "Saved to SD card.",
     * then that it may upload the file later (decision 8). Only Google Drive's "It is uploaded when
     * the phone is online." went, which repeated that line; the first reading of decision 20 had
     * dropped the place's name as well.
     */
    @Test
    fun aSavedBackupSaysWhereItIs_andThatAPlaceThatIsNotThePhoneMayUploadItLater() {
        val name = "PasswordVault-backup-2026-10-08.pvault"
        val saved = UiText.plural(R.plurals.backup_saved, 52, 52, name)
        val later = UiText.of(R.string.backup_upload_later)
        assertEquals(
            listOf(saved, UiText.of(R.string.backup_place_phone)),
            BackupTexts.saved(BackupStep.Saved(name, 52, Place.PhoneStorage)),
        )
        assertEquals(
            listOf(saved, UiText.of(R.string.backup_place_drive), later),
            BackupTexts.saved(BackupStep.Saved(name, 52, Place.GoogleDrive)),
        )
        assertEquals(
            listOf(saved, UiText.of(R.string.backup_place_other, "SD card"), later),
            BackupTexts.saved(BackupStep.Saved(name, 52, Place.Other("SD card"))),
        )
    }

    @Test
    fun theLinesOfAPlaceThatIsNotThePhone_sayWhatTheOwnerAskedFor() {
        assertEquals("Saved to Google Drive.", englishBackupString("backup_place_drive"))
        assertEquals("Saved to %1\$s.", englishBackupString("backup_place_other"))
        assertEquals("This place may upload the file later. Open it there once to check that it arrived.", englishBackupString("backup_upload_later"))
    }

    /** The English text of the string [name] in the app's `values/strings_backup.xml`. */
    private fun englishBackupString(name: String): String {
        val dir = generateSequence(java.io.File("").absoluteFile) { it.parentFile }.map { java.io.File(it, "app/src/main/res/values") }.first { it.isDirectory }
        val xml = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(java.io.File(dir, "strings_backup.xml"))
        val nodes = xml.getElementsByTagName("string")
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as org.w3c.dom.Element
            if (e.getAttribute("name") == name) return e.textContent
        }
        throw AssertionError("no string $name")
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
