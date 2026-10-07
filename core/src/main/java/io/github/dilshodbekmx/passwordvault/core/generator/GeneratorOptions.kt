package io.github.dilshodbekmx.passwordvault.core.generator

/**
 * The choices of the password generator sheet (ux.md §5.15; architecture.md §3.7): the kind, the
 * length and character sets of a random password, the words of a passphrase and their separator.
 * The defaults are ux.md's. `AppSettings.generator` remembers the last choices (not secret);
 * `PasswordGenerator` (package 2.6) makes passwords from them.
 *
 * This is a value: nothing here checks the bounds below or that at least one character set is on;
 * the sheet keeps them ("At least one kind of character is needed.") and the generator refuses
 * anything else.
 */
data class GeneratorOptions(
    val kind: Kind = Kind.RANDOM,
    /** Random: the length, [MIN_LENGTH] to [MAX_LENGTH]. */
    val length: Int = 16,
    /** Random: "A–Z". */
    val upper: Boolean = true,
    /** Random: "a–z". */
    val lower: Boolean = true,
    /** Random: "0–9". */
    val digits: Boolean = true,
    /** Random: "Symbols ! # $ % & * + - = ? @ _". */
    val symbols: Boolean = true,
    /** Random: "Avoid look-alike characters (I l 1 O 0)". */
    val avoidLookAlike: Boolean = true,
    /** Words: the number of words, [MIN_WORDS] to [MAX_WORDS], from the EFF short list (D-6a). */
    val words: Int = 6,
    /** Words: the separator. */
    val separator: Separator = Separator.HYPHEN,
    /** Words: "Capital letters". */
    val capitalize: Boolean = true,
    /** Words: "Add a number". */
    val addNumber: Boolean = true,
) {
    /** "Random" or "Words" (ux.md §5.15). */
    enum class Kind { RANDOM, WORDS }

    /** The separators ux.md §5.15 offers: - . _ and space; [char] is the one written between words. */
    enum class Separator(val char: Char) {
        HYPHEN('-'),
        DOT('.'),
        UNDERSCORE('_'),
        SPACE(' '),
    }

    companion object {
        const val MIN_LENGTH = 4
        const val MAX_LENGTH = 64
        const val MIN_WORDS = 4
        const val MAX_WORDS = 12
    }
}
