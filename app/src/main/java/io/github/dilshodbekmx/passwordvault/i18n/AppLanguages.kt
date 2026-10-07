package io.github.dilshodbekmx.passwordvault.i18n

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import io.github.dilshodbekmx.passwordvault.R
import java.util.Locale

/**
 * The choices of the language dialog (i18n.md §1.4.5, ux.md §5.18), in its fixed order, which is
 * never re-sorted by the current language: System language, Oʻzbekcha, Ўзбекча, English, Русский.
 *
 * @property tag the language tag handed to Android, exactly those of the generated locale config
 *   (i18n.md §1.4.2), so that the phone's own per-app language setting shows the same choice; null
 *   for [SYSTEM], which is the empty list.
 * @property ownName the language's name in its own language (`donottranslate.xml`); null for
 *   [SYSTEM], whose label "System language" is translated (the language dialog's strings).
 */
enum class AppLanguage(val tag: String?, @StringRes val ownName: Int?) {
    SYSTEM(null, null),
    UZ_LATN("uz", R.string.i18n_language_uz),
    UZ_CYRL("uz-Cyrl", R.string.i18n_language_uz_cyrl),
    EN("en", R.string.i18n_language_en),
    RU("ru", R.string.i18n_language_ru),
    ;

    /** The locale of [tag]: for sorting, formatting and the `SpanStyle` of [ownName]. Null for [SYSTEM]. */
    val locale: Locale? get() = tag?.let(Locale::forLanguageTag)
}

/**
 * The app's language: which one is chosen, which one the screens show, and the switch (i18n.md
 * §1.4). AppCompat and Android keep the choice, not [AppSettings][io.github.dilshodbekmx.passwordvault.settings.AppSettings]:
 * on Android 13 and later the system's `LocaleManager` (also shown in the phone's Settings › Apps ›
 * PasswordVault › Language), on Android 7 to 12 AppCompat's own record file (`autoStoreLocales` in
 * the manifest).
 *
 * A change recreates the activities and nothing else: the process, the open vault and an unsaved
 * form go on (lock.md §11).
 *
 * The functions without a [Context] are plain Kotlin over [Locale] and are tested on the JVM; the
 * others call Android.
 */
object AppLanguages {
    /** The dialog's choices in their fixed order (i18n.md §1.4.5). */
    val choices: List<AppLanguage> = AppLanguage.entries.toList()

    /**
     * The globe button's label, the same in every language so that a reader of any of the four finds
     * the switch: "Til · Тил · Язык · Language" (i18n.md §1.4.5; Welcome and Settings).
     */
    @StringRes
    val switchLabel: Int = R.string.i18n_language_switch

    /**
     * The choice that an app locale stands for (i18n.md §1.4.5 `currentAppLanguage`, §5 item 3):
     * "uz", "uz-UZ" and "uz-Latn" are Oʻzbekcha, "uz-Cyrl" Ўзбекча, any "ru" Русский, any "en"
     * English; null, the empty locale and every other language are [AppLanguage.SYSTEM]. A locale
     * whose script is not ours ("uz-Arab", or "uz-AF", whose likely script is Arabic) is not one of
     * ours either: Android would show it the default English texts.
     */
    fun of(locale: Locale?): AppLanguage {
        if (locale == null) return AppLanguage.SYSTEM
        return when (locale.language) {
            "uz" -> when (scriptOf(locale)) {
                "Latn" -> AppLanguage.UZ_LATN
                "Cyrl" -> AppLanguage.UZ_CYRL
                else -> AppLanguage.SYSTEM
            }
            "ru" -> if (scriptOf(locale) == "Cyrl") AppLanguage.RU else AppLanguage.SYSTEM
            "en" -> if (scriptOf(locale) == "Latn") AppLanguage.EN else AppLanguage.SYSTEM
            else -> AppLanguage.SYSTEM
        }
    }

    /**
     * The language the screens show for [locales]: never [AppLanguage.SYSTEM]. Android's two steps of
     * i18n.md §1.2 over our four languages: with more than one locale, the first whose language and
     * likely script are one of ours, English always counting as supported
     * (`LocaleList.getFirstMatchWithEnglishSupported`); with one, or when none matches, the first;
     * and a locale that is not one of ours gets the default resources, which are English. A
     * pseudo-locale of the debug build (en-XA, ar-XB) counts as English.
     *
     * Given the list of a context's configuration ([localesOf]) it is exact: Android has already put
     * the locale its resources use in front (`ResourcesImpl` does), seen on API 24 and 37 as
     * `[en_US, uz-Cyrl]` while the APK had no Uzbek Cyrillic texts. Given the phone's own list, or
     * the app's choice in front of it, it says what a build with all four translations shows, which
     * every build has from wave 6 on.
     */
    fun shown(locales: List<Locale>): AppLanguage {
        val first = locales.firstOrNull() ?: return AppLanguage.EN
        val chosen = if (locales.size == 1) first else locales.firstOrNull { of(it) != AppLanguage.SYSTEM } ?: first
        val language = of(chosen)
        return if (language == AppLanguage.SYSTEM) AppLanguage.EN else language
    }

    /**
     * The locale to format dates and numbers with, and to sort with, on a screen whose configuration
     * has [locales] (i18n.md §4.6, ux.md §5.11): the first of them in the language the screens show
     * ([shown]), so that its region still counts (en-GB writes "6 Oct 2026"); else that language
     * itself. So a phone set to German, then Russian, gets Russian texts with Russian dates, never
     * German dates in a Russian sentence. A pseudo-locale in front is kept as it is.
     */
    fun screenLocale(locales: List<Locale>): Locale {
        val first = locales.firstOrNull() ?: return Locale.ENGLISH
        if (isPseudoLocale(first)) return first
        val shown = shown(locales)
        return locales.firstOrNull { of(it) == shown } ?: requireNotNull(shown.locale)
    }

    /** The locales of [configuration], first the one its resources prefer. */
    fun localesOf(configuration: Configuration): List<Locale> = configuration.locales.toList()

    /** The phone's own languages, which the [AppLanguage.SYSTEM] choice follows. */
    fun systemLocales(context: Context): List<Locale> =
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).systemLocales.toList()
        } else {
            Resources.getSystem().configuration.locales.toList()
        }

    /** The language the app would show with [AppLanguage.SYSTEM]: the dialog's second line under it. */
    fun systemShown(context: Context): AppLanguage = shown(systemLocales(context))

    /**
     * The current choice. On Android 13 and later it is asked from the system, with any context. On
     * Android 7 to 12 it is AppCompat's, which reads its record file when the first AppCompat activity
     * of the process starts: before that it answers [AppLanguage.SYSTEM].
     */
    fun current(context: Context): AppLanguage = of(appLocales(context).firstOrNull())

    /** The app's own locales as Android keeps them; empty for [AppLanguage.SYSTEM]. */
    fun appLocales(context: Context): List<Locale> =
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales.toList()
        } else {
            AppCompatDelegate.getApplicationLocales().toList()
        }

    /**
     * Chooses [language] (the dialog, i18n.md §1.4.5). Android recreates the activities in it; the
     * process goes on. Call it on the main thread. On Android 13 and later this is the system's
     * `LocaleManager`, reached with any context; AppCompat's call reaches it only through a live
     * AppCompat activity and otherwise does nothing (i18n.md §1.4.3). On Android 7 to 12 it is
     * AppCompat's, which applies the choice to the live activities and keeps it in its record file.
     */
    fun set(context: Context, language: AppLanguage) = setTags(context, language.tag.orEmpty())

    /**
     * Chooses the language tags [tags] (comma-separated, BCP 47; empty for the system language), as
     * [set] does. For the debug build's `locale` command, which also takes the pseudo-locales; the
     * screens use [set].
     */
    fun setTags(context: Context, tags: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tags)
        } else {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags))
        }
    }

    /** The debug build's pseudo-locales (i18n.md §5): English (XA) and Arabic (XB). */
    fun isPseudoLocale(locale: Locale): Boolean =
        (locale.language == "en" && locale.country == "XA") || (locale.language == "ar" && locale.country == "XB")

    /**
     * The script of [locale]: its own, or the likely one of its language and region for the three
     * languages we compare (CLDR's likely subtags, as Android computes them, i18n.md §1.2): Uzbek is
     * Latin, except in Afghanistan (Arabic) and China (Cyrillic); Russian is Cyrillic; English Latin.
     * Empty for other languages.
     */
    private fun scriptOf(locale: Locale): String {
        if (locale.script.isNotEmpty()) return locale.script
        return when (locale.language) {
            "uz" -> when (locale.country) {
                "AF" -> "Arab"
                "CN" -> "Cyrl"
                else -> "Latn"
            }
            "ru" -> "Cyrl"
            "en" -> "Latn"
            else -> ""
        }
    }

    private fun LocaleList.toList(): List<Locale> = List(size()) { get(it) }

    private fun LocaleListCompat.toList(): List<Locale> = (0 until size()).mapNotNull { get(it) }
}
