package io.github.dilshodbekmx.passwordvault.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.PinStatus
import io.github.dilshodbekmx.passwordvault.nav.HelpPage
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.settings.StyleChoice
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.setup.PinSetupViewModel

/**
 * Every screen of build plan 4.3 in one composition, for the walks that a test can make only with
 * one `setContent`: the en-XA walk (build plan §1 rule 5) and the screenshots for the user. [shown]
 * picks the screen, [style] the colours and [theme] light or dark; the view models are made over
 * [rig]'s fakes, and the walks move them on with the same calls the screens make. A choice in the
 * dialog "Appearance" changes [style] and [theme], as the stored choice changes the app's.
 */
class SettingsGallery(val rig: SettingsRig) {
    /** The screens. */
    enum class Screen { SETTINGS, APP_LOCK, THEME, PIN_STEPS, PIN_STEPS_NO_PROOF, CHANGE_PIN, SET_PIN, PIN_STEPS_UNSURE, CHANGE_PIN_UNSURE }

    var shown by mutableStateOf(Screen.SETTINGS)
    var theme by mutableStateOf(ThemeChoice.LIGHT)
    var style by mutableStateOf(StyleChoice.DEFAULT)

    val settings: SettingsViewModel = rig.settingsViewModel()
    val pinSteps: PinSetupViewModel = rig.pinSetupViewModel(canAuthenticate = 0)

    /** The PIN steps of a flow without a proof (a fingerprint unlock): the master password first. */
    val pinStepsNoProof: PinSetupViewModel

    /** "Change PIN": a PIN is set. */
    val changePin: ChangePinViewModel

    /** "Set a PIN": no PIN works. */
    val setPin: ChangePinViewModel

    /** A lock that leaves the screens on "Saving…" (build-plan.md §12, decision 4; [GatedLock]). */
    val gated = GatedLock(rig.lock)

    /** The PIN steps over [gated]: the walk tells them that the PIN may not have been saved. */
    val pinStepsUnsure: PinSetupViewModel = rig.pinSetupViewModel(canAuthenticate = 0, lock = gated)

    /** "Change PIN" over [gated], for the same. */
    val changePinUnsure: ChangePinViewModel

    init {
        val unlocked = rig.lock.state.value
        rig.lock.state.value = LockUiState.Unlocked(emptyList(), null)
        pinStepsNoProof = rig.pinSetupViewModel(canAuthenticate = 0)
        pinStepsNoProof.start()
        rig.lock.state.value = unlocked
        rig.lock.status.value = rig.lock.status.value.copy(pin = PinStatus.On)
        changePin = rig.changePinViewModel()
        changePinUnsure = rig.changePinViewModel(lock = gated)
        rig.lock.status.value = rig.lock.status.value.copy(pin = PinStatus.NotSet)
        setPin = rig.changePinViewModel()
    }

    /** Types [pin] on the PIN steps' pad of [on]. */
    fun typeSteps(pin: String, on: Route) = pin.forEach { pinSteps.digit(it - '0', on) }

    /** Types [pin] on the pad of "Change PIN". */
    fun typeChange(pin: String) = pin.forEach { changePin.digit(it - '0') }

    /** Types [pin] on the PIN steps' pad of [on], over the lock that does not answer yet. */
    fun typeStepsUnsure(pin: String, on: Route) = pin.forEach { pinStepsUnsure.digit(it - '0', on) }

    /** Types [pin] on the pad of "Change PIN", over the lock that does not answer yet. */
    fun typeChangeUnsure(pin: String) = pin.forEach { changePinUnsure.digit(it - '0') }
}

/** The gallery's screen in its style and theme. */
@Composable
fun GalleryHost(gallery: SettingsGallery) {
    SettingsTestTheme(gallery.theme, gallery.style) {
        when (gallery.shown) {
            SettingsGallery.Screen.SETTINGS -> SettingsScreen(gallery.settings, gallery.rig.nav)
            SettingsGallery.Screen.APP_LOCK -> AppLockPage(gallery.settings, gallery.rig.nav)
            SettingsGallery.Screen.THEME -> {
                val ui by gallery.settings.ui.collectAsStateWithLifecycle()
                SettingsScreen(gallery.settings, gallery.rig.nav)
                AppearanceDialog(
                    styles = ui.styles,
                    style = gallery.style,
                    theme = gallery.theme,
                    onStyle = { gallery.style = it },
                    onTheme = { gallery.theme = it },
                    onDone = {},
                )
            }
            SettingsGallery.Screen.PIN_STEPS -> PinStepsHost(gallery.pinSteps, gallery.rig.nav)
            SettingsGallery.Screen.PIN_STEPS_NO_PROOF -> PinStepsHost(gallery.pinStepsNoProof, gallery.rig.nav)
            SettingsGallery.Screen.CHANGE_PIN -> ChangePinScreen(gallery.changePin, gallery.rig.nav)
            SettingsGallery.Screen.SET_PIN -> ChangePinScreen(gallery.setPin, gallery.rig.nav)
            SettingsGallery.Screen.PIN_STEPS_UNSURE -> PinStepsHost(gallery.pinStepsUnsure, gallery.rig.nav)
            SettingsGallery.Screen.CHANGE_PIN_UNSURE -> ChangePinScreen(gallery.changePinUnsure, gallery.rig.nav)
        }
    }
}

/** The rows of the Settings list from the top to the bottom, one tag each, for walks that scroll through it. */
val SETTINGS_ROWS: List<String> = listOf(
    SettingsTags.PIN,
    SettingsTags.FINGERPRINT,
    SettingsTags.LOCK_DELAY,
    SettingsTags.CLIP_DELAY,
    SettingsTags.CHANGE_MASTER_PASSWORD,
    SettingsTags.LOCK_NOW,
    SettingsTags.open(Route.Backup),
    SettingsTags.BACKUP_REMINDERS,
    SettingsTags.open(Route.Export()),
    SettingsTags.open(Route.Import()),
    SettingsTags.oldPasswords(0),
    SettingsTags.open(Route.Language),
    SettingsTags.open(Route.Theme),
    SettingsTags.help(HelpPage.PROTECTION),
    SettingsTags.help(HelpPage.FORGOT_PIN),
    SettingsTags.open(Route.Licences),
    SettingsTags.help(HelpPage.VERSION),
)

/** Scrolls the Settings list until the row [tag] is shown. */
fun ComposeTestRule.scrollSettingsTo(tag: String) {
    onNodeWithTag(SettingsTags.LIST).performScrollToNode(hasTestTag(tag))
    waitForIdle()
}
