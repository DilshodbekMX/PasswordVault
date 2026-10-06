package io.github.dilshodbekmx.passwordvault.core.text

import java.text.Normalizer
import java.util.Locale

/**
 * Which Uzbek Latin alphabet [UzbekScript.cyrillicToLatin] writes.
 *
 * - [OFFICIAL_1995]: the alphabet in force (1995): oʻ gʻ sh ch, with ʻ = U+02BB.
 * - [REFORM_2026]: the law passed by the Senate on 10 September 2026 and awaiting the
 *   President's signature on 6 October 2026: ö ğ ş ç replace oʻ gʻ sh ch (28 letters and
 *   one apostrophe; ng stays a letter combination). Its spelling rules are not yet
 *   published, so this mode is provisional: it also writes сҳ as plain "sh".
 */
enum class UzbekLatinAlphabet { OFFICIAL_1995, REFORM_2026 }

/**
 * Converters between the two scripts of Uzbek.
 *
 * The rules are those of the Uzbek Latin alphabet in force since 1995 (Cabinet of
 * Ministers resolution No. 339 of 24 August 1995, "Oʻzbek tilining asosiy imlo
 * qoidalari"), as applied in the official Latin editions of laws on lex.uz.
 *
 * - [cyrillicToLatin] is used by search (see `SearchText`) and may be used for display.
 *   On the Cyrillic and Latin editions of four laws on lex.uz (112,262 words) it gives the
 *   official Latin word for all but one word (a spelling that differs inside the reference).
 * - [latinToCyrillic] is a developer tool: it drafts the Uzbek Cyrillic UI strings from
 *   the Uzbek Latin ones for a human to review. It is not meant for user data. On the
 *   held-out laws 99.88 % of words come out exactly; most misses are ь at the end of
 *   Russian loanwords (kalendar: календарь), which the Latin spelling does not show.
 * - [UzbekLatinAlphabet.REFORM_2026] writes the letters ö ğ ş ç of the alphabet law
 *   approved by the Senate on 10 September 2026 (not yet signed on 6 October 2026).
 *
 * Pure Kotlin plus `java.text.Normalizer`; no Android dependencies (API 24 and later).
 */
object UzbekScript {

    /** ʻ U+02BB MODIFIER LETTER TURNED COMMA, written in oʻ and gʻ. */
    const val OKINA: Char = '\u02BB'

    /** ʼ U+02BC MODIFIER LETTER APOSTROPHE, the tutuq belgisi (Cyrillic ъ). */
    const val TUTUQ: Char = '\u02BC'

    /**
     * Every character people use for the oʻ/gʻ mark or the tutuq belgisi: the ASCII
     * apostrophe and grave accent, the acute accent, the curly quotes, the modifier
     * letters U+02BB to U+02BD, the prime, and a few rarer look-alikes.
     */
    fun isApostrophe(c: Char): Boolean = when (c) {
        '\u0027', '\u0060', '\u00B4', '\u2018', '\u2019', '\u02BB', '\u02BC', '\u02BD', '\u2032',
        '\u201B', '\u2035', '\u02B9', '\u02BE', '\u02BF', '\uA78B', '\uA78C', '\uFF07' -> true
        else -> false
    }

    // ------------------------------------------------------------------------------------
    // Cyrillic -> Latin
    // ------------------------------------------------------------------------------------

    /**
     * Converts Uzbek Cyrillic to Uzbek Latin. Characters that are not Cyrillic are kept.
     *
     * - е is "ye" at the start of a word and after a vowel, ъ or ь; "e" elsewhere.
     * - ц is "s" at the start of a word and after a consonant; "ts" after a vowel.
     * - ъ is the tutuq belgisi ʼ, except after a consonant before е, ё, ю, я (Russian
     *   loanwords such as объект: obyekt) and after ў (мўъжиза: moʻjiza), where it is dropped.
     * - ь is dropped ("y" before о, as in медальон: medalyon).
     * - сҳ is written sʼh (Исҳоқ: Isʼhoq) so that it is not read as sh.
     * - ў and ғ are written with [OKINA]: oʻ, gʻ.
     * - An upper-case letter written with two Latin letters becomes "Sh" inside a
     *   capitalised word and "SH" inside a word in capitals.
     * - [alphabet] = [UzbekLatinAlphabet.REFORM_2026] writes ö ğ ş ç instead (provisional).
     */
    fun cyrillicToLatin(text: String, alphabet: UzbekLatinAlphabet = UzbekLatinAlphabet.OFFICIAL_1995): String {
        val s = Normalizer.normalize(text, Normalizer.Form.NFC)
        if (!containsCyrillic(s)) return s
        val out = StringBuilder(s.length + s.length / 4)
        appendCyrillicToLatin(s, out, forSearch = false, reform = alphabet == UzbekLatinAlphabet.REFORM_2026)
        return out.toString()
    }

    internal fun containsCyrillic(s: CharSequence): Boolean {
        for (c in s) if (c in '\u0400'..'\u04FF') return true
        return false
    }

    /**
     * Appends the Latin transliteration of [s] to [out]. [forSearch] also maps letters of
     * other Cyrillic alphabets used in the region (Karakalpak, Kazakh, Tajik, Ukrainian)
     * to their nearest Latin letters; display conversion leaves those untouched.
     */
    internal fun appendCyrillicToLatin(s: String, out: StringBuilder, forSearch: Boolean, reform: Boolean = false) {
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (c < '\u0400' || c > '\u04FF') {
                out.append(c); i++; continue
            }
            val lc = Character.toLowerCase(c)
            if (isWordStart(s, i)) {
                val stem = matchCyrillicStem(s, i)
                if (stem != null) {
                    appendWithWordCase(out, s, i, stem.first.length, stem.second)
                    i += stem.first.length
                    continue
                }
            }
            val latin = latinFor(s, i, lc, forSearch, reform)
            if (latin == null) {
                out.append(c)
            } else if (latin.isNotEmpty()) {
                if (lc == c) out.append(latin) else appendUpper(out, latin, allCapsAt(s, i))
            }
            i++
        }
    }

    private const val CYR_VOWELS = "аеёиоуэюяўыієїәөүұӣӯ"
    private const val LAT_VOWELS = "aeiouö"

    private fun isVowelChar(c: Char): Boolean {
        val l = Character.toLowerCase(c)
        return CYR_VOWELS.indexOf(l) >= 0 || LAT_VOWELS.indexOf(l) >= 0
    }

    private fun isWordStart(s: CharSequence, i: Int): Boolean = i == 0 || !Character.isLetter(s[i - 1])

    private fun lowerAt(s: CharSequence, i: Int): Char =
        if (i in 0 until s.length) Character.toLowerCase(s[i]) else '\u0000'

    /** Latin for the lower-case Cyrillic letter [lc] at [i]; null if it is not mapped. */
    private fun latinFor(s: CharSequence, i: Int, lc: Char, forSearch: Boolean, reform: Boolean): String? {
        val prev = lowerAt(s, i - 1)
        val next = lowerAt(s, i + 1)
        return when (lc) {
            'а' -> "a"
            'б' -> "b"
            'в' -> "v"
            'г' -> "g"
            'д' -> "d"
            'е' -> if (isWordStart(s, i) || isVowelChar(prev) || prev == 'ъ' || prev == 'ь') "ye" else "e"
            'ё' -> "yo"
            'ж' -> "j"
            'з' -> "z"
            'и' -> "i"
            'й' -> "y"
            'к' -> "k"
            'л' -> "l"
            'м' -> "m"
            'н' -> "n"
            'о' -> "o"
            'п' -> "p"
            'р' -> "r"
            'с' -> if (next == 'ҳ' && !reform) "s$TUTUQ" else "s"
            'т' -> "t"
            'у' -> "u"
            'ф' -> "f"
            'х' -> "x"
            'ц' -> if (!isWordStart(s, i) && isVowelChar(prev)) "ts" else "s"
            'ч' -> if (reform) "ç" else "ch"
            'ш' -> if (reform) "ş" else "sh"
            'щ' -> if (reform) "ş" else "sh"
            'ъ' -> when {
                prev == 'ў' -> ""
                (next == 'е' || next == 'ё' || next == 'ю' || next == 'я') &&
                    Character.isLetter(s.getOrElse(i - 1) { ' ' }) && !isVowelChar(prev) -> ""
                else -> TUTUQ.toString()
            }
            'ы' -> "i"
            'ь' -> if (next == 'о') "y" else ""
            'э' -> "e"
            'ю' -> "yu"
            'я' -> "ya"
            'ў' -> if (reform) "ö" else "o$OKINA"
            'қ' -> "q"
            'ғ' -> if (reform) "ğ" else "g$OKINA"
            'ҳ' -> "h"
            else -> if (forSearch) otherCyrillicForSearch(lc) else null
        }
    }

    /** Letters of neighbouring Cyrillic alphabets, mapped only for search. */
    private fun otherCyrillicForSearch(lc: Char): String? = when (lc) {
        'і' -> "i"
        'ї' -> "yi"
        'є' -> "ye"
        'ґ' -> "g"
        'ә' -> "a"
        'ө' -> "o"
        'ү' -> "u"
        'ұ' -> "u"
        'ң' -> "n"
        'һ' -> "h"
        'ҷ' -> "j"
        'ӣ' -> "i"
        'ӯ' -> "u"
        'ј' -> "j"
        else -> null
    }

    /**
     * True when the upper-case letter at [i] is inside a word written in capitals: the
     * next letter is upper case, or the word ends here and the previous letter is upper case.
     */
    private fun allCapsAt(s: CharSequence, i: Int): Boolean {
        var j = i + 1
        while (j < s.length && isApostrophe(s[j])) j++
        if (j < s.length && Character.isLetter(s[j])) return Character.isUpperCase(s[j])
        var k = i - 1
        while (k >= 0 && isApostrophe(s[k])) k--
        return k >= 0 && Character.isLetter(s[k]) && Character.isUpperCase(s[k])
    }

    private fun appendUpper(out: StringBuilder, latin: String, allCaps: Boolean) {
        var first = true
        for (ch in latin) {
            if (Character.isLetter(ch) && (first || allCaps)) out.append(Character.toUpperCase(ch)) else out.append(ch)
            if (Character.isLetter(ch)) first = false
        }
    }

    /**
     * Words whose official Latin spelling departs from the letter rules. Keys are
     * lower-case Cyrillic word beginnings; the rest of the word is converted normally.
     */
    private val CYRILLIC_STEMS: List<Pair<String, String>> = listOf(
        "сентябр" to "sentabr",
        "октябр" to "oktabr",
        "бюджет" to "budjet",
        "мавқеи" to "mavqeyi",
    ).sortedByDescending { it.first.length }

    private fun matchCyrillicStem(s: String, i: Int): Pair<String, String>? {
        for (p in CYRILLIC_STEMS) {
            if (s.regionMatches(i, p.first, 0, p.first.length, ignoreCase = true)) return p
        }
        return null
    }

    /** Appends [replacement] for s[i, i+len) with the case pattern of the source word. */
    private fun appendWithWordCase(out: StringBuilder, s: CharSequence, i: Int, len: Int, replacement: String) {
        val firstUpper = Character.isUpperCase(s[i])
        val secondUpper = len > 1 && Character.isUpperCase(s[i + 1])
        when {
            firstUpper && (secondUpper || (len == 1 && allCapsAt(s, i))) -> out.append(replacement.uppercase(Locale.ROOT))
            firstUpper -> {
                out.append(Character.toUpperCase(replacement[0]))
                out.append(replacement, 1, replacement.length)
            }
            else -> out.append(replacement)
        }
    }

    // ------------------------------------------------------------------------------------
    // Latin -> Cyrillic (developer tool)
    // ------------------------------------------------------------------------------------

    /** Words left in Latin by default: product names and acronyms that UI strings keep. */
    val DEFAULT_KEEP: Set<String> = setOf(
        "Android", "Google", "Gmail", "Chrome", "Wi-Fi", "WiFi", "Bluetooth", "USB", "PIN", "QR",
        "CSV", "JSON", "URL", "AES", "GCM", "Argon2id", "KeePass", "KeePassDX", "Bitwarden",
        "PasswordVault", "OK", "ID", "SMS", "NFC", "API",
    )

    /**
     * Drafts Uzbek Cyrillic from Uzbek Latin, for UI strings that a person then reviews.
     *
     * Rules: sh ш, ch ч, oʻ ў, gʻ ғ (any apostrophe; the 2026 letters ş ç ö ğ too), ng нг,
     * yo ё, yu ю, ya я; ye е at the start of a word or after a vowel and ъе after a
     * consonant (obyekt: объект); e э at the start of a word or after a vowel, е elsewhere;
     * sʼh сҳ (not ш); yoʻ йў; a tutuq belgisi between letters ъ; x х, h ҳ, q қ, j ж, y й.
     * "ts" and "s" become ц before i + vowel in loanwords (-tsiya, -tsion, -tsiyent, and
     * -siya/-sion after k, n or p) and in the listed stems; month names alone keep ь.
     * Upper-case Roman numerals (XIV) stay in Latin.
     *
     * Android format placeholders (%1$s, %d), escapes (\n, \'), markup (<b>) and words in
     * [keep] are copied unchanged. Letters outside the Uzbek alphabet (c without h, w) are
     * left in Latin so that a reviewer sees them.
     */
    fun latinToCyrillic(text: String, keep: Set<String> = DEFAULT_KEEP): String {
        val s = Normalizer.normalize(text, Normalizer.Form.NFC)
        val out = StringBuilder(s.length)
        convertMessage(s, 0, s.length, out, keep)
        return out.toString()
    }

    /**
     * Converts s[from, to), keeping ICU MessageFormat syntax: a simple argument ({name},
     * {0, number}) is copied; in {n, plural|select|selectordinal, ...} the argument, the
     * selectors (one, other, =1) and # are copied and the sub-messages are converted.
     */
    private fun convertMessage(s: String, from: Int, to: Int, out: StringBuilder, keep: Set<String>) {
        var i = from
        var textStart = from
        while (i < to) {
            if (s[i] == '{') {
                val close = matchingBrace(s, i, to)
                if (close > 0) {
                    convertPlain(s, textStart, i, out, keep)
                    convertArgument(s, i, close, out, keep)
                    i = close + 1
                    textStart = i
                    continue
                }
            }
            i++
        }
        convertPlain(s, textStart, to, out, keep)
    }

    private val CHOICE_HEAD = Regex("\\{\\s*\\w+\\s*,\\s*(?:plural|select|selectordinal)\\s*,")

    private fun convertArgument(s: String, open: Int, close: Int, out: StringBuilder, keep: Set<String>) {
        val head = CHOICE_HEAD.find(s.substring(open, close))
        if (head == null || head.range.first != 0) {
            out.append(s, open, close + 1); return // {name}, {0, number, integer}, ...
        }
        out.append(head.value)
        var i = open + head.value.length
        while (i < close) {
            if (s[i] == '{') {
                val end = matchingBrace(s, i, close)
                if (end < 0) break
                out.append('{')
                convertMessage(s, i + 1, end, out, keep)
                out.append('}')
                i = end + 1
            } else {
                out.append(s[i]); i++ // selectors, offset:1, spaces
            }
        }
        out.append(s, i, close + 1)
    }

    /** Index of the brace closing the one at [open], before [limit]; -1 if none. */
    private fun matchingBrace(s: String, open: Int, limit: Int): Int {
        var depth = 0
        for (k in open until limit) {
            when (s[k]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return k
            }
        }
        return -1
    }

    /** Converts text outside ICU arguments, copying placeholders, escapes, markup and links. */
    private fun convertPlain(s: String, from: Int, to: Int, out: StringBuilder, keep: Set<String>) {
        if (from >= to) return
        var last = from
        val m0 = PROTECTED.find(s.subSequence(0, to), from)
        var m = m0
        while (m != null) {
            convertLatinRun(s, last, m.range.first, out, keep)
            out.append(m.value)
            last = m.range.last + 1
            m = m.next()
        }
        convertLatinRun(s, last, to, out, keep)
    }

    private val PROTECTED = Regex(
        "%(?:\\d+\\$)?[-#+0,(]*\\d*(?:\\.\\d+)?[a-zA-Z%]" + // format specifiers
            "|\\\\u[0-9a-fA-F]{4}|\\\\." + // escapes
            "|<[^>]*>|&[a-zA-Z]+;|&#\\d+;" + // markup and entities
            "|https?://\\S+|[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+", // links and e-mail addresses
    )

    /** ASCII letters plus the letters of the 2026 alphabet (ö ğ ş ç, and ș for ş). */
    private fun isLatinLetter(c: Char): Boolean = (c in 'a'..'z') || (c in 'A'..'Z') || REFORM_LETTERS.indexOf(c) >= 0

    private const val REFORM_LETTERS = "öÖğĞşŞçÇșȘ"

    private fun convertLatinRun(s: String, from: Int, to: Int, out: StringBuilder, keep: Set<String>) {
        var i = from
        while (i < to) {
            val c = s[i]
            if (!isLatinLetter(c)) {
                out.append(c); i++; continue
            }
            // A word: Latin letters, plus an apostrophe that is the oʻ/gʻ mark or a tutuq.
            var j = i
            while (j < to) {
                val d = s[j]
                if (isLatinLetter(d)) {
                    j++
                } else if (isApostrophe(d) && j > i && apostropheBelongsToWord(s, j, to)) {
                    j++
                } else {
                    break
                }
            }
            // Keep-list check on the word including a following "-" compound (Wi-Fi).
            val word = s.substring(i, j)
            val hyphenWord = if (j + 1 < to && s[j] == '-' && isLatinLetter(s[j + 1])) {
                var k = j + 1
                while (k < to && isLatinLetter(s[k])) k++
                s.substring(i, k)
            } else {
                null
            }
            val keptPrefix = if (word in keep) word else keep.firstOrNull { it.length < word.length && word.startsWith(it) }
            when {
                hyphenWord != null && hyphenWord in keep -> {
                    out.append(hyphenWord); i += hyphenWord.length
                }
                keptPrefix != null -> {
                    // "Google" or "Googleda": keep the name, convert an Uzbek suffix after it.
                    out.append(keptPrefix)
                    var k = keptPrefix.length
                    while (k < word.length && isApostrophe(word[k])) out.append(word[k++])
                    if (k < word.length) convertLatinWord(word.substring(k), out)
                    i = j
                }
                else -> {
                    convertLatinWord(word, out); i = j
                }
            }
        }
    }

    /** An apostrophe at [j] (after a letter) is part of the word when it is oʻ/gʻ or a tutuq. */
    private fun apostropheBelongsToWord(s: String, j: Int, to: Int): Boolean {
        val prev = Character.toLowerCase(s[j - 1])
        val nextIsLetter = j + 1 < to && isLatinLetter(s[j + 1])
        return when {
            prev == 'o' || prev == 'g' -> true // oʻ and gʻ, also at the end of a word (togʻ)
            nextIsLetter -> true // tutuq belgisi between letters (maʼno)
            else -> s[j] == TUTUQ || s[j] == OKINA // word-final modifier letter; quotes stay quotes
        }
    }

    /** Latin -> Cyrillic for single letters (lower case). */
    private fun cyrillicLetter(c: Char): Char? = when (c) {
        'a' -> 'а'; 'b' -> 'б'; 'd' -> 'д'; 'e' -> 'е'; 'f' -> 'ф'; 'g' -> 'г'; 'h' -> 'ҳ'
        'i' -> 'и'; 'j' -> 'ж'; 'k' -> 'к'; 'l' -> 'л'; 'm' -> 'м'; 'n' -> 'н'; 'o' -> 'о'
        'p' -> 'п'; 'q' -> 'қ'; 'r' -> 'р'; 's' -> 'с'; 't' -> 'т'; 'u' -> 'у'; 'v' -> 'в'
        'x' -> 'х'; 'y' -> 'й'; 'z' -> 'з'
        'ö' -> 'ў'; 'ğ' -> 'ғ'; 'ş' -> 'ш'; 'ș' -> 'ш'; 'ç' -> 'ч'
        else -> null
    }

    /**
     * Loanwords whose Cyrillic spelling the letter rules cannot recover: mostly ц written
     * s or ts in Latin, ь or ъ before я/ю/ё, and э after a consonant. Keys are lower-case
     * Latin word beginnings with apostrophes written ', values the Cyrillic beginning.
     * A word equal to a key in [LATIN_WHOLE_WORDS] takes that form instead (months keep ь
     * only when they have no suffix: апрель, апрелда).
     */
    private val LATIN_STEMS: List<Pair<String, String>> = listOf(
        // ц written "s" (start of word, after a consonant) or "ts" before a non-i vowel
        "prinsip" to "принцип",
        "protsess" to "процесс",
        "protsedur" to "процедур",
        "senzur" to "цензур",
        "sivil" to "цивил",
        "sement" to "цемент",
        "sentner" to "центнер",
        "sirk" to "цирк",
        "sikl" to "цикл",
        "silindr" to "цилиндр",
        "sitat" to "цитат",
        "sitrus" to "цитрус",
        "sex" to "цех",
        "konsert" to "концерт",
        "konsep" to "концеп",
        "kansler" to "канцлер",
        "aksent" to "акцент",
        "aksiz" to "акциз",
        "litsenz" to "лиценз",
        "litsey" to "лицей",
        "ofitser" to "офицер",
        "spetsial" to "специал",
        "spetsifik" to "специфик",
        "sotsial" to "социал",
        "abzats" to "абзац",
        "shprits" to "шприц",
        "shveysar" to "швейцар",
        // -siya/-sion with с although a k, n or p comes before it
        "pensi" to "пенси",
        "ekspansi" to "экспанси",
        // ь or ъ before я, ю, ё, е
        "sudya" to "судья",
        "kompyuter" to "компьютер",
        "pyesa" to "пьеса",
        "premyer" to "премьер",
        "intervyu" to "интервью",
        "obyom" to "объём",
        "sentabr" to "сентябр",
        "oktabr" to "октябр",
        // э after a consonant (compound) and йо written yo
        "umume" to "умумэ",
        "budjet" to "бюджет",
        "mavqeyi" to "мавқеи",
        "rayon" to "район",
        "mayor" to "майор",
    ).sortedByDescending { it.first.length }

    private val LATIN_WHOLE_WORDS: Map<String, String> = mapOf(
        "yanvar" to "январь",
        "fevral" to "февраль",
        "aprel" to "апрель",
        "iyun" to "июнь",
        "iyul" to "июль",
        "sentabr" to "сентябрь",
        "oktabr" to "октябрь",
        "noyabr" to "ноябрь",
        "dekabr" to "декабрь",
    )

    /** Lower case with every apostrophe written as ', for dictionary lookups. */
    private fun lookupKey(word: String): String {
        val sb = StringBuilder(word.length)
        for (c in word) sb.append(if (isApostrophe(c)) '\'' else Character.toLowerCase(c))
        return sb.toString()
    }

    /** Upper-case Roman numerals (I to MMMCMXCIX), which Cyrillic text also writes in Latin. */
    private val ROMAN = Regex("(?=[MDCLXVI])M{0,3}(?:CM|CD|D?C{0,3})(?:XC|XL|L?X{0,3})(?:IX|IV|V?I{0,3})")

    private fun convertLatinWord(word: String, out: StringBuilder) {
        if (ROMAN.matches(word)) {
            out.append(word); return
        }
        val key = lookupKey(word)
        LATIN_WHOLE_WORDS[key]?.let { appendWithWordCase(out, word, 0, word.length, it); return }
        var start = 0
        for (p in LATIN_STEMS) {
            if (key.startsWith(p.first)) {
                appendWithWordCase(out, word, 0, p.first.length, p.second)
                start = p.first.length
                break
            }
        }
        convertLatinLetters(word, key, start, out)
    }

    private fun isLatinVowelAt(key: String, j: Int): Boolean {
        if (j < 0) return false
        val c = key[j]
        if (c == '\'') {
            // oʻ is a vowel; a tutuq (ъ) is not.
            return j > 0 && key[j - 1] == 'o'
        }
        return LAT_VOWELS.indexOf(c) >= 0
    }

    private fun isApostropheAt(key: String, j: Int): Boolean = j in key.indices && key[j] == '\''

    /** True when the "s" (or "ts") at [j] spells ц: consonant k/n/p + s, or ts, before i + vowel. */
    private fun spellsTse(key: String, j: Int, len: Int): Boolean {
        val after = j + len
        if (after >= key.length || key[after] != 'i') return false
        val v = key.getOrNull(after + 1) ?: return false
        val beforeVowel = LAT_VOWELS.indexOf(v) >= 0 ||
            (v == 'y' && key.getOrNull(after + 2)?.let { LAT_VOWELS.indexOf(it) >= 0 } == true)
        if (!beforeVowel) return false
        if (len == 2) return true // "ts" + i + vowel: -tsiya, -tsion, -tsiyent
        val prev = key.getOrNull(j - 1)
        return prev == 'k' || prev == 'n' || prev == 'p'
    }

    private fun convertLatinLetters(word: String, key: String, from: Int, out: StringBuilder) {
        var j = from
        val n = key.length
        while (j < n) {
            val c = key[j]
            val upper = Character.isUpperCase(word[j])
            val next = key.getOrNull(j + 1)
            val next2 = key.getOrNull(j + 2)
            var consumed = 1
            val cyr: String = when {
                c == '\'' -> {
                    // A tutuq belgisi (oʻ and gʻ were consumed with their letter).
                    val prevUpper = j > 0 && Character.isUpperCase(word[j - 1])
                    val nextUpper = j + 1 < n && Character.isUpperCase(word[j + 1])
                    if (prevUpper && (nextUpper || j + 1 >= n)) "Ъ" else "ъ"
                }
                c == 's' && next == 'h' -> { consumed = 2; "ш" }
                c == 's' && next == '\'' && next2 == 'h' -> { consumed = 2; "с" } // sʼh: с + ҳ
                c == 'c' && next == 'h' -> { consumed = 2; "ч" }
                c == 'o' && next == '\'' -> { consumed = 2; "ў" }
                c == 'g' && next == '\'' -> { consumed = 2; "ғ" }
                c == 'y' && next == 'o' && isApostropheAt(key, j + 2) -> "й" // yoʻl: йўл
                c == 'y' && next == 'o' -> { consumed = 2; "ё" }
                c == 'y' && next == 'u' -> { consumed = 2; "ю" }
                c == 'y' && next == 'a' -> { consumed = 2; "я" }
                c == 'y' && next == 'e' -> {
                    consumed = 2
                    if (j == 0 || isLatinVowelAt(key, j - 1)) "е" else "ъе"
                }
                c == 'e' -> if (j == 0 || isLatinVowelAt(key, j - 1)) "э" else "е"
                c == 't' && next == 's' && spellsTse(key, j, 2) -> { consumed = 2; "ц" }
                c == 's' && spellsTse(key, j, 1) -> "ц"
                else -> cyrillicLetter(c)?.toString() ?: word[j].toString()
            }
            if (upper && cyr.isNotEmpty() && cyr != "Ъ") {
                if (cyr == "ъе") {
                    // Upper-case Y after a consonant: both letters follow the word's case.
                    val nextUpper = j + 1 < n && Character.isUpperCase(word[j + 1])
                    out.append(if (nextUpper) "ЪЕ" else "Ъе")
                } else {
                    out.append(cyr.uppercase(Locale.ROOT))
                }
            } else {
                out.append(cyr)
            }
            j += consumed
        }
    }
}
