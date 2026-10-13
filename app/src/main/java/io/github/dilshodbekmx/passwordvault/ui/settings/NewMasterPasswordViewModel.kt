package io.github.dilshodbekmx.passwordvault.ui.settings

import androidx.compose.foundation.text.input.TextFieldState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.strength.MasterPasswordCheck
import io.github.dilshodbekmx.passwordvault.core.strength.MasterPasswordRules
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.core.util.RandomSource
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.lock.FingerprintStatus
import io.github.dilshodbekmx.passwordvault.lock.FinishResult
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.lock.LockStatus
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.PinStatus
import io.github.dilshodbekmx.passwordvault.lock.ProofResult
import io.github.dilshodbekmx.passwordvault.lock.RequestResult
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.ui.common.clearSecret
import io.github.dilshodbekmx.passwordvault.ui.common.readSecret
import io.github.dilshodbekmx.passwordvault.ui.setup.PinDigits
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupOffer
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * `Route.NewMasterPassword` (ux.md §5.24; lock.md F11, in its order): a new master password chosen
 * while the old one is forgotten, and "Finish now" once its 24 hours are credited (build-plan.md §12,
 * decision 14). Opened by "I don't remember it" on Change master password, "I forgot it" on the
 * practice banner, "Choose a new master password" on the wrong-tries notice, "Finish now" on the
 * banner and on the notice, and after the lock-screen route (`LockIntent.CantRemember`, then the PIN
 * or fingerprint). It shows the finishing step while `LockStatus.pending?.ready` is true, and the
 * asking flow otherwise ([NewStage]).
 *
 * **Asking** (F11 steps 2 to 6): the explanation ("PIN" reads "fingerprint" when no PIN exists),
 * "Proof now" (`LockManager.proveForNewMasterPassword`: the proof the lock-screen route made stands
 * when there is one; else the PIN typed now on this screen's pad while a PIN exists, a counted check;
 * the fingerprint only where there is none), the new master password chosen, written down and typed
 * again ([choice]; the screen kept on for the last two, lock.md §10.1), the request
 * (`requestNewMasterPassword`), and the wait.
 *
 * **The proof goes with its flow** (lock.md F11 step 3, §5.8; build-plan.md §12 item 13): whenever
 * this flow ends without `RequestResult.Requested` ("Cancel" on the explanation, Back from choosing,
 * the route closed any other way, the ViewModel cleared), it tells the lock
 * (`leaveNewMasterPassword`), so that no request can be made later in the session without the PIN
 * or fingerprint again. The lock drops the proof by itself at a lock and when the app stops being
 * visible; should that happen while a password is chosen, the request answers `NotAllowed`, and the
 * proof is asked again with the chosen password kept.
 *
 * **Finishing** (F11 steps 13 to 19): the new master password typed once
 * (`finishNewMasterPassword`, under the guard of lock.md §6.8); nothing locks. Afterwards the lock's
 * "Choose a PIN" opens 4.3's PIN steps with the fingerprint offer (through 4.2's notices), and this
 * screen offers the new backup: "Make a new backup now. …" with "Back up now", which hands the new
 * master password's proof to the backup screen (`BackupOffer`), and "Later".
 *
 * The lock's calls run in [work], the app's scope, so that leaving the screen cannot cut the lock's
 * records in half. A lock ends the flow; after the unlock the route starts again. A rotation or a
 * language change keeps everything (lock.md §11).
 */
class NewMasterPasswordViewModel(
    private val lock: LockManager,
    private val clock: Clock,
    random: RandomSource,
    dispatchers: AppDispatchers,
    private val work: CoroutineScope,
    private val offerBackup: (MasterProof) -> Unit,
    rules: (CharArray) -> MasterPasswordCheck = { MasterPasswordRules.check(it) },
) : ViewModel() {

    /** Steps 3 to 5 of the asking flow: the new master password, chosen, written down and typed again. */
    val choice = NewMasterPasswordChoice(viewModelScope, random, dispatchers, rules)

    /** The field of the finishing step: the new master password, typed once. */
    val finishField = TextFieldState()

    private val state: MutableStateFlow<NewMasterPasswordUi>

    /** What the route shows. */
    val ui: StateFlow<NewMasterPasswordUi>

    private val pinDigits = PinDigits()

    /** A request was made in this flow: the proof was used up, and nothing is left for [leave]. */
    private var requested = false

    /**
     * The flow may hold a proof of step 3 now: one made here, or the lock-screen route's, which
     * stands when the route opens, on whichever step it opens (the finishing step too, when the new
     * master password is ready). [leave] tells the lock when the flow ends.
     */
    private var asking = true

    private var countdown: Job? = null

    init {
        state = MutableStateFlow(startUi(lock.status.value))
        ui = state.asStateFlow()
        viewModelScope.launch {
            lock.state.collect { if (it !is LockUiState.Unlocked) lockedNow() }
        }
        viewModelScope.launch {
            lock.status.collect(::statusChanged)
        }
    }

    // ---- the explanation (F11 step 2) and "Proof now" (step 3) -------------------------------------

    /**
     * "Continue" on the explanation, "Use fingerprint" after the PIN was turned off, and the second
     * proof after `NotAllowed`: the lock is asked for the proof without a PIN. A proof that stands
     * (the lock-screen route) leads to the choice; while a PIN exists, the pad; without one, the
     * fingerprint prompt "Confirm with your fingerprint".
     */
    fun proveWithoutPin() {
        val now = state.value
        if (now.proving || (now.stage != NewStage.EXPLAIN && now.stage != NewStage.PIN_GONE && now.stage != NewStage.PIN)) return
        asking = true
        state.update { it.copy(proving = true, line = null, fingerprintPrompt = promptsFingerprint(lock.status.value)) }
        prove(pin = null)
    }

    /** A digit of the pad; the sixth sends the PIN to the lock, a counted check. */
    fun digit(digit: Int) {
        val now = state.value
        if (now.stage != NewStage.PIN || now.proving) return
        if (!pinDigits.add(digit)) {
            state.update { it.copy(entered = pinDigits.count) }
            return
        }
        val pin = pinDigits.take()
        state.update { it.copy(entered = PIN_DIGITS, proving = true, fingerprintPrompt = false) }
        prove(pin)
    }

    /** ⌫ on the pad. */
    fun deleteDigit() {
        if (state.value.stage != NewStage.PIN || state.value.proving) return
        pinDigits.delete()
        state.update { it.copy(entered = pinDigits.count) }
    }

    /** A long press on ⌫: the digits typed so far go. */
    fun clearDigits() {
        if (state.value.stage != NewStage.PIN || state.value.proving) return
        pinDigits.clear()
        state.update { it.copy(entered = 0) }
    }

    /** "Proof now" with [pin] (six digits, which this function zeroes) or without one. */
    private fun prove(pin: ByteArray?) {
        val proving = work.async {
            try {
                lock.proveForNewMasterPassword(pin)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ProofResult.CannotCheck // the lock failed in a way it does not report: the flow stops
            } finally {
                pin?.fill(0)
            }
        }
        viewModelScope.launch { proven(proving.await()) }
    }

    private fun proven(result: ProofResult) {
        val now = state.value
        if (!now.proving) return
        val base = now.copy(proving = false, fingerprintPrompt = false, entered = 0)
        when (result) {
            is ProofResult.Proven -> {
                pinDigits.clear()
                if (choice.typed) {
                    request() // the proof went while the password was chosen, and is back: ask now
                } else {
                    state.value = base.copy(stage = NewStage.CHOOSE, line = null)
                    choice.showWords()
                }
            }
            ProofResult.NeedsPin -> state.value = base.copy(stage = NewStage.PIN) // the pad; a line it had (asked again) stays
            is ProofResult.PinWrong -> state.value =
                base.copy(stage = NewStage.PIN, line = UiText.plural(R.plurals.master_pin_wrong, result.left), shakes = now.shakes + 1)
            ProofResult.SamePinAgain -> state.value =
                base.copy(stage = NewStage.PIN, line = UiText.of(R.string.master_pin_same_again), shakes = now.shakes + 1)
            ProofResult.PinGone -> state.value =
                if (fingerprintUsable(lock.status.value)) base.copy(stage = NewStage.PIN_GONE, line = null) else stop(R.string.master_not_offered)
            ProofResult.CannotCheck -> state.value = stop(R.string.master_proof_cannot_check)
            ProofResult.Cancelled, ProofResult.NotShown -> state.value = base // the prompt was closed: stay, and try again from here
            is ProofResult.FingerprintFailed -> state.value = base.copy(line = fingerprintLine(result.line))
            ProofResult.NotOffered -> state.value = stop(R.string.master_not_offered)
        }
    }

    // ---- choosing (step 4) -------------------------------------------------------------------------

    /** "Use these words" or "No, it's new": on to "Write it down on paper". */
    fun chosen() {
        val stage = state.value.stage
        val choosing = stage == NewStage.CHOOSE || stage == NewStage.WRITE_DOWN || stage == NewStage.TYPE_AGAIN
        if (choosing && choice.state.value.chosen != null) state.update { it.copy(stage = NewStage.WRITE_DOWN) }
    }

    /** "Next" on "Write it down on paper". */
    fun wroteItDown() {
        if (state.value.stage == NewStage.WRITE_DOWN) state.update { it.copy(stage = NewStage.TYPE_AGAIN) }
    }

    /** "Show my password again": back to "Write it down on paper". */
    fun showAgain() {
        if (state.value.stage != NewStage.TYPE_AGAIN) return
        choice.leftTypeAgain()
        state.update { it.copy(stage = NewStage.WRITE_DOWN) }
    }

    /** "Next" on "Type your master password again": a matching typing makes the request (step 5). */
    fun submitAgain() {
        if (state.value.stage != NewStage.TYPE_AGAIN) return
        if (choice.submitAgain()) request()
    }

    /** "Try again" after the request could not be saved or Argon2id could not run: the same typings. */
    fun retryRequest() {
        if (state.value.stage == NewStage.REQUEST_FAILED && choice.typed) request()
    }

    // ---- the request (step 5) ----------------------------------------------------------------------

    private fun request() {
        val typings = choice.typings() ?: return
        state.update { it.copy(stage = NewStage.REQUESTING, line = null, proving = false) }
        val (first, again) = typings
        val asked = work.async {
            try {
                lock.requestNewMasterPassword(first, again)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RequestResult.NotSaved
            } finally {
                first.fill('\u0000')
                again.fill('\u0000')
            }
        }
        viewModelScope.launch { requestedNow(asked.await()) }
    }

    private fun requestedNow(result: RequestResult) {
        if (state.value.stage != NewStage.REQUESTING) return
        when (result) {
            is RequestResult.Requested -> {
                requested = true
                asking = false
                choice.wipe()
                state.update { it.copy(stage = NewStage.WAIT) }
            }
            RequestResult.Mismatch -> {
                choice.mismatchFromLock()
                state.update { it.copy(stage = NewStage.TYPE_AGAIN) }
            }
            is RequestResult.Unassigned -> {
                choice.refuseUnassigned()
                state.update { it.copy(stage = NewStage.CHOOSE) }
            }
            // The proof went meanwhile (the app was left, lock.md §5.8): asked again, the password kept. The pad
            // only while a PIN exists; without one the fingerprint is asked on the explanation, as at first.
            RequestResult.NotAllowed -> if (lock.status.value.pin == PinStatus.On) {
                state.update { it.copy(stage = NewStage.PIN, line = UiText.of(R.string.master_proof_again), entered = 0) }
            } else {
                state.update { it.copy(stage = NewStage.EXPLAIN, entered = 0) }
                proveWithoutPin()
                state.update { it.copy(line = UiText.of(R.string.master_proof_again)) } // says why, also after a closed prompt
            }
            RequestResult.Cancelled -> {
                choice.wipe()
                state.update { it.copy(stage = NewStage.EXPLAIN, line = UiText.of(R.string.master_request_cancelled)) }
            }
            RequestResult.NoMemory -> state.update { it.copy(stage = NewStage.REQUEST_FAILED, line = UiText.of(R.string.master_request_no_memory)) }
            RequestResult.NotSaved -> state.update { it.copy(stage = NewStage.REQUEST_FAILED, line = UiText.of(R.string.master_request_not_saved)) }
        }
    }

    // ---- finishing (steps 13 to 19) ----------------------------------------------------------------

    /**
     * "Finish" or the keyboard's Done on the finishing step: the new master password typed once goes
     * to the lock, and the field is emptied at once.
     */
    fun submitFinish() {
        val now = state.value
        if (now.stage != NewStage.FINISH || now.finishing || now.waitSeconds != null || finishField.text.isEmpty()) return
        val chars = finishField.readSecret()
        finishField.clearSecret()
        state.update { it.copy(finishing = true, line = null) }
        val finishing = work.async {
            try {
                lock.finishNewMasterPassword(chars)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FinishResult.NotFinished
            } finally {
                chars.fill('\u0000')
            }
        }
        viewModelScope.launch { finished(finishing.await()) }
    }

    private fun finished(result: FinishResult) {
        if (state.value.stage != NewStage.FINISH || !state.value.finishing) return
        state.update { it.copy(finishing = false) }
        when (result) {
            FinishResult.Finished -> state.update { it.copy(stage = NewStage.FINISHED, line = null) }
            // The current master password was typed: the request is cancelled, and the lock's notice says so.
            FinishResult.RequestCancelled, FinishResult.TooLate -> state.update { it.copy(leave = Leave.BACK) }
            FinishResult.Wrong -> state.update { it.copy(line = UiText.of(R.string.master_wrong)) }
            is FinishResult.Wait -> startWait(result.untilElapsedMs)
            FinishResult.NoMemory -> state.update { it.copy(line = UiText.of(R.string.master_no_memory)) }
            FinishResult.NotFinished -> state.update { it.copy(line = UiText.of(R.string.master_not_finished)) }
        }
    }

    /**
     * "Back up now" after the finish: the backup screen that opens next uses the new master
     * password's proof, which the unlocked state holds (F11 step 19, F12 step 2).
     */
    fun backUpNow() {
        if (state.value.stage != NewStage.FINISHED) return
        (lock.state.value as? LockUiState.Unlocked)?.proof?.let(offerBackup)
        state.update { it.copy(leave = Leave.BACKUP) }
    }

    // ---- leaving -----------------------------------------------------------------------------------

    /**
     * Back: on the pad, back to the explanation; from choosing back to the explanation (the proof
     * goes with the flow, lock.md F11 step 3); from "Write it down on paper" to the choice; from
     * "Type your master password again" to "Write it down on paper". Ignored while the lock works.
     * Elsewhere the route closes ([close]).
     */
    fun back() {
        val now = state.value
        if (now.proving || now.finishing) return
        when (now.stage) {
            NewStage.PIN, NewStage.PIN_GONE, NewStage.CHOOSE -> {
                leave()
                choice.wipe()
                pinDigits.clear()
                state.update { it.copy(stage = NewStage.EXPLAIN, line = null, entered = 0) }
            }
            NewStage.WRITE_DOWN -> {
                choice.leftWriteDown()
                state.update { it.copy(stage = NewStage.CHOOSE) }
            }
            NewStage.TYPE_AGAIN -> {
                choice.leftTypeAgain()
                state.update { it.copy(stage = NewStage.WRITE_DOWN) }
            }
            NewStage.REQUESTING -> Unit
            else -> close()
        }
    }

    /** "Cancel" on the explanation, "OK", "Done", "Later", or Back where the route closes: the flow ends here. */
    fun close() {
        val now = state.value
        if (now.proving || now.finishing || now.stage == NewStage.REQUESTING) return
        dropAll()
        state.update { it.copy(leave = Leave.BACK) }
    }

    /** The screen closed the route, or opened the next one, as [NewMasterPasswordUi.leave] asked. */
    fun left() = state.update { it.copy(leave = null) }

    override fun onCleared() {
        dropAll()
    }

    // ---- inside ------------------------------------------------------------------------------------

    /**
     * The flow ends without a request: the lock drops the proof of step 3 (lock.md F11 step 3, §5.8).
     * Told once per flow, and never after a request, which used the proof up already.
     */
    private fun leave() {
        if (asking && !requested) lock.leaveNewMasterPassword()
        asking = false
    }

    private fun dropAll() {
        leave()
        choice.wipe()
        pinDigits.clear()
        finishField.clearSecret()
        countdown?.cancel()
        countdown = null
    }

    /**
     * The app locked: the flow is over (the lock dropped the proof itself); after the unlock the route
     * starts again with a new flow, in which no request was made yet, so that its proof, too, goes
     * when it ends without one.
     */
    private fun lockedNow() {
        asking = false
        dropAll()
        state.value = startUi(lock.status.value)
        requested = false
        asking = true
    }

    /**
     * Where the route starts for [status]: the finishing step while a new master password is ready;
     * the explanation while the PIN or the fingerprint can open the app; otherwise the route is not
     * offered (lock.md F11 step 3, "Neither"), and says so.
     */
    private fun startUi(status: LockStatus): NewMasterPasswordUi {
        val stage = when {
            status.pending?.ready == true -> NewStage.FINISH
            status.pin == PinStatus.On || status.pin == PinStatus.CannotCheck || fingerprintUsable(status) -> NewStage.EXPLAIN
            else -> NewStage.STOPPED
        }
        val line = if (stage == NewStage.STOPPED) UiText.of(R.string.master_not_offered) else null
        return NewMasterPasswordUi(stage = stage, line = line).withStatus(status)
    }

    /**
     * The lock's facts changed: the wording of the explanation follows them, and the finishing step
     * closes when nothing is pending any more (cancelled meanwhile). An explanation still on screen
     * becomes the finishing step when the new master password becomes ready.
     */
    private fun statusChanged(status: LockStatus) {
        val now = state.value
        when {
            now.stage == NewStage.FINISH && status.pending == null && !now.finishing -> close()
            now.stage == NewStage.EXPLAIN && status.pending?.ready == true && !now.proving -> {
                leave()
                state.update { it.copy(stage = NewStage.FINISH, line = null).withStatus(status) }
            }
            else -> state.update { it.withStatus(status) }
        }
    }

    private fun NewMasterPasswordUi.withStatus(status: LockStatus): NewMasterPasswordUi = copy(
        by = if (status.pin == PinStatus.On || status.pin == PinStatus.CannotCheck) Shortcut.PIN else Shortcut.FINGERPRINT,
        replacesPending = status.pending?.ready == false,
        fingerprintOn = fingerprintUsable(status),
    )

    private fun stop(text: Int): NewMasterPasswordUi {
        leave()
        choice.wipe()
        pinDigits.clear()
        return state.value.copy(stage = NewStage.STOPPED, line = UiText.of(text), proving = false, fingerprintPrompt = false, entered = 0)
    }

    /** "Wait 30 seconds, then try again." with a countdown of the seconds left; the field is off until then. */
    private fun startWait(untilElapsedMs: Long) {
        countdown?.cancel()
        val now = clock.elapsedMs()
        if (now >= untilElapsedMs) {
            state.update { it.copy(line = null, waitSeconds = null) }
            return
        }
        state.update { it.copy(line = UiText.of(R.string.master_wait), waitSeconds = secondsLeft(untilElapsedMs - now)) }
        countdown = viewModelScope.launch {
            while (true) {
                val left = untilElapsedMs - clock.elapsedMs()
                if (left <= 0) break
                clock.delayUntilElapsed(untilElapsedMs - (secondsLeft(left) - 1) * SECOND_MS)
                val at = clock.elapsedMs()
                if (at >= untilElapsedMs) break
                state.update { it.copy(waitSeconds = secondsLeft(untilElapsedMs - at)) }
            }
            state.update { it.copy(line = null, waitSeconds = null) }
        }
    }

    companion object {
        private const val PIN_DIGITS = 6
        private const val SECOND_MS = 1_000L

        /** Whole seconds left, rounded up: 30 at the start of the wait, 1 in its last second. */
        private fun secondsLeft(ms: Long): Int = ((ms + SECOND_MS - 1) / SECOND_MS).toInt()

        /** Fingerprint unlock is on and can be used: the line "Forgot your PIN as well? …", and the way on after the PIN is turned off. */
        private fun fingerprintUsable(status: LockStatus): Boolean = status.fingerprint == FingerprintStatus.On

        /** Whether "Proof now" without a PIN opens the fingerprint prompt: no PIN exists, and fingerprint unlock is on (lock.md F11 step 3). */
        private fun promptsFingerprint(status: LockStatus): Boolean =
            status.pin != PinStatus.On && status.pin != PinStatus.CannotCheck && fingerprintUsable(status)

        /** The line of lock.md §13.3 for a fingerprint that did not confirm, said for this step. */
        fun fingerprintLine(line: FingerprintLine): UiText = UiText.of(
            when (line) {
                FingerprintLine.DidNotOpen -> R.string.master_fingerprint_did_not_confirm
                FingerprintLine.Locked -> R.string.master_fingerprint_locked
                FingerprintLine.NotForApps -> R.string.master_fingerprint_not_for_apps
                FingerprintLine.NoneSaved -> R.string.master_fingerprint_none_saved
                FingerprintLine.UpdateNeeded -> R.string.master_fingerprint_update_needed
                FingerprintLine.TurnedOff, FingerprintLine.StoppedWorking, FingerprintLine.CannotCheck -> R.string.master_fingerprint_cannot_check
            },
        )

        /** The ViewModel over the app's graph. */
        fun from(graph: AppGraph): NewMasterPasswordViewModel {
            val offers = BackupOffer.of(graph)
            return NewMasterPasswordViewModel(
                lock = graph.lock,
                clock = graph.platform.clock,
                random = graph.platform.random,
                dispatchers = graph.dispatchers,
                work = graph.appScope,
                offerBackup = offers::offer,
            )
        }
    }
}

/** The steps of `Route.NewMasterPassword` (lock.md F11). */
enum class NewStage {
    /** "You can choose a new master password because …" with "Continue" and "Cancel" (step 2). */
    EXPLAIN,

    /** "Enter your app PIN" (step 3), a counted check. */
    PIN,

    /** The PIN was turned off by wrong tries in this step: "Use fingerprint" (step 3). */
    PIN_GONE,

    /** "Choose your master password" (setup's screen, step 4). */
    CHOOSE,

    /** "Write it down on paper" (setup's screen; the screen kept on). */
    WRITE_DOWN,

    /** "Type your master password again" (setup's screen; the screen kept on). */
    TYPE_AGAIN,

    /** "Saving…": the request (step 5). */
    REQUESTING,

    /** The request could not be saved, or Argon2id could not run: "Try again". */
    REQUEST_FAILED,

    /** "Your new master password starts working after about 24 hours …" (step 6). */
    WAIT,

    /** The flow stopped: the PIN cannot be checked now, or neither the PIN nor the fingerprint works ("OK"). */
    STOPPED,

    /** "Type your new master password": finishing (steps 13 to 19). */
    FINISH,

    /** Finished: "Your new master password works now." and the new backup (step 19). */
    FINISHED,
}

/**
 * What `Route.NewMasterPassword` shows. Never a digit or a password.
 *
 * @property by the shortcut the explanation names: the PIN while one exists, else the fingerprint.
 * @property replacesPending another new master password is waiting and not ready: the explanation
 *   says that a new one starts its 24 hours again.
 * @property fingerprintOn fingerprint unlock is on: "Forgot your PIN as well? …" on the pad.
 * @property proving the lock checks the PIN, or the fingerprint prompt is open ([fingerprintPrompt]).
 * @property line the line of the step: a wrong PIN, a fingerprint that did not confirm, why the flow
 *   stopped, why the request failed, a wrong new master password, the wait.
 * @property entered how many digits of the PIN are typed.
 * @property shakes grows with each wrong PIN: the dots shake.
 * @property finishing the lock checks the new master password and finishes (steps 14 to 18).
 * @property waitSeconds the seconds of a master-password wait left; the field is off meanwhile.
 * @property leave the route ends, and where it goes.
 */
data class NewMasterPasswordUi(
    val stage: NewStage = NewStage.EXPLAIN,
    val by: Shortcut = Shortcut.PIN,
    val replacesPending: Boolean = false,
    val fingerprintOn: Boolean = false,
    val proving: Boolean = false,
    val fingerprintPrompt: Boolean = false,
    val line: UiText? = null,
    val entered: Int = 0,
    val shakes: Int = 0,
    val finishing: Boolean = false,
    val waitSeconds: Int? = null,
    val leave: Leave? = null,
)
