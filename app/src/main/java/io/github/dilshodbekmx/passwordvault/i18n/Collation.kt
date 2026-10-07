package io.github.dilshodbekmx.passwordvault.i18n

/**
 * The A-to-Z order and the letter headers of the list, for one language (ux.md §5.11, i18n.md
 * §2.7). `SortKeys` (build plan 2.9) implements it with `android.icu.text.Collator` and
 * `AlphabeticIndex` for the app's language, on the sort key of i18n.md §2.7, and is tested on a
 * device (architecture.md §8.3); JVM tests use a simple one. The list is rebuilt with a new
 * [Collation] when the language changes (architecture.md §5.1).
 *
 * The two functions agree: titles in [compare] order have their [header]s in the order of the
 * headers, so the list's sections can be cut from the sorted titles.
 */
interface Collation {
    /** The order of two titles in this language: negative when [a] comes first, 0 when they sort alike. */
    fun compare(a: String, b: String): Int

    /**
     * The letter header under which [title] is listed: "A", "Ш", "Oʻ" (Uzbek on ICU 78 lists Oʻ,
     * Gʻ, Sh and Ch after Z), or a catch-all such as "#" for titles that start with a digit or a
     * symbol.
     */
    fun header(title: String): String
}
