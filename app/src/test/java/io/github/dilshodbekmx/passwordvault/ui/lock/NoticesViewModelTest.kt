package io.github.dilshodbekmx.passwordvault.ui.lock

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChoosePinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.NoticeAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.lock.LockIntent
import io.github.dilshodbekmx.passwordvault.lock.LockStatus
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.MpPurpose
import io.github.dilshodbekmx.passwordvault.lock.MpResult
import io.github.dilshodbekmx.passwordvault.lock.PinStatus
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.ui.lock.LockKit.text
import io.github.dilshodbekmx.passwordvault.ui.setup.PinSetupStart
import java.util.TimeZone
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
 * Build plan 4.2, [JVM]: the notices after unlock (lock.md §13.4; ux.md §5.9, §5.24) over
 * `FakeLockManager`: each notice's texts, answers and the screens they open; one at a time, in the
 * lock's order; "Choose a PIN" with the proof of the unlock going straight to its screen and answered
 * when the PIN screens are left; and "Type master password", checked as practice, with every answer
 * of the lock, the field emptied at each submit and when the app locks.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalFoundationApi::class)
class NoticesViewModelTest {
    private val store = ViewModelStore()
    private val clock = FakeClock(wallMs = NOW, elapsedMs = LockKit.START, autoAdvance = false)

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    // ---- Each notice ----------------------------------------------------------------------------

    @Test
    fun anOlderCopyNamesItsTimeAndOffersABackup() = runTest {
        val ui = notice(Notice.OlderCopy(1, COPY_AT))
        assertNull(ui.title)
        assertEquals(listOf(text(R.string.lock_notice_older_copy, LockDate(COPY_AT, LockDate.Style.DATE_TIME))), ui.lines)
        assertEquals(
            listOf(NoticeButton(text(R.string.lock_notice_back_up_now), NoticeAnswer.BACK_UP_NOW, Route.Backup), ok),
            ui.buttons,
        )
        assertEquals(NoticeAnswer.OK, ui.dismiss)
    }

    @Test
    fun olderThanLastTimeHasOnlyOk() = runTest {
        val ui = notice(Notice.OlderThanLastTime(1, COPY_AT))
        assertEquals(listOf(text(R.string.lock_notice_older_than_last, LockDate(COPY_AT, LockDate.Style.DATE_TIME))), ui.lines)
        assertEquals(listOf(ok), ui.buttons)
    }

    @Test
    fun aPendingMasterPasswordSaysWhoWhenAndAboutHowManyHours() = runTest {
        val requested = LockDate(REQUESTED_AT, LockDate.Style.DATE_TIME)
        val pin = notice(Notice.Pending(1, REQUESTED_AT, Shortcut.PIN, remainingMs = 20 * HOUR - 1))
        assertEquals(listOf(UiText.plural(R.plurals.lock_notice_pending_pin, 20, 20, requested)), pin.lines)
        assertEquals(listOf(cancelIt, ok), pin.buttons)
        val fingerprint = notice(Notice.Pending(2, REQUESTED_AT, Shortcut.FINGERPRINT, remainingMs = 10 * MINUTE))
        assertEquals("the last minutes read about 1 hour", listOf(UiText.plural(R.plurals.lock_notice_pending_fingerprint, 1, 1, requested)), fingerprint.lines)
    }

    @Test
    fun aReadyMasterPasswordOffersFinishNowLaterAndCancelIt() = runTest {
        val ui = notice(Notice.PendingReady(1, REQUESTED_AT, Shortcut.PIN))
        assertEquals(listOf(text(R.string.lock_notice_pending_ready)), ui.lines)
        assertEquals(
            listOf(cancelIt, later, NoticeButton(text(R.string.lock_notice_finish_now), NoticeAnswer.FINISH_NOW, Route.NewMasterPassword)),
            ui.buttons,
        )
        assertEquals(NoticeAnswer.LATER, ui.dismiss)
        assertEquals(listOf(ok), notice(Notice.PendingCancelled(2)).buttons)
        assertEquals(listOf(text(R.string.lock_notice_pending_cancelled)), notice(Notice.PendingCancelled(2)).lines)
    }

    @Test
    fun aReplacementSaysByWhatAndOffersItWasntMe() = runTest {
        val day = LockDate(REPLACED_AT, LockDate.Style.DAY_MONTH)
        val pin = notice(Notice.Replaced(1, REPLACED_AT, Shortcut.PIN, LockKit.VAULT_A))
        assertEquals(listOf(text(R.string.lock_notice_replaced_pin, day)), pin.lines)
        assertEquals(listOf(NoticeButton(text(R.string.lock_notice_it_wasnt_me), NoticeAnswer.IT_WASNT_ME), ok), pin.buttons)
        val fingerprint = notice(Notice.Replaced(2, REPLACED_AT, Shortcut.FINGERPRINT, LockKit.VAULT_A))
        assertEquals(listOf(text(R.string.lock_notice_replaced_fingerprint, day)), fingerprint.lines)
    }

    @Test
    fun wrongPinsAreReportedWithTheTimeOfTheLastOne() = runTest {
        val today = notice(wrongTries(pins = 3, lastAt = NOW - 2 * HOUR))
        assertEquals(listOf(UiText.plural(R.plurals.lock_notice_wrong_pins_today, 3, 3, LockDate(NOW - 2 * HOUR, LockDate.Style.TIME))), today.lines)
        assertEquals(listOf(changePin, ok), today.buttons)

        val earlier = notice(wrongTries(pins = 1, lastAt = NOW - 30 * HOUR))
        assertEquals(listOf(UiText.plural(R.plurals.lock_notice_wrong_pins_on, 1, 1, LockDate(NOW - 30 * HOUR, LockDate.Style.DATE_TIME))), earlier.lines)

        val unknown = notice(wrongTries(pins = 2, lastAt = null))
        assertEquals(listOf(UiText.plural(R.plurals.lock_notice_wrong_pins, 2, 2)), unknown.lines)
    }

    @Test
    fun wrongTriesAddTheirLinesAndAnswers() = runTest {
        val all = notice(
            Notice.WrongTries(1, wrongPins = 6, lastWrongPinAtMs = NOW - HOUR, interrupted = true, pinsBeforeOff = 4, offerNewMasterPassword = true, wrongMasterPasswords = 2),
        )
        assertEquals(
            listOf(
                UiText.plural(R.plurals.lock_notice_wrong_pins_today, 6, 6, LockDate(NOW - HOUR, LockDate.Style.TIME)),
                text(R.string.lock_notice_interrupted),
                UiText.plural(R.plurals.lock_notice_pins_before_off, 4, 4),
                text(R.string.lock_notice_new_master_text),
                UiText.plural(R.plurals.lock_notice_wrong_master, 2, 2),
            ),
            all.lines,
        )
        assertEquals(
            listOf(
                changePin,
                NoticeButton(text(R.string.lock_notice_type_master_password), NoticeAnswer.TYPE_MASTER_PASSWORD),
                NoticeButton(text(R.string.lock_notice_new_master), NoticeAnswer.NEW_MASTER_PASSWORD, Route.NewMasterPassword),
                ok,
            ),
            all.buttons,
        )
        // Only wrong master passwords: nothing about the PIN, no "Change PIN".
        val master = notice(Notice.WrongTries(2, 0, null, false, null, false, wrongMasterPasswords = 1))
        assertEquals(listOf(UiText.plural(R.plurals.lock_notice_wrong_master, 1, 1)), master.lines)
        assertEquals(listOf(ok), master.buttons)
    }

    @Test
    fun choosePinWithoutAProofAsksForTheMasterPasswordThroughSetAPin() = runTest {
        val ui = notice(Notice.ChoosePin(1, ChoosePinReason.TURNED_OFF, needsMasterPassword = true))
        assertEquals(listOf(text(R.string.lock_notice_choose_pin_master)), ui.lines)
        assertEquals(listOf(later, NoticeButton(text(R.string.lock_notice_choose_pin), NoticeAnswer.CHOOSE_PIN, Route.ChangePin)), ui.buttons)
        assertEquals(NoticeAnswer.LATER, ui.dismiss)
    }

    @Test
    fun theShortcutAndBackupNoticesHaveTheirAnswers() = runTest {
        val fingerprint = notice(Notice.FingerprintAgain(1))
        assertEquals(text(R.string.lock_notice_fingerprint_again), fingerprint.title)
        assertTrue(fingerprint.lines.isEmpty())
        assertEquals(listOf(later, NoticeButton(text(R.string.lock_notice_turn_on), NoticeAnswer.TURN_ON_FINGERPRINT, Route.AppLock)), fingerprint.buttons)

        val storage = notice(Notice.StorageUnreadable(2))
        assertEquals(listOf(text(R.string.lock_notice_storage_unreadable)), storage.lines)
        assertEquals(
            listOf(
                later,
                NoticeButton(text(R.string.lock_notice_set_up_again), NoticeAnswer.SET_UP_AGAIN),
                NoticeButton(text(R.string.lock_notice_back_up_now), NoticeAnswer.BACK_UP_NOW, Route.Backup),
            ),
            storage.buttons,
        )

        val backup = notice(Notice.BackupNow(3))
        assertEquals(text(R.string.lock_notice_backup_title), backup.title)
        assertEquals(listOf(text(R.string.lock_notice_backup_body)), backup.lines)
        assertEquals(listOf(later, NoticeButton(text(R.string.lock_notice_back_up), NoticeAnswer.BACK_UP_NOW, Route.Backup)), backup.buttons)
    }

    @Test
    fun everyNoticeOffersOnlyTheAnswersItsKdocLists() = runTest {
        val offered = mapOf(
            Notice.OlderCopy(1, COPY_AT) to setOf(NoticeAnswer.BACK_UP_NOW, NoticeAnswer.OK),
            Notice.OlderThanLastTime(2, COPY_AT) to setOf(NoticeAnswer.OK),
            Notice.Pending(3, REQUESTED_AT, Shortcut.PIN, HOUR) to setOf(NoticeAnswer.CANCEL_IT, NoticeAnswer.OK),
            Notice.PendingReady(4, REQUESTED_AT, Shortcut.PIN) to setOf(NoticeAnswer.FINISH_NOW, NoticeAnswer.LATER, NoticeAnswer.CANCEL_IT),
            Notice.PendingCancelled(5) to setOf(NoticeAnswer.OK),
            Notice.Replaced(6, REPLACED_AT, Shortcut.PIN, LockKit.VAULT_A) to setOf(NoticeAnswer.OK, NoticeAnswer.IT_WASNT_ME),
            Notice.WrongTries(7, 6, NOW, true, 4, true, 2) to
                setOf(NoticeAnswer.CHANGE_PIN, NoticeAnswer.OK, NoticeAnswer.TYPE_MASTER_PASSWORD, NoticeAnswer.NEW_MASTER_PASSWORD),
            Notice.ChoosePin(8, ChoosePinReason.STOPPED_WORKING, true) to setOf(NoticeAnswer.CHOOSE_PIN, NoticeAnswer.LATER),
            Notice.FingerprintAgain(9) to setOf(NoticeAnswer.TURN_ON_FINGERPRINT, NoticeAnswer.LATER),
            Notice.StorageUnreadable(10) to setOf(NoticeAnswer.SET_UP_AGAIN, NoticeAnswer.BACK_UP_NOW, NoticeAnswer.LATER),
            Notice.BackupNow(11) to setOf(NoticeAnswer.BACK_UP_NOW, NoticeAnswer.LATER),
        )
        for ((n, answers) in offered) {
            // Every case of Notice is here (a new case does not compile).
            when (n) {
                is Notice.OlderCopy, is Notice.OlderThanLastTime, is Notice.Pending, is Notice.PendingReady, is Notice.PendingCancelled,
                is Notice.Replaced, is Notice.WrongTries, is Notice.ChoosePin, is Notice.FingerprintAgain, is Notice.StorageUnreadable,
                is Notice.BackupNow,
                -> Unit
            }
            val ui = NoticeTexts.of(n, NOW, UTC)!!
            assertEquals("$n", answers, ui.buttons.map { it.answer }.toSet())
            assertTrue("$n", ui.dismiss in answers)
            assertEquals("$n: one tag per answer", ui.buttons.size, ui.buttons.map { it.tag }.toSet().size)
        }
    }

    // ---- One at a time --------------------------------------------------------------------------

    @Test
    fun noticesComeOneAtATimeAndEachAnswerGoesToTheLock() = runTest {
        val lock = FakeLockManager(initial = unlocked(Notice.OlderCopy(1, COPY_AT), Notice.BackupNow(2)))
        val vm = vm(lock)
        assertEquals(1L, vm.notice.value!!.id)
        vm.answer(1, NoticeAnswer.OK)
        runCurrent()
        assertEquals(2L, vm.notice.value!!.id)
        vm.answer(2, NoticeAnswer.LATER)
        runCurrent()
        assertNull(vm.notice.value)
        assertEquals(
            listOf<LockIntent>(LockIntent.NoticeAnswered(1, NoticeAnswer.OK), LockIntent.NoticeAnswered(2, NoticeAnswer.LATER)),
            lock.sent,
        )
        // While locked there is no notice.
        lock.state.value = LockUiState.Locked(LockKit.pad())
        runCurrent()
        assertNull(vm.notice.value)
        assertNull(vm.choosePin.value)
    }

    @Test
    fun itWasntMeOnTheNoticeGoesThroughTheSession_asTheBannersDoes() = runTest {
        // build-plan.md §12, w5-carry step 3 item 1: the notice's answer as NoticeAnswered(IT_WASNT_ME) made the engine
        // lock from inside, without Session.lockNow, so our clip stayed and a draft was not flushed.
        val lock = FakeLockManager(initial = unlocked(Notice.Replaced(4, REPLACED_AT, Shortcut.PIN, LockKit.VAULT_A), Notice.BackupNow(5)))
        val vm = vm(lock)
        assertEquals(4L, vm.notice.value!!.id)
        vm.answer(4, NoticeAnswer.IT_WASNT_ME)
        runCurrent()
        assertEquals(listOf<LockIntent>(LockIntent.ItWasntMe), lock.sent)
        // Its other answer is an answer to the notice as any other.
        lock.sent.clear()
        vm.answer(4, NoticeAnswer.OK)
        assertEquals(listOf<LockIntent>(LockIntent.NoticeAnswered(4, NoticeAnswer.OK)), lock.sent)
    }

    @Test
    fun choosePinWithAProofOpensTheScreenAndIsAnsweredWhenThePinScreensAreLeft() = runTest {
        val lock = FakeLockManager(initial = unlocked(Notice.ChoosePin(5, ChoosePinReason.NONE_HERE, needsMasterPassword = false), Notice.BackupNow(6)))
        val vm = vm(lock)
        assertNull("no dialog", vm.notice.value)
        assertEquals(5L, vm.choosePin.value)
        var opened = 0
        vm.choosePinStep(5, onPinScreens = false) { opened++; true }
        assertEquals(1, opened)
        assertTrue("not answered while the PIN screens follow", lock.sent.isEmpty())
        // On the PIN screens: nothing; the backup offer waits behind it.
        vm.choosePinStep(5, onPinScreens = true) { opened++; true }
        runCurrent()
        assertEquals(1, opened)
        assertTrue(lock.sent.isEmpty())
        assertNull(vm.notice.value)
        // Back on the list with a PIN set: answered, and the next notice comes.
        lock.status.value = LockStatus.NONE.copy(pin = PinStatus.On)
        vm.choosePinStep(5, onPinScreens = false) { opened++; true }
        runCurrent()
        assertEquals(1, opened)
        assertEquals(listOf<LockIntent>(LockIntent.NoticeAnswered(5, NoticeAnswer.CHOOSE_PIN)), lock.sent)
        assertEquals(6L, vm.notice.value!!.id)
    }

    @Test
    fun choosePinLeavesItsReasonForThePinSteps_justBeforeTheirScreenOpens() = runTest {
        PinSetupStart.take() // nothing left by another test
        val lock = FakeLockManager(initial = unlocked(Notice.ChoosePin(5, ChoosePinReason.SOMEONE_MAY_KNOW, false)))
        val vm = vm(lock)
        var taken: ChoosePinReason? = null
        vm.choosePinStep(5, onPinScreens = false) { taken = PinSetupStart.take(); true }
        assertEquals("the first step of the PIN screens takes the notice's reason (build-plan §12, merge of R3 item 2)", ChoosePinReason.SOMEONE_MAY_KNOW, taken)

        // A screen that cannot open leaves no mark for a later opening.
        lock.state.value = unlocked(Notice.ChoosePin(7, ChoosePinReason.FORGOTTEN, false))
        runCurrent()
        vm.choosePinStep(7, onPinScreens = false) { false }
        assertNull(PinSetupStart.take())
    }

    @Test
    fun choosePinNotTakenUpIsLaterAndOneThatCannotOpenIsPutOff() = runTest {
        val lock = FakeLockManager(initial = unlocked(Notice.ChoosePin(5, ChoosePinReason.FORGOTTEN, false)))
        val vm = vm(lock)
        vm.choosePinStep(5, onPinScreens = false) { true }
        vm.choosePinStep(5, onPinScreens = false) { true }
        assertEquals(listOf<LockIntent>(LockIntent.NoticeAnswered(5, NoticeAnswer.LATER)), lock.sent)

        lock.sent.clear()
        lock.state.value = unlocked(Notice.ChoosePin(7, ChoosePinReason.SOMEONE_MAY_KNOW, false))
        runCurrent()
        vm.choosePinStep(7, onPinScreens = false) { false }
        assertEquals(listOf<LockIntent>(LockIntent.NoticeAnswered(7, NoticeAnswer.LATER)), lock.sent)

        // When the screen shown cannot be known, it is answered once the screen is open.
        lock.sent.clear()
        lock.state.value = unlocked(Notice.ChoosePin(8, ChoosePinReason.NONE_HERE, false))
        runCurrent()
        vm.choosePinStep(8, onPinScreens = null) { true }
        assertEquals(listOf<LockIntent>(LockIntent.NoticeAnswered(8, NoticeAnswer.CHOOSE_PIN)), lock.sent)
    }

    // ---- "Type master password" -----------------------------------------------------------------

    @Test
    fun typeMasterPasswordOpensThePromptWhichChecksAsPractice() = runTest {
        val lock = FakeLockManager(initial = unlocked(Notice.WrongTries(1, 6, NOW, false, 4, false, 0)))
        val vm = vm(lock)
        assertNull(vm.prompt.value)
        vm.answer(1, NoticeAnswer.TYPE_MASTER_PASSWORD)
        runCurrent()
        assertEquals(PromptUi(emptyList(), null, checking = false, fieldEnabled = true), vm.prompt.value)
        assertEquals(listOf<LockIntent>(LockIntent.NoticeAnswered(1, NoticeAnswer.TYPE_MASTER_PASSWORD)), lock.sent)

        // An empty field sends nothing.
        vm.submitPrompt()
        runCurrent()
        assertTrue(lock.purposes.isEmpty())

        // A right one: checked as practice, the field emptied, the prompt closed.
        vm.password.setTextAndPlaceCursorAtEnd(TYPED)
        vm.submitPrompt()
        assertEmpty(vm.password)
        runCurrent()
        assertEquals(listOf(MpPurpose.PRACTICE), lock.purposes)
        assertNull(vm.prompt.value)
    }

    @Test
    fun thePromptShowsEveryAnswerOfTheLock() = runTest {
        val proof = MasterProof.forFakes(ByteArray(4) { 1 }, LockKit.VAULT_A)
        val lock = FakeLockManager(initial = unlocked())
        lock.mpResults += listOf(MpResult.Wrong, MpResult.NoMemory, MpResult.OlderPassword(CHANGED_AT, false), MpResult.Wait(LockKit.START + 30_000), MpResult.Right(proof))
        val vm = vm(lock)
        vm.answer(1, NoticeAnswer.TYPE_MASTER_PASSWORD)
        fun tryOnce(): PromptUi {
            vm.password.setTextAndPlaceCursorAtEnd(TYPED)
            vm.submitPrompt()
            runCurrent()
            return vm.prompt.value!!
        }
        assertEquals(listOf(LockLine(text(R.string.lock_mp_wrong), alert = true)), tryOnce().lines)
        assertEquals(listOf(LockLine(text(R.string.lock_prompt_no_memory), alert = true)), tryOnce().lines)
        assertEquals(listOf(LockLine(text(R.string.lock_prompt_older, LockDate(CHANGED_AT, LockDate.Style.DATE)), alert = true)), tryOnce().lines)
        val waiting = tryOnce()
        assertEquals(listOf(LockLine(text(R.string.lock_mp_wait))), waiting.lines)
        assertEquals(UiText.plural(R.plurals.lock_mp_wait_seconds, 30), waiting.countdown)
        assertFalse(waiting.fieldEnabled)

        // Nothing is sent while the field waits; the countdown runs on the elapsed clock.
        vm.password.setTextAndPlaceCursorAtEnd(TYPED)
        vm.submitPrompt()
        runCurrent()
        assertEquals(4, lock.purposes.size)
        clock.advance(1_000)
        runCurrent()
        assertEquals(UiText.plural(R.plurals.lock_mp_wait_seconds, 29), vm.prompt.value!!.countdown)
        clock.advance(29_000)
        runCurrent()
        assertTrue(vm.prompt.value!!.fieldEnabled)
        assertTrue(vm.prompt.value!!.lines.isEmpty())

        // Right: closed, and its proof, which has no next step, dropped.
        vm.submitPrompt()
        runCurrent()
        assertEquals(5, lock.purposes.size)
        assertNull(vm.prompt.value)
        var dropped = false
        try {
            proof.useBytes { }
        } catch (_: IllegalStateException) {
            dropped = true
        }
        assertTrue("the proof of practice is dropped", dropped)
    }

    @Test
    fun aLateAnswerOrALockClosesThePromptAndEmptiesItsField() = runTest {
        val lock = FakeLockManager(initial = unlocked())
        lock.mpResults += MpResult.TooLate
        val vm = vm(lock)
        vm.answer(1, NoticeAnswer.TYPE_MASTER_PASSWORD)
        vm.password.setTextAndPlaceCursorAtEnd(TYPED)
        vm.submitPrompt()
        runCurrent()
        assertNull(vm.prompt.value)

        vm.answer(1, NoticeAnswer.TYPE_MASTER_PASSWORD)
        runCurrent()
        vm.password.setTextAndPlaceCursorAtEnd(TYPED)
        lock.state.value = LockUiState.Locked(LockKit.pad())
        runCurrent()
        assertNull(vm.prompt.value)
        assertEmpty(vm.password)

        // Cancel empties it too.
        lock.state.value = unlocked()
        runCurrent()
        vm.answer(1, NoticeAnswer.TYPE_MASTER_PASSWORD)
        vm.password.setTextAndPlaceCursorAtEnd(TYPED)
        vm.closePrompt()
        runCurrent()
        assertNull(vm.prompt.value)
        assertEmpty(vm.password)
    }

    @Test
    fun nothingIsSentWhileThePromptChecks() = runTest {
        val lock = FakeLockManager(initial = unlocked())
        val gate = kotlinx.coroutines.CompletableDeferred<MpResult>()
        val slow = object : io.github.dilshodbekmx.passwordvault.lock.LockManager by lock {
            var calls = 0

            override suspend fun submitMasterPassword(chars: CharArray, purpose: MpPurpose): MpResult {
                calls++
                return gate.await()
            }
        }
        val vm = vm(slow)
        vm.answer(1, NoticeAnswer.TYPE_MASTER_PASSWORD)
        vm.password.setTextAndPlaceCursorAtEnd(TYPED)
        vm.submitPrompt()
        runCurrent()
        assertTrue(vm.prompt.value!!.checking)
        assertFalse(vm.prompt.value!!.fieldEnabled)
        vm.password.setTextAndPlaceCursorAtEnd(TYPED)
        vm.submitPrompt()
        runCurrent()
        assertEquals(1, slow.calls)
        assertEquals(TYPED, vm.password.text.toString())
        gate.complete(MpResult.Wrong)
        runCurrent()
        assertFalse(vm.prompt.value!!.checking)
        assertNotNull(vm.prompt.value)
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun TestScope.vm(lock: io.github.dilshodbekmx.passwordvault.lock.LockManager): NoticesViewModel {
        val factory = viewModelFactory { initializer { NoticesViewModel(lock, clock, backgroundScope, UTC) } }
        val vm = ViewModelProvider(store, factory)[NoticesViewModel::class.java]
        backgroundScope.launch { vm.notice.collect {} }
        backgroundScope.launch { vm.choosePin.collect {} }
        backgroundScope.launch { vm.prompt.collect {} }
        runCurrent()
        return vm
    }

    /** The dialog of [n] as the ViewModel shows it while [n] is the first notice. */
    private fun TestScope.notice(n: Notice): NoticeUi {
        store.clear()
        return vm(FakeLockManager(initial = unlocked(n))).notice.value!!
    }

    private fun unlocked(vararg notices: Notice) = LockUiState.Unlocked(notices.toList(), null)

    private fun wrongTries(pins: Int, lastAt: Long?) = Notice.WrongTries(1, pins, lastAt, false, null, false, 0)

    private fun assertEmpty(field: TextFieldState) {
        assertEquals("", field.text.toString())
        assertFalse("nothing to undo", field.undoState.canUndo)
    }

    private companion object {
        val UTC: TimeZone = TimeZone.getTimeZone("UTC")
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE

        /** 2026-10-08 14:00 UTC. */
        const val NOW = 1_791_468_000_000L
        const val COPY_AT = 1_791_158_400_000L
        const val CHANGED_AT = 1_791_158_400_000L
        const val REQUESTED_AT = 1_791_400_000_000L
        const val REPLACED_AT = 1_791_331_200_000L
        const val TYPED = "correct horse battery"

        val ok = NoticeButton(UiText.of(R.string.common_ok), NoticeAnswer.OK)
        val later = NoticeButton(UiText.of(R.string.common_later), NoticeAnswer.LATER)
        val cancelIt = NoticeButton(UiText.of(R.string.lock_notice_cancel_it), NoticeAnswer.CANCEL_IT)
        val changePin = NoticeButton(UiText.of(R.string.lock_notice_change_pin), NoticeAnswer.CHANGE_PIN, Route.ChangePin)
    }
}
