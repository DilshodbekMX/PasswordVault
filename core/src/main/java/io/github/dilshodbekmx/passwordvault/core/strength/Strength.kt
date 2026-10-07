package io.github.dilshodbekmx.passwordvault.core.strength

/**
 * How hard one password is to guess (ux.md §5.3): what [StrengthMeter.measure] says about it.
 * It holds a number and a hint, never the password, so it may be kept in a screen's state.
 */
data class Strength(
    /**
     * zxcvbn's score, 0 to 4, with the bundled word list: the lower of the scores of the password as
     * typed and of its search key ([StrengthMeter.measure]). 4, the highest, means 10^10 guesses or
     * more; 3 means 10^8 to 10^10, which one graphics card tries against a stolen backup in 16 hours
     * to 68 days (ux.md §5.3).
     */
    val score: Int,
    /** What would make the password harder to guess; null when [score] is 4. */
    val hint: StrengthHint?,
) {
    init {
        require(score in 0..4) { "a zxcvbn score is 0 to 4" }
        require((score == 4) == (hint == null)) { "a hint goes with every score below 4, and only with those" }
    }

    /** The meter's step (ux.md §5.3): 0 and 1 are VERY_WEAK, 2 WEAK, 3 GOOD and 4 STRONG. */
    val level: StrengthLevel
        get() = when (score) {
            0, 1 -> StrengthLevel.VERY_WEAK
            2 -> StrengthLevel.WEAK
            3 -> StrengthLevel.GOOD
            else -> StrengthLevel.STRONG
        }

    /**
     * "Easy to guess" in the security check (ux.md §5.16, card 3): a score of
     * [StrengthMeter.EASY_TO_GUESS] or less, Bitwarden's threshold.
     */
    val easyToGuess: Boolean get() = score <= StrengthMeter.EASY_TO_GUESS
}

/**
 * The four steps of the strength meter (ux.md §5.3): four segments and a word, "Very weak", "Weak",
 * "Good", "Strong". The screens pick the word; colour is never the only signal.
 */
enum class StrengthLevel { VERY_WEAK, WEAK, GOOD, STRONG }

/**
 * The app's own short hints (ux.md §5.3), which the screens word; zxcvbn's English feedback is never
 * shown. [StrengthMeter.measure] picks one from what it found in the password, in this order.
 */
enum class StrengthHint {
    /**
     * "Avoid city names, people's names and years": a word of the bundled Uzbek and Russian list (a
     * place, a first name, a common word or phrase), a first name or surname of zxcvbn's own lists,
     * a year or a date.
     */
    AVOID_NAMES_AND_YEARS,

    /** "Avoid keyboard rows like qwerty": keys next to each other on a keyboard (and nothing above). */
    AVOID_KEYBOARD_ROWS,

    /** "Add another word": anything else, such as a common English word or password, or too few characters. */
    ADD_WORD,
}
