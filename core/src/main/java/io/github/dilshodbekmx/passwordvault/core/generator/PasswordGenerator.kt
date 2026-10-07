package io.github.dilshodbekmx.passwordvault.core.generator

import io.github.dilshodbekmx.passwordvault.core.util.RandomSource
import java.math.BigInteger
import kotlin.math.log2

/**
 * The passwords of the generator sheet (ux.md §5.15), made from [GeneratorOptions] with [random] as
 * the only source of chance: `SystemRandom` (`SecureRandom`) on the phone, `FakeRandom` in tests.
 *
 * **Unbiased** (crypto.md §9). Every choice is one [RandomSource.int] over the whole set it is made
 * from, which draws by rejection and never as a random number `% n`. Nothing is chosen in any other
 * way, and [RandomSource.bytes] is never called.
 *
 * **Random.** [GeneratorOptions.length] characters, each drawn from [alphabet]: the chosen kinds of
 * A–Z, a–z, 0–9 and the twelve [SYMBOLS], in that order, without the [LOOK_ALIKE] characters
 * I l 1 O 0 when look-alikes are avoided. A draw that misses one of the chosen kinds is thrown away
 * whole and made again from the start, so every password of that length over the alphabet that has
 * at least one character of each chosen kind is equally likely, and no other one can come out. With
 * the defaults (16 characters out of 69, all four kinds) that is 97.4 bits ([bits]). Each try makes
 * `length` draws `int(alphabet.length)`, one per character in order.
 *
 * **Words.** [GeneratorOptions.words] words of [Wordlist.effShort], joined by the separator.
 * "Capital letters" makes the first letter of every word upper case; "Add a number" puts one digit
 * 0–9 right after one of the words. The draws, in this order: `int(1295)` for each word, then, with a
 * number, `int(words)` for the word it follows and `int(10)` for the digit.
 *
 * The password comes back as a new `CharArray`, which the caller owns, puts where it belongs and
 * then wipes (architecture.md §5.6). Options that are not [isValid] are refused with an
 * [IllegalArgumentException]; the sheet never makes them ("At least one kind of character is
 * needed.").
 */
class PasswordGenerator(private val random: RandomSource) {

    /**
     * A new password for [options]. The Words kind reads the word list on its first use in the
     * process (see [Wordlist.effShort]): call this from a background thread.
     */
    fun generate(options: GeneratorOptions): CharArray {
        problem(options)?.let { throw IllegalArgumentException(it) }
        return when (options.kind) {
            GeneratorOptions.Kind.RANDOM -> characters(options)
            GeneratorOptions.Kind.WORDS -> words(options)
        }
    }

    private fun characters(options: GeneratorOptions): CharArray {
        val kinds = kinds(options)
        val alphabet = kinds.joinToString("")
        // ends[k]: the index in the alphabet just after the last character of kind k.
        val ends = IntArray(kinds.size)
        var end = 0
        for (k in kinds.indices) {
            end += kinds[k].length
            ends[k] = end
        }
        val everyKind = (1 shl kinds.size) - 1
        val password = CharArray(options.length)
        repeat(MAX_TRIES) {
            var seen = 0
            for (i in password.indices) {
                val pick = random.int(alphabet.length)
                password[i] = alphabet[pick]
                var k = 0
                while (pick >= ends[k]) k++
                seen = seen or (1 shl k)
            }
            if (seen == everyKind) return password
        }
        password.fill('\u0000')
        throw IllegalStateException("no password with every chosen kind in $MAX_TRIES tries: the random source is broken")
    }

    private fun words(options: GeneratorOptions): CharArray {
        val list = Wordlist.effShort
        val count = options.words
        val picks = IntArray(count) { random.int(list.size) }
        val after = if (options.addNumber) random.int(count) else -1
        val digit = if (options.addNumber) random.int(10) else 0
        var size = count - 1 + (if (options.addNumber) 1 else 0)
        for (pick in picks) size += list[pick].length
        val password = CharArray(size)
        var at = 0
        for (w in 0 until count) {
            if (w > 0) password[at++] = options.separator.char
            val word = list[picks[w]]
            for (c in word.indices) {
                // Char.uppercaseChar() ignores the default locale: "i" is "I" under tr-TR as well.
                password[at++] = if (c == 0 && options.capitalize) word[c].uppercaseChar() else word[c]
            }
            if (w == after) password[at++] = '0' + digit
        }
        picks.fill(0)
        return password
    }

    companion object {
        /** "A–Z". */
        const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"

        /** "a–z". */
        const val LOWER = "abcdefghijklmnopqrstuvwxyz"

        /** "0–9". */
        const val DIGITS = "0123456789"

        /** "Symbols ! # $ % & * + - = ? @ _" (ux.md §5.15). */
        const val SYMBOLS = "!#\$%&*+-=?@_"

        /** "Avoid look-alike characters (I l 1 O 0)". */
        const val LOOK_ALIKE = "Il1O0"

        /**
         * A try that misses a chosen kind is made again at most this often. With a working random
         * source the worst case (4 characters, all four kinds) keeps about 1 try in 16, so a source
         * that fails 10,000 times is broken, and the generator says so instead of looping for ever.
         */
        internal const val MAX_TRIES = 10_000

        /**
         * The characters a Random password of [options] is drawn from: the chosen kinds one after
         * the other, in the order A–Z, a–z, 0–9, symbols, each without [LOOK_ALIKE] when look-alikes
         * are avoided. 69 characters with the defaults, 74 with every kind and nothing avoided.
         */
        fun alphabet(options: GeneratorOptions): String = kinds(options).joinToString("")

        /**
         * The chance in a password made with [options], in bits: log2 of the number of passwords
         * that can come out, all equally likely. Random: counted by inclusion and exclusion over the
         * chosen kinds, 97.4 with the defaults. Words: words × log2(1,295), plus log2(words × 10)
         * with a number: 62.0 for 6 words without one, 67.9 with. The separator and capital letters
         * add nothing.
         */
        fun bits(options: GeneratorOptions): Double {
            problem(options)?.let { throw IllegalArgumentException(it) }
            return when (options.kind) {
                GeneratorOptions.Kind.RANDOM -> bitsOf(possiblePasswords(options))
                GeneratorOptions.Kind.WORDS -> {
                    val words = options.words * log2(Wordlist.SIZE.toDouble())
                    if (options.addNumber) words + log2(options.words * 10.0) else words
                }
            }
        }

        /** Why [options] are refused, or null: the bounds of [GeneratorOptions] and one kind at least. */
        internal fun problem(options: GeneratorOptions): String? = when {
            options.length !in GeneratorOptions.MIN_LENGTH..GeneratorOptions.MAX_LENGTH ->
                "length ${options.length} is not in ${GeneratorOptions.MIN_LENGTH}..${GeneratorOptions.MAX_LENGTH}"
            options.words !in GeneratorOptions.MIN_WORDS..GeneratorOptions.MAX_WORDS ->
                "words ${options.words} is not in ${GeneratorOptions.MIN_WORDS}..${GeneratorOptions.MAX_WORDS}"
            !(options.upper || options.lower || options.digits || options.symbols) ->
                "no kind of character is chosen"
            else -> null
        }

        private fun kinds(options: GeneratorOptions): List<String> {
            val chosen = ArrayList<String>(4)
            if (options.upper) chosen += UPPER
            if (options.lower) chosen += LOWER
            if (options.digits) chosen += DIGITS
            if (options.symbols) chosen += SYMBOLS
            return if (options.avoidLookAlike) chosen.map { kind -> kind.filterNot { it in LOOK_ALIKE } } else chosen
        }

        /**
         * The passwords of [GeneratorOptions.length] characters over the alphabet that have every
         * chosen kind: for each set of kinds left out, the passwords that use none of them, added
         * or taken away by the size of the set.
         */
        private fun possiblePasswords(options: GeneratorOptions): BigInteger {
            val sizes = kinds(options).map { it.length }
            val all = sizes.sum()
            var total = BigInteger.ZERO
            for (leftOut in 0 until (1 shl sizes.size)) {
                var size = all
                for (k in sizes.indices) {
                    if ((leftOut and (1 shl k)) != 0) size -= sizes[k]
                }
                val term = BigInteger.valueOf(size.toLong()).pow(options.length)
                total = if ((Integer.bitCount(leftOut) and 1) == 0) total.add(term) else total.subtract(term)
            }
            return total
        }

        /** log2 of a positive [count], to the precision of a Double. */
        private fun bitsOf(count: BigInteger): Double {
            val shift = maxOf(0, count.bitLength() - 62)
            return shift + log2(count.shiftRight(shift).toDouble())
        }
    }
}

/**
 * Whether [PasswordGenerator.generate] takes these options: the length in 4..64 and the number of
 * words in 4..12, whichever kind is chosen, and at least one kind of character on. The defaults
 * are. A settings reader can use it to fall back to the defaults when a stored value is not.
 */
fun GeneratorOptions.isValid(): Boolean = PasswordGenerator.problem(this) == null
