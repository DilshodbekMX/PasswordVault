package io.github.dilshodbekmx.passwordvault.ui.transfer.export

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.files.DocRef
import io.github.dilshodbekmx.passwordvault.files.FileTask
import io.github.dilshodbekmx.passwordvault.lock.MpPurpose
import io.github.dilshodbekmx.passwordvault.lock.MpResult
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportChoice
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportFile
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportFormat
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportScreen
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportTags
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportUiState
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportViewModel
import io.github.dilshodbekmx.passwordvault.ui.transfer.export.ExportRig.Companion.DOC
import io.github.dilshodbekmx.passwordvault.ui.transfer.export.ExportRig.Companion.MASTER
import io.github.dilshodbekmx.passwordvault.ui.transfer.export.ExportRig.Companion.NAME
import io.github.dilshodbekmx.passwordvault.ui.transfer.export.ExportRig.Companion.PASSWORD_GMAIL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 5.3: the export screen on a device, over the fakes (ux.md §5.20). The steps as a user
 * meets them: "Continue" on the warning works only with "I understand" ticked; the master password
 * is a masked field that no accessibility service reads and that is asked for every export; "Save
 * as" opens with the right name; the result names the file, with "Done" first and "Delete it now"
 * under it; an export cut short by a lock says so and asks again; the CSV card lists the accounts
 * Google will skip.
 */
@RunWith(AndroidJUnit4::class)
class ExportScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var rig: ExportRig? = null

    @After
    fun close() {
        rig?.close()
    }

    private fun show(task: FileTask? = null): Pair<ExportRig, () -> ExportViewModel> {
        val made = ExportRig(compose.activity, task = task).also { rig = it }
        lateinit var vm: ExportViewModel
        compose.setContent {
            PasswordVaultTheme(ThemeChoice.LIGHT) {
                vm = viewModel { made.viewModel() }
                ExportScreen(vm, made.nav)
            }
        }
        compose.waitForIdle()
        return made to { vm }
    }

    private fun string(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    /** From "Choose a format" through the warning of [choice] to the master password. */
    private fun toPassword(choice: ExportChoice) {
        compose.onNodeWithTag(ExportTags.choice(choice)).performClick()
        compose.onNodeWithTag(ExportTags.CONTINUE).performScrollTo().performClick()
        compose.onNodeWithTag(ExportTags.UNDERSTAND).performScrollTo().performClick()
        compose.onNodeWithTag(ExportTags.WARNING_CONTINUE).performScrollTo().performClick()
        compose.onNodeWithTag(ExportTags.PASSWORD).assertExists()
    }

    @Test
    fun theWarningsContinueWorksOnlyOnceIUnderstandIsTicked() {
        val (rig, vm) = show()
        compose.onNodeWithTag(ExportTags.choice(ExportChoice.BACKUP)).assertIsSelected()
        compose.onNodeWithTag(ExportTags.choice(ExportChoice.XLSX)).performClick()
        compose.onNodeWithTag(ExportTags.choice(ExportChoice.XLSX)).assertIsSelected()
        compose.onNodeWithTag(ExportTags.CONTINUE).performScrollTo().performClick()

        compose.onNodeWithTag(ExportTags.WARNING).assertTextContains(string(R.string.export_warning_title))
        compose.onNodeWithText(string(R.string.export_warning_xlsx)).assertExists()
        compose.onNodeWithTag(ExportTags.UNDERSTAND).performScrollTo().assertIsOff()
        compose.onNodeWithTag(ExportTags.WARNING_CONTINUE).performScrollTo().assertIsNotEnabled().performClick()
        compose.onNodeWithTag(ExportTags.PASSWORD).assertDoesNotExist()

        compose.onNodeWithTag(ExportTags.UNDERSTAND).performClick().assertIsOn()
        compose.onNodeWithTag(ExportTags.WARNING_CONTINUE).assertIsEnabled().performClick()
        compose.onNodeWithTag(ExportTags.PASSWORD).assertExists()
        assertTrue(vm().state.value is ExportUiState.Password)
        assertEquals("nothing was asked of the lock yet", emptyList<MpPurpose>(), rig.lock.purposes)
    }

    @Test
    fun theMasterPasswordIsMaskedAskedForEveryExportAndTheResultHasDoneFirst() {
        val (rig, vm) = show()
        toPassword(ExportChoice.CSV)
        compose.onNodeWithTag(ExportTags.SUBMIT).assertIsNotEnabled()
        compose.onNodeWithTag(ExportTags.PASSWORD).performTextInput(MASTER)
        compose.waitForIdle()
        // The field's node holds a mask character for each typed one, and no text of the screen any of it.
        val field = compose.onNodeWithTag(ExportTags.PASSWORD).fetchSemanticsNode().config
        assertTrue("the field is a password field", SemanticsProperties.Password in field)
        assertEquals("\u2022".repeat(MASTER.length), field.getOrNull(SemanticsProperties.EditableText)?.text)
        assertNoTextHolds(MASTER)
        compose.onNodeWithTag(ExportTags.SUBMIT).assertIsEnabled().performClick()
        compose.waitUntil(TIMEOUT_MS) { rig.picker.creates.isNotEmpty() }
        assertEquals(listOf(NAME_TODAY to "text/csv"), rig.picker.creates.map { it.first.replace(Regex("\\d{4}-\\d{2}-\\d{2}"), "DAY") to it.second })
        assertEquals(listOf(MpPurpose.EXPORT), rig.lock.purposes)
        assertEquals("the field is emptied", "", vm().password.text.toString())
        compose.onNodeWithTag(ExportTags.PICKING).assertExists()

        rig.pick()
        compose.waitUntil(TIMEOUT_MS) { vm().state.value is ExportUiState.Saved }
        compose.onNodeWithTag(ExportTags.SAVED).assertTextContains(string(R.string.export_saved, NAME))
        compose.onNodeWithTag(ExportTags.UPLOAD).assertDoesNotExist() // the phone's own storage
        // "Done" is the main button, above "Delete it now".
        val done = compose.onNodeWithTag(ExportTags.DONE).assertTextContains(string(R.string.common_done)).fetchSemanticsNode().boundsInRoot
        val delete = compose.onNodeWithTag(ExportTags.DELETE).assertTextContains(string(R.string.export_delete_now)).fetchSemanticsNode().boundsInRoot
        assertTrue("Done above Delete it now: $done, $delete", done.bottom <= delete.top)
        assertTrue(String(rig.documents.files.getValue(DOC), Charsets.UTF_8).contains(PASSWORD_GMAIL))
        assertEquals(listOf(DOC), rig.repository.stored.plainExports.map { it.uri })
        assertNoTextHolds(PASSWORD_GMAIL)

        compose.onNodeWithTag(ExportTags.DONE).performClick()
        assertEquals(1, rig.nav.backs)
    }

    @Test
    fun aWrongPasswordIsSaidUnderTheFieldAndTheKeyboardsDoneSubmits() {
        val (rig, vm) = show()
        toPassword(ExportChoice.JSON)
        rig.lock.mpResults.addLast(MpResult.Wrong)
        compose.onNodeWithTag(ExportTags.PASSWORD).performTextInput("wrong")
        compose.onNodeWithTag(ExportTags.PASSWORD).performImeAction()
        compose.waitUntil(TIMEOUT_MS) { (vm().state.value as? ExportUiState.Password)?.error != null }
        compose.onNodeWithTag(ExportTags.PASSWORD_LINE).assertTextContains(string(R.string.export_password_wrong))
        assertTrue(rig.picker.creates.isEmpty())

        compose.onNodeWithTag(ExportTags.PASSWORD).performTextInput(MASTER)
        compose.onNodeWithTag(ExportTags.PASSWORD).performImeAction()
        compose.waitUntil(TIMEOUT_MS) { rig.picker.creates.isNotEmpty() }
        assertEquals(listOf(MpPurpose.EXPORT, MpPurpose.EXPORT), rig.lock.purposes)
    }

    @Test
    fun anExportCutShortByALockSaysSoAsksAgainAndCancelSaysWhatBecameOfTheFile() {
        val task = FileTask(FileTask.Kind.EXPORT, ExportFile(ExportFormat.CSV).step, DocRef(DOC))
        val (rig, vm) = show(task)
        compose.waitUntil(TIMEOUT_MS) { (vm().state.value as? ExportUiState.Password)?.name != null }
        compose.onNodeWithTag(ExportTags.PAUSED).assertExists()
        compose.onNodeWithText(string(R.string.export_paused)).assertExists()
        compose.onNodeWithText(string(R.string.export_paused_body, NAME)).assertExists()

        compose.onNodeWithTag(ExportTags.CANCEL).performScrollTo().performClick()
        compose.waitUntil(TIMEOUT_MS) { (vm().state.value as? ExportUiState.Ended)?.cleanup != null }
        compose.onNodeWithTag(ExportTags.ENDED).assertTextContains(string(R.string.export_cleanup_removed))
        assertFalse("the empty file was removed", DOC in rig.documents.files)
        assertEquals(emptyList<MpPurpose>(), rig.lock.purposes)
        compose.onNodeWithTag(ExportTags.DONE).performClick()
        assertEquals(1, rig.nav.backs)
    }

    @Test
    fun aPausedExportIsWrittenIntoItsFileAfterTheMasterPassword() {
        val task = FileTask(FileTask.Kind.EXPORT, ExportFile(ExportFormat.JSON).step, DocRef(DOC))
        val (rig, vm) = show(task)
        compose.onNodeWithTag(ExportTags.PAUSED).assertExists()
        compose.onNodeWithTag(ExportTags.PASSWORD).performTextInput(MASTER)
        compose.onNodeWithTag(ExportTags.SUBMIT).performClick()
        compose.waitUntil(TIMEOUT_MS) { vm().state.value is ExportUiState.Saved }
        assertTrue("no second \"Save as\"", rig.picker.creates.isEmpty())
        assertTrue(String(rig.documents.files.getValue(DOC), Charsets.UTF_8).contains(PASSWORD_GMAIL))
    }

    @Test
    fun theCsvCardSaysWhatGoogleSkipsAndSeeWhichNamesTheAccounts() {
        show()
        compose.onNodeWithTag(ExportTags.choice(ExportChoice.CSV)).performClick()
        compose.onNodeWithTag(ExportTags.CONTINUE).performScrollTo().performClick()
        compose.onNodeWithText(string(R.string.export_warning_csv)).assertExists()
        compose.onNodeWithTag(ExportTags.CSV_CARD).performScrollTo()
        val resources = compose.activity.resources
        compose.onNodeWithText(resources.getQuantityString(R.plurals.export_csv_skip_no_website, 1, 1)).assertExists()
        compose.onNodeWithText(resources.getQuantityString(R.plurals.export_csv_skip_no_password, 1, 1)).assertExists()
        compose.onNodeWithTag(ExportTags.WHICH).assertDoesNotExist()
        compose.onNodeWithTag(ExportTags.SEE_WHICH).performScrollTo().performClick()
        compose.onNodeWithTag(ExportTags.WHICH).assertExists()
        compose.onNodeWithText("Ипотека банк").assertExists()
        compose.onNodeWithText("Uy Wi-Fi").assertExists()
        compose.onNodeWithText(string(R.string.export_csv_not_kept)).performScrollTo().assertExists()
    }

    @Test
    fun backFromTheWarningGoesToTheFormatsAndFromThemLeaves() {
        val (rig, vm) = show()
        compose.onNodeWithTag(ExportTags.choice(ExportChoice.JSON)).performClick()
        compose.onNodeWithTag(ExportTags.CONTINUE).performScrollTo().performClick()
        compose.onNodeWithTag(ExportTags.WARNING).assertExists()
        compose.onNodeWithTag(ExportTags.BACK).performClick()
        compose.onNodeWithTag(ExportTags.choice(ExportChoice.JSON)).assertIsSelected()
        assertEquals(0, rig.nav.backs)
        compose.onNodeWithTag(ExportTags.BACK).performClick()
        assertEquals(1, rig.nav.backs)
        assertTrue(vm().state.value is ExportUiState.Choose)
    }

    @Test
    fun theBackupRowOpensTheBackupScreen() {
        val (rig, _) = show()
        compose.onNodeWithTag(ExportTags.CONTINUE).performScrollTo().performClick()
        compose.waitUntil(TIMEOUT_MS) { rig.nav.opened.isNotEmpty() }
        assertEquals(listOf<Route>(Route.Backup), rig.nav.opened.toList())
    }

    /** No text, description or state of the screen holds four characters in a row of [secret]. */
    private fun assertNoTextHolds(secret: String) {
        val shown = compose.everyText()
        assertTrue("the screen's texts are read", shown.size >= 3)
        for (text in shown) {
            for (part in secret.windowed(4)) assertFalse("\"$text\" shows \"$part\" of a secret", part in text)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val NAME_TODAY = "passwords-DAY.csv"
    }
}
