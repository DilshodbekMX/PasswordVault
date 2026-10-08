package io.github.dilshodbekmx.passwordvault.ui.theme

import io.github.dilshodbekmx.passwordvault.fake.FakeAppSettings
import io.github.dilshodbekmx.passwordvault.settings.FakePrefsFile
import io.github.dilshodbekmx.passwordvault.settings.FakeSharedPreferences
import io.github.dilshodbekmx.passwordvault.settings.PrefsAppSettings
import io.github.dilshodbekmx.passwordvault.settings.PrefsAppSettings.Companion.STYLE
import io.github.dilshodbekmx.passwordvault.settings.PrefsAppSettings.Companion.THEME
import io.github.dilshodbekmx.passwordvault.settings.StyleChoice
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The setting "Colours" (build plan §12, 4.3b; docs/design/looks/README.md "What the app offers";
 * architecture.md §3.5): `AppSettings.style` in "pv_settings". The file is [FakePrefsFile], which
 * outlives the settings object made over it, so a new [PrefsAppSettings] over the same file is a new
 * process.
 */
class StyleSettingTest {
    private val file = FakePrefsFile()

    /** A new process on a phone that has the wallpaper's colours (Android 12 and later), or has none. */
    private fun start(phoneColours: Boolean = true) = PrefsAppSettings(FakeSharedPreferences(file), phoneColours)

    @Test
    fun aNewInstallHasBankBlueAndFollowsThePhonesLightOrDark() {
        val settings = start()
        assertEquals(StyleChoice.BANK_BLUE, settings.style.value)
        assertEquals(ThemeChoice.SYSTEM, settings.theme.value)
        assertEquals(StyleChoice.BANK_BLUE, StyleChoice.DEFAULT)
        assertEquals(StyleChoice.BANK_BLUE, FakeAppSettings().style.value)
    }

    @Test
    fun everyStyleSurvivesANewProcess() {
        for (style in StyleChoice.entries) {
            // From another style, so that the choice is a change and is written.
            val other = StyleChoice.entries.first { it != style }
            assertTrue(start().update { it.style = other })
            assertTrue(start().update { it.style = style })
            assertEquals("the stored text of $style", style.stored, file.stored[STYLE])
            repeat(2) { assertEquals("$style after a new process", style, start().style.value) }
        }
    }

    @Test
    fun theStoredTextsAreFixed() {
        // What earlier versions wrote: these texts and the key never change.
        assertEquals("style", STYLE)
        assertEquals(listOf("bank_blue", "emerald", "night_vault", "phone"), StyleChoice.entries.map { it.stored })
        assertEquals(
            listOf(StyleChoice.BANK_BLUE, StyleChoice.EMERALD, StyleChoice.NIGHT_VAULT, StyleChoice.PHONE_COLOURS),
            StyleChoice.entries,
        )
    }

    @Test
    fun anUnknownStoredValueReadsAsBankBlue() {
        val unknown: List<Any> = listOf("sunset", "", "EMERALD", "Night vault", " emerald", 2, 2L, true, 1.5f, setOf("emerald"))
        for (value in unknown) {
            file.stored = mapOf(STYLE to value, THEME to "dark")
            val settings = start()
            assertEquals("stored $value", StyleChoice.BANK_BLUE, settings.style.value)
            assertEquals("the theme next to an unreadable style", ThemeChoice.DARK, settings.theme.value)
        }
        // Read as Bank blue is not written as Bank blue: the file is left as it was until the user chooses.
        assertEquals(setOf("emerald"), file.stored[STYLE])
        assertEquals(0, file.commits)
    }

    @Test
    fun anUnknownStoredValueIsReplacedByTheNextChoice() {
        file.stored = mapOf(STYLE to "sunset")
        val settings = start()
        assertTrue(settings.update { it.style = StyleChoice.NIGHT_VAULT })
        assertEquals("night_vault", file.stored[STYLE])
        assertEquals(StyleChoice.NIGHT_VAULT, start().style.value)
        // Choosing Bank blue over an unknown text changes nothing that is shown, so nothing is written.
        file.stored = mapOf(STYLE to "sunset")
        assertTrue(start().update { it.style = StyleChoice.BANK_BLUE })
        assertEquals("sunset", file.stored[STYLE])
    }

    @Test
    fun phonesColoursIsReadAsBankBlueOnAPhoneThatHasNone() {
        file.stored = mapOf(STYLE to "phone")
        assertEquals(StyleChoice.PHONE_COLOURS, start(phoneColours = true).style.value)
        assertEquals(StyleChoice.BANK_BLUE, start(phoneColours = false).style.value)
        assertEquals("phone", file.stored[STYLE])
    }

    @Test
    fun phonesColoursCannotBeSetOnAPhoneThatHasNone() {
        val settings = start(phoneColours = false)
        assertTrue(settings.update { it.style = StyleChoice.EMERALD })
        assertTrue(settings.update { it.style = StyleChoice.PHONE_COLOURS })
        assertEquals(StyleChoice.BANK_BLUE, settings.style.value)
        assertEquals("bank_blue", file.stored[STYLE])
        assertEquals(StyleChoice.BANK_BLUE, start(phoneColours = false).style.value)
    }

    @Test
    fun aPhoneBeforeAndroid12OffersNoPhonesColours() {
        val own = listOf(StyleChoice.BANK_BLUE, StyleChoice.EMERALD, StyleChoice.NIGHT_VAULT)
        assertEquals(own, StyleChoice.offered(phoneColours = false))
        assertEquals(own + StyleChoice.PHONE_COLOURS, StyleChoice.offered(phoneColours = true))
        assertEquals(31, StyleChoice.PHONE_COLOURS_FROM_SDK)
        for (sdk in 24..30) assertFalse("Android API $sdk", StyleChoice.hasPhoneColours(sdk))
        for (sdk in 31..37) assertTrue("Android API $sdk", StyleChoice.hasPhoneColours(sdk))
    }

    @Test
    fun theStyleAndLightOrDarkAreTwoSettings() {
        val settings = start()
        assertTrue(settings.update { it.style = StyleChoice.EMERALD })
        assertTrue(settings.update { it.theme = ThemeChoice.DARK })
        assertEquals(StyleChoice.EMERALD, settings.style.value)
        assertTrue(settings.update { it.style = StyleChoice.NIGHT_VAULT })
        assertEquals(ThemeChoice.DARK, settings.theme.value)
        start().let {
            assertEquals(StyleChoice.NIGHT_VAULT, it.style.value)
            assertEquals(ThemeChoice.DARK, it.theme.value)
        }
        assertEquals(mapOf<String, Any>(STYLE to "night_vault", THEME to "dark"), file.stored)
    }

    @Test
    fun aStyleThatCouldNotBeWrittenIsNotShown() {
        val settings = start()
        assertTrue(settings.update { it.style = StyleChoice.EMERALD })
        file.failWrites = true
        assertFalse(settings.update { it.style = StyleChoice.NIGHT_VAULT })
        assertEquals(StyleChoice.EMERALD, settings.style.value)
        file.failWrites = false
        // The failed change is not carried to disk by a later write of something else.
        assertTrue(settings.update { it.theme = ThemeChoice.LIGHT })
        assertEquals("emerald", file.stored[STYLE])
        assertEquals(StyleChoice.EMERALD, start().style.value)
    }

    @Test
    fun setupsDefaultsLeaveTheLookAlone() {
        val settings = start()
        assertTrue(settings.update { it.style = StyleChoice.EMERALD; it.theme = ThemeChoice.DARK })
        assertTrue(settings.writeDefaults())
        assertEquals(StyleChoice.EMERALD, settings.style.value)
        assertEquals(ThemeChoice.DARK, settings.theme.value)
    }

    @Test
    fun theFakeTakesAStyleLikeTheRealSettings() {
        val fake = FakeAppSettings()
        assertTrue(fake.update { it.style = StyleChoice.NIGHT_VAULT })
        assertEquals(StyleChoice.NIGHT_VAULT, fake.style.value)
        fake.failWrites = true
        assertFalse(fake.update { it.style = StyleChoice.EMERALD })
        assertEquals(StyleChoice.NIGHT_VAULT, fake.style.value)
    }
}
