package io.github.dilshodbekmx.passwordvault.ui.setup

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.lock.pin.PinBlocklist
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.i18n.resolve
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.lock.MpPurpose
import io.github.dilshodbekmx.passwordvault.lock.MpResult
import io.github.dilshodbekmx.passwordvault.ui.common.KeypadColumn
import io.github.dilshodbekmx.passwordvault.ui.common.PIN_LENGTH
import io.github.dilshodbekmx.passwordvault.ui.common.PinDots
import io.github.dilshodbekmx.passwordvault.ui.common.PinKeys
import io.github.dilshodbekmx.passwordvault.ui.common.VaultFieldDefaults
import io.github.dilshodbekmx.passwordvault.ui.common.VaultSecureField
import io.github.dilshodbekmx.passwordvault.ui.common.clearSecret
import io.github.dilshodbekmx.passwordvault.ui.common.fadeWhereMore
import io.github.dilshodbekmx.passwordvault.ui.common.readSecret
import io.github.dilshodbekmx.passwordvault.ui.common.rememberFieldKeyboard
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// "Choose a 6-digit PIN" (ux.md §5.6; lock.md §7) and the parts that the PIN steps of setup and
// Settings › App lock › "Change PIN" or "Set a PIN" share (build plan 4.3): the digits typed on the
// pad ([PinDigits]), the choice of a new PIN with its confirmation ([PinChooser]), the
// master-password step before a PIN is set or fingerprint unlock is turned on ([MasterPasswordAsk]),
// and the page that holds a pad ([PinStepPage]).

/**
 * The digits typed on a PIN pad, held as the ASCII bytes `'0'` to `'9'` (lock.md §4.2) and never as
 * a `String`, so that no copy of a PIN is left in a string pool (architecture.md §5.6). Nothing of
 * it reaches saved state: the pad shows only [count]. Main thread only.
 */
class PinDigits {
    private val bytes = ByteArray(PIN_LENGTH)

    /** How many digits are typed, 0 to 6. */
    var count: Int = 0
        private set

    /** Whether six digits are typed. */
    val full: Boolean get() = count == PIN_LENGTH

    /** Adds [digit] (0 to 9) unless six are typed already; true when this made six. */
    fun add(digit: Int): Boolean {
        require(digit in 0..9) { "a PIN digit is 0 to 9" }
        if (full) return false
        bytes[count++] = (ZERO + digit).toByte()
        return full
    }

    /** ⌫: removes the last digit. */
    fun delete() {
        if (count == 0) return
        count--
        bytes[count] = 0
    }

    /** Removes every digit and zeroes them. */
    fun clear() {
        bytes.fill(0)
        count = 0
    }

    /** A copy of the six digits; the digits here are cleared. The caller wipes the copy. */
    fun take(): ByteArray {
        check(full) { "six digits are needed" }
        val copy = bytes.copyOf()
        clear()
        return copy
    }

    private companion object {
        const val ZERO = 0x30
    }
}

/**
 * The choice of a new PIN (lock.md §7.2, §7.3; ux.md §5.6): six digits checked against the
 * blocklist ([problem], `LockManager.pinProblem`), then the same six again. A refused PIN clears the
 * dots and keeps the pad; in "Change PIN" a new PIN equal to the current one typed in the same flow
 * is refused too. After [MAX_MISMATCHES] mismatches the choice starts again from the first entry.
 * Choosing a PIN is not an unlock, so nothing here is counted by the lock.
 *
 * It keeps the chosen PIN until it is confirmed ([Result.Confirmed] hands it over) or dropped
 * ([startAgain]); every array it no longer needs is zeroed. Main thread only.
 */
class PinChooser(private val problem: (ByteArray) -> PinBlocklist.Reason?) {
    /** The two entries of the choice. */
    enum class Step { CHOOSE, CONFIRM }

    /** What a digit led to. */
    sealed interface Result {
        /** Fewer than six digits so far. */
        data object Typing : Result

        /** A PIN was chosen: "Enter the PIN again" follows. */
        data object ToConfirm : Result

        /** The six digits are refused for [reason] (lock.md §7.2); the dots are cleared. */
        data class Refused(val reason: PinBlocklist.Reason) : Result

        /** The new PIN equals the current one typed in this flow: "Choose a PIN different from your current one." */
        data object SameAsCurrent : Result

        /** The second entry differs from the first: "The PINs don't match. Try again." */
        data object Mismatch : Result

        /** The [MAX_MISMATCHES]th mismatch: the first entry is dropped and the choice starts again. */
        data object StartAgain : Result

        /** Both entries match: [pin] is the new PIN, which the caller now owns and wipes. */
        class Confirmed(val pin: ByteArray) : Result {
            override fun toString(): String = "Confirmed"
        }
    }

    /** The entry being typed. */
    var step: Step = Step.CHOOSE
        private set

    /** The digits of the entry being typed. */
    val digits = PinDigits()

    /** How many second entries did not match the first since it was chosen. */
    var mismatches: Int = 0
        private set

    private var chosen: ByteArray? = null

    /**
     * A digit of the pad. When it is the sixth, the entry is decided: [current] is the current PIN
     * typed earlier in a "Change PIN" (lock.md F6 step 3), which the new one must differ from; null
     * when there is none.
     */
    fun digit(digit: Int, current: ByteArray? = null): Result {
        if (!digits.add(digit)) return Result.Typing
        val typed = digits.take()
        return when (step) {
            Step.CHOOSE -> choose(typed, current)
            Step.CONFIRM -> confirm(typed)
        }
    }

    /** ⌫ */
    fun delete() = digits.delete()

    /** A long press on ⌫: the entry is cleared. */
    fun clearEntry() = digits.clear()

    /** Drops the first entry and the digits: the choice starts again ("after 3 mismatches", Back, a lock). */
    fun startAgain() {
        chosen?.fill(0)
        chosen = null
        mismatches = 0
        step = Step.CHOOSE
        digits.clear()
    }

    private fun choose(typed: ByteArray, current: ByteArray?): Result {
        val reason = problem(typed)
        if (reason != null) {
            typed.fill(0)
            return Result.Refused(reason)
        }
        if (current != null && MessageDigest.isEqual(typed, current)) {
            typed.fill(0)
            return Result.SameAsCurrent
        }
        chosen?.fill(0)
        chosen = typed
        mismatches = 0
        step = Step.CONFIRM
        return Result.ToConfirm
    }

    private fun confirm(typed: ByteArray): Result {
        val first = chosen ?: run {
            typed.fill(0)
            startAgain()
            return Result.StartAgain
        }
        if (MessageDigest.isEqual(typed, first)) {
            typed.fill(0)
            chosen = null
            mismatches = 0
            step = Step.CHOOSE
            return Result.Confirmed(first)
        }
        typed.fill(0)
        mismatches++
        if (mismatches >= MAX_MISMATCHES) {
            startAgain()
            return Result.StartAgain
        }
        return Result.Mismatch
    }

    companion object {
        /** "After 3 mismatches the user starts again from the first entry" (ux.md §5.6). */
        const val MAX_MISMATCHES = 3

        /** The message of lock.md §7.3 for a refused PIN: dates and years; repeats and patterns; sequences and keypad shapes. */
        fun refusal(reason: PinBlocklist.Reason): UiText = UiText.of(
            when (reason) {
                PinBlocklist.Reason.DATE -> R.string.pin_refused_date
                PinBlocklist.Reason.REPEAT, PinBlocklist.Reason.PATTERN, PinBlocklist.Reason.STEPS -> R.string.pin_refused_pattern
                PinBlocklist.Reason.SEQUENCE, PinBlocklist.Reason.KEYPAD -> R.string.pin_refused_sequence
            },
        )
    }
}

/**
 * The line under the dots of a PIN step (a refused PIN, a mismatch, a wrong current PIN) or under a
 * master-password field. [alert]: something went wrong, drawn in the error colour; otherwise a
 * neutral line ("Saving…", "Your PIN is set.").
 */
data class PinLine(val text: UiText, val alert: Boolean = true)

/**
 * What the master-password step shows (lock.md §8.2, §6.8, §13.3): [checking] while the lock checks
 * the password, the [line] after a try, and during a wait the seconds left ([secondsLeft]), while the
 * field is off. Nothing typed is ever in it.
 */
data class MasterPasswordAskUi(
    val checking: Boolean = false,
    val line: PinLine? = null,
    val waitUntilElapsedMs: Long? = null,
    val secondsLeft: Int? = null,
) {
    /** The field and its button take input: nothing is checked and nothing waits. */
    val enabled: Boolean get() = !checking && secondsLeft == null
}

/**
 * The master password asked in the open app before a way in is added (lock.md §8.2: "Set a PIN when
 * there is none; turn fingerprint on: typed in the same flow"), or instead of the current PIN in
 * "Change PIN". [submit] hands the typed characters to `LockManager.submitMasterPassword` for
 * [purpose], under the lock's guard (§6.8): a right one gives the `MasterProof` of this flow, which
 * the caller keeps until its flow ends and then drops; a wrong one, a wait or too little memory is
 * shown in [ui].
 *
 * [field] is held here, never in saved state (lock.md §5.8, architecture.md §5.6): [submit] empties it
 * with its undo history at once, and [clear] when the step ends. The check runs in [checkScope], the
 * app's scope, so that leaving the screen cannot cut the lock's records in half; the countdown of a
 * wait runs in [uiScope].
 */
class MasterPasswordAsk(
    private val lock: LockManager,
    private val clock: Clock,
    private val purpose: MpPurpose,
    private val checkScope: CoroutineScope,
    private val uiScope: CoroutineScope,
) {
    /** The master-password field. Read and emptied by [submit] only. */
    val field = TextFieldState()

    private val state = MutableStateFlow(MasterPasswordAskUi())

    /** What the step shows. */
    val ui: StateFlow<MasterPasswordAskUi> = state.asStateFlow()

    private var countdown: Job? = null

    /**
     * "Next" (or the keyboard's Done): when nothing is checked, nothing waits and something was
     * typed, the characters go to the lock for [purpose] and the field is emptied. [onRight] receives
     * the proof of a right master password on the main thread.
     */
    fun submit(purpose: MpPurpose = this.purpose, onRight: (MasterProof) -> Unit) {
        val now = state.value
        if (!now.enabled || field.text.isEmpty()) return
        val chars = field.readSecret()
        field.clearSecret()
        countdown?.cancel()
        state.value = MasterPasswordAskUi(checking = true)
        checkScope.launch {
            val result = try {
                lock.submitMasterPassword(chars, purpose)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // the lock failed in a way it does not report: nothing was proved
            } finally {
                chars.fill('\u0000')
            }
            when (result) {
                is MpResult.Right -> {
                    state.value = MasterPasswordAskUi()
                    onRight(result.proof)
                }
                MpResult.Wrong, is MpResult.OlderPassword -> state.value = MasterPasswordAskUi(line = PinLine(UiText.of(R.string.pin_mp_wrong)))
                is MpResult.Wait -> startWait(result.untilElapsedMs)
                MpResult.NoMemory -> state.value = MasterPasswordAskUi(line = PinLine(UiText.of(R.string.pin_mp_no_memory)))
                MpResult.TooLate, MpResult.NotFinished, null -> state.value = MasterPasswordAskUi() // NotFinished: the lock screen's answer only
            }
        }
    }

    /** Empties the field and its undo history and forgets the last answer: the step ended or the app locked. */
    fun clear() {
        field.clearSecret()
        countdown?.cancel()
        countdown = null
        state.value = MasterPasswordAskUi()
    }

    /** "Wait 30 seconds, then try again." and a countdown of the seconds left; the field is off until then. */
    private fun startWait(untilElapsedMs: Long) {
        countdown?.cancel()
        val waitLine = PinLine(UiText.of(R.string.pin_mp_wait), alert = false)
        fun show(now: Long) {
            state.value = MasterPasswordAskUi(line = waitLine, waitUntilElapsedMs = untilElapsedMs, secondsLeft = secondsLeft(untilElapsedMs - now))
        }
        val now = clock.elapsedMs()
        if (now >= untilElapsedMs) {
            state.value = MasterPasswordAskUi()
            return
        }
        show(now)
        countdown = uiScope.launch {
            while (true) {
                val left = untilElapsedMs - clock.elapsedMs()
                if (left <= 0) break
                clock.delayUntilElapsed(untilElapsedMs - (secondsLeft(left) - 1) * SECOND_MS)
                val at = clock.elapsedMs()
                if (at >= untilElapsedMs) break
                show(at)
            }
            state.value = MasterPasswordAskUi()
        }
    }

    private companion object {
        const val SECOND_MS = 1_000L

        /** Whole seconds left, rounded up: 30 at the start of the wait, 1 in its last second. */
        fun secondsLeft(ms: Long): Int = ((ms + SECOND_MS - 1) / SECOND_MS).toInt()
    }
}

/**
 * The resource ids (test tags) of the PIN steps and the master-password step, shared by setup and
 * Settings: tests and the emulator scripts find each part by them, whatever the language. The pad's
 * own dots and keys are `PinPadTags`.
 */
object PinTags {
    const val TITLE = "pv.pin.title"
    const val BODY = "pv.pin.body"
    const val TALKBACK = "pv.pin.talkback"
    const val LINE = "pv.pin.line"
    const val FOOTER = "pv.pin.footer"
    const val BUSY = "pv.pin.busy"
    const val DONE = "pv.pin.done"
    const val NOT_NOW = "pv.pin.notNow"
    const val RETRY = "pv.pin.retry"
    const val OK = "pv.pin.ok"
    const val USE_MASTER_PASSWORD = "pv.pin.useMasterPassword"
    const val TYPE_MASTER_PASSWORD = "pv.pin.typeMasterPassword"
    const val LATER = "pv.pin.later"
    const val MP_FIELD = "pv.pin.mp.field"
    const val MP_NEXT = "pv.pin.mp.next"
    const val MP_LINE = "pv.pin.mp.line"
    const val MP_COUNTDOWN = "pv.pin.mp.countdown"
    const val MP_CHECKING = "pv.pin.mp.checking"
}

/**
 * "Choose a 6-digit PIN" (ux.md §5.6, lock.md §7.3), or "Choose a new PIN" after the PIN was turned
 * off, forgotten or stopped working ([newPin]): the three lines that say which PINs to avoid before
 * the user types (with the headphones sentence while TalkBack is on), the dots and the pad, the line
 * of a refused PIN, the footer about wrong tries, and "Not now".
 *
 * @param someoneMayKnow adds "Someone else may know the old one." (lock.md F16b step 6).
 */
@Composable
fun CreatePinScreen(
    entered: Int,
    line: PinLine?,
    shakes: Int,
    onDigit: (Int) -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    onNotNow: (() -> Unit)?,
    modifier: Modifier = Modifier,
    newPin: Boolean = false,
    someoneMayKnow: Boolean = false,
    title: String = stringResource(if (newPin) R.string.pin_create_new_title else R.string.pin_create_title),
) {
    val talkBack = spokenFeedbackOn()
    PinStepPage(
        title = title,
        entered = entered,
        onDigit = onDigit,
        onDelete = onDelete,
        onClear = onClear,
        modifier = modifier,
        line = line,
        shakes = shakes,
        footer = stringResource(R.string.pin_create_footer),
        body = {
            if (someoneMayKnow) BodyText(stringResource(R.string.pin_create_someone_may_know))
            BodyText(stringResource(R.string.pin_create_body_use))
            BodyText(stringResource(R.string.pin_create_body_avoid))
            BodyText(stringResource(R.string.pin_create_body_others))
            if (talkBack) BodyText(stringResource(R.string.pin_create_talkback), Modifier.testTag(PinTags.TALKBACK))
        },
        buttons = {
            if (onNotNow != null) {
                TextButton(onClick = onNotNow, modifier = Modifier.fillMaxWidth().testTag(PinTags.NOT_NOW)) {
                    Text(stringResource(R.string.common_not_now))
                }
            }
        },
    )
}

/**
 * A page with a PIN pad (ux.md §5.6, §5.9): the title, the [body] lines, the dots, the [line] under
 * them, the keypad (or [cover] in its place: "Saving…" with a spinner, [BusyCover], or "Your PIN is
 * set." with a check mark, [DoneCover]), the [footer] and the [buttons]. Upright, at most 640 dp
 * wide, a [KeypadColumn]: the dots and the whole keypad stay on the screen together, with the title,
 * the body and the footer whole above them where the page allows; on a short page or with a large
 * font the buttons go below the first screen, then the keys shrink to 64 dp, and only then do the
 * texts take the height left above the dots and scroll there (build plan 6.2: in `en-XA` on a 1080 ×
 * 1920 screen the keys 7, 8, 9 and 0 were below the screen's edge). In a wide and low window (a
 * phone in landscape) the texts and the dots on the left and the keypad on the right (ux.md §5.9
 * "Landscape"). With large fonts the keys grow as far as the width allows; nothing is cut off.
 *
 * The dots shake (unless animations are off) with a reject haptic each time [shakes] grows: a
 * refused PIN, a mismatch, a wrong current PIN. A hardware keyboard's digits and Backspace work on
 * the pad.
 */
@Composable
fun PinStepPage(
    title: String,
    entered: Int,
    onDigit: (Int) -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    line: PinLine? = null,
    shakes: Int = 0,
    footer: String? = null,
    cover: (@Composable () -> Unit)? = null,
    body: @Composable ColumnScope.() -> Unit = {},
    buttons: @Composable ColumnScope.() -> Unit = {},
) {
    val texts: @Composable ColumnScope.() -> Unit = {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() }.testTag(PinTags.TITLE),
        )
        Column(Modifier.testTag(PinTags.BODY), verticalArrangement = Arrangement.spacedBy(8.dp)) { body() }
    }
    val dots: @Composable ColumnScope.() -> Unit = {
        PinDots(entered = entered, wrongTries = shakes, modifier = Modifier.align(Alignment.CenterHorizontally))
        StatusLine(line)
    }
    val keys: @Composable () -> Unit = {
        if (cover == null) PinKeys(onDigit = onDigit, onDelete = onDelete, onClear = onClear) else cover()
    }
    val footerText: @Composable ColumnScope.() -> Unit = {
        if (footer != null) {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(PinTags.FOOTER),
            )
        }
    }
    val end: @Composable ColumnScope.() -> Unit = {
        footerText()
        buttons()
    }
    // The page has no text field: a keyboard left open by the step before (the master password)
    // goes, so that it covers nothing.
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(keyboard) { keyboard?.hide() }
    // The layout is chosen from the page's whole size, not from the part above a keyboard, so that a
    // keyboard still closing never turns a phone held upright into the landscape layout.
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .semantics {
                paneTitle = title
                testTagsAsResourceId = true
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        val landscape = maxWidth > maxHeight && maxHeight < LANDSCAPE_MAX_HEIGHT
        if (landscape) {
            Row(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    texts()
                    dots()
                    end()
                }
                Box(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    keys()
                }
            }
        } else {
            // Upright: the keypad and the dots stay whole on the screen together; the texts above them
            // (with the footer) take the height that is left and scroll there (KeypadColumn).
            BoxWithConstraints(Modifier.safeDrawingPadding().widthIn(max = 640.dp).fillMaxWidth()) {
                val viewport = maxHeight
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    KeypadColumn(
                        viewport = viewport,
                        head = {
                            val scroll = rememberScrollState()
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fadeWhereMore(scroll, MaterialTheme.colorScheme.surface)
                                    .verticalScroll(scroll)
                                    .padding(start = 24.dp, end = 24.dp, top = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                texts()
                                footerText()
                            }
                        },
                        dots = {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                dots()
                            }
                        },
                        keys = { Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) { keys() } },
                        foot = {
                            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp)) { buttons() }
                        },
                    )
                }
            }
        }
    }
}

/** In place of the keypad while something is saved or checked: a spinner and [text] ("Saving…"). */
@Composable
fun BusyCover(text: String) {
    Row(
        modifier = Modifier.padding(vertical = 24.dp).testTag(PinTags.BUSY).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(24.dp))
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** In place of the keypad once a PIN was saved: a check mark and [text] ("Your PIN is set."). */
@Composable
fun DoneCover(text: String) {
    Row(
        modifier = Modifier.padding(vertical = 24.dp).testTag(PinTags.DONE).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_common_check), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** A line of a step's body text. */
@Composable
internal fun BodyText(text: String, modifier: Modifier = Modifier) {
    Text(text = text, style = MaterialTheme.typography.bodyLarge, modifier = modifier)
}

/**
 * The line under the dots or under a field: what went wrong in the error colour, or a neutral line.
 * A polite live region, so that TalkBack reads it when it appears. While there is none, the space
 * stays, so that the pad does not jump.
 */
@Composable
internal fun StatusLine(line: PinLine?, tag: String = PinTags.LINE) {
    Text(
        text = line?.text?.resolve().orEmpty(),
        style = MaterialTheme.typography.bodyMedium,
        color = if (line?.alert == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        minLines = 2,
        modifier = Modifier.fillMaxWidth().testTag(tag).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * The master password asked before a PIN is chosen (lock.md §8.2, F5 step 4, F6 "Set"): "Type your
 * master password", why, the field, "Next" and, when the step may be skipped, "Not now". The field
 * takes the focus and the keyboard when the page appears.
 */
@Composable
fun MasterPasswordPage(
    ask: MasterPasswordAsk,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    body: String = stringResource(R.string.pin_mp_body),
    onNotNow: (() -> Unit)? = null,
    title: String = stringResource(R.string.pin_mp_title),
) {
    SetupPage(title = title, modifier = modifier.semantics { testTagsAsResourceId = true }) {
        BodyText(body)
        MasterPasswordInput(ask = ask, onNext = onNext, focusFirst = true)
        val ui by ask.ui.collectAsStateWithLifecycle()
        Button(onClick = onNext, enabled = ui.enabled, modifier = Modifier.fillMaxWidth().testTag(PinTags.MP_NEXT)) {
            Text(stringResource(R.string.pin_mp_next))
        }
        if (onNotNow != null) {
            TextButton(onClick = onNotNow, enabled = !ui.checking, modifier = Modifier.fillMaxWidth().testTag(PinTags.NOT_NOW)) {
                Text(stringResource(R.string.common_not_now))
            }
        }
    }
}

/**
 * The master-password field with its lines (lock.md §13.3): "Checking…" while the lock checks it, a
 * wrong password, the wait and its countdown, too little memory. The keyboard's Done does what
 * [onNext] does. [focusFirst]: the field takes the focus and the keyboard when it appears.
 */
@Composable
fun MasterPasswordInput(ask: MasterPasswordAsk, onNext: () -> Unit, focusFirst: Boolean, modifier: Modifier = Modifier) {
    val ui by ask.ui.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    // The keyboard asked for as the other master-password fields ask for it (FieldKeyboard): Android 17
    // no longer brings it back after a rotation, and Gboard may close it again by itself.
    val fieldKeyboard = rememberFieldKeyboard()
    var focused by remember { mutableStateOf(false) }
    if (focusFirst) {
        LaunchedEffect(focus, fieldKeyboard) {
            fieldKeyboard.awaitWindowFocus()
            try {
                focus.requestFocus()
            } catch (_: IllegalStateException) {
                return@LaunchedEffect
            }
            fieldKeyboard.show { focused }
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        VaultSecureField(
            state = ask.field,
            modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { focused = it.isFocused }.testTag(PinTags.MP_FIELD),
            label = stringResource(R.string.pin_mp_label),
            enabled = ui.enabled,
            isError = ui.line?.alert == true,
            keyboardOptions = VaultFieldDefaults.Secret.copy(imeAction = ImeAction.Done),
            onKeyboardAction = KeyboardActionHandler { onNext() },
        )
        if (ui.checking) {
            Row(
                modifier = Modifier.testTag(PinTags.MP_CHECKING).semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(text = stringResource(R.string.pin_mp_checking), style = MaterialTheme.typography.bodyLarge)
            }
        }
        ui.line?.let { StatusLine(it, PinTags.MP_LINE) }
        ui.secondsLeft?.let { left ->
            Text(
                text = pluralStringResource(R.plurals.pin_mp_countdown, left, left),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(PinTags.MP_COUNTDOWN),
            )
        }
    }
}

/**
 * Whether TalkBack or another spoken-feedback service is on (ux.md §5.6: the headphones sentence),
 * and again whenever that changes while the screen is shown.
 */
@Composable
internal fun spokenFeedbackOn(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager }
    var on by remember(manager) { mutableStateOf(manager.speaks()) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.AccessibilityStateChangeListener { on = manager.speaks() }
        val touch = AccessibilityManager.TouchExplorationStateChangeListener { on = manager.speaks() }
        manager?.addAccessibilityStateChangeListener(listener)
        manager?.addTouchExplorationStateChangeListener(touch)
        onDispose {
            manager?.removeAccessibilityStateChangeListener(listener)
            manager?.removeTouchExplorationStateChangeListener(touch)
        }
    }
    return on
}

private fun AccessibilityManager?.speaks(): Boolean {
    if (this == null || !isEnabled) return false
    return isTouchExplorationEnabled || getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isNotEmpty()
}

/** The keypad goes beside the texts in a window lower than this that is wider than high. */
private val LANDSCAPE_MAX_HEIGHT = 480.dp
