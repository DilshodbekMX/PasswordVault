package io.github.dilshodbekmx.passwordvault.files

/**
 * A name that another app gave, as the app shows and keeps it: a document's display name ("Backup
 * saved: …", "Delete the empty file … yourself", the `name` kept with `backups` and `plainExports`
 * in the vault) and the name of a place ("Saved to …"). The place behind the file picker is any app
 * with a documents provider, so its names are not trusted: one may hold line breaks that put a
 * sentence of its own under the app's, direction marks that turn the rest of the line around, and
 * megabytes of text.
 *
 * [of] keeps what a name is made of and nothing else:
 * - a control character, a line separator or a paragraph separator becomes a space;
 * - a format character is left out: direction marks, embeddings, overrides and isolates, zero-width
 *   characters, the soft hyphen;
 * - an unpaired surrogate becomes U+FFFD, as the vault's writer makes it (formats.md §2 rule 8);
 * - white space at either end goes;
 * - a name longer than [MAX_LENGTH] is cut and ends in "…".
 *
 * A name with nothing left is no name (null): the caller shows what it has instead, the suggested
 * name or the provider's authority. A name that a place really shows comes back as it is.
 */
internal object ShownName {
    /** The longest name shown, in UTF-16 units, the mark of a cut name included. */
    const val MAX_LENGTH = 255

    private const val CUT_MARK = '\u2026' // …
    private const val REPLACEMENT = 0xFFFD

    fun of(raw: String?): String? {
        if (raw == null) return null
        val name = StringBuilder(minOf(raw.length, MAX_LENGTH))
        // White space since the last character kept: written only when another character follows it.
        val space = StringBuilder()
        var cut = false
        var i = 0
        while (i < raw.length) {
            val read = raw.codePointAt(i) // an unpaired surrogate comes as itself
            i += Character.charCount(read)
            val kept = when (Character.getType(read)) {
                Character.FORMAT.toInt() -> continue
                Character.CONTROL.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt() -> ' '.code
                Character.SURROGATE.toInt() -> REPLACEMENT
                else -> read
            }
            if (isSpace(kept)) {
                if (name.isNotEmpty() && space.length < MAX_LENGTH) space.appendCodePoint(kept)
                continue
            }
            if (name.length + space.length + Character.charCount(kept) > MAX_LENGTH) {
                cut = true
                break
            }
            name.append(space).appendCodePoint(kept)
            space.setLength(0)
        }
        if (cut) {
            // Room for the mark, at a whole character, and no space before it.
            while (name.isNotEmpty() && (name.length > MAX_LENGTH - 1 || isSpace(name.codePointBefore(name.length)))) {
                name.setLength(name.length - Character.charCount(name.codePointBefore(name.length)))
            }
            name.append(CUT_MARK)
        }
        return if (name.isEmpty()) null else name.toString()
    }

    private fun isSpace(codePoint: Int): Boolean = Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
}
