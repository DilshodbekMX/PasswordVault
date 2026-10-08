package io.github.dilshodbekmx.passwordvault.ui.transfer.backup

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.crypto.KdfUnavailable
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.data.BackupResult
import io.github.dilshodbekmx.passwordvault.data.SaveProblem
import io.github.dilshodbekmx.passwordvault.files.Cleanup
import io.github.dilshodbekmx.passwordvault.files.DocRef
import io.github.dilshodbekmx.passwordvault.files.FileTask
import io.github.dilshodbekmx.passwordvault.files.FileTaskStore
import io.github.dilshodbekmx.passwordvault.files.Place
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.lock.LockReason
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.MpPurpose
import io.github.dilshodbekmx.passwordvault.lock.MpResult
import io.github.dilshodbekmx.passwordvault.lock.PendingMasterPassword
import io.github.dilshodbekmx.passwordvault.settings.OneTime
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupDate
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupFiles
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupOffer
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupStep
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupTexts
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupViewModel
import io.github.dilshodbekmx.passwordvault.ui.transfer.Failure
import io.github.dilshodbekmx.passwordvault.ui.transfer.PasswordError
import io.github.dilshodbekmx.passwordvault.ui.transfer.Retry
import io.github.dilshodbekmx.passwordvault.ui.transfer.backup.BackupRig.Companion.BACKUP_BYTES
import io.github.dilshodbekmx.passwordvault.ui.transfer.backup.BackupRig.Companion.DOC
import io.github.dilshodbekmx.passwordvault.ui.transfer.backup.BackupRig.Companion.DRIVE_DOC
import io.github.dilshodbekmx.passwordvault.ui.transfer.backup.BackupRig.Companion.NOW
import io.github.dilshodbekmx.passwordvault.ui.transfer.backup.BackupRig.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.ui.transfer.backup.BackupRig.Companion.PASSWORD_BYTES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Build plan 5.2, [JVM]: "Make a backup" (ux.md §5.19; lock.md F12) against the fakes of the lock,
 * the vault and the documents, through the process's real `FileTaskStore`. The master password is
 * always asked, except in the offer after an entry; no backup while a new master password is
 * pending; the backup is made before the picker opens and checked after the write (the owner's
 * decision 5); every error of ux.md §5.19 and what becomes of the file (ux.md §4 item 3).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {
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

    private fun TestScope.rig(explained: Boolean = true): BackupRig = BackupRig(this, temp.newFolder()).also {
        it.vault.backupBytes = BACKUP_BYTES
        if (explained) it.settings.oneTime.value = setOf(OneTime.BACKUP_EXPLAINED)
    }

    private fun BackupRig.backup(offered: MasterProof? = null): BackupViewModel =
        make { BackupViewModel(lock, repository, files, settings, clock, dispatchers, offered) }

    /** Types the master password and submits it; the lock answers with [answer], or with a right one. */
    private fun BackupRig.submit(vm: BackupViewModel, answer: MpResult = MpResult.Right(proof())) {
        fakeLock.mpResults.add(answer)
        vm.password.setTextAndPlaceCursorAtEnd(PASSWORD)
        vm.submit()
        scope.runCurrent()
    }

    private fun BackupViewModel.step() = ui.value.step

    // ---- The master password, always, except in the offer after an entry ------------------------

    @Test
    fun theMasterPasswordIsAskedEvenRightAfterAMasterPasswordUnlock() = runTest {
        val rig = rig()
        // The unlock left its proof in the lock's state, and no offer was shown: Settings › Make a backup asks.
        rig.fakeLock.state.value = LockUiState.Unlocked(emptyList(), rig.proof())
        val offers = BackupOffer(rig.fakeLock.state, backgroundScope)
        runCurrent()
        val vm = rig.backup(offered = offers.take())
        assertEquals(BackupStep.Password(), vm.step())
        assertTrue("nothing asked yet", rig.fakeLock.purposes.isEmpty())

        val typed = rig.proof()
        rig.submit(vm, MpResult.Right(typed))
        assertEquals(listOf(MpPurpose.BACKUP), rig.fakeLock.purposes)
        assertEquals(listOf(typed), rig.repository.sealedWith)
        assertEquals("the field is emptied at the submit", "", vm.password.text.toString())
    }

    @Test
    fun theOfferAfterAnEntryUsesThatEntryAndAsksNothing() = runTest {
        val rig = rig()
        val entry = rig.proof()
        val offers = BackupOffer(rig.fakeLock.state, backgroundScope)
        rig.fakeLock.state.value = LockUiState.Unlocked(listOf(Notice.BackupNow(7)), entry)
        runCurrent()
        // "Back up now": the lock removes the offer and keeps the proof for the backup it starts.
        rig.fakeLock.state.value = LockUiState.Unlocked(emptyList(), entry)
        rig.opens()
        val vm = rig.backup(offered = offers.take())
        runCurrent()

        assertTrue("no master password was asked", rig.fakeLock.purposes.isEmpty())
        assertEquals(listOf(entry), rig.repository.sealedWith)
        val (opened, with) = rig.repository.openedWith.single()
        assertArrayEquals("opened once, the backup that was sealed", BACKUP_BYTES, opened)
        assertArrayEquals("with the entry's own bytes", PASSWORD_BYTES, with)
        assertTrue("the entry is dropped once the backup is made", dropped(entry))
        assertEquals(BackupStep.Choose(opening = true), vm.step())
        assertEquals(listOf("create ${BackupFiles.suggestedName(NOW)} application/octet-stream"), rig.launcher.calls)

        // Given out once: the next backup screen asks.
        assertNull(offers.take())
    }

    @Test
    fun anOfferAnsweredWithAnythingElseLeavesNoEntry() = runTest {
        val rig = rig()
        val entry = rig.proof()
        val offers = BackupOffer(rig.fakeLock.state, backgroundScope)
        rig.fakeLock.state.value = LockUiState.Unlocked(listOf(Notice.OlderCopy(3, NOW), Notice.BackupNow(7)), entry)
        runCurrent()
        // "Back up now" of the older-copy notice is not the offer, which is still listed: it asks.
        rig.fakeLock.state.value = LockUiState.Unlocked(listOf(Notice.BackupNow(7)), entry)
        assertNull(offers.take())
        assertEquals(BackupStep.Password(), rig.backup(offered = offers.take()).step())
        // "Later": the lock drops the proof of the unlock.
        rig.fakeLock.state.value = LockUiState.Unlocked(emptyList(), null)
        runCurrent()
        assertNull(offers.take())
    }

    @Test
    fun anEntryThatEndedBeforeItWasUsedAsksForTheMasterPassword() = runTest {
        val rig = rig()
        val entry = rig.proof()
        entry.drop() // the app stopped being visible after "Back up now" (lock.md §5.8)
        val vm = rig.backup(offered = entry)
        runCurrent()
        assertEquals(BackupStep.Password(), vm.step())
        assertTrue(rig.launcher.calls.isEmpty())
    }

    // ---- No backup while a new master password is pending ---------------------------------------

    @Test
    fun noBackupWhileANewMasterPasswordIsPending() = runTest {
        val rig = rig()
        rig.fakeLock.status.value = rig.fakeLock.status.value.copy(
            pending = PendingMasterPassword(requestedAtMs = NOW, by = Shortcut.PIN, creditedMs = 0, remainingMs = 86_400_000L, ready = false),
        )
        val vm = rig.backup()
        assertEquals(BackupStep.Pending, vm.step())
        vm.password.setTextAndPlaceCursorAtEnd(PASSWORD)
        vm.submit()
        runCurrent()
        assertTrue("nothing is checked", rig.fakeLock.purposes.isEmpty())

        // Not even with an entry offered: it is dropped, and nothing is sealed.
        val entry = rig.proof()
        val offered = rig.backup(offered = entry)
        runCurrent()
        assertEquals(BackupStep.Pending, offered.step())
        assertTrue(dropped(entry))
        assertTrue(rig.repository.sealedWith.isEmpty())
        assertTrue(rig.launcher.calls.isEmpty())
    }

    // ---- Steps 3 to 5 -------------------------------------------------------------------------

    @Test
    fun aBackupIsMadeBeforeThePickerSavedReadBackAndNoted() = runTest {
        val rig = rig()
        rig.opens()
        rig.fakeDocuments.names[DOC] = "PasswordVault-backup-2026-10-08 (1).pvault"
        val vm = rig.backup()
        val typed = rig.proof()
        rig.submit(vm, MpResult.Right(typed))

        // Made and opened once before the picker opened, with the bytes of the entry just checked.
        assertEquals(listOf(typed), rig.repository.sealedWith)
        assertArrayEquals(PASSWORD_BYTES, rig.repository.openedWith.single().second)
        assertTrue(dropped(typed))
        assertEquals(BackupStep.Choose(opening = true), vm.step())
        assertEquals(1, rig.session.begun)
        assertEquals(FileTask(FileTask.Kind.BACKUP, BackupViewModel.STEP_SAVE, null), rig.settings.fileTask.value)

        rig.answerSaveAs(DOC)
        val saved = vm.step() as BackupStep.Saved
        assertEquals("the name the place shows", "PasswordVault-backup-2026-10-08 (1).pvault", saved.name)
        assertEquals("two accounts outside Recently deleted", 2, saved.accounts)
        assertEquals(Place.PhoneStorage, saved.place)
        assertArrayEquals(BACKUP_BYTES, rig.fakeDocuments.files[DOC])
        assertTrue("read back after the write", DOC in rig.documents.reads)
        assertNull("the task is finished", rig.settings.fileTask.value)
        val noted = rig.vault.stored.backups.single()
        assertEquals(DOC, noted.uri)
        assertEquals(saved.name, noted.name)
        assertEquals(NOW, noted.savedAt)
        assertTrue("the permission stays while the vault notes it", DOC in rig.fakeDocuments.kept)
    }

    @Test
    fun aSavedBackupTheVaultCouldNotNoteKeepsNoPermission() = runTest {
        val rig = rig()
        rig.opens()
        rig.vault.failures.add(SaveProblem.FAILED) // the note in the vault
        val vm = rig.backup()
        rig.submit(vm)
        rig.answerSaveAs(DOC)
        assertTrue("the file is saved and checked all the same", vm.step() is BackupStep.Saved)
        assertTrue(rig.vault.stored.backups.isEmpty())
        assertTrue("nothing notes the document: its permission is given up", DOC in rig.fakeDocuments.released)
        assertArrayEquals(BACKUP_BYTES, rig.fakeDocuments.files[DOC])
    }

    @Test
    fun aPickerClosedWithoutAPlaceIsOfferedAgainWithTheSameBackup() = runTest {
        val rig = rig()
        rig.opens()
        val vm = rig.backup()
        rig.submit(vm)
        rig.closePicker(FileTaskStore.Action.CREATE)
        assertEquals("no message", BackupStep.Choose(opening = false), vm.step())

        vm.choose()
        runCurrent()
        assertEquals(BackupStep.Choose(opening = true), vm.step())
        assertEquals(2, rig.launcher.calls.size)
        assertEquals("one master password, one backup", 1, rig.fakeLock.purposes.size)
        assertEquals(1, rig.repository.sealedWith.size)

        rig.answerSaveAs(DOC)
        assertTrue(vm.step() is BackupStep.Saved)
    }

    @Test
    fun aPlaceThatDoesNotTakeTheFileLosesItsEmptyFileAndAnotherIsOffered() = runTest {
        val rig = rig()
        rig.opens()
        rig.fakeDocuments.writeResults.add(false)
        val vm = rig.backup()
        rig.submit(vm)
        rig.answerSaveAs(DOC)
        assertEquals(BackupStep.Failed(Failure.WRITE, Cleanup.Removed, null, Retry.CHOOSE), vm.step())
        assertFalse("the empty file is removed", DOC in rig.fakeDocuments.files)
        assertNull(rig.settings.fileTask.value)
        assertEquals(UiText.of(R.string.backup_write_failed), BackupTexts.failure(Failure.WRITE))
        assertEquals(UiText.of(R.string.backup_file_removed), BackupTexts.failedFile(Cleanup.Removed, null))

        vm.retry()
        runCurrent()
        assertEquals(BackupStep.Choose(opening = true), vm.step())
        rig.answerSaveAs("$DOC-2")
        assertTrue(vm.step() is BackupStep.Saved)
        assertEquals("one master password", 1, rig.fakeLock.purposes.size)
    }

    @Test
    fun aFileThatCannotBeDeletedIsLeftForTheUserWithItsName() = runTest {
        val rig = rig()
        rig.opens()
        rig.fakeDocuments.writeResults.add(false)
        rig.fakeDocuments.notDeletable += DRIVE_DOC
        rig.fakeDocuments.places[DRIVE_DOC] = Place.GoogleDrive
        rig.fakeDocuments.names[DRIVE_DOC] = "PasswordVault-backup-2026-10-08.pvault"
        val vm = rig.backup()
        rig.submit(vm)
        rig.answerSaveAs(DRIVE_DOC)
        val failed = vm.step() as BackupStep.Failed
        assertEquals(Failure.WRITE, failed.why)
        assertEquals(Cleanup.Left(DocRef(DRIVE_DOC), Place.GoogleDrive), failed.cleanup)
        assertEquals(
            UiText.of(R.string.backup_file_left_drive, "PasswordVault-backup-2026-10-08.pvault"),
            BackupTexts.failedFile(failed.cleanup, failed.name),
        )
    }

    @Test
    fun aSavedFileThatDiffersFromTheBackupIsCaughtAndRemoved() = runTest {
        for (back in listOf(BACKUP_BYTES.copyOf(4), BACKUP_BYTES.copyOf().also { it[10] = 0 }, ByteArray(0))) {
            val rig = rig()
            rig.opens()
            rig.documents.readAs[DOC] = back // the provider cut the file, changed it, or kept nothing
            val vm = rig.backup()
            rig.submit(vm)
            rig.answerSaveAs(DOC)
            assertEquals(BackupStep.Failed(Failure.CHECK, Cleanup.Removed, null, Retry.CHOOSE), vm.step())
            assertFalse(DOC in rig.fakeDocuments.files)
            assertTrue("not noted", rig.vault.stored.backups.isEmpty())
            assertEquals(UiText.of(R.string.backup_check_failed), BackupTexts.failure(Failure.CHECK))
        }
    }

    @Test
    fun aBackupThatDoesNotOpenWithThePasswordIsNeverSaved() = runTest {
        for (answer in listOf(BackupResult.Wrong, BackupResult.Damaged, BackupResult.WrongOrDamaged)) {
            val rig = rig()
            rig.vault.backupResults.add(answer)
            val vm = rig.backup()
            rig.submit(vm)
            assertEquals("$answer", BackupStep.Failed(Failure.NOT_MADE, null, null, Retry.PASSWORD), vm.step())
            assertTrue("no picker", rig.launcher.calls.isEmpty())
            vm.retry()
            assertEquals(BackupStep.Password(), vm.step())
        }
    }

    @Test
    fun tooLittleMemoryToMakeTheBackupSaysSo() = runTest {
        val rig = rig()
        rig.repository.sealThrows = KdfUnavailable(null)
        val vm = rig.backup()
        rig.submit(vm)
        assertEquals(BackupStep.Failed(Failure.NO_MEMORY, null, null, Retry.PASSWORD), vm.step())
        assertEquals(UiText.of(R.string.backup_no_memory_make), BackupTexts.failure(Failure.NO_MEMORY))

        rig.vault.backupResults.add(BackupResult.NoMemory)
        vm.retry()
        rig.submit(vm)
        assertEquals(BackupStep.Failed(Failure.NO_MEMORY, null, null, Retry.PASSWORD), vm.step())
    }

    @Test
    fun aPickerThatCannotOpenSaysSo() = runTest {
        val rig = rig()
        rig.opens()
        rig.store.detach(rig.launcher) // no activity: nothing can be opened
        val vm = rig.backup()
        rig.submit(vm)
        assertEquals(BackupStep.Failed(Failure.PICKER, null, null, Retry.CHOOSE), vm.step())
        rig.store.attach(rig.launcher)
        vm.retry()
        runCurrent()
        assertEquals(BackupStep.Choose(opening = true), vm.step())
    }

    // ---- What the lock answers (lock.md §13.3) --------------------------------------------------

    @Test
    fun eachAnswerOfTheLockHasItsLine() = runTest {
        val rig = rig()
        val vm = rig.backup()

        rig.submit(vm, MpResult.Wrong)
        assertEquals(BackupStep.Password(error = PasswordError.Wrong), vm.step())
        assertEquals(UiText.of(R.string.backup_wrong), BackupTexts.passwordError(PasswordError.Wrong))

        rig.submit(vm, MpResult.NoMemory)
        assertEquals(BackupStep.Password(error = PasswordError.NoMemory), vm.step())
        assertEquals(UiText.of(R.string.backup_no_memory), BackupTexts.passwordError(PasswordError.NoMemory))

        rig.submit(vm, MpResult.OlderPassword(changedAtMs = NOW - 86_400_000L, unfinishedChange = false))
        val older = (vm.step() as BackupStep.Password).error as PasswordError.Older
        assertEquals(
            UiText.of(R.string.backup_older, BackupDate(NOW - 86_400_000L, BackupDate.Style.DAY_MONTH)),
            BackupTexts.passwordError(older),
        )
        assertEquals(
            UiText.of(R.string.backup_older_unfinished, BackupDate(NOW, BackupDate.Style.DAY_MONTH)),
            BackupTexts.passwordError(PasswordError.Older(NOW, unfinished = true)),
        )

        rig.submit(vm, MpResult.TooLate)
        assertEquals("nothing was checked: no line", BackupStep.Password(), vm.step())
        assertTrue(rig.repository.sealedWith.isEmpty())
    }

    @Test
    fun aWaitCountsDownAndThenTheFieldTakesATryAgain() = runTest {
        val rig = rig()
        val vm = rig.backup()
        val until = rig.clock.elapsedMs() + 30_000L
        rig.submit(vm, MpResult.Wait(until))
        assertEquals(BackupStep.Password(waitUntilElapsedMs = until), vm.step())
        assertEquals(30, vm.ui.value.waitSeconds)

        // A submit during the wait checks nothing.
        vm.password.setTextAndPlaceCursorAtEnd(PASSWORD)
        vm.submit()
        runCurrent()
        assertEquals(1, rig.fakeLock.purposes.size)

        rig.clock.advance(1_000L)
        runCurrent()
        assertEquals(29, vm.ui.value.waitSeconds)
        rig.clock.advance(29_000L)
        runCurrent()
        assertNull(vm.ui.value.waitSeconds)
        rig.submit(vm, MpResult.Wrong)
        assertEquals(2, rig.fakeLock.purposes.size)
    }

    // ---- An unfinished backup (ux.md §4) ----------------------------------------------------------

    @Test
    fun aBackupALockCutShortAsksAgainAndGoesIntoItsFile() = runTest {
        val rig = rig()
        rig.settings.fileTask.value = FileTask(FileTask.Kind.BACKUP, BackupViewModel.STEP_SAVE, DocRef(DOC))
        rig.fakeDocuments.files[DOC] = ByteArray(0)
        rig.opens()
        val vm = rig.backup()
        assertEquals(BackupStep.Password(), vm.step())
        assertTrue("This was paused when the app locked.", vm.ui.value.paused)

        rig.submit(vm)
        assertTrue("no picker: its place was chosen", rig.launcher.calls.isEmpty())
        assertTrue(vm.step() is BackupStep.Saved)
        assertArrayEquals(BACKUP_BYTES, rig.fakeDocuments.files[DOC])
        assertNull(rig.settings.fileTask.value)
    }

    @Test
    fun cancellingABackupWhosePlaceWasChosenRemovesItsEmptyFileAndSaysSo() = runTest {
        val rig = rig()
        rig.settings.fileTask.value = FileTask(FileTask.Kind.BACKUP, BackupViewModel.STEP_SAVE, DocRef(DOC))
        rig.fakeDocuments.files[DOC] = ByteArray(0)
        val vm = rig.backup()
        vm.cancel()
        runCurrent()
        val stopped = vm.step() as BackupStep.Stopped
        assertEquals(Cleanup.Removed, stopped.cleanup)
        assertEquals(UiText.of(R.string.backup_stopped_removed), BackupTexts.stopped(stopped.cleanup, stopped.name))
        assertFalse(DOC in rig.fakeDocuments.files)
        assertNull(rig.settings.fileTask.value)
        assertTrue("its permission is given up", DOC in rig.fakeDocuments.released)
    }

    @Test
    fun anEmptyFileThatCannotBeDeletedIsNamedWithItsPlace() = runTest {
        val rig = rig()
        val sd = "content://com.android.externalstorage.documents/document/1A2B-3C4D%3APasswordVault-backup-2026-10-08.pvault"
        rig.settings.fileTask.value = FileTask(FileTask.Kind.BACKUP, BackupViewModel.STEP_SAVE, DocRef(sd))
        rig.fakeDocuments.files[sd] = ByteArray(0)
        rig.fakeDocuments.notDeletable += sd
        rig.fakeDocuments.places[sd] = Place.Other("SD card")
        rig.fakeDocuments.names[sd] = "PasswordVault-backup-2026-10-08.pvault"
        val vm = rig.backup()
        vm.cancel()
        runCurrent()
        val stopped = vm.step() as BackupStep.Stopped
        assertEquals(
            UiText.of(R.string.backup_stopped_left_other, "PasswordVault-backup-2026-10-08.pvault", "SD card"),
            BackupTexts.stopped(stopped.cleanup, stopped.name),
        )
    }

    @Test
    fun aWriteANewProcessCutOffIsRemovedWhenTheBackupIsGivenUp() = runTest {
        val rig = rig()
        // The write had begun when the process ended: the file holds part of a backup.
        rig.settings.fileTask.value = FileTask(FileTask.Kind.BACKUP, BackupViewModel.STEP_SAVE, DocRef(DOC), written = true)
        rig.fakeDocuments.files[DOC] = BACKUP_BYTES.copyOf(5)
        val vm = rig.backup()
        vm.cancel()
        runCurrent()
        assertEquals(Cleanup.Removed, (vm.step() as BackupStep.Stopped).cleanup)
        assertFalse(DOC in rig.fakeDocuments.files)
    }

    @Test
    fun withoutAChosenPlaceBackClosesTheScreen() = runTest {
        val rig = rig()
        val vm = rig.backup()
        var left = 0
        backgroundScope.launch { vm.leave.collect { left++ } }
        runCurrent()
        vm.cancel()
        runCurrent()
        assertEquals(1, left)
    }

    // ---- Locks, the first time, sending -----------------------------------------------------------

    @Test
    fun aLockDropsWhatWasTypedAndTheBackupMade() = runTest {
        val rig = rig()
        rig.opens()
        val vm = rig.backup()
        rig.submit(vm)
        assertEquals(BackupStep.Choose(opening = true), vm.step())
        vm.password.setTextAndPlaceCursorAtEnd("half typed")
        rig.fakeLock.lock(LockReason.SCREEN_OFF)
        runCurrent()
        assertEquals("", vm.password.text.toString())
        assertEquals(BackupStep.Password(), vm.step())
        assertTrue("the task waits for the next unlock", vm.ui.value.let { !it.paused })
        vm.choose()
        runCurrent()
        assertEquals("the backup made is gone: nothing to choose a place for", 1, rig.launcher.calls.size)
    }

    @Test
    fun theFirstBackupExplainsWhatABackupIs() = runTest {
        val rig = rig(explained = false)
        val vm = rig.backup()
        assertEquals(BackupStep.Explain, vm.step())
        vm.explained()
        runCurrent()
        assertEquals(BackupStep.Password(), vm.step())
        assertTrue(OneTime.BACKUP_EXPLAINED in rig.settings.oneTime.value)
        assertEquals(BackupStep.Password(), rig.backup().step())
    }

    @Test
    fun sendToAnotherAppSharesACopyOfTheBackupUnderItsName() = runTest {
        val rig = rig()
        rig.opens()
        rig.fakeDocuments.names[DOC] = "PasswordVault-backup-2026-10-08.pvault"
        val vm = rig.backup()
        rig.submit(vm)
        rig.answerSaveAs(DOC)
        vm.send("Send the backup")
        runCurrent()
        assertEquals("share PasswordVault-backup-2026-10-08.pvault", rig.launcher.calls.last())
        assertArrayEquals(BACKUP_BYTES, rig.launcher.shared?.readBytes())
        assertEquals("Send the backup", rig.launcher.sharedTitle)
        assertFalse((vm.step() as BackupStep.Saved).sendFailed)

        rig.store.detach(rig.launcher)
        vm.send("Send the backup")
        runCurrent()
        assertTrue((vm.step() as BackupStep.Saved).sendFailed)
    }

    @Test
    fun whileAStepRunsBackDoesNothing() = runTest {
        val rig = rig()
        val vm = rig.backup()
        rig.fakeLock.mpResults.add(MpResult.Right(rig.proof()))
        vm.password.setTextAndPlaceCursorAtEnd(PASSWORD)
        vm.submit()
        assertEquals(BackupStep.Password(checking = true), vm.step())
        assertTrue(vm.busy)
        vm.cancel()
        assertEquals(BackupStep.Password(checking = true), vm.step())
        runCurrent()
    }

    private fun dropped(proof: MasterProof): Boolean = try {
        proof.useBytes { }
        false
    } catch (e: IllegalStateException) {
        true
    }
}
