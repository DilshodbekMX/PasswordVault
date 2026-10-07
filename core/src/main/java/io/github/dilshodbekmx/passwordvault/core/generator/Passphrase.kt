package io.github.dilshodbekmx.passwordvault.core.generator

import io.github.dilshodbekmx.passwordvault.core.util.RandomSource
import kotlin.math.log2

/**
 * A suggested master password (ux.md §5.3; architecture.md D-6a, D-6b): [WORDS] = 6 words of
 * [Wordlist.effShort], each drawn with one unbiased [RandomSource.int], in lower case and joined by
 * hyphens, such as `vowel-shiny-shell-crisp-shaft-cadet`. That is 6 × log2(1,295) = 62.0 bits
 * ([BITS]). "Show other words" is a new [suggest]; rolling again costs at most log2 of the number of
 * rolls, in bits (ux.md §5.3).
 *
 * Setup shows [words] as a numbered list and, under them, the whole passphrase exactly as it is typed
 * ([words] joined by [SEPARATOR]); [toCharArray] hands it to the lock. The words are never stored
 * (ux.md §5.5). [toString] names no word: this is a master password, which must reach no log and no
 * exception message (architecture.md §5.6).
 */
class Passphrase private constructor(
    /** The [WORDS] words in order, in lower case, as setup numbers them. */
    val words: List<String>,
) {
    /**
     * The passphrase exactly as it is typed: [words] joined by [SEPARATOR]. A new array, which the
     * caller owns and wipes after use (architecture.md §5.6). `MasterPasswordBytes` turns it into its
     * own ASCII bytes unchanged: it has no white space, no apostrophe and nothing NFC changes.
     */
    fun toCharArray(): CharArray {
        var size = words.size - 1
        for (word in words) size += word.length
        val chars = CharArray(size)
        var at = 0
        for ((index, word) in words.withIndex()) {
            if (index > 0) chars[at++] = SEPARATOR
            for (c in word) chars[at++] = c
        }
        return chars
    }

    override fun toString(): String = "Passphrase(${words.size} words, hidden)"

    companion object {
        /** Six words (D-6a). */
        const val WORDS = 6

        /** The separator of version 1: a hyphen (D-6b). */
        const val SEPARATOR = '-'

        /** 6 × log2(1,295) = 62.03 bits. */
        val BITS: Double = WORDS * log2(Wordlist.SIZE.toDouble())

        /**
         * A new suggestion: [WORDS] draws `random.int(1295)`, one per word, in order. The first use
         * in the process reads the word list (see [Wordlist.effShort]): call it from a background
         * thread.
         */
        fun suggest(random: RandomSource): Passphrase {
            val list = Wordlist.effShort
            return Passphrase(List(WORDS) { list[random.int(list.size)] })
        }
    }
}
