package io.github.dilshodbekmx.passwordvault.ui.lock

import androidx.lifecycle.ViewModelStore
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.CurrentVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PadStatus
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinReason
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.lock.LockIntent
import io.github.dilshodbekmx.passwordvault.lock.LockStatus
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.OlderPasswordChoice
import io.github.dilshodbekmx.passwordvault.lock.ReplacedNotice
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.ui.lock.LockKit.ASIDE_AT
import io.github.dilshodbekmx.passwordvault.ui.lock.LockKit.CHANGED_AT
import io.github.dilshodbekmx.passwordvault.ui.lock.LockKit.REPLACED_AT
import io.github.dilshodbekmx.passwordvault.ui.lock.LockKit.VAULT_A
import io.github.dilshodbekmx.passwordvault.ui.lock.LockKit.VAULT_B
import io.github.dilshodbekmx.passwordvault.ui.lock.LockKit.aside
import io.github.dilshodbekmx.passwordvault.ui.lock.LockKit.fingerprintOnly
import io.github.dilshodbekmx.passwordvault.ui.lock.LockKit.lockViewModel
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
 * Build plan 4.2, [JVM], U §12 check 17: the fingerprint-only screen (lock.md §13.3, its second
 * table), "This is an older master password" in both forms (F16b), and "Your passwords can't be
 * opened" for each reason (§13.1 row 2), from `FakeLockManager`: every text, every button and what
 * it tells the lock. None of them offers to delete.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OtherScreensViewModelTest {
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

    // ---- The fingerprint-only screen ------------------------------------------------------------

    @Test
    fun theFingerprintOnlyScreenSaysUseYourMasterPasswordForEachFingerprintLine() = runTest {
        val expected = mapOf(
            FingerprintLine.TurnedOff to R.string.lock_fingerprint_turned_off_master,
            FingerprintLine.Locked to R.string.lock_fingerprint_locked_master,
            FingerprintLine.DidNotOpen to R.string.lock_fingerprint_did_not_open_master,
            FingerprintLine.NotForApps to R.string.lock_fingerprint_not_for_apps_master,
            FingerprintLine.NoneSaved to R.string.lock_fingerprint_none_saved_master,
            FingerprintLine.UpdateNeeded to R.string.lock_fingerprint_update_needed_master,
            FingerprintLine.CannotCheck to R.string.lock_fingerprint_cannot_check_master,
        )
        assertEquals(LockKit.FINGERPRINT_LINES.toSet(), expected.keys)
        val lock = FakeLockManager(initial = LockUiState.Locked(fingerprintOnly()))
        val vm = vm(lock)
        assertNull(fpUi(vm).status)
        for ((line, id) in expected) {
            lock.state.value = LockUiState.Locked(fingerprintOnly(status = line))
            runCurrent()
            assertEquals("$line", LockLine(text(id), alert = false), fpUi(vm).status)
        }
        // A status the lock does not give there reads as on a pad with the fingerprint key.
        lock.state.value = LockUiState.Locked(fingerprintOnly(status = PadStatus.StorageFull))
        runCurrent()
        assertEquals(LockLine(text(R.string.lock_pad_storage_full_fingerprint), alert = true), fpUi(vm).status)
    }

    @Test
    fun theFingerprintOnlyScreenSaysWhyThereIsNoPad() = runTest {
        val expected = mapOf(
            PinReason.LIMIT to R.string.lock_fingerprint_only_pin_limit,
            PinReason.CANNOT_CHECK to R.string.lock_fingerprint_only_pin_cannot_check,
            PinReason.STOPPED_WORKING to R.string.lock_fingerprint_only_pin_stopped_working,
            PinReason.STORAGE_FULL to R.string.lock_fingerprint_only_pin_storage_full,
            PinReason.NEWER_APP to R.string.lock_fingerprint_only_pin_newer_app,
        )
        assertEquals(PinReason.entries.toSet(), expected.keys)
        val lock = FakeLockManager(initial = LockUiState.Locked(fingerprintOnly()))
        val vm = vm(lock)
        assertNull("no PIN was ever set: no line", fpUi(vm).pinReason)
        for ((reason, id) in expected) {
            lock.state.value = LockUiState.Locked(fingerprintOnly(pinReason = reason, status = FingerprintLine.Locked))
            runCurrent()
            assertEquals("$reason", text(id), fpUi(vm).pinReason)
            assertEquals(text(R.string.lock_fingerprint_locked_master), fpUi(vm).status?.text)
        }
    }

    @Test
    fun theFingerprintOnlyButtonsOpenThePromptOrTheMasterPassword() = runTest {
        val lock = FakeLockManager(initial = LockUiState.Locked(fingerprintOnly(aside = AsideLine.LockedOn(ASIDE_AT))))
        val vm = vm(lock)
        val ui = fpUi(vm)
        assertEquals(
            listOf(
                LockButton(text(R.string.lock_use_fingerprint), send(LockIntent.UseFingerprint), LockTags.USE_FINGERPRINT, ButtonStyle.FILLED),
                LockButton(text(R.string.lock_use_master_password), send(LockIntent.UseMasterPassword), LockTags.USE_MASTER_PASSWORD, ButtonStyle.OUTLINED),
            ),
            ui.buttons,
        )
        assertEquals(text(R.string.lock_aside_locked_on, LockDate(ASIDE_AT, LockDate.Style.DATE)), ui.asideLine)
        assertTrue(ui.goesBack)
        for (button in ui.buttons) vm.onAction(button.action)
        assertEquals(listOf(LockIntent.UseFingerprint, LockIntent.UseMasterPassword), lock.sent)
    }

    // ---- "This is an older master password" ------------------------------------------------------

    @Test
    fun aProvenChangeSaysTypeTheNewOneWithOk() = runTest {
        val lock = FakeLockManager(initial = LockUiState.Locked(LockScreen.OlderPassword(CHANGED_AT, unfinishedChange = false)))
        val vm = vm(lock)
        val ui = olderUi(vm)
        assertEquals(text(R.string.lock_older_proven, LockDate(CHANGED_AT, LockDate.Style.DAY_MONTH)), ui.text)
        assertEquals(listOf(LockButton(text(R.string.common_ok), send(LockIntent.OlderPasswordAnswer(OlderPasswordChoice.OK)), LockTags.OLDER_OK, ButtonStyle.FILLED)), ui.buttons)
        assertNull(ui.asideLine)
        assertFalse(ui.goesBack)
        vm.onAction(ui.buttons.single().action)
        assertEquals(listOf<LockIntent>(LockIntent.OlderPasswordAnswer(OlderPasswordChoice.OK)), lock.sent)
    }

    @Test
    fun anUnfinishedChangeOffersTryAgainAndTheCopyFromBeforeWhichAsksFirst() = runTest {
        val lock = FakeLockManager(initial = LockUiState.Locked(LockScreen.OlderPassword(CHANGED_AT, unfinishedChange = true)))
        val vm = vm(lock)
        val ui = olderUi(vm)
        val day = LockDate(CHANGED_AT, LockDate.Style.DAY_MONTH)
        assertEquals(text(R.string.lock_older_unfinished, day), ui.text)
        assertEquals(listOf(LockTags.OLDER_TRY_AGAIN, LockTags.OLDER_FROM_BEFORE), ui.buttons.map { it.tag })
        assertEquals(text(R.string.common_try_again), ui.buttons[0].label)
        assertEquals(text(R.string.lock_older_open_from_before), ui.buttons[1].label)

        // "Open my passwords from before the change" asks once more; Cancel does nothing.
        vm.onAction(ui.buttons[1].action)
        runCurrent()
        assertEquals(LockConfirm.OpenFromBefore(day), vm.confirm.value)
        assertEquals(
            ConfirmTexts(text(R.string.lock_older_confirm_title, day), text(R.string.lock_older_confirm_body), text(R.string.lock_older_confirm_open)),
            confirmTexts(vm.confirm.value!!),
        )
        assertTrue(lock.sent.isEmpty())
        vm.onAction(LockAction.CancelConfirm)
        runCurrent()
        assertNull(vm.confirm.value)
        assertTrue(lock.sent.isEmpty())

        // Asked again and confirmed: the lock is told.
        vm.onAction(ui.buttons[1].action)
        vm.onAction(LockAction.Confirm)
        runCurrent()
        assertNull(vm.confirm.value)
        vm.onAction(ui.buttons[0].action)
        assertEquals(
            listOf<LockIntent>(
                LockIntent.OlderPasswordAnswer(OlderPasswordChoice.OPEN_FROM_BEFORE),
                LockIntent.OlderPasswordAnswer(OlderPasswordChoice.TRY_AGAIN),
            ),
            lock.sent,
        )
    }

    @Test
    fun theOlderPasswordOfAVaultPutAsideHasItsLine() = runTest {
        val lock = FakeLockManager(initial = LockUiState.Locked(LockScreen.OlderPassword(CHANGED_AT, false, AsideLine.LockedOn(ASIDE_AT))))
        val vm = vm(lock)
        assertEquals(text(R.string.lock_aside_locked_on, LockDate(ASIDE_AT, LockDate.Style.DATE)), olderUi(vm).asideLine)
        assertTrue(olderUi(vm).goesBack)
    }

    // ---- "Your passwords can't be opened" --------------------------------------------------------

    @Test
    fun eachReasonHasItsTextAndOnlyAnIoErrorOffersTryAgain() = runTest {
        val expected = mapOf(
            CurrentVault.Why.IO to R.string.lock_unreadable_io,
            CurrentVault.Why.DAMAGED to R.string.lock_unreadable_damaged,
            CurrentVault.Why.NEWER to R.string.lock_unreadable_newer,
        )
        assertEquals(CurrentVault.Why.entries.toSet(), expected.keys)
        val lock = FakeLockManager(initial = LockUiState.Unreadable(CurrentVault.Why.IO, emptyList()))
        val vm = vm(lock)
        for ((why, id) in expected) {
            lock.state.value = LockUiState.Unreadable(why, emptyList())
            runCurrent()
            val ui = unreadable(vm)
            assertEquals("$why", text(id), ui.body)
            assertNull(ui.asideLine)
            assertFalse(ui.goesBack)
            if (why == CurrentVault.Why.IO) {
                assertEquals(
                    listOf(
                        LockButton(text(R.string.common_try_again), send(LockIntent.UseMasterPassword), LockTags.UNREADABLE_TRY_AGAIN, ButtonStyle.FILLED),
                        LockButton(text(R.string.lock_restore), LockAction.Restore, LockTags.RESTORE, ButtonStyle.OUTLINED),
                    ),
                    ui.buttons,
                )
            } else {
                assertEquals(listOf(LockButton(text(R.string.lock_restore), LockAction.Restore, LockTags.RESTORE, ButtonStyle.FILLED)), ui.buttons)
            }
        }
        assertNull("not the master-password screen", vm.masterPassword.value)
    }

    @Test
    fun tryAgainAndRestoreTellTheLock() = runTest {
        val lock = FakeLockManager(initial = LockUiState.Unreadable(CurrentVault.Why.IO, emptyList()))
        val vm = vm(lock)
        for (button in unreadable(vm).buttons) vm.onAction(button.action)
        assertEquals(listOf(LockIntent.UseMasterPassword, LockIntent.RestoreFromFile), lock.sent)
    }

    @Test
    fun theVaultsPutAsideAreOfferedNewestFirstAndNothingIsDeleted() = runTest {
        val older = aside(VAULT_A, ASIDE_AT)
        val newer = aside(VAULT_B, REPLACED_AT)
        val lock = FakeLockManager(initial = LockUiState.Unreadable(CurrentVault.Why.DAMAGED, listOf(older, newer)))
        val vm = vm(lock)
        val asides = unreadable(vm).buttons.drop(1)
        assertEquals(
            listOf(
                LockButton(text(R.string.lock_open_aside, LockDate(REPLACED_AT, LockDate.Style.DATE)), send(LockIntent.OpenAside(VAULT_B)), LockTags.openAside(VAULT_B), ButtonStyle.OUTLINED),
                LockButton(text(R.string.lock_open_aside, LockDate(ASIDE_AT, LockDate.Style.DATE)), send(LockIntent.OpenAside(VAULT_A)), LockTags.openAside(VAULT_A), ButtonStyle.OUTLINED),
            ),
            asides,
        )
        // The vault a replacement put aside is named "from before" while the lock names it.
        lock.status.value = LockStatus.NONE.copy(replaced = ReplacedNotice(REPLACED_AT, Shortcut.PIN, VAULT_A))
        runCurrent()
        assertEquals(text(R.string.lock_mp_open_from_before, LockDate(REPLACED_AT, LockDate.Style.DATE)), unreadable(vm).buttons.last().label)
        vm.onAction(unreadable(vm).buttons.last().action)
        assertEquals(listOf<LockIntent>(LockIntent.OpenAside(VAULT_A)), lock.sent)
        // No button of this screen can delete: its actions are Send, Restore, nothing else.
        for (button in unreadable(vm).buttons) {
            val action = button.action
            assertTrue("$action", action is LockAction.Send || action == LockAction.Restore)
            assertFalse((action as? LockAction.Send)?.intent == LockIntent.StartEmpty)
        }
    }

    @Test
    fun anUnlockKeepsTheLastScreenAndAnotherScreenReplacesIt() = runTest {
        val lock = FakeLockManager(initial = LockUiState.Locked(fingerprintOnly()))
        val vm = vm(lock)
        assertTrue(vm.screen.value is FingerprintOnlyUi)
        lock.state.value = LockUiState.Starting
        runCurrent()
        assertTrue("nothing flashes", vm.screen.value is FingerprintOnlyUi)
        lock.state.value = LockUiState.Unreadable(CurrentVault.Why.NEWER, emptyList())
        runCurrent()
        assertTrue(vm.screen.value is UnreadableUi)
        lock.state.value = LockUiState.NoVault(emptyList())
        runCurrent()
        assertTrue(vm.screen.value is UnreadableUi)
    }

    @Test
    fun beforeTheLockHasShownAScreenNothingIsShown() = runTest {
        val vm = vm(FakeLockManager(initial = LockUiState.Starting))
        assertNull(vm.screen.value)
        assertNull(vm.masterPassword.value)
        assertNull(vm.sheet.value)
        assertNull(vm.confirm.value)
        assertEquals(UiText.of(R.string.lock_mp_title), LockTexts.masterPasswordTitle(null))
    }

    private fun TestScope.vm(lock: FakeLockManager): LockViewModel = lockViewModel(store, lock, clock)

    private fun fpUi(vm: LockViewModel): FingerprintOnlyUi = vm.screen.value as FingerprintOnlyUi

    private fun olderUi(vm: LockViewModel): OlderPasswordUi = vm.screen.value as OlderPasswordUi

    private fun unreadable(vm: LockViewModel): UnreadableUi = vm.screen.value as UnreadableUi
}
