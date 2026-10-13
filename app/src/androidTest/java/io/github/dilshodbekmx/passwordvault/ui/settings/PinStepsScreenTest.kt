package io.github.dilshodbekmx.passwordvault.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.lock.MpPurpose
import io.github.dilshodbekmx.passwordvault.lock.PinStatus
import io.github.dilshodbekmx.passwordvault.ui.common.PinPadTags
import io.github.dilshodbekmx.passwordvault.ui.setup.FingerprintOfferTags
import io.github.dilshodbekmx.passwordvault.ui.setup.PinSetupStart
import io.github.dilshodbekmx.passwordvault.ui.setup.PinTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 4.3 on a device: the PIN steps (ux.md §5.6, §5.7) and "Change PIN" / "Set a PIN"
 * (§5.18) as the user taps them, over the fakes: a refused PIN says why and clears the dots, the dots
 * count without a digit anywhere, an allowed PIN typed twice is saved and the fingerprint offer
 * follows, three mismatches go back to the first entry, "Change PIN" takes the current PIN first and
 * "Set a PIN" the master password.
 */
@RunWith(AndroidJUnit4::class)
class PinStepsScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val rig = SettingsRig()

    @After
    fun tearDown() = rig.close()

    private fun string(id: Int): String = compose.activity.getString(id)

    private fun type(pin: String) {
        for (c in pin) {
            compose.onNodeWithTag(PinPadTags.key(c - '0')).performScrollTo().performClick()
        }
        compose.waitForIdle()
    }

    private fun dots(count: Int) = compose.activity.resources.getQuantityString(R.plurals.common_pin_dots, count, count, 6)

    /** The PIN steps; with [newVault], as setup or a restore opens them, which end with "Make backups" (decision 24). */
    private fun showSteps(canAuthenticate: Int = 0, newVault: Boolean = false) {
        PinSetupStart.take() // nothing left by another test of the process (the steps would say "Choose a new PIN")
        PinSetupStart.takeNewVault()
        if (newVault) PinSetupStart.newVault()
        val vm = rig.pinSetupViewModel(canAuthenticate)
        compose.setContent { SettingsTestTheme { PinStepsHost(vm, rig.nav) } }
        compose.waitForIdle()
    }

    @Test
    fun aRefusedPinSaysWhyAndClearsTheDots() {
        showSteps()
        compose.onNodeWithText(string(R.string.pin_create_title)).assertIsDisplayed()
        type("111111")
        compose.onNodeWithText(string(R.string.pin_refused_pattern)).assertIsDisplayed()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(dots(0)))).assertExists()
        type("250890")
        compose.onNodeWithText(string(R.string.pin_refused_date)).assertIsDisplayed()
        type("123456")
        compose.onNodeWithText(string(R.string.pin_refused_sequence)).assertIsDisplayed()
        assertTrue("nothing moved", rig.nav.steps.isEmpty())
    }

    @Test
    fun theDotsCountAndNoNodeShowsTheDigits() {
        showSteps()
        type("582")
        compose.onNodeWithTag(PinPadTags.DOTS).assertExists()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(dots(3)))).assertExists()
        val texts = compose.shownTexts()
        assertFalse("no text holds the digits typed: $texts", texts.any { "582" in it })
    }

    @Test
    fun anAllowedPinTypedTwiceIsSavedAndTheOfferFollows() {
        showSteps(canAuthenticate = 0, newVault = true)
        type("582913")
        compose.waitUntil(5_000) { "go ConfirmPin" in rig.nav.steps }
        compose.onNodeWithText(string(R.string.pin_confirm_title)).assertIsDisplayed()
        type("582913")
        compose.waitUntil(5_000) { rig.lock.status.value.pin == PinStatus.On }
        compose.waitUntil(5_000) { "replace FingerprintOffer" in rig.nav.steps }
        assertEquals(listOf("go ConfirmPin", "back", "replace FingerprintOffer"), rig.nav.steps)
        compose.onNodeWithText(string(R.string.pin_fingerprint_title)).assertIsDisplayed()
        compose.onNodeWithTag(FingerprintOfferTags.ANYONE).assertIsDisplayed()
        compose.onNodeWithTag(FingerprintOfferTags.NOT_NOW).performScrollTo().performClick()
        compose.waitUntil(5_000) { "replace BackupsExplained" in rig.nav.steps }
        compose.onNodeWithText(string(R.string.pin_backups_title)).assertIsDisplayed()
    }

    @Test
    fun threeMismatchesGoBackToTheFirstEntry() {
        showSteps()
        type("582913")
        compose.waitUntil(5_000) { "go ConfirmPin" in rig.nav.steps }
        type("905127")
        compose.onNodeWithText(string(R.string.pin_mismatch)).assertIsDisplayed()
        type("905127")
        compose.onNodeWithText(string(R.string.pin_confirm_title)).assertIsDisplayed()
        type("905127")
        compose.waitUntil(5_000) { "back" in rig.nav.steps }
        compose.onNodeWithText(string(R.string.pin_create_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.pin_mismatch)).assertIsDisplayed()
        assertEquals(PinStatus.NotSet, rig.lock.status.value.pin)
    }

    /**
     * build-plan.md §12, decision 4: when the lock cannot say whether the new PIN was saved, the step
     * promises nothing. It says what to try next time and offers "OK" alone, which goes on.
     */
    @Test
    fun aPinThatMayNotBeSavedSaysWhatToTryNextTimeAndOkGoesOn() {
        PinSetupStart.takeNewVault()
        PinSetupStart.newVault() // after setup: "Make backups" follows
        val gated = GatedLock(rig.lock)
        val vm = rig.pinSetupViewModel(canAuthenticate = 11, lock = gated)
        compose.setContent { SettingsTestTheme { PinStepsHost(vm, rig.nav) } }
        compose.waitForIdle()
        type("582913")
        compose.waitUntil(5_000) { "go ConfirmPin" in rig.nav.steps }
        type("582913")
        compose.onNodeWithTag(PinTags.BUSY).assertIsDisplayed() // "Saving…": the lock has not answered
        compose.runOnIdle { vm.pinMayNotBeSaved() }
        compose.onNodeWithText(string(R.string.pin_may_not_be_saved_first)).assertIsDisplayed()
        compose.onNodeWithTag(PinTags.OK).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(PinTags.RETRY).assertDoesNotExist()
        compose.onNodeWithTag(PinTags.NOT_NOW).assertDoesNotExist()
        compose.onNodeWithTag(PinTags.DONE).assertDoesNotExist() // not "Your PIN is set."
        // The lock's own answer, when it comes, changes nothing on the screen.
        gated.open.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.pin_may_not_be_saved_first)).assertIsDisplayed()
        compose.onNodeWithTag(PinTags.OK).performScrollTo().performClick()
        compose.waitUntil(5_000) { "replace BackupsExplained" in rig.nav.steps }
        assertEquals(listOf("go ConfirmPin", "back", "replace BackupsExplained"), rig.nav.steps)
    }

    @Test
    fun aChangedPinThatMayNotBeSavedNamesTheOldPinAndOkClosesTheRoute() {
        rig.lock.status.value = rig.lock.status.value.copy(pin = PinStatus.On)
        SettingsNotice.take()
        val gated = GatedLock(rig.lock)
        val vm = rig.changePinViewModel(lock = gated)
        compose.setContent { SettingsTestTheme { ChangePinScreen(vm, rig.nav) } }
        compose.waitForIdle()
        type("582913")
        type("905127")
        type("905127")
        compose.onNodeWithTag(PinTags.BUSY).assertIsDisplayed()
        compose.runOnIdle { vm.pinMayNotBeSaved() }
        compose.onNodeWithText(string(R.string.pin_may_not_be_saved)).assertIsDisplayed()
        compose.onNodeWithTag(PinTags.RETRY).assertDoesNotExist()
        compose.onNodeWithTag(PinTags.LATER).assertDoesNotExist()
        compose.onNodeWithTag(PinPadTags.DOTS).assertDoesNotExist()
        gated.open.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.pin_may_not_be_saved)).assertIsDisplayed()
        assertEquals("Settings is not told that the PIN was changed", null, SettingsNotice.take())
        compose.onNodeWithTag(PinTags.OK).performClick()
        compose.waitUntil(5_000) { "back" in rig.nav.steps }
    }

    @Test
    fun changePinTakesTheCurrentPinFirst() {
        rig.lock.status.value = rig.lock.status.value.copy(pin = PinStatus.On)
        SettingsNotice.take()
        val vm = rig.changePinViewModel()
        compose.setContent { SettingsTestTheme { ChangePinScreen(vm, rig.nav) } }
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.pin_current_title)).assertIsDisplayed()
        compose.onNodeWithTag(PinTags.USE_MASTER_PASSWORD).assertExists()
        type("582913")
        compose.onNodeWithText(string(R.string.pin_create_new_title)).assertIsDisplayed()
        type("905127")
        type("905127")
        compose.waitUntil(5_000) { "back" in rig.nav.steps }
        assertTrue("no master password was asked", rig.lock.purposes.isEmpty())
        assertEquals(io.github.dilshodbekmx.passwordvault.i18n.UiText.of(R.string.pin_changed), SettingsNotice.take())
    }

    @Test
    fun setAPinAsksForTheMasterPasswordFirst() {
        val vm = rig.changePinViewModel()
        compose.setContent { SettingsTestTheme { ChangePinScreen(vm, rig.nav) } }
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.pin_mp_title)).assertIsDisplayed()
        compose.onNodeWithTag(PinPadTags.DOTS).assertDoesNotExist()
        compose.onNodeWithTag(PinTags.MP_FIELD).performTextInput("vowel-shiny-shell-crisp-shaft-cadet")
        compose.onNodeWithTag(PinTags.MP_NEXT).performClick()
        compose.waitUntil(5_000) { rig.lock.purposes.isNotEmpty() }
        compose.waitForIdle()
        assertEquals(listOf(MpPurpose.SET_PIN), rig.lock.purposes)
        compose.onNodeWithText(string(R.string.pin_create_title)).assertIsDisplayed()
    }
}
