package io.github.dilshodbekmx.passwordvault.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChoosePinReason
import io.github.dilshodbekmx.passwordvault.core.model.FileRef
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.data.VaultState
import io.github.dilshodbekmx.passwordvault.files.Place
import io.github.dilshodbekmx.passwordvault.lock.FingerprintStatus
import io.github.dilshodbekmx.passwordvault.lock.PinStatus
import io.github.dilshodbekmx.passwordvault.nav.HelpPage
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.setup.FingerprintOfferTags
import io.github.dilshodbekmx.passwordvault.ui.setup.PinSetupStart
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pictures of the package's screens for the user (build plan 4.3's note: the Settings list and the
 * Create PIN step, and the screens around them), on Android 17 with the debug build: [Shots] saves
 * each into `/data/local/tmp/pv-shots-4A/` on the device. The states are those the user meets: a PIN
 * and fingerprint unlock set, a backup saved two days ago to Google Drive, one set of passwords put
 * aside.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 37)
class SettingsScreenshotsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val rig = SettingsRig()

    @After
    fun tearDown() = rig.close()

    @Test
    fun theScreensForTheUser() {
        val now = System.currentTimeMillis()
        rig.files.places["content://com.google.android.apps.docs.storage/document/backup"] = Place.GoogleDrive
        rig.repository.state.value = VaultState.Unlocked(
            Vault(backups = listOf(FileRef("content://com.google.android.apps.docs.storage/document/backup", "PasswordVault-backup.pvault", now - 2 * DAY_MS))),
            1L,
            null,
            false,
        )
        lateinit var gallery: SettingsGallery
        compose.runOnUiThread { gallery = SettingsGallery(rig) }
        rig.lock.status.value = rig.lock.status.value.copy(
            pin = PinStatus.On,
            fingerprint = FingerprintStatus.On,
            aside = listOf(AsideVault("00000000-0000-4000-8000-0000000000aa", now - 30 * DAY_MS, null)),
        )
        compose.setContent { GalleryHost(gallery) }
        compose.waitForIdle()

        Shots.take(compose, "01-settings-top")
        compose.scrollSettingsTo(SettingsTags.open(Route.Import()))
        Shots.take(compose, "02-settings-middle")
        compose.scrollSettingsTo(SettingsTags.help(HelpPage.VERSION))
        Shots.take(compose, "03-settings-end")
        compose.scrollSettingsTo(SettingsTags.PIN)
        step("04-lock-delay-dialog") { gallery.settings.openLockDelay() }
        step("05-clipboard-delay-dialog") {
            gallery.settings.dismissDialog()
            gallery.settings.openClipDelay()
        }
        step("06-appearance-dialog") {
            gallery.settings.dismissDialog()
            gallery.shown = SettingsGallery.Screen.THEME
        }
        step("07-settings-dark") {
            gallery.theme = ThemeChoice.DARK
            gallery.shown = SettingsGallery.Screen.SETTINGS
        }
        rig.lock.status.value = rig.lock.status.value.copy(fingerprint = FingerprintStatus.Off)
        step("08-fingerprint-dialog") {
            gallery.theme = ThemeChoice.LIGHT
            gallery.settings.setFingerprint(true)
        }
        step("09-app-lock-page") {
            gallery.settings.dismissDialog()
            gallery.shown = SettingsGallery.Screen.APP_LOCK
        }

        // The PIN steps as setup opens them: no PIN on this phone yet (lock.md F1 step 6), a vault just
        // made, so "Make backups" ends them (decision 24).
        PinSetupStart.offer(ChoosePinReason.NONE_HERE)
        PinSetupStart.newVault()
        step("10-create-pin") { gallery.shown = SettingsGallery.Screen.PIN_STEPS }
        step("11-create-pin-refused") { gallery.typeSteps("111111", Route.CreatePin) }
        compose.runOnUiThread { gallery.typeSteps("582913", Route.CreatePin) }
        compose.waitForIdle() // the move to the second step is made before its pad takes digits
        step("12-confirm-pin") { gallery.typeSteps("58", Route.ConfirmPin) }
        step("13-pin-is-set") { gallery.typeSteps("2913", Route.ConfirmPin) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(FingerprintOfferTags.USE)).fetchSemanticsNodes().isNotEmpty() }
        Shots.take(compose, "14-fingerprint-offer")
        step("15-make-backups") { gallery.pinSteps.fingerprintNotNow() }
        step("16-master-password-before-a-pin") { gallery.shown = SettingsGallery.Screen.PIN_STEPS_NO_PROOF }
        step("17-change-pin-current") { gallery.shown = SettingsGallery.Screen.CHANGE_PIN }
        step("18-set-a-pin-master-password") { gallery.shown = SettingsGallery.Screen.SET_PIN }
        assertTrue(shell("ls ${Shots.DIR}").contains("18-set-a-pin-master-password.png"))
    }

    private fun step(name: String, action: () -> Unit) {
        compose.runOnUiThread(action)
        compose.waitForIdle()
        Shots.take(compose, name)
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
