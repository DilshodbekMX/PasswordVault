package io.github.dilshodbekmx.passwordvault.core.search

/**
 * The Russian ЙЦУКЕН and the Latin QWERTY keyboard layouts, key by key, for a query typed while
 * the keyboard was on the wrong one (search.md §2, architecture.md D-11). [SearchIndex.searchOrRetry]
 * uses it when a query finds nothing.
 */
object KeyboardLayout {

    /**
     * The characters on the keys of QWERTY and of ЙЦУКЕН, in the same order: the 26 Latin letters
     * and the seven keys [ ] ; ' , . ` against the 33 Russian letters (search.md §2's table).
     */
    private const val QWERTY = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`"
    private const val JCUKEN = "йцукенгшщзхъфывапролджэячсмитьбюё"

    /**
     * [query] as the same keys give it on the other layout: "штые" becomes "inst", and "ghbdtn"
     * becomes "привет". The direction is chosen once for the whole query: when it has more
     * Cyrillic letters than Latin ones, each Cyrillic letter of the table becomes the QWERTY
     * character on its key; otherwise each QWERTY character of the table becomes the ЙЦУКЕН letter
     * on its key. A capital letter gives a capital letter (Й and Q); a capital Х Ъ Ж Э Б Ю Ё gives
     * the key's character [ ] ; ' , . `, which has no capital. Everything else is kept: digits,
     * spaces, other characters, and the Uzbek letters ў қ ғ ҳ, which sit on different keys in
     * different Uzbek layouts. Returns [query] itself when nothing changes.
     */
    fun otherLayout(query: String): String {
        val toQwerty = cyrillicLetters(query) > latinLetters(query)
        val from = if (toQwerty) JCUKEN else QWERTY
        val to = if (toQwerty) QWERTY else JCUKEN
        var out: CharArray? = null
        for (i in query.indices) {
            val r = onOtherKey(query[i], from, to)
            if (r == query[i]) continue
            val chars = out ?: query.toCharArray().also { out = it }
            chars[i] = r
        }
        return out?.let { String(it) } ?: query
    }

    /**
     * The character of [to] on the key of [c] in [from], or [c] when [c] is neither a character
     * of [from] nor the capital of one. Only the exact capital counts: İ and the Kelvin sign, whose
     * lower case is i and k, stay as they are.
     */
    private fun onOtherKey(c: Char, from: String, to: String): Char {
        val k = from.indexOf(c)
        if (k >= 0) return to[k]
        val lower = from.indexOf(c.lowercaseChar())
        if (lower < 0 || from[lower].uppercaseChar() != c) return c
        return to[lower].uppercaseChar()
    }

    private fun cyrillicLetters(s: String): Int = countLetters(s, Character.UnicodeScript.CYRILLIC)

    private fun latinLetters(s: String): Int = countLetters(s, Character.UnicodeScript.LATIN)

    private fun countLetters(s: String, script: Character.UnicodeScript): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.isLetter(cp) && Character.UnicodeScript.of(cp) == script) n++
        }
        return n
    }
}
