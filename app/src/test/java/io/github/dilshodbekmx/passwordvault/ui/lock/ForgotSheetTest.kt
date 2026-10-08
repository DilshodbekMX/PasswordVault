package io.github.dilshodbekmx.passwordvault.ui.lock

import androidx.lifecycle.ViewModelStore
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpError
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Practice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ReplacedLine
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.lock.FingerprintStatus
import io.github.dilshodbekmx.passwordvault.lock.LockIntent
import io.github.dilshodbekmx.passwordvault.lock.LockStatus
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.PinStatus
import io.github.dilshodbekmx.passwordvault.lock.ReplacedNotice
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.ui.lock.LockKit.ASIDE_AT
import io.github.dilshodbekmx.passwordvault.ui.lock.LockKit.REPLACED_AT
import io.github.dilshodbekmx.passwordvault.ui.lock.LockKit.VAULT_A
import io.github.dilshodbekmx.passwordvault.ui.lock.LockKit.VAULT_B
import io.github.dilshodbekmx.passwordvault.ui.lock.LockKit.aside
import io.github.dilshodbekmx.passwordvault.ui.lock.LockKit.lockViewModel
import io.github.dilshodbekmx.passwordvault.ui.lock.LockKit.masterPassword
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Build plan 4.2, [JVM]: the "Forgot master password?" sheet (ux.md §5.10; lock.md F17 step 1) over
 * `FakeLockManager`: its five entries, each only where it applies and in that order, for the PIN and
 * fingerprint states of `LockStatus` and the lock screen it opens from; what each tells the lock (the
 * sheet closes first, `LockIntent.CloseSheet`); "Start with an empty app?" asked once more; nothing
 * that deletes, and no "Delete" (U §12 check 16 is the device half, at wave 5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForgotSheetTest {
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
    fun forgotMasterPasswordOpensTheSheetAndTellsTheLockOnce() = runTest {
        val lock = FakeLockManager(initial = LockUiState.Locked(masterPassword()))
        val vm = vm(lock)
        assertNull(vm.sheet.value)
        val forgot = (vm.screen.value as MasterPasswordUi).buttons.last()
        assertEquals(LockButton(text(R.string.lock_mp_forgot), LockAction.OpenSheet, LockTags.FORGOT), forgot)
        vm.onAction(forgot.action)
        vm.onAction(forgot.action)
        runCurrent()
        assertNotNull(vm.sheet.value)
        assertEquals(listOf<LockIntent>(LockIntent.OpenForgotSheet), lock.sent)
    }

    @Test
    fun whileThePinWorksTheSheetOffersUseMyPinRestoreAndTryAgain() = runTest {
        val lock = FakeLockManager(initial = LockUiState.Locked(masterPassword(canUsePin = true)))
        lock.status.value = LockStatus.NONE.copy(pin = PinStatus.On, fingerprint = FingerprintStatus.On)
        val vm = open(lock)
        assertEquals(listOf(LockTags.SHEET_USE_PIN, LockTags.SHEET_RESTORE, LockTags.SHEET_TRY_AGAIN), tags(vm))
        val options = vm.sheet.value!!.options
        assertEquals(
            SheetOption(text(R.string.lock_sheet_pin_text), LockButton(text(R.string.lock_sheet_use_pin), send(LockIntent.CantRemember), LockTags.SHEET_USE_PIN, ButtonStyle.FILLED)),
            options[0],
        )
        assertEquals(
            SheetOption(text(R.string.lock_sheet_restore_text), LockButton(text(R.string.lock_restore), LockAction.Restore, LockTags.SHEET_RESTORE, ButtonStyle.OUTLINED)),
            options[1],
        )
        assertEquals(SheetOption(null, LockButton(text(R.string.common_try_again), LockAction.CloseSheet, LockTags.SHEET_TRY_AGAIN)), options[2])
    }

    @Test
    fun aPinThatCannotBeCheckedIsStillThePinNeverAFingerprint() = runTest {
        // F11 step 3: whenever a PIN exists it is the proof; a fingerprint never stands in for it.
        for (pin in listOf(PinStatus.CannotCheck, PinStatus.Off(PinReason.STORAGE_FULL))) {
            store.clear()
            val lock = FakeLockManager(initial = LockUiState.Locked(masterPassword(reason = MpReason.Pin(PinReason.CANNOT_CHECK))))
            lock.status.value = LockStatus.NONE.copy(pin = pin, fingerprint = FingerprintStatus.Off)
            val vm = open(lock)
            assertEquals("$pin", listOf(LockTags.SHEET_USE_PIN, LockTags.SHEET_RESTORE, LockTags.SHEET_EMPTY, LockTags.SHEET_TRY_AGAIN), tags(vm))

            store.clear()
            lock.status.value = LockStatus.NONE.copy(pin = pin, fingerprint = FingerprintStatus.On)
            val withFingerprint = open(lock)
            assertEquals("$pin, fingerprint on", listOf(LockTags.SHEET_USE_PIN, LockTags.SHEET_RESTORE, LockTags.SHEET_TRY_AGAIN), tags(withFingerprint))
        }
    }

    @Test
    fun withoutAPinAWorkingFingerprintIsOffered() = runTest {
        for (pin in listOf(PinStatus.NotSet, PinStatus.Off(PinReason.LIMIT), PinStatus.Off(PinReason.STOPPED_WORKING), PinStatus.Off(PinReason.NEWER_APP))) {
            store.clear()
            val lock = FakeLockManager(initial = LockUiState.Locked(masterPassword()))
            lock.status.value = LockStatus.NONE.copy(pin = pin, fingerprint = FingerprintStatus.On)
            val vm = open(lock)
            assertEquals("$pin", listOf(LockTags.SHEET_USE_FINGERPRINT, LockTags.SHEET_RESTORE, LockTags.SHEET_TRY_AGAIN), tags(vm))
            assertEquals(
                SheetOption(
                    text(R.string.lock_sheet_fingerprint_text),
                    LockButton(text(R.string.lock_sheet_use_fingerprint), send(LockIntent.CantRemember), LockTags.SHEET_USE_FINGERPRINT, ButtonStyle.FILLED),
                ),
                vm.sheet.value!!.options.first(),
            )
        }
    }

    @Test
    fun withNoShortcutUsableNowStartWithAnEmptyAppIsOffered() = runTest {
        val cases = listOf(
            LockStatus.NONE,
            LockStatus.NONE.copy(pin = PinStatus.Off(PinReason.LIMIT), fingerprint = FingerprintStatus.Off),
            LockStatus.NONE.copy(pin = PinStatus.NoSecureHardware, fingerprint = FingerprintStatus.NoSecureHardware),
            LockStatus.NONE.copy(pin = PinStatus.NotSet, fingerprint = FingerprintStatus.Unavailable(11)),
            LockStatus.NONE.copy(pin = PinStatus.NotSet, fingerprint = FingerprintStatus.TurnOnAgain),
        )
        for (status in cases) {
            store.clear()
            val lock = FakeLockManager(initial = LockUiState.Locked(masterPassword()))
            lock.status.value = status
            val vm = open(lock)
            assertEquals("$status", listOf(LockTags.SHEET_RESTORE, LockTags.SHEET_EMPTY, LockTags.SHEET_TRY_AGAIN), tags(vm))
            assertEquals(
                SheetOption(text(R.string.lock_sheet_empty_text), LockButton(text(R.string.lock_sheet_empty), LockAction.AskStartEmpty, LockTags.SHEET_EMPTY)),
                vm.sheet.value!!.options[1],
            )
        }
        // Fingerprint unlock is on but cannot be used now: the same, though the status says On.
        store.clear()
        val lock = FakeLockManager(initial = LockUiState.Locked(masterPassword(fingerprintLine = FingerprintLine.NoneSaved)))
        lock.status.value = LockStatus.NONE.copy(fingerprint = FingerprintStatus.On)
        assertEquals(listOf(LockTags.SHEET_RESTORE, LockTags.SHEET_EMPTY, LockTags.SHEET_TRY_AGAIN), tags(open(lock)))
    }

    @Test
    fun onAPracticeScreenAShortcutWorksSoNothingStartsEmpty() = runTest {
        val lock = FakeLockManager(initial = LockUiState.Locked(masterPassword(reason = MpReason.PracticeDue, practice = Practice(null))))
        lock.status.value = LockStatus.NONE.copy(pin = PinStatus.On)
        assertEquals(listOf(LockTags.SHEET_USE_PIN, LockTags.SHEET_RESTORE, LockTags.SHEET_TRY_AGAIN), tags(open(lock)))
    }

    @Test
    fun eachVaultPutAsideHasItsEntryNewestFirst() = runTest {
        val lock = FakeLockManager(initial = LockUiState.Locked(masterPassword()))
        lock.status.value = LockStatus.NONE.copy(
            aside = listOf(aside(VAULT_A, ASIDE_AT), aside(VAULT_B, REPLACED_AT)),
            replaced = ReplacedNotice(REPLACED_AT, Shortcut.PIN, VAULT_B),
        )
        val vm = open(lock)
        assertEquals(
            listOf(LockTags.SHEET_RESTORE, LockTags.sheetAside(VAULT_B), LockTags.sheetAside(VAULT_A), LockTags.SHEET_EMPTY, LockTags.SHEET_TRY_AGAIN),
            tags(vm),
        )
        val options = vm.sheet.value!!.options
        assertEquals(
            SheetOption(null, LockButton(text(R.string.lock_mp_open_from_before, LockDate(REPLACED_AT, LockDate.Style.DATE)), send(LockIntent.OpenAside(VAULT_B)), LockTags.sheetAside(VAULT_B), ButtonStyle.OUTLINED)),
            options[1],
        )
        assertEquals(
            SheetOption(null, LockButton(text(R.string.lock_open_aside, LockDate(ASIDE_AT, LockDate.Style.DATE)), send(LockIntent.OpenAside(VAULT_A)), LockTags.sheetAside(VAULT_A), ButtonStyle.OUTLINED)),
            options[2],
        )
    }

    @Test
    fun onTheScreenOfAVaultPutAsideOnlyItsOwnWaysOutAreOffered() = runTest {
        val lock = FakeLockManager(initial = LockUiState.Locked(masterPassword(aside = AsideLine.LockedOn(ASIDE_AT))))
        lock.status.value = LockStatus.NONE.copy(pin = PinStatus.On, aside = listOf(aside(VAULT_A, ASIDE_AT)))
        assertEquals(listOf(LockTags.SHEET_RESTORE, LockTags.sheetAside(VAULT_A), LockTags.SHEET_TRY_AGAIN), tags(open(lock)))
    }

    @Test
    fun eachOptionClosesTheSheetFirstThenTellsTheLock() = runTest {
        val cases = listOf(
            LockTags.SHEET_USE_PIN to listOf<LockIntent>(LockIntent.CloseSheet, LockIntent.CantRemember),
            LockTags.SHEET_RESTORE to listOf(LockIntent.CloseSheet, LockIntent.RestoreFromFile),
            LockTags.sheetAside(VAULT_A) to listOf(LockIntent.CloseSheet, LockIntent.OpenAside(VAULT_A)),
            LockTags.SHEET_TRY_AGAIN to listOf<LockIntent>(LockIntent.CloseSheet),
        )
        for ((tag, intents) in cases) {
            store.clear()
            val lock = FakeLockManager(initial = LockUiState.Locked(masterPassword(canUsePin = true)))
            lock.status.value = LockStatus.NONE.copy(pin = PinStatus.On, aside = listOf(aside(VAULT_A, ASIDE_AT)))
            val vm = open(lock)
            vm.onAction(vm.sheet.value!!.options.single { it.button.tag == tag }.button.action)
            runCurrent()
            assertNull("$tag closes the sheet", vm.sheet.value)
            assertEquals(tag, listOf<LockIntent>(LockIntent.OpenForgotSheet) + intents, lock.sent)
        }
        // Dismissing the sheet is its "Try again".
        store.clear()
        val lock = FakeLockManager(initial = LockUiState.Locked(masterPassword()))
        val vm = open(lock)
        vm.onAction(LockAction.CloseSheet)
        vm.onAction(LockAction.CloseSheet)
        runCurrent()
        assertNull(vm.sheet.value)
        assertEquals(listOf<LockIntent>(LockIntent.OpenForgotSheet, LockIntent.CloseSheet), lock.sent)
    }

    @Test
    fun startWithAnEmptyAppIsAskedOnceMore() = runTest {
        val lock = FakeLockManager(initial = LockUiState.Locked(masterPassword()))
        val vm = open(lock)
        vm.onAction(LockAction.AskStartEmpty)
        runCurrent()
        assertEquals(LockConfirm.StartEmpty, vm.confirm.value)
        assertNotNull("the sheet stays under the question", vm.sheet.value)
        assertEquals(
            ConfirmTexts(text(R.string.lock_empty_title), text(R.string.lock_empty_body), text(R.string.lock_empty_confirm)),
            confirmTexts(LockConfirm.StartEmpty),
        )
        vm.onAction(LockAction.CancelConfirm)
        runCurrent()
        assertNull(vm.confirm.value)
        assertNotNull(vm.sheet.value)
        assertEquals(listOf<LockIntent>(LockIntent.OpenForgotSheet), lock.sent)

        vm.onAction(LockAction.AskStartEmpty)
        vm.onAction(LockAction.Confirm)
        runCurrent()
        assertNull(vm.confirm.value)
        assertNull(vm.sheet.value)
        assertEquals(listOf(LockIntent.OpenForgotSheet, LockIntent.CloseSheet, LockIntent.StartEmpty), lock.sent)
    }

    @Test
    fun iForgotItAndAReplacementMadeMoreThanOnceOpenTheSheet() = runTest {
        val lock = FakeLockManager(
            initial = LockUiState.Locked(masterPassword(canUsePin = true, error = MpError.Wrong(offerForgot = true, replaced = ReplacedLine(REPLACED_AT, Shortcut.PIN, null)))),
        )
        lock.status.value = LockStatus.NONE.copy(pin = PinStatus.On)
        val vm = vm(lock)
        val lines = (vm.screen.value as MasterPasswordUi).lines
        assertEquals(LockAction.OpenSheet, lines[1].button!!.action)
        assertEquals(LockAction.OpenSheet, lines[2].button!!.action)
        vm.onAction(lines[1].button!!.action)
        runCurrent()
        assertNotNull(vm.sheet.value)
        vm.onAction(LockAction.CloseSheet)
        vm.onAction(lines[2].button!!.action)
        runCurrent()
        assertNotNull(vm.sheet.value)
        assertEquals(listOf(LockIntent.OpenForgotSheet, LockIntent.CloseSheet, LockIntent.OpenForgotSheet), lock.sent)
    }

    @Test
    fun anUnlockOrWelcomeClosesTheSheetAndTheQuestionWithoutAWord() = runTest {
        val lock = FakeLockManager(initial = LockUiState.Locked(masterPassword()))
        val vm = open(lock)
        vm.onAction(LockAction.AskStartEmpty)
        lock.state.value = LockUiState.Unlocked(emptyList(), null)
        runCurrent()
        assertNull(vm.sheet.value)
        assertNull(vm.confirm.value)
        assertEquals(listOf<LockIntent>(LockIntent.OpenForgotSheet), lock.sent)
        // Locked again: the sheet does not come back by itself.
        lock.state.value = LockUiState.Locked(masterPassword())
        runCurrent()
        assertNull(vm.sheet.value)
    }

    @Test
    fun nothingOnTheSheetDeletesOrSaysDelete() = runTest {
        // A PIN that cannot be checked now and no fingerprint: all five entries at once.
        val lock = FakeLockManager(initial = LockUiState.Locked(masterPassword()))
        lock.status.value = LockStatus.NONE.copy(pin = PinStatus.CannotCheck, aside = listOf(aside(VAULT_A, ASIDE_AT)))
        val vm = open(lock)
        val options = vm.sheet.value!!.options
        assertEquals(5, options.size)
        for (option in options) {
            assertFalse(option.button.label.toString(), option.button.label == text(R.string.common_delete))
            val action = option.button.action
            assertTrue(
                "$action",
                action == LockAction.Restore || action == LockAction.AskStartEmpty || action == LockAction.CloseSheet ||
                    (action as? LockAction.Send)?.intent.let { it == LockIntent.CantRemember || it is LockIntent.OpenAside },
            )
        }
    }

    private fun TestScope.vm(lock: FakeLockManager): LockViewModel = lockViewModel(store, lock, clock)

    /** The ViewModel with its sheet open. */
    private fun TestScope.open(lock: FakeLockManager): LockViewModel {
        val vm = vm(lock)
        vm.onAction(LockAction.OpenSheet)
        runCurrent()
        return vm
    }

    private fun tags(vm: LockViewModel): List<String> = vm.sheet.value!!.options.map { it.button.tag }
}
