package io.github.dilshodbekmx.passwordvault.ui.lock

import androidx.lifecycle.ViewModelStore
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PadStatus
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.lock.LockIntent
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.ui.lock.LockKit.ASIDE_AT
import io.github.dilshodbekmx.passwordvault.ui.lock.LockKit.REPLACED_AT
import io.github.dilshodbekmx.passwordvault.ui.lock.LockKit.lockViewModel
import io.github.dilshodbekmx.passwordvault.ui.lock.LockKit.pad
import io.github.dilshodbekmx.passwordvault.ui.lock.LockKit.send
import io.github.dilshodbekmx.passwordvault.ui.lock.LockKit.text
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Build plan 4.2, [JVM]: the PIN pad's ViewModel for every `LockScreen.Pad` of `FakeLockManager`
 * (ux.md §5.9; lock.md §13.2, §13.3 "On the pad", U §12 check 17): each status line with and
 * without fingerprint, which of them are alerts, the dots, the fingerprint key, "Opening…", the
 * shake of each wrong PIN, "Forgot PIN? Use master password", the line of a vault put aside, the
 * test build's line, and the keys, which go to the lock and never stay in the ViewModel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PadViewModelTest {
    private val store = ViewModelStore()
    private val clock = FakeClock(elapsedMs = LockKit.START, autoAdvance = false)

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun aFreshPadHasItsDotsTheForgotButtonAndNothingElse() = runTest {
        val vm = vm(FakeLockManager(initial = LockUiState.Locked(pad())))
        val ui = padUi(vm)
        assertEquals(0, ui.digits)
        assertNull(ui.status)
        assertFalse(ui.fingerprintKey)
        assertFalse(ui.opening)
        assertNull(ui.asideLine)
        assertFalse(ui.goesBack)
        assertFalse(ui.testBuild)
        assertEquals(0, ui.wrongTries)
        assertEquals(listOf(LockButton(text(R.string.lock_pad_forgot), send(LockIntent.UseMasterPassword), LockTags.FORGOT_PIN)), ui.buttons)
        assertNull("not the master-password screen", vm.masterPassword.value)
    }

    @Test
    fun everyStatusLineHasItsTextWithAndWithoutFingerprint() = runTest {
        val expected: Map<PadStatus, Pair<UiText, UiText>> = mapOf(
            PadStatus.TriesLeft(2) to same(UiText.plural(R.plurals.lock_pad_tries_left, 2)),
            PadStatus.WrongPin(4) to same(UiText.plural(R.plurals.lock_pad_wrong_pin, 4)),
            PadStatus.LastTry to (text(R.string.lock_pad_last_try) to text(R.string.lock_pad_last_try_fingerprint)),
            PadStatus.LastTryWhilePending to same(text(R.string.lock_pad_last_try_pending)),
            PadStatus.SameAgain to same(text(R.string.lock_pad_same_again)),
            PadStatus.Interrupted(4) to same(UiText.plural(R.plurals.lock_pad_interrupted, 4)),
            PadStatus.StorageFull to (text(R.string.lock_pad_storage_full) to text(R.string.lock_pad_storage_full_fingerprint)),
            FingerprintLine.TurnedOff to same(text(R.string.lock_fingerprint_turned_off_pin)),
            FingerprintLine.Locked to same(text(R.string.lock_fingerprint_locked_pin)),
            FingerprintLine.DidNotOpen to same(text(R.string.lock_fingerprint_did_not_open_pin)),
            FingerprintLine.NotForApps to same(text(R.string.lock_fingerprint_not_for_apps_pin)),
            FingerprintLine.NoneSaved to same(text(R.string.lock_fingerprint_none_saved_pin)),
            FingerprintLine.UpdateNeeded to same(text(R.string.lock_fingerprint_update_needed_pin)),
            FingerprintLine.CannotCheck to same(text(R.string.lock_fingerprint_cannot_check_pin)),
        )
        assertEquals("every status of the lock is checked", LockKit.PAD_STATUSES.toSet(), expected.keys)
        LockKit.PAD_STATUSES.forEach(LockKit::covered)

        val lock = FakeLockManager(initial = LockUiState.Locked(pad()))
        val vm = vm(lock)
        for ((status, texts) in expected) {
            for (fingerprint in listOf(false, true)) {
                lock.state.value = LockUiState.Locked(pad(digits = 3, status = status, fingerprintKey = fingerprint))
                runCurrent()
                val line = padUi(vm).status!!
                assertEquals("$status, fingerprint $fingerprint", if (fingerprint) texts.second else texts.first, line.text)
                assertEquals("$status: an alert unless it is about the fingerprint", status !is FingerprintLine, line.alert)
                assertNull(line.button)
                assertEquals("typing leaves the line in place (lock.md §6.4)", 3, padUi(vm).digits)
            }
        }
        // Two status lines never share a text on the same pad.
        val texts = LockKit.PAD_STATUSES.map { LockTexts.padStatus(it, fingerprint = false) }
        assertEquals(texts.size, texts.toSet().size)
    }

    @Test
    fun theFingerprintKeyAndOpeningComeFromTheLock() = runTest {
        val lock = FakeLockManager(initial = LockUiState.Locked(pad(fingerprintKey = true)))
        val vm = vm(lock)
        assertTrue(padUi(vm).fingerprintKey)
        lock.state.value = LockUiState.Locked(pad(digits = 6, opening = true))
        runCurrent()
        assertTrue(padUi(vm).opening)
        assertEquals(6, padUi(vm).digits)
        assertFalse(padUi(vm).fingerprintKey)

        // More digits than a PIN has are never drawn.
        lock.state.value = LockUiState.Locked(pad(digits = 9))
        runCurrent()
        assertEquals(6, padUi(vm).digits)
    }

    @Test
    fun theKeysGoToTheLockAndNoDigitStaysOrIsShown() = runTest {
        val lock = FakeLockManager(initial = LockUiState.Locked(pad()))
        val vm = vm(lock)
        vm.onDigit(7)
        vm.onDigit(0)
        vm.onDelete()
        vm.onClear()
        vm.onAction(send(LockIntent.UseFingerprint))
        vm.onAction(padUi(vm).buttons.single().action)
        assertEquals(
            listOf(
                LockIntent.Digit(7),
                LockIntent.Digit(0),
                LockIntent.DeleteDigit,
                LockIntent.ClearDigits,
                LockIntent.UseFingerprint,
                LockIntent.UseMasterPassword,
            ),
            lock.sent,
        )
        assertEquals("a digit intent never prints its digit", "Digit", LockIntent.Digit(7).toString())
        assertEquals("the screen has only how many digits the lock holds", 0, padUi(vm).digits)
    }

    @Test
    fun eachWrongPinShakesTheDotsOnce() = runTest {
        val lock = FakeLockManager(initial = LockUiState.Locked(pad()))
        val vm = vm(lock)
        fun show(screen: LockScreen) {
            lock.state.value = LockUiState.Locked(screen)
            runCurrent()
        }
        // A fresh pad that warns about tries left is not a wrong PIN.
        show(pad(status = PadStatus.TriesLeft(2)))
        assertEquals(0, padUi(vm).wrongTries)

        // Six digits, the check, a wrong PIN: one shake.
        show(pad(digits = 5, status = PadStatus.TriesLeft(2)))
        show(pad(digits = 6, status = PadStatus.TriesLeft(2), opening = true))
        show(pad(status = PadStatus.WrongPin(4)))
        assertEquals(1, padUi(vm).wrongTries)

        // Typing again with the line in place does not shake.
        show(pad(digits = 2, status = PadStatus.WrongPin(4)))
        show(pad(digits = 0, status = PadStatus.WrongPin(4)))
        assertEquals(1, padUi(vm).wrongTries)

        // The same wrong PIN again, the last try, and the last try while a new master password waits.
        for (status in listOf(PadStatus.SameAgain, PadStatus.LastTry, PadStatus.LastTryWhilePending)) {
            show(pad(digits = 6, status = PadStatus.WrongPin(4), opening = true))
            show(pad(status = status))
        }
        assertEquals(4, padUi(vm).wrongTries)

        // A check that ends in a fingerprint line, or in the master-password screen, does not shake.
        show(pad(digits = 6, opening = true))
        show(pad(status = FingerprintLine.CannotCheck))
        assertEquals(4, padUi(vm).wrongTries)
        show(pad(digits = 6, opening = true))
        show(LockKit.masterPassword())
        show(pad())
        assertEquals(4, padUi(vm).wrongTries)
    }

    @Test
    fun aVaultPutAsideHasItsLineAndBackGoesBack() = runTest {
        val lock = FakeLockManager(initial = LockUiState.Locked(pad(aside = AsideLine.LockedOn(ASIDE_AT))))
        val vm = vm(lock)
        assertEquals(text(R.string.lock_aside_locked_on, LockDate(ASIDE_AT, LockDate.Style.DATE)), padUi(vm).asideLine)
        assertTrue(padUi(vm).goesBack)
        lock.state.value = LockUiState.Locked(pad(aside = AsideLine.FromBefore(REPLACED_AT)))
        runCurrent()
        assertEquals(text(R.string.lock_from_before, LockDate(REPLACED_AT, LockDate.Style.DATE)), padUi(vm).asideLine)
        vm.onAction(send(LockIntent.GoBack))
        assertEquals(listOf<LockIntent>(LockIntent.GoBack), lock.sent)
    }

    @Test
    fun theTestBuildLineShowsExactlyWhenSoftwareKeysAreAllowed() = runTest {
        for (testBuild in listOf(false, true)) {
            store.clear()
            val lock = FakeLockManager(initial = LockUiState.Locked(pad()))
            val vm = vm(lock, testBuild)
            assertEquals(testBuild, padUi(vm).testBuild)
            lock.state.value = LockUiState.Locked(LockKit.fingerprintOnly())
            runCurrent()
            assertEquals(testBuild, (vm.screen.value as FingerprintOnlyUi).testBuild)
            lock.state.value = LockUiState.Locked(LockKit.masterPassword())
            runCurrent()
            assertEquals(testBuild, (vm.screen.value as MasterPasswordUi).testBuild)
        }
        // The debug build, whose unit tests these are, allows software keys (architecture.md §7.2).
        assertTrue(io.github.dilshodbekmx.passwordvault.BuildConfig.ALLOW_SOFTWARE_KEYSTORE)
    }

    @Test
    fun anUnlockKeepsThePadUntilTheRouteLeaves() = runTest {
        val lock = FakeLockManager(initial = LockUiState.Locked(pad(digits = 6, opening = true)))
        val vm = vm(lock)
        lock.state.value = LockUiState.Unlocked(emptyList(), null)
        runCurrent()
        assertTrue("nothing flashes while the route leaves", padUi(vm).opening)
    }

    private fun TestScope.vm(lock: FakeLockManager, testBuild: Boolean = false): LockViewModel = lockViewModel(store, lock, clock, testBuild)

    private fun padUi(vm: LockViewModel): PadUi = vm.screen.value as PadUi

    private fun same(text: UiText): Pair<UiText, UiText> = text to text
}
