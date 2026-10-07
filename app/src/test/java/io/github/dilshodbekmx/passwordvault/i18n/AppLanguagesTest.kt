package io.github.dilshodbekmx.passwordvault.i18n

import io.github.dilshodbekmx.passwordvault.i18n.AppLanguage.EN
import io.github.dilshodbekmx.passwordvault.i18n.AppLanguage.RU
import io.github.dilshodbekmx.passwordvault.i18n.AppLanguage.SYSTEM
import io.github.dilshodbekmx.passwordvault.i18n.AppLanguage.UZ_CYRL
import io.github.dilshodbekmx.passwordvault.i18n.AppLanguage.UZ_LATN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * Build plan 2.9, [JVM]: the language mapping of i18n.md §5 item 3 (`currentAppLanguage()` for
 * "uz", "uz-UZ", "uz-Latn", "uz-Cyrl", "ru-RU", "en", "de"), the dialog's fixed order and tags
 * (§1.4.5), and which language the screens show for a phone's list of languages (§1.2).
 */
class AppLanguagesTest {
    private fun of(tag: String?) = AppLanguages.of(tag?.let(Locale::forLanguageTag))

    private fun shown(vararg tags: String) = AppLanguages.shown(tags.map(Locale::forLanguageTag))

    @Test
    fun theLanguageMappingOfI18nSection5Item3() {
        assertEquals(UZ_LATN, of("uz"))
        assertEquals(UZ_LATN, of("uz-UZ"))
        assertEquals(UZ_LATN, of("uz-Latn"))
        assertEquals(UZ_CYRL, of("uz-Cyrl"))
        assertEquals(RU, of("ru-RU"))
        assertEquals(EN, of("en"))
        assertEquals(SYSTEM, of("de"))
        // No app locale at all: the system language.
        assertEquals(SYSTEM, of(null))
        assertEquals(SYSTEM, AppLanguages.of(Locale.ROOT))
    }

    @Test
    fun moreSpellingsOfTheFourLanguages() {
        assertEquals(UZ_LATN, of("uz-Latn-UZ"))
        assertEquals(UZ_CYRL, of("uz-Cyrl-UZ"))
        assertEquals("likely script Cyrillic (i18n.md §1.2)", UZ_CYRL, of("uz-CN"))
        assertEquals("Arabic script: Android shows the default English texts", SYSTEM, of("uz-Arab-AF"))
        assertEquals("likely script Arabic", SYSTEM, of("uz-AF"))
        assertEquals(RU, of("ru"))
        assertEquals(RU, of("ru-UZ"))
        assertEquals(RU, of("ru-KZ"))
        assertEquals(EN, of("en-US"))
        assertEquals(EN, of("en-GB"))
        assertEquals("the debug build's pseudo-locale", EN, of("en-XA"))
        assertEquals(SYSTEM, of("kk-KZ"))
        assertEquals(SYSTEM, of("tr-TR"))
    }

    @Test
    fun theDialogsOrderAndTagsAreFixed() {
        assertEquals(listOf(SYSTEM, UZ_LATN, UZ_CYRL, EN, RU), AppLanguages.choices)
        // Exactly the tags of the generated locale config (i18n.md §1.4.2), so that the phone's own
        // per-app language setting shows the same choice as selected.
        assertEquals(listOf(null, "uz", "uz-Cyrl", "en", "ru"), AppLanguages.choices.map { it.tag })
        assertNull(SYSTEM.locale)
        assertNull("System language is translated, not a fixed name", SYSTEM.ownName)
        for (language in AppLanguages.choices - SYSTEM) {
            // Each tag maps back to its own choice.
            assertEquals(language, AppLanguages.of(language.locale))
            assertEquals(language, AppLanguages.shown(listOf(requireNotNull(language.locale))))
        }
    }

    @Test
    fun theLanguageTheScreensShowFollowsAndroidsChoice() {
        // The table of i18n.md §1.2.
        assertEquals(UZ_LATN, shown("uz-Latn-UZ"))
        assertEquals(UZ_LATN, shown("uz-UZ"))
        assertEquals(UZ_CYRL, shown("uz-Cyrl-UZ"))
        assertEquals(RU, shown("ru-RU"))
        assertEquals(RU, shown("ru-KZ"))
        assertEquals(RU, shown("ru-UZ"))
        assertEquals(EN, shown("en-US"))
        assertEquals(UZ_CYRL, shown("uz-Cyrl-UZ", "ru-RU"))
        assertEquals(UZ_CYRL, shown("uz-Cyrl-UZ", "en-US", "ru-RU"))
        assertEquals(RU, shown("uz-Arab-AF", "ru-RU"))
        assertEquals(EN, shown("uz-Arab-AF"))
        assertEquals(RU, shown("kk-KZ", "ru-RU"))
        assertEquals(RU, shown("de-DE", "ru-RU", "en-US"))
        // English always counts as supported, so it wins when it comes first.
        assertEquals(EN, shown("de-DE", "en-US", "ru-RU"))
        // Nothing of ours: the first stays, and its texts are the default English ones.
        assertEquals(EN, shown("de-DE", "fr-FR"))
        assertEquals(EN, AppLanguages.shown(emptyList()))
        // The app's choice in front of the phone's list, as AppCompat and Android put it.
        assertEquals(UZ_LATN, shown("uz", "ru-RU", "en-US"))
    }

    @Test
    fun theScreensLocaleKeepsTheRegionOfTheLanguageShown() {
        fun screen(vararg tags: String) = AppLanguages.screenLocale(tags.map(Locale::forLanguageTag)).toLanguageTag()
        assertEquals("en-GB", screen("en-GB"))
        assertEquals("ru-RU", screen("de-DE", "ru-RU"))
        assertEquals("never German dates in a Russian sentence", "ru-KZ", screen("de-DE", "ru-KZ", "en-US"))
        assertEquals("uz-Cyrl-UZ", screen("uz-Cyrl-UZ", "ru-RU"))
        assertEquals("uz-Cyrl", screen("uz-Cyrl", "en-US"))
        assertEquals("en", screen("uz-Arab-AF"))
        assertEquals("en", screen("de-DE"))
        assertEquals("en", AppLanguages.screenLocale(emptyList()).toLanguageTag())
        assertEquals("a pseudo-locale stays as it is", "ar-XB", screen("ar-XB"))
        assertEquals("en-XA", screen("en-XA", "ru-RU"))
    }
}
