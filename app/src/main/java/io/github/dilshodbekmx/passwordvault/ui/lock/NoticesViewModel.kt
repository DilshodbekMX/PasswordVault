package io.github.dilshodbekmx.passwordvault.ui.lock

import androidx.compose.foundation.text.input.TextFieldState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChoosePinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.NoticeAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.lock.LockIntent
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.MpPurpose
import io.github.dilshodbekmx.passwordvault.lock.MpResult
import io.github.dilshodbekmx.passwordvault.lock.PinStatus
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.ui.common.clearSecret
import io.github.dilshodbekmx.passwordvault.ui.common.readSecret
import io.github.dilshodbekmx.passwordvault.ui.setup.PinSetupStart
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The notices after unlock (lock.md §13.4; ux.md §5.9, §5.24): the first of
 * `LockUiState.Unlocked.notices`, one at a time, in the lock's order.
 *
 * - **A notice** ([notice]) is a dialog with the texts of §13.4 and its answers; each answer goes to
 *   the lock as `LockIntent.NoticeAnswered` ("It wasn't me" as `LockIntent.ItWasntMe`, [answer]), and
 *   some open a screen ([NoticeButton.route]): "Make a backup now" the backup, "Change PIN" Change
 *   PIN, "Choose a new master password" and "Finish now" the new master password, "Choose a new PIN"
 *   Set a PIN, "Turn on" Settings › App lock.
 * - **"Choose a PIN" with the proof of this unlock** has no dialog: the "Choose a new PIN" screen
 *   follows at once (lock.md F1 step 6, F5 step 4, F7 step 3; ux.md §5.9 "After an unlock through
 *   'Forgot PIN?' …"; `Notice.ChoosePin`'s KDoc). [choosePin] names it; [choosePinStep] leaves the
 *   notice's reason for the PIN steps (`PinSetupStart`), opens that screen once and answers the
 *   notice when the PIN screens are left, so that the notices after it wait until then.
 * - **"Type master password"** on the wrong-tries notice opens [prompt], the master password typed
 *   in the open app and checked as practice (`MpPurpose.PRACTICE`), with the lines of §13.3 for a
 *   wrong password, a wait, too little memory and an older password. Its field is [password], held
 *   here, never in saved state, emptied at every submit, when the prompt closes, when the app locks
 *   and when this ViewModel is cleared. A right password closes the prompt; its proof has no next
 *   step and is dropped.
 *
 * It belongs to the activity (the notices are drawn over every unlocked screen), so a rotation keeps
 * it, and it drops its prompt and its typed text whenever the app is not unlocked.
 *
 * @param zone the phone's time zone, for "last time today at 14:32".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoticesViewModel(
    private val lock: LockManager,
    private val clock: Clock,
    private val submitScope: CoroutineScope,
    private val zone: TimeZone = TimeZone.getDefault(),
) : ViewModel() {

    /** The master-password field of [prompt]. */
    val password = TextFieldState()

    private val promptState = MutableStateFlow<PromptState?>(null)

    /** The ids of the "Choose a PIN" notices whose screen was opened and that are not answered yet. */
    private val choosing = mutableSetOf<Long>()

    private val first: Flow<Notice?> = lock.state.map { (it as? LockUiState.Unlocked)?.notices?.firstOrNull() }

    /** The notice to show as a dialog, or null: none, or the first is "Choose a PIN" with a proof ([choosePin]). */
    val notice: StateFlow<NoticeUi?> = first.map { it?.let(::noticeUi) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), firstNow()?.let(::noticeUi))

    /** The id of a first notice "Choose a PIN" that has the proof of this unlock: its screen follows without a dialog. */
    val choosePin: StateFlow<Long?> = first.map { choosePinId(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), choosePinId(firstNow()))

    /** The master-password prompt of "Type master password", or null. */
    val prompt: StateFlow<PromptUi?> = promptState.flatMapLatest { state ->
        val until = (state?.error as? PromptError.Wait)?.untilElapsedMs
        when {
            state == null -> flowOf(null)
            until == null -> flowOf(promptUi(state, clock.elapsedMs()))
            else -> countdown(until).map { now -> promptUi(state, now) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            lock.state.collect { state ->
                if (state !is LockUiState.Unlocked) {
                    password.clearSecret()
                    promptState.value = null
                    choosing.clear()
                }
            }
        }
    }

    /**
     * The answer [answer] to the notice [id]; "Type master password" also opens [prompt]. The screen
     * to open is the button's [NoticeButton.route]. "It wasn't me" is `LockIntent.ItWasntMe`, as on the
     * list's banner: the open app is locked through the session, as "Lock now" locks it (our clip
     * cleared, a draft flushed; architecture.md §5.5), and then the old vault's lock screen shows
     * (lock.md F16b). The lock takes the notice away with the unlocked state.
     */
    fun answer(id: Long, answer: NoticeAnswer) {
        lock.send(if (answer == NoticeAnswer.IT_WASNT_ME) LockIntent.ItWasntMe else LockIntent.NoticeAnswered(id, answer))
        if (answer == NoticeAnswer.TYPE_MASTER_PASSWORD) {
            password.clearSecret()
            promptState.value = PromptState()
        }
    }

    /**
     * One step of "Choose a PIN" with a proof, for the notice [id] while it is the first. The first
     * time, [open] opens the "Choose a new PIN" screen, and just before it the notice's reason is
     * left for the PIN steps (`PinSetupStart.offer`, build-plan.md §12, merge of R3 item 2); if it
     * cannot open, no mark stays and the offer is put off (LATER). Afterwards, once [onPinScreens]
     * is false (the PIN screens were left), the notice is answered: CHOOSE_PIN when a PIN is set now,
     * else LATER. [onPinScreens] null: the screen shown cannot be known, and the notice is answered
     * as soon as the screen is open.
     */
    fun choosePinStep(id: Long, onPinScreens: Boolean?, open: () -> Boolean) {
        if (id !in choosing) {
            choosePinReason(id)?.let(PinSetupStart::offer)
            if (!open()) {
                PinSetupStart.take() // not opened: nothing is left for a later opening
                lock.send(LockIntent.NoticeAnswered(id, NoticeAnswer.LATER))
            } else if (onPinScreens == null) {
                lock.send(LockIntent.NoticeAnswered(id, NoticeAnswer.CHOOSE_PIN))
            } else {
                choosing += id
            }
            return
        }
        if (onPinScreens == false) {
            choosing -= id
            val answer = if (lock.status.value.pin == PinStatus.On) NoticeAnswer.CHOOSE_PIN else NoticeAnswer.LATER
            lock.send(LockIntent.NoticeAnswered(id, answer))
        }
    }

    /** "Check" or the keyboard's Done key in [prompt]: the typed master password goes to the lock as practice; the field is emptied at once. */
    fun submitPrompt() {
        val now = promptState.value ?: return
        val wait = (now.error as? PromptError.Wait)?.untilElapsedMs
        if (now.checking || (wait != null && clock.elapsedMs() < wait) || password.text.isEmpty()) return
        val chars = password.readSecret()
        password.clearSecret()
        promptState.value = PromptState(checking = true)
        submitScope.launch {
            var result: MpResult? = null
            try {
                result = lock.submitMasterPassword(chars, MpPurpose.PRACTICE)
            } finally {
                chars.fill('\u0000')
                val open = promptState.value != null
                (result as? MpResult.Right)?.proof?.drop() // practice has no next step (lock.md §5.8)
                if (open) {
                    promptState.value = when (result) {
                        MpResult.Wrong -> PromptState(error = PromptError.Wrong)
                        is MpResult.Wait -> PromptState(error = PromptError.Wait(result.untilElapsedMs))
                        MpResult.NoMemory -> PromptState(error = PromptError.NoMemory)
                        is MpResult.OlderPassword -> PromptState(error = PromptError.Older(result.changedAtMs))
                        is MpResult.Right, MpResult.TooLate, MpResult.NotFinished, null -> null // NotFinished: the lock screen's answer only
                    }
                }
            }
        }
    }

    /** "Cancel", or the prompt dismissed. */
    fun closePrompt() {
        password.clearSecret()
        promptState.value = null
    }

    override fun onCleared() {
        password.clearSecret()
    }

    private fun firstNow(): Notice? = (lock.state.value as? LockUiState.Unlocked)?.notices?.firstOrNull()

    private fun choosePinId(notice: Notice?): Long? = (notice as? Notice.ChoosePin)?.takeIf { !it.needsMasterPassword }?.id

    /** The reason of the notice "Choose a PIN" [id] in the lock's state now, or null. */
    private fun choosePinReason(id: Long): ChoosePinReason? =
        (lock.state.value as? LockUiState.Unlocked)?.notices?.firstNotNullOfOrNull { (it as? Notice.ChoosePin)?.takeIf { notice -> notice.id == id } }?.reason

    private fun noticeUi(notice: Notice): NoticeUi? = NoticeTexts.of(notice, clock.wallMs(), zone)

    /** The elapsed time now, then at every change of the shown seconds, until [until]. */
    private fun countdown(until: Long): Flow<Long> = flow {
        while (true) {
            val now = clock.elapsedMs()
            emit(now)
            if (now >= until) break
            val left = until - now
            clock.delayUntilElapsed(until - (secondsLeft(left) - 1) * 1_000L)
        }
    }

    private fun promptUi(state: PromptState, now: Long): PromptUi {
        val waitLeft = (state.error as? PromptError.Wait)?.untilElapsedMs?.let { it - now }?.takeIf { it > 0 }
        val lines = when (val error = state.error) {
            PromptError.Wrong -> listOf(LockLine(UiText.of(R.string.lock_mp_wrong), alert = true))
            PromptError.NoMemory -> listOf(LockLine(UiText.of(R.string.lock_prompt_no_memory), alert = true))
            is PromptError.Older -> listOf(LockLine(UiText.of(R.string.lock_prompt_older, LockDate(error.changedAtMs, LockDate.Style.DATE)), alert = true))
            is PromptError.Wait -> if (waitLeft != null) listOf(LockLine(UiText.of(R.string.lock_mp_wait))) else emptyList()
            null -> emptyList()
        }
        return PromptUi(
            lines = lines,
            countdown = waitLeft?.let { UiText.plural(R.plurals.lock_mp_wait_seconds, secondsLeft(it)) },
            checking = state.checking,
            fieldEnabled = !state.checking && waitLeft == null,
        )
    }

    private data class PromptState(val checking: Boolean = false, val error: PromptError? = null)

    private sealed interface PromptError {
        data object Wrong : PromptError

        data object NoMemory : PromptError

        data class Wait(val untilElapsedMs: Long) : PromptError

        data class Older(val changedAtMs: Long) : PromptError
    }

    private companion object {
        fun secondsLeft(ms: Long): Int = ((ms + 999L) / 1_000L).toInt()
    }
}

/**
 * A notice as a dialog: its title (only the questions have one), its lines, and its buttons, each
 * an answer the notice offers. [dismiss] is the answer that changes nothing (OK or LATER), given when
 * the dialog is closed with Back.
 */
data class NoticeUi(
    val id: Long,
    val title: UiText?,
    val lines: List<UiText>,
    val buttons: List<NoticeButton>,
    val dismiss: NoticeAnswer,
)

/** A button of a notice: its label, its answer, the screen it opens after answering, and its test tag ([NoticeTags.answer]). */
data class NoticeButton(val label: UiText, val answer: NoticeAnswer, val route: Route? = null) {
    val tag: String get() = NoticeTags.answer(answer)
}

/**
 * The master-password prompt of "Type master password": the lines after the last try, the wait's
 * countdown, "checking" while the lock runs Argon2id, and whether the field takes text now.
 */
data class PromptUi(val lines: List<LockLine>, val countdown: UiText?, val checking: Boolean, val fieldEnabled: Boolean)

/** The test tags of the notices and of the prompt, as resource ids of their own windows. */
object NoticeTags {
    const val NOTICE = "pv.notice"
    const val TITLE = "pv.notice.title"
    const val PROMPT = "pv.prompt"
    const val PROMPT_FIELD = "pv.prompt.password"
    const val PROMPT_CHECK = "pv.prompt.check"
    const val PROMPT_CANCEL = "pv.prompt.cancel"
    const val PROMPT_COUNTDOWN = "pv.prompt.countdown"
    const val PROMPT_CHECKING = "pv.prompt.checking"

    /** The line [index] of a notice, from 0. */
    fun line(index: Int): String = "pv.notice.line.$index"

    /** The button that gives [answer]. */
    fun answer(answer: NoticeAnswer): String = "pv.answer.${answer.name}"

    /** The line [index] of the prompt, from 0. */
    fun promptLine(index: Int): String = "pv.prompt.line.$index"
}

/**
 * The texts of lock.md §13.4 and ux.md §5.9, §5.24 for each [Notice], and its answers in the order
 * they are drawn, from the start of the row to its end. Each notice offers only answers its KDoc
 * lists. "Choose a PIN" with the proof of this unlock has no dialog: null.
 */
internal object NoticeTexts {
    private const val DAY_MS = 86_400_000L
    private const val HOUR_MS = 3_600_000L

    fun of(notice: Notice, nowWallMs: Long, zone: TimeZone): NoticeUi? = when (notice) {
        is Notice.OlderCopy -> ui(
            notice,
            lines = listOf(UiText.of(R.string.lock_notice_older_copy, LockDate(notice.openedCreatedMs, LockDate.Style.DATE_TIME))),
            buttons = listOf(NoticeButton(UiText.of(R.string.lock_notice_back_up_now), NoticeAnswer.BACK_UP_NOW, Route.Backup), ok()),
        )
        is Notice.OlderThanLastTime -> ui(
            notice,
            lines = listOf(UiText.of(R.string.lock_notice_older_than_last, LockDate(notice.lastOpenedAtMs, LockDate.Style.DATE_TIME))),
            buttons = listOf(ok()),
        )
        is Notice.Pending -> {
            val hours = hoursLeft(notice.remainingMs)
            val id = if (notice.by == Shortcut.FINGERPRINT) R.plurals.lock_notice_pending_fingerprint else R.plurals.lock_notice_pending_pin
            ui(
                notice,
                lines = listOf(UiText.plural(id, hours, hours, LockDate(notice.requestedAtMs, LockDate.Style.DATE_TIME))),
                buttons = listOf(cancelIt(), ok()),
            )
        }
        is Notice.PendingReady -> ui(
            notice,
            lines = listOf(UiText.of(R.string.lock_notice_pending_ready)),
            buttons = listOf(
                cancelIt(),
                NoticeButton(UiText.of(R.string.common_later), NoticeAnswer.LATER),
                NoticeButton(UiText.of(R.string.lock_notice_finish_now), NoticeAnswer.FINISH_NOW, Route.NewMasterPassword),
            ),
        )
        is Notice.PendingCancelled -> ui(notice, lines = listOf(UiText.of(R.string.lock_notice_pending_cancelled)), buttons = listOf(ok()))
        is Notice.Replaced -> {
            val id = if (notice.by == Shortcut.FINGERPRINT) R.string.lock_notice_replaced_fingerprint else R.string.lock_notice_replaced_pin
            ui(
                notice,
                lines = listOf(UiText.of(id, LockDate(notice.atMs, LockDate.Style.DAY_MONTH))),
                buttons = listOf(NoticeButton(UiText.of(R.string.lock_notice_it_wasnt_me), NoticeAnswer.IT_WASNT_ME), ok()),
            )
        }
        is Notice.WrongTries -> wrongTries(notice, nowWallMs, zone)
        is Notice.ChoosePin ->
            if (!notice.needsMasterPassword) {
                null
            } else {
                ui(
                    notice,
                    lines = listOf(UiText.of(R.string.lock_notice_choose_pin_master)),
                    buttons = listOf(
                        NoticeButton(UiText.of(R.string.common_later), NoticeAnswer.LATER),
                        NoticeButton(UiText.of(R.string.lock_notice_choose_pin), NoticeAnswer.CHOOSE_PIN, Route.ChangePin),
                    ),
                )
            }
        is Notice.FingerprintAgain -> ui(
            notice,
            title = UiText.of(R.string.lock_notice_fingerprint_again),
            lines = emptyList(),
            buttons = listOf(
                NoticeButton(UiText.of(R.string.common_later), NoticeAnswer.LATER),
                NoticeButton(UiText.of(R.string.lock_notice_turn_on), NoticeAnswer.TURN_ON_FINGERPRINT, Route.AppLock),
            ),
        )
        is Notice.StorageUnreadable -> ui(
            notice,
            lines = listOf(UiText.of(R.string.lock_notice_storage_unreadable)),
            buttons = listOf(
                NoticeButton(UiText.of(R.string.common_later), NoticeAnswer.LATER),
                NoticeButton(UiText.of(R.string.lock_notice_set_up_again), NoticeAnswer.SET_UP_AGAIN),
                NoticeButton(UiText.of(R.string.lock_notice_back_up_now), NoticeAnswer.BACK_UP_NOW, Route.Backup),
            ),
        )
        is Notice.BackupNow -> ui(
            notice,
            title = UiText.of(R.string.lock_notice_backup_title),
            lines = listOf(UiText.of(R.string.lock_notice_backup_body)),
            buttons = listOf(
                NoticeButton(UiText.of(R.string.common_later), NoticeAnswer.LATER),
                NoticeButton(UiText.of(R.string.lock_notice_back_up), NoticeAnswer.BACK_UP_NOW, Route.Backup),
            ),
        )
    }

    /**
     * lock.md §13.4 item 4: the wrong PINs (with the time of the last one: "today at 14:32", or the
     * date and time), an interrupted entry, how many more before the PIN turns off with "Type master
     * password", "Can't remember it? Choose a new one." with "Choose a new master password", and the
     * wrong master passwords. "Change PIN" only when wrong PINs are reported.
     */
    private fun wrongTries(notice: Notice.WrongTries, nowWallMs: Long, zone: TimeZone): NoticeUi {
        val lines = buildList {
            if (notice.wrongPins > 0) {
                val n = notice.wrongPins
                val last = notice.lastWrongPinAtMs
                add(
                    when {
                        last == null -> UiText.plural(R.plurals.lock_notice_wrong_pins, n, n)
                        sameDay(last, nowWallMs, zone) -> UiText.plural(R.plurals.lock_notice_wrong_pins_today, n, n, LockDate(last, LockDate.Style.TIME))
                        else -> UiText.plural(R.plurals.lock_notice_wrong_pins_on, n, n, LockDate(last, LockDate.Style.DATE_TIME))
                    },
                )
            }
            if (notice.interrupted) add(UiText.of(R.string.lock_notice_interrupted))
            notice.pinsBeforeOff?.let { add(UiText.plural(R.plurals.lock_notice_pins_before_off, it, it)) }
            if (notice.offerNewMasterPassword) add(UiText.of(R.string.lock_notice_new_master_text))
            if (notice.wrongMasterPasswords > 0) {
                val n = notice.wrongMasterPasswords
                add(UiText.plural(R.plurals.lock_notice_wrong_master, n, n))
            }
        }
        val buttons = buildList {
            if (notice.wrongPins > 0 || notice.interrupted) add(NoticeButton(UiText.of(R.string.lock_notice_change_pin), NoticeAnswer.CHANGE_PIN, Route.ChangePin))
            if (notice.pinsBeforeOff != null) add(NoticeButton(UiText.of(R.string.lock_notice_type_master_password), NoticeAnswer.TYPE_MASTER_PASSWORD))
            if (notice.offerNewMasterPassword) add(NoticeButton(UiText.of(R.string.lock_notice_new_master), NoticeAnswer.NEW_MASTER_PASSWORD, Route.NewMasterPassword))
            add(ok())
        }
        return ui(notice, lines = lines, buttons = buttons)
    }

    private fun ui(notice: Notice, lines: List<UiText>, buttons: List<NoticeButton>, title: UiText? = null): NoticeUi {
        val dismiss = if (buttons.any { it.answer == NoticeAnswer.OK }) NoticeAnswer.OK else NoticeAnswer.LATER
        return NoticeUi(notice.id, title, lines, buttons, dismiss)
    }

    private fun ok() = NoticeButton(UiText.of(R.string.common_ok), NoticeAnswer.OK)

    private fun cancelIt() = NoticeButton(UiText.of(R.string.lock_notice_cancel_it), NoticeAnswer.CANCEL_IT)

    /** "About N hours", rounded up and at least 1, as on the master-password screen. */
    private fun hoursLeft(ms: Long): Int = maxOf(1L, (ms + HOUR_MS - 1) / HOUR_MS).toInt()

    private fun sameDay(a: Long, b: Long, zone: TimeZone): Boolean =
        Math.floorDiv(a + zone.getOffset(a), DAY_MS) == Math.floorDiv(b + zone.getOffset(b), DAY_MS)
}
