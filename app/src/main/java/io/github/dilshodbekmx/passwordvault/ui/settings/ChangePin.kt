package io.github.dilshodbekmx.passwordvault.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.i18n.resolve
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.MpPurpose
import io.github.dilshodbekmx.passwordvault.lock.PinChange
import io.github.dilshodbekmx.passwordvault.lock.PinSetResult
import io.github.dilshodbekmx.passwordvault.lock.PinStatus
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.ui.common.PIN_LENGTH
import io.github.dilshodbekmx.passwordvault.ui.setup.BodyText
import io.github.dilshodbekmx.passwordvault.ui.setup.BusyCover
import io.github.dilshodbekmx.passwordvault.ui.setup.CreatePinScreen
import io.github.dilshodbekmx.passwordvault.ui.setup.MasterPasswordAsk
import io.github.dilshodbekmx.passwordvault.ui.setup.MasterPasswordInput
import io.github.dilshodbekmx.passwordvault.ui.setup.PinChooser
import io.github.dilshodbekmx.passwordvault.ui.setup.PinDigits
import io.github.dilshodbekmx.passwordvault.ui.setup.PinLine
import io.github.dilshodbekmx.passwordvault.ui.setup.PinStepPage
import io.github.dilshodbekmx.passwordvault.ui.setup.PinTags
import io.github.dilshodbekmx.passwordvault.ui.setup.StatusLine
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Settings › App lock › "Change PIN" or "Set a PIN" (ux.md §5.18; lock.md F6, §8.2), one route with
 * its steps:
 *
 * - **Change PIN** (a PIN works, or its check only failed for now) starts with "Enter your current
 *   PIN", a counted check that the lock makes when the new PIN is saved (`LockManager.changePin`), or
 *   "Use master password instead" (`MpPurpose.CHANGE_PIN`). Then the new PIN, which must differ from
 *   the current one when that was typed, and once more. A wrong current PIN says how many tries are
 *   left and asks for it again, keeping the new PIN; at the limit the PIN is turned off, and the
 *   master password sets the new one ("Type master password", the "Set" branch); a PIN that cannot be
 *   checked offers the master password.
 * - **Set a PIN** (no PIN works) asks for the master password first (`MpPurpose.SET_PIN`): it adds a
 *   way in, so nothing less will do, and no proof from an earlier unlock counts here (§8.2: "There is
 *   no time window in which settings are open"). Then the new PIN and once more, saved with
 *   `LockManager.setPin`.
 *
 * A fingerprint never stands in for either. The digits are held as bytes and zeroed as soon as they
 * are not needed; a proof asked for here is dropped when the route ends. The lock's calls run in
 * [work], the app's scope, so that leaving the screen cannot cut the lock's records in half.
 *
 * When the lock cannot say whether the new PIN was saved (lock.md F6 step 8; build-plan.md §12,
 * decision 4: `PinSetResult.MayNotBeSaved`, `PinChange.MayNotBeSaved`), the route promises nothing:
 * [pinMayNotBeSaved] says what to try next time.
 */
class ChangePinViewModel(
    private val lock: LockManager,
    clock: Clock,
    private val work: CoroutineScope,
) : ViewModel() {

    /** Whether the route changes a working PIN or sets one where none works. */
    enum class Mode { CHANGE, SET }

    /** The step shown. */
    enum class Stage {
        /** "Enter your current PIN". */
        CURRENT,

        /** "Type your master password". */
        MASTER,

        /** The new PIN. */
        CHOOSE,

        /** "Enter the PIN again". */
        CONFIRM,

        /** "Saving…": the lock checks the current PIN and saves the new one. */
        SAVING,

        /** The current PIN was wrong too many times and the PIN is off: "Type master password" or "Later". */
        LIMIT,

        /** The current PIN cannot be checked now: "Use master password instead" or "Later". */
        CANNOT_CHECK,

        /** The new PIN could not be saved: "Try again" or "Later". */
        NOT_SAVED,

        /** The lock cannot say whether the new PIN was saved: what to try next time, and "OK". */
        MAY_NOT_BE_SAVED,

        /** This phone cannot keep a PIN key in secure hardware (lock.md F18). */
        UNAVAILABLE,

        /** Saved: the route closes, and Settings says so. */
        DONE,
    }

    /** The master-password step. */
    val masterPassword = MasterPasswordAsk(lock, clock, MpPurpose.SET_PIN, work, viewModelScope)

    private val chooser = PinChooser(lock::pinProblem)
    private val currentDigits = PinDigits()
    private var current: ByteArray? = null
    private var newPin: ByteArray? = null
    private var proof: MasterProof? = null
    private var mpPurpose = MpPurpose.SET_PIN
    private var firstPin = false

    private val state: MutableStateFlow<ChangePinUi>

    /** What the route shows. */
    val ui: StateFlow<ChangePinUi>

    init {
        val pin = lock.status.value.pin
        val mode = if (pin == PinStatus.On || pin == PinStatus.CannotCheck) Mode.CHANGE else Mode.SET
        firstPin = pin == PinStatus.NotSet
        val stage = when {
            pin == PinStatus.NoSecureHardware -> Stage.UNAVAILABLE
            mode == Mode.CHANGE -> Stage.CURRENT
            else -> Stage.MASTER
        }
        state = MutableStateFlow(ChangePinUi(mode = mode, stage = stage))
        ui = state.asStateFlow()
        // A lock ends the route: nothing typed or proved stays behind.
        viewModelScope.launch {
            lock.state.collect { if (it !is LockUiState.Unlocked) dropAll() }
        }
    }

    // ---- the pad ---------------------------------------------------------------------------------

    /** A digit of the pad shown now (current, new or again). */
    fun digit(digit: Int) {
        val now = state.value
        when (now.stage) {
            Stage.CURRENT -> {
                if (!currentDigits.add(digit)) {
                    state.update { it.copy(entered = currentDigits.count) }
                    return
                }
                wipeCurrent()
                current = currentDigits.take()
                state.update { it.copy(entered = 0) }
                if (newPin != null) save() else state.update { it.copy(stage = Stage.CHOOSE, line = null) }
            }
            Stage.CHOOSE, Stage.CONFIRM -> chooserDigit(digit)
            else -> Unit
        }
    }

    /** ⌫ */
    fun delete() {
        when (state.value.stage) {
            Stage.CURRENT -> currentDigits.delete()
            Stage.CHOOSE, Stage.CONFIRM -> chooser.delete()
            else -> return
        }
        state.update { it.copy(entered = enteredNow()) }
    }

    /** A long press on ⌫: the entry is cleared. */
    fun clearEntry() {
        when (state.value.stage) {
            Stage.CURRENT -> currentDigits.clear()
            Stage.CHOOSE, Stage.CONFIRM -> chooser.clearEntry()
            else -> return
        }
        state.update { it.copy(entered = 0) }
    }

    // ---- the master password ---------------------------------------------------------------------

    /**
     * "Use master password instead" on the current-PIN step, or after the PIN could not be checked:
     * the master password replaces the current PIN (`MpPurpose.CHANGE_PIN`).
     */
    fun useMasterPassword() {
        val stage = state.value.stage
        if (stage != Stage.CURRENT && stage != Stage.CANNOT_CHECK) return
        currentDigits.clear()
        wipeCurrent()
        mpPurpose = MpPurpose.CHANGE_PIN
        masterPassword.clear()
        state.update { it.copy(stage = Stage.MASTER, entered = 0, line = null) }
    }

    /** "Type master password" after the limit turned the PIN off: the "Set" branch (`MpPurpose.SET_PIN`). */
    fun typeMasterPassword() {
        if (state.value.stage != Stage.LIMIT) return
        wipeCurrent()
        mpPurpose = MpPurpose.SET_PIN
        masterPassword.clear()
        state.update { it.copy(stage = Stage.MASTER, line = null, setAfterLimit = true) }
    }

    /** "Next" on the master-password step: a right one is this route's proof; the new PIN follows, or is saved when it was chosen already. */
    fun submitMasterPassword() {
        if (state.value.stage != Stage.MASTER) return
        masterPassword.submit(mpPurpose) { right ->
            if (state.value.stage != Stage.MASTER) {
                right.drop()
                return@submit
            }
            proof?.drop()
            proof = right
            if (newPin != null) save() else state.update { it.copy(stage = Stage.CHOOSE, line = null, entered = 0) }
        }
    }

    // ---- results ---------------------------------------------------------------------------------

    /** "Try again" after the new PIN could not be saved. */
    fun retry() {
        if (state.value.stage == Stage.NOT_SAVED && newPin != null) save()
    }

    /**
     * The lock cannot say whether the new PIN was saved (lock.md F6 step 8; build-plan.md §12,
     * decision 4): the route says "The new PIN may not have been saved. Next time try the new PIN
     * first. If it is not accepted, use the old PIN or your master password." (without the old PIN
     * where none works), and "OK" closes it. Nothing typed or proved stays, and nothing is tried
     * again here: the lock already works from the record it read back, so the PIN typed as the
     * current one may no longer be it.
     *
     * Called for the answer of `LockManager.setPin` or `changePin` that says so
     * (`PinSetResult.MayNotBeSaved`, `PinChange.MayNotBeSaved`), while the save is running.
     */
    internal fun pinMayNotBeSaved() {
        val now = state.value
        if (now.stage != Stage.SAVING) return
        val text = if (now.mode == Mode.CHANGE && !now.setAfterLimit) R.string.pin_may_not_be_saved else R.string.pin_may_not_be_saved_first
        dropAll()
        state.update { it.copy(stage = Stage.MAY_NOT_BE_SAVED, entered = 0, line = PinLine(UiText.of(text))) }
    }

    /** "Later", or Back: the route closes; nothing changes. */
    fun leave() {
        if (state.value.stage == Stage.SAVING) return
        dropAll()
        state.update { it.copy(leave = true) }
    }

    /** Back on the step "Enter the PIN again": the new PIN is chosen again. */
    fun backFromConfirm() {
        if (state.value.stage != Stage.CONFIRM) return
        chooser.startAgain()
        state.update { it.copy(stage = Stage.CHOOSE, entered = 0, line = null) }
    }

    /** The route closed, as [ChangePinUi.leave] asked. */
    fun left() = state.update { it.copy(leave = false) }

    override fun onCleared() {
        dropAll()
    }

    // ---- inside ----------------------------------------------------------------------------------

    private fun chooserDigit(digit: Int) {
        when (val result = chooser.digit(digit, current)) {
            PinChooser.Result.Typing -> state.update { it.copy(entered = chooser.digits.count) }
            is PinChooser.Result.Refused -> refused(PinChooser.refusal(result.reason))
            PinChooser.Result.SameAsCurrent -> refused(UiText.of(R.string.pin_same_as_current))
            PinChooser.Result.ToConfirm -> state.update { it.copy(stage = Stage.CONFIRM, entered = 0, line = null) }
            PinChooser.Result.Mismatch -> state.update { it.copy(entered = 0, line = mismatch(), shakes = it.shakes + 1) }
            PinChooser.Result.StartAgain -> state.update { it.copy(stage = Stage.CHOOSE, entered = 0, line = mismatch(), shakes = it.shakes + 1) }
            is PinChooser.Result.Confirmed -> {
                wipeNew()
                newPin = result.pin
                state.update { it.copy(entered = 0) }
                save()
            }
        }
    }

    private fun refused(text: UiText) = state.update { it.copy(entered = 0, line = PinLine(text), shakes = it.shakes + 1) }

    /**
     * Saves the new PIN: with the master password of this route (`setPin`), or with the current PIN
     * typed here (`changePin`, a counted check of it first).
     */
    private fun save() {
        val pin = newPin ?: return
        val withProof = proof
        val withCurrent = current
        if (withProof == null && withCurrent == null) {
            state.update { it.copy(stage = if (it.mode == Mode.CHANGE) Stage.CURRENT else Stage.MASTER) }
            return
        }
        val pinCopy = pin.copyOf()
        val currentCopy = withCurrent?.copyOf()
        state.update { it.copy(stage = Stage.SAVING, line = null, entered = 0) }
        val saved = work.async {
            try {
                if (withProof != null) {
                    when (lock.setPin(pinCopy, withProof)) {
                        PinSetResult.Set -> PinChange.Changed
                        PinSetResult.NotSet -> PinChange.NotSaved
                        PinSetResult.MayNotBeSaved -> PinChange.MayNotBeSaved
                    }
                } else {
                    lock.changePin(checkNotNull(currentCopy), pinCopy)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PinChange.NotSaved
            } finally {
                pinCopy.fill(0)
                currentCopy?.fill(0)
            }
        }
        viewModelScope.launch { afterSave(saved.await(), withProof != null) }
    }

    private fun afterSave(result: PinChange, withMasterPassword: Boolean) {
        if (state.value.stage != Stage.SAVING) return
        when (result) {
            PinChange.Changed -> {
                val set = state.value.mode == Mode.SET || state.value.setAfterLimit
                dropAll()
                SettingsNotice.post(UiText.of(if (set) R.string.pin_set else R.string.pin_changed))
                state.update { it.copy(stage = Stage.DONE, leave = true) }
            }
            is PinChange.Wrong -> {
                wipeCurrent()
                state.update {
                    it.copy(stage = Stage.CURRENT, line = PinLine(UiText.plural(R.plurals.pin_wrong_left, result.left)), shakes = it.shakes + 1)
                }
            }
            PinChange.SameWrongAgain -> {
                wipeCurrent()
                state.update { it.copy(stage = Stage.CURRENT, line = PinLine(UiText.of(R.string.pin_same_wrong_again)), shakes = it.shakes + 1) }
            }
            PinChange.Limit -> {
                wipeCurrent()
                state.update { it.copy(stage = Stage.LIMIT, line = null) }
            }
            PinChange.CannotCheck -> {
                wipeCurrent()
                state.update { it.copy(stage = Stage.CANNOT_CHECK, line = null) }
            }
            PinChange.SameAsCurrent -> {
                wipeNew()
                chooser.startAgain()
                state.update { it.copy(stage = Stage.CHOOSE, line = PinLine(UiText.of(R.string.pin_same_as_current)), shakes = it.shakes + 1) }
            }
            PinChange.NotSaved -> {
                val text = if (state.value.mode == Mode.CHANGE && !state.value.setAfterLimit) R.string.pin_not_saved_change else R.string.pin_not_saved_new
                if (!withMasterPassword) wipeCurrent()
                state.update { it.copy(stage = Stage.NOT_SAVED, line = PinLine(UiText.of(text))) }
            }
            PinChange.MayNotBeSaved -> pinMayNotBeSaved()
        }
    }

    private fun enteredNow(): Int = if (state.value.stage == Stage.CURRENT) currentDigits.count else chooser.digits.count

    private fun mismatch() = PinLine(UiText.of(R.string.pin_mismatch))

    private fun wipeCurrent() {
        current?.fill(0)
        current = null
    }

    private fun wipeNew() {
        newPin?.fill(0)
        newPin = null
    }

    private fun dropAll() {
        currentDigits.clear()
        chooser.startAgain()
        wipeCurrent()
        wipeNew()
        proof?.drop()
        proof = null
        masterPassword.clear()
    }

    /** Whether the new PIN is this phone's first (the title "Choose a 6-digit PIN" instead of "Choose a new PIN"). */
    val choosesFirstPin: Boolean get() = firstPin

    companion object {
        /** The view model over the app's graph. */
        fun from(graph: AppGraph): ChangePinViewModel = ChangePinViewModel(graph.lock, graph.platform.clock, graph.appScope)
    }
}

/**
 * What "Change PIN" or "Set a PIN" shows. Never a digit.
 *
 * @property entered how many digits of the entry shown are typed.
 * @property line the line under the dots: a refused PIN, a mismatch, a wrong current PIN, a PIN
 *   that could not be saved.
 * @property shakes grows with each refused or wrong entry: the dots shake.
 * @property setAfterLimit the master password sets the new PIN after the limit turned the old one off.
 * @property leave the route closes.
 */
data class ChangePinUi(
    val mode: ChangePinViewModel.Mode,
    val stage: ChangePinViewModel.Stage,
    val entered: Int = 0,
    val line: PinLine? = null,
    val shakes: Int = 0,
    val setAfterLimit: Boolean = false,
    val leave: Boolean = false,
)

/**
 * "Change PIN" or "Set a PIN" (ux.md §5.18, §5.6; lock.md F6): a top bar with Back and the title,
 * then the step: the current PIN, the master password, the new PIN, the new PIN again, "Saving…",
 * or what the lock said (the limit, a check that cannot be made now, a PIN that could not be saved,
 * a PIN that may not have been saved).
 */
@OptIn(ExperimentalMaterial3Api::class) // TopAppBar's signature names TopAppBarScrollBehavior (ux.md §10)
@Composable
fun ChangePinScreen(vm: ChangePinViewModel, nav: Navigator, modifier: Modifier = Modifier) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ui.leave) {
        if (ui.leave) {
            vm.left()
            nav.back()
        }
    }
    BackHandler(enabled = ui.stage == ChangePinViewModel.Stage.SAVING) { /* ignored while the lock saves (ux.md §4) */ }
    BackHandler(enabled = ui.stage == ChangePinViewModel.Stage.CONFIRM) { vm.backFromConfirm() }
    val title = stringResource(if (ui.mode == ChangePinViewModel.Mode.CHANGE) R.string.settings_change_pin else R.string.settings_set_pin)
    Scaffold(
        modifier = modifier.semantics {
            paneTitle = title
            testTagsAsResourceId = true
        },
        topBar = {
            TopAppBar(
                title = { Text(title, modifier = Modifier.semantics { heading() }) },
                navigationIcon = { BackArrow(nav, enabled = ui.stage != ChangePinViewModel.Stage.SAVING) },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            ChangePinStep(vm, ui)
        }
    }
}

@Composable
private fun ChangePinStep(vm: ChangePinViewModel, ui: ChangePinUi) {
    when (ui.stage) {
        ChangePinViewModel.Stage.CURRENT -> PinStepPage(
            title = stringResource(R.string.pin_current_title),
            entered = ui.entered,
            onDigit = vm::digit,
            onDelete = vm::delete,
            onClear = vm::clearEntry,
            line = ui.line,
            shakes = ui.shakes,
            buttons = {
                TextButton(onClick = vm::useMasterPassword, modifier = Modifier.fillMaxWidth().testTag(PinTags.USE_MASTER_PASSWORD)) {
                    Text(stringResource(R.string.pin_use_master_password))
                }
            },
        )
        ChangePinViewModel.Stage.MASTER -> MessagePage(title = stringResource(R.string.pin_mp_title)) {
            BodyText(stringResource(R.string.pin_mp_body))
            MasterPasswordInput(ask = vm.masterPassword, onNext = vm::submitMasterPassword, focusFirst = true)
            val ask by vm.masterPassword.ui.collectAsStateWithLifecycle()
            Button(onClick = vm::submitMasterPassword, enabled = ask.enabled, modifier = Modifier.fillMaxWidth().testTag(PinTags.MP_NEXT)) {
                Text(stringResource(R.string.pin_mp_next))
            }
        }
        ChangePinViewModel.Stage.CHOOSE -> CreatePinScreen(
            entered = ui.entered,
            line = ui.line,
            shakes = ui.shakes,
            onDigit = vm::digit,
            onDelete = vm::delete,
            onClear = vm::clearEntry,
            onNotNow = null,
            newPin = !vm.choosesFirstPin,
        )
        ChangePinViewModel.Stage.CONFIRM, ChangePinViewModel.Stage.SAVING -> {
            val saving = ui.stage == ChangePinViewModel.Stage.SAVING
            val savingText = stringResource(R.string.pin_saving)
            PinStepPage(
                title = stringResource(R.string.pin_confirm_title),
                entered = if (saving) PIN_LENGTH else ui.entered,
                onDigit = vm::digit,
                onDelete = vm::delete,
                onClear = vm::clearEntry,
                line = ui.line,
                shakes = ui.shakes,
                cover = if (saving) ({ BusyCover(savingText) }) else null,
                body = { BodyText(stringResource(R.string.pin_confirm_body)) },
            )
        }
        ChangePinViewModel.Stage.LIMIT -> MessagePage(title = stringResource(R.string.settings_change_pin)) {
            AlertText(stringResource(R.string.pin_limit))
            Button(onClick = vm::typeMasterPassword, modifier = Modifier.fillMaxWidth().testTag(PinTags.TYPE_MASTER_PASSWORD)) {
                Text(stringResource(R.string.pin_type_master_password))
            }
            LaterButton(vm)
        }
        ChangePinViewModel.Stage.CANNOT_CHECK -> MessagePage(title = stringResource(R.string.settings_change_pin)) {
            AlertText(stringResource(R.string.pin_cannot_check))
            Button(onClick = vm::useMasterPassword, modifier = Modifier.fillMaxWidth().testTag(PinTags.USE_MASTER_PASSWORD)) {
                Text(stringResource(R.string.pin_use_master_password))
            }
            LaterButton(vm)
        }
        ChangePinViewModel.Stage.NOT_SAVED -> MessagePage(title = stringResource(R.string.pin_confirm_title)) {
            ui.line?.let { StatusLine(it) }
            Button(onClick = vm::retry, modifier = Modifier.fillMaxWidth().testTag(PinTags.RETRY)) {
                Text(stringResource(R.string.common_try_again))
            }
            LaterButton(vm)
        }
        ChangePinViewModel.Stage.MAY_NOT_BE_SAVED -> MessagePage(title = stringResource(R.string.pin_confirm_title)) {
            ui.line?.let { StatusLine(it) }
            Button(onClick = vm::leave, modifier = Modifier.fillMaxWidth().testTag(PinTags.OK)) {
                Text(stringResource(R.string.common_ok))
            }
        }
        ChangePinViewModel.Stage.UNAVAILABLE -> MessagePage(title = stringResource(R.string.settings_set_pin)) {
            AlertText(stringResource(R.string.settings_no_secure_hardware))
            LaterButton(vm)
        }
        ChangePinViewModel.Stage.DONE -> Unit
    }
}

/** A step without a pad: a title and its content in a column that scrolls, at most 640 dp wide. */
@Composable
private fun MessagePage(title: String, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() }.testTag(PinTags.TITLE))
            content()
        }
    }
}

/** What the lock said, announced when it appears. */
@Composable
private fun AlertText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.testTag(PinTags.LINE).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun LaterButton(vm: ChangePinViewModel) {
    TextButton(onClick = vm::leave, modifier = Modifier.fillMaxWidth().testTag(PinTags.LATER)) {
        Text(stringResource(R.string.common_later))
    }
}
