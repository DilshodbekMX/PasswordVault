package io.github.dilshodbekmx.passwordvault.core.strength

import com.nulabinc.zxcvbn.Context
import com.nulabinc.zxcvbn.Pattern
import com.nulabinc.zxcvbn.StandardDictionaries
import com.nulabinc.zxcvbn.StandardKeyboards
import com.nulabinc.zxcvbn.WipeableString
import com.nulabinc.zxcvbn.Zxcvbn
import com.nulabinc.zxcvbn.ZxcvbnBuilder
import com.nulabinc.zxcvbn.matchers.Dictionary
import com.nulabinc.zxcvbn.matchers.Keyboard
import com.nulabinc.zxcvbn.matchers.Match
import com.nulabinc.zxcvbn.matchers.SpatialMatcher
import io.github.dilshodbekmx.passwordvault.core.search.KeyboardLayout
import io.github.dilshodbekmx.passwordvault.core.search.searchKey
import java.lang.ref.SoftReference

/**
 * The strength meter (ux.md §5.3): zxcvbn4j 1.9.0 with its English lists and the bundled Uzbek and
 * Russian word list ([WordList]), used by the master-password checklist ([MasterPasswordRules]), the
 * meter under a typed password (ux.md §5.14) and the security check ([SecurityCheck]).
 *
 * **Two readings of every password.** zxcvbn scores the password as it is, and again as its search
 * key ([searchKey], search.md §1): Cyrillic in Latin letters by the Uzbek rules, case folded and every
 * apostrophe removed. The lower score counts. So "Ташкент2020" is also read as "tashkent2020", and
 * "Oʻzbekiston2024", whose ʻ hides the word from zxcvbn, as "ozbekiston2024" (ux.md §5.3, D-6d).
 *
 * **Long passwords.** zxcvbn's matching grows with the cube of the length (20 s for 1,000 characters
 * on this project's build machine), so only the first [MAX_LENGTH] characters are scored; 100
 * characters that are not a few repeated words score 4 anyway.
 *
 * **Secrets.** [measure] copies the password into an array that it wipes before it returns, and has
 * zxcvbn wipe the copies it made of its parts. zxcvbn also makes `String`s of the parts it looks up,
 * and the search key is a `String`; those can only be dropped. Nothing is kept or logged.
 *
 * **Threads and memory.** Building a meter ([create]) reads zxcvbn's lists and the bundled one,
 * about 1.6 MB of text that take about 20 MB of memory: do it off the main thread (a ViewModel's
 * `Dispatchers.Default`), and share one ([shared]). After that, [measure] keeps no state between
 * calls and may be called from several threads at once.
 */
class StrengthMeter private constructor(private val zxcvbn: Zxcvbn, keyboards: List<Keyboard>) {

    /** zxcvbn's own keyboard-walk finder, to tell "qwerty123" from "password1" in its list of common passwords. */
    private val walks = SpatialMatcher(Context(emptyMap(), keyboards.associateBy { it.name }))

    /**
     * How hard [password] is to guess: the lower of zxcvbn's scores for its first [MAX_LENGTH]
     * characters and for their search key, and a hint when that is below 4.
     *
     * [userInputs] are words the password should not lean on, which zxcvbn counts as the cheapest
     * guesses: the parts of an account's name and username ([SecurityCheck.userInputs]) or the app's
     * name ([MasterPasswordRules.USER_INPUTS]). They are compared in lower case, and their search
     * keys with the search key of the password.
     */
    fun measure(password: CharSequence, userInputs: Collection<String> = emptyList()): Strength {
        val scored = Scored(::isKeyboardWalk)
        reading(password, userInputs) { score, matches -> scored.add(score, matches) }
        return scored.result()
    }

    /**
     * Whether [password] is "easy to guess" (ux.md §5.16, card 3): score [EASY_TO_GUESS] or less, as
     * [measure] would give it. It stops at the first reading that is easy, so a weak password costs
     * one zxcvbn run instead of two.
     */
    fun isEasyToGuess(password: CharSequence, userInputs: Collection<String> = emptyList()): Boolean {
        var easy = false
        reading(password, userInputs) { score, _ ->
            if (score <= EASY_TO_GUESS) easy = true
            !easy
        }
        return easy
    }

    /**
     * Runs zxcvbn on the readings of [password], as typed and as its search key, and hands each
     * score and match sequence to [each] until it returns false. The search key is skipped when it is
     * the same text.
     */
    private fun reading(password: CharSequence, userInputs: Collection<String>, each: (Int, List<Match>) -> Boolean) {
        var end = minOf(password.length, MAX_LENGTH)
        if (end in 1 until password.length && Character.isHighSurrogate(password[end - 1])) end-- // keep a pair whole
        val chars = CharArray(end) { password[it] }
        try {
            if (!once(WipeableString(chars), lowerCaseInputs(userInputs), each)) return
            val key = searchKey(String(chars))
            if (sameText(key, chars)) return
            val keyChars = key.toCharArray()
            try {
                once(WipeableString(keyChars), searchKeyInputs(userInputs), each)
            } finally {
                keyChars.fill('\u0000')
            }
        } finally {
            chars.fill('\u0000')
        }
    }

    /** One zxcvbn run on [text], which it wipes afterwards; returns what [each] returned. */
    private fun once(text: WipeableString, inputs: List<String>, each: (Int, List<Match>) -> Boolean): Boolean {
        val strength = zxcvbn.measure(text, inputs)
        try {
            return each(strength.score, strength.sequence)
        } finally {
            strength.wipe()
            text.wipe()
        }
    }

    /**
     * Whether [token], a word zxcvbn found in a list, is mostly walks on a keyboard: of at least five
     * characters, three quarters or more of them on walks of three keys or more ("zxcvfdsa",
     * "1qaz2wsx", "qwerty123"). zxcvbn reports such passwords as words of its list of common passwords,
     * and the bundled list holds the rows of the Russian keyboard in both layouts ("фывапролдж",
     * "asdfghjkl;"), not as walks; an English word such as "were" is too short to count. Cyrillic is
     * looked at on the QWERTY keys it shares (ЙЦУКЕН ↔ QWERTY).
     */
    private fun isKeyboardWalk(token: CharSequence): Boolean {
        if (token.any { it in '\u0400'..'\u04FF' }) return isQwertyWalk(KeyboardLayout.otherLayout(token.toString()))
        return isQwertyWalk(token)
    }

    private fun isQwertyWalk(token: CharSequence): Boolean {
        val n = token.length
        if (n < 5) return false
        val onWalk = BooleanArray(n)
        for (m in walks.execute(token)) if (m.j - m.i + 1 >= 3) for (k in m.i..m.j) onWalk[k] = true
        return onWalk.count { it } * 4 >= n * 3
    }

    private fun sameText(key: String, chars: CharArray): Boolean {
        if (key.length != chars.size) return false
        for (i in chars.indices) if (key[i] != chars[i]) return false
        return true
    }

    /**
     * The lower score of the readings so far, and the hint that the matches of the reading that gave
     * it call for. Readings come as typed first: when the search key scores the same, the hint
     * describes what was typed ("фывапролдж" is a keyboard row, not the word "fivaproldj").
     */
    private class Scored(private val isKeyboardWalk: (CharSequence) -> Boolean) {
        private var score = Int.MAX_VALUE
        private var names = false
        private var keyboard = false

        fun add(score: Int, matches: List<Match>): Boolean {
            if (score >= this.score) return true
            this.score = score
            names = false
            keyboard = false
            for (match in matches) {
                when {
                    match.pattern == Pattern.Spatial -> keyboard = true
                    match.pattern == Pattern.Dictionary && isKeyboardWalk(match.token) -> keyboard = true
                    match.isNameOrYear() -> names = true
                }
            }
            return true
        }

        fun result(): Strength {
            val final = if (score == Int.MAX_VALUE) 0 else score
            val hint = when {
                final == 4 -> null
                names -> StrengthHint.AVOID_NAMES_AND_YEARS
                keyboard -> StrengthHint.AVOID_KEYBOARD_ROWS
                else -> StrengthHint.ADD_WORD
            }
            return Strength(final, hint)
        }

        private fun Match.isNameOrYear(): Boolean = when (pattern) {
            Pattern.Dictionary -> dictionaryName in NAME_DICTIONARIES
            Pattern.Date -> true
            Pattern.Regex -> regexName == RECENT_YEAR
            else -> false
        }
    }

    /**
     * The bundled list as one zxcvbn dictionary. zxcvbn's own [Dictionary] ranks the words of a list
     * by their position in it; here every form of an entry shares the entry's rank (see [WordList]),
     * so the class hands zxcvbn the finished map. zxcvbn 1.9.0 reads a dictionary only through this
     * method (`Matching`); `StrengthMeterTest` pins the ranks it sees.
     */
    private class RankedDictionary(name: String, private val ranks: Map<String, Int>) : Dictionary(name, emptyList()) {
        override fun getRankedDictionary(): Map<String, Int> = ranks
    }

    companion object {
        /** "Easy to guess" (ux.md §5.16, card 3): a score of 2 or less, Bitwarden's threshold. */
        const val EASY_TO_GUESS = 2

        /** The characters of a password that are scored (see the class comment). */
        const val MAX_LENGTH = 100

        /** The name zxcvbn reports for a match in the bundled list. */
        const val BUNDLED = "uz_ru"

        private const val RECENT_YEAR = "recent_year"

        /** The lists whose words call for "Avoid city names, people's names and years". */
        private val NAME_DICTIONARIES = setOf(
            BUNDLED,
            StandardDictionaries.MALE_NAMES,
            StandardDictionaries.FEMALE_NAMES,
            StandardDictionaries.SURNAMES,
        )

        private val sharedLock = Any()
        private var sharedMeter: SoftReference<StrengthMeter>? = null

        /**
         * The meter of the app: built by [create] on first use, then kept while memory allows. It
         * holds about 20 MB of word lists, so it is held through a soft reference, which the runtime
         * may clear when memory runs short; the next use then builds it again. The first use, and a
         * use after a clear, read the word lists: call this from a background thread, and keep the
         * meter for the length of one task (a security check) rather than calling this per password.
         * If building fails, the next call tries again.
         */
        fun shared(): StrengthMeter = synchronized(sharedLock) {
            sharedMeter?.get() ?: create().also { sharedMeter = SoftReference(it) }
        }

        /**
         * A new meter: zxcvbn's English dictionaries and keyboards, and the bundled list.
         *
         * @throws IllegalStateException when a list is missing or damaged.
         */
        fun create(): StrengthMeter {
            val english: List<Dictionary>
            val keyboards: List<Keyboard>
            try {
                english = StandardDictionaries.loadAllDictionaries()
                keyboards = StandardKeyboards.loadAllKeyboards()
            } catch (e: java.io.IOException) {
                throw IllegalStateException("zxcvbn's own word lists or keyboards cannot be read", e)
            }
            val zxcvbn = ZxcvbnBuilder()
                .dictionaries(english)
                .dictionary(RankedDictionary(BUNDLED, WordList.load()))
                .keyboards(keyboards)
                .build()
            return StrengthMeter(zxcvbn, keyboards)
        }

        /** [inputs] in lower case char by char, as zxcvbn lowers the password; blank ones dropped. */
        private fun lowerCaseInputs(inputs: Collection<String>): List<String> =
            inputs.map { WordList.lowerCase(it.trim()) }.filter { it.isNotEmpty() }.distinct()

        /** The search keys of [inputs], to go with the search key of the password. */
        private fun searchKeyInputs(inputs: Collection<String>): List<String> =
            inputs.map { searchKey(it) }.filter { it.isNotEmpty() }.distinct()
    }
}
