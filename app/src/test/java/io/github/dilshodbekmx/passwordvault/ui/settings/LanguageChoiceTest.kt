package io.github.dilshodbekmx.passwordvault.ui.settings

import io.github.dilshodbekmx.passwordvault.core.lock.flow.CurrentVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.i18n.AppLanguage
import io.github.dilshodbekmx.passwordvault.i18n.AppLanguages
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.ui.settings.LanguageChoice.AR_XB
import io.github.dilshodbekmx.passwordvault.ui.settings.LanguageChoice.EN
import io.github.dilshodbekmx.passwordvault.ui.settings.LanguageChoice.EN_XA
import io.github.dilshodbekmx.passwordvault.ui.settings.LanguageChoice.RU
import io.github.dilshodbekmx.passwordvault.ui.settings.LanguageChoice.SYSTEM
import io.github.dilshodbekmx.passwordvault.ui.settings.LanguageChoice.UZ_CYRL
import io.github.dilshodbekmx.passwordvault.ui.settings.LanguageChoice.UZ_LATN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Locale

/**
 * Build plan 6.1, [JVM]: the choices of the language dialog (i18n.md §1.4.5), the tags each hands
 * to Android, which choice the app's own locales stand for (also those that the phone's own
 * language page sets on Android 13 and later), and what the dialog draws behind it.
 */
class LanguageChoiceTest {
    private fun of(vararg tags: String, pseudo: Boolean = true) = LanguageChoice.of(tags.map(Locale::forLanguageTag), pseudo)

    @Test
    fun theChoicesAreInTheirFixedOrder() {
        assertEquals(listOf(SYSTEM, UZ_LATN, UZ_CYRL, EN, RU), LanguageChoice.offered(pseudo = false))
        assertEquals("the same order as AppLanguages.choices", AppLanguages.choices, LanguageChoice.offered(pseudo = false).map { it.language })
        assertEquals("debug builds add the pseudo-locales", listOf(SYSTEM, UZ_LATN, UZ_CYRL, EN, RU, EN_XA, AR_XB), LanguageChoice.offered(pseudo = true))
    }

    @Test
    fun eachChoiceHandsAndroidItsTags() {
        assertEquals(
            listOf("", "uz-Latn", "uz-Cyrl", "en", "ru", "en-XA", "ar-XB"),
            LanguageChoice.entries.map { it.tags },
        )
        // Ўзбекча, English and Русский are the tags of the generated locale config (AppLanguage.tag).
        for (choice in listOf(UZ_CYRL, EN, RU)) assertEquals(choice.language?.tag, choice.tags)
        // Every language's tags read back as its own choice, and as its AppLanguage.
        for (choice in LanguageChoice.offered(pseudo = false) - SYSTEM) {
            val locale = Locale.forLanguageTag(choice.tags)
            assertEquals(choice.tags, choice.language, AppLanguages.of(locale))
            assertEquals(choice.tags, choice, LanguageChoice.of(listOf(locale), pseudo = false))
            assertEquals(choice.tags, choice.language, AppLanguages.shown(listOf(locale)))
        }
    }

    @Test
    fun theAppsOwnLocalesStandForAChoice() {
        assertEquals("no locale of its own: the system language", SYSTEM, of())
        assertEquals(UZ_LATN, of("uz"))
        assertEquals(UZ_LATN, of("uz-Latn"))
        assertEquals(UZ_LATN, of("uz-UZ"))
        assertEquals(UZ_LATN, of("uz-Latn-UZ"))
        assertEquals(UZ_CYRL, of("uz-Cyrl"))
        assertEquals("what the phone's own language page sets for Ўзбекча (API 37)", UZ_CYRL, of("uz-Cyrl-UZ"))
        assertEquals(RU, of("ru"))
        assertEquals(RU, of("ru-RU"))
        assertEquals(EN, of("en"))
        assertEquals(EN, of("en-US"))
        assertEquals("the first locale decides", RU, of("ru-RU", "uz-Cyrl"))
        assertEquals("a language that is not ours: the app shows the system language's texts", SYSTEM, of("de-DE"))
        assertEquals("Uzbek in Arabic script is not ours either", SYSTEM, of("uz-Arab-AF"))
    }

    @Test
    fun thePseudoLocalesAreChoicesOnlyInDebugBuilds() {
        assertEquals(EN_XA, of("en-XA", pseudo = true))
        assertEquals(AR_XB, of("ar-XB", pseudo = true))
        assertEquals("without them, en-XA is English", EN, of("en-XA", pseudo = false))
        assertEquals("and ar-XB not a language of ours", SYSTEM, of("ar-XB", pseudo = false))
    }

    @Test
    fun theDialogDrawsTheScreenItWasOpenedFrom() {
        assertEquals("Welcome's globe button, in setup", LanguageBackdrop.WELCOME, LanguageBackdrop.of(LockUiState.NoVault(emptyList())))
        assertEquals("Settings' language row", LanguageBackdrop.SETTINGS, LanguageBackdrop.of(LockUiState.Unlocked(emptyList(), null)))
        // Never shown there (NavState.allowedIn): nothing is drawn behind.
        assertEquals(LanguageBackdrop.NONE, LanguageBackdrop.of(LockUiState.Starting))
        assertEquals(
            LanguageBackdrop.NONE,
            LanguageBackdrop.of(LockUiState.Locked(LockScreen.FingerprintOnly(status = null, pinReason = null, aside = null))),
        )
        assertEquals(LanguageBackdrop.NONE, LanguageBackdrop.of(LockUiState.Unreadable(CurrentVault.Why.DAMAGED, emptyList())))
    }

    @Test
    fun theSystemLanguageIsNeverShownAsALanguage() {
        // The dialog's line under "System language" is AppLanguages.shown of the phone's languages,
        // which is always one of the four.
        for (tags in listOf(listOf("uz-Arab-AF"), listOf("de-DE"), emptyList(), listOf("uz-Cyrl-UZ", "ru-RU"))) {
            val shown = AppLanguages.shown(tags.map(Locale::forLanguageTag))
            assertNotEquals("$tags", AppLanguage.SYSTEM, shown)
        }
    }
}
