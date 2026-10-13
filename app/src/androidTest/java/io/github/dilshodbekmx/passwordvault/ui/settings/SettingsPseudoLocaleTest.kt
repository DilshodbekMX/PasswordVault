package io.github.dilshodbekmx.passwordvault.ui.settings

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.BuildConfig
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideVault
import io.github.dilshodbekmx.passwordvault.lock.FingerprintOnResult
import io.github.dilshodbekmx.passwordvault.lock.FingerprintStatus
import io.github.dilshodbekmx.passwordvault.lock.MpResult
import io.github.dilshodbekmx.passwordvault.lock.PinChange
import io.github.dilshodbekmx.passwordvault.lock.PinSetResult
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.ui.setup.FingerprintOfferTags
import io.github.dilshodbekmx.passwordvault.ui.setup.PinSetupStart
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan §1 rule 5 for package 4.3: every `pin_` and `settings_` string and plural comes out
 * pseudo-localized in `en-XA` (none is plain English or wrongly marked untranslatable), and every
 * screen of the package, drawn in `en-XA` in states that between them show every line, button and
 * dialog, has no text and no label that stayed plain English, so none is written into the code.
 * Not texts of their own: the keypad's digits, the version number and the product name "Google
 * Drive" (translatable="false", i18n.md §3).
 */
@RunWith(AndroidJUnit4::class)
class SettingsPseudoLocaleTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val rig = SettingsRig()

    @After
    fun tearDown() = rig.close()

    private fun pseudo(base: Context): Context {
        val configuration = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag("en-XA")) }
        return base.createConfigurationContext(configuration)
    }

    @Test
    fun everyPinAndSettingsStringIsPseudoLocalized() {
        val english = compose.activity
        val pseudo = pseudo(english)
        val untranslatable = setOf("settings_place_drive")
        val strings = R.string::class.java.fields.filter { (it.name.startsWith("pin_") || it.name.startsWith("settings_")) && it.name !in untranslatable }
        assertTrue("the strings are found: ${strings.size}", strings.size >= 90)
        for (field in strings) {
            val id = field.getInt(null)
            assertNotEquals("string ${field.name}", english.getString(id), pseudo.getString(id))
        }
        val plurals = R.plurals::class.java.fields.filter { it.name.startsWith("pin_") || it.name.startsWith("settings_") }
        assertTrue("the plurals are found: ${plurals.size}", plurals.size >= 6)
        for (field in plurals) {
            val id = field.getInt(null)
            for (n in listOf(1, 3)) {
                assertNotEquals("plural ${field.name} ($n)", english.resources.getQuantityString(id, n, n), pseudo.resources.getQuantityString(id, n, n))
            }
        }
    }

    @Test
    fun theScreensDrawEveryTextFromResources() {
        val pseudo = pseudo(compose.activity)
        rig.lock.status.value = rig.lock.status.value.copy(aside = listOf(AsideVault("00000000-0000-4000-8000-0000000000aa", AT, null)))
        lateinit var gallery: SettingsGallery
        compose.runOnUiThread { gallery = SettingsGallery(rig) }
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides pseudo,
                LocalConfiguration provides pseudo.resources.configuration,
                LocalResources provides pseudo.resources,
            ) {
                GalleryHost(gallery)
            }
        }
        compose.waitForIdle()

        // The Settings list, from the top to the bottom.
        for (tag in SETTINGS_ROWS) {
            compose.scrollSettingsTo(tag)
            check("the Settings list at $tag")
        }
        // A fingerprint switch that cannot be used, and its reason.
        rig.lock.status.value = rig.lock.status.value.copy(fingerprint = FingerprintStatus.Unavailable(11))
        compose.scrollSettingsTo(SettingsTags.FINGERPRINT)
        check("the Settings list with a fingerprint reason")
        rig.lock.status.value = rig.lock.status.value.copy(fingerprint = FingerprintStatus.Off)
        // Its dialogs.
        step("the lock-delay dialog") { gallery.settings.openLockDelay() }
        step("the clipboard-delay dialog") {
            gallery.settings.dismissDialog()
            gallery.settings.openClipDelay()
        }
        rig.lock.mpResults += MpResult.Wrong
        step("the fingerprint dialog") {
            gallery.settings.dismissDialog()
            gallery.settings.setFingerprint(true)
        }
        compose.runOnUiThread {
            gallery.settings.fingerprintPassword.field.edit { append("wrong") }
            gallery.settings.submitFingerprintPassword()
        }
        compose.waitForIdle()
        check("the fingerprint dialog after a wrong master password")
        step("the dialog Appearance") {
            gallery.settings.dismissDialog()
            gallery.shown = SettingsGallery.Screen.THEME
        }
        step("the App lock page") { gallery.shown = SettingsGallery.Screen.APP_LOCK }

        // The PIN steps, as setup opens them: "Make backups" ends them (decision 24).
        PinSetupStart.takeNewVault()
        PinSetupStart.newVault()
        step("Choose a 6-digit PIN") { gallery.shown = SettingsGallery.Screen.PIN_STEPS }
        step("a refused PIN") { gallery.typeSteps("111111", Route.CreatePin) }
        step("Enter the PIN again") { gallery.typeSteps("582913", Route.CreatePin) }
        step("a mismatch") { gallery.typeSteps("905127", Route.ConfirmPin) }
        rig.lock.setPinResults += PinSetResult.NotSet
        step("a PIN that could not be saved") { gallery.typeSteps("582913", Route.ConfirmPin) }
        step("Your PIN is set.") { gallery.pinSteps.retrySave() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(FingerprintOfferTags.USE)).fetchSemanticsNodes().isNotEmpty() }
        check("the fingerprint offer")
        rig.lock.fingerprintResults += FingerprintOnResult.Failed
        step("the fingerprint offer after a failure") { gallery.pinSteps.useFingerprint() }
        step("the fingerprint offer when it may not have been turned on") { gallery.pinSteps.fingerprintMayNotBeOn() }
        step("Make backups") { gallery.pinSteps.fingerprintNotNow() }
        step("the master password before a PIN") { gallery.shown = SettingsGallery.Screen.PIN_STEPS_NO_PROOF }

        // "Change PIN" and "Set a PIN".
        step("Enter your current PIN") { gallery.shown = SettingsGallery.Screen.CHANGE_PIN }
        step("Choose a new PIN") { gallery.typeChange("582913") }
        rig.lock.pinChanges += PinChange.Wrong(4)
        step("Change PIN: a wrong current PIN") {
            gallery.typeChange("905127")
            gallery.typeChange("905127")
        }
        rig.lock.pinChanges += PinChange.Limit
        step("Change PIN: the limit") { gallery.typeChange("368014") }
        step("Set a PIN: the master password") { gallery.shown = SettingsGallery.Screen.SET_PIN }

        // A save the lock cannot vouch for (build-plan.md §12, decision 4): the lock of these two does
        // not answer, so the screens are on "Saving…" when the walk tells them.
        step("the PIN steps over a lock that does not answer yet") { gallery.shown = SettingsGallery.Screen.PIN_STEPS_UNSURE }
        compose.runOnUiThread { gallery.typeStepsUnsure("582913", Route.CreatePin) }
        compose.waitForIdle()
        compose.runOnUiThread { gallery.typeStepsUnsure("582913", Route.ConfirmPin) }
        compose.waitForIdle()
        step("a PIN that may not have been saved") { gallery.pinStepsUnsure.pinMayNotBeSaved() }
        assertTrue(gallery.pinStepsUnsure.ui.value.saveUnsure)
        step("Change PIN over a lock that does not answer yet") { gallery.shown = SettingsGallery.Screen.CHANGE_PIN_UNSURE }
        compose.runOnUiThread {
            gallery.typeChangeUnsure("582913")
            gallery.typeChangeUnsure("905127")
            gallery.typeChangeUnsure("905127")
        }
        compose.waitForIdle()
        step("Change PIN: a PIN that may not have been saved") { gallery.changePinUnsure.pinMayNotBeSaved() }
        assertEquals(ChangePinViewModel.Stage.MAY_NOT_BE_SAVED, gallery.changePinUnsure.ui.value.stage)

        // Settings says the same of fingerprint unlock in a message.
        step("Settings: fingerprint unlock may not have been turned on") {
            gallery.shown = SettingsGallery.Screen.SETTINGS
            gallery.settings.fingerprintMayNotBeOn()
        }
        assertTrue(compose.shownTexts().any { it == pseudo.getString(R.string.pin_fingerprint_may_not_be_on) })
    }

    /** Does [action] on the main thread, waits, and checks the screen as [what]. */
    private fun step(what: String, action: () -> Unit) {
        compose.runOnUiThread(action)
        compose.waitForIdle()
        check(what)
    }

    /** Every text shown now has a letter that the pseudo-locale made: nothing stayed plain English. */
    private fun check(what: String) {
        val texts = compose.shownTexts().filterNot { it.matches(Regex("\\d")) || it == BuildConfig.VERSION_NAME || it == "Google Drive" }
        assertTrue("$what: the screen shows texts", texts.size >= 2)
        for (text in texts) {
            assertTrue("\"$text\" stayed plain English in en-XA ($what)", text.any { it.code > 0x7F })
        }
    }

    private companion object {
        const val AT = 1_791_331_200_000L
    }
}
