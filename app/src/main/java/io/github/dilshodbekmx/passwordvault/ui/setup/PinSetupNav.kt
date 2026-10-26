package io.github.dilshodbekmx.passwordvault.ui.setup

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.EntryProviderScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChoosePinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.lock.FingerprintOnResult
import io.github.dilshodbekmx.passwordvault.lock.FingerprintStatus
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.MpPurpose
import io.github.dilshodbekmx.passwordvault.lock.PinSetResult
import io.github.dilshodbekmx.passwordvault.lock.PinStatus
import io.github.dilshodbekmx.passwordvault.nav.NavState
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.settings.AppSettings
import io.github.dilshodbekmx.passwordvault.ui.common.KeyboardFirstOnBack
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The routes of the PIN and fingerprint steps (ux.md §5.6 to §5.8; lock.md F1 steps 6 to 9, F5 step
 * 4, F7 step 3, F14, F16b step 6; build plan 4.3): CreatePin ("Choose a 6-digit PIN", or the master
 * password first when this flow has none), ConfirmPin ("Enter the PIN again"), FingerprintOffer and
 * BackupsExplained ("Make backups"). They come after the vault exists, on top of the unlocked
 * screens: after setup the lock's notice "Choose a PIN" opens CreatePin, and the steps that follow
 * depend on why ([PinFlow]).
 *
 * The four steps share one [PinSetupViewModel], kept by the activity (like the setup screens'
 * `SetupViewModel`): the chosen PIN goes from the first step to the second, and a rotation or a
 * language change keeps it. Each step also has a small view model of its own entry, which
 * Navigation 3 clears when the step leaves the back stack; it then tells the shared one
 * ([PinSetupViewModel.stepLeft]). A step that the shared view model asks to move ([PinMove]) does
 * so with the navigator, so the back stack never holds a finished step: once the PIN is saved, the
 * PIN steps make room for the next one.
 */
fun EntryProviderScope<Route>.pinSetupNav(graph: AppGraph, nav: Navigator) {
    entry<Route.CreatePin> { CreatePinRoute(graph, nav) }
    entry<Route.ConfirmPin> { ConfirmPinRoute(graph, nav) }
    entry<Route.FingerprintOffer> { FingerprintOfferRoute(graph, nav) }
    entry<Route.BackupsExplained> { BackupsExplainedRoute(graph, nav) }
}

/**
 * Why the PIN steps are opened, for the screen that opens [Route.CreatePin]: the reason of the lock's
 * notice "Choose a PIN" (lock.md §13.4 item 5), which decides the steps that follow the PIN
 * ([PinFlow]) and the words of the first step. The route key holds no argument, so the opener leaves
 * this one-time mark ([offer]) just before it opens the route, and the first step takes it ([take])
 * when it starts. Without a mark the steps read the reason from a "Choose a PIN" notice still in the
 * lock's state, and otherwise from the lock's status (no PIN on this phone: the steps of an unlock
 * without a PIN). A second mark, [newVault], is left by setup and the restore just before they ask
 * the lock to make the vault: only then do the steps end with "Make backups" (build-plan.md §12,
 * decision 24). In memory only, nothing secret; a new process has none. Main thread only.
 */
object PinSetupStart {
    private var reason: ChoosePinReason? = null
    private var vaultMade = false

    /** The PIN steps are about to be opened for [reason]. */
    fun offer(reason: ChoosePinReason) {
        this.reason = reason
    }

    /** The reason left by [offer], once; null when there is none. */
    fun take(): ChoosePinReason? = reason.also { reason = null }

    /**
     * Setup or a restore is about to make the vault (`LockManager.createVault`; lock.md F1, F13): the
     * PIN steps that "Choose a PIN" opens next are setup's, with "Make backups" at their end. The
     * maker takes the mark back ([takeNewVault]) when no vault was made.
     */
    fun newVault() {
        vaultMade = true
    }

    /** Whether [newVault] was left, once. */
    fun takeNewVault(): Boolean = vaultMade.also { vaultMade = false }
}

/** Which steps follow the PIN (lock.md §12). */
enum class PinFlow {
    /**
     * A new vault or a restored one (F1 steps 6 to 9, F13; [PinSetupStart.newVault]): fingerprint
     * offer, "Make backups", then what lies under the steps, the list.
     */
    SETUP,

    /**
     * A master-password unlock without a PIN on this phone (a PIN never set, F14, F17's vault opened
     * again), or "Set a PIN" in Settings: the PIN and the fingerprint offer as in setup, then back to
     * the screen under the steps, which an unlock brought back. Setup's "Make backups" (F1 step 8)
     * follows only the first setup and a restore (build-plan.md §12, decision 24); the backup reminder
     * after an unlock is the lock's "Make a backup now?" (decision 15).
     */
    AFTER_UNLOCK,

    /**
     * A new master password finished in the open app (F11 step 19, "Finish now"): the lock's "Choose
     * a PIN" (`ChoosePinReason.NONE_HERE`) opens the steps over `Route.NewMasterPassword`. The PIN
     * and the fingerprint offer as in setup (F1 steps 5 to 7), then back to that screen, whose own
     * backup step follows ("Make a new backup now. Your old backups still open only with your old
     * master password."); setup's "Make backups" (F1 step 8) is left out.
     */
    AFTER_NEW_MASTER_PASSWORD,

    /** Back to the passwords from before a replacement (F16b step 6): "Someone else may know the old one.", then the fingerprint offer. */
    NEW_PIN_WITH_OFFER,

    /** The PIN was turned off, forgotten, stopped working or not kept (F5, F7, F8, F15b): back to where the user was. */
    NEW_PIN,
}

/** Where the PIN steps are. */
enum class PinSetupStage {
    /** Not started: the first step decides the flow. */
    START,

    /** "Type your master password": this flow has no proof yet (a fingerprint or PIN unlock, lock.md F5 step 4). */
    MASTER,

    /** "Choose a 6-digit PIN" or "Choose a new PIN". */
    CHOOSE,

    /** "Enter the PIN again", then the PIN is saved. */
    CONFIRM,

    /** "Use your fingerprint?" */
    FINGERPRINT,

    /** "Make backups". */
    BACKUPS,

    /** The steps are over; they leave the back stack. */
    DONE,
}

/** One move of a [PinMove], on the navigator of the step that performs it. */
sealed interface NavStep {
    /** Opens [to] on top. */
    data class Go(val to: Route) : NavStep

    /** Closes the screen shown now. */
    data object Back : NavStep

    /** Replaces the screen shown now with [to]. */
    data class Replace(val to: Route) : NavStep

    /** Shows the bottom bar's root [root] alone (the end of setup). */
    data class Tab(val root: Route) : NavStep

    /** Performs this move on [nav]; false when the navigator refused it (the app locked meanwhile). */
    fun applyTo(nav: Navigator): Boolean = when (this) {
        is Go -> nav.go(to)
        Back -> nav.back()
        is Replace -> nav.replace(to)
        is Tab -> nav.tab(root)
    }
}

/**
 * A move that the step [from] performs on its navigator, in order ([steps]), and then reports with
 * [PinSetupViewModel.moved]. [id] tells two moves apart.
 */
data class PinMove(val id: Long, val from: Route, val steps: List<NavStep>)

/**
 * What the PIN steps show (ux.md §5.6 to §5.8). Texts only as [UiText]; never a digit.
 *
 * @property newPin the first step says "Choose a new PIN" (a PIN existed before this flow).
 * @property someoneMayKnow the first step adds "Someone else may know the old one." (lock.md F16b).
 * @property entered how many digits of the entry are typed (the dots).
 * @property line the line under the dots: a refused PIN, a mismatch, a PIN that could not be saved.
 * @property shakes grows with each refused entry or mismatch: the dots shake.
 * @property saving "Saving…" in place of the keypad while the lock saves the new PIN.
 * @property saved "Your PIN is set." in place of the keypad, for a moment before the next step.
 * @property saveFailed the PIN could not be saved: "Try again" and "Not now".
 * @property saveUnsure the lock cannot say whether the PIN was saved: the line says what to try next
 *   time, and "OK" goes on.
 * @property fingerprintBusy the fingerprint prompt is open or the key is being made.
 * @property fingerprintLine why fingerprint unlock could not be turned on.
 * @property backupLine the backup reminder could not be saved.
 * @property move a move that a step performs ([PinMove]).
 */
data class PinSetupUi(
    val stage: PinSetupStage = PinSetupStage.START,
    val flow: PinFlow = PinFlow.SETUP,
    val newPin: Boolean = false,
    val someoneMayKnow: Boolean = false,
    val entered: Int = 0,
    val line: PinLine? = null,
    val shakes: Int = 0,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val saveFailed: Boolean = false,
    val saveUnsure: Boolean = false,
    val fingerprintBusy: Boolean = false,
    val fingerprintLine: PinLine? = null,
    val backupLine: PinLine? = null,
    val move: PinMove? = null,
)

/**
 * The view model of the PIN and fingerprint steps after the vault exists (ux.md §5.6 to §5.8; lock.md
 * F1 steps 6 to 9, F5, F7, F14, F16b; build plan 4.3).
 *
 * - **The proof.** Setting a PIN or turning fingerprint on adds a way in, so it needs the master
 *   password typed in the same flow (lock.md §8.2): the proof of the unlock that led here
 *   (`LockUiState.Unlocked.proof`: setup, a restore, "Forgot PIN?", a master-password unlock), or,
 *   when there is none, the master password asked first ([PinSetupStage.MASTER], [masterPassword]).
 *   A proof asked for here is dropped when the steps end; the unlock's own proof is the lock's.
 * - **The PIN** is chosen and typed again by [PinChooser]: the three refusal messages, three
 *   mismatches back to the first entry. It is saved with `LockManager.setPin`, in [work], the app's
 *   scope, so that leaving the screen cannot cut the lock's records in half; the digits are held as
 *   bytes, handed to the lock as a copy and zeroed as soon as they are not needed.
 * - **The fingerprint offer** (lock.md §9.4) is shown only when `canAuthenticate(BIOMETRIC_STRONG)`
 *   ([availability]) is 0, asked off the main thread when the steps start, and fingerprint unlock is
 *   not on. Its prompt comes through the lock (`LockManager.fingerprintOn`).
 * - **A lock** drops everything: digits, proof, the field. After the unlock the same step is shown
 *   again and starts afresh.
 * - **A save the lock cannot vouch for** (lock.md F6 step 8, F9 step 9; build-plan.md §12, decision 4:
 *   the record's write reported a failure, and the record read back is the new one or cannot be
 *   read: `PinSetResult.MayNotBeSaved`, `FingerprintOnResult.MayNotBeOn`). The screen then promises
 *   nothing: [pinMayNotBeSaved] and [fingerprintMayNotBeOn] say what to try next time.
 */
class PinSetupViewModel(
    private val lock: LockManager,
    private val settings: AppSettings,
    clock: Clock,
    private val availability: () -> Int,
    private val dispatchers: AppDispatchers,
    private val work: CoroutineScope,
) : ViewModel() {
    private val chooser = PinChooser(lock::pinProblem)

    /** The master-password step of a flow without a proof (lock.md §8.2). */
    val masterPassword = MasterPasswordAsk(lock, clock, MpPurpose.SET_PIN, work, viewModelScope)

    private val state = MutableStateFlow(PinSetupUi())

    /** What the steps show. */
    val ui: StateFlow<PinSetupUi> = state.asStateFlow()

    /** "Remind me to make a backup" (ux.md §5.8): the stored switch. */
    val backupReminder: StateFlow<Boolean> =
        settings.backupReminder.map { it.on }.stateIn(viewModelScope, SharingStarted.Eagerly, settings.backupReminder.value.on)

    private var ownProof: MasterProof? = null
    private var pending: ByteArray? = null
    private var canAuthenticate: Deferred<Int>? = null
    private var deciding = false

    /** Whether a PIN worked when the save now running began: the line after it names "the old PIN" only then. */
    private var pinWorkedBefore = false
    private var moves = 0L
    private var cleared = false

    init {
        // A lock (or the vault put aside) ends the steps: nothing typed or proved stays behind.
        viewModelScope.launch {
            lock.state.collect { if (it !is LockUiState.Unlocked) resetAll() }
        }
    }

    // ---- the first step --------------------------------------------------------------------------

    /**
     * The first step is shown. Unless the steps are under way already, it decides the flow (the
     * opener's mark, a "Choose a PIN" notice, or the lock's status, and [under], the route the steps
     * were opened over), starts the fingerprint question where an offer may follow, and asks for the
     * master password when this flow has no proof.
     */
    fun start(under: Route? = null) {
        if (state.value.stage != PinSetupStage.START) return
        val newVault = PinSetupStart.takeNewVault()
        val reason = PinSetupStart.take() ?: choosePinNotice()?.reason
        val setup = when {
            under == Route.NewMasterPassword -> PinFlow.AFTER_NEW_MASTER_PASSWORD
            newVault -> PinFlow.SETUP
            else -> PinFlow.AFTER_UNLOCK
        }
        val flow = when (reason) {
            null -> if (lock.status.value.pin == PinStatus.NotSet) setup else PinFlow.NEW_PIN
            ChoosePinReason.NONE_HERE -> setup
            ChoosePinReason.SOMEONE_MAY_KNOW -> PinFlow.NEW_PIN_WITH_OFFER
            ChoosePinReason.TURNED_OFF, ChoosePinReason.FORGOTTEN, ChoosePinReason.STOPPED_WORKING, ChoosePinReason.MASTER_CHANGED -> PinFlow.NEW_PIN
        }
        if (flow != PinFlow.NEW_PIN) {
            canAuthenticate = viewModelScope.async(dispatchers.io) {
                try {
                    availability()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CANNOT_ASK
                }
            }
        }
        state.value = PinSetupUi(
            stage = if (proof() != null) PinSetupStage.CHOOSE else PinSetupStage.MASTER,
            flow = flow,
            newPin = flow == PinFlow.NEW_PIN || flow == PinFlow.NEW_PIN_WITH_OFFER,
            someoneMayKnow = reason == ChoosePinReason.SOMEONE_MAY_KNOW,
        )
    }

    /** "Next" on the master-password step: a right master password is this flow's proof, and the PIN is chosen next. */
    fun submitMasterPassword() {
        if (state.value.stage != PinSetupStage.MASTER) return
        masterPassword.submit { proof ->
            if (cleared || state.value.stage != PinSetupStage.MASTER) {
                proof.drop()
                return@submit
            }
            ownProof?.drop()
            ownProof = proof
            state.update { it.copy(stage = PinSetupStage.CHOOSE, line = null, entered = 0) }
        }
    }

    /** A digit of the pad of [on] (CreatePin or ConfirmPin); ignored unless that step is the one under way. */
    fun digit(digit: Int, on: Route) {
        val now = state.value
        if (!typingOn(now, on)) return
        when (val result = chooser.digit(digit)) {
            PinChooser.Result.Typing -> state.update { it.copy(entered = chooser.digits.count) }
            is PinChooser.Result.Refused -> state.update {
                it.copy(entered = 0, line = PinLine(PinChooser.refusal(result.reason)), shakes = it.shakes + 1)
            }
            PinChooser.Result.SameAsCurrent -> state.update { it.copy(entered = 0) } // no current PIN in these steps
            PinChooser.Result.ToConfirm -> state.update {
                it.copy(stage = PinSetupStage.CONFIRM, entered = 0, line = null, move = move(Route.CreatePin, NavStep.Go(Route.ConfirmPin)))
            }
            PinChooser.Result.Mismatch -> state.update { it.copy(entered = 0, line = mismatch(), shakes = it.shakes + 1) }
            PinChooser.Result.StartAgain -> state.update {
                it.copy(stage = PinSetupStage.CHOOSE, entered = 0, line = mismatch(), shakes = it.shakes + 1, move = move(Route.ConfirmPin, NavStep.Back))
            }
            is PinChooser.Result.Confirmed -> {
                wipePending()
                pending = result.pin
                state.update { it.copy(entered = 0) }
                save()
            }
        }
    }

    /** ⌫ on the pad of [on]. */
    fun delete(on: Route) {
        if (!typingOn(state.value, on)) return
        chooser.delete()
        state.update { it.copy(entered = chooser.digits.count) }
    }

    /** A long press on ⌫ of [on]: the entry is cleared. */
    fun clearEntry(on: Route) {
        if (!typingOn(state.value, on)) return
        chooser.clearEntry()
        state.update { it.copy(entered = 0) }
    }

    /** "Not now" (or Back) on the first step: no PIN now; the steps after the PIN follow, or the user goes back. */
    fun notNow() {
        val stage = state.value.stage
        if (deciding || state.value.move != null || (stage != PinSetupStage.MASTER && stage != PinSetupStage.CHOOSE)) return
        chooser.startAgain()
        masterPassword.clear()
        state.update { it.copy(entered = 0, line = null) }
        decideAfterPin(Route.CreatePin)
    }

    // ---- the second step -------------------------------------------------------------------------

    /** The second step is shown: without a chosen PIN (a new process, a lock) it goes back to the first. */
    fun confirmShown() {
        val now = state.value
        if (now.stage == PinSetupStage.CONFIRM || now.move?.from == Route.ConfirmPin) return
        state.update { it.copy(move = move(Route.ConfirmPin, NavStep.Back)) }
    }

    /**
     * Back on the second step: the first entry is dropped and chosen again. Ignored while the PIN is
     * saved; after a save the lock cannot vouch for it goes on, as "OK" does.
     */
    fun backFromConfirm() {
        val now = state.value
        if (now.stage != PinSetupStage.CONFIRM || now.saving || now.saved || deciding) return
        if (now.saveUnsure) return pinUnsureOk()
        chooser.startAgain()
        wipePending()
        state.update {
            it.copy(stage = PinSetupStage.CHOOSE, entered = 0, line = null, saveFailed = false, move = move(Route.ConfirmPin, NavStep.Back))
        }
    }

    /** "Try again" after the PIN could not be saved. */
    fun retrySave() {
        val now = state.value
        if (now.stage == PinSetupStage.CONFIRM && now.saveFailed && !now.saving && !deciding) save()
    }

    /** "Not now" after the PIN could not be saved: the steps go on without a PIN. */
    fun skipAfterFailure() {
        val now = state.value
        if (deciding || now.stage != PinSetupStage.CONFIRM || !now.saveFailed || now.saving) return
        wipePending()
        chooser.startAgain()
        state.update { it.copy(line = null) }
        decideAfterPin(Route.ConfirmPin)
    }

    /**
     * The lock cannot say whether the new PIN was saved (lock.md F6 step 8; build-plan.md §12,
     * decision 4). The second step then says "The new PIN may not have been saved. Next time try the
     * new PIN first. If it is not accepted, use the old PIN or your master password." (without the
     * old PIN where none worked before), and "OK" goes on ([pinUnsureOk]). Nothing is tried again
     * here: the lock already works from the record it read back.
     *
     * Called for the answer of `LockManager.setPin` that says so (`PinSetResult.MayNotBeSaved`),
     * while the save is running.
     */
    internal fun pinMayNotBeSaved() {
        val now = state.value
        if (now.stage != PinSetupStage.CONFIRM || !now.saving) return
        wipePending()
        val text = if (pinWorkedBefore) R.string.pin_may_not_be_saved else R.string.pin_may_not_be_saved_first
        state.update { it.copy(saving = false, saveFailed = false, saveUnsure = true, line = PinLine(UiText.of(text))) }
    }

    /** "OK" (or Back) after [pinMayNotBeSaved]: the steps go on as after a PIN. */
    fun pinUnsureOk() {
        val now = state.value
        if (deciding || now.stage != PinSetupStage.CONFIRM || !now.saveUnsure) return
        chooser.startAgain()
        state.update { it.copy(line = null) }
        decideAfterPin(Route.ConfirmPin)
    }

    // ---- the fingerprint offer -------------------------------------------------------------------

    /** The offer is shown: reached without the steps before it (a new process, a lock), it goes back. */
    fun fingerprintShown() {
        val now = state.value
        if (now.stage == PinSetupStage.FINGERPRINT || now.move?.from == Route.FingerprintOffer) return
        state.update { it.copy(move = move(Route.FingerprintOffer, NavStep.Back)) }
    }

    /**
     * "Use fingerprint" (lock.md F9 steps 3 to 9 with this flow's proof): the lock makes the key and
     * shows the prompt "Turn on fingerprint unlock". On: the next step. A closed prompt stays here;
     * a failure says why, and "Not now" goes on: setup is never blocked.
     */
    fun useFingerprint() {
        val now = state.value
        if (now.stage != PinSetupStage.FINGERPRINT || now.fingerprintBusy || now.move != null) return
        val proof = proof() ?: return fingerprintNotNow()
        state.update { it.copy(fingerprintBusy = true, fingerprintLine = null) }
        val turnOn = work.async {
            try {
                lock.fingerprintOn(proof)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FingerprintOnResult.Failed
            }
        }
        viewModelScope.launch {
            val result = turnOn.await()
            state.update { it.copy(fingerprintBusy = false) }
            if (state.value.stage != PinSetupStage.FINGERPRINT) return@launch
            when (result) {
                FingerprintOnResult.On -> afterFingerprint()
                FingerprintOnResult.Cancelled, FingerprintOnResult.NotShown -> Unit
                FingerprintOnResult.NoneEnrolled -> fingerprintLine(R.string.pin_fingerprint_add_first)
                FingerprintOnResult.NoSecureHardware -> fingerprintLine(R.string.pin_fingerprint_no_secure_hardware)
                FingerprintOnResult.Failed -> fingerprintLine(R.string.pin_fingerprint_failed)
                FingerprintOnResult.MayNotBeOn -> fingerprintMayNotBeOn()
            }
        }
    }

    /**
     * The lock cannot say whether fingerprint unlock was turned on (lock.md F9 step 9; build-plan.md
     * §12, decision 4): the offer says "Fingerprint unlock may not have been turned on. If your
     * fingerprint is not accepted, turn it on again in this app’s Settings.", and "Not now" goes on.
     *
     * Called for the answer of `LockManager.fingerprintOn` that says so (`FingerprintOnResult.MayNotBeOn`).
     */
    internal fun fingerprintMayNotBeOn() {
        if (state.value.stage == PinSetupStage.FINGERPRINT) fingerprintLine(R.string.pin_fingerprint_may_not_be_on)
    }

    /** "Not now" (or Back) on the offer: the next step. */
    fun fingerprintNotNow() {
        val now = state.value
        if (now.stage != PinSetupStage.FINGERPRINT || now.fingerprintBusy || now.move != null) return
        afterFingerprint()
    }

    // ---- "Make backups" --------------------------------------------------------------------------

    /** "Remind me to make a backup" switched: written at once, off the main thread. */
    fun setBackupReminder(on: Boolean) {
        work.launch(dispatchers.io) {
            val written = settings.update { it.backupReminder = it.backupReminder.copy(on = on) }
            state.update { it.copy(backupLine = if (written) null else PinLine(UiText.of(R.string.settings_not_saved))) }
        }
    }

    /**
     * "Finish" (or Back) on "Make backups", which only the first setup and a restore show (decision
     * 24): the PIN steps close, and what lies under them is shown again, the list alone. (After a
     * master-password unlock without a PIN the steps end at the fingerprint offer or the PIN and close
     * onto the screen the unlock brought back, an account or a form with its kept draft: ux.md §4,
     * "What survives a lock".) The steps are never the root of the unlocked stack (they open over a
     * tab's root), so Back always has somewhere to go.
     */
    fun finish() {
        if (state.value.move?.from == Route.BackupsExplained) return
        state.update { it.copy(stage = PinSetupStage.DONE, move = move(Route.BackupsExplained, NavStep.Back)) }
    }

    // ---- moves and steps -------------------------------------------------------------------------

    /** The step that performed [move] reports it. */
    fun moved(move: PinMove) {
        state.update { if (it.move?.id == move.id) it.copy(move = null) else it }
    }

    /**
     * The step [route] left the back stack for good (its entry's view model was cleared): what it
     * held goes. Back from the second step drops the first entry; the end of the steps, Back from
     * the first one, or a lock drops everything.
     */
    fun stepLeft(route: Route) {
        val now = state.value
        when (route) {
            Route.ConfirmPin -> if (now.stage == PinSetupStage.CONFIRM && !now.saving && !now.saved && !deciding) {
                chooser.startAgain()
                wipePending()
                state.update {
                    it.copy(stage = PinSetupStage.CHOOSE, entered = 0, line = if (now.saveUnsure) null else it.line, saveFailed = false, saveUnsure = false)
                }
            }
            Route.CreatePin -> if (now.stage in FIRST_STEPS_OR_DONE && !deciding) resetAll()
            Route.FingerprintOffer -> if (now.stage == PinSetupStage.FINGERPRINT || now.stage == PinSetupStage.DONE) resetAll()
            Route.BackupsExplained -> resetAll()
            else -> Unit
        }
    }

    override fun onCleared() {
        cleared = true
        resetAll()
    }

    // ---- inside ----------------------------------------------------------------------------------

    private fun typingOn(now: PinSetupUi, on: Route): Boolean {
        if (deciding || now.move != null) return false
        return when (on) {
            Route.CreatePin -> now.stage == PinSetupStage.CHOOSE && chooser.step == PinChooser.Step.CHOOSE
            Route.ConfirmPin -> now.stage == PinSetupStage.CONFIRM && chooser.step == PinChooser.Step.CONFIRM &&
                !now.saving && !now.saved && !now.saveFailed && !now.saveUnsure
            else -> false
        }
    }

    /** The proof of this flow: the one asked for here, or the unlock's (lock.md §8.2 "in the same flow"). */
    private fun proof(): MasterProof? = ownProof ?: (lock.state.value as? LockUiState.Unlocked)?.proof

    private fun choosePinNotice(): Notice.ChoosePin? =
        (lock.state.value as? LockUiState.Unlocked)?.notices?.firstNotNullOfOrNull { it as? Notice.ChoosePin }

    /** Saves the confirmed PIN with this flow's proof (lock.md F6 steps 4 to 8). */
    private fun save() {
        val pin = pending ?: return
        val proof = proof()
        if (proof == null) {
            // The proof went away (it should not, while the app is open): the master password first.
            wipePending()
            chooser.startAgain()
            state.update { it.copy(stage = PinSetupStage.MASTER, line = null, move = move(Route.ConfirmPin, NavStep.Back)) }
            return
        }
        val copy = pin.copyOf()
        pinWorkedBefore = lock.status.value.pin == PinStatus.On
        state.update { it.copy(saving = true, saveFailed = false, saveUnsure = false, line = null) }
        val saved = work.async {
            try {
                lock.setPin(copy, proof)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PinSetResult.NotSet
            } finally {
                copy.fill(0)
            }
        }
        viewModelScope.launch {
            val result = saved.await()
            if (state.value.stage != PinSetupStage.CONFIRM || !state.value.saving) return@launch
            when (result) {
                PinSetResult.Set -> {
                    wipePending()
                    state.update { it.copy(saving = false, saved = true) }
                    delay(SAVED_SHOWN_MS)
                    if (state.value.stage == PinSetupStage.CONFIRM) decideAfterPin(Route.ConfirmPin)
                }
                PinSetResult.MayNotBeSaved -> pinMayNotBeSaved()
                PinSetResult.NotSet -> {
                    val change = lock.status.value.pin == PinStatus.On
                    state.update {
                        it.copy(
                            saving = false,
                            saveFailed = true,
                            line = PinLine(UiText.of(if (change) R.string.pin_not_saved_change else R.string.pin_not_saved_new)),
                        )
                    }
                }
            }
        }
    }

    /**
     * After the PIN (saved, skipped, or not saved and skipped), from the step [from]: the
     * fingerprint offer when lock.md §9.4 says so, "Make backups" in setup, otherwise back to where
     * the user was (after F11 step 19, the new master password's screen). The PIN steps leave the
     * back stack.
     */
    private fun decideAfterPin(from: Route) {
        deciding = true
        viewModelScope.launch {
            val next = when {
                offerFingerprint() -> PinSetupStage.FINGERPRINT
                state.value.flow == PinFlow.SETUP -> PinSetupStage.BACKUPS
                else -> PinSetupStage.DONE
            }
            deciding = false
            val steps = buildList {
                if (from == Route.ConfirmPin) add(NavStep.Back)
                add(
                    when (next) {
                        PinSetupStage.FINGERPRINT -> NavStep.Replace(Route.FingerprintOffer)
                        PinSetupStage.BACKUPS -> NavStep.Replace(Route.BackupsExplained)
                        else -> NavStep.Back
                    },
                )
            }
            chooser.startAgain()
            state.update {
                it.copy(
                    stage = next,
                    entered = 0,
                    line = null,
                    saving = false,
                    saved = false,
                    saveFailed = false,
                    saveUnsure = false,
                    move = PinMove(++moves, from, steps),
                )
            }
        }
    }

    /** lock.md §9.4: offered when an offer belongs to this flow, a proof exists, fingerprint unlock is off, and `canAuthenticate` is 0. */
    private suspend fun offerFingerprint(): Boolean {
        if (state.value.flow == PinFlow.NEW_PIN || proof() == null) return false
        val fingerprint = lock.status.value.fingerprint
        if (fingerprint != FingerprintStatus.Off && fingerprint != FingerprintStatus.TurnOnAgain) return false
        val asked = canAuthenticate ?: return false
        return asked.await() == BIOMETRIC_SUCCESS
    }

    private fun afterFingerprint() {
        val setup = state.value.flow == PinFlow.SETUP
        state.update {
            it.copy(
                stage = if (setup) PinSetupStage.BACKUPS else PinSetupStage.DONE,
                fingerprintLine = null,
                move = move(Route.FingerprintOffer, if (setup) NavStep.Replace(Route.BackupsExplained) else NavStep.Back),
            )
        }
    }

    private fun fingerprintLine(text: Int) = state.update { it.copy(fingerprintLine = PinLine(UiText.of(text))) }

    private fun mismatch() = PinLine(UiText.of(R.string.pin_mismatch))

    private fun move(from: Route, vararg steps: NavStep) = PinMove(++moves, from, steps.toList())

    private fun wipePending() {
        pending?.fill(0)
        pending = null
    }

    private fun resetAll() {
        chooser.startAgain()
        wipePending()
        masterPassword.clear()
        ownProof?.drop()
        ownProof = null
        canAuthenticate?.cancel()
        canAuthenticate = null
        deciding = false
        state.value = PinSetupUi()
    }

    companion object {
        /** `BiometricManager.BIOMETRIC_SUCCESS`: the only answer of `canAuthenticate` that offers fingerprint at setup (lock.md §9.4). */
        const val BIOMETRIC_SUCCESS = 0

        /** The answer when the question itself failed; like every value the table does not name, it offers nothing. */
        const val CANNOT_ASK = Int.MIN_VALUE

        /** How long "Your PIN is set." stays before the next step. */
        const val SAVED_SHOWN_MS = 900L

        private val FIRST_STEPS_OR_DONE = setOf(
            PinSetupStage.START,
            PinSetupStage.MASTER,
            PinSetupStage.CHOOSE,
            PinSetupStage.CONFIRM,
            PinSetupStage.DONE,
        )

        /** The view model over the app's graph: its lock and settings, the fingerprint question of the Keystore adapter, the app's scope. */
        fun from(graph: AppGraph): PinSetupViewModel = PinSetupViewModel(
            lock = graph.lock,
            settings = graph.settings,
            clock = graph.platform.clock,
            availability = { graph.keys.fingerprint.availability() },
            dispatchers = graph.dispatchers,
            work = graph.appScope,
        )
    }
}

// ---- the routes -------------------------------------------------------------------------------

@Composable
private fun CreatePinRoute(graph: AppGraph, nav: Navigator) {
    val vm = pinSetupViewModel(graph)
    PinStepScope(Route.CreatePin, vm)
    LaunchedEffect(vm) { vm.start(under = routeUnderPinSteps(nav)) }
    PerformMoves(vm, Route.CreatePin, nav)
    val ui by vm.ui.collectAsStateWithLifecycle()
    BackHandler(enabled = ui.stage == PinSetupStage.MASTER || ui.stage == PinSetupStage.CHOOSE) { vm.notNow() }
    KeyboardFirstOnBack()
    if (ui.stage == PinSetupStage.MASTER) {
        MasterPasswordPage(ask = vm.masterPassword, onNext = vm::submitMasterPassword, onNotNow = vm::notNow)
    } else {
        CreatePinScreen(
            entered = if (ui.stage == PinSetupStage.CHOOSE) ui.entered else 0,
            line = ui.line.takeIf { ui.stage == PinSetupStage.CHOOSE },
            shakes = ui.shakes,
            onDigit = { vm.digit(it, Route.CreatePin) },
            onDelete = { vm.delete(Route.CreatePin) },
            onClear = { vm.clearEntry(Route.CreatePin) },
            onNotNow = vm::notNow,
            newPin = ui.newPin,
            someoneMayKnow = ui.someoneMayKnow,
        )
    }
}

@Composable
private fun ConfirmPinRoute(graph: AppGraph, nav: Navigator) {
    val vm = pinSetupViewModel(graph)
    PinStepScope(Route.ConfirmPin, vm)
    LaunchedEffect(vm) { vm.confirmShown() }
    PerformMoves(vm, Route.ConfirmPin, nav)
    val ui by vm.ui.collectAsStateWithLifecycle()
    BackHandler { vm.backFromConfirm() }
    ConfirmPinScreen(
        entered = if (ui.stage == PinSetupStage.CONFIRM) ui.entered else 0,
        line = ui.line.takeIf { ui.stage == PinSetupStage.CONFIRM },
        shakes = ui.shakes,
        saving = ui.saving,
        saved = ui.saved,
        saveFailed = ui.saveFailed,
        onDigit = { vm.digit(it, Route.ConfirmPin) },
        onDelete = { vm.delete(Route.ConfirmPin) },
        onClear = { vm.clearEntry(Route.ConfirmPin) },
        onRetry = vm::retrySave,
        onNotNow = vm::skipAfterFailure,
        saveUnsure = ui.saveUnsure,
        onOk = vm::pinUnsureOk,
    )
}

@Composable
private fun FingerprintOfferRoute(graph: AppGraph, nav: Navigator) {
    val vm = pinSetupViewModel(graph)
    PinStepScope(Route.FingerprintOffer, vm)
    LaunchedEffect(vm) { vm.fingerprintShown() }
    PerformMoves(vm, Route.FingerprintOffer, nav)
    val ui by vm.ui.collectAsStateWithLifecycle()
    BackHandler(enabled = !ui.fingerprintBusy) { vm.fingerprintNotNow() }
    FingerprintOfferScreen(busy = ui.fingerprintBusy, line = ui.fingerprintLine, onUse = vm::useFingerprint, onNotNow = vm::fingerprintNotNow)
}

@Composable
private fun BackupsExplainedRoute(graph: AppGraph, nav: Navigator) {
    val vm = pinSetupViewModel(graph)
    PinStepScope(Route.BackupsExplained, vm)
    PerformMoves(vm, Route.BackupsExplained, nav)
    val ui by vm.ui.collectAsStateWithLifecycle()
    val remind by vm.backupReminder.collectAsStateWithLifecycle()
    BackHandler { vm.finish() }
    BackupsExplainedScreen(remind = remind, line = ui.backupLine, onRemindChange = vm::setBackupReminder, onFinish = vm::finish)
}

/**
 * The route the PIN steps were opened over: the one under [Route.CreatePin] on the unlocked back
 * stack (`Route.NewMasterPassword` after F11 step 19, [PinFlow.AFTER_NEW_MASTER_PASSWORD]), or null
 * when [nav] is not the app's [NavState] or nothing lies under the step.
 */
internal fun routeUnderPinSteps(nav: Navigator): Route? {
    val stack = (nav as? NavState)?.stack(NavState.Top.UNLOCKED) ?: return null
    val at = stack.lastIndexOf(Route.CreatePin)
    return if (at > 0) stack[at - 1] else null
}

/**
 * The view model of the PIN steps, kept by the activity rather than by one route's entry, so that
 * the steps share it and a recreation keeps it. Outside an activity (a test's host) it falls back to
 * the composition's owner.
 */
@Composable
internal fun pinSetupViewModel(graph: AppGraph): PinSetupViewModel {
    val owner = LocalActivity.current as? ViewModelStoreOwner ?: checkNotNull(LocalViewModelStoreOwner.current)
    return viewModel(viewModelStoreOwner = owner, key = PIN_SETUP_VIEW_MODEL_KEY) { PinSetupViewModel.from(graph) }
}

/**
 * Ties the step [route] to the back-stack entry of its route: Navigation 3 clears this entry's view
 * models when the route leaves the back stack (not at a rotation, and not while a predictive Back is
 * only being previewed), and [PinSetupViewModel.stepLeft] then drops what the step held.
 */
@Composable
internal fun PinStepScope(route: Route, vm: PinSetupViewModel) {
    viewModel(key = PIN_STEP_KEY) { PinStepViewModel(route, vm) }
}

/** The entry's own view model of [PinStepScope]. */
internal class PinStepViewModel(private val route: Route, private val vm: PinSetupViewModel) : ViewModel() {
    override fun onCleared() {
        vm.stepLeft(route)
    }
}

/** Performs the moves that [vm] asks of the step [from], once each, then reports them. */
@Composable
internal fun PerformMoves(vm: PinSetupViewModel, from: Route, nav: Navigator) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val move = ui.move
    LaunchedEffect(move) {
        if (move == null || move.from != from) return@LaunchedEffect
        for (step in move.steps) {
            if (!step.applyTo(nav)) break
        }
        vm.moved(move)
    }
}

private const val PIN_SETUP_VIEW_MODEL_KEY = "pv.pinSetup"
private const val PIN_STEP_KEY = "pv.pinSetup.step"
