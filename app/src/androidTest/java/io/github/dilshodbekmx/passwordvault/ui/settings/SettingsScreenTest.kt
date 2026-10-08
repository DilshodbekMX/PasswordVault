package io.github.dilshodbekmx.passwordvault.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.lock.FingerprintStatus
import io.github.dilshodbekmx.passwordvault.lock.LockReason
import io.github.dilshodbekmx.passwordvault.lock.MpPurpose
import io.github.dilshodbekmx.passwordvault.lock.PinStatus
import io.github.dilshodbekmx.passwordvault.nav.HelpPage
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.settings.LockDelay
import io.github.dilshodbekmx.passwordvault.ui.setup.PinTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 4.3 on a device: the Settings list as the user sees it (ux.md §5.18) over the fakes:
 * every row is there and opens its route, the delays show what setup wrote and the dialog writes a
 * choice, a fingerprint switch that cannot be used says why and does nothing, turning fingerprint on
 * asks for the master password in a dialog, and "Lock now" locks.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val rig = SettingsRig(withProof = false)

    @After
    fun tearDown() = rig.close()

    private fun show(vm: SettingsViewModel = rig.settingsViewModel()): SettingsViewModel {
        compose.setContent { SettingsTestTheme { SettingsScreen(vm, rig.nav) } }
        compose.waitForIdle()
        return vm
    }

    private fun string(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag(SettingsTags.LIST).performScrollToNode(hasTestTag(tag))
    }

    private fun tapRow(tag: String) {
        scrollTo(tag)
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    @Test
    fun everyRowIsThereAndOpensItsRoute() {
        show()
        val rows = listOf(
            SettingsTags.PIN to "go ${Route.ChangePin.name}",
            SettingsTags.CHANGE_MASTER_PASSWORD to "go ${Route.ChangeMasterPassword.name}",
            SettingsTags.open(Route.Backup) to "go ${Route.Backup.name}",
            SettingsTags.open(Route.Export()) to "go Export",
            SettingsTags.open(Route.Import()) to "go Import",
            SettingsTags.open(Route.Language) to "go ${Route.Language.name}",
            SettingsTags.open(Route.Theme) to "go ${Route.Theme.name}",
            SettingsTags.help(HelpPage.PROTECTION) to "go Help",
            SettingsTags.help(HelpPage.LOST_PHONE) to "go Help",
            SettingsTags.help(HelpPage.FORGOT_MASTER_PASSWORD) to "go Help",
            SettingsTags.help(HelpPage.FORGOT_PIN) to "go Help",
            SettingsTags.open(Route.Licences) to "go ${Route.Licences.name}",
            SettingsTags.help(HelpPage.VERSION) to "go Help",
        )
        for ((tag, step) in rows) {
            rig.nav.steps.clear()
            tapRow(tag)
            assertEquals(tag, listOf(step), rig.nav.steps)
        }
        for (heading in listOf("appLock", "backup", "appearance", "help")) {
            scrollTo(SettingsTags.heading(heading))
            compose.onNodeWithTag(SettingsTags.heading(heading)).assertIsDisplayed()
        }
        // No "Old passwords" row without a vault put aside (lock.md F17).
        compose.onNodeWithTag(SettingsTags.oldPasswords(0)).assertDoesNotExist()
    }

    @Test
    fun theDelaysShowWhatSetupWroteAndTheDialogWritesTheChoice() {
        show()
        compose.onNodeWithText(compose.activity.resources.getQuantityString(R.plurals.settings_lock_delay_seconds, 30, 30)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.resources.getQuantityString(R.plurals.settings_clip_delay_minutes, 1, 1)).assertIsDisplayed()
        tapRow(SettingsTags.LOCK_DELAY)
        compose.onNodeWithTag(SettingsTags.lockDelay(LockDelay.MINUTES_5)).performClick()
        compose.waitUntil(5_000) { rig.settings.lockDelay.value == LockDelay.MINUTES_5 }
        compose.waitForIdle()
        compose.onNodeWithTag(SettingsTags.lockDelay(LockDelay.MINUTES_5)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.resources.getQuantityString(R.plurals.settings_lock_delay_minutes, 5, 5)).assertIsDisplayed()
    }

    @Test
    fun aFingerprintSwitchThatCannotBeUsedSaysWhyAndDoesNothing() {
        rig.lock.status.value = rig.lock.status.value.copy(fingerprint = FingerprintStatus.Unavailable(11))
        show()
        compose.onNodeWithText(string(R.string.settings_fingerprint_none_enrolled)).assertIsDisplayed()
        compose.onNodeWithTag(SettingsTags.FINGERPRINT).assertIsOff().assertIsNotEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(PinTags.MP_FIELD).assertDoesNotExist()
    }

    @Test
    fun turningFingerprintOnAsksForTheMasterPasswordInADialog() {
        show()
        compose.onNodeWithTag(SettingsTags.FINGERPRINT).assertIsOff().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.settings_fingerprint_on_body)).assertIsDisplayed()
        compose.onNodeWithTag(PinTags.MP_FIELD).performTextInput("vowel-shiny-shell-crisp-shaft-cadet")
        compose.onNodeWithTag(SettingsTags.FINGERPRINT_TURN_ON).performClick()
        compose.waitUntil(5_000) { rig.lock.status.value.fingerprint == FingerprintStatus.On }
        compose.waitForIdle()
        assertEquals(listOf(MpPurpose.FINGERPRINT_ON), rig.lock.purposes)
        compose.onNodeWithTag(PinTags.MP_FIELD).assertDoesNotExist()
        compose.onNodeWithTag(SettingsTags.FINGERPRINT).assertIsOn()
    }

    @Test
    fun thePinRowSaysChangePinOrSetAPin() {
        show()
        compose.onNodeWithText(string(R.string.settings_set_pin)).assertIsDisplayed()
        rig.lock.status.value = rig.lock.status.value.copy(pin = PinStatus.On)
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.settings_change_pin)).assertIsDisplayed()
    }

    @Test
    fun lockNowLocks() {
        show()
        tapRow(SettingsTags.LOCK_NOW)
        compose.waitUntil(5_000) { rig.lock.lockReasons.isNotEmpty() }
        assertEquals(1, rig.session.lockNows)
        assertEquals(listOf(LockReason.LOCK_NOW), rig.lock.lockReasons)
        assertTrue(rig.nav.steps.isEmpty())
    }
}
