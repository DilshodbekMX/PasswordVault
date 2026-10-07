package io.github.dilshodbekmx.passwordvault.core.strength

import io.github.dilshodbekmx.passwordvault.core.crypto.MasterPasswordBytes
import java.nio.ByteBuffer
import java.nio.CharBuffer

/**
 * The checklist of a master password the user makes up (ux.md §5.3 "Own password view",
 * architecture.md D-6d and §4.6), which the setup, change and replacement screens apply before the
 * password is set (build plan 3.3, 5.5). Every item must pass ([MasterPasswordCheck.passes]):
 * - "At least 12 characters": [MIN_LENGTH] code points or more;
 * - "Hard to guess": zxcvbn score 4 with the bundled word list, for the password and for its search
 *   key ([StrengthMeter.measure] reads both), with the app's name, "password", "parol" and "пароль"
 *   as user inputs ([USER_INPUTS]);
 * - no code point that Unicode has not assigned and no unpaired surrogate
 *   ([MasterPasswordBytes.firstUnassigned], VERIFICATION.md V-C9), which a later phone might fold
 *   differently.
 *
 * Length and strength are judged on the characters as [MasterPasswordBytes] folds them before they
 * become bytes (§4.6 steps 1 to 3: NFC, a run of white space as one space and none at the ends, the
 * 17 apostrophes as U+0027), so that spaces cannot pad a password, a password of white space only
 * has no characters at all, and "oʻgʻlim" typed with any apostrophe is judged once.
 *
 * The suggested passphrase needs no check: six words of the EFF list score 4 (StrengthMeterTest).
 */
object MasterPasswordRules {

    /** "At least 12 characters" (Bitwarden's minimum for master passwords, ux.md §5.3). */
    const val MIN_LENGTH = 12

    /** What zxcvbn treats as the cheapest guesses for a master password (ux.md §5.3). */
    val USER_INPUTS: List<String> = listOf("passwordvault", "password", "parol", "пароль")

    /**
     * The checklist for [password], as typed in the field. [password] is not changed; every copy this
     * makes is wiped before it returns (the search key, a `String`, can only be dropped). Runs zxcvbn
     * twice: call it from a background thread.
     */
    fun check(password: CharArray, meter: StrengthMeter = StrengthMeter.shared): MasterPasswordCheck {
        val unassigned = MasterPasswordBytes.firstUnassigned(password) != null
        val folded = folded(password)
        try {
            return MasterPasswordCheck(
                longEnough = Character.codePointCount(folded, 0, folded.size) >= MIN_LENGTH,
                strength = meter.measure(CharBuffer.wrap(folded), USER_INPUTS),
                unassignedCharacter = unassigned,
                mixesScripts = mixesLatinAndCyrillic(folded),
            )
        } finally {
            folded.fill('\u0000')
        }
    }

    /**
     * Whether [password] holds a Cyrillic letter. "Type it again" checks a mismatch with it (ux.md
     * §5.5): the suggested words are English, so a Cyrillic letter means the keyboard is on Russian.
     */
    fun containsCyrillic(password: CharArray): Boolean = countLetters(password, Character.UnicodeScript.CYRILLIC) > 0

    /**
     * Whether [password] mixes Latin and Cyrillic letters (ux.md §5.3, "Mixed scripts"): the screen
     * asks whether the keyboard is set to the right language. A question, not a rule.
     */
    fun mixesLatinAndCyrillic(password: CharArray): Boolean =
        countLetters(password, Character.UnicodeScript.LATIN) > 0 && countLetters(password, Character.UnicodeScript.CYRILLIC) > 0

    /**
     * [password] after steps 1 to 3 of architecture.md §4.6: the UTF-8 of [MasterPasswordBytes.of]
     * read back as characters, so that the two can never disagree. The caller wipes the result.
     */
    internal fun folded(password: CharArray): CharArray {
        val bytes = MasterPasswordBytes.of(password)
        try {
            val decoded = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes))
            val chars = CharArray(decoded.remaining())
            decoded.get(chars)
            if (decoded.hasArray()) decoded.array().fill('\u0000')
            return chars
        } finally {
            bytes.fill(0)
        }
    }

    private fun countLetters(chars: CharArray, script: Character.UnicodeScript): Int {
        var n = 0
        var i = 0
        while (i < chars.size) {
            val c = Character.codePointAt(chars, i)
            i += Character.charCount(c)
            if (Character.isLetter(c) && Character.UnicodeScript.of(c) == script) n++
        }
        return n
    }
}

/**
 * The checklist of one master password ([MasterPasswordRules.check]). It holds no character of the
 * password and not its length, so it may be kept in a screen's state.
 */
data class MasterPasswordCheck(
    /** "At least 12 characters", counted after folding. */
    val longEnough: Boolean,
    /** For the meter under the field and its hint; the lower score of the password and its search key. */
    val strength: Strength,
    /** A code point Unicode has not assigned, or an unpaired surrogate: refused (V-C9). */
    val unassignedCharacter: Boolean,
    /** Latin and Cyrillic letters together: the screen asks about the keyboard (not a rule). */
    val mixesScripts: Boolean,
) {
    /** "Hard to guess": score 4 for the password and for its search key (D-6d). */
    val hardToGuess: Boolean get() = strength.score == 4

    /** Every item of the checklist passes: the password may be set (ux.md §5.3). */
    val passes: Boolean get() = longEnough && hardToGuess && !unassignedCharacter
}
