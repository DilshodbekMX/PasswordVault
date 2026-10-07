package io.github.dilshodbekmx.passwordvault.i18n

import android.icu.text.AlphabeticIndex
import android.icu.text.CollationKey
import android.icu.text.Collator
import android.icu.text.RuleBasedCollator
import android.icu.util.ULocale
import java.util.Locale

/**
 * The A-to-Z order and the letter headers of the list in one language (ux.md §5.11, i18n.md §2.7;
 * build plan 2.9): ICU's collator for [locale] and an `AlphabeticIndex` over the same collator, with
 * the labels of [locale], English and Russian (and of both Uzbek alphabets for Uzbek), both fed the
 * sort text of [sortTextOf]. Android's ICU decides the order: Android 9 and 17 (ICU 60 and 78) sort
 * Uzbek Oʻ, Gʻ, Sh and Ch after Z and head them so, in both Uzbek scripts; Android 7 (ICU 56) has no
 * Uzbek order and puts them under O, G, S and C, which again matches its sort; Russian puts Cyrillic
 * before Latin, the others Latin before Cyrillic. Tested on a device (architecture.md §8.3).
 *
 * Headers agree with [compare]: the collator and the index are the same, so titles in [compare]
 * order have their headers in the index's order, each header one run of titles. The catch-alls:
 * titles before the first letter (digits, signs) are under [UNDERFLOW_LABEL], titles in a script
 * after the last labelled one (Arabic, Chinese, …) under [OVERFLOW_LABEL], and a title of a script
 * that falls between two labelled ones (Greek, between Latin and Cyrillic) under the header just
 * before it, so that no header heads two runs.
 *
 * Built for one language and immutable: make a new one when the language changes. [compare],
 * [header] and [key] may be called from any thread (the collator is frozen, the index immutable).
 * Building one takes some milliseconds: not on every recomposition.
 */
class SortKeys(val locale: Locale) : Collation {
    private val collator: RuleBasedCollator =
        (Collator.getInstance(ULocale.forLocale(locale)) as RuleBasedCollator).apply { freeze() }

    private val index: AlphabeticIndex.ImmutableIndex<Any> = AlphabeticIndex<Any>(collator)
        .addLabels(*labelLocales(locale))
        .setUnderflowLabel(UNDERFLOW_LABEL)
        .setOverflowLabel(OVERFLOW_LABEL)
        .buildImmutableIndex()

    /** The header of each bucket of [index], with the inflow buckets given the label before them. */
    private val labels: List<String> = run {
        var previous = UNDERFLOW_LABEL
        List(index.bucketCount) { i ->
            val bucket = index.getBucket(i)
            val label = when (bucket.labelType) {
                AlphabeticIndex.Bucket.LabelType.UNDERFLOW -> UNDERFLOW_LABEL
                AlphabeticIndex.Bucket.LabelType.OVERFLOW -> OVERFLOW_LABEL
                AlphabeticIndex.Bucket.LabelType.INFLOW -> previous
                else -> bucket.label
            }
            previous = label
            label
        }
    }

    override fun compare(a: String, b: String): Int = collator.compare(sortTextOf(a), sortTextOf(b))

    override fun header(title: String): String = labels[index.getBucketIndex(sortTextOf(title))]

    /** The collation key of [title]: to sort many titles, compare these instead of calling [compare] each time. */
    fun key(title: String): CollationKey = collator.getCollationKey(sortTextOf(title))

    /** Every header this index can give, in its order, each once. */
    val headers: List<String> get() = labels.distinct()

    companion object {
        /** The header of titles that come before the first letter: digits and signs (ux.md §5.11). */
        const val UNDERFLOW_LABEL = "#"

        /** The header of titles in a script after the last labelled one. */
        const val OVERFLOW_LABEL = "…"

        private val RUSSIAN = ULocale("ru")
        private val UZBEK_LATIN = ULocale("uz")
        private val UZBEK_CYRILLIC = ULocale("uz_Cyrl")

        /**
         * The locales whose letters head the index: [locale]'s, English and Russian (ux.md §5.11),
         * and for Uzbek in either script both Uzbek alphabets. ICU's Uzbek Cyrillic collator sorts
         * Latin Oʻ, Gʻ, Sh and Ch after Z as the Latin one does (seen with ICU 60 and 78), so without
         * the Latin labels "Shahar" would be headed "Z"; and the Cyrillic labels give Ғ, Қ and Ҳ their
         * own headers under the Latin collator.
         */
        private fun labelLocales(locale: Locale): Array<ULocale> {
            val own = ULocale.forLocale(locale)
            val uzbek = if (locale.language == "uz") listOf(UZBEK_LATIN, UZBEK_CYRILLIC) else emptyList()
            return (listOf(own) + uzbek + listOf(ULocale.ENGLISH, RUSSIAN)).distinct().toTypedArray()
        }

        /** For the app's language as the screens show it ([AppLanguages.shown]). */
        fun of(language: AppLanguage): SortKeys =
            SortKeys(language.locale ?: error("SYSTEM is not a language the screens show; use AppLanguages.shown"))
    }
}

/** The apostrophe-like marks that people type for the ʻ of oʻ and gʻ (i18n.md §2.7). */
private const val OG_MARKS = "'‘’`ʼ"

private val OG_MARK = Regex("(?<=[oOgG])['‘’`ʼ]")

/**
 * The text a title is sorted and headed by (i18n.md §2.7): every ' ‘ ’ ` ʼ right after o, g, O or G
 * becomes ʻ (U+02BB), so that "O'zbekiston", "O‘zbekneftgaz" and "Oʻzbekiston banki" sort together
 * in every language however the apostrophe was typed. Nothing else changes. Plain Kotlin, tested on
 * the JVM.
 */
internal fun sortTextOf(title: String): String =
    if (title.none { it in OG_MARKS }) title else OG_MARK.replace(title, "ʻ")
