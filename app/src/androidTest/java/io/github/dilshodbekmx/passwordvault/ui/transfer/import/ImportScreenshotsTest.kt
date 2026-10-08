package io.github.dilshodbekmx.passwordvault.ui.transfer.`import`

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import io.github.dilshodbekmx.passwordvault.core.formats.ColumnMeaning
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.data.BackupResult
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.transfer.ColumnMappingScreen
import io.github.dilshodbekmx.passwordvault.ui.transfer.ColumnsScreen
import io.github.dilshodbekmx.passwordvault.ui.transfer.ImportColumnsViewModel
import io.github.dilshodbekmx.passwordvault.ui.transfer.ImportScreen
import io.github.dilshodbekmx.passwordvault.ui.transfer.ImportSource
import io.github.dilshodbekmx.passwordvault.ui.transfer.ImportSourcesScreen
import io.github.dilshodbekmx.passwordvault.ui.transfer.ImportSourcesViewModel
import io.github.dilshodbekmx.passwordvault.ui.transfer.ImportTags
import io.github.dilshodbekmx.passwordvault.ui.transfer.ImportViewModel
import io.github.dilshodbekmx.passwordvault.ui.transfer.`import`.ImportRig.Companion.account
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pictures of the import's screens for the user (the round's note), on Android 17 with the debug
 * build, in English and the default colours: [ImportShots] saves each as `5.4-NN-name.png` in
 * `/data/local/tmp/pv-shots-5.4/` on the device, and the package's agent copies them to
 * `/home/asus/pvwork/shots-5/`. The file is a Chrome export with the user's kinds of accounts, two of
 * them here already, so that the preview shows its counts.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 37)
class ImportScreenshotsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val rig = ImportRig(
        Vault(
            entries = listOf(
                account("11111111-1111-4111-8111-111111111111", "Kapitalbank", "aziz.karimov", "Pw-Bank-5521", "https://kapitalbank.uz"),
                account("22222222-2222-4222-8222-222222222222", "Uzum Market", "+998901234567", "Old-Uzum-1", "https://uzum.uz"),
            ),
        ),
    )

    @After
    fun tearDown() = compose.runOnUiThread { rig.close() }

    @Test
    fun theScreensForTheUser() {
        var which by mutableIntStateOf(SOURCES)
        var theme by mutableStateOf(ThemeChoice.LIGHT)
        lateinit var sources: ImportSourcesViewModel
        var file by mutableStateOf<ImportViewModel?>(null)
        var columns by mutableStateOf<ImportColumnsViewModel?>(null)
        compose.runOnUiThread {
            rig.attach()
            sources = rig.sources()
        }
        compose.setContent {
            ImportTestTheme(theme) {
                when (which) {
                    SOURCES -> ImportSourcesScreen(sources, rig.nav)
                    FILE -> file?.let { ImportScreen(it, rig.nav) }
                    else -> columns?.let { ColumnMappingScreen(it, rig.nav) }
                }
            }
        }
        ImportShots.take(compose, "5.4-01-sources")
        compose.onNodeWithTag(ImportTags.source(ImportSource.GOOGLE)).performClick()
        ImportShots.take(compose, "5.4-02-google-help")
        compose.onNodeWithTag(ImportTags.HELP_CANT_FIND).performClick()
        ImportShots.take(compose, "5.4-03-google-help-settings")

        // The file Chrome exported.
        rig.recorded((ImportRig.CHROME_CSV + "Google,https://www.youtube.com,aziz.karimov@gmail.com,Pw-Gmail-7731,\n").toByteArray())
        compose.runOnUiThread {
            file = rig.file()
            which = FILE
        }
        compose.waitUntil(TIMEOUT_MS) { file!!.ui.value.screen is ImportScreen.Preview }
        ImportShots.take(compose, "5.4-04-preview")
        compose.onNodeWithTag(ImportTags.PREVIEW).performScrollToKey("wrong")
        ImportShots.take(compose, "5.4-05-preview-rows")
        compose.onNodeWithTag(ImportTags.PREVIEW).performScrollToKey("columns")
        ImportShots.take(compose, "5.4-06-preview-names")
        compose.runOnUiThread { theme = ThemeChoice.DARK }
        compose.onNodeWithTag(ImportTags.PREVIEW).performScrollToKey("sample")
        ImportShots.take(compose, "5.4-07-preview-dark")
        compose.runOnUiThread { theme = ThemeChoice.LIGHT }

        compose.onNodeWithTag(ImportTags.IMPORT).performClick()
        compose.waitUntil(TIMEOUT_MS) { file!!.ui.value.screen is ImportScreen.Done }
        ImportShots.take(compose, "5.4-08-imported")
        compose.onNodeWithTag(ImportTags.DELETE_FILE).performClick()
        compose.waitUntil(TIMEOUT_MS) { (file!!.ui.value.screen as? ImportScreen.Done)?.done?.file != null }
        ImportShots.take(compose, "5.4-09-file-deleted")

        // A spreadsheet's CSV whose columns are not recognised.
        rig.recorded("Sayt,Login,Kalit,Izoh\nhttps://my.gov.uz,AA1234567,Kalit-1-Pw,Davlat xizmatlari\n".toByteArray(), uri = OTHER)
        compose.runOnUiThread {
            columns = rig.columns()
            which = MAPPING
        }
        compose.waitUntil(TIMEOUT_MS) { columns!!.ui.value.screen is ColumnsScreen.Shown }
        ImportShots.take(compose, "5.4-10-columns")
        compose.onNodeWithTag(ImportTags.column(2)).performClick()
        ImportShots.take(compose, "5.4-11-columns-menu")
        compose.onNodeWithTag(ImportTags.meaning(ColumnMeaning.PASSWORD)).performClick()
        ImportShots.take(compose, "5.4-12-columns-chosen")

        // A backup file of this app.
        rig.recorded("PVAULT01".toByteArray() + ByteArray(64), name = "PasswordVault-backup-2026-10-01.pvault", uri = BACKUP)
        rig.repository.backupResults.add(BackupResult.Opened(Vault(entries = List(52) { i -> account("%08x-1111-4111-8111-111111111111".format(i), "Account $i", "u$i", "P-$i", "https://site$i.uz") }), System.currentTimeMillis() - 5 * 86_400_000L))
        compose.runOnUiThread {
            file = rig.file()
            which = FILE
        }
        compose.waitUntil(TIMEOUT_MS) { file!!.ui.value.screen is ImportScreen.BackupPassword }
        compose.onNodeWithTag(ImportTags.BACKUP_PASSWORD).performTextInput("correct horse battery staple")
        ImportShots.take(compose, "5.4-13-backup-password")
        compose.onNodeWithTag(ImportTags.BACKUP_OPEN).performClick()
        compose.waitUntil(TIMEOUT_MS) { file!!.ui.value.screen is ImportScreen.BackupSummary }
        ImportShots.take(compose, "5.4-14-backup-summary")

        // An Excel file.
        rig.recorded(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0), name = "Passwords.xlsx", uri = EXCEL)
        compose.runOnUiThread { file = rig.file() }
        compose.waitUntil(TIMEOUT_MS) { file!!.ui.value.screen is ImportScreen.Problem }
        ImportShots.take(compose, "5.4-15-excel-file")
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val SOURCES = 0
        const val FILE = 1
        const val MAPPING = 2
        const val OTHER = "content://com.android.providers.downloads.documents/document/1301"
        const val BACKUP = "content://com.android.providers.downloads.documents/document/1302"
        const val EXCEL = "content://com.android.providers.downloads.documents/document/1303"
    }
}
