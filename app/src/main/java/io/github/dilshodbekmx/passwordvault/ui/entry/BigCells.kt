package io.github.dilshodbekmx.passwordvault.ui.entry

import java.text.BreakIterator
import java.util.Locale

/**
 * One box of "Show in big letters" (ux.md §5.13): one character as a person reads and types it.
 *
 * @property text what the box shows: the character itself; nothing for a space or a line break
 *   (the box then says so in words); for a character that cannot be seen, its code ("U+200B").
 * @property kind what the character is: its colour, and the word TalkBack says before it, because
 *   a voice does not tell "A" from "a" nor "O" from "0".
 */
data class BigCell(val text: String, val kind: Kind) {
    enum class Kind { UPPER, LOWER, LETTER, DIGIT, SYMBOL, SPACE, LINE_BREAK, INVISIBLE }

    /** A value's characters are a secret: [toString] shows only the kind (architecture.md §5.6). */
    override fun toString(): String = "BigCell($kind)"

    companion object {
        /** "Show in big letters" shows at most this many characters (ux.md §5.13: Keyguard's limit). */
        const val MAX: Int = 128

        /**
         * The boxes of [value], in order.
         *
         * A box holds one grapheme: a letter with its combining marks is one box, as it is one
         * shape on the screen and one key on a keyboard, and so is an emoji made of several code
         * points. A line break written as two characters is one box too.
         *
         * A character that leaves no mark gets a box that says what it is, so that nobody types a
         * password one character short: the plain space is "space", a line break "new line", and
         * any other invisible character (a no-break space, a zero-width space, a control
         * character) shows its code, because it is not typed with the space bar.
         */
        fun of(value: String): List<BigCell> {
            val cells = ArrayList<BigCell>(value.length)
            val graphemes = BreakIterator.getCharacterInstance(Locale.ROOT)
            graphemes.setText(value)
            var start = graphemes.first()
            var end = graphemes.next()
            while (end != BreakIterator.DONE) {
                cells += cellOf(value.substring(start, end))
                start = end
                end = graphemes.next()
            }
            return cells
        }

        private fun cellOf(grapheme: String): BigCell {
            val first = grapheme.codePointAt(0)
            return when {
                first == ' '.code -> BigCell("", Kind.SPACE)
                isLineBreak(first) -> BigCell("", Kind.LINE_BREAK)
                isInvisible(first) -> BigCell(codeOf(first), Kind.INVISIBLE)
                Character.isDigit(first) -> BigCell(grapheme, Kind.DIGIT)
                Character.isUpperCase(first) || Character.isTitleCase(first) -> BigCell(grapheme, Kind.UPPER)
                Character.isLowerCase(first) -> BigCell(grapheme, Kind.LOWER)
                Character.isLetter(first) -> BigCell(grapheme, Kind.LETTER)
                else -> BigCell(grapheme, Kind.SYMBOL)
            }
        }

        private fun isLineBreak(codePoint: Int): Boolean =
            codePoint == '\n'.code || codePoint == '\r'.code || codePoint == 0x85 || codePoint == 0x2028 || codePoint == 0x2029

        /** White space other than the plain space, control and format characters, and code points that are no character at all. */
        private fun isInvisible(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
            Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
            Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.UNASSIGNED,
            -> true
            else -> false
        }

        /** "U+200B": the code point in at least four hexadecimal digits, as Unicode writes it. */
        private fun codeOf(codePoint: Int): String = "U+" + codePoint.toString(16).uppercase(Locale.ROOT).padStart(4, '0')
    }
}
