package io.github.dilshodbekmx.passwordvault.core.util

/**
 * Control characters and unpaired surrogates: the text rules shared by input, import and every
 * writer (ux.md §2, formats.md §2 rule 8 and §8.4, formats-review P7, architecture.md §4.3).
 *
 * - **Input** refuses both (`isClean`, `firstControl`, `firstUnpairedSurrogate`), except line feeds
 *   in notes and field values.
 * - **Import** removes control characters from every value but a password and counts them
 *   (`removeControls`); a password is never altered (formats-review P14).
 * - **Every writer** passes its text through `replaceUnpairedSurrogates`, which turns each unpaired
 *   surrogate into U+FFFD and counts it, so that an export can say so instead of changing text
 *   silently. Without it a UTF-8 encoder writes `?` (0x3F) for one.
 *
 * A *control character* is U+0000 to U+001F or U+007F, exactly as ux.md §2 lists them. A line break
 * is stored as a line feed (U+000A); a carriage return is a control character, so text that may
 * hold line breaks goes through `normalizeLineBreaks` before it is checked.
 */
object TextHygiene {
    /** What an unpaired surrogate becomes in a writer's output. */
    const val REPLACEMENT_CHARACTER = '\uFFFD'

    /** Counts what the functions below replaced or removed, summed over every call it is given to. */
    class Counter {
        var count: Int = 0
            private set

        internal fun add(n: Int) {
            count += n
        }
    }

    /** U+0000 to U+001F and U+007F (ux.md §2). */
    fun isControl(c: Char): Boolean = c < ' ' || c == '\u007F'

    /**
     * The index of the first control character in [s] that a value may not hold, or -1. A line feed
     * is allowed when [lineBreaks] is true (notes and field values); a carriage return never is.
     */
    fun firstControl(s: CharSequence, lineBreaks: Boolean): Int {
        for (i in s.indices) {
            val c = s[i]
            if (isControl(c) && !(lineBreaks && c == '\n')) return i
        }
        return -1
    }

    /**
     * The index of the first unpaired surrogate in [s], or -1: a high surrogate not followed by a
     * low one, or a low surrogate not preceded by a high one.
     */
    fun firstUnpairedSurrogate(s: CharSequence): Int {
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < n && Character.isLowSurrogate(s[i + 1])) {
                    i += 2
                    continue
                }
                return i
            }
            if (Character.isLowSurrogate(c)) return i
            i++
        }
        return -1
    }

    fun hasUnpairedSurrogate(s: CharSequence): Boolean = firstUnpairedSurrogate(s) >= 0

    /**
     * The input rule of ux.md §2 and formats-review P7: no control character (a line feed is allowed
     * when [lineBreaks]) and no unpaired surrogate.
     */
    fun isClean(s: CharSequence, lineBreaks: Boolean): Boolean =
        firstControl(s, lineBreaks) < 0 && firstUnpairedSurrogate(s) < 0

    /**
     * The one function every writer passes its text through (formats.md §2 rule 8): each unpaired
     * surrogate becomes U+FFFD and is counted in [counter]. Returns [s] itself when it has none.
     */
    fun replaceUnpairedSurrogates(s: String, counter: Counter? = null): String {
        val first = firstUnpairedSurrogate(s)
        if (first < 0) return s
        val sb = StringBuilder(s.length)
        sb.append(s, 0, first)
        var replaced = 0
        var i = first
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(s[i + 1])) {
                sb.append(c).append(s[i + 1])
                i += 2
                continue
            }
            if (Character.isSurrogate(c)) {
                sb.append(REPLACEMENT_CHARACTER)
                replaced++
            } else {
                sb.append(c)
            }
            i++
        }
        counter?.add(replaced)
        return sb.toString()
    }

    /** CRLF and a lone CR become LF. Returns [s] itself when it holds no CR. */
    fun normalizeLineBreaks(s: String): String =
        if (s.indexOf('\r') < 0) s else s.replace("\r\n", "\n").replace('\r', '\n')

    /**
     * The importer's cleaning of a title, username, website, note or field (formats.md §8.4), never
     * of a password: when [lineBreaks] is true, line breaks are first made LF (not counted) and kept;
     * every other control character is removed and counted in [counter]. Unpaired surrogates are
     * not touched here: `replaceUnpairedSurrogates` is the rule for them. Returns [s] itself when
     * nothing changes.
     */
    fun removeControls(s: String, lineBreaks: Boolean, counter: Counter? = null): String {
        val text = if (lineBreaks) normalizeLineBreaks(s) else s
        val first = firstControl(text, lineBreaks)
        if (first < 0) return text
        val sb = StringBuilder(text.length)
        sb.append(text, 0, first)
        var removed = 0
        for (i in first until text.length) {
            val c = text[i]
            if (isControl(c) && !(lineBreaks && c == '\n')) removed++ else sb.append(c)
        }
        counter?.add(removed)
        return sb.toString()
    }
}
