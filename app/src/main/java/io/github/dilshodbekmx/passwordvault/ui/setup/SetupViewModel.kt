package io.github.dilshodbekmx.passwordvault.ui.setup

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.crypto.MasterPasswordBytes
import io.github.dilshodbekmx.passwordvault.core.generator.Passphrase
import io.github.dilshodbekmx.passwordvault.core.strength.MasterPasswordCheck
import io.github.dilshodbekmx.passwordvault.core.strength.MasterPasswordRules
import io.github.dilshodbekmx.passwordvault.core.strength.StrengthHint
import io.github.dilshodbekmx.passwordvault.core.util.RandomSource
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.lock.CreateResult
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.settings.AppSettings
import io.github.dilshodbekmx.passwordvault.settings.OneTime
import io.github.dilshodbekmx.passwordvault.ui.common.clearSecret
import io.github.dilshodbekmx.passwordvault.ui.common.readSecret
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Setup's three steps before the vault exists (ux.md §5.3 to §5.5; lock.md F1 steps 1 to 3):
 * "Choose your master password", "Write it down on paper" and "Type your master password again".
 * One view model serves the three screens, because the password chosen in the first is shown by the
 * second and checked by the third; `SetupNav.kt` keeps it in the activity, so that a rotation or a
 * language change keeps it (lock.md §11), and each screen tells it through [stepLeft] when that
 * screen has left the back stack, so that what the screen held goes with it (architecture.md §5.1).
 *
 * **Secrets.** The two fields are [TextFieldState]s held here, never in saved state (architecture.md
 * §5.6); the chosen password is a `CharArray` that is zeroed when it is dropped. The suggested words
 * are never stored (ux.md §5.5): a new process has none, and the screens after the first then go
 * back to it ([SetupState.chosen] is null), where new words appear. Nothing of the password reaches
 * [SetupState.toString].
 *
 * **"Setup was interrupted."** [OneTime.WORDS_SHOWN] is set when words are first shown and cleared
 * when a vault exists. A view model that finds it already set when it is made, or that dropped words
 * it had shown, shows its new words with that text (ux.md §5.5).
 *
 * **Threads.** Every function is called on the main thread. Words and the checklist are made on
 * `default` (the word list and zxcvbn's lists are read on first use), settings are written on `io`,
 * and the lock derives the key itself.
 *
 * @param rules the checklist of an own password: [MasterPasswordRules.check] with the app's strength
 *   meter; a test may pass another.
 */
class SetupViewModel(
    private val lock: LockManager,
    private val settings: AppSettings,
    private val random: RandomSource,
    private val dispatchers: AppDispatchers,
    private val rules: (CharArray) -> MasterPasswordCheck = { MasterPasswordRules.check(it) },
) : ViewModel() {

    /** The field of a master password of one's own (ux.md §5.3, "Own password view"). */
    val own = TextFieldState()

    /** The field of "Type your master password again" (ux.md §5.5). */
    val again = TextFieldState()

    private val mutableState = MutableStateFlow(SetupState(interrupted = OneTime.WORDS_SHOWN in settings.oneTime.value))

    /** What the three screens show. */
    val state: StateFlow<SetupState> = mutableState.asStateFlow()

    /** The chosen password, shared with [SetupState.chosen]; zeroed by [dropChosen]. */
    private var chosen: CharArray? = null

    /** Words were shown by this view model, and no vault came of them yet. */
    private var wordsShown = false

    private var suggesting: Job? = null
    private var checking: Job? = null
    private var meterWarm = false

    /** The text "Use this password" last judged: an edit that leaves it unchanged keeps the message. */
    private var judgedText: CharSequence? = null

    init {
        viewModelScope.launch {
            lock.state.collect { state ->
                when (state) {
                    is LockUiState.Unlocked -> leaveSetup(vaultExists = true)
                    is LockUiState.Locked, is LockUiState.Unreadable -> leaveSetup(vaultExists = false)
                    LockUiState.Starting, is LockUiState.NoVault -> Unit
                }
            }
        }
    }

    // ---- Step 1: "Choose your master password" ------------------------------------------------

    /** The first suggestion, made once; the first step calls it when it is shown. */
    fun showWords() {
        if (mutableState.value.passphrase == null) suggest()
    }

    /** "Show other words": six new words (ux.md §5.3). */
    fun showOtherWords() = suggest()

    /** "Use these words": they become the chosen password. False while no words are shown yet. */
    fun useTheseWords(): Boolean {
        val passphrase = mutableState.value.passphrase ?: return false
        choose(passphrase.words, passphrase.toCharArray())
        return true
    }

    /** "Type my own instead": the field, its strength meter and the checklist. */
    fun typeOwn() {
        mutableState.update { it.copy(view = CreateView.OWN) }
        if (!meterWarm) {
            meterWarm = true
            // The strength meter reads its word lists on first use: start now, not at the first key.
            viewModelScope.launch(dispatchers.default) { rules(CharArray(0)) }
        }
        if (checking == null) checking = viewModelScope.launch { checkWhileTyping() }
    }

    /** Back to the suggested words from the own password's view. */
    fun showSuggested() {
        mutableState.update { it.copy(view = CreateView.SUGGESTED, askReuse = false) }
        showWords()
    }

    /**
     * "Use this password" (ux.md §5.3): the checklist of the text now, on the characters as they
     * become bytes (architecture.md §4.6). When an item is missing it says which; when all pass it
     * asks whether the password is used anywhere else ([SetupState.askReuse]).
     */
    fun submitOwn() {
        val chars = own.readSecret()
        val text = own.text
        viewModelScope.launch {
            val check = try {
                withContext(dispatchers.default) { rules(chars) }
            } finally {
                chars.fill(ZERO)
            }
            val missing = when {
                check.unassignedCharacter -> UiText.of(R.string.setup_own_unassigned)
                !check.longEnough -> UiText.of(R.string.setup_own_missing_length)
                !check.hardToGuess -> UiText.of(R.string.setup_own_missing_hard)
                else -> null
            }
            judgedText = text
            mutableState.update { it.copy(check = check, ownMessage = missing, askReuse = missing == null) }
        }
    }

    /**
     * The answer to "Do you use this password for any other account?": "Yes" ([usedElsewhere])
     * keeps the user at the field with "Then choose a different one. …"; "No, it's new" makes it the
     * chosen password. True when step 2 follows.
     */
    fun answerReuse(usedElsewhere: Boolean): Boolean {
        if (!mutableState.value.askReuse) return false
        if (usedElsewhere) {
            judgedText = own.text
            mutableState.update { it.copy(askReuse = false, ownMessage = UiText.of(R.string.setup_own_reuse_refused)) }
            return false
        }
        mutableState.update { it.copy(askReuse = false, ownMessage = null) }
        choose(null, own.readSecret())
        return true
    }

    /** The question was closed without an answer (Back, a tap outside it): nothing changes. */
    fun dismissReuse() {
        mutableState.update { it.copy(askReuse = false) }
    }

    // ---- Step 2: "Write it down on paper" -----------------------------------------------------

    /** The optional "I wrote it down" (ux.md §5.4). */
    fun setWroteItDown(wrote: Boolean) {
        mutableState.update { it.copy(wroteItDown = wrote) }
    }

    // ---- Step 3: "Type your master password again" --------------------------------------------

    /**
     * "Next" or the keyboard's Done (ux.md §5.5). A typing that differs is "This doesn't match. …",
     * with the Cyrillic-keyboard hint when it has Cyrillic letters and the password has none, and
     * "Show my password again" from the third on; the field is emptied for the next try. The same
     * password makes the vault through the lock, which compares the two typings again before it
     * derives anything, writes the vault, proves it from disk with the second typing and unlocks
     * (lock.md F1 step 3); the app then leaves setup by itself. "Couldn't save. …" and "not enough
     * memory" keep the field, so "Try again" repeats the same typings.
     */
    fun submitAgain() {
        if (mutableState.value.settingUp) return
        val first = chosen ?: return
        val typed = again.readSecret()
        if (!MasterPasswordBytes.same(first, typed)) {
            val wrongKeyboard = MasterPasswordRules.containsCyrillic(typed) && !MasterPasswordRules.containsCyrillic(first)
            typed.fill(ZERO)
            mismatch(wrongKeyboard)
            return
        }
        mutableState.update { it.copy(settingUp = true, againMessage = null, keyboardHint = null, canRetry = false) }
        viewModelScope.launch {
            val copy = first.copyOf()
            PinSetupStart.newVault() // the PIN steps after it end with "Make backups" (decision 24)
            var made = false
            val result = try {
                lock.createVault(copy, typed, restoreFrom = null).also { made = it is CreateResult.Created }
            } finally {
                copy.fill(ZERO)
                typed.fill(ZERO)
                if (!made) PinSetupStart.takeNewVault() // no vault: the mark goes again
            }
            when (result) {
                is CreateResult.Created -> created()
                CreateResult.Mismatch -> mismatch(wrongKeyboard = false)
                is CreateResult.Unassigned -> failed(UiText.of(R.string.setup_own_unassigned), retry = false)
                CreateResult.NoMemory -> failed(UiText.of(R.string.setup_again_no_memory), retry = true)
                CreateResult.NotSaved -> failed(UiText.of(R.string.setup_again_not_saved), retry = true)
            }
        }
    }

    // ---- Leaving -------------------------------------------------------------------------------

    /**
     * The screen of [step] has left the back stack (its own entry's view models were cleared; a
     * rotation does not clear them): what it held is dropped. Back from step 1 keeps the suggested
     * words for a second "Start" in the same activity; the own password, the chosen password and
     * the second typing go.
     */
    fun stepLeft(step: SetupStep) {
        when (step) {
            SetupStep.CREATE -> {
                checking?.cancel()
                checking = null
                own.clearSecret()
                judgedText = null
                dropChosen()
                mutableState.update { it.copy(view = CreateView.SUGGESTED, check = null, ownMessage = null, askReuse = false) }
            }
            SetupStep.WRITE_DOWN -> {
                dropChosen()
                mutableState.update { it.copy(wroteItDown = false) }
            }
            SetupStep.TYPE_AGAIN -> {
                again.clearSecret()
                mutableState.update { it.copy(mismatches = 0, againMessage = null, keyboardHint = null, canRetry = false) }
            }
        }
    }

    override fun onCleared() {
        wipe()
    }

    // ---- Internals -----------------------------------------------------------------------------

    private fun suggest() {
        if (suggesting?.isActive == true) return
        suggesting = viewModelScope.launch {
            val next = withContext(dispatchers.default) { Passphrase.suggest(random) }
            mutableState.update { it.copy(passphrase = next) }
            wordsShown = true
            if (OneTime.WORDS_SHOWN !in settings.oneTime.value) {
                withContext(dispatchers.io) { settings.update { it.oneTime = it.oneTime + OneTime.WORDS_SHOWN } }
            }
        }
    }

    /**
     * The checklist follows the field (ux.md §5.3): judged on `default` a moment after the last key,
     * so that zxcvbn does not run for every key of a fast typist. An edit clears the message of the
     * last "Use this password".
     */
    private suspend fun checkWhileTyping() {
        snapshotFlow { own.text }
            .distinctUntilChanged { old, new -> old.contentEquals(new) }
            .collectLatest { text ->
                if (judgedText?.contentEquals(text) == false) {
                    judgedText = null
                    mutableState.update { it.copy(ownMessage = null) }
                }
                if (text.isEmpty()) {
                    mutableState.update { it.copy(check = null) }
                    return@collectLatest
                }
                delay(CHECK_AFTER_MS)
                val chars = own.readSecret()
                val check = try {
                    withContext(dispatchers.default) { rules(chars) }
                } finally {
                    chars.fill(ZERO)
                }
                mutableState.update { it.copy(check = check) }
            }
    }

    private fun choose(words: List<String>?, chars: CharArray) {
        dropChosen()
        chosen = chars
        mutableState.update { it.copy(chosen = ChosenPassword(words, chars), wroteItDown = false) }
    }

    private fun dropChosen() {
        chosen?.fill(ZERO)
        chosen = null
        mutableState.update { it.copy(chosen = null) }
    }

    private fun mismatch(wrongKeyboard: Boolean) {
        again.clearSecret()
        mutableState.update {
            it.copy(
                settingUp = false,
                mismatches = it.mismatches + 1,
                againMessage = UiText.of(R.string.setup_again_mismatch),
                keyboardHint = if (wrongKeyboard) UiText.of(R.string.setup_again_cyrillic) else null,
                canRetry = false,
            )
        }
    }

    private fun failed(message: UiText, retry: Boolean) {
        mutableState.update { it.copy(settingUp = false, againMessage = message, keyboardHint = null, canRetry = retry) }
    }

    /** The vault exists and the lock has unlocked it: setup is over (lock.md F1 step 4 is the lock's). */
    private fun created() {
        leaveSetup(vaultExists = true)
    }

    /**
     * The app left setup: a vault was made or opened ([vaultExists]: the flag of words shown is
     * cleared), or the lock screen of a vault put aside is shown (setup may come back, and then shows
     * new words as an interrupted setup if this one had shown some).
     */
    private fun leaveSetup(vaultExists: Boolean) {
        val interrupted = !vaultExists && (wordsShown || mutableState.value.interrupted)
        wipe()
        wordsShown = false
        mutableState.value = SetupState(interrupted = interrupted)
        if (vaultExists && OneTime.WORDS_SHOWN in settings.oneTime.value) {
            viewModelScope.launch(dispatchers.io) { settings.update { it.oneTime = it.oneTime - OneTime.WORDS_SHOWN } }
        }
    }

    private fun wipe() {
        suggesting?.cancel()
        suggesting = null
        checking?.cancel()
        checking = null
        judgedText = null
        own.clearSecret()
        again.clearSecret()
        dropChosen()
    }

    companion object {
        /** "Show my password again" appears with the third mismatch (ux.md §5.5). */
        const val SHOW_AGAIN_AFTER = 3

        /** The pause after the last key before the checklist is judged again. */
        const val CHECK_AFTER_MS = 150L

        private const val ZERO = '\u0000'
    }
}

/** The hint under the strength meter for [hint] (ux.md §5.3). */
fun strengthHintText(hint: StrengthHint): UiText = UiText.of(
    when (hint) {
        StrengthHint.ADD_WORD -> R.string.setup_own_hint_add_word
        StrengthHint.AVOID_NAMES_AND_YEARS -> R.string.setup_own_hint_names
        StrengthHint.AVOID_KEYBOARD_ROWS -> R.string.setup_own_hint_keyboard
    },
)

/** The steps of setup that hold something: what [SetupViewModel.stepLeft] drops. */
enum class SetupStep { CREATE, WRITE_DOWN, TYPE_AGAIN }

/** The two views of "Choose your master password" (ux.md §5.3). */
enum class CreateView {
    /** Six suggested words, the default. */
    SUGGESTED,

    /** A password the user types, with the strength meter and the checklist. */
    OWN,
}

/**
 * The master password chosen in step 1, which "Write it down on paper" shows (ux.md §5.4) and "Type
 * your master password again" checks. [words] are the suggested words in order, null for a password
 * of one's own. Its characters stay in the view model, which zeroes them when it drops the choice;
 * [text] makes the string a screen shows. Its [toString] names nothing of it.
 */
class ChosenPassword internal constructor(val words: List<String>?, private val chars: CharArray) {
    /** The password as it is typed, for the screen. */
    fun text(): String = String(chars)

    override fun toString(): String = "ChosenPassword(${if (words != null) "words" else "own"}, hidden)"
}

/**
 * What the setup screens show. It holds facts and resource texts (architecture.md §5.4), and of the
 * password only [passphrase] and [chosen], whose `toString` hide it.
 */
data class SetupState(
    /** The suggested words; null until the first are made. */
    val passphrase: Passphrase? = null,
    /** "Setup was interrupted. …" above the words (ux.md §5.5). */
    val interrupted: Boolean = false,
    /** Suggested words or a password of one's own. */
    val view: CreateView = CreateView.SUGGESTED,
    /** The checklist of the own password as it is now; null while the field is empty. */
    val check: MasterPasswordCheck? = null,
    /** Under the own password's field: what "Use this password" found missing, or the answer to "Yes". */
    val ownMessage: UiText? = null,
    /** "Do you use this password for any other account?" is open. */
    val askReuse: Boolean = false,
    /** The password chosen in step 1; null before, and in a new process (the screens after step 1 then go back). */
    val chosen: ChosenPassword? = null,
    /** "I wrote it down" (optional). */
    val wroteItDown: Boolean = false,
    /** Mismatches on "Type your master password again" since the screen opened. */
    val mismatches: Int = 0,
    /** Under the second typing's field: the mismatch, or why the vault was not made. */
    val againMessage: UiText? = null,
    /** "Your keyboard may be set to Russian. …" with the last mismatch. */
    val keyboardHint: UiText? = null,
    /** "Setting up…": the lock is making the vault; Back is ignored meanwhile (ux.md §4). */
    val settingUp: Boolean = false,
    /** The vault was not made for a reason another try may fix: the button says "Try again". */
    val canRetry: Boolean = false,
) {
    /** "Show my password again", from the third mismatch on. */
    val canShowAgain: Boolean get() = mismatches >= SetupViewModel.SHOW_AGAIN_AFTER

    /** The hint under the own password's strength meter, while it is not hard to guess. */
    val strengthHint: UiText? get() = check?.strength?.hint?.let(::strengthHintText)

    override fun toString(): String =
        "SetupState(view=$view, words=${passphrase != null}, interrupted=$interrupted, check=$check, ownMessage=$ownMessage, " +
            "askReuse=$askReuse, chosen=$chosen, wroteItDown=$wroteItDown, mismatches=$mismatches, againMessage=$againMessage, " +
            "keyboardHint=$keyboardHint, settingUp=$settingUp, canRetry=$canRetry)"
}
