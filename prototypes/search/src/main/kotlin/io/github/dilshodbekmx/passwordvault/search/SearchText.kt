package io.github.dilshodbekmx.passwordvault.search

import io.github.dilshodbekmx.passwordvault.text.UzbekScript
import java.text.Normalizer

/**
 * The search key of [text]: the form in which a query and an entry's fields are compared.
 *
 * Steps, in order:
 * 1. Unicode NFKC: full-width letters, ligatures (ﬁ) and decomposed input (и + ̆) compose
 *    to one form. Apostrophe-like characters are set aside first, because NFKC would turn
 *    ´ (U+00B4) into a space and a combining accent.
 * 2. Cyrillic becomes Latin by the official Uzbek rules ([UzbekScript.cyrillicToLatin]):
 *    ўзбек and oʻzbek meet at "ozbek". Letters of neighbouring Cyrillic alphabets
 *    (Karakalpak ә ө ү ң, Kazakh, Tajik ҷ ӣ ӯ, Ukrainian і ї є ґ) map to the nearest Latin letter.
 * 3. Case folding by code point, independent of the default locale: upper case, then lower
 *    case (so ı, İ, ſ and ς fold like i, i, s and σ), and ß becomes ss.
 * 4. The letters of the 2026 alphabet reform meet the 1995 spellings: ş (and ș) is "sh",
 *    ç is "ch", and ö and ğ lose their marks in step 5 (o, g).
 * 5. Combining marks on Latin and Greek letters are removed (café → cafe, Köln → koln), and
 *    ø đ ł ħ ŧ æ œ þ ð are spelled o d l h t ae oe th d. Arabic short-vowel marks and
 *    tatweel are removed. Marks in other scripts (Devanagari vowel signs, Thai) are kept.
 * 6. Every apostrophe-like character is removed: U+0027 ' U+0060 ` U+00B4 ´ U+2018 ‘
 *    U+2019 ’ U+02BB ʻ U+02BC ʼ U+02BD ʽ U+2032 ′ and a few look-alikes, so oʻ, o', o‘ and
 *    o compare equal, and so do maʼno and ma'no.
 * 7. Decimal digits of any script become 0-9. Invisible format characters (zero-width
 *    space and joiners, direction marks, soft hyphen) and variation selectors are removed.
 * 8. Runs of white space become one space; leading and trailing space is dropped.
 *
 * Punctuation and symbols (emoji included) are kept; the matcher decides how to use them.
 */
fun searchKey(text: String): String = SearchText.fold(text).key

internal object SearchText {

    /**
     * A folded string. [wordStart] tells, for each char of [key], whether a word starts
     * there: at a letter or digit after a space, punctuation or a symbol; at a lower-to-upper
     * case change in the source (PayMe); at a letter-digit change (Top100); and at every
     * symbol (emoji).
     */
    class Folded(val key: String, val wordStart: BooleanArray)

    private const val APOSTROPHE = UzbekScript.TUTUQ // NFKC leaves U+02BC unchanged

    fun fold(text: String): Folded = foldPrepared(prepare(text))

    /** Step 1: apostrophes unified to U+02BC, then NFKC. */
    fun prepare(text: String): String {
        val s = unifyApostrophes(text)
        if (isAscii(s) || Normalizer.isNormalized(s, Normalizer.Form.NFKC)) return s
        return Normalizer.normalize(s, Normalizer.Form.NFKC)
    }

    /** Steps 2 to 8 on a string that went through [prepare]. */
    fun foldPrepared(prepared: String): Folded {
        var s = prepared
        if (UzbekScript.containsCyrillic(s)) {
            val sb = StringBuilder(s.length + s.length / 4)
            UzbekScript.appendCyrillicToLatin(s, sb, forSearch = true)
            s = sb.toString()
        }
        return foldLatin(s)
    }

    /**
     * [prepared] as a Russian keyboard (or Russian habit) would write it. Cyrillic ў қ ғ ҳ ё
     * become у к г х е. In Latin text oʻ and ö become u, ğ becomes g, q becomes k, h becomes
     * x except in sh and ch, and yo becomes what е would give in its place (ye at the start
     * of a word or after a vowel, e after a consonant), so that ё, е and yo meet. Search
     * compares these spellings in a second, lower-ranked tier: "Узбек" finds "Oʻzbekiston",
     * "хисоб" finds "Hisob", "кукон" finds "Qoʻqon", "хаёт" finds "hayot" and "елка" finds
     * "Ёлка". Returns [prepared] itself when nothing changes.
     */
    fun russianKeyboard(prepared: String): String {
        var sb: StringBuilder? = null
        var i = 0
        val n = prepared.length
        fun builder(): StringBuilder = sb ?: StringBuilder(n + 4).append(prepared, 0, i).also { sb = it }
        while (i < n) {
            val c = prepared[i]
            val next = if (i + 1 < n) prepared[i + 1] else ' '
            when {
                c == 'o' || c == 'O' -> if (next == APOSTROPHE) {
                    builder().append(if (c == 'o') 'u' else 'U'); i += 2; continue // oʻ: u
                }
                (c == 'y' || c == 'Y') && (next == 'o' || next == 'O') &&
                    (i + 2 >= n || prepared[i + 2] != APOSTROPHE) -> {
                    // yo: as е would be written here (yoʻ is y + oʻ and is left alone)
                    val b = builder()
                    if (!isLatinConsonantBefore(prepared, i)) b.append(c)
                    b.append(if (next == 'o') 'e' else 'E')
                    i += 2
                    continue
                }
            }
            val r: Char = when (c) {
                'ў' -> 'у'; 'Ў' -> 'У'; 'қ' -> 'к'; 'Қ' -> 'К'; 'ғ' -> 'г'; 'Ғ' -> 'Г'
                'ҳ' -> 'х'; 'Ҳ' -> 'Х'; 'ё' -> 'е'; 'Ё' -> 'Е'
                'ö' -> 'u'; 'Ö' -> 'U'; 'ğ' -> 'g'; 'Ğ' -> 'G'; 'q' -> 'k'; 'Q' -> 'K'
                'h', 'H' -> {
                    val prev = if (i > 0) prepared[i - 1] else ' '
                    if (prev == 's' || prev == 'S' || prev == 'c' || prev == 'C') c else if (c == 'h') 'x' else 'X'
                }
                else -> c
            }
            if (r != c) builder()
            sb?.append(r)
            i++
        }
        return sb?.toString() ?: prepared
    }

    /** True when a Latin consonant letter comes right before [i] (oʻ counts as a vowel). */
    private fun isLatinConsonantBefore(s: String, i: Int): Boolean {
        if (i == 0) return false
        val p = s[i - 1]
        if (p == APOSTROPHE) return !(i >= 2 && (s[i - 2] == 'o' || s[i - 2] == 'O'))
        val l = Character.toLowerCase(p)
        if (l !in 'a'..'z') return false
        return "aeiou".indexOf(l) < 0
    }

    private fun isAscii(s: String): Boolean {
        for (c in s) if (c.code >= 0x80) return false
        return true
    }

    /** Writes every apostrophe-like character as U+02BC, which survives NFKC unchanged. */
    private fun unifyApostrophes(text: String): String {
        var i = 0
        while (i < text.length && (text[i] == APOSTROPHE || !UzbekScript.isApostrophe(text[i]))) i++
        if (i == text.length) return text
        val chars = text.toCharArray()
        while (i < chars.size) {
            if (UzbekScript.isApostrophe(chars[i])) chars[i] = APOSTROPHE
            i++
        }
        return String(chars)
    }

    private fun foldLatin(s: String): Folded {
        val out = StringBuilder(s.length + 4)
        var starts = BooleanArray(s.length + 8)

        var prevWordChar = false // last emitted character was a letter, digit or mark
        var prevSrcLower = false // last source letter was lower case (for camelCase)
        var prevSrcLetter = false
        var prevSrcDigit = false
        var stripMarks = false // last emitted base letter is Latin or Greek
        var pendingSpace = false

        fun emit(text: CharSequence, wordStart: Boolean) {
            val need = out.length + text.length + 2
            if (need >= starts.size) starts = starts.copyOf(need * 2)
            if (pendingSpace) {
                out.append(' ')
                pendingSpace = false
            }
            starts[out.length] = wordStart
            out.append(text)
        }

        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (cp < 0x10000 && UzbekScript.isApostrophe(cp.toChar())) continue // oʻ marks and tutuq
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                if (out.isNotEmpty()) pendingSpace = true
                prevWordChar = false; prevSrcLower = false; prevSrcLetter = false; prevSrcDigit = false
                stripMarks = false
                continue
            }
            val type = Character.getType(cp)
            if (type == Character.FORMAT.toInt() || type == Character.CONTROL.toInt() || isVariationSelector(cp)) {
                continue // invisible: zero-width joiners, direction marks, soft hyphen, controls
            }
            if (type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt()
            ) {
                if (stripMarks || isArabicVowelMark(cp)) continue
                emit(String(Character.toChars(cp)), wordStart = false)
                continue
            }
            if (cp == 0x0640) continue // Arabic tatweel (kashida)

            val isLetter = Character.isLetter(cp)
            val isDigit = type == Character.DECIMAL_DIGIT_NUMBER.toInt()
            val wordChar = isLetter || isDigit || type == Character.LETTER_NUMBER.toInt() ||
                type == Character.OTHER_NUMBER.toInt()
            val upper = isLetter && (Character.isUpperCase(cp) || Character.isTitleCase(cp))

            val wordStart = if (wordChar) {
                !prevWordChar || (upper && prevSrcLower) || (isDigit && prevSrcLetter) || (isLetter && prevSrcDigit)
            } else {
                true // every symbol (emoji, currency, math) is its own word
            }

            val folded: CharSequence = when {
                isDigit -> {
                    val d = Character.digit(cp, 10)
                    if (d >= 0) ('0' + d).toString() else String(Character.toChars(cp))
                }
                isLetter -> foldLetter(cp)
                else -> String(Character.toChars(Character.toLowerCase(Character.toUpperCase(cp))))
            }
            if (folded.isNotEmpty()) emit(folded, wordStart)

            stripMarks = isLetter && isLatinOrGreek(cp)
            prevWordChar = wordChar
            prevSrcLetter = isLetter
            prevSrcDigit = isDigit
            prevSrcLower = isLetter && !upper && Character.isLowerCase(cp)
        }
        return Folded(out.toString(), starts.copyOf(out.length))
    }

    private fun isVariationSelector(cp: Int): Boolean = cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF

    /** Script test for a letter, with the common blocks answered without a table lookup. */
    private fun isLatinOrGreek(cp: Int): Boolean = when {
        cp < 0x0250 -> true // Basic Latin to Latin Extended-B
        cp in 0x0370..0x03FF || cp in 0x1E00..0x1FFF -> true // Greek, Latin Ext. Additional, Greek Ext.
        cp in 0x0400..0x052F -> false // Cyrillic
        else -> {
            val script = Character.UnicodeScript.of(cp)
            script == Character.UnicodeScript.LATIN || script == Character.UnicodeScript.GREEK
        }
    }

    /** Arabic harakat, shadda, sukun and superscript alef: optional vowel marks. */
    private fun isArabicVowelMark(cp: Int): Boolean = cp in 0x064B..0x065F || cp == 0x0670

    /** Case-folds one letter and, for Latin and Greek, removes its diacritics. */
    private fun foldLetter(cp: Int): CharSequence {
        if (cp < 0x80) {
            return if (cp in 'A'.code..'Z'.code) (cp + 32).toChar().toString() else cp.toChar().toString()
        }
        LATIN_GREEK_FOLD[cp]?.let { return it }
        val lower = Character.toLowerCase(Character.toUpperCase(cp))
        return String(Character.toChars(lower))
    }

    /**
     * Latin letters spelled out instead of losing a mark: ş/ș and ç are the 2026 Uzbek
     * letters for sh and ch; the others have no decomposition.
     */
    private val SPECIAL_LATIN = mapOf(
        'ş' to "sh", 'Ş' to "sh", 'ș' to "sh", 'Ș' to "sh", 'ç' to "ch", 'Ç' to "ch",
        'ß' to "ss", 'ẞ' to "ss", 'ø' to "o", 'Ø' to "o", 'đ' to "d", 'Đ' to "d", 'ł' to "l", 'Ł' to "l",
        'ħ' to "h", 'Ħ' to "h", 'ŧ' to "t", 'Ŧ' to "t", 'æ' to "ae", 'Æ' to "ae", 'œ' to "oe", 'Œ' to "oe",
        'þ' to "th", 'Þ' to "th", 'ð' to "d", 'Ð' to "d", 'ı' to "i", 'ĸ' to "k", 'ŀ' to "l", 'Ŀ' to "l",
    )

    /**
     * Precomputed fold for Latin-1, Latin Extended-A/B, Latin Extended Additional, Greek
     * and Greek Extended: lower case without diacritics.
     */
    private val LATIN_GREEK_FOLD: Map<Int, String> = buildMap {
        val ranges = listOf(0x00C0..0x024F, 0x1E00..0x1EFF, 0x0370..0x03FF, 0x1F00..0x1FFF)
        for (range in ranges) for (cp in range) {
            if (!Character.isLetter(cp)) continue
            val special = SPECIAL_LATIN[cp.toChar()]
            if (special != null) {
                put(cp, special); continue
            }
            val lower = Character.toLowerCase(Character.toUpperCase(cp))
            val nfd = Normalizer.normalize(String(Character.toChars(lower)), Normalizer.Form.NFD)
            val sb = StringBuilder()
            var k = 0
            while (k < nfd.length) {
                val c = nfd.codePointAt(k)
                k += Character.charCount(c)
                val t = Character.getType(c)
                if (t != Character.NON_SPACING_MARK.toInt() && t != Character.COMBINING_SPACING_MARK.toInt() &&
                    t != Character.ENCLOSING_MARK.toInt()
                ) {
                    sb.appendCodePoint(c)
                }
            }
            val folded = sb.toString()
            if (folded.isNotEmpty() && folded != String(Character.toChars(cp))) put(cp, folded)
        }
    }
}
