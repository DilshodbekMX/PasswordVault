package io.github.dilshodbekmx.passwordvault.ui.transfer.backup

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.CurrentVault
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.data.BackupResult
import io.github.dilshodbekmx.passwordvault.files.DocRef
import io.github.dilshodbekmx.passwordvault.files.FileTask
import io.github.dilshodbekmx.passwordvault.files.FileTaskStore
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.lock.CreateResult
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.ui.setup.PinSetupStart
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupDate
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupTexts
import io.github.dilshodbekmx.passwordvault.ui.transfer.RestoreProblem
import io.github.dilshodbekmx.passwordvault.ui.transfer.RestoreStep
import io.github.dilshodbekmx.passwordvault.ui.transfer.RestoreViewModel
import io.github.dilshodbekmx.passwordvault.ui.transfer.backup.BackupRig.Companion.BACKUP_BYTES
import io.github.dilshodbekmx.passwordvault.ui.transfer.backup.BackupRig.Companion.ENTRIES
import io.github.dilshodbekmx.passwordvault.ui.transfer.backup.BackupRig.Companion.FILE
import io.github.dilshodbekmx.passwordvault.ui.transfer.backup.BackupRig.Companion.MADE_MS
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Build plan 5.2, [JVM]: "Restore from a backup file" (ux.md §5.2; lock.md F13, F17 step 3) against
 * the fakes, through the process's real `FileTaskStore`. Every message of ux.md §5.2, "This file may
 * be damaged, or the password is wrong." with another try among them; nothing changes before the
 * backup has opened; the lock writes it with the password that opened it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestoreViewModelTest {
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

    /** A rig on Welcome (no vault), or on a lock screen when [locked]. */
    private fun TestScope.rig(locked: Boolean = false): BackupRig = BackupRig(this, temp.newFolder(), Vault()).also {
        it.fakeLock.state.value = if (locked) LockUiState.Locked(it.fakeLock.lockScreen) else LockUiState.NoVault(emptyList())
    }

    private fun BackupRig.restore(): RestoreViewModel = make { RestoreViewModel(lock, repository, files) }

    /** "Choose file", and the picker returns [FILE] holding [bytes]. */
    private fun BackupRig.chooseFile(vm: RestoreViewModel, bytes: ByteArray = BACKUP_BYTES) {
        vm.choose()
        scope.runCurrent()
        answerOpen(bytes)
    }

    /** Types the backup's master password and opens it; the vault answers [answer]. */
    private fun BackupRig.open(vm: RestoreViewModel, answer: BackupResult, typed: String = PASSWORD) {
        vault.backupResults.add(answer)
        vm.password.setTextAndPlaceCursorAtEnd(typed)
        vm.submit()
        scope.runCurrent()
    }

    private fun opened(vault: Vault = Vault(entries = ENTRIES)) = BackupResult.Opened(vault, MADE_MS)

    @Test
    fun aBackupIsOpenedWithItsPasswordShownAndRestoredWithThatPassword() = runTest {
        val rig = rig()
        val vm = rig.restore()
        assertEquals(RestoreStep.Start(), vm.step.value)
        rig.chooseFile(vm)
        assertEquals(listOf("open */*"), rig.launcher.calls)
        assertEquals(RestoreStep.Password(), vm.step.value)

        val backup = Vault(entries = ENTRIES)
        rig.open(vm, opened(backup))
        assertArrayEquals("the file as read", BACKUP_BYTES, rig.repository.openedWith.single().first)
        assertArrayEquals("the typed password's bytes", PASSWORD_BYTES, rig.repository.openedWith.single().second)
        val summary = vm.step.value as RestoreStep.Summary
        assertEquals(RestoreStep.Summary(accounts = 2, createdMs = MADE_MS, overCurrent = false), summary)
        assertEquals(
            UiText.plural(R.plurals.backup_restore_summary, 2, 2, BackupDate(MADE_MS, BackupDate.Style.DATE)),
            BackupTexts.summary(summary),
        )
        assertNull("the file has done its part", rig.settings.fileTask.value)
        assertTrue("and its permission is given up", FILE in rig.fakeDocuments.released)
        assertTrue("nothing on the phone changed yet", rig.fakeLock.state.value is LockUiState.NoVault)

        PinSetupStart.takeNewVault()
        vm.restore()
        runCurrent()
        assertTrue("the lock made the vault and unlocked", rig.fakeLock.state.value is LockUiState.Unlocked)
        assertTrue("the PIN steps after a restore end with \"Make backups\" (decision 24)", PinSetupStart.takeNewVault())
        assertSame("the backup's own vault", backup, rig.vault.stored)
        assertEquals("", vm.password.text.toString())
    }

    @Test
    fun theLockGetsThePasswordThatOpenedTheBackupTwice() = runTest {
        val rig = rig()
        var typings: Pair<String, String>? = null
        val lock = object : io.github.dilshodbekmx.passwordvault.lock.LockManager by rig.fakeLock {
            override suspend fun createVault(first: CharArray, again: CharArray, restoreFrom: Vault?): CreateResult {
                typings = String(first) to String(again)
                return rig.fakeLock.createVault(first, again, restoreFrom)
            }
        }
        val vm = rig.make { RestoreViewModel(lock, rig.repository, rig.files) }
        rig.chooseFile(vm)
        rig.open(vm, opened())
        vm.restore()
        runCurrent()
        assertEquals(PASSWORD to PASSWORD, typings)
    }

    @Test
    fun everyMessageOfTheRestore() = runTest {
        val rig = rig()
        val vm = rig.restore()
        rig.chooseFile(vm)

        // A wrong password: another try.
        rig.open(vm, BackupResult.Wrong)
        assertEquals(RestoreStep.Password(problem = RestoreProblem.WRONG), vm.step.value)
        assertEquals(UiText.of(R.string.backup_restore_wrong), BackupTexts.restoreProblem(RestoreProblem.WRONG))
        assertEquals("the field is empty for the next try", "", vm.password.text.toString())

        // The trailer is damaged and the slot did not open: either may be the reason, and another try is allowed.
        rig.open(vm, BackupResult.WrongOrDamaged)
        assertEquals(RestoreStep.Password(problem = RestoreProblem.WRONG_OR_DAMAGED), vm.step.value)
        assertEquals(
            UiText.of(R.string.backup_restore_wrong_or_damaged),
            BackupTexts.restoreProblem(RestoreProblem.WRONG_OR_DAMAGED),
        )
        rig.open(vm, BackupResult.NoMemory)
        assertEquals(RestoreStep.Password(problem = RestoreProblem.NO_MEMORY), vm.step.value)
        assertEquals(UiText.of(R.string.backup_restore_no_memory), BackupTexts.restoreProblem(RestoreProblem.NO_MEMORY))
        rig.open(vm, opened())
        assertTrue("the fourth try opened it", vm.step.value is RestoreStep.Summary)
        assertEquals(4, rig.repository.openedWith.size)

        // Files that cannot be restored, back at step 1 with their message.
        for ((answer, problem, id) in listOf(
            Triple(BackupResult.Damaged, RestoreProblem.DAMAGED, R.string.backup_restore_damaged),
            Triple(BackupResult.Newer, RestoreProblem.NEWER, R.string.backup_restore_newer),
            Triple(BackupResult.NotOurs, RestoreProblem.NOT_OURS, R.string.backup_restore_not_ours),
        )) {
            rig.chooseFile(vm)
            rig.open(vm, answer)
            assertEquals("$answer", RestoreStep.Start(problem = problem), vm.step.value)
            assertEquals(UiText.of(id), BackupTexts.restoreProblem(problem))
            assertNull("its task ends", rig.settings.fileTask.value)
        }
    }

    @Test
    fun aFileThatIsNoBackupIsRefusedBeforeAnyPasswordIsTyped() = runTest {
        for (content in listOf("name,url,username,password\n".toByteArray(), ByteArray(0), "PK\u0003\u0004".toByteArray())) {
            val rig = rig()
            val vm = rig.restore()
            rig.chooseFile(vm, content)
            assertEquals(RestoreStep.Start(problem = RestoreProblem.NOT_OURS), vm.step.value)
            assertTrue(rig.repository.openedWith.isEmpty())
        }
        assertEquals(
            UiText.of(R.string.backup_restore_not_ours),
            BackupTexts.restoreProblem(RestoreProblem.NOT_OURS),
        )
    }

    @Test
    fun overTheLockScreenTheCurrentPasswordsStayAndTheButtonSaysSo() = runTest {
        val rig = rig(locked = true)
        val vm = rig.restore()
        rig.chooseFile(vm, "not a backup".toByteArray())
        assertEquals("no hint about setup there", RestoreStep.Start(problem = RestoreProblem.NOT_OURS_LOCKED), vm.step.value)
        assertEquals(UiText.of(R.string.backup_restore_not_ours_locked), BackupTexts.restoreProblem(RestoreProblem.NOT_OURS_LOCKED))

        rig.chooseFile(vm)
        rig.open(vm, opened())
        assertEquals(RestoreStep.Summary(accounts = 2, createdMs = MADE_MS, overCurrent = true), vm.step.value)

        // Also on "Your passwords can't be opened".
        rig.fakeLock.state.value = LockUiState.Unreadable(CurrentVault.Why.DAMAGED, emptyList())
        assertTrue(vm.overCurrent)
    }

    @Test
    fun aRestoreThatCouldNotBeSavedCanBeTriedAgain() = runTest {
        val rig = rig()
        val vm = rig.restore()
        rig.chooseFile(vm)
        rig.open(vm, opened())
        rig.fakeLock.createResults.add(CreateResult.NotSaved)
        vm.restore()
        runCurrent()
        val summary = vm.step.value as RestoreStep.Summary
        assertEquals(RestoreProblem.NOT_SAVED, summary.problem)
        assertEquals(UiText.of(R.string.backup_restore_not_saved), BackupTexts.restoreProblem(RestoreProblem.NOT_SAVED))
        assertTrue(rig.fakeLock.state.value is LockUiState.NoVault)

        rig.fakeLock.createResults.add(CreateResult.NoMemory)
        vm.restore()
        runCurrent()
        assertEquals(RestoreProblem.NO_MEMORY, (vm.step.value as RestoreStep.Summary).problem)

        vm.restore()
        runCurrent()
        assertTrue(rig.fakeLock.state.value is LockUiState.Unlocked)
    }

    @Test
    fun aFileThatCannotBeReadSaysSo() = runTest {
        val rig = rig()
        rig.documents.unreadable += FILE
        val vm = rig.restore()
        rig.chooseFile(vm)
        assertEquals(RestoreStep.Start(problem = RestoreProblem.UNREADABLE), vm.step.value)
        assertEquals(UiText.of(R.string.backup_restore_unreadable), BackupTexts.restoreProblem(RestoreProblem.UNREADABLE))
    }

    @Test
    fun aClosedPickerChangesNothingAndAPickerThatCannotOpenSaysSo() = runTest {
        val rig = rig()
        val vm = rig.restore()
        vm.choose()
        runCurrent()
        assertEquals(RestoreStep.Start(opening = true), vm.step.value)
        rig.closePicker(FileTaskStore.Action.OPEN)
        assertEquals(RestoreStep.Start(), vm.step.value)

        rig.store.detach(rig.launcher)
        vm.choose()
        runCurrent()
        assertEquals(RestoreStep.Start(problem = RestoreProblem.PICKER), vm.step.value)
    }

    @Test
    fun aFileThatANewProcessRecordedIsReadWhenTheScreenOpens() = runTest {
        // The picker answered after the process ended; the new process recorded the file (ux.md §4 item 4).
        val rig = rig(locked = true)
        rig.settings.fileTask.value = FileTask(FileTask.Kind.RESTORE, RestoreViewModel.STEP_FILE, DocRef(FILE))
        rig.fakeDocuments.files[FILE] = BACKUP_BYTES
        val vm = rig.restore()
        assertEquals(RestoreStep.Password(), vm.step.value)
        assertTrue("no picker", rig.launcher.calls.isEmpty())
        assertEquals("read once", listOf(FILE), rig.documents.reads)
    }

    @Test
    fun backGivesTheRestoreUpAndForgetsWhatWasTyped() = runTest {
        val rig = rig()
        val vm = rig.restore()
        var left = 0
        backgroundScope.launch { vm.leave.collect { left++ } }
        rig.chooseFile(vm)
        vm.password.setTextAndPlaceCursorAtEnd("half typed")
        vm.leave()
        runCurrent()
        assertEquals(1, left)
        assertEquals("", vm.password.text.toString())
        assertNull("the task is given up", rig.settings.fileTask.value)
        assertTrue(FILE in rig.fakeDocuments.released)
        assertTrue("an opened file is never deleted", FILE in rig.fakeDocuments.files)
    }
}
