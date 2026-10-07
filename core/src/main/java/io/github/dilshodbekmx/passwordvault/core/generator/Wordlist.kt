package io.github.dilshodbekmx.passwordvault.core.generator

import java.util.Arrays

/**
 * The words of passphrases (architecture.md D-6a): EFF's short word list 1 without its one word
 * with a hyphen, "yo-yo". [SIZE] = 1,295 common English words of 3 to 5 letters a–z (4.5 on
 * average), in ascending order, so no word is there twice; a word drawn from it carries
 * log2(1,295) = 10.34 bits, six words 62.0.
 *
 * The list is the resource [RESOURCE]: EFF's file with that one line removed. `SOURCES.md` beside it
 * names the origin, the licence (CC BY 4.0) and the change, for the licences screen
 * (VERIFICATION.md V-C13). [effShort] reads and checks the file once, on first use, and refuses one
 * that is not exactly such a list, so that a damaged or edited file fails loudly instead of making
 * weaker passphrases.
 *
 * The words are not secret; which of them were drawn is, and that never passes through this class.
 */
class Wordlist private constructor(private val words: Array<String>) {

    /** The number of words: always [SIZE]. */
    val size: Int get() = words.size

    /** The word at [index], 0 until [size]: 3 to 5 lower-case letters a–z. */
    operator fun get(index: Int): String = words[index]

    /** The index of [word] (lower case, as listed), or -1 when it is not in the list. */
    fun indexOf(word: String): Int = Arrays.binarySearch(words, word).let { if (it >= 0) it else -1 }

    /** Whether [word] (lower case, as listed) is in the list. */
    operator fun contains(word: String): Boolean = indexOf(word) >= 0

    override fun toString(): String = "Wordlist($size words)"

    companion object {
        /** The 1,296 words of EFF's short list 1 less "yo-yo". */
        const val SIZE = 1_295

        /** Where the list is among the Java resources of :core, which are packaged into the app. */
        const val RESOURCE = "/wordlists/eff_short_wordlist_1.txt"

        /** One line of the file: four dice digits 1 to 6, a tab, and the word. */
        private val LINE = Regex("[1-6]{4}\t[a-z]{3,5}")

        /**
         * The list, read from [RESOURCE] and checked by [parse] on first use. That first use reads
         * 13,649 bytes from the app's package: make it from a background thread (a ViewModel's
         * `Dispatchers.Default`), not from the main thread. Later uses cost nothing.
         *
         * @throws IllegalStateException when the resource is missing or is not the list described
         *   above; a later use tries again.
         */
        val effShort: Wordlist by lazy { parse(read()) }

        private fun read(): String {
            val stream = Wordlist::class.java.getResourceAsStream(RESOURCE)
                ?: throw IllegalStateException("the word list $RESOURCE is missing")
            return stream.use { String(it.readBytes(), Charsets.UTF_8) }
        }

        /**
         * The list in [text]: exactly [SIZE] lines, each four dice digits 1 to 6, a tab and 3 to 5
         * letters a–z, each ended by LF (no CR), with the dice numbers and the words both in
         * strictly ascending order.
         *
         * @throws IllegalStateException naming the first line that breaks a rule by its number,
         *   never by its text.
         */
        internal fun parse(text: String): Wordlist {
            val lines = text.split('\n')
            check(lines.last().isEmpty()) { "word list: the last line has no line end" }
            val words = ArrayList<String>(SIZE)
            var previousDice = ""
            var previousWord = ""
            for (n in 1 until lines.size) {
                val line = lines[n - 1]
                check(LINE.matches(line)) { "word list line $n: not four dice digits 1-6, a tab and 3 to 5 letters a-z" }
                val dice = line.substring(0, 4)
                val word = line.substring(5)
                check(dice > previousDice && word > previousWord) { "word list line $n: not in ascending order" }
                words += word
                previousDice = dice
                previousWord = word
            }
            check(words.size == SIZE) { "word list: ${words.size} words instead of $SIZE" }
            return Wordlist(words.toTypedArray())
        }
    }
}
