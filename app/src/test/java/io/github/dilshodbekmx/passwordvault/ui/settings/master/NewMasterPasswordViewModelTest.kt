package io.github.dilshodbekmx.passwordvault.ui.settings.master

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.generator.Wordlist
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.lock.FingerprintStatus
import io.github.dilshodbekmx.passwordvault.lock.FinishResult
import io.github.dilshodbekmx.passwordvault.lock.LockReason
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.PendingMasterPassword
import io.github.dilshodbekmx.passwordvault.lock.PinStatus
import io.github.dilshodbekmx.passwordvault.lock.ProofResult
import io.github.dilshodbekmx.passwordvault.lock.RequestResult
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import io.github.dilshodbekmx.passwordvault.ui.settings.Leave
import io.github.dilshodbekmx.passwordvault.ui.settings.NewMasterPasswordViewModel
import io.github.dilshodbekmx.passwordvault.ui.settings.NewStage
import io.github.dilshodbekmx.passwordvault.ui.settings.digitsOf
import io.github.dilshodbekmx.passwordvault.ui.settings.master.MasterData.DAY_MS
import io.github.dilshodbekmx.passwordvault.ui.settings.master.MasterData.NOW
import io.github.dilshodbekmx.passwordvault.ui.settings.master.MasterData.OWN_NEW
import io.github.dilshodbekmx.passwordvault.ui.settings.master.MasterData.PIN
import io.github.dilshodbekmx.passwordvault.ui.settings.master.MasterData.WORD_INDICES
import io.github.dilshodbekmx.passwordvault.ui.settings.type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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
import org.junit.Test

/**
 * Build plan 5.5, [JVM]: `Route.NewMasterPassword` (ux.md §5.24; lock.md F11) against
 * `FakeLockManager`: the explanation in the words of the shortcut there is; "Proof now" (the proof
 * that stands, the PIN typed now with every answer of the lock, the fingerprint where there is no
 * PIN, "Use fingerprint" after the PIN was turned off, a flow that stops); the choice, the request
 * and the wait; the proof asked again with the chosen password kept when it went meanwhile;
 * `leaveNewMasterPassword` on every way out without a request, and never after one; and "Finish
 * now" with every answer of `finishNewMasterPassword`, then the new backup with the new master
 * password's proof. `MasterPasswordFlowsTest` runs the flows over the real lock manager.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewMasterPasswordViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val work = CoroutineScope(SupervisorJob() + dispatcher)
    private val store = ViewModelStore()
    private val clock = FakeClock(elapsedMs = 5_000_000L, autoAdvance = false)
    private val random = FakeRandom(seed = 5L)
    private val offered = mutableListOf<MasterProof>()
    private var models = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        store.clear()
        work.cancel()
        Dispatchers.resetMain()
    }

    private fun lockWith(
        pin: PinStatus = PinStatus.On,
        fingerprint: FingerprintStatus = FingerprintStatus.Off,
        pending: PendingMasterPassword? = null,
    ): MasterLock {
        val lock = MasterLock()
        lock.fake.state.value = LockUiState.Unlocked(emptyList(), null)
        lock.fake.status.value = lock.fake.status.value.copy(pin = pin, fingerprint = fingerprint, pending = pending)
        return lock
    }

    private fun TestScope.model(lock: MasterLock): NewMasterPasswordViewModel {
        val factory = viewModelFactory {
            initializer {
                NewMasterPasswordViewModel(
                    lock = lock,
                    clock = clock,
                    random = random,
                    dispatchers = AppDispatchers.single(dispatcher),
                    work = work,
                    offerBackup = { offered += it },
                )
            }
        }
        val vm = ViewModelProvider(store, factory)["new-${models++}", NewMasterPasswordViewModel::class.java]
        advanceUntilIdle()
        return vm
    }

    private fun NewMasterPasswordViewModel.typePin(pin: String) = digitsOf(pin).forEach(::digit)

    /** The choice with the suggested words, typed again; returns them. */
    private fun TestScope.chooseAndTypeAgain(vm: NewMasterPasswordViewModel): String {
        assertEquals(NewStage.CHOOSE, vm.ui.value.stage)
        random.queueInts(*WORD_INDICES)
        vm.choice.showOtherWords()
        advanceUntilIdle()
        assertTrue(vm.choice.useTheseWords())
        vm.chosen()
        assertEquals(NewStage.WRITE_DOWN, vm.ui.value.stage)
        vm.wroteItDown()
        assertEquals(NewStage.TYPE_AGAIN, vm.ui.value.stage)
        val words = WORD_INDICES.map { Wordlist.effShort[it] }.joinToString("-")
        vm.choice.again.type(words)
        vm.submitAgain()
        return words
    }

    private val ready = PendingMasterPassword(requestedAtMs = NOW - DAY_MS, by = Shortcut.PIN, creditedMs = DAY_MS, remainingMs = 0L, ready = true)
    private val waiting = PendingMasterPassword(requestedAtMs = NOW, by = Shortcut.PIN, creditedMs = 3_600_000L, remainingMs = DAY_MS - 3_600_000L, ready = false)

    // ---- the explanation --------------------------------------------------------------------------

    @Test
    fun theExplanationNamesThePin_orTheFingerprintWhereThereIsNoPin() = runTest(dispatcher) {
        val withPin = model(lockWith(pin = PinStatus.On))
        assertEquals(NewStage.EXPLAIN, withPin.ui.value.stage)
        assertEquals(Shortcut.PIN, withPin.ui.value.by)
        val withoutPin = model(lockWith(pin = PinStatus.NotSet, fingerprint = FingerprintStatus.On))
        assertEquals(Shortcut.FINGERPRINT, withoutPin.ui.value.by)
        assertFalse(withoutPin.ui.value.replacesPending)
        val again = model(lockWith(pending = waiting))
        assertTrue("another new master password waits: its 24 hours start again", again.ui.value.replacesPending)
    }

    @Test
    fun cancelOnTheExplanation_leavesTheFlow_soAProofThatStandsGoes() = runTest(dispatcher) {
        val lock = lockWith()
        val vm = model(lock)
        vm.close()
        assertEquals(Leave.BACK, vm.ui.value.leave)
        assertEquals("the proof of the lock-screen route, if any, goes with the flow (lock.md F11 step 3)", 1, lock.fake.newMasterPasswordLeft)
        store.clear()
        assertEquals("told once", 1, lock.fake.newMasterPasswordLeft)
    }

    // ---- "Proof now" -----------------------------------------------------------------------------------

    @Test
    fun aProofThatStands_leadsStraightToTheChoice() = runTest(dispatcher) {
        val lock = lockWith()
        val vm = model(lock)
        lock.fake.proofResults += ProofResult.Proven(Shortcut.PIN)
        vm.proveWithoutPin()
        advanceUntilIdle()
        assertEquals(listOf<String?>(null), lock.proofs)
        assertEquals(NewStage.CHOOSE, vm.ui.value.stage)
    }

    @Test
    fun whileAPinExists_itIsTypedNow_andEachAnswerShows() = runTest(dispatcher) {
        val lock = lockWith(fingerprint = FingerprintStatus.On)
        val vm = model(lock)
        lock.fake.proofResults += ProofResult.NeedsPin
        vm.proveWithoutPin()
        advanceUntilIdle()
        assertEquals("\"Enter your app PIN\"", NewStage.PIN, vm.ui.value.stage)
        assertTrue("\"Forgot your PIN as well? …\" while fingerprint unlock is on", vm.ui.value.fingerprintOn)

        lock.fake.proofResults += ProofResult.PinWrong(left = 4)
        vm.typePin("143637")
        assertTrue("checking", vm.ui.value.proving)
        advanceUntilIdle()
        assertEquals(UiText.plural(R.plurals.master_pin_wrong, 4), vm.ui.value.line)
        assertEquals(1, vm.ui.value.shakes)

        lock.fake.proofResults += ProofResult.SamePinAgain
        vm.typePin("143637")
        advanceUntilIdle()
        assertEquals(UiText.of(R.string.master_pin_same_again), vm.ui.value.line)

        lock.fake.proofResults += ProofResult.Proven(Shortcut.PIN)
        vm.typePin(PIN)
        advanceUntilIdle()
        assertEquals(listOf(null, "143637", "143637", PIN), lock.proofs)
        assertEquals(NewStage.CHOOSE, vm.ui.value.stage)
    }

    @Test
    fun aPinTurnedOffByWrongTries_goesOnWithTheFingerprint_onlyWhereItIsUsable() = runTest(dispatcher) {
        val lock = lockWith(fingerprint = FingerprintStatus.On)
        val vm = model(lock)
        lock.fake.proofResults += ProofResult.NeedsPin
        vm.proveWithoutPin()
        advanceUntilIdle()
        lock.fake.proofResults += ProofResult.PinGone
        vm.typePin("143637")
        advanceUntilIdle()
        assertEquals(NewStage.PIN_GONE, vm.ui.value.stage)
        lock.fake.status.value = lock.fake.status.value.copy(pin = PinStatus.NotSet)
        lock.fake.proofResults += ProofResult.Proven(Shortcut.FINGERPRINT)
        vm.proveWithoutPin() // "Use fingerprint"
        assertTrue("\"Touch the sensor to confirm with your fingerprint.\"", vm.ui.value.fingerprintPrompt)
        advanceUntilIdle()
        assertEquals(NewStage.CHOOSE, vm.ui.value.stage)

        val noFingerprint = lockWith()
        val other = model(noFingerprint)
        noFingerprint.fake.proofResults += ProofResult.NeedsPin
        other.proveWithoutPin()
        advanceUntilIdle()
        noFingerprint.fake.proofResults += ProofResult.PinGone
        other.typePin("143637")
        advanceUntilIdle()
        assertEquals(NewStage.STOPPED, other.ui.value.stage)
        assertEquals(UiText.of(R.string.master_not_offered), other.ui.value.line)
        assertEquals("the flow ended: the lock is told", 1, noFingerprint.fake.newMasterPasswordLeft)
    }

    @Test
    fun aPinThatCannotBeChecked_stopsTheFlow_andNeverFallsBackToTheFingerprint() = runTest(dispatcher) {
        val lock = lockWith(fingerprint = FingerprintStatus.On)
        val vm = model(lock)
        lock.fake.proofResults += ProofResult.NeedsPin
        vm.proveWithoutPin()
        advanceUntilIdle()
        lock.fake.proofResults += ProofResult.CannotCheck
        vm.typePin(PIN)
        advanceUntilIdle()
        assertEquals(NewStage.STOPPED, vm.ui.value.stage)
        assertEquals(UiText.of(R.string.master_proof_cannot_check), vm.ui.value.line)
        assertEquals(1, lock.fake.newMasterPasswordLeft)
        vm.close()
        assertEquals(Leave.BACK, vm.ui.value.leave)
        assertEquals(1, lock.fake.newMasterPasswordLeft)
    }

    @Test
    fun withoutAPin_theFingerprintConfirms_andAClosedPromptStaysOnTheExplanation() = runTest(dispatcher) {
        val lock = lockWith(pin = PinStatus.NotSet, fingerprint = FingerprintStatus.On)
        val vm = model(lock)
        lock.fake.proofResults += ProofResult.Cancelled
        vm.proveWithoutPin()
        assertTrue(vm.ui.value.fingerprintPrompt)
        advanceUntilIdle()
        assertEquals(NewStage.EXPLAIN, vm.ui.value.stage)
        assertFalse(vm.ui.value.proving)

        lock.fake.proofResults += ProofResult.FingerprintFailed(FingerprintLine.Locked)
        vm.proveWithoutPin()
        advanceUntilIdle()
        assertEquals(UiText.of(R.string.master_fingerprint_locked), vm.ui.value.line)

        lock.fake.proofResults += ProofResult.Proven(Shortcut.FINGERPRINT)
        vm.proveWithoutPin()
        advanceUntilIdle()
        assertEquals(NewStage.CHOOSE, vm.ui.value.stage)
    }

    @Test
    fun neitherAPinNorAFingerprint_theRouteIsNotOffered() = runTest(dispatcher) {
        for (fingerprint in listOf(FingerprintStatus.Off, FingerprintStatus.TurnOnAgain, FingerprintStatus.Unavailable(11))) {
            val vm = model(lockWith(pin = PinStatus.NotSet, fingerprint = fingerprint))
            assertEquals("$fingerprint: said at once, without an explanation that promises a way", NewStage.STOPPED, vm.ui.value.stage)
            assertEquals(UiText.of(R.string.master_not_offered), vm.ui.value.line)
        }
        // The lock decides when it is asked: a shortcut that stopped working meanwhile.
        val lock = lockWith(pin = PinStatus.NotSet, fingerprint = FingerprintStatus.On)
        val vm = model(lock)
        lock.fake.proofResults += ProofResult.NotOffered
        vm.proveWithoutPin()
        advanceUntilIdle()
        assertEquals(NewStage.STOPPED, vm.ui.value.stage)
        assertEquals(UiText.of(R.string.master_not_offered), vm.ui.value.line)
    }

    // ---- the choice, the request, the wait ------------------------------------------------------------

    @Test
    fun theRequest_takesTheTwoTypings_andShowsTheWait_withoutLeavingAfterwards() = runTest(dispatcher) {
        val lock = lockWith()
        val vm = model(lock)
        vm.proveWithoutPin()
        advanceUntilIdle()
        val words = chooseAndTypeAgain(vm)
        assertEquals(NewStage.REQUESTING, vm.ui.value.stage)
        advanceUntilIdle()
        assertEquals(listOf(words to words), lock.requests)
        assertEquals(NewStage.WAIT, vm.ui.value.stage)
        assertNull("nothing chosen stays", vm.choice.state.value.chosen)
        vm.close()
        store.clear()
        assertEquals("the request used the proof up: nothing to leave", 0, lock.fake.newMasterPasswordLeft)
    }

    @Test
    fun aProofThatWentWhileThePasswordWasChosen_isAskedAgain_andThePasswordIsKept() = runTest(dispatcher) {
        val lock = lockWith()
        val vm = model(lock)
        vm.proveWithoutPin()
        advanceUntilIdle()
        lock.fake.requestResults += RequestResult.NotAllowed
        val words = chooseAndTypeAgain(vm)
        advanceUntilIdle()
        assertEquals("the PIN again (the app was left meanwhile, lock.md §5.8)", NewStage.PIN, vm.ui.value.stage)
        assertEquals(UiText.of(R.string.master_proof_again), vm.ui.value.line)
        lock.fake.proofResults += ProofResult.Proven(Shortcut.PIN)
        vm.typePin(PIN)
        advanceUntilIdle()
        assertEquals("asked straight away with the typings kept", listOf(words to words, words to words), lock.requests)
        assertEquals(NewStage.WAIT, vm.ui.value.stage)
    }

    @Test
    fun aFailedRequest_tryAgainUsesTheSameTypings() = runTest(dispatcher) {
        for ((result, line) in listOf(RequestResult.NotSaved to R.string.master_request_not_saved, RequestResult.NoMemory to R.string.master_request_no_memory)) {
            val lock = lockWith()
            val vm = model(lock)
            vm.proveWithoutPin()
            advanceUntilIdle()
            lock.fake.requestResults += result
            chooseAndTypeAgain(vm)
            advanceUntilIdle()
            assertEquals("$result", NewStage.REQUEST_FAILED, vm.ui.value.stage)
            assertEquals("$result", UiText.of(line), vm.ui.value.line)
            vm.retryRequest()
            advanceUntilIdle()
            assertEquals("$result", NewStage.WAIT, vm.ui.value.stage)
            assertEquals("$result", 2, lock.requests.size)
        }
    }

    @Test
    fun aRequestCancelledByTheAppLeaving_startsAgainAtTheExplanation() = runTest(dispatcher) {
        val lock = lockWith()
        val vm = model(lock)
        vm.proveWithoutPin()
        advanceUntilIdle()
        lock.fake.requestResults += RequestResult.Cancelled
        chooseAndTypeAgain(vm)
        advanceUntilIdle()
        assertEquals(NewStage.EXPLAIN, vm.ui.value.stage)
        assertEquals(UiText.of(R.string.master_request_cancelled), vm.ui.value.line)
        assertNull(vm.choice.state.value.chosen)
    }

    @Test
    fun aPasswordOfOnesOwn_canBeTheNewOne() = runTest(dispatcher) {
        val lock = lockWith()
        val vm = model(lock)
        vm.proveWithoutPin()
        advanceUntilIdle()
        vm.choice.typeOwn()
        vm.choice.own.type(OWN_NEW)
        vm.choice.submitOwn()
        advanceUntilIdle()
        assertTrue(vm.choice.answerReuse(usedElsewhere = false))
        vm.chosen()
        vm.wroteItDown()
        vm.choice.again.type(OWN_NEW)
        vm.submitAgain()
        advanceUntilIdle()
        assertEquals(listOf(OWN_NEW to OWN_NEW), lock.requests)
    }

    // ---- the proof goes with its flow (lock.md F11 step 3; build-plan.md §12 item 13) ------------------

    @Test
    fun backFromThePadOrFromChoosing_leavesTheFlow_andContinueAsksAgain() = runTest(dispatcher) {
        val lock = lockWith()
        val vm = model(lock)
        lock.fake.proofResults += ProofResult.NeedsPin
        vm.proveWithoutPin()
        advanceUntilIdle()
        vm.back()
        assertEquals(NewStage.EXPLAIN, vm.ui.value.stage)
        assertEquals(1, lock.fake.newMasterPasswordLeft)

        vm.proveWithoutPin() // the fake proves without a PIN by default
        advanceUntilIdle()
        assertEquals(NewStage.CHOOSE, vm.ui.value.stage)
        random.queueInts(*WORD_INDICES)
        vm.choice.showOtherWords()
        advanceUntilIdle()
        assertTrue(vm.choice.useTheseWords())
        vm.chosen()
        vm.back() // Write it down → the choice
        assertEquals(NewStage.CHOOSE, vm.ui.value.stage)
        assertEquals("not yet: still choosing", 1, lock.fake.newMasterPasswordLeft)
        vm.back() // Back from choosing
        assertEquals(NewStage.EXPLAIN, vm.ui.value.stage)
        assertEquals("Back from choosing ends the flow", 2, lock.fake.newMasterPasswordLeft)
        assertNull(vm.choice.state.value.chosen)
    }

    @Test
    fun theRouteClosedAnyOtherWay_leavesTheFlow() = runTest(dispatcher) {
        val lock = lockWith()
        val vm = model(lock)
        vm.proveWithoutPin()
        advanceUntilIdle()
        random.queueInts(*WORD_INDICES)
        vm.choice.showOtherWords()
        advanceUntilIdle()
        assertTrue(vm.choice.useTheseWords())
        vm.chosen()
        assertEquals(0, lock.fake.newMasterPasswordLeft)
        store.clear() // the route left the back stack: its ViewModel is cleared
        assertEquals(1, lock.fake.newMasterPasswordLeft)
        assertTrue("nothing was asked for", lock.requests.isEmpty())
    }

    @Test
    fun aLock_endsTheFlow_withoutTellingTheLock_whichDroppedTheProofItself() = runTest(dispatcher) {
        val lock = lockWith()
        val vm = model(lock)
        vm.proveWithoutPin()
        advanceUntilIdle()
        lock.fake.lock(LockReason.LEFT)
        advanceUntilIdle()
        assertEquals(NewStage.EXPLAIN, vm.ui.value.stage)
        assertEquals(0, lock.fake.newMasterPasswordLeft)
    }

    // ---- "Finish now" (lock.md F11 steps 13 to 19; decision 14) ---------------------------------------

    @Test
    fun whileTheNewMasterPasswordIsReady_theScreenFinishes_andThenOffersTheBackup() = runTest(dispatcher) {
        val lock = lockWith(pending = ready)
        val vm = model(lock)
        assertEquals(NewStage.FINISH, vm.ui.value.stage)
        vm.finishField.type(OWN_NEW)
        vm.submitFinish()
        assertTrue(vm.ui.value.finishing)
        assertEquals("the field is emptied at once", "", vm.finishField.text.toString())
        val newProof = lock.fake.proof()
        lock.fake.state.value = LockUiState.Unlocked(emptyList(), newProof) // as the lock leaves it: the new master password's proof
        advanceUntilIdle()
        assertEquals(listOf(OWN_NEW), lock.finishes)
        assertEquals("no PIN and no fingerprint was asked for", emptyList<String?>(), lock.proofs)
        assertEquals(NewStage.FINISHED, vm.ui.value.stage)
        assertNull("nothing locked or closed", vm.ui.value.leave)

        vm.backUpNow()
        assertSame("F11 step 19: the backup uses the new master password just typed", newProof, offered.single())
        assertEquals(Leave.BACKUP, vm.ui.value.leave)
    }

    @Test
    fun finishNow_saysWhatEachAnswerMeans() = runTest(dispatcher) {
        val lock = lockWith(pending = ready)
        val vm = model(lock)
        val lines = listOf(
            FinishResult.Wrong to R.string.master_wrong,
            FinishResult.NoMemory to R.string.master_no_memory,
            FinishResult.NotFinished to R.string.master_not_finished,
        )
        for ((result, line) in lines) {
            lock.fake.finishResults += result
            vm.finishField.type("typed")
            vm.submitFinish()
            advanceUntilIdle()
            assertEquals("$result", NewStage.FINISH, vm.ui.value.stage)
            assertEquals("$result", UiText.of(line), vm.ui.value.line)
        }
        lock.fake.finishResults += FinishResult.Wait(untilElapsedMs = clock.elapsedMs() + 30_000L)
        vm.finishField.type("typed")
        vm.submitFinish()
        runCurrent()
        assertEquals(30, vm.ui.value.waitSeconds)
        vm.finishField.type("typed")
        vm.submitFinish()
        assertEquals("nothing is tried during the wait", 4, lock.finishes.size)
        clock.advance(30_000L)
        advanceTimeBy(30_001L)
        runCurrent()
        assertNull(vm.ui.value.waitSeconds)
    }

    @Test
    fun theCurrentMasterPassword_cancelsTheRequest_andTheRouteCloses() = runTest(dispatcher) {
        for (result in listOf(FinishResult.RequestCancelled, FinishResult.TooLate)) {
            val lock = lockWith(pending = ready)
            val vm = model(lock)
            lock.fake.finishResults += result
            vm.finishField.type("the current one")
            vm.submitFinish()
            advanceUntilIdle()
            assertEquals("$result", Leave.BACK, vm.ui.value.leave)
            assertTrue("$result", offered.isEmpty())
        }
    }

    @Test
    fun aRequestCancelledElsewhere_closesTheFinishingStep() = runTest(dispatcher) {
        val lock = lockWith(pending = ready)
        val vm = model(lock)
        lock.fake.cancelNewMasterPassword() // "Cancel it" on the banner or the notice
        advanceUntilIdle()
        assertEquals(Leave.BACK, vm.ui.value.leave)
    }

    @Test
    fun aRouteThatOpensOnTheFinishingStep_alsoLeavesTheFlowWhenItCloses() = runTest(dispatcher) {
        val lock = lockWith(pending = ready)
        val vm = model(lock)
        assertEquals(NewStage.FINISH, vm.ui.value.stage)
        vm.close() // "Later"
        assertEquals(Leave.BACK, vm.ui.value.leave)
        assertEquals(
            "a proof that the lock-screen route left goes with the route that opened after it (build-plan.md §12, 5.5)",
            1,
            lock.fake.newMasterPasswordLeft,
        )
    }

    @Test
    fun anExplanationOnScreen_becomesTheFinishingStep_whenTheNewMasterPasswordBecomesReady() = runTest(dispatcher) {
        val lock = lockWith(pending = waiting)
        val vm = model(lock)
        assertEquals(NewStage.EXPLAIN, vm.ui.value.stage)
        lock.fake.status.value = lock.fake.status.value.copy(pending = ready)
        advanceUntilIdle()
        assertEquals(NewStage.FINISH, vm.ui.value.stage)
    }
}
