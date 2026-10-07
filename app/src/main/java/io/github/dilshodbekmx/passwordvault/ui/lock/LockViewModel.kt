package io.github.dilshodbekmx.passwordvault.ui.lock

import androidx.compose.foundation.text.input.TextFieldState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintLine
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
import io.github.dilshodbekmx.passwordvault.lock.PinStatus
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
 * The ViewModel of the lock route (build plan 3.4): the master-password screen of ux.md §5.10 with
 * the states and texts of lock.md §13.2 and §13.3. The lock screens package (4.2) adds the others.
 *
 * - **What it shows** is [masterPassword]: the lock's `LockScreen.MasterPassword` turned into
 *   [UiText]s (resource ids and arguments, never a finished sentence, architecture.md §5.4), so
 *   that the language can change under it. A date is a [LockDate] argument, which the screen
 *   formats in its own language. While the lock shows another screen it is null; once the app is
 *   unlocked it keeps the last screen until the route leaves, so that nothing flashes.
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
 * - **The wait** counts down on [clock]'s elapsed time (`elapsedRealtime()`), once a second.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LockViewModel(
    private val lock: LockManager,
    private val clock: Clock,
    private val submitScope: CoroutineScope,
) : ViewModel() {

    /** The master-password field. Read and emptied by [submit] only. */
    val password = TextFieldState()

    private val local = MutableStateFlow(Local())

    /** The lock's master-password screen as it was last shown; null while it shows another screen. */
    private val shown: Flow<LockScreen.MasterPassword?> = flow {
        var last: LockScreen.MasterPassword? = null
        lock.state.collect { state ->
            last = masterPasswordOf(state, last)
            emit(last)
        }
    }

    /** The master-password screen, or null while the lock shows another screen. */
    val masterPassword: StateFlow<MasterPasswordUi?> = combine(shown, local, lock.status) { screen, local, status ->
        Inputs(screen, local, status)
    }.flatMapLatest { inputs ->
        val screen = inputs.screen ?: return@flatMapLatest flowOf(null)
        val until = waitUntil(screen, inputs.local)
        if (until == null) flowOf(masterPasswordUi(screen, inputs.local, inputs.status, clock.elapsedMs()))
        else countdown(until).map { now -> masterPasswordUi(screen, inputs.local, inputs.status, now) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialUi())

    init {
        // Unlocked by any means, or back to setup: nothing typed stays behind (architecture.md §5.6).
        viewModelScope.launch {
            lock.state.collect { state ->
                if (state !is LockUiState.Locked && state !is LockUiState.Unreadable) password.clearSecret()
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
        val screen = masterPasswordOf(lock.state.value, null) ?: return
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

    /** A button of the screen ([MasterPasswordUi.buttons], [MpLine.button]). */
    fun onAction(action: MpAction) {
        when (action) {
            is MpAction.Send -> lock.send(action.intent)
            MpAction.TryAgain -> {
                val screen = masterPasswordOf(lock.state.value, null)
                local.update { it.copy(error = null, dismissedOn = screen) }
            }
        }
    }

    override fun onCleared() {
        password.clearSecret()
    }

    /** What this screen keeps of its own: a check under way, the last answer of the lock, a dismissed line. */
    private data class Local(
        val submitting: Boolean = false,
        /** The line of the last answer: `MpResult.Wrong` or `NoMemory`; never the proof of a right one. */
        val error: MpError? = null,
        /** The wait of the last answer (`MpResult.Wait`). */
        val waitUntilElapsedMs: Long? = null,
        /** The lock's screen when that answer came: the answer is shown while the lock still shows it. */
        val resultFor: LockScreen.MasterPassword? = null,
        /** "Try again" hid the line of the lock's screen [dismissedOn] until the lock shows another one. */
        val dismissedOn: LockScreen.MasterPassword? = null,
    )

    private class Inputs(val screen: LockScreen.MasterPassword?, val local: Local, val status: LockStatus)

    private fun afterResult(result: MpResult?): Local {
        val screen = masterPasswordOf(lock.state.value, null)
        return when (result) {
            MpResult.Wrong -> Local(error = MpError.Wrong(offerForgot = false, replaced = null), resultFor = screen)
            MpResult.NoMemory -> Local(error = MpError.NoMemory, resultFor = screen)
            is MpResult.Wait -> Local(waitUntilElapsedMs = result.untilElapsedMs, resultFor = screen)
            else -> Local()
        }
    }

    private fun initialUi(): MasterPasswordUi? =
        masterPasswordOf(lock.state.value, null)?.let { masterPasswordUi(it, Local(), lock.status.value, clock.elapsedMs()) }

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

    /** The screen of ux.md §5.10 for [screen] at the elapsed time [now]. */
    private fun masterPasswordUi(screen: LockScreen.MasterPassword, local: Local, status: LockStatus, now: Long): MasterPasswordUi {
        val answer = local.takeIf { it.resultFor == screen }
        val opening = screen.opening || local.submitting
        val error = (screen.error?.takeUnless { local.dismissedOn == screen } ?: answer?.error).takeUnless { opening }
        val waitLeft = waitUntil(screen, local)?.let { it - now }?.takeIf { it > 0 }
        val lines = buildList {
            when (error) {
                is MpError.Wrong -> {
                    add(MpLine(UiText.of(R.string.lock_mp_wrong), alert = true))
                    if (error.offerForgot) add(forgotLine(status))
                    error.replaced?.let { add(replacedLine(it)) }
                }
                MpError.NoMemory -> add(
                    MpLine(
                        UiText.of(R.string.lock_mp_no_memory),
                        MpButton(UiText.of(R.string.common_try_again), MpAction.TryAgain, LockTags.TRY_AGAIN),
                        alert = true,
                    ),
                )
                null -> Unit
            }
            if (waitLeft != null) add(MpLine(UiText.of(R.string.lock_mp_wait)))
        }
        return MasterPasswordUi(
            asideLine = asideLine(screen),
            title = title(screen.reason),
            reason = screen.reason?.let(::reasonText),
            fingerprintLine = screen.fingerprintLine?.let(::fingerprintText),
            fieldEnabled = waitLeft == null,
            fieldError = error is MpError.Wrong,
            pending = screen.pendingReadyInMs?.let { UiText.plural(R.plurals.lock_mp_pending_hours, hoursLeft(it)) },
            lines = lines,
            countdown = waitLeft?.let { UiText.plural(R.plurals.lock_mp_wait_seconds, secondsLeft(it)) },
            opening = opening,
            canSubmit = !opening && waitLeft == null,
            buttons = buttons(screen),
            goesBack = screen.aside != null,
        )
    }

    private companion object {
        const val SECOND_MS = 1_000L
        const val HOUR_MS = 3_600_000L

        /** The lock's master-password screen in [state]; [last] while the app is not locked; null for another screen. */
        fun masterPasswordOf(state: LockUiState, last: LockScreen.MasterPassword?): LockScreen.MasterPassword? = when (state) {
            is LockUiState.Locked -> state.screen as? LockScreen.MasterPassword
            is LockUiState.Unreadable -> null
            else -> last
        }

        /** Whole seconds left, rounded up: 30 at the start of the wait, 1 in its last second. */
        fun secondsLeft(ms: Long): Int = ((ms + SECOND_MS - 1) / SECOND_MS).toInt()

        /** "About N hours", rounded up and at least 1, so that the last minutes never read "about 0 hours". */
        fun hoursLeft(ms: Long): Int = maxOf(1L, (ms + HOUR_MS - 1) / HOUR_MS).toInt()

        fun title(reason: MpReason?): UiText = when (reason) {
            MpReason.WelcomeBack -> UiText.of(R.string.lock_mp_title_welcome_back)
            is MpReason.FromBefore -> UiText.of(R.string.lock_from_before, LockDate(reason.atMs, LockDate.Style.DATE))
            else -> UiText.of(R.string.lock_mp_title)
        }

        /** The line of a vault put aside; none when the title already says it (a vault from before a replacement). */
        fun asideLine(screen: LockScreen.MasterPassword): UiText? = when (val aside = screen.aside) {
            null -> null
            is AsideLine.LockedOn -> UiText.of(R.string.lock_aside_locked_on, LockDate(aside.atMs, LockDate.Style.DATE))
            is AsideLine.FromBefore ->
                if (screen.reason is MpReason.FromBefore) null
                else UiText.of(R.string.lock_from_before, LockDate(aside.atMs, LockDate.Style.DATE))
        }

        fun reasonText(reason: MpReason): UiText = when (reason) {
            MpReason.PracticeDue -> UiText.of(R.string.lock_mp_reason_practice)
            MpReason.WelcomeBack -> UiText.of(R.string.lock_mp_reason_welcome_back)
            is MpReason.FromBefore -> UiText.of(R.string.lock_mp_reason_from_before)
            is MpReason.Pin -> UiText.of(
                when (reason.reason) {
                    PinReason.LIMIT -> R.string.lock_mp_reason_pin_limit
                    PinReason.CANNOT_CHECK -> R.string.lock_mp_reason_pin_cannot_check
                    PinReason.STOPPED_WORKING -> R.string.lock_mp_reason_pin_stopped_working
                    PinReason.STORAGE_FULL -> R.string.lock_mp_reason_pin_storage_full
                    PinReason.NEWER_APP -> R.string.lock_mp_reason_pin_newer_app
                },
            )
            MpReason.MasterChanged -> UiText.of(R.string.lock_mp_reason_master_changed)
            is MpReason.OlderCopy -> UiText.of(R.string.lock_mp_reason_older_copy, LockDate(reason.createdMs, LockDate.Style.DAY_MONTH))
            MpReason.NoSecureHardware -> UiText.of(R.string.lock_mp_reason_no_secure_hardware)
        }

        fun fingerprintText(line: FingerprintLine): UiText = UiText.of(
            when (line) {
                FingerprintLine.TurnedOff -> R.string.lock_fingerprint_turned_off_master
                FingerprintLine.Locked -> R.string.lock_fingerprint_locked_master
                FingerprintLine.DidNotOpen -> R.string.lock_fingerprint_did_not_open_master
                FingerprintLine.NotForApps -> R.string.lock_fingerprint_not_for_apps_master
                FingerprintLine.NoneSaved -> R.string.lock_fingerprint_none_saved_master
                FingerprintLine.UpdateNeeded -> R.string.lock_fingerprint_update_needed_master
                FingerprintLine.CannotCheck -> R.string.lock_fingerprint_cannot_check_master
            },
        )

        /**
         * "Can't remember it? Open the app with your PIN, …" with "I forgot it". It names the
         * fingerprint when fingerprint is the shortcut that still works, because no PIN is set on
         * this phone (lock.md F11 step 2: "PIN" reads "fingerprint" when no PIN exists).
         */
        fun forgotLine(status: LockStatus): MpLine {
            val fingerprintOnly = status.fingerprint == FingerprintStatus.On &&
                status.pin != PinStatus.On && status.pin != PinStatus.CannotCheck
            return MpLine(
                UiText.of(if (fingerprintOnly) R.string.lock_mp_wrong_forgot_fingerprint else R.string.lock_mp_wrong_forgot_pin),
                MpButton(UiText.of(R.string.lock_mp_i_forgot), MpAction.Send(LockIntent.OpenForgotSheet), LockTags.I_FORGOT),
            )
        }

        /**
         * "The master password was replaced on 7 Oct using the PIN. …" with "Open my passwords from
         * before 7 Oct": that vault's own lock screen, or the sheet's list when it was replaced more
         * than once (lock.md §13.3, F16b).
         */
        fun replacedLine(replaced: ReplacedLine): MpLine {
            val date = LockDate(replaced.atMs, LockDate.Style.DAY_MONTH)
            val text = if (replaced.by == Shortcut.FINGERPRINT) R.string.lock_mp_wrong_replaced_fingerprint else R.string.lock_mp_wrong_replaced_pin
            val intent = replaced.oldVaultId?.let { LockIntent.OpenAside(it) } ?: LockIntent.OpenForgotSheet
            return MpLine(UiText.of(text, date), MpButton(UiText.of(R.string.lock_mp_open_from_before, date), MpAction.Send(intent), LockTags.FROM_BEFORE))
        }

        /**
         * The buttons under "Unlock", in this order (lock.md §13.3; ux.md §5.10): "Use PIN" when the
         * PIN can be used ("Not now: use PIN" on a practice day in grace), "Not now: use fingerprint"
         * on a practice day whose shortcut is fingerprint, "I can't remember it" on a practice
         * screen, and "Forgot master password?" always.
         */
        fun buttons(screen: LockScreen.MasterPassword): List<MpButton> = buildList {
            val notNow = screen.practice?.notNow
            if (screen.canUsePin || notNow == Shortcut.PIN) {
                val label = if (notNow == Shortcut.PIN) R.string.lock_mp_not_now_pin else R.string.lock_mp_use_pin
                add(MpButton(UiText.of(label), MpAction.Send(LockIntent.UsePin), LockTags.USE_PIN))
            }
            if (notNow == Shortcut.FINGERPRINT) {
                add(MpButton(UiText.of(R.string.lock_mp_not_now_fingerprint), MpAction.Send(LockIntent.NotNow), LockTags.NOT_NOW))
            }
            if (screen.practice != null) {
                add(MpButton(UiText.of(R.string.lock_mp_cant_remember), MpAction.Send(LockIntent.CantRemember), LockTags.CANT_REMEMBER))
            }
            add(MpButton(UiText.of(R.string.lock_mp_forgot), MpAction.Send(LockIntent.OpenForgotSheet), LockTags.FORGOT))
        }
    }
}

/**
 * What the master-password screen shows (ux.md §5.10; lock.md §13.2, §13.3). Texts only as
 * [UiText]; nothing typed is ever in it.
 *
 * @property asideLine "Passwords locked on 6 Oct 2026" on the screen of a vault put aside.
 * @property title "Type your master password", "Welcome back", or "Your passwords from before …".
 * @property reason the subtitle: why the master password is needed now; null when none applies.
 * @property fingerprintLine fingerprint unlock is on and cannot be used now, with "Use your master
 *   password", under the reason.
 * @property fieldEnabled false while the field waits (lock.md §6.8).
 * @property fieldError the field is marked wrong (the last try was a wrong master password).
 * @property pending under the field: "Your new master password starts working in about N hours."
 * @property lines after the last try, in order: "That's not the right master password. …" with its
 *   additions, or "not enough memory" with "Try again"; then, during a wait, "Wait 30 seconds …".
 * @property countdown the wait's countdown: "Try again in N seconds".
 * @property opening "Opening…" with a spinner in place of "Unlock": the master password is checked.
 * @property canSubmit "Unlock" works once something is typed: nothing is checked and nothing waits.
 * @property buttons the text buttons under "Unlock".
 * @property goesBack the screen of a vault put aside: Back returns to the current vault
 *   (`LockIntent.GoBack`, lock.md F17 step 4) instead of leaving the app.
 */
data class MasterPasswordUi(
    val asideLine: UiText?,
    val title: UiText,
    val reason: UiText?,
    val fingerprintLine: UiText?,
    val fieldEnabled: Boolean,
    val fieldError: Boolean,
    val pending: UiText?,
    val lines: List<MpLine>,
    val countdown: UiText?,
    val opening: Boolean,
    val canSubmit: Boolean,
    val buttons: List<MpButton>,
    val goesBack: Boolean,
)

/**
 * A line after a try (lock.md §13.3 "When"), with the button that belongs to it. [alert]: what went
 * wrong ("That's not the right master password. …", "not enough memory"), drawn in the error colour;
 * the lines that add a way out and the wait are not.
 */
data class MpLine(val text: UiText, val button: MpButton? = null, val alert: Boolean = false)

/** A button of the master-password screen: its label, what it does, and its test tag ([LockTags]). */
data class MpButton(val label: UiText, val action: MpAction, val tag: String)

/** What a button of the master-password screen does. */
sealed interface MpAction {
    /** Tells the lock ([LockManager.send]): "Use PIN", "Forgot master password?" and the others. */
    data class Send(val intent: LockIntent) : MpAction

    /** "Try again" after "not enough memory": the line goes and the field takes the keyboard again. */
    data object TryAgain : MpAction
}

/**
 * A date in a lock text: the ViewModel keeps the time, and the screen formats it in its own
 * language (`Formatters`, i18n.md §4.6), so that a language switch changes it too.
 */
data class LockDate(val ms: Long, val style: Style) {
    enum class Style {
        /** A day and a month: "Oct 5", "5-okt", "5 окт." (in "An older copy from 5 Oct …"). */
        DAY_MONTH,

        /** A day, a month and the year: "Oct 7, 2026" (in "Your passwords from before 7 Oct 2026"). */
        DATE,
    }
}

/**
 * The test tags of the lock screens, exposed as resource ids under the route's root, so that tests
 * and the emulator scripts find each part whatever the language.
 */
object LockTags {
    const val ASIDE = "pv.lock.aside"
    const val TITLE = "pv.lock.title"
    const val REASON = "pv.lock.reason"
    const val FINGERPRINT = "pv.lock.fingerprint"
    const val FIELD = "pv.lock.password"
    const val PENDING = "pv.lock.pending"
    const val COUNTDOWN = "pv.lock.countdown"
    const val UNLOCK = "pv.lock.unlock"
    const val OPENING = "pv.lock.opening"
    const val USE_PIN = "pv.lock.usePin"
    const val NOT_NOW = "pv.lock.notNow"
    const val CANT_REMEMBER = "pv.lock.cantRemember"
    const val FORGOT = "pv.lock.forgot"
    const val I_FORGOT = "pv.lock.iForgot"
    const val FROM_BEFORE = "pv.lock.fromBefore"
    const val TRY_AGAIN = "pv.lock.tryAgain"

    /** The line [index] after a try (`MasterPasswordUi.lines`), from 0. */
    fun line(index: Int): String = "pv.lock.line.$index"
}
