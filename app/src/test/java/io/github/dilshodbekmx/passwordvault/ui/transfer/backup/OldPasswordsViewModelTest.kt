package io.github.dilshodbekmx.passwordvault.ui.transfer.backup

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.GenerationInfo
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.lock.AsideResult
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.lock.LockReason
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupDate
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupTexts
import io.github.dilshodbekmx.passwordvault.ui.transfer.OldAction
import io.github.dilshodbekmx.passwordvault.ui.transfer.OldDone
import io.github.dilshodbekmx.passwordvault.ui.transfer.OldPasswordsViewModel
import io.github.dilshodbekmx.passwordvault.ui.transfer.OldProblem
import io.github.dilshodbekmx.passwordvault.ui.transfer.OldStep
import io.github.dilshodbekmx.passwordvault.ui.transfer.backup.BackupRig.Companion.NOW
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Build plan 5.2, [JVM]: Settings › Old passwords (ux.md §5.10, §5.18; lock.md F17 step 5). "Open"
 * and "Delete" go to the lock with what was typed in their own step, for that vault, and never with
 * the current master password, a proof or a pending password; every try is typed again; "Delete"
 * shows its progress while the lock holds it; the lock's waits and messages.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OldPasswordsViewModelTest {
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

    private val older = AsideVault("11111111-1111-4111-8111-111111111111", putAsideAtMs = NOW - 30 * DAY, newest = GenerationInfo(4, "k1", NOW - 31 * DAY))
    private val newer = AsideVault("22222222-2222-4222-8222-222222222222", putAsideAtMs = NOW - 2 * DAY, newest = null)

    private fun TestScope.rig(): BackupRig = BackupRig(this, temp.newFolder()).also {
        it.fakeLock.status.value = it.fakeLock.status.value.copy(aside = listOf(older, newer))
    }

    private fun BackupRig.old(lock: LockManager = this.lock): OldPasswordsViewModel = make { OldPasswordsViewModel(lock, clock) }

    private fun BackupRig.type(vm: OldPasswordsViewModel, typed: String, answer: AsideResult = AsideResult.Done) {
        fakeLock.asideResults.add(answer)
        vm.password.setTextAndPlaceCursorAtEnd(typed)
        vm.submit()
        scope.runCurrent()
    }

    @Test
    fun theVaultsPutAsideAreListedNewestFirst() = runTest {
        val rig = rig()
        val vm = rig.old()
        assertEquals(listOf(newer, older), vm.vaults.value)
        assertEquals(OldStep.List(), vm.step.value)
        assertEquals(UiText.of(R.string.backup_old_item, BackupDate(newer.putAsideAtMs, BackupDate.Style.DATE)), BackupTexts.oldItem(newer.putAsideAtMs))
    }

    @Test
    fun openAddsItsAccountsWithThatVaultsOwnMasterPassword() = runTest {
        val rig = rig()
        val vm = rig.old()
        vm.ask(older, OldAction.OPEN)
        assertEquals(OldStep.Asking(older, OldAction.OPEN), vm.step.value)
        rig.type(vm, "the old one")
        assertEquals(listOf("open ${older.vaultId} the old one"), rig.lock.asideCalls)
        assertTrue("no other check of a master password", rig.fakeLock.purposes.isEmpty())
        assertEquals(OldStep.List(done = OldDone.ADDED), vm.step.value)
        assertEquals(UiText.of(R.string.backup_old_added), BackupTexts.oldDone(OldDone.ADDED))
        assertEquals("", vm.password.text.toString())
    }

    @Test
    fun deleteNeedsThatVaultsOwnMasterPasswordAndEveryTryIsTypedAgain() = runTest {
        val rig = rig()
        // A proof of the current vault is in the lock's state: it plays no part.
        rig.fakeLock.state.value = io.github.dilshodbekmx.passwordvault.lock.LockUiState.Unlocked(emptyList(), rig.proof())
        val vm = rig.old()
        vm.ask(newer, OldAction.DELETE)

        rig.type(vm, "current master password", AsideResult.Wrong)
        assertEquals(OldStep.Asking(newer, OldAction.DELETE, problem = OldProblem.WRONG), vm.step.value)
        assertEquals(UiText.of(R.string.backup_old_wrong), BackupTexts.oldProblem(OldProblem.WRONG, OldAction.DELETE))
        assertEquals("the field is empty: the next try is typed again", "", vm.password.text.toString())

        // An empty field sends nothing.
        vm.submit()
        runCurrent()
        assertEquals(1, rig.lock.asideCalls.size)

        rig.type(vm, "its own", AsideResult.NotSaved)
        assertEquals(OldProblem.NOT_SAVED, (vm.step.value as OldStep.Asking).problem)
        assertEquals(UiText.of(R.string.backup_old_not_deleted), BackupTexts.oldProblem(OldProblem.NOT_SAVED, OldAction.DELETE))
        assertEquals(UiText.of(R.string.backup_old_not_added), BackupTexts.oldProblem(OldProblem.NOT_SAVED, OldAction.OPEN))

        rig.type(vm, "its own")
        assertEquals(
            listOf("delete ${newer.vaultId} current master password", "delete ${newer.vaultId} its own", "delete ${newer.vaultId} its own"),
            rig.lock.asideCalls,
        )
        assertTrue(rig.fakeLock.purposes.isEmpty())
        assertEquals(OldStep.List(done = OldDone.DELETED), vm.step.value)
        assertEquals(UiText.of(R.string.backup_old_deleted), BackupTexts.oldDone(OldDone.DELETED))
    }

    @Test
    fun deleteShowsItsProgressWhileTheLockHoldsIt() = runTest {
        val rig = rig()
        val release = CompletableDeferred<AsideResult>()
        val slow = object : LockManager by rig.lock {
            override suspend fun deleteAside(vaultId: String, chars: CharArray): AsideResult = release.await()
        }
        val vm = rig.old(slow)
        vm.ask(older, OldAction.DELETE)
        vm.password.setTextAndPlaceCursorAtEnd("its own")
        vm.submit()
        runCurrent()
        assertEquals(OldStep.Asking(older, OldAction.DELETE, working = true), vm.step.value)
        assertTrue("Back waits for it", vm.back())
        assertEquals(OldStep.Asking(older, OldAction.DELETE, working = true), vm.step.value)
        vm.submit() // a second tap sends nothing
        release.complete(AsideResult.Done)
        runCurrent()
        assertEquals(OldStep.List(done = OldDone.DELETED), vm.step.value)
    }

    @Test
    fun thatVaultsWaitCountsDownAndNothingIsSentMeanwhile() = runTest {
        val rig = rig()
        val vm = rig.old()
        vm.ask(older, OldAction.OPEN)
        val until = rig.clock.elapsedMs() + 30_000L
        rig.type(vm, "guess", AsideResult.Wait(until))
        assertEquals(OldStep.Asking(older, OldAction.OPEN, waitUntilElapsedMs = until), vm.step.value)
        assertEquals(30, vm.waitSeconds.value)
        vm.password.setTextAndPlaceCursorAtEnd("guess again")
        vm.submit()
        runCurrent()
        assertEquals(1, rig.lock.asideCalls.size)
        rig.clock.advance(30_000L)
        runCurrent()
        assertNull(vm.waitSeconds.value)
        rig.type(vm, "its own", AsideResult.NoMemory)
        assertEquals(OldProblem.NO_MEMORY, (vm.step.value as OldStep.Asking).problem)
        assertEquals(UiText.of(R.string.backup_old_no_memory), BackupTexts.oldProblem(OldProblem.NO_MEMORY, OldAction.OPEN))
    }

    @Test
    fun cancelAndALockGoBackToTheListAndForgetWhatWasTyped() = runTest {
        val rig = rig()
        val vm = rig.old()
        vm.ask(older, OldAction.DELETE)
        vm.password.setTextAndPlaceCursorAtEnd("half")
        assertTrue(vm.back())
        assertEquals(OldStep.List(), vm.step.value)
        assertEquals("", vm.password.text.toString())

        vm.ask(older, OldAction.OPEN)
        vm.password.setTextAndPlaceCursorAtEnd("half")
        rig.fakeLock.lock(LockReason.LEFT)
        runCurrent()
        assertEquals(OldStep.List(), vm.step.value)
        assertEquals("", vm.password.text.toString())
        assertTrue(rig.lock.asideCalls.isEmpty())
    }

    @Test
    fun aVaultThatIsGoneMeanwhileClosesItsStep() = runTest {
        val rig = rig()
        val vm = rig.old()
        vm.ask(newer, OldAction.OPEN)
        rig.fakeLock.status.value = rig.fakeLock.status.value.copy(aside = listOf(older))
        runCurrent()
        assertEquals(OldStep.List(), vm.step.value)
        assertEquals(listOf(older), vm.vaults.value)
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
