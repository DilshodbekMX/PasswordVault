package io.github.dilshodbekmx.passwordvault.ui.lock

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpError
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Practice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ReplacedLine
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.lock.FingerprintStatus
import io.github.dilshodbekmx.passwordvault.lock.LockIntent
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.lock.LockStatus
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.MpPurpose
import io.github.dilshodbekmx.passwordvault.lock.MpResult
import io.github.dilshodbekmx.passwordvault.lock.PinStatus
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Build plan 3.4, [JVM]: the master-password screen's ViewModel for every `LockScreen.MasterPassword`
 * state of `FakeLockManager` (ux.md §5.10; lock.md §13.2, §13.3): each reason, each line after a
 * try with its button, the wait and its countdown, a pending new master password, "Opening…", the
 * practice buttons, the fingerprint lines and the line of a vault put aside; and the field, which is
 * empty after every submit (lock.md §5.8, architecture.md §5.6).
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalFoundationApi::class)
class LockViewModelTest {
    private val store = ViewModelStore()
    private val clock = FakeClock(elapsedMs = START, autoAdvance = false)

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    // ---- every state ----------------------------------------------------------------------------

    @Test
    fun theFakesOwnLockScreenAsksForTheMasterPasswordWithNothingElse() = runTest {
        val lock = FakeLockManager(initial = LockUiState.Locked(FakeLockManager().lockScreen))
        val ui = ui(viewModel(lock))
        assertEquals(text(R.string.lock_mp_title), ui.title)
        assertNull(ui.asideLine)
        assertNull(ui.reason)
        assertNull(ui.fingerprintLine)
        assertTrue(ui.fieldEnabled)
        assertFalse(ui.fieldError)
        assertNull(ui.pending)
        assertEquals(emptyList<LockLine>(), ui.lines)
        assertNull(ui.countdown)
        assertFalse(ui.opening)
        assertTrue(ui.canSubmit)
        assertEquals(listOf(forgot), ui.buttons)
        assertFalse(ui.goesBack)
    }

    @Test
    fun eachReasonHasItsSubtitleAndTitle() = runTest {
        val cases = listOf(
            MpReason.PracticeDue to R.string.lock_mp_reason_practice,
            MpReason.WelcomeBack to R.string.lock_mp_reason_welcome_back,
            MpReason.FromBefore(REPLACED_AT) to R.string.lock_mp_reason_from_before,
            MpReason.Pin(PinReason.LIMIT) to R.string.lock_mp_reason_pin_limit,
            MpReason.Pin(PinReason.STOPPED_WORKING) to R.string.lock_mp_reason_pin_stopped_working,
            MpReason.Pin(PinReason.NEWER_APP) to R.string.lock_mp_reason_pin_newer_app,
            MpReason.Pin(PinReason.CANNOT_CHECK) to R.string.lock_mp_reason_pin_cannot_check,
            MpReason.Pin(PinReason.STORAGE_FULL) to R.string.lock_mp_reason_pin_storage_full,
            MpReason.MasterChanged to R.string.lock_mp_reason_master_changed,
            MpReason.NoSecureHardware to R.string.lock_mp_reason_no_secure_hardware,
        )
        val lock = FakeLockManager(initial = LockUiState.Locked(screen()))
        val vm = viewModel(lock)
        assertNull(ui(vm).reason)
        for ((reason, id) in cases) {
            lock.state.value = LockUiState.Locked(screen(reason = reason))
            runCurrent()
            val ui = ui(vm)
            assertEquals("$reason", text(id), ui.reason)
            val title = when (reason) {
                MpReason.WelcomeBack -> text(R.string.lock_mp_title_welcome_back)
                is MpReason.FromBefore -> UiText.of(R.string.lock_from_before, LockDate(REPLACED_AT, LockDate.Style.DATE))
                else -> text(R.string.lock_mp_title)
            }
            assertEquals("$reason", title, ui.title)
        }
        // An older copy names the day of the copy that opens.
        lock.state.value = LockUiState.Locked(screen(reason = MpReason.OlderCopy(COPY_AT)))
        runCurrent()
        assertEquals(UiText.of(R.string.lock_mp_reason_older_copy, LockDate(COPY_AT, LockDate.Style.DAY_MONTH)), ui(vm).reason)
        assertEquals(text(R.string.lock_mp_title), ui(vm).title)
        // Every case of MpReason is among them (a new case does not compile here).
        for (reason in cases.map { it.first } + MpReason.OlderCopy(COPY_AT)) {
            when (reason) {
                MpReason.PracticeDue, MpReason.WelcomeBack, is MpReason.FromBefore, is MpReason.Pin, MpReason.MasterChanged,
                is MpReason.OlderCopy, MpReason.NoSecureHardware,
                -> Unit
            }
        }
        assertEquals(PinReason.entries.toSet(), cases.mapNotNull { (it.first as? MpReason.Pin)?.reason }.toSet())
    }

    @Test
    fun eachFingerprintLineSaysUseYourMasterPassword() = runTest {
        val cases = mapOf(
            FingerprintLine.TurnedOff to R.string.lock_fingerprint_turned_off_master,
            FingerprintLine.StoppedWorking to R.string.lock_fingerprint_stopped_working_master,
            FingerprintLine.Locked to R.string.lock_fingerprint_locked_master,
            FingerprintLine.DidNotOpen to R.string.lock_fingerprint_did_not_open_master,
            FingerprintLine.NotForApps to R.string.lock_fingerprint_not_for_apps_master,
            FingerprintLine.NoneSaved to R.string.lock_fingerprint_none_saved_master,
            FingerprintLine.UpdateNeeded to R.string.lock_fingerprint_update_needed_master,
            FingerprintLine.CannotCheck to R.string.lock_fingerprint_cannot_check_master,
        )
        // Every FingerprintLine is among them (a new case does not compile here).
        for (line in cases.keys) {
            when (line) {
                FingerprintLine.TurnedOff, FingerprintLine.StoppedWorking, FingerprintLine.Locked, FingerprintLine.DidNotOpen,
                FingerprintLine.NotForApps, FingerprintLine.NoneSaved, FingerprintLine.UpdateNeeded, FingerprintLine.CannotCheck,
                -> Unit
            }
        }
        val lock = FakeLockManager(initial = LockUiState.Locked(screen()))
        val vm = viewModel(lock)
        for ((line, id) in cases) {
            lock.state.value = LockUiState.Locked(screen(reason = MpReason.Pin(PinReason.LIMIT), fingerprintLine = line))
            runCurrent()
            assertEquals("$line", text(id), ui(vm).fingerprintLine)
            assertEquals(text(R.string.lock_mp_reason_pin_limit), ui(vm).reason)
        }
    }

    @Test
    fun usePinIsOfferedWhenThePinCanBeUsed() = runTest {
        val vm = viewModel(FakeLockManager(initial = LockUiState.Locked(screen(canUsePin = true))))
        assertEquals(listOf(LockButton(text(R.string.lock_mp_use_pin), send(LockIntent.UsePin), LockTags.USE_PIN), forgot), ui(vm).buttons)
    }

    @Test
    fun aPracticeDayInGraceOffersNotNowAndICantRememberIt() = runTest {
        val lock = FakeLockManager(
            initial = LockUiState.Locked(screen(reason = MpReason.PracticeDue, canUsePin = true, practice = Practice(Shortcut.PIN))),
        )
        val vm = viewModel(lock)
        val cantRemember = LockButton(text(R.string.lock_mp_cant_remember), send(LockIntent.CantRemember), LockTags.CANT_REMEMBER)
        assertEquals(
            listOf(LockButton(text(R.string.lock_mp_not_now_pin), send(LockIntent.UsePin), LockTags.USE_PIN), cantRemember, forgot),
            ui(vm).buttons,
        )
        assertEquals(text(R.string.lock_mp_reason_practice), ui(vm).reason)

        // The shortcut of "Not now" is fingerprint: no PIN button.
        lock.state.value = LockUiState.Locked(screen(reason = MpReason.PracticeDue, practice = Practice(Shortcut.FINGERPRINT)))
        runCurrent()
        assertEquals(
            listOf(LockButton(text(R.string.lock_mp_not_now_fingerprint), send(LockIntent.NotNow), LockTags.NOT_NOW), cantRemember, forgot),
            ui(vm).buttons,
        )

        // Past its grace the master password is required: no "Not now".
        lock.state.value = LockUiState.Locked(screen(reason = MpReason.PracticeDue, practice = Practice(null)))
        runCurrent()
        assertEquals(listOf(cantRemember, forgot), ui(vm).buttons)
    }

    @Test
    fun aWrongMasterPasswordGivesTheNeutralHint() = runTest {
        val vm = viewModel(FakeLockManager(initial = LockUiState.Locked(screen(error = MpError.Wrong(offerForgot = false, replaced = null)))))
        assertEquals(listOf(wrong), ui(vm).lines)
        assertTrue(ui(vm).fieldError)
        assertTrue(ui(vm).fieldEnabled)
        assertTrue(ui(vm).canSubmit)
    }

    @Test
    fun wrongTwiceWithAShortcutAddsCantRememberItWithIForgotIt() = runTest {
        val lock = FakeLockManager(initial = LockUiState.Locked(screen(canUsePin = true, error = MpError.Wrong(offerForgot = true, replaced = null))))
        lock.status.value = LockStatus.NONE.copy(pin = PinStatus.On, fingerprint = FingerprintStatus.On)
        val vm = viewModel(lock)
        val iForgot = LockButton(text(R.string.lock_mp_i_forgot), LockAction.OpenSheet, LockTags.I_FORGOT)
        assertEquals(listOf(wrong, LockLine(text(R.string.lock_mp_wrong_forgot_pin), iForgot)), ui(vm).lines)

        // No PIN on this phone, fingerprint works: the line names the fingerprint (lock.md F11 step 2).
        lock.status.value = LockStatus.NONE.copy(pin = PinStatus.Off(PinReason.LIMIT), fingerprint = FingerprintStatus.On)
        runCurrent()
        assertEquals(listOf(wrong, LockLine(text(R.string.lock_mp_wrong_forgot_fingerprint), iForgot)), ui(vm).lines)
    }

    @Test
    fun aWrongMasterPasswordAfterAReplacementOffersThePasswordsFromBefore() = runTest {
        val dayMonth = LockDate(REPLACED_AT, LockDate.Style.DAY_MONTH)
        val lock = FakeLockManager(
            initial = LockUiState.Locked(screen(error = MpError.Wrong(false, ReplacedLine(REPLACED_AT, Shortcut.PIN, OLD_VAULT)))),
        )
        val vm = viewModel(lock)
        assertEquals(
            listOf(
                wrong,
                LockLine(
                    UiText.of(R.string.lock_mp_wrong_replaced_pin, dayMonth),
                    LockButton(UiText.of(R.string.lock_mp_open_from_before, dayMonth), send(LockIntent.OpenAside(OLD_VAULT)), LockTags.FROM_BEFORE),
                ),
            ),
            ui(vm).lines,
        )

        // Replaced with a fingerprint, more than once: the button opens the sheet's list.
        lock.state.value = LockUiState.Locked(screen(error = MpError.Wrong(true, ReplacedLine(REPLACED_AT, Shortcut.FINGERPRINT, null))))
        runCurrent()
        val lines = ui(vm).lines
        assertEquals(3, lines.size)
        assertEquals(
            LockLine(
                UiText.of(R.string.lock_mp_wrong_replaced_fingerprint, dayMonth),
                LockButton(UiText.of(R.string.lock_mp_open_from_before, dayMonth), LockAction.OpenSheet, LockTags.FROM_BEFORE),
            ),
            lines[2],
        )
    }

    @Test
    fun notEnoughMemoryOffersTryAgainWhichTakesTheLineAway() = runTest {
        val lock = FakeLockManager(initial = LockUiState.Locked(screen(error = MpError.NoMemory)))
        val vm = viewModel(lock)
        assertEquals(listOf(noMemory), ui(vm).lines)
        assertFalse("not the field's fault", ui(vm).fieldError)
        vm.onAction(LockAction.TryAgain)
        runCurrent()
        assertEquals(emptyList<LockLine>(), ui(vm).lines)
        assertEquals(emptyList<LockIntent>(), lock.sent)

        // The lock shows its screen again, with a new answer: the line is back.
        lock.state.value = LockUiState.Locked(screen(reason = MpReason.MasterChanged, error = MpError.NoMemory))
        runCurrent()
        assertEquals(listOf(noMemory), ui(vm).lines)
    }

    @Test
    fun theWaitDisablesTheFieldAndCountsDownEverySecond() = runTest {
        val lock = FakeLockManager(
            initial = LockUiState.Locked(screen(waitUntilElapsedMs = START + 30_000, error = MpError.Wrong(false, null))),
        )
        val vm = viewModel(lock)
        var ui = ui(vm)
        assertFalse(ui.fieldEnabled)
        assertFalse(ui.canSubmit)
        assertEquals(listOf(wrong, waitLine), ui.lines)
        assertEquals(seconds(30), ui.countdown)

        clock.advance(400)
        runCurrent()
        assertEquals("the shown second has not passed", seconds(30), ui(vm).countdown)
        clock.advance(600)
        runCurrent()
        assertEquals(seconds(29), ui(vm).countdown)
        clock.advance(27_999)
        runCurrent()
        assertEquals(seconds(2), ui(vm).countdown)
        clock.advance(1)
        runCurrent()
        assertEquals(seconds(1), ui(vm).countdown)
        clock.advance(1_000)
        runCurrent()
        ui = ui(vm)
        assertNull(ui.countdown)
        assertTrue(ui.fieldEnabled)
        assertTrue(ui.canSubmit)
        assertEquals(listOf(wrong), ui.lines)
    }

    @Test
    fun aWaitThatHasPassedLeavesTheFieldEnabled() = runTest {
        val vm = viewModel(FakeLockManager(initial = LockUiState.Locked(screen(waitUntilElapsedMs = START - 1))))
        assertTrue(ui(vm).fieldEnabled)
        assertNull(ui(vm).countdown)
        assertEquals(emptyList<LockLine>(), ui(vm).lines)
    }

    @Test
    fun aPendingNewMasterPasswordSaysInAboutHowManyHours() = runTest {
        val lock = FakeLockManager(initial = LockUiState.Locked(screen(pendingReadyInMs = 20 * HOUR)))
        val vm = viewModel(lock)
        assertEquals(hours(20), ui(vm).pending)
        for ((ms, n) in listOf(20 * HOUR - 1 to 20, 19 * HOUR + 1 to 20, 30 * MINUTE to 1, 0L to 1)) {
            lock.state.value = LockUiState.Locked(screen(pendingReadyInMs = ms))
            runCurrent()
            assertEquals("$ms ms", hours(n), ui(vm).pending)
        }
    }

    @Test
    fun openingShowsTheSpinnerAndNoLine() = runTest {
        val vm = viewModel(FakeLockManager(initial = LockUiState.Locked(screen(opening = true, error = MpError.Wrong(true, null)))))
        val ui = ui(vm)
        assertTrue(ui.opening)
        assertFalse(ui.canSubmit)
        assertEquals(emptyList<LockLine>(), ui.lines)
        assertFalse(ui.fieldError)
    }

    @Test
    fun theScreenOfAVaultPutAsideHasItsLineAndGoesBack() = runTest {
        val lock = FakeLockManager(initial = LockUiState.Locked(screen(aside = AsideLine.LockedOn(ASIDE_AT))))
        val vm = viewModel(lock)
        assertEquals(UiText.of(R.string.lock_aside_locked_on, LockDate(ASIDE_AT, LockDate.Style.DATE)), ui(vm).asideLine)
        assertEquals(text(R.string.lock_mp_title), ui(vm).title)
        assertTrue(ui(vm).goesBack)

        // A vault from before a replacement: its title already says it.
        lock.state.value = LockUiState.Locked(screen(reason = MpReason.FromBefore(REPLACED_AT), aside = AsideLine.FromBefore(REPLACED_AT)))
        runCurrent()
        assertNull(ui(vm).asideLine)
        assertEquals(UiText.of(R.string.lock_from_before, LockDate(REPLACED_AT, LockDate.Style.DATE)), ui(vm).title)
        assertEquals(text(R.string.lock_mp_reason_from_before), ui(vm).reason)
        assertTrue(ui(vm).goesBack)

        // Its line without that reason.
        lock.state.value = LockUiState.Locked(screen(aside = AsideLine.FromBefore(REPLACED_AT)))
        runCurrent()
        assertEquals(UiText.of(R.string.lock_from_before, LockDate(REPLACED_AT, LockDate.Style.DATE)), ui(vm).asideLine)

        vm.onAction(LockAction.Send(LockIntent.GoBack))
        assertEquals(listOf<LockIntent>(LockIntent.GoBack), lock.sent)
    }

    @Test
    fun theButtonsSendTheirIntents() = runTest {
        val lock = FakeLockManager(
            initial = LockUiState.Locked(screen(reason = MpReason.PracticeDue, canUsePin = true, practice = Practice(Shortcut.PIN))),
        )
        val vm = viewModel(lock)
        for (button in ui(vm).buttons) vm.onAction(button.action)
        assertEquals(listOf(LockIntent.UsePin, LockIntent.CantRemember, LockIntent.OpenForgotSheet), lock.sent)
        assertEquals(emptyList<MpPurpose>(), lock.purposes)
    }

    @Test
    fun anotherLockScreenIsNotThisOneAndAnUnlockKeepsTheLastScreen() = runTest {
        val lock = FakeLockManager(initial = LockUiState.Locked(LockScreen.Pad(0, null, fingerprintKey = false, opening = false, aside = null)))
        val vm = viewModel(lock)
        assertNull(vm.masterPassword.value)

        lock.state.value = LockUiState.Locked(screen(reason = MpReason.MasterChanged))
        runCurrent()
        assertEquals(text(R.string.lock_mp_reason_master_changed), ui(vm).reason)

        // Unlocked: the route is about to leave; it keeps showing what it showed.
        lock.state.value = LockUiState.Unlocked(emptyList(), null)
        runCurrent()
        assertEquals(text(R.string.lock_mp_reason_master_changed), ui(vm).reason)
    }

    // ---- the field -----------------------------------------------------------------------------

    @Test
    fun theFieldIsEmptyAfterASubmitAndARightPasswordUnlocks() = runTest {
        val lock = RecordingLock(FakeLockManager(initial = LockUiState.Locked(screen())))
        val vm = viewModel(lock)
        vm.password.setTextAndPlaceCursorAtEnd(TYPED)
        assertTrue(ui(vm).canSubmit)

        vm.submit()
        assertEmpty(vm.password)
        runCurrent()

        assertEquals(listOf(MpPurpose.UNLOCK), lock.fake.purposes)
        assertEquals(TYPED, lock.typed)
        assertTrue("the characters were zeroed after the check", lock.chars!!.all { it == '\u0000' })
        assertTrue(lock.fake.state.value is LockUiState.Unlocked)
        assertEmpty(vm.password)
    }

    @Test
    fun theFieldIsEmptyAfterAWrongPasswordAndTheAnswerIsShown() = runTest {
        val lock = FakeLockManager(initial = LockUiState.Locked(screen()))
        lock.mpResults += MpResult.Wrong
        val vm = viewModel(lock)
        vm.password.setTextAndPlaceCursorAtEnd(TYPED)
        vm.submit()
        runCurrent()
        assertEmpty(vm.password)
        assertEquals(listOf(MpPurpose.UNLOCK), lock.purposes)
        // The fake leaves its screen as it was: the answer is shown until the lock shows another one.
        assertEquals(listOf(wrong), ui(vm).lines)
        assertTrue(ui(vm).fieldError)
        assertTrue(lock.state.value is LockUiState.Locked)

        lock.state.value = LockUiState.Locked(screen(reason = MpReason.MasterChanged))
        runCurrent()
        assertEquals(emptyList<LockLine>(), ui(vm).lines)
    }

    @Test
    fun theFieldIsEmptyAfterNotEnoughMemoryAndTryAgainTakesTheLineAway() = runTest {
        val lock = FakeLockManager(initial = LockUiState.Locked(screen()))
        lock.mpResults += MpResult.NoMemory
        val vm = viewModel(lock)
        vm.password.setTextAndPlaceCursorAtEnd(TYPED)
        vm.submit()
        runCurrent()
        assertEmpty(vm.password)
        assertEquals(listOf(noMemory), ui(vm).lines)
        vm.onAction(LockAction.TryAgain)
        runCurrent()
        assertEquals(emptyList<LockLine>(), ui(vm).lines)
    }

    /** lock.md F11 steps 15 and 16: the new master password was ready and typed here, and could not finish. */
    @Test
    fun aNewMasterPasswordThatCouldNotFinishSaysSoAndNothingElse() = runTest {
        val lock = FakeLockManager(initial = LockUiState.Locked(screen()))
        lock.mpResults += MpResult.NotFinished
        val vm = viewModel(lock)
        vm.password.setTextAndPlaceCursorAtEnd(TYPED)
        vm.submit()
        runCurrent()
        assertEmpty(vm.password)
        assertEquals(listOf(LockLine(UiText.of(R.string.lock_mp_not_finished), alert = true)), ui(vm).lines)
        assertFalse("not a wrong master password", ui(vm).fieldError)
        assertTrue(ui(vm).canSubmit)
        assertTrue(lock.state.value is LockUiState.Locked)

        lock.state.value = LockUiState.Locked(screen(reason = MpReason.MasterChanged))
        runCurrent()
        assertEquals("another screen of the lock: the answer is gone", emptyList<LockLine>(), ui(vm).lines)
    }

    @Test
    fun theFieldIsEmptyAfterAWaitAnswerAndWaitsForIt() = runTest {
        val lock = FakeLockManager(initial = LockUiState.Locked(screen()))
        lock.mpResults += MpResult.Wait(START + 30_000)
        val vm = viewModel(lock)
        vm.password.setTextAndPlaceCursorAtEnd(TYPED)
        vm.submit()
        runCurrent()
        assertEmpty(vm.password)
        assertFalse(ui(vm).fieldEnabled)
        assertEquals(listOf(waitLine), ui(vm).lines)
        assertEquals(seconds(30), ui(vm).countdown)

        // Nothing is sent while the field waits.
        vm.password.setTextAndPlaceCursorAtEnd(TYPED)
        vm.submit()
        runCurrent()
        assertEquals(1, lock.purposes.size)
        assertEquals(TYPED, vm.password.text.toString())

        clock.advance(30_000)
        runCurrent()
        assertTrue(ui(vm).fieldEnabled)
        vm.submit()
        runCurrent()
        assertEquals(2, lock.purposes.size)
        assertEmpty(vm.password)
    }

    @Test
    fun anEmptyFieldSendsNothing() = runTest {
        val lock = FakeLockManager(initial = LockUiState.Locked(screen()))
        val vm = viewModel(lock)
        vm.submit()
        runCurrent()
        assertEquals(emptyList<MpPurpose>(), lock.purposes)
    }

    @Test
    fun nothingIsSentWhileTheLockChecksAndOpeningShowsUntilItAnswers() = runTest {
        val lock = RecordingLock(FakeLockManager(initial = LockUiState.Locked(screen())), gate = CompletableDeferred())
        val vm = viewModel(lock)
        vm.password.setTextAndPlaceCursorAtEnd(TYPED)
        vm.submit()
        runCurrent()
        assertEmpty(vm.password)
        assertTrue("Opening… from the tap until the lock answers", ui(vm).opening)
        assertFalse(ui(vm).canSubmit)

        // A second submit while the first is checked: nothing sent, nothing emptied.
        vm.password.setTextAndPlaceCursorAtEnd("second")
        vm.submit()
        runCurrent()
        assertEquals(1, lock.calls)
        assertEquals("second", vm.password.text.toString())
        assertFalse("not zeroed while the lock still has them", lock.chars!!.all { it == '\u0000' })

        lock.gate!!.complete(MpResult.Wrong)
        runCurrent()
        assertFalse(ui(vm).opening)
        assertEquals(listOf(wrong), ui(vm).lines)
        assertTrue(lock.chars!!.all { it == '\u0000' })
    }

    @Test
    fun nothingIsSentWhileTheLockSaysOpening() = runTest {
        val lock = FakeLockManager(initial = LockUiState.Locked(screen(opening = true)))
        val vm = viewModel(lock)
        vm.password.setTextAndPlaceCursorAtEnd(TYPED)
        vm.submit()
        runCurrent()
        assertEquals(emptyList<MpPurpose>(), lock.purposes)
        assertEquals(TYPED, vm.password.text.toString())
    }

    @Test
    fun nothingIsSentFromAnotherLockScreen() = runTest {
        val lock = FakeLockManager(initial = LockUiState.Locked(LockScreen.FingerprintOnly(null, null, null)))
        val vm = viewModel(lock)
        vm.password.setTextAndPlaceCursorAtEnd(TYPED)
        vm.submit()
        runCurrent()
        assertEquals(emptyList<MpPurpose>(), lock.purposes)
    }

    @Test
    fun theFieldIsEmptiedWhenTheAppIsUnlockedOtherwiseAndWhenTheViewModelIsCleared() = runTest {
        val lock = FakeLockManager(initial = LockUiState.Locked(screen()))
        val vm = viewModel(lock)
        vm.password.setTextAndPlaceCursorAtEnd(TYPED)
        lock.state.value = LockUiState.Unlocked(emptyList(), null)
        runCurrent()
        assertEmpty(vm.password)

        lock.state.value = LockUiState.Locked(screen())
        runCurrent()
        vm.password.setTextAndPlaceCursorAtEnd(TYPED)
        store.clear()
        assertEmpty(vm.password)
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** The ViewModel over [lock], with a collector of its screen as the route has; cleared in [tearDown]. */
    private fun TestScope.viewModel(lock: LockManager): LockViewModel {
        val factory = viewModelFactory { initializer { LockViewModel(lock, clock, backgroundScope) } }
        val vm = ViewModelProvider(store, factory)[LockViewModel::class.java]
        backgroundScope.launch { vm.masterPassword.collect {} }
        runCurrent()
        return vm
    }

    private fun ui(vm: LockViewModel): MasterPasswordUi {
        val ui = vm.masterPassword.value
        assertNotNull("the master-password screen is shown", ui)
        return ui!!
    }

    private fun assertEmpty(field: TextFieldState) {
        assertEquals("", field.text.toString())
        assertFalse("nothing to undo", field.undoState.canUndo)
    }

    /**
     * [fake] with a record of the characters it was given: the array itself, to see that it is
     * zeroed afterwards, and a copy of what it held. With [gate], the check waits for it.
     */
    private class RecordingLock(val fake: FakeLockManager, val gate: CompletableDeferred<MpResult>? = null) : LockManager by fake {
        var chars: CharArray? = null
        var typed: String? = null
        var calls = 0

        override suspend fun submitMasterPassword(chars: CharArray, purpose: MpPurpose): MpResult {
            calls++
            this.chars = chars
            typed = String(chars)
            gate?.let { return it.await() }
            return fake.submitMasterPassword(chars, purpose)
        }
    }

    private companion object {
        const val START = 5_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val REPLACED_AT = 1_791_331_200_000L
        const val COPY_AT = 1_791_158_400_000L
        const val ASIDE_AT = 1_791_244_800_000L
        const val OLD_VAULT = "00000000-0000-4000-8000-0000000000aa"
        const val TYPED = "correct horse battery"

        fun text(id: Int): UiText = UiText.of(id)
        fun send(intent: LockIntent): LockAction = LockAction.Send(intent)
        fun seconds(n: Int): UiText = UiText.plural(R.plurals.lock_mp_wait_seconds, n)
        fun hours(n: Int): UiText = UiText.plural(R.plurals.lock_mp_pending_hours, n)

        val forgot = LockButton(text(R.string.lock_mp_forgot), LockAction.OpenSheet, LockTags.FORGOT)
        val wrong = LockLine(text(R.string.lock_mp_wrong), alert = true)
        val noMemory = LockLine(
            text(R.string.lock_mp_no_memory),
            LockButton(text(R.string.common_try_again), LockAction.TryAgain, LockTags.TRY_AGAIN),
            alert = true,
        )
        val waitLine = LockLine(text(R.string.lock_mp_wait))

        fun screen(
            reason: MpReason? = null,
            canUsePin: Boolean = false,
            practice: Practice? = null,
            waitUntilElapsedMs: Long? = null,
            error: MpError? = null,
            pendingReadyInMs: Long? = null,
            opening: Boolean = false,
            aside: AsideLine? = null,
            fingerprintLine: FingerprintLine? = null,
        ) = LockScreen.MasterPassword(reason, canUsePin, practice, waitUntilElapsedMs, error, pendingReadyInMs, opening, aside, fingerprintLine)
    }
}
