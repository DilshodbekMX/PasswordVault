package io.github.dilshodbekmx.passwordvault.ui.edit

import io.github.dilshodbekmx.passwordvault.core.generator.GeneratorOptions
import io.github.dilshodbekmx.passwordvault.core.generator.PasswordGenerator
import io.github.dilshodbekmx.passwordvault.core.generator.isValid
import io.github.dilshodbekmx.passwordvault.core.strength.Strength
import io.github.dilshodbekmx.passwordvault.core.util.RandomSource
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The password generator sheet (ux.md §5.15) without its look: the choices, the password made from
 * them and its strength. The add and edit form owns one ([EditViewModel.generator]); the guided
 * change (build plan 7.2) can own another, in its own ViewModel's [scope].
 *
 * - **The choices** start as the last ones (`AppSettings.generator`, not secret), or ux.md's defaults
 *   when those cannot be used, and are written back when the sheet closes or its password is taken.
 *   Every change makes a new password at once.
 * - **At least one kind of character**: switching off the last kind that is on is refused, and
 *   [GeneratorState.oneKindNeeded] says so ("At least one kind of character is needed.").
 * - **The password** comes from `PasswordGenerator` over [random] (`SystemRandom`, a `SecureRandom`,
 *   on the phone) on the default dispatcher, where the word list is read on its first use and
 *   [measure] (zxcvbn) runs; the newest request wins. It lives only in [state], in memory; closing the
 *   sheet drops it. There is no copy button (ux.md §5.15): the password goes into the form, and is
 *   copied after Save from the detail screen.
 *
 * Main thread only, like the ViewModel that owns it.
 */
class GeneratorModel(
    private val scope: CoroutineScope,
    private val settings: AppSettings,
    random: RandomSource,
    private val dispatchers: AppDispatchers,
    private val measure: (CharSequence) -> Strength,
) {
    private val generator = PasswordGenerator(random)
    private val mutable = MutableStateFlow(GeneratorState.CLOSED)

    /** The sheet as it is now; [GeneratorState.open] false while it is closed. */
    val state: StateFlow<GeneratorState> = mutable

    /** The choices as they were read when the sheet opened, to write back only what changed. */
    private var opened: GeneratorOptions? = null
    private var making: Job? = null

    /** Opens the sheet with the last choices and makes a password. */
    fun open() {
        if (mutable.value.open) return
        val options = settings.generator.value.takeIf { it.isValid() } ?: GeneratorOptions()
        opened = options
        mutable.value = GeneratorState(open = true, options = options, preview = null, strength = null, oneKindNeeded = false)
        make()
    }

    /** Closes the sheet: the password is dropped, and choices that changed are remembered. */
    fun close() {
        making?.cancel()
        making = null
        val now = mutable.value
        mutable.value = GeneratorState.CLOSED
        val before = opened ?: return
        opened = null
        if (now.open && now.options != before) {
            val options = now.options
            scope.launch(dispatchers.io) { settings.update { it.generator = options } }
        }
    }

    /** The password shown now, for "Use this password"; null while none is made yet or the sheet is closed. */
    fun password(): String? = mutable.value.takeIf { it.open }?.preview

    /** "New password": another one with the same choices. */
    fun again() {
        if (mutable.value.open) make()
    }

    /** "Random" or "Words". */
    fun setKind(kind: GeneratorOptions.Kind) = change { it.copy(kind = kind) }

    /** The length slider; kept within 4 to 64. */
    fun setLength(length: Int) =
        change { it.copy(length = length.coerceIn(GeneratorOptions.MIN_LENGTH, GeneratorOptions.MAX_LENGTH)) }

    /** The − button of the length. */
    fun shorter() = setLength(mutable.value.options.length - 1)

    /** The + button of the length. */
    fun longer() = setLength(mutable.value.options.length + 1)

    /** "A–Z". */
    fun setUpper(on: Boolean) = change { it.copy(upper = on) }

    /** "a–z". */
    fun setLower(on: Boolean) = change { it.copy(lower = on) }

    /** "0–9". */
    fun setDigits(on: Boolean) = change { it.copy(digits = on) }

    /** "Symbols ! # $ % & * + - = ? @ _". */
    fun setSymbols(on: Boolean) = change { it.copy(symbols = on) }

    /** "Avoid look-alike characters (I l 1 O 0)". */
    fun setAvoidLookAlike(on: Boolean) = change { it.copy(avoidLookAlike = on) }

    /** "Number of words"; kept within 4 to 12. */
    fun setWords(words: Int) =
        change { it.copy(words = words.coerceIn(GeneratorOptions.MIN_WORDS, GeneratorOptions.MAX_WORDS)) }

    /** The − button of the number of words. */
    fun fewerWords() = setWords(mutable.value.options.words - 1)

    /** The + button of the number of words. */
    fun moreWords() = setWords(mutable.value.options.words + 1)

    /** "Separator": - . _ or space. */
    fun setSeparator(separator: GeneratorOptions.Separator) = change { it.copy(separator = separator) }

    /** "Capital letters". */
    fun setCapitalize(on: Boolean) = change { it.copy(capitalize = on) }

    /** "Add a number". */
    fun setAddNumber(on: Boolean) = change { it.copy(addNumber = on) }

    private fun change(update: (GeneratorOptions) -> GeneratorOptions) {
        val now = mutable.value
        if (!now.open) return
        val next = update(now.options)
        if (next == now.options) return
        if (!(next.upper || next.lower || next.digits || next.symbols)) {
            mutable.value = now.copy(oneKindNeeded = true)
            return
        }
        mutable.value = now.copy(options = next, oneKindNeeded = false)
        make()
    }

    /** Makes a password for the choices shown now; a newer request replaces an older one. */
    private fun make() {
        making?.cancel()
        val options = mutable.value.options
        making = scope.launch {
            val made = withContext(dispatchers.default) {
                val chars = generator.generate(options)
                try {
                    val text = String(chars)
                    Made(text, measure(text))
                } finally {
                    chars.fill('\u0000')
                }
            }
            val now = mutable.value
            if (now.open && now.options == options) mutable.value = now.copy(preview = made.text, strength = made.strength)
        }
    }

    private class Made(val text: String, val strength: Strength)
}

/**
 * The generator sheet as the screen draws it (ux.md §5.15).
 *
 * @property open whether the sheet is shown.
 * @property options the choices.
 * @property preview the password made from them, shown large; null while it is being made.
 * @property strength its strength word (`StrengthMeter`).
 * @property oneKindNeeded the last kind of character was about to be switched off: "At least one
 *   kind of character is needed."
 *
 * [toString] hides [preview].
 */
data class GeneratorState(
    val open: Boolean,
    val options: GeneratorOptions,
    val preview: String?,
    val strength: Strength?,
    val oneKindNeeded: Boolean,
) {
    override fun toString(): String =
        "GeneratorState(open=$open, options=$options, preview=${if (preview == null) "none" else "hidden"}, " +
            "strength=$strength, oneKindNeeded=$oneKindNeeded)"

    companion object {
        val CLOSED = GeneratorState(open = false, options = GeneratorOptions(), preview = null, strength = null, oneKindNeeded = false)
    }
}
