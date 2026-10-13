package io.github.dilshodbekmx.passwordvault.ui.transfer.`import`

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.formats.ColumnMeaning
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.data.BackupResult
import io.github.dilshodbekmx.passwordvault.files.FileTask
import io.github.dilshodbekmx.passwordvault.ui.transfer.ColumnMappingScreen
import io.github.dilshodbekmx.passwordvault.ui.transfer.ColumnsScreen
import io.github.dilshodbekmx.passwordvault.ui.transfer.ImportColumnsViewModel
import io.github.dilshodbekmx.passwordvault.ui.transfer.ImportScreen
import io.github.dilshodbekmx.passwordvault.ui.transfer.ImportSource
import io.github.dilshodbekmx.passwordvault.ui.transfer.ImportSourcesScreen
import io.github.dilshodbekmx.passwordvault.ui.transfer.ImportTags
import io.github.dilshodbekmx.passwordvault.ui.transfer.`import`.ImportRig.Companion.CHROME_CSV
import io.github.dilshodbekmx.passwordvault.ui.transfer.`import`.ImportRig.Companion.CHROME_PASSWORDS
import io.github.dilshodbekmx.passwordvault.ui.transfer.`import`.ImportRig.Companion.DOC
import io.github.dilshodbekmx.passwordvault.ui.transfer.`import`.ImportRig.Companion.account
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 5.4 on a device: the import's screens drawn with the app's fakes (ux.md §5.21). The
 * preview shows names, usernames and counts and no password in any node, wherever it is scrolled; the
 * import, its undo and the question about the file; the column mapping, which draws the password
 * column's first value as dots once it is chosen; a backup file; the sources and the Google help.
 */
@RunWith(AndroidJUnit4::class)
class ImportScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var rig = ImportRig()

    @After
    fun tearDown() = compose.runOnUiThread { rig.close() }

    private fun string(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    private fun plural(id: Int, count: Int, vararg args: Any): String = compose.activity.resources.getQuantityString(id, count, *args)

    private fun noPasswordShown() {
        val texts = compose.shownTexts()
        for (password in CHROME_PASSWORDS) {
            assertTrue("the screen shows a password: ${texts.size} texts", texts.none { it.contains(password) })
        }
    }

    @Test
    fun thePreviewShowsNamesUsernamesAndCountsButNoPassword() {
        rig.recorded(CHROME_CSV.toByteArray())
        val vm = rig.file()
        compose.setContent { ImportTestTheme { ImportScreen(vm, rig.nav) } }
        compose.waitUntil(TIMEOUT_MS) { vm.ui.value.screen is ImportScreen.Preview }
        compose.onNodeWithTag(ImportTags.FOUND).assertIsDisplayed()
        compose.onNodeWithText(plural(R.plurals.import_found, 5, 5)).assertIsDisplayed()
        compose.onNodeWithText("Почта", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("азиз", useUnmergedTree = true).assertExists()
        compose.onNodeWithText(plural(R.plurals.import_button, 5, 5)).assertIsDisplayed()
        noPasswordShown()
        // Every part of the list, scrolled to: none shows a password.
        for (key in listOf("counts", "wrong", "tidy", "columns")) {
            compose.onNodeWithTag(ImportTags.PREVIEW).performScrollToKey(key)
            compose.waitForIdle()
            noPasswordShown()
        }
        compose.onNodeWithText(plural(R.plurals.import_wrong_row, 6, 7, 6, 5), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(ImportTags.INCLUDE_WRONG).performClick()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTag(ImportTags.IMPORT).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithText(plural(R.plurals.import_button, 6, 6)).assertExists()
        noPasswordShown()
    }

    /**
     * The owner's decision 20: a hidden value over 1,000 characters is not imported, and the preview
     * lists it on its account: "Bank: 1 hidden value was too long to keep hidden and was not
     * imported". No text of the preview shows any of it.
     */
    @Test
    fun aHiddenValueTooLongToKeepHiddenIsListedOnItsAccountAndNeverShown() {
        val secret = "Qz8" + (1..250).joinToString("") { "Xy${it % 10}w" }
        rig.recorded(
            (
                """{"encrypted": false, "items": [{"type": 1, "name": "Bank", "fields": [{"name": "Recovery key", "value": "$secret", "type": 1}],""" +
                    """ "login": {"username": "aziz", "password": "Pw-Bank-1", "uris": [{"uri": "https://bank.uz"}]}}]}"""
                ).toByteArray(),
            name = "bitwarden.json",
        )
        val vm = rig.file()
        compose.setContent { ImportTestTheme { ImportScreen(vm, rig.nav) } }
        compose.waitUntil(TIMEOUT_MS) { vm.ui.value.screen is ImportScreen.Preview }
        compose.onNodeWithTag(ImportTags.PREVIEW).performScrollToKey("changes")
        compose.waitForIdle()
        val line = plural(R.plurals.import_change_hidden_not_imported, 1, 1)
        compose.onNodeWithText(string(R.string.import_change, "Bank", line), useUnmergedTree = true).assertExists()
        val texts = compose.shownTexts()
        assertTrue("the value is not shown", texts.none { text -> secret.windowed(12, 50).any { it in text } })
    }

    @Test
    fun importThenTheQuestionAboutTheFile() {
        rig.recorded(CHROME_CSV.toByteArray())
        val vm = rig.file()
        compose.setContent { ImportTestTheme { ImportScreen(vm, rig.nav) } }
        compose.waitUntil(TIMEOUT_MS) { vm.ui.value.screen is ImportScreen.Preview }
        compose.onNodeWithTag(ImportTags.IMPORT).performClick()
        compose.waitUntil(TIMEOUT_MS) { vm.ui.value.screen is ImportScreen.Done }
        compose.onNodeWithText(plural(R.plurals.import_done, 5, 5)).assertIsDisplayed()
        compose.onNodeWithTag(ImportTags.UNDO).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.import_file_question, "Chrome Passwords.csv")).assertIsDisplayed()
        noPasswordShown()

        compose.onNodeWithTag(ImportTags.DELETE_FILE).performClick()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTag(ImportTags.FILE_OUTCOME).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(string(R.string.import_file_deleted)).assertIsDisplayed()
        compose.onNodeWithTag(ImportTags.UNDO).assertDoesNotExist()
        assertTrue(DOC !in rig.files.files)
        compose.onNodeWithTag(ImportTags.FINISH).performClick()
        compose.waitUntil(TIMEOUT_MS) { rig.nav.moves.isNotEmpty() }
        assertEquals(listOf("back"), rig.nav.moves)
        assertEquals(null, rig.settings.fileTask.value)
    }

    @Test
    fun undoPutsThePasswordsBack() {
        val vault = Vault(entries = listOf(account("11111111-1111-4111-8111-111111111111", "Gmail", "aziz", "P-1", "https://mail.google.com")))
        rig = ImportRig(vault)
        rig.recorded(CHROME_CSV.toByteArray())
        val vm = rig.file()
        compose.setContent { ImportTestTheme { ImportScreen(vm, rig.nav) } }
        compose.waitUntil(TIMEOUT_MS) { vm.ui.value.screen is ImportScreen.Preview }
        compose.onNodeWithTag(ImportTags.IMPORT).performClick()
        compose.waitUntil(TIMEOUT_MS) { vm.ui.value.screen is ImportScreen.Done }
        compose.onNodeWithTag(ImportTags.UNDO).performClick()
        compose.waitUntil(TIMEOUT_MS) { (vm.ui.value.screen as? ImportScreen.Done)?.done?.undone == true }
        compose.onNodeWithText(string(R.string.import_undone)).assertIsDisplayed()
        assertEquals(vault, rig.repository.stored)
    }

    @Test
    fun theMappingDrawsThePasswordColumnsFirstValueAsDots() {
        rig.recorded("Sayt,Login,Kalit\nhttps://my.gov.uz,aziz,Kalit-1-Pw\n".toByteArray())
        val file = rig.file()
        var mapping by mutableStateOf(false)
        lateinit var columns: ImportColumnsViewModel
        compose.setContent {
            ImportTestTheme { if (mapping) ColumnMappingScreen(columns, rig.nav) else ImportScreen(file, rig.nav) }
        }
        compose.waitUntil(TIMEOUT_MS) { rig.nav.moves.isNotEmpty() }
        assertEquals("the mapping opens by itself", listOf("go ColumnMapping"), rig.nav.moves)

        compose.runOnUiThread {
            rig.nav.moves.clear()
            columns = rig.columns()
            mapping = true
        }
        compose.waitUntil(TIMEOUT_MS) { columns.ui.value.screen is ColumnsScreen.Shown }
        compose.onNodeWithText(string(R.string.import_columns_no_password)).assertIsDisplayed()
        compose.onNodeWithTag(ImportTags.MAPPING_NO_PASSWORD).assertExists()
        // Not yet the password column: its first value is shown, so that the user can recognise it.
        assertTrue(compose.shownTexts().any { it.contains("Kalit-1-Pw") })

        // On a short screen the third column is under the bottom bar: scrolled into view first.
        compose.onNodeWithTag(ImportTags.column(2)).performScrollTo().performClick()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTag(ImportTags.meaning(ColumnMeaning.PASSWORD)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(ImportTags.meaning(ColumnMeaning.PASSWORD)).performClick()
        compose.waitForIdle()
        assertTrue("dots once it is the password column", compose.shownTexts().none { it.contains("Kalit-1-Pw") })
        compose.onNodeWithContentDescription(string(R.string.import_columns_hidden_value), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(ImportTags.MAPPING_NO_PASSWORD).assertDoesNotExist()

        compose.onNodeWithTag(ImportTags.MAPPING_CONTINUE).performClick()
        compose.waitUntil(TIMEOUT_MS) { rig.nav.moves.isNotEmpty() }
        assertEquals(listOf("back"), rig.nav.moves)
        assertEquals(listOf(ColumnMeaning.WEBSITE, ColumnMeaning.USERNAME, ColumnMeaning.PASSWORD), rig.memory.choicesFor(io.github.dilshodbekmx.passwordvault.files.DocRef(DOC)).columns)
    }

    @Test
    fun aBackupFileAsksForItsMasterPasswordAndAddsItsAccounts() {
        rig.recorded("PVAULT01".toByteArray() + ByteArray(64), name = "PasswordVault-backup-2026-10-01.pvault")
        val backup = Vault(entries = listOf(account("22222222-2222-4222-8222-222222222222", "Uzum", "aziz", "Pw-Uzum", "https://uzum.uz")))
        rig.repository.backupResults.add(BackupResult.Opened(backup, System.currentTimeMillis() - 7 * 86_400_000L))
        val vm = rig.file()
        compose.setContent { ImportTestTheme { ImportScreen(vm, rig.nav) } }
        compose.waitUntil(TIMEOUT_MS) { vm.ui.value.screen is ImportScreen.BackupPassword }
        compose.onNodeWithText(string(R.string.import_backup_prompt)).assertIsDisplayed()
        compose.onNodeWithTag(ImportTags.BACKUP_PASSWORD).performTextInput("correct horse battery")
        compose.onNodeWithTag(ImportTags.BACKUP_OPEN).performClick()
        compose.waitUntil(TIMEOUT_MS) { vm.ui.value.screen is ImportScreen.BackupSummary }
        compose.onNodeWithTag(ImportTags.BACKUP_SUMMARY).assertIsDisplayed()
        compose.onNodeWithTag(ImportTags.BACKUP_ADD).performClick()
        compose.waitUntil(TIMEOUT_MS) { vm.ui.value.screen is ImportScreen.BackupAdded }
        compose.onNodeWithText(plural(R.plurals.import_backup_added, 1, 1)).assertIsDisplayed()
        assertEquals(1, rig.repository.stored.entries.size)
    }

    @Test
    fun theSourcesOpenTheGoogleHelpThenThePickerAndAChosenFileOpensTheFileStep() {
        compose.runOnUiThread { rig.attach() }
        val vm = rig.sources()
        compose.setContent { ImportTestTheme { ImportSourcesScreen(vm, rig.nav) } }
        for (source in ImportSource.entries) compose.onNodeWithTag(ImportTags.source(source)).assertExists()
        compose.onNodeWithTag(ImportTags.source(ImportSource.GOOGLE)).performClick()
        compose.onNodeWithTag(ImportTags.HELP).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.import_google_steps)).assertIsDisplayed()
        compose.onNodeWithTag(ImportTags.HELP_CANT_FIND).performClick()
        compose.onNodeWithTag(ImportTags.HELP_SETTINGS).assertIsDisplayed()
        compose.onNodeWithTag(ImportTags.CHOOSE_FILE).performClick()
        compose.waitUntil(TIMEOUT_MS) { rig.launcher.opened == 1 }
        compose.waitUntil(TIMEOUT_MS) { rig.settings.fileTask.value?.kind == FileTask.Kind.IMPORT }
        compose.runOnUiThread { rig.answer(DOC, CHROME_CSV.toByteArray()) }
        compose.waitUntil(TIMEOUT_MS) { rig.nav.moves.isNotEmpty() }
        assertEquals(listOf("replace Import(step=1)"), rig.nav.moves)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
