package io.github.dilshodbekmx.passwordvault.ui.transfer.export

import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportActions
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportFrame
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportTags
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pictures of the export's main steps for the user (build plan 5.3), in English, light and dark:
 * the shell user's `screencap` keeps them in `/data/local/tmp/pv-shots-5.3/5.3-*.png`, from where
 * the package's agent copies them into /home/asus/pvwork/shots-5/. Each step is the screen's own
 * frame, drawn from a state of [ExportStates].
 */
@RunWith(AndroidJUnit4::class)
class ExportScreenshotsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theMainStepsInLightAndDark() {
        val states = ExportStates.all.toMap()
        val password = TextFieldState("Toshkent-2026")
        var shown by mutableStateOf<ExportUiState>(states.getValue("choose"))
        var theme by mutableStateOf(ThemeChoice.LIGHT)
        compose.setContent {
            PasswordVaultTheme(theme) {
                ExportFrame(shown, password, ExportActions(), onBack = {})
            }
        }
        ExportShots.USER.start()
        fun take(file: String, step: String, scrollTo: String? = null, state: ExportUiState = states.getValue(step)) {
            compose.runOnUiThread { shown = state }
            compose.waitForIdle()
            if (scrollTo != null) compose.onNodeWithTag(scrollTo).performScrollTo()
            ExportShots.USER.take(compose, file)
        }
        // The CSV warning as this phone shows it (Android 17: no line about Downloads), with the names listed afterwards.
        val csv = (states.getValue("warning-csv") as ExportUiState.Warning).copy(oldAndroid = false, showWhich = false)
        take("5.3-01-choose", "choose")
        take("5.3-02-warning-csv", "warning-csv", state = csv)
        take("5.3-03-warning-csv-see-which", "warning-csv", scrollTo = ExportTags.WARNING_CONTINUE, state = csv.copy(showWhich = true, understood = true))
        take("5.3-04-warning-excel", "warning-xlsx", scrollTo = ExportTags.WARNING_CONTINUE)
        take("5.3-05-password", "password")
        take("5.3-06-password-wrong", "password-wrong")
        take("5.3-07-paused", "paused")
        take("5.3-08-saving", "saving")
        take("5.3-09-saved-drive", "saved-drive")
        take("5.3-10-saved-part-1-of-2", "saved-part")
        take("5.3-11-cannot-delete", "saved-not-deletable")
        take("5.3-12-write-failed", "failed-write")
        take("5.3-13-pending", "pending")
        compose.runOnUiThread { theme = ThemeChoice.DARK }
        take("5.3-14-choose-dark", "choose")
        take("5.3-15-warning-csv-dark", "warning-csv", state = csv)
        take("5.3-16-password-dark", "password")
        take("5.3-17-saved-dark", "saved-drive")
        val taken = ExportShots.USER.taken()
        assertTrue("17 pictures: $taken", taken.size >= 17)
    }
}
