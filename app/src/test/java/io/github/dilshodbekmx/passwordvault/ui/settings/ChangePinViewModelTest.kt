package io.github.dilshodbekmx.passwordvault.ui.settings

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinReason
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.lock.LockReason
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.MpPurpose
import io.github.dilshodbekmx.passwordvault.lock.MpResult
import io.github.dilshodbekmx.passwordvault.lock.PinChange
import io.github.dilshodbekmx.passwordvault.lock.PinSetResult
import io.github.dilshodbekmx.passwordvault.lock.PinStatus
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.ui.settings.ChangePinViewModel.Mode
import io.github.dilshodbekmx.passwordvault.ui.settings.ChangePinViewModel.Stage
import io.github.dilshodbekmx.passwordvault.ui.setup.PinLine
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Build plan 4.3, [JVM]: Settings › App lock › "Change PIN" and "Set a PIN" (ux.md §5.18; lock.md F6,
 * §8.2) against `FakeLockManager`: "Set a PIN" asks for the master password and "Change PIN" for the
 * current PIN; the new PIN differs from the current one; every answer of `changePin` (wrong with the
 * tries left, the same wrong PIN again, the limit and then the master password, a PIN that cannot be
 * checked, not saved); three mismatches back to the first entry.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChangePinViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    /** The app's scope in these tests: on the test dispatcher, and not a background scope, so that `advanceUntilIdle` runs its work. */
    private val work = CoroutineScope(SupervisorJob() + dispatcher)
    private val store = ViewModelStore()
    private val clock = FakeClock(elapsedMs = 5_000_000L, autoAdvance = false)
    private var models = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        SettingsNotice.take()
    }

    @After
    fun tearDown() {
        store.clear()
        work.cancel()
        Dispatchers.resetMain()
    }

    /** The open app, unlocked by a PIN or fingerprint (no proof: Settings never uses an unlock's proof), with the PIN [pin]. */
    private fun lockWith(pin: PinStatus): RecordingLock {
        val lock = RecordingLock()
        lock.fake.state.value = LockUiState.Unlocked(emptyList(), lock.fake.proof())
        lock.fake.status.value = lock.fake.status.value.copy(pin = pin)
        return lock
    }

    private fun TestScope.model(lock: RecordingLock): ChangePinViewModel {
        val factory = viewModelFactory { initializer { ChangePinViewModel(lock, clock, work) } }
        val vm = ViewModelProvider(store, factory)["change-pin-${models++}", ChangePinViewModel::class.java]
        runCurrent()
        return vm
    }

    private fun ChangePinViewModel.type(pin: String) {
        for (digit in digitsOf(pin)) digit(digit)
    }

    private fun TestScope.typeMasterPassword(vm: ChangePinViewModel) {
        vm.masterPassword.field.type("vowel-shiny-shell-crisp-shaft-cadet")
        vm.submitMasterPassword()
        runCurrent()
    }

    // ---- what each asks for first --------------------------------------------------------------------

    @Test
    fun setAPinAsksForTheMasterPassword() = runTest {
        for (pin in listOf(PinStatus.NotSet, PinStatus.Off(PinReason.LIMIT), PinStatus.Off(PinReason.STOPPED_WORKING), PinStatus.Off(PinReason.NEWER_APP))) {
            val lock = lockWith(pin)
            val vm = model(lock)
            assertEquals("$pin", Mode.SET, vm.ui.value.mode)
            assertEquals("$pin: the master password first, even with an unlock's proof in the lock's state", Stage.MASTER, vm.ui.value.stage)
            vm.digit(5) // no pad on this step
            assertEquals(0, vm.ui.value.entered)
            typeMasterPassword(vm)
            assertEquals("$pin", listOf(MpPurpose.SET_PIN), lock.fake.purposes)
            assertEquals("$pin", Stage.CHOOSE, vm.ui.value.stage)
            vm.type(Pins.ALLOWED)
            vm.type(Pins.ALLOWED)
            advanceUntilIdle()
            assertEquals("$pin: set with the master password of this route", listOf(Pins.ALLOWED), lock.setPins.map { it.first })
            assertTrue("$pin: no counted check", lock.changedPins.isEmpty())
            assertEquals(Stage.DONE, vm.ui.value.stage)
            assertTrue(vm.ui.value.leave)
            assertEquals(UiText.of(R.string.pin_set), SettingsNotice.take())
        }
    }

    @Test
    fun changePinAsksForTheCurrentPin() = runTest {
        for (pin in listOf(PinStatus.On, PinStatus.CannotCheck)) {
            val lock = lockWith(pin)
            val vm = model(lock)
            assertEquals("$pin", Mode.CHANGE, vm.ui.value.mode)
            assertEquals("$pin", Stage.CURRENT, vm.ui.value.stage)
            assertTrue("$pin: no master password asked", lock.fake.purposes.isEmpty())
        }
    }

    @Test
    fun aPhoneWithoutSecureHardwareCannotSetAPin() = runTest {
        val vm = model(lockWith(PinStatus.NoSecureHardware))
        assertEquals(Stage.UNAVAILABLE, vm.ui.value.stage)
        vm.leave()
        assertTrue(vm.ui.value.leave)
    }

    // ---- change with the current PIN -------------------------------------------------------------------

    @Test
    fun theCurrentPinThenTheNewOneTwice() = runTest {
        val lock = lockWith(PinStatus.On)
        val vm = model(lock)
        vm.digit(5)
        assertEquals(1, vm.ui.value.entered)
        vm.delete()
        assertEquals(0, vm.ui.value.entered)
        vm.type(Pins.ALLOWED)
        assertEquals(Stage.CHOOSE, vm.ui.value.stage)
        vm.type(Pins.ALLOWED_2)
        assertEquals(Stage.CONFIRM, vm.ui.value.stage)
        vm.type(Pins.ALLOWED_2)
        assertEquals(Stage.SAVING, vm.ui.value.stage)
        advanceUntilIdle()
        assertEquals("a counted check of the current PIN, then the new one", listOf(Pins.ALLOWED to Pins.ALLOWED_2), lock.changedPins)
        assertTrue(lock.setPins.isEmpty())
        assertEquals(Stage.DONE, vm.ui.value.stage)
        assertTrue(vm.ui.value.leave)
        assertEquals(UiText.of(R.string.pin_changed), SettingsNotice.take())
        vm.left()
        assertFalse(vm.ui.value.leave)
    }

    @Test
    fun theNewPinMustDifferFromTheCurrentOne() = runTest {
        val vm = model(lockWith(PinStatus.On))
        vm.type(Pins.ALLOWED)
        vm.type(Pins.ALLOWED)
        assertEquals(Stage.CHOOSE, vm.ui.value.stage)
        assertEquals(PinLine(UiText.of(R.string.pin_same_as_current)), vm.ui.value.line)
    }

    @Test
    fun theThreeBlocklistMessagesForTheNewPin() = runTest {
        val vm = model(lockWith(PinStatus.On))
        vm.type(Pins.ALLOWED)
        for ((pin, message) in listOf(Pins.DATE to R.string.pin_refused_date, Pins.REPEAT to R.string.pin_refused_pattern, Pins.SEQUENCE to R.string.pin_refused_sequence)) {
            vm.type(pin)
            assertEquals(pin, PinLine(UiText.of(message)), vm.ui.value.line)
            assertEquals(pin, Stage.CHOOSE, vm.ui.value.stage)
        }
    }

    @Test
    fun threeMismatchesReturnToTheFirstEntry() = runTest {
        val vm = model(lockWith(PinStatus.On))
        vm.type(Pins.ALLOWED)
        vm.type(Pins.ALLOWED_2)
        vm.type(Pins.ALLOWED_3)
        assertEquals(Stage.CONFIRM, vm.ui.value.stage)
        vm.type(Pins.ALLOWED_3)
        assertEquals(Stage.CONFIRM, vm.ui.value.stage)
        vm.type(Pins.ALLOWED_3)
        assertEquals(Stage.CHOOSE, vm.ui.value.stage)
        assertEquals(PinLine(UiText.of(R.string.pin_mismatch)), vm.ui.value.line)
    }

    @Test
    fun aWrongCurrentPinSaysHowManyTriesAreLeftAndKeepsTheNewPin() = runTest {
        val lock = lockWith(PinStatus.On)
        lock.fake.pinChanges += PinChange.Wrong(left = 4)
        lock.fake.pinChanges += PinChange.SameWrongAgain
        lock.fake.pinChanges += PinChange.Wrong(left = 1)
        val vm = model(lock)
        vm.type(Pins.ALLOWED_3)
        vm.type(Pins.ALLOWED_2)
        vm.type(Pins.ALLOWED_2)
        advanceUntilIdle()
        assertEquals(Stage.CURRENT, vm.ui.value.stage)
        assertEquals(PinLine(UiText.plural(R.plurals.pin_wrong_left, 4)), vm.ui.value.line)
        vm.type(Pins.ALLOWED_3)
        advanceUntilIdle()
        assertEquals(PinLine(UiText.of(R.string.pin_same_wrong_again)), vm.ui.value.line)
        vm.type(Pins.ALLOWED)
        advanceUntilIdle()
        assertEquals(PinLine(UiText.plural(R.plurals.pin_wrong_left, 1)), vm.ui.value.line)
        vm.type(Pins.ALLOWED)
        advanceUntilIdle()
        assertEquals(
            "the new PIN is kept; only the current one is asked again",
            listOf(Pins.ALLOWED_3, Pins.ALLOWED_3, Pins.ALLOWED, Pins.ALLOWED).map { it to Pins.ALLOWED_2 },
            lock.changedPins,
        )
        assertEquals(Stage.DONE, vm.ui.value.stage)
    }

    @Test
    fun atTheLimitTheMasterPasswordSetsTheNewPin() = runTest {
        val lock = lockWith(PinStatus.On)
        lock.fake.pinChanges += PinChange.Limit
        val vm = model(lock)
        vm.type(Pins.ALLOWED_3)
        vm.type(Pins.ALLOWED_2)
        vm.type(Pins.ALLOWED_2)
        advanceUntilIdle()
        assertEquals(Stage.LIMIT, vm.ui.value.stage)
        vm.typeMasterPassword()
        assertEquals(Stage.MASTER, vm.ui.value.stage)
        typeMasterPassword(vm)
        advanceUntilIdle()
        assertEquals("the \"Set\" branch (lock.md F6 step 2)", listOf(MpPurpose.SET_PIN), lock.fake.purposes)
        assertEquals("the new PIN chosen before is set", listOf(Pins.ALLOWED_2), lock.setPins.map { it.first })
        assertEquals(Stage.DONE, vm.ui.value.stage)
        assertEquals(UiText.of(R.string.pin_set), SettingsNotice.take())
    }

    @Test
    fun laterAtTheLimitLeavesWithNothingChanged() = runTest {
        val lock = lockWith(PinStatus.On)
        lock.fake.pinChanges += PinChange.Limit
        val vm = model(lock)
        vm.type(Pins.ALLOWED_3)
        vm.type(Pins.ALLOWED_2)
        vm.type(Pins.ALLOWED_2)
        advanceUntilIdle()
        vm.leave()
        assertTrue(vm.ui.value.leave)
        assertTrue(lock.setPins.isEmpty())
        assertNull(SettingsNotice.take())
    }

    @Test
    fun aPinThatCannotBeCheckedOffersTheMasterPassword() = runTest {
        val lock = lockWith(PinStatus.On)
        lock.fake.pinChanges += PinChange.CannotCheck
        val vm = model(lock)
        vm.type(Pins.ALLOWED_3)
        vm.type(Pins.ALLOWED_2)
        vm.type(Pins.ALLOWED_2)
        advanceUntilIdle()
        assertEquals(Stage.CANNOT_CHECK, vm.ui.value.stage)
        vm.useMasterPassword()
        assertEquals(Stage.MASTER, vm.ui.value.stage)
        typeMasterPassword(vm)
        advanceUntilIdle()
        assertEquals("instead of the current PIN (lock.md F6 step 2)", listOf(MpPurpose.CHANGE_PIN), lock.fake.purposes)
        assertEquals(listOf(Pins.ALLOWED_2), lock.setPins.map { it.first })
        assertEquals(UiText.of(R.string.pin_changed), SettingsNotice.take())
    }

    @Test
    fun useMasterPasswordInsteadOfTheCurrentPin() = runTest {
        val lock = lockWith(PinStatus.On)
        val vm = model(lock)
        vm.digit(1)
        vm.useMasterPassword()
        assertEquals(Stage.MASTER, vm.ui.value.stage)
        typeMasterPassword(vm)
        assertEquals(listOf(MpPurpose.CHANGE_PIN), lock.fake.purposes)
        assertEquals(Stage.CHOOSE, vm.ui.value.stage)
        vm.type(Pins.ALLOWED)
        vm.type(Pins.ALLOWED)
        advanceUntilIdle()
        assertEquals(listOf(Pins.ALLOWED), lock.setPins.map { it.first })
        assertTrue(lock.changedPins.isEmpty())
        assertEquals(UiText.of(R.string.pin_changed), SettingsNotice.take())
    }

    @Test
    fun aWrongMasterPasswordStaysOnTheField() = runTest {
        val lock = lockWith(PinStatus.NotSet)
        lock.fake.mpResults += MpResult.Wrong
        val vm = model(lock)
        typeMasterPassword(vm)
        assertEquals(Stage.MASTER, vm.ui.value.stage)
        assertEquals(PinLine(UiText.of(R.string.pin_mp_wrong)), vm.masterPassword.ui.value.line)
        assertEquals("", vm.masterPassword.field.text.toString())
    }

    @Test
    fun aWaitCountsDownAndThenTheFieldWorksAgain() = runTest {
        val lock = lockWith(PinStatus.NotSet)
        lock.fake.mpResults += MpResult.Wait(untilElapsedMs = clock.elapsedMs() + 30_000L)
        val vm = model(lock)
        typeMasterPassword(vm)
        var ask = vm.masterPassword.ui.value
        assertEquals(30, ask.secondsLeft)
        assertFalse(ask.enabled)
        assertEquals(PinLine(UiText.of(R.string.pin_mp_wait), alert = false), ask.line)
        clock.advance(10_000L)
        runCurrent()
        assertEquals(20, vm.masterPassword.ui.value.secondsLeft)
        clock.advance(20_000L)
        runCurrent()
        ask = vm.masterPassword.ui.value
        assertNull(ask.secondsLeft)
        assertTrue(ask.enabled)
    }

    /** build-plan.md §12, decision 4: the lock's own answer, `PinChange.MayNotBeSaved`. */
    @Test
    fun theLocksAnswerThatAChangedPinMayNotBeSavedIsShownAsSuch() = runTest {
        val lock = lockWith(PinStatus.On)
        lock.fake.pinChanges += PinChange.MayNotBeSaved
        val vm = model(lock)
        vm.type(Pins.ALLOWED_3)
        vm.type(Pins.ALLOWED_2)
        vm.type(Pins.ALLOWED_2)
        advanceUntilIdle()
        assertEquals(Stage.MAY_NOT_BE_SAVED, vm.ui.value.stage)
        assertEquals(PinLine(UiText.of(R.string.pin_may_not_be_saved)), vm.ui.value.line)
        assertNull("no \"Your PIN was changed.\"", SettingsNotice.take())
    }

    /** build-plan.md §12, decision 4: the lock's own answer to "Set a PIN", `PinSetResult.MayNotBeSaved`. */
    @Test
    fun theLocksAnswerThatASetPinMayNotBeSavedIsShownAsSuch() = runTest {
        val lock = lockWith(PinStatus.NotSet)
        lock.fake.setPinResults += PinSetResult.MayNotBeSaved
        val vm = model(lock)
        typeMasterPassword(vm)
        vm.type(Pins.ALLOWED)
        vm.type(Pins.ALLOWED)
        advanceUntilIdle()
        assertEquals(Stage.MAY_NOT_BE_SAVED, vm.ui.value.stage)
        assertEquals(PinLine(UiText.of(R.string.pin_may_not_be_saved_first)), vm.ui.value.line)
        assertNull("no \"Your PIN is set.\"", SettingsNotice.take())
    }

    /**
     * build-plan.md §12, decision 4, given to the view model by the test itself at the moment the
     * lock's answer is awaited: what the lock answers later changes nothing of it.
     */
    @Test
    fun aPinThatMayNotBeSavedPromisesNothingAndOffersOnlyOk() = runTest {
        val lock = lockWith(PinStatus.On)
        val vm = model(lock)
        vm.pinMayNotBeSaved()
        assertEquals("nothing is being saved", Stage.CURRENT, vm.ui.value.stage)
        vm.type(Pins.ALLOWED_3)
        vm.type(Pins.ALLOWED_2)
        vm.type(Pins.ALLOWED_2)
        assertEquals(Stage.SAVING, vm.ui.value.stage)
        vm.pinMayNotBeSaved()
        assertEquals(Stage.MAY_NOT_BE_SAVED, vm.ui.value.stage)
        assertEquals(PinLine(UiText.of(R.string.pin_may_not_be_saved)), vm.ui.value.line)
        // The lock's own answer, which comes later, changes nothing of it, and Settings is told nothing.
        advanceUntilIdle()
        assertEquals(Stage.MAY_NOT_BE_SAVED, vm.ui.value.stage)
        assertNull("no \"Your PIN was changed.\"", SettingsNotice.take())
        // Nothing is tried again: the PIN typed as the current one may no longer be it.
        vm.retry()
        vm.type(Pins.ALLOWED_3)
        advanceUntilIdle()
        assertEquals(Stage.MAY_NOT_BE_SAVED, vm.ui.value.stage)
        assertEquals("the lock was asked once", 1, lock.changedPins.size)
        // "OK" closes the route.
        vm.leave()
        assertTrue(vm.ui.value.leave)
    }

    @Test
    fun aFirstPinThatMayNotBeSavedNamesNoOldPin() = runTest {
        val vm = model(lockWith(PinStatus.NotSet))
        typeMasterPassword(vm)
        vm.type(Pins.ALLOWED)
        vm.type(Pins.ALLOWED)
        assertEquals(Stage.SAVING, vm.ui.value.stage)
        vm.pinMayNotBeSaved()
        assertEquals(Stage.MAY_NOT_BE_SAVED, vm.ui.value.stage)
        assertEquals(PinLine(UiText.of(R.string.pin_may_not_be_saved_first)), vm.ui.value.line)
        advanceUntilIdle()
        assertEquals(Stage.MAY_NOT_BE_SAVED, vm.ui.value.stage)
        assertNull(SettingsNotice.take())
    }

    @Test
    fun aPinThatCannotBeSavedOffersTryAgain() = runTest {
        val lock = lockWith(PinStatus.On)
        lock.fake.pinChanges += PinChange.NotSaved
        val vm = model(lock)
        vm.type(Pins.ALLOWED_3)
        vm.type(Pins.ALLOWED_2)
        vm.type(Pins.ALLOWED_2)
        advanceUntilIdle()
        assertEquals(Stage.NOT_SAVED, vm.ui.value.stage)
        assertEquals(PinLine(UiText.of(R.string.pin_not_saved_change)), vm.ui.value.line)
        vm.retry()
        advanceUntilIdle()
        assertEquals(Stage.CURRENT, vm.ui.value.stage) // the current PIN is not kept after a counted check
        vm.type(Pins.ALLOWED_3)
        advanceUntilIdle()
        assertEquals(Stage.DONE, vm.ui.value.stage)
        assertEquals(listOf(Pins.ALLOWED_3 to Pins.ALLOWED_2, Pins.ALLOWED_3 to Pins.ALLOWED_2), lock.changedPins)
    }

    @Test
    fun aLockDropsWhatWasTyped() = runTest {
        val lock = lockWith(PinStatus.NotSet)
        val vm = model(lock)
        vm.masterPassword.field.type("half typed")
        lock.fake.lock(LockReason.SCREEN_OFF)
        runCurrent()
        assertEquals("", vm.masterPassword.field.text.toString())
    }
}
