package io.github.dilshodbekmx.passwordvault.ui.settings

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChoosePinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.fake.FakeAppSettings
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.lock.FingerprintOnResult
import io.github.dilshodbekmx.passwordvault.lock.FingerprintStatus
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.MpPurpose
import io.github.dilshodbekmx.passwordvault.lock.PinSetResult
import io.github.dilshodbekmx.passwordvault.lock.PinStatus
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.ui.setup.NavStep
import io.github.dilshodbekmx.passwordvault.ui.setup.PinFlow
import io.github.dilshodbekmx.passwordvault.ui.setup.PinLine
import io.github.dilshodbekmx.passwordvault.ui.setup.PinSetupStage
import io.github.dilshodbekmx.passwordvault.ui.setup.PinSetupStart
import io.github.dilshodbekmx.passwordvault.ui.setup.PinSetupUi
import io.github.dilshodbekmx.passwordvault.ui.setup.PinSetupViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
 * Build plan 4.3, [JVM]: the view model of the PIN and fingerprint steps after the vault exists
 * (ux.md §5.6 to §5.8; lock.md F1 steps 6 to 9, F5, F7, §9.4) against `FakeLockManager` and
 * `FakeAppSettings`: the three blocklist messages, three mismatches back to the first entry, the PIN
 * saved with the proof of this flow (the unlock's, or the master password asked first), the
 * first-run fingerprint offer by the availability table of lock.md §9.4, the steps that follow each
 * flow, and a lock that drops everything.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PinSetupViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    /** The app's scope in these tests: on the test dispatcher, and not a background scope, so that `advanceUntilIdle` runs its work. */
    private val work = CoroutineScope(SupervisorJob() + dispatcher)
    private val store = ViewModelStore()
    private val settings = FakeAppSettings()
    private val clock = FakeClock(elapsedMs = 5_000_000L, autoAdvance = false)
    private var models = 0
    private var asked = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        PinSetupStart.take()
    }

    @After
    fun tearDown() {
        store.clear()
        work.cancel()
        Dispatchers.resetMain()
    }

    /** A lock that is unlocked with the proof of the master password just typed (setup, "Forgot PIN?"). */
    private fun unlockedWithProof(): RecordingLock {
        val fake = FakeLockManager()
        fake.state.value = LockUiState.Unlocked(emptyList(), fake.proof())
        return RecordingLock(fake)
    }

    private fun TestScope.model(lock: RecordingLock, canAuthenticate: Int = 0): PinSetupViewModel {
        val factory = viewModelFactory {
            initializer {
                PinSetupViewModel(lock, settings, clock, { asked++; canAuthenticate }, AppDispatchers.single(dispatcher), work)
            }
        }
        val vm = ViewModelProvider(store, factory)["pin-setup-${models++}", PinSetupViewModel::class.java]
        runCurrent()
        return vm
    }

    private fun PinSetupViewModel.type(pin: String, on: Route) {
        for (digit in digitsOf(pin)) digit(digit, on)
    }

    private fun PinSetupViewModel.performMove() {
        val move = ui.value.move ?: return
        moved(move)
    }

    /** Setup up to a saved PIN and the decision after it. */
    private fun TestScope.chooseAndSave(vm: PinSetupViewModel, pin: String = Pins.ALLOWED) {
        vm.start()
        runCurrent()
        vm.type(pin, Route.CreatePin)
        vm.performMove()
        vm.type(pin, Route.ConfirmPin)
        advanceUntilIdle()
    }

    // ---- the proof ---------------------------------------------------------------------------------

    @Test
    fun theUnlocksProofLeadsStraightToThePin() = runTest {
        val vm = model(unlockedWithProof())
        vm.start()
        runCurrent()
        val ui = vm.ui.value
        assertEquals(PinSetupStage.CHOOSE, ui.stage)
        assertEquals("no PIN on this phone: the steps of setup", PinFlow.SETUP, ui.flow)
        assertFalse("\"Choose a 6-digit PIN\"", ui.newPin)
    }

    @Test
    fun withoutAProofTheMasterPasswordComesFirst() = runTest {
        val lock = RecordingLock()
        lock.fake.state.value = LockUiState.Unlocked(emptyList(), proof = null) // a fingerprint unlock
        val vm = model(lock)
        vm.start()
        runCurrent()
        assertEquals(PinSetupStage.MASTER, vm.ui.value.stage)
        vm.masterPassword.field.type("vowel-shiny-shell-crisp-shaft-cadet")
        vm.submitMasterPassword()
        runCurrent()
        assertEquals("it adds a way in: the master password, for SET_PIN", listOf(MpPurpose.SET_PIN), lock.fake.purposes)
        assertEquals("the field is empty after a submit", "", vm.masterPassword.field.text.toString())
        assertEquals(PinSetupStage.CHOOSE, vm.ui.value.stage)
        vm.type(Pins.ALLOWED, Route.CreatePin)
        vm.performMove()
        vm.type(Pins.ALLOWED, Route.ConfirmPin)
        advanceUntilIdle()
        assertEquals("the PIN was saved", 1, lock.setPins.size)
    }

    @Test
    fun aWrongMasterPasswordSaysSoAndStays() = runTest {
        val lock = RecordingLock()
        lock.fake.state.value = LockUiState.Unlocked(emptyList(), proof = null)
        lock.fake.mpResults += io.github.dilshodbekmx.passwordvault.lock.MpResult.Wrong
        val vm = model(lock)
        vm.start()
        runCurrent()
        vm.masterPassword.field.type("wrong")
        vm.submitMasterPassword()
        runCurrent()
        assertEquals(PinSetupStage.MASTER, vm.ui.value.stage)
        assertEquals(PinLine(UiText.of(R.string.pin_mp_wrong)), vm.masterPassword.ui.value.line)
    }

    // ---- choosing ----------------------------------------------------------------------------------

    @Test
    fun theThreeBlocklistMessages() = runTest {
        val vm = model(unlockedWithProof())
        vm.start()
        runCurrent()
        val cases = listOf(
            Pins.DATE to R.string.pin_refused_date,
            Pins.YEAR to R.string.pin_refused_date,
            Pins.REPEAT to R.string.pin_refused_pattern,
            Pins.PATTERN to R.string.pin_refused_pattern,
            Pins.STEPS to R.string.pin_refused_pattern,
            Pins.SEQUENCE to R.string.pin_refused_sequence,
            Pins.KEYPAD to R.string.pin_refused_sequence,
            Pins.PATH to R.string.pin_refused_sequence,
        )
        var shakes = vm.ui.value.shakes
        for ((pin, message) in cases) {
            vm.type(pin, Route.CreatePin)
            val ui = vm.ui.value
            assertEquals("the message for $pin", PinLine(UiText.of(message)), ui.line)
            assertEquals("$pin: the pad stays", PinSetupStage.CHOOSE, ui.stage)
            assertEquals("$pin: the dots are cleared", 0, ui.entered)
            assertEquals("$pin: the dots shake", ++shakes, ui.shakes)
            assertNull("$pin: nothing moves", ui.move)
        }
    }

    @Test
    fun anAllowedPinOpensTheSecondStep() = runTest {
        val vm = model(unlockedWithProof())
        vm.start()
        runCurrent()
        vm.digit(5, Route.CreatePin)
        vm.digit(8, Route.CreatePin)
        assertEquals(2, vm.ui.value.entered)
        vm.delete(Route.CreatePin)
        assertEquals(1, vm.ui.value.entered)
        vm.clearEntry(Route.CreatePin)
        assertEquals(0, vm.ui.value.entered)
        vm.digit(1, Route.ConfirmPin) // the other step's pad does nothing now
        assertEquals(0, vm.ui.value.entered)
        vm.type(Pins.ALLOWED, Route.CreatePin)
        val ui = vm.ui.value
        assertEquals(PinSetupStage.CONFIRM, ui.stage)
        assertEquals(Route.CreatePin, ui.move?.from)
        assertEquals(listOf(NavStep.Go(Route.ConfirmPin)), ui.move?.steps)
    }

    @Test
    fun threeMismatchesReturnToTheFirstEntry() = runTest {
        val lock = unlockedWithProof()
        val vm = model(lock)
        vm.start()
        runCurrent()
        vm.type(Pins.ALLOWED, Route.CreatePin)
        vm.performMove()
        vm.type(Pins.ALLOWED_2, Route.ConfirmPin)
        assertEquals(PinLine(UiText.of(R.string.pin_mismatch)), vm.ui.value.line)
        assertEquals("one mismatch: still the second entry", PinSetupStage.CONFIRM, vm.ui.value.stage)
        vm.type(Pins.ALLOWED_3, Route.ConfirmPin)
        assertEquals("two mismatches: still the second entry", PinSetupStage.CONFIRM, vm.ui.value.stage)
        assertNull(vm.ui.value.move)
        vm.type(Pins.ALLOWED_2, Route.ConfirmPin)
        val ui = vm.ui.value
        assertEquals("the third: back to the first entry", PinSetupStage.CHOOSE, ui.stage)
        assertEquals(PinLine(UiText.of(R.string.pin_mismatch)), ui.line)
        assertEquals(Route.ConfirmPin, ui.move?.from)
        assertEquals(listOf(NavStep.Back), ui.move?.steps)
        vm.performMove()
        // The first PIN is gone: it has to be chosen again, and nothing was saved.
        vm.type(Pins.ALLOWED_2, Route.CreatePin)
        assertEquals(PinSetupStage.CONFIRM, vm.ui.value.stage)
        vm.performMove()
        vm.type(Pins.ALLOWED_2, Route.ConfirmPin)
        advanceUntilIdle()
        assertEquals(listOf(Pins.ALLOWED_2), lock.setPins.map { it.first })
    }

    @Test
    fun backFromTheSecondStepDropsTheFirstEntry() = runTest {
        val vm = model(unlockedWithProof())
        vm.start()
        runCurrent()
        vm.type(Pins.ALLOWED, Route.CreatePin)
        vm.performMove()
        vm.digit(5, Route.ConfirmPin)
        vm.backFromConfirm()
        val ui = vm.ui.value
        assertEquals(PinSetupStage.CHOOSE, ui.stage)
        assertEquals(0, ui.entered)
        assertEquals(listOf(NavStep.Back), ui.move?.steps)
    }

    // ---- saving and what follows ---------------------------------------------------------------------

    @Test
    fun aMatchingPinIsSavedWithTheUnlocksProofAndTheOfferFollows() = runTest {
        val lock = unlockedWithProof()
        val proof = (lock.fake.state.value as LockUiState.Unlocked).proof
        val vm = model(lock)
        vm.start()
        runCurrent()
        vm.type(Pins.ALLOWED, Route.CreatePin)
        vm.performMove()
        vm.type(Pins.ALLOWED, Route.ConfirmPin)
        runCurrent()
        assertEquals(1, lock.setPins.size)
        assertEquals(Pins.ALLOWED, lock.setPins.single().first)
        assertSame("the proof of the unlock just made (lock.md §8.2)", proof, lock.setPins.single().second)
        assertTrue("\"Your PIN is set.\"", vm.ui.value.saved)
        assertNull("it shows for a moment first", vm.ui.value.move)
        advanceUntilIdle()
        val ui = vm.ui.value
        assertEquals(PinSetupStage.FINGERPRINT, ui.stage)
        assertEquals(Route.ConfirmPin, ui.move?.from)
        assertEquals(listOf(NavStep.Back, NavStep.Replace(Route.FingerprintOffer)), ui.move?.steps)
        assertEquals(1, asked)
    }

    /** lock.md §9.4, the first-run column: offered for 0 only; −1, 11, 12, −2, 1 and 15 are not offered. */
    @Test
    fun theFirstRunOfferFollowsTheAvailabilityTable() = runTest {
        val table = listOf(0 to true, -1 to false, 11 to false, 12 to false, -2 to false, 1 to false, 15 to false, 21 to false)
        for ((canAuthenticate, offered) in table) {
            val vm = model(unlockedWithProof(), canAuthenticate)
            chooseAndSave(vm)
            val ui = vm.ui.value
            if (offered) {
                assertEquals("canAuthenticate $canAuthenticate", PinSetupStage.FINGERPRINT, ui.stage)
                assertEquals(listOf(NavStep.Back, NavStep.Replace(Route.FingerprintOffer)), ui.move?.steps)
            } else {
                assertEquals("canAuthenticate $canAuthenticate: not offered, \"Make backups\" next", PinSetupStage.BACKUPS, ui.stage)
                assertEquals(listOf(NavStep.Back, NavStep.Replace(Route.BackupsExplained)), ui.move?.steps)
            }
        }
    }

    @Test
    fun noOfferWhenFingerprintUnlockIsOnAlready() = runTest {
        val lock = unlockedWithProof()
        lock.fake.status.value = lock.fake.status.value.copy(fingerprint = FingerprintStatus.On)
        val vm = model(lock, canAuthenticate = 0)
        chooseAndSave(vm)
        assertEquals(PinSetupStage.BACKUPS, vm.ui.value.stage)
    }

    @Test
    fun aNewPinAfterForgotPinGoesBackToWhereTheUserWas() = runTest {
        val lock = unlockedWithProof()
        lock.fake.status.value = lock.fake.status.value.copy(pin = PinStatus.On) // F7: the PIN still exists
        val vm = model(lock)
        vm.start()
        runCurrent()
        assertEquals(PinFlow.NEW_PIN, vm.ui.value.flow)
        assertTrue("\"Choose a new PIN\"", vm.ui.value.newPin)
        vm.type(Pins.ALLOWED, Route.CreatePin)
        vm.performMove()
        vm.type(Pins.ALLOWED, Route.ConfirmPin)
        advanceUntilIdle()
        val ui = vm.ui.value
        assertEquals(PinSetupStage.DONE, ui.stage)
        assertEquals(listOf(NavStep.Back, NavStep.Back), ui.move?.steps)
        assertEquals("no fingerprint question in this flow", 0, asked)
    }

    @Test
    fun theOpenersReasonPicksTheFlow() = runTest {
        PinSetupStart.offer(ChoosePinReason.SOMEONE_MAY_KNOW)
        val vm = model(unlockedWithProof())
        vm.start()
        runCurrent()
        val ui = vm.ui.value
        assertEquals(PinFlow.NEW_PIN_WITH_OFFER, ui.flow)
        assertTrue("\"Someone else may know the old one.\"", ui.someoneMayKnow)
        assertTrue(ui.newPin)
        assertNull("the mark is used once", PinSetupStart.take())
        vm.type(Pins.ALLOWED, Route.CreatePin)
        vm.performMove()
        vm.type(Pins.ALLOWED, Route.ConfirmPin)
        advanceUntilIdle()
        assertEquals("the fingerprint offer follows (lock.md F16b step 6)", PinSetupStage.FINGERPRINT, vm.ui.value.stage)
        vm.performMove()
        vm.fingerprintNotNow()
        assertEquals("then back, without \"Make backups\"", PinSetupStage.DONE, vm.ui.value.stage)
        assertEquals(listOf(NavStep.Back), vm.ui.value.move?.steps)
    }

    @Test
    fun aChoosePinNoticeInTheStatePicksTheFlow() = runTest {
        val fake = FakeLockManager()
        fake.state.value = LockUiState.Unlocked(listOf(Notice.ChoosePin(7L, ChoosePinReason.TURNED_OFF, needsMasterPassword = false)), fake.proof())
        val vm = model(RecordingLock(fake))
        vm.start()
        runCurrent()
        assertEquals(PinFlow.NEW_PIN, vm.ui.value.flow)
    }

    @Test
    fun notNowSkipsThePinAndSetupGoesOn() = runTest {
        val vm = model(unlockedWithProof(), canAuthenticate = 0)
        vm.start()
        runCurrent()
        vm.notNow()
        advanceUntilIdle()
        val ui = vm.ui.value
        assertEquals(PinSetupStage.FINGERPRINT, ui.stage)
        assertEquals(Route.CreatePin, ui.move?.from)
        assertEquals(listOf(NavStep.Replace(Route.FingerprintOffer)), ui.move?.steps)
    }

    @Test
    fun aPinThatCannotBeSavedSaysSoWithTryAgain() = runTest {
        val lock = unlockedWithProof()
        lock.fake.setPinResults += PinSetResult.NotSet
        val vm = model(lock)
        vm.start()
        runCurrent()
        vm.type(Pins.ALLOWED, Route.CreatePin)
        vm.performMove()
        vm.type(Pins.ALLOWED, Route.ConfirmPin)
        advanceUntilIdle()
        var ui = vm.ui.value
        assertTrue(ui.saveFailed)
        assertEquals(PinLine(UiText.of(R.string.pin_not_saved_new)), ui.line)
        assertEquals(PinSetupStage.CONFIRM, ui.stage)
        vm.digit(1, Route.ConfirmPin) // the pad waits for "Try again" or "Not now"
        assertEquals(0, vm.ui.value.entered)
        vm.retrySave()
        advanceUntilIdle()
        assertEquals("the same PIN once more", listOf(Pins.ALLOWED, Pins.ALLOWED), lock.setPins.map { it.first })
        ui = vm.ui.value
        assertEquals(PinSetupStage.FINGERPRINT, ui.stage)
    }

    @Test
    fun notNowAfterAFailedSaveGoesOnWithoutAPin() = runTest {
        val lock = unlockedWithProof()
        lock.fake.setPinResults += PinSetResult.NotSet
        val vm = model(lock, canAuthenticate = 11)
        chooseAndSave(vm)
        vm.skipAfterFailure()
        advanceUntilIdle()
        assertEquals(PinSetupStage.BACKUPS, vm.ui.value.stage)
        assertEquals(listOf(NavStep.Back, NavStep.Replace(Route.BackupsExplained)), vm.ui.value.move?.steps)
    }

    // ---- a save the lock cannot vouch for (build-plan.md §12, decision 4) ----------------------------------
    // The first two tests take the lock's own answers (`PinSetResult.MayNotBeSaved`,
    // `FingerprintOnResult.MayNotBeOn`); the others give the answer to the view model themselves, at the
    // moment the lock's answer is awaited.

    @Test
    fun theLocksAnswerThatAPinMayNotBeSavedIsShownAsSuch() = runTest {
        val lock = unlockedWithProof()
        lock.fake.setPinResults += PinSetResult.MayNotBeSaved
        val vm = model(lock, canAuthenticate = 11)
        chooseAndSave(vm)
        val ui = vm.ui.value
        assertEquals(PinSetupStage.CONFIRM, ui.stage)
        assertTrue(ui.saveUnsure)
        assertFalse("not \"Your PIN is set.\"", ui.saved)
        assertFalse("no \"Try again\"", ui.saveFailed)
        assertEquals(PinLine(UiText.of(R.string.pin_may_not_be_saved_first)), ui.line)
        assertEquals("the PIN was handed to the lock once", 1, lock.setPins.size)
    }

    @Test
    fun theLocksAnswerThatFingerprintMayNotBeOnIsShownOnTheOffer() = runTest {
        val lock = unlockedWithProof()
        lock.fake.fingerprintResults += FingerprintOnResult.MayNotBeOn
        val vm = model(lock, canAuthenticate = 0)
        chooseAndSave(vm)
        vm.performMove()
        assertEquals(PinSetupStage.FINGERPRINT, vm.ui.value.stage)
        vm.useFingerprint()
        advanceUntilIdle()
        assertEquals(PinLine(UiText.of(R.string.pin_fingerprint_may_not_be_on)), vm.ui.value.fingerprintLine)
        assertFalse(vm.ui.value.fingerprintBusy)
        assertEquals("the offer stays", PinSetupStage.FINGERPRINT, vm.ui.value.stage)
    }

    @Test
    fun aPinThatMayNotBeSavedPromisesNothingAndGoesOn() = runTest {
        val lock = unlockedWithProof()
        val vm = model(lock, canAuthenticate = 11)
        vm.start()
        runCurrent()
        vm.type(Pins.ALLOWED, Route.CreatePin)
        vm.performMove()
        vm.type(Pins.ALLOWED, Route.ConfirmPin)
        assertTrue("the lock's answer is awaited", vm.ui.value.saving)
        vm.pinMayNotBeSaved()
        var ui = vm.ui.value
        assertFalse(ui.saving)
        assertFalse("not \"Your PIN is set.\"", ui.saved)
        assertFalse("no \"Try again\"", ui.saveFailed)
        assertTrue(ui.saveUnsure)
        assertEquals("no PIN worked before: the sentence names no old PIN", PinLine(UiText.of(R.string.pin_may_not_be_saved_first)), ui.line)
        assertEquals(PinSetupStage.CONFIRM, ui.stage)
        // The lock's own answer, which comes later, changes nothing of it.
        advanceUntilIdle()
        ui = vm.ui.value
        assertTrue(ui.saveUnsure)
        assertFalse(ui.saved)
        assertEquals(PinSetupStage.CONFIRM, ui.stage)
        // The pad takes nothing, and nothing is tried again.
        vm.digit(1, Route.ConfirmPin)
        vm.retrySave()
        vm.skipAfterFailure()
        advanceUntilIdle()
        assertEquals(0, vm.ui.value.entered)
        assertEquals("the PIN was handed to the lock once", 1, lock.setPins.size)
        assertEquals(PinSetupStage.CONFIRM, vm.ui.value.stage)
        // "OK" goes on with the steps of setup.
        vm.pinUnsureOk()
        advanceUntilIdle()
        ui = vm.ui.value
        assertEquals(PinSetupStage.BACKUPS, ui.stage)
        assertFalse(ui.saveUnsure)
        assertNull(ui.line)
        assertEquals(listOf(NavStep.Back, NavStep.Replace(Route.BackupsExplained)), ui.move?.steps)
    }

    @Test
    fun aNewPinThatMayNotBeSavedNamesTheOldPinAndBackGoesOnToo() = runTest {
        val lock = unlockedWithProof()
        lock.fake.status.value = lock.fake.status.value.copy(pin = PinStatus.On)
        val vm = model(lock)
        vm.start()
        runCurrent()
        vm.type(Pins.ALLOWED, Route.CreatePin)
        vm.performMove()
        vm.type(Pins.ALLOWED, Route.ConfirmPin)
        vm.pinMayNotBeSaved()
        assertEquals(PinLine(UiText.of(R.string.pin_may_not_be_saved)), vm.ui.value.line)
        vm.backFromConfirm()
        advanceUntilIdle()
        assertTrue("Back does not choose the PIN again: the steps go on", vm.ui.value.stage != PinSetupStage.CHOOSE && vm.ui.value.stage != PinSetupStage.CONFIRM)
        assertFalse(vm.ui.value.saveUnsure)
    }

    @Test
    fun theAnswerThatAPinMayNotBeSavedCountsOnlyWhileItIsSaved() = runTest {
        val vm = model(unlockedWithProof())
        vm.start()
        runCurrent()
        vm.pinMayNotBeSaved()
        assertFalse(vm.ui.value.saveUnsure)
        chooseAndSave(vm)
        vm.pinMayNotBeSaved()
        assertFalse("the PIN was saved: nothing is in doubt", vm.ui.value.saveUnsure)
    }

    @Test
    fun fingerprintThatMayNotBeOnSaysSoOnTheOffer() = runTest {
        val lock = unlockedWithProof()
        val vm = model(lock, canAuthenticate = 0)
        vm.fingerprintMayNotBeOn()
        assertNull("not on another step", vm.ui.value.fingerprintLine)
        chooseAndSave(vm)
        vm.performMove()
        assertEquals(PinSetupStage.FINGERPRINT, vm.ui.value.stage)
        vm.fingerprintMayNotBeOn()
        assertEquals(PinLine(UiText.of(R.string.pin_fingerprint_may_not_be_on)), vm.ui.value.fingerprintLine)
        assertEquals("the offer stays", PinSetupStage.FINGERPRINT, vm.ui.value.stage)
        vm.fingerprintNotNow()
        advanceUntilIdle()
        assertEquals("\"Not now\" goes on", PinSetupStage.BACKUPS, vm.ui.value.stage)
    }

    // ---- fingerprint and backups ---------------------------------------------------------------------

    @Test
    fun theFingerprintOfferSaysWhatHappened() = runTest {
        val lock = unlockedWithProof()
        val vm = model(lock, canAuthenticate = 0)
        chooseAndSave(vm)
        vm.performMove()
        vm.fingerprintShown()
        assertNull("the offer belongs to this flow: it stays", vm.ui.value.move)
        val cases = listOf(
            FingerprintOnResult.NoneEnrolled to PinLine(UiText.of(R.string.pin_fingerprint_add_first)),
            FingerprintOnResult.NoSecureHardware to PinLine(UiText.of(R.string.pin_fingerprint_no_secure_hardware)),
            FingerprintOnResult.Failed to PinLine(UiText.of(R.string.pin_fingerprint_failed)),
            FingerprintOnResult.Cancelled to null,
        )
        for ((result, line) in cases) {
            lock.fake.fingerprintResults += result
            vm.useFingerprint()
            assertTrue("$result: busy while the prompt is open", vm.ui.value.fingerprintBusy)
            advanceUntilIdle()
            val ui = vm.ui.value
            assertFalse(ui.fingerprintBusy)
            assertEquals("$result", PinSetupStage.FINGERPRINT, ui.stage)
            assertEquals("$result", line, ui.fingerprintLine)
        }
        lock.fake.fingerprintResults += FingerprintOnResult.On
        vm.useFingerprint()
        advanceUntilIdle()
        assertEquals(PinSetupStage.BACKUPS, vm.ui.value.stage)
        assertEquals(listOf(NavStep.Replace(Route.BackupsExplained)), vm.ui.value.move?.steps)
        assertEquals(FingerprintStatus.On, lock.fake.status.value.fingerprint)
    }

    @Test
    fun theOfferShownWithoutTheStepsBeforeItGoesBack() = runTest {
        val vm = model(unlockedWithProof())
        vm.fingerprintShown()
        assertEquals(Route.FingerprintOffer, vm.ui.value.move?.from)
        assertEquals(listOf(NavStep.Back), vm.ui.value.move?.steps)
    }

    @Test
    fun theReminderSwitchIsWrittenAndFinishOpensTheList() = runTest {
        val vm = model(unlockedWithProof())
        assertTrue("on by default", vm.backupReminder.value)
        vm.setBackupReminder(false)
        advanceUntilIdle()
        assertFalse(settings.backupReminder.value.on)
        assertFalse(vm.backupReminder.value)
        settings.failWrites = true
        vm.setBackupReminder(true)
        advanceUntilIdle()
        assertEquals(PinLine(UiText.of(R.string.settings_not_saved)), vm.ui.value.backupLine)
        vm.finish()
        assertEquals(Route.BackupsExplained, vm.ui.value.move?.from)
        assertEquals(listOf(NavStep.Tab(Route.Passwords)), vm.ui.value.move?.steps)
    }

    // ---- a lock ---------------------------------------------------------------------------------------

    @Test
    fun aLockDropsEverythingAndTheStepsStartAfresh() = runTest {
        val lock = unlockedWithProof()
        lock.fake.state.value = LockUiState.Unlocked(emptyList(), proof = null)
        val vm = model(lock)
        vm.start()
        runCurrent()
        vm.masterPassword.field.type("vowel-shiny-shell-crisp-shaft-cadet")
        vm.submitMasterPassword()
        runCurrent()
        vm.type(Pins.ALLOWED, Route.CreatePin)
        vm.performMove()
        vm.digit(5, Route.ConfirmPin)
        lock.fake.lock(io.github.dilshodbekmx.passwordvault.lock.LockReason.SCREEN_OFF)
        runCurrent()
        assertEquals(PinSetupUi(), vm.ui.value)
        lock.fake.state.value = LockUiState.Unlocked(emptyList(), proof = null)
        runCurrent()
        vm.start()
        runCurrent()
        assertEquals("the proof asked for before the lock is gone", PinSetupStage.MASTER, vm.ui.value.stage)
    }

    @Test
    fun aProofAskedForHereIsDroppedWhenTheStepsEnd() = runTest {
        val lock = RecordingLock()
        lock.fake.state.value = LockUiState.Unlocked(emptyList(), proof = null)
        val vm = model(lock)
        vm.start()
        runCurrent()
        vm.masterPassword.field.type("vowel-shiny-shell-crisp-shaft-cadet")
        vm.submitMasterPassword()
        runCurrent()
        vm.type(Pins.ALLOWED, Route.CreatePin)
        vm.performMove()
        vm.type(Pins.ALLOWED, Route.ConfirmPin)
        advanceUntilIdle()
        val proof: MasterProof = lock.setPins.single().second
        vm.stepLeft(Route.ConfirmPin)
        vm.stepLeft(Route.CreatePin)
        vm.performMove()
        vm.finish()
        vm.stepLeft(Route.BackupsExplained)
        assertEquals(PinSetupUi(), vm.ui.value)
        assertTrue("the proof was dropped", runCatching { proof.useBytes { } }.isFailure)
    }
}
