package io.github.dilshodbekmx.passwordvault.ui.settings

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.crypto.MasterPasswordBytes
import io.github.dilshodbekmx.passwordvault.core.generator.Passphrase
import io.github.dilshodbekmx.passwordvault.core.strength.MasterPasswordCheck
import io.github.dilshodbekmx.passwordvault.core.strength.MasterPasswordRules
import io.github.dilshodbekmx.passwordvault.core.util.RandomSource
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.ui.common.clearSecret
import io.github.dilshodbekmx.passwordvault.ui.common.readSecret
import io.github.dilshodbekmx.passwordvault.ui.setup.ChosenPassword
import io.github.dilshodbekmx.passwordvault.ui.setup.CreateView
import io.github.dilshodbekmx.passwordvault.ui.setup.SetupState
import kotlinx.coroutines.CoroutineScope
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
 * Choosing the new master password of a change or a replacement (lock.md F8 step 2, F11 step 4;
 * ux.md §5.18 step 2, §5.24 step 3): the three steps of setup, "Choose your master password", "Write
 * it down on paper" and "Type your master password again" (ux.md §5.3 to §5.5), with the same rules
 * and texts, drawn by setup's own screens from [state]. What differs from setup's `SetupViewModel`:
 * no vault is made here (the owner of this object hands the two typings to the lock), nothing is
 * written to the settings (setup's "Setup was interrupted" mark belongs to setup alone), and the lock
 * being open is the normal case, not the end of the flow.
 *
 * **Secrets.** The two fields are [TextFieldState]s held here, never in saved state (architecture.md
 * §5.6). The chosen password and the second typing are `CharArray`s, zeroed when they are dropped
 * ([dropChosen], [clearAgain], [wipe]); [typings] hands out copies, which the caller zeroes. The
 * suggested words are never stored (ux.md §5.5). Nothing of a password reaches [SetupState.toString].
 *
 * **Threads.** Every function is called on the main thread; the words and the checklist are made
 * on `default` in [scope], the owner's ViewModel scope.
 *
 * @param rules the checklist of an own password ([MasterPasswordRules.check] with the app's strength
 *   meter); a test may pass another.
 */
class NewMasterPasswordChoice(
    private val scope: CoroutineScope,
    private val random: RandomSource,
    private val dispatchers: AppDispatchers,
    private val rules: (CharArray) -> MasterPasswordCheck = { MasterPasswordRules.check(it) },
) {
    /** The field of a master password of one's own (ux.md §5.3, "Own password view"). */
    val own = TextFieldState()

    /** The field of "Type your master password again" (ux.md §5.5). */
    val again = TextFieldState()

    private val mutableState = MutableStateFlow(SetupState())

    /** What setup's three screens show for this choice. */
    val state: StateFlow<SetupState> = mutableState.asStateFlow()

    /** The chosen password, shared with [SetupState.chosen]; zeroed by [dropChosen]. */
    private var chosen: CharArray? = null

    /** The second typing, once it matched [chosen]: the lock proves the new copy with it (lock.md F8 step 6). */
    private var typedAgain: CharArray? = null

    private var suggesting: Job? = null
    private var checking: Job? = null
    private var meterWarm = false

    /** The text "Use this password" last judged: an edit that leaves it unchanged keeps the message. */
    private var judgedText: CharSequence? = null

    /** Whether a password is chosen and was typed again to match it: [typings] can be made. */
    val typed: Boolean get() = chosen != null && typedAgain != null

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
            scope.launch(dispatchers.default) { rules(CharArray(0)) }
        }
        if (checking == null) checking = scope.launch { checkWhileTyping() }
    }

    /** Back to the suggested words from the own password's view. */
    fun showSuggested() {
        mutableState.update { it.copy(view = CreateView.SUGGESTED, askReuse = false) }
        showWords()
    }

    /**
     * "Use this password" (ux.md §5.3): the checklist of the text now. When an item is missing it
     * says which; when all pass it asks whether the password is used anywhere else.
     */
    fun submitOwn() {
        val chars = own.readSecret()
        val text = own.text
        scope.launch {
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

    /**
     * The lock refused the chosen password for a character Unicode has not assigned (lock.md §8.1):
     * back on the first step, the own password's view says so, and the choice is dropped.
     */
    fun refuseUnassigned() {
        dropChosen()
        clearAgain()
        judgedText = own.text
        mutableState.update { it.copy(view = CreateView.OWN, ownMessage = UiText.of(R.string.setup_own_unassigned), askReuse = false) }
    }

    // ---- Step 2: "Write it down on paper" -----------------------------------------------------

    /** The optional "I wrote it down" (ux.md §5.4). */
    fun setWroteItDown(wrote: Boolean) {
        mutableState.update { it.copy(wroteItDown = wrote) }
    }

    // ---- Step 3: "Type your master password again" --------------------------------------------

    /**
     * "Next" or the keyboard's Done (ux.md §5.5): true when the typing matches the chosen password
     * (as `MasterPasswordBytes` turns both into bytes), and it is kept for [typings]. A typing that
     * differs is "This doesn't match. …", with the Cyrillic-keyboard hint when it has Cyrillic letters
     * and the password has none, and "Show my password again" from the third on; the field is
     * emptied for the next try.
     */
    fun submitAgain(): Boolean {
        val first = chosen ?: return false
        val typedNow = again.readSecret()
        if (!MasterPasswordBytes.same(first, typedNow)) {
            val wrongKeyboard = MasterPasswordRules.containsCyrillic(typedNow) && !MasterPasswordRules.containsCyrillic(first)
            typedNow.fill(ZERO)
            mismatch(wrongKeyboard)
            return false
        }
        typedAgain?.fill(ZERO)
        typedAgain = typedNow
        again.clearSecret()
        mutableState.update { it.copy(againMessage = null, keyboardHint = null) }
        return true
    }

    /** The lock found that the two typings differ after all (it compares them again): as a mismatch here. */
    fun mismatchFromLock() {
        typedAgain?.fill(ZERO)
        typedAgain = null
        mismatch(wrongKeyboard = false)
    }

    /**
     * Copies of the chosen password and of the second typing, for the lock; null unless both are
     * there ([typed]). The caller zeroes both arrays.
     */
    fun typings(): Pair<CharArray, CharArray>? {
        val first = chosen ?: return null
        val second = typedAgain ?: return null
        return first.copyOf() to second.copyOf()
    }

    // ---- Leaving the steps ---------------------------------------------------------------------

    /** Back from "Write it down on paper": the chosen password goes; the suggested words stay for another try. */
    fun leftWriteDown() {
        dropChosen()
        clearAgain()
        mutableState.update { it.copy(wroteItDown = false) }
    }

    /** Back from "Type your master password again": the typing and its messages go. */
    fun leftTypeAgain() {
        clearAgain()
        mutableState.update { it.copy(mismatches = 0, againMessage = null, keyboardHint = null) }
    }

    /** Everything goes: the flow ended, the app locked, or the owner's ViewModel is cleared. */
    fun wipe() {
        suggesting?.cancel()
        suggesting = null
        checking?.cancel()
        checking = null
        judgedText = null
        own.clearSecret()
        dropChosen()
        clearAgain()
        mutableState.value = SetupState()
    }

    // ---- Internals -----------------------------------------------------------------------------

    private fun suggest() {
        if (suggesting?.isActive == true) return
        suggesting = scope.launch {
            val next = withContext(dispatchers.default) { Passphrase.suggest(random) }
            mutableState.update { it.copy(passphrase = next) }
        }
    }

    /**
     * The checklist follows the field (ux.md §5.3): judged on `default` a moment after the last key.
     * An edit clears the message of the last "Use this password".
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
        clearAgain()
        chosen = chars
        mutableState.update { it.copy(chosen = ChosenPassword(words, chars), wroteItDown = false, mismatches = 0, againMessage = null, keyboardHint = null) }
    }

    private fun dropChosen() {
        chosen?.fill(ZERO)
        chosen = null
        mutableState.update { it.copy(chosen = null) }
    }

    private fun clearAgain() {
        again.clearSecret()
        typedAgain?.fill(ZERO)
        typedAgain = null
    }

    private fun mismatch(wrongKeyboard: Boolean) {
        again.clearSecret()
        mutableState.update {
            it.copy(
                mismatches = it.mismatches + 1,
                againMessage = UiText.of(R.string.setup_again_mismatch),
                keyboardHint = if (wrongKeyboard) UiText.of(R.string.setup_again_cyrillic) else null,
            )
        }
    }

    private companion object {
        /** The pause after the last key before the checklist is judged again, as in setup. */
        const val CHECK_AFTER_MS = 150L

        const val ZERO = '\u0000'
    }
}
