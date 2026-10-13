package io.github.dilshodbekmx.passwordvault.ui.lock

import androidx.compose.foundation.text.input.TextFieldState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dilshodbekmx.passwordvault.BuildConfig
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.CurrentVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpError
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ReplacedLine
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.lock.FingerprintStatus
import io.github.dilshodbekmx.passwordvault.lock.LockIntent
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.lock.LockStatus
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.MpPurpose
import io.github.dilshodbekmx.passwordvault.lock.MpResult
import io.github.dilshodbekmx.passwordvault.lock.OlderPasswordChoice
import io.github.dilshodbekmx.passwordvault.lock.PinStatus
import io.github.dilshodbekmx.passwordvault.lock.ReplacedNotice
import io.github.dilshodbekmx.passwordvault.ui.common.PIN_LENGTH
import io.github.dilshodbekmx.passwordvault.ui.common.clearSecret
import io.github.dilshodbekmx.passwordvault.ui.common.readSecret
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The ViewModel of the lock route (build plan 3.4, then 4.2): every screen of lock.md §13.2 with the
 * texts of §13.3, the "Forgot master password?" sheet of ux.md §5.10 (lock.md F17 step 1), the two
 * questions asked once more ("Start with an empty app?", "Open your passwords as they were before
 * …?"), and the lock's own question "Open the older set instead?" (lock.md F16b step 3, 4.2b).
 *
 * - **What it shows** is [screen]: the lock's `LockScreen`, or "Your passwords can't be opened",
 *   turned into [UiText]s (resource ids and arguments, never a finished sentence, architecture.md
 *   §5.4), so that the language can change under it. A date is a [LockDate] argument, which the screen
 *   formats in its own language. Once the app is unlocked (or back on Welcome) it keeps the last
 *   screen until the route leaves, so that nothing flashes. [masterPassword] is the same while it is
 *   the master-password screen.
 * - **The pad** holds no digit: the lock does (lock.md §5.8). The keys go to the lock as intents
 *   ([onDigit]); the screen gets back only how many digits there are. [PadUi.wrongTries] counts the
 *   wrong PINs answered while this ViewModel lives, so that each one shakes the dots once.
 * - **The field** is [password], a `TextFieldState` held here and never in saved state (lock.md
 *   §5.8, architecture.md §5.6): a rotation keeps it, and nothing of it reaches the saved Bundle.
 *   [submit] takes the characters out and empties the field and its undo history at once; the
 *   characters go to the lock as a `CharArray`, which is zeroed when the check ends. The field is
 *   also emptied when the app leaves the lock (unlocked by any means) and when this ViewModel is
 *   cleared.
 * - **The check** runs in [submitScope], the app's scope, so that leaving the screen cannot cut
 *   the lock's records in half (lock.md §6.8 step 8 decides what a late result does). "Opening…"
 *   shows from the tap until the lock answers, whether or not the lock says so itself.
 * - **The lock's own state wins.** The line after a try comes from `LockScreen.MasterPassword.error`
 *   and the wait from `waitUntilElapsedMs`. If the lock answers with `MpResult.Wrong`, `NoMemory` or
 *   `Wait` and leaves its screen as it was, that answer is shown instead, until the lock publishes
 *   another screen; so a lock that reports a try only by its result still gives the message.
 *   `MpResult.NotFinished` (a ready new master password that could not finish, lock.md F11 steps 15
 *   and 16) is reported by its result alone, as "Couldn't finish now. Nothing was changed. Try again
 *   later."
 * - **The wait** counts down on [clock]'s elapsed time (`elapsedRealtime()`), once a second.
 * - **The sheet** ([sheet]) is this screen's own: `LockUiState` has no place for it. Opening it and
 *   closing it tell the lock (`LockIntent.OpenForgotSheet`, `CloseSheet`), so that the lock knows no
 *   sheet covers the screen before it opens a fingerprint prompt by itself (lock.md §9.2 rule 7); an
 *   option of the sheet closes it first, then sends its own intent. A lock that leaves the locked
 *   state closes it without a word. Its options come from the lock's screen and [LockManager.status]
 *   (F17 step 1, F11 step 3): "Use my PIN" while a PIN exists, else "Use my fingerprint" while
 *   fingerprint works (both `LockIntent.CantRemember`, F11 from the lock screen); "Restore from a
 *   backup file"; one entry for each vault put aside; "Start with an empty app…" only while no PIN
 *   and no fingerprint can be used now; "Try again". On the screen of a vault put aside, the first
 *   and the fourth are about the current vault and are left out.
 * - **The lock's question** (`LockScreen.OpenOlderSet`, lock.md F16b step 3) is a screen of its own,
 *   [OlderSetUi]: the passwords from before a replacement have opened with their master password,
 *   and nothing changes unless "Open my passwords from 7 Oct" is the answer; "Cancel" changes
 *   nothing. Both go straight to the lock (`LockIntent.OlderPasswordAnswer`): the screen is the
 *   question, so neither is asked once more. A sheet or a question that was open when the lock asks
 *   is closed, and the screen itself has no button that opens one, so nothing lies over it.
 * - **A vault put aside that cannot be opened** (`LockScreen.CannotOpen`, lock.md F17 step 4;
 *   build-plan.md §12, decision 6) is [CannotOpenUi]: under that vault's line, "These passwords
 *   can't be opened", "Their files on this phone are damaged." and "Back" (`LockIntent.GoBack`).
 *   As with the question, a sheet or a question that was open is closed when it shows, so that no
 *   "Restore from a backup file" lies over it.
 *
 * @param testBuild `BuildConfig.ALLOW_SOFTWARE_KEYSTORE`: every lock screen shows "TEST BUILD: PIN
 *   keys are not protected" (lock.md §4.5, §13.3; architecture.md §7.2).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LockViewModel(
    private val lock: LockManager,
    private val clock: Clock,
    private val submitScope: CoroutineScope,
    private val testBuild: Boolean = BuildConfig.ALLOW_SOFTWARE_KEYSTORE,
) : ViewModel() {

    /** The master-password field. Read and emptied by [submit] only. */
    val password = TextFieldState()

    private val local = MutableStateFlow(Local())
    private val overlay = MutableStateFlow(Overlay())
    private val wrongTries = MutableStateFlow(0)

    /** The lock's state as it was last shown on this route: a lock screen or "can't be opened"; kept while the app is not locked. */
    private val shown: Flow<LockUiState?> = flow {
        var last: LockUiState? = null
        lock.state.collect { state ->
            last = shownOf(state, last)
            emit(last)
        }
    }

    /** The screen of the route, or null before the lock has shown one. */
    val screen: StateFlow<LockScreenUi?> = combine(shown, local, lock.status, wrongTries) { shown, local, status, wrong ->
        Inputs(shown, local, status, wrong)
    }.flatMapLatest { inputs ->
        val mp = (inputs.shown as? LockUiState.Locked)?.screen as? LockScreen.MasterPassword
        val until = mp?.let { waitUntil(it, inputs.local) }
        if (until == null) flowOf(screenUi(inputs, clock.elapsedMs()))
        else countdown(until).map { now -> screenUi(inputs, now) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialScreen())

    /** The master-password screen, or null while the lock shows another screen. */
    val masterPassword: StateFlow<MasterPasswordUi?> =
        screen.map { it as? MasterPasswordUi }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), screen.value as? MasterPasswordUi)

    /** The "Forgot master password?" sheet while it is open, else null. */
    val sheet: StateFlow<ForgotSheetUi?> = combine(lock.state, lock.status, overlay) { state, status, overlay ->
        if (overlay.sheetOpen) sheetUi(state, status) else null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The question asked once more, or null. */
    val confirm: StateFlow<LockConfirm?> =
        overlay.map { it.confirm }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            var previousPad: LockScreen.Pad? = null
            lock.state.collect { state ->
                // Unlocked by any means, or back to setup: nothing typed stays behind (architecture.md §5.6).
                if (state !is LockUiState.Locked && state !is LockUiState.Unreadable) password.clearSecret()
                // No sheet or question outlives the lock screen it was asked on.
                if (state !is LockUiState.Locked && overlay.value != Overlay()) overlay.value = Overlay()
                // And none lies over the lock's own question (lock.md F16b step 3), or over the screen
                // of a vault put aside that cannot be opened, which offers Back only (decision 6): the
                // app is still locked, so the lock is told that the sheet closed, as on its "Try again".
                val shownScreen = (state as? LockUiState.Locked)?.screen
                if (shownScreen is LockScreen.OpenOlderSet || shownScreen is LockScreen.CannotOpen) {
                    overlay.update { it.copy(confirm = null) }
                    closeSheet()
                }
                val pad = (state as? LockUiState.Locked)?.screen as? LockScreen.Pad
                val before = previousPad
                if (pad != null && before != null && !pad.opening && LockTexts.isWrongPin(pad.status) &&
                    (before.opening || before.digits >= PIN_LENGTH)
                ) {
                    wrongTries.update { it + 1 }
                }
                previousPad = pad
            }
        }
    }

    /**
     * "Unlock", or the keyboard's Done key: when the lock shows the master-password screen, nothing
     * is being checked, the field does not wait and something was typed, the typed characters go to
     * `LockManager.submitMasterPassword` for [MpPurpose.UNLOCK] and the field is emptied at once,
     * with its undo history. Otherwise nothing happens.
     */
    fun submit() {
        val screen = masterPasswordOf(lock.state.value) ?: return
        val now = local.value
        if (now.submitting || screen.opening || waiting(screen, now, clock.elapsedMs()) || password.text.isEmpty()) return
        val chars = password.readSecret()
        password.clearSecret()
        local.value = Local(submitting = true)
        submitScope.launch {
            var result: MpResult? = null
            try {
                result = lock.submitMasterPassword(chars, MpPurpose.UNLOCK)
            } finally {
                chars.fill('\u0000')
                local.value = afterResult(result)
            }
        }
    }

    /** A digit key of the pad, or a digit on a hardware keyboard: the lock appends it (it never stays here). */
    fun onDigit(digit: Int) = lock.send(LockIntent.Digit(digit))

    /** ⌫ on the pad, or Backspace. */
    fun onDelete() = lock.send(LockIntent.DeleteDigit)

    /** A long press on ⌫: every digit typed so far goes. */
    fun onClear() = lock.send(LockIntent.ClearDigits)

    /** A button of a screen, of the sheet or of a question ([LockButton.action], [LockLine.button]). */
    fun onAction(action: LockAction) {
        when (action) {
            is LockAction.Send -> {
                if (action.intent == LockIntent.OpenForgotSheet) return openSheet()
                // A button behind the sheet cannot be reached while it is open: this is an option of the sheet.
                closeSheet()
                lock.send(action.intent)
            }
            LockAction.TryAgain -> {
                val screen = masterPasswordOf(lock.state.value)
                local.update { it.copy(error = null, dismissedOn = screen) }
            }
            LockAction.OpenSheet -> openSheet()
            LockAction.CloseSheet -> closeSheet()
            LockAction.Restore -> {
                closeSheet()
                lock.send(LockIntent.RestoreFromFile)
            }
            LockAction.AskStartEmpty -> overlay.update { it.copy(confirm = LockConfirm.StartEmpty) }
            LockAction.AskOpenFromBefore -> {
                val older = (lock.state.value as? LockUiState.Locked)?.screen as? LockScreen.OlderPassword ?: return
                overlay.update { it.copy(confirm = LockConfirm.OpenFromBefore(LockDate(older.changedAtMs, LockDate.Style.DAY_MONTH))) }
            }
            LockAction.Confirm -> when (overlay.value.confirm) {
                LockConfirm.StartEmpty -> {
                    overlay.update { it.copy(confirm = null) }
                    closeSheet()
                    lock.send(LockIntent.StartEmpty)
                }
                is LockConfirm.OpenFromBefore -> {
                    overlay.update { it.copy(confirm = null) }
                    lock.send(LockIntent.OlderPasswordAnswer(OlderPasswordChoice.OPEN_FROM_BEFORE))
                }
                null -> Unit
            }
            LockAction.CancelConfirm -> overlay.update { it.copy(confirm = null) }
        }
    }

    override fun onCleared() {
        password.clearSecret()
    }

    private fun openSheet() {
        if (overlay.value.sheetOpen) return
        overlay.update { it.copy(sheetOpen = true) }
        lock.send(LockIntent.OpenForgotSheet)
    }

    private fun closeSheet() {
        if (!overlay.value.sheetOpen) return
        overlay.update { it.copy(sheetOpen = false) }
        lock.send(LockIntent.CloseSheet)
    }

    /** What the master-password screen keeps of its own: a check under way, the last answer of the lock, a dismissed line. */
    private data class Local(
        val submitting: Boolean = false,
        /** The line of the last answer: `MpResult.Wrong` or `NoMemory`; never the proof of a right one. */
        val error: MpError? = null,
        /** The wait of the last answer (`MpResult.Wait`). */
        val waitUntilElapsedMs: Long? = null,
        /** The last answer was `MpResult.NotFinished`: the new master password could not finish (lock.md F11 steps 15 and 16). */
        val notFinished: Boolean = false,
        /** The lock's screen when that answer came: the answer is shown while the lock still shows it. */
        val resultFor: LockScreen.MasterPassword? = null,
        /** "Try again" hid the line of the lock's screen [dismissedOn] until the lock shows another one. */
        val dismissedOn: LockScreen.MasterPassword? = null,
    )

    /** What lies over the screen: the sheet, and a question asked once more. */
    private data class Overlay(val sheetOpen: Boolean = false, val confirm: LockConfirm? = null)

    private class Inputs(val shown: LockUiState?, val local: Local, val status: LockStatus, val wrongTries: Int)

    private fun afterResult(result: MpResult?): Local {
        val screen = masterPasswordOf(lock.state.value)
        return when (result) {
            MpResult.Wrong -> Local(error = MpError.Wrong(offerForgot = false, replaced = null), resultFor = screen)
            MpResult.NoMemory -> Local(error = MpError.NoMemory, resultFor = screen)
            is MpResult.Wait -> Local(waitUntilElapsedMs = result.untilElapsedMs, resultFor = screen)
            MpResult.NotFinished -> Local(notFinished = true, resultFor = screen)
            else -> Local()
        }
    }

    private fun initialScreen(): LockScreenUi? =
        screenUi(Inputs(shownOf(lock.state.value, null), Local(), lock.status.value, 0), clock.elapsedMs())

    /** The elapsed time now, then at every change of the shown seconds, until [until]. */
    private fun countdown(until: Long): Flow<Long> = flow {
        while (true) {
            val now = clock.elapsedMs()
            emit(now)
            if (now >= until) break
            val left = until - now
            clock.delayUntilElapsed(until - (secondsLeft(left) - 1) * SECOND_MS)
        }
    }

    private fun waitUntil(screen: LockScreen.MasterPassword, local: Local): Long? =
        screen.waitUntilElapsedMs ?: local.takeIf { it.resultFor == screen }?.waitUntilElapsedMs

    private fun waiting(screen: LockScreen.MasterPassword, local: Local, now: Long): Boolean =
        waitUntil(screen, local)?.let { now < it } == true

    private fun screenUi(inputs: Inputs, now: Long): LockScreenUi? = when (val shown = inputs.shown) {
        is LockUiState.Locked -> when (val screen = shown.screen) {
            is LockScreen.Pad -> padUi(screen, inputs.wrongTries)
            is LockScreen.FingerprintOnly -> fingerprintOnlyUi(screen)
            is LockScreen.MasterPassword -> masterPasswordUi(screen, inputs.local, inputs.status, now)
            is LockScreen.OlderPassword -> olderPasswordUi(screen)
            is LockScreen.OpenOlderSet -> olderSetUi(screen)
            is LockScreen.CannotOpen -> cannotOpenUi(screen)
        }
        is LockUiState.Unreadable -> unreadableUi(shown.why, shown.aside, inputs.status.replaced)
        else -> null
    }

    private fun padUi(screen: LockScreen.Pad, wrong: Int): PadUi = PadUi(
        asideLine = screen.aside?.let(LockTexts::asideLine),
        digits = screen.digits.coerceIn(0, PIN_LENGTH),
        status = screen.status?.let { LockLine(LockTexts.padStatus(it, screen.fingerprintKey), alert = LockTexts.padAlert(it)) },
        fingerprintKey = screen.fingerprintKey,
        opening = screen.opening,
        wrongTries = wrong,
        buttons = listOf(LockButton(UiText.of(R.string.lock_pad_forgot), LockAction.Send(LockIntent.UseMasterPassword), LockTags.FORGOT_PIN)),
        testBuild = testBuild,
        goesBack = screen.aside != null,
    )

    private fun fingerprintOnlyUi(screen: LockScreen.FingerprintOnly): FingerprintOnlyUi = FingerprintOnlyUi(
        asideLine = screen.aside?.let(LockTexts::asideLine),
        status = screen.status?.let { LockLine(LockTexts.fingerprintOnlyStatus(it), alert = LockTexts.padAlert(it)) },
        pinReason = screen.pinReason?.let(LockTexts::fingerprintOnlyPinReason),
        buttons = listOf(
            LockButton(UiText.of(R.string.lock_use_fingerprint), LockAction.Send(LockIntent.UseFingerprint), LockTags.USE_FINGERPRINT, ButtonStyle.FILLED),
            LockButton(UiText.of(R.string.lock_use_master_password), LockAction.Send(LockIntent.UseMasterPassword), LockTags.USE_MASTER_PASSWORD, ButtonStyle.OUTLINED),
        ),
        testBuild = testBuild,
        goesBack = screen.aside != null,
    )

    private fun olderPasswordUi(screen: LockScreen.OlderPassword): OlderPasswordUi = OlderPasswordUi(
        asideLine = screen.aside?.let(LockTexts::asideLine),
        text = LockTexts.olderPassword(screen.changedAtMs, screen.unfinishedChange),
        buttons = if (screen.unfinishedChange) {
            listOf(
                LockButton(UiText.of(R.string.common_try_again), olderAnswer(OlderPasswordChoice.TRY_AGAIN), LockTags.OLDER_TRY_AGAIN, ButtonStyle.FILLED),
                LockButton(UiText.of(R.string.lock_older_open_from_before), LockAction.AskOpenFromBefore, LockTags.OLDER_FROM_BEFORE, ButtonStyle.OUTLINED),
            )
        } else {
            listOf(LockButton(UiText.of(R.string.common_ok), olderAnswer(OlderPasswordChoice.OK), LockTags.OLDER_OK, ButtonStyle.FILLED))
        },
        goesBack = screen.aside != null,
    )

    /**
     * "Open the older set instead?" for [screen] (lock.md F16b step 3). The title names the passwords
     * as their master-password field did, so the line of a vault from before a replacement is not
     * said twice; the date of both sentences is `atMs`, when the master password was replaced.
     */
    private fun olderSetUi(screen: LockScreen.OpenOlderSet): OlderSetUi = OlderSetUi(
        asideLine = screen.aside?.takeUnless { it is AsideLine.FromBefore }?.let(LockTexts::asideLine),
        title = LockTexts.olderSetTitle(screen.atMs),
        text = LockTexts.olderSet(screen.atMs),
        buttons = listOf(
            LockButton(LockTexts.olderSetOpen(screen.atMs), olderAnswer(OlderPasswordChoice.OPEN_OLDER_SET), LockTags.OLDER_SET_OPEN, ButtonStyle.FILLED),
            LockButton(UiText.of(R.string.common_cancel), olderAnswer(OlderPasswordChoice.KEEP_NEWER_SET), LockTags.OLDER_SET_CANCEL, ButtonStyle.OUTLINED),
        ),
        goesBack = screen.aside != null,
    )

    /**
     * The screen of a vault put aside whose copies cannot be opened (lock.md F17 step 4, §13.3;
     * decision 6): its line, the owner's two sentences, and "Back" only.
     */
    private fun cannotOpenUi(screen: LockScreen.CannotOpen): CannotOpenUi = CannotOpenUi(
        asideLine = LockTexts.asideLine(screen.aside),
        title = UiText.of(R.string.lock_cannot_open_title),
        text = UiText.of(R.string.lock_cannot_open_text),
        buttons = listOf(LockButton(UiText.of(R.string.lock_back), LockAction.Send(LockIntent.GoBack), LockTags.BACK, ButtonStyle.FILLED)),
    )

    private fun unreadableUi(why: CurrentVault.Why, aside: List<AsideVault>, replaced: ReplacedNotice?): UnreadableUi = UnreadableUi(
        body = LockTexts.unreadable(why),
        buttons = buildList {
            if (why == CurrentVault.Why.IO) {
                // "Try again" lists the vaults again (LockEngine.refresh); the lock takes it as the gate of 3.2 does.
                add(LockButton(UiText.of(R.string.common_try_again), LockAction.Send(LockIntent.UseMasterPassword), LockTags.UNREADABLE_TRY_AGAIN, ButtonStyle.FILLED))
            }
            val restoreStyle = if (why == CurrentVault.Why.IO) ButtonStyle.OUTLINED else ButtonStyle.FILLED
            add(LockButton(UiText.of(R.string.lock_restore), LockAction.Restore, LockTags.RESTORE, restoreStyle))
            for (vault in newestFirst(aside)) {
                add(LockButton(LockTexts.openAside(vault, replaced), LockAction.Send(LockIntent.OpenAside(vault.vaultId)), LockTags.openAside(vault.vaultId), ButtonStyle.OUTLINED))
            }
        },
    )

    /** The screen of ux.md §5.10 for [screen] at the elapsed time [now]. */
    private fun masterPasswordUi(screen: LockScreen.MasterPassword, local: Local, status: LockStatus, now: Long): MasterPasswordUi {
        val answer = local.takeIf { it.resultFor == screen }
        val opening = screen.opening || local.submitting
        val error = (screen.error?.takeUnless { local.dismissedOn == screen } ?: answer?.error).takeUnless { opening }
        val waitLeft = waitUntil(screen, local)?.let { it - now }?.takeIf { it > 0 }
        val lines = buildList {
            when (error) {
                is MpError.Wrong -> {
                    add(LockLine(UiText.of(R.string.lock_mp_wrong), alert = true))
                    if (error.offerForgot) add(forgotLine(status))
                    error.replaced?.let { add(replacedLine(it)) }
                }
                MpError.NoMemory -> add(
                    LockLine(
                        UiText.of(R.string.lock_mp_no_memory),
                        LockButton(UiText.of(R.string.common_try_again), LockAction.TryAgain, LockTags.TRY_AGAIN),
                        alert = true,
                    ),
                )
                // The swap after "Open my passwords from 7 Oct" failed (decision 17): not the field's fault.
                is MpError.OlderSetNotOpened -> add(LockLine(LockTexts.olderSetNotOpened(error.atMs), alert = true))
                null -> if (answer?.notFinished == true && !opening) add(LockLine(UiText.of(R.string.lock_mp_not_finished), alert = true))
            }
            if (waitLeft != null) add(LockLine(UiText.of(R.string.lock_mp_wait)))
        }
        return MasterPasswordUi(
            asideLine = asideLine(screen),
            title = LockTexts.masterPasswordTitle(screen.reason),
            reason = screen.reason?.let(LockTexts::masterPasswordReason),
            fingerprintLine = screen.fingerprintLine?.let(LockTexts::fingerprintWithMasterPassword),
            fieldEnabled = waitLeft == null,
            fieldError = error is MpError.Wrong,
            pending = screen.pendingReadyInMs?.let { UiText.plural(R.plurals.lock_mp_pending_hours, hoursLeft(it)) },
            lines = lines,
            countdown = waitLeft?.let { UiText.plural(R.plurals.lock_mp_wait_seconds, secondsLeft(it)) },
            opening = opening,
            canSubmit = !opening && waitLeft == null,
            buttons = buttons(screen),
            goesBack = screen.aside != null,
            testBuild = testBuild,
        )
    }

    /**
     * The options of the sheet for the lock screen of [state] (lock.md F17 step 1; ux.md §5.10), in
     * order and only those that apply; null when the app is not locked.
     */
    private fun sheetUi(state: LockUiState, status: LockStatus): ForgotSheetUi? {
        val screen = (state as? LockUiState.Locked)?.screen ?: return null
        val mp = screen as? LockScreen.MasterPassword
        val current = screen.aside == null
        val fingerprintWorks = status.fingerprint == FingerprintStatus.On && mp?.fingerprintLine == null
        val options = buildList {
            // 1. F11 with the proof F11 step 3 accepts: the PIN whenever there is one, else a fingerprint.
            if (current && pinExists(status, mp)) {
                add(SheetOption(UiText.of(R.string.lock_sheet_pin_text), LockButton(UiText.of(R.string.lock_sheet_use_pin), LockAction.Send(LockIntent.CantRemember), LockTags.SHEET_USE_PIN, ButtonStyle.FILLED)))
            } else if (current && fingerprintWorks) {
                add(SheetOption(UiText.of(R.string.lock_sheet_fingerprint_text), LockButton(UiText.of(R.string.lock_sheet_use_fingerprint), LockAction.Send(LockIntent.CantRemember), LockTags.SHEET_USE_FINGERPRINT, ButtonStyle.FILLED)))
            }
            // 2. An older backup.
            add(SheetOption(UiText.of(R.string.lock_sheet_restore_text), LockButton(UiText.of(R.string.lock_restore), LockAction.Restore, LockTags.SHEET_RESTORE, ButtonStyle.OUTLINED)))
            // 3. One entry for each vault put aside, newest first.
            for (vault in newestFirst(status.aside)) {
                add(SheetOption(null, LockButton(LockTexts.openAside(vault, status.replaced), LockAction.Send(LockIntent.OpenAside(vault.vaultId)), LockTags.sheetAside(vault.vaultId), ButtonStyle.OUTLINED)))
            }
            // 4. Only while no PIN and no fingerprint can be used now.
            val shortcutNow = status.pin == PinStatus.On || mp?.canUsePin == true || fingerprintWorks || mp?.practice != null
            if (current && !shortcutNow) {
                add(SheetOption(UiText.of(R.string.lock_sheet_empty_text), LockButton(UiText.of(R.string.lock_sheet_empty), LockAction.AskStartEmpty, LockTags.SHEET_EMPTY)))
            }
            // 5. Back to the field.
            add(SheetOption(null, LockButton(UiText.of(R.string.common_try_again), LockAction.CloseSheet, LockTags.SHEET_TRY_AGAIN)))
        }
        return ForgotSheetUi(options)
    }

    private companion object {
        const val SECOND_MS = 1_000L
        const val HOUR_MS = 3_600_000L

        /** What the route shows for [state]: a lock screen or "can't be opened"; [last] while the app is not locked. */
        fun shownOf(state: LockUiState, last: LockUiState?): LockUiState? = when (state) {
            is LockUiState.Locked, is LockUiState.Unreadable -> state
            else -> last
        }

        /** The lock's master-password screen in [state], or null. */
        fun masterPasswordOf(state: LockUiState): LockScreen.MasterPassword? = (state as? LockUiState.Locked)?.screen as? LockScreen.MasterPassword

        /**
         * A PIN exists on this phone, whether or not it can be checked now (lock.md F11 step 3): then
         * it is the only proof F11 accepts, never a fingerprint in its place.
         */
        fun pinExists(status: LockStatus, mp: LockScreen.MasterPassword?): Boolean =
            mp?.canUsePin == true || when (val pin = status.pin) {
                PinStatus.On, PinStatus.CannotCheck -> true
                is PinStatus.Off -> pin.reason == PinReason.STORAGE_FULL
                PinStatus.NotSet, PinStatus.NoSecureHardware -> false
            }

        fun newestFirst(aside: List<AsideVault>): List<AsideVault> = aside.distinctBy { it.vaultId }.sortedByDescending { it.putAsideAtMs }

        /** Whole seconds left, rounded up: 30 at the start of the wait, 1 in its last second. */
        fun secondsLeft(ms: Long): Int = ((ms + SECOND_MS - 1) / SECOND_MS).toInt()

        /** "About N hours", rounded up and at least 1, so that the last minutes never read "about 0 hours". */
        fun hoursLeft(ms: Long): Int = maxOf(1L, (ms + HOUR_MS - 1) / HOUR_MS).toInt()

        /** The line of a vault put aside; none when the title already says it (a vault from before a replacement). */
        fun asideLine(screen: LockScreen.MasterPassword): UiText? {
            val aside = screen.aside ?: return null
            return if (aside is AsideLine.FromBefore && screen.reason is MpReason.FromBefore) {
                null
            } else {
                LockTexts.asideLine(aside)
            }
        }

        /**
         * "Can't remember it? Open the app with your PIN, …" with "I forgot it". It names the
         * fingerprint when fingerprint is the shortcut that still works, because no PIN is set on
         * this phone (lock.md F11 step 2: "PIN" reads "fingerprint" when no PIN exists).
         */
        fun forgotLine(status: LockStatus): LockLine {
            val fingerprintOnly = status.fingerprint == FingerprintStatus.On &&
                status.pin != PinStatus.On && status.pin != PinStatus.CannotCheck
            return LockLine(
                UiText.of(if (fingerprintOnly) R.string.lock_mp_wrong_forgot_fingerprint else R.string.lock_mp_wrong_forgot_pin),
                LockButton(UiText.of(R.string.lock_mp_i_forgot), LockAction.OpenSheet, LockTags.I_FORGOT),
            )
        }

        /**
         * "The master password was replaced on 7 Oct using the PIN. …" with "Open my passwords from
         * before 7 Oct": that vault's own lock screen, or the sheet's list when it was replaced more
         * than once (lock.md §13.3, F16b).
         */
        fun replacedLine(replaced: ReplacedLine): LockLine {
            val date = LockDate(replaced.atMs, LockDate.Style.DAY_MONTH)
            val text = if (replaced.by == Shortcut.FINGERPRINT) R.string.lock_mp_wrong_replaced_fingerprint else R.string.lock_mp_wrong_replaced_pin
            val action = replaced.oldVaultId?.let { LockAction.Send(LockIntent.OpenAside(it)) } ?: LockAction.OpenSheet
            return LockLine(UiText.of(text, date), LockButton(UiText.of(R.string.lock_mp_open_from_before, date), action, LockTags.FROM_BEFORE))
        }

        /**
         * The buttons under "Unlock", in this order (lock.md §13.3; ux.md §5.10): "Use PIN" when the
         * PIN can be used ("Not now: use PIN" on a practice day in grace), "Not now: use fingerprint"
         * on a practice day whose shortcut is fingerprint, "I can't remember it" on a practice
         * screen, and "Forgot master password?" always.
         */
        fun buttons(screen: LockScreen.MasterPassword): List<LockButton> = buildList {
            val notNow = screen.practice?.notNow
            if (screen.canUsePin || notNow == Shortcut.PIN) {
                val label = if (notNow == Shortcut.PIN) R.string.lock_mp_not_now_pin else R.string.lock_mp_use_pin
                add(LockButton(UiText.of(label), LockAction.Send(LockIntent.UsePin), LockTags.USE_PIN))
            }
            if (notNow == Shortcut.FINGERPRINT) {
                add(LockButton(UiText.of(R.string.lock_mp_not_now_fingerprint), LockAction.Send(LockIntent.NotNow), LockTags.NOT_NOW))
            }
            if (screen.practice != null) {
                add(LockButton(UiText.of(R.string.lock_mp_cant_remember), LockAction.Send(LockIntent.CantRemember), LockTags.CANT_REMEMBER))
            }
            add(LockButton(UiText.of(R.string.lock_mp_forgot), LockAction.OpenSheet, LockTags.FORGOT))
        }
    }
}
