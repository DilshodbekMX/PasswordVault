package io.github.dilshodbekmx.passwordvault.ui.transfer.`import`

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.ui.layout.LayoutHook
import io.github.dilshodbekmx.passwordvault.ui.layout.press
import io.github.dilshodbekmx.passwordvault.ui.layout.revealTag
import io.github.dilshodbekmx.passwordvault.ui.layout.revealText
import io.github.dilshodbekmx.passwordvault.ui.transfer.ColumnMappingScreen
import io.github.dilshodbekmx.passwordvault.ui.transfer.ColumnsScreen
import io.github.dilshodbekmx.passwordvault.ui.transfer.ImportColumnsViewModel
import io.github.dilshodbekmx.passwordvault.ui.transfer.ImportScreen
import io.github.dilshodbekmx.passwordvault.ui.transfer.ImportSourcesScreen
import io.github.dilshodbekmx.passwordvault.ui.transfer.ImportSourcesViewModel
import io.github.dilshodbekmx.passwordvault.ui.transfer.ImportTags
import io.github.dilshodbekmx.passwordvault.ui.transfer.ImportViewModel
import io.github.dilshodbekmx.passwordvault.ui.transfer.`import`.ImportRig.Companion.CHROME_CSV
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan §1 rule 5: the import's screens walked once in the `en-XA` pseudo-locale (the debug
 * build makes it, i18n.md §1.1, §5), and in `ar-XB` for a right-to-left layout. Every `import_` string
 * and plural comes out pseudo-localized, so none is plain English or marked untranslatable; the
 * screens drawn in the pseudo-locale show the pseudo-localized texts, so none is written into the
 * code; and each screen is kept as a picture in `/data/local/tmp/pv-shots-5.4/pseudo-*.png` for a
 * person to look for cut text.
 */
@RunWith(AndroidJUnit4::class)
class ImportPseudoLocaleTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val rig = ImportRig()

    @After
    fun tearDown() = compose.runOnUiThread { rig.close() }

    private fun pseudo(tag: String): Context {
        val base = compose.activity
        val configuration = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return base.createConfigurationContext(configuration)
    }

    @Composable
    private fun InLocale(context: Context, content: @Composable () -> Unit) {
        val configuration = context.resources.configuration
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides configuration,
            LocalResources provides context.resources,
            LocalLayoutDirection provides if (configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr,
        ) {
            ImportTestTheme { content() }
        }
    }

    @Test
    fun everyImportStringIsPseudoLocalized() {
        val english = compose.activity.resources
        val pseudo = pseudo("en-XA").resources
        val strings = R.string::class.java.fields.filter { it.name.startsWith("import_") }
        assertTrue("the import's strings are found: ${strings.size}", strings.size >= 100)
        for (field in strings) {
            val id = field.getInt(null)
            assertNotEquals("string ${field.name}", english.getString(id), pseudo.getString(id))
        }
        val plurals = R.plurals::class.java.fields.filter { it.name.startsWith("import_") }
        assertTrue("the import's plurals are found: ${plurals.size}", plurals.size >= 30)
        for (field in plurals) {
            val id = field.getInt(null)
            for (count in listOf(1, 2)) {
                assertNotEquals("plural ${field.name} for $count", english.getQuantityString(id, count, count, count, count), pseudo.getQuantityString(id, count, count, count, count))
            }
        }
    }

    @Test
    fun theScreensInEnXa() = screens("en-XA")

    @Test
    fun theScreensInArXb() = screens("ar-XB")

    /** The sources, the Google help, the preview top and end, the result, and the column mapping, in the locale [tag]. */
    private fun screens(tag: String) {
        val context = pseudo(tag)
        val res = context.resources
        rig.recorded(CHROME_CSV.toByteArray())
        lateinit var sources: ImportSourcesViewModel
        lateinit var file: ImportViewModel
        lateinit var columns: ImportColumnsViewModel
        var which by mutableIntStateOf(SOURCES)
        compose.runOnUiThread {
            sources = rig.sources()
            file = rig.file()
            columns = rig.columns()
        }
        compose.setContent {
            InLocale(context) {
                when (which) {
                    SOURCES -> ImportSourcesScreen(sources, rig.nav)
                    FILE -> ImportScreen(file, rig.nav)
                    else -> ColumnMappingScreen(columns, rig.nav)
                }
            }
        }
        compose.revealText(res.getString(R.string.import_source_google)).assertExists()
        compose.revealText(res.getString(R.string.import_sources_intro)).assertExists()
        save("sources-$tag")
        compose.runOnUiThread { sources.choose(io.github.dilshodbekmx.passwordvault.ui.transfer.ImportSource.GOOGLE) }
        compose.revealText(res.getString(R.string.import_google_steps)).assertExists()
        save("help-$tag")

        compose.runOnUiThread { which = FILE }
        compose.waitUntil(TIMEOUT_MS) { file.ui.value.screen is ImportScreen.Preview }
        compose.revealText(res.getQuantityString(R.plurals.import_found, 5, 5)).assertExists()
        compose.revealText(res.getQuantityString(R.plurals.import_button, 5, 5)).assertExists()
        save("preview-$tag")
        compose.onNodeWithTag(ImportTags.PREVIEW).performScrollToKey("tidy")
        compose.revealText(res.getString(R.string.import_tidy_names)).assertExists()
        save("preview-end-$tag")

        compose.revealTag(ImportTags.IMPORT).press()
        compose.waitUntil(TIMEOUT_MS) { file.ui.value.screen is ImportScreen.Done }
        compose.revealText(res.getQuantityString(R.plurals.import_done, 5, 5)).assertExists()
        compose.revealText(res.getString(R.string.import_undo)).assertExists()
        save("done-$tag")

        // The mapping of another file, with no password column.
        rig.recorded("Sayt,Login,Kalit\nhttps://my.gov.uz,aziz,Kalit-1-Pw\n".toByteArray(), uri = "content://com.android.providers.downloads.documents/document/1300")
        compose.runOnUiThread {
            columns = rig.columns()
            which = MAPPING
        }
        compose.waitUntil(TIMEOUT_MS) { columns.ui.value.screen is ColumnsScreen.Shown }
        compose.revealText(res.getString(R.string.import_columns_no_password)).assertExists()
        save("mapping-$tag")
    }

    private fun save(name: String) {
        LayoutHook.look(compose, "import", name)
        ImportShots.take(compose, "pseudo-$name-api${Build.VERSION.SDK_INT}", everyApi = true)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val SOURCES = 0
        const val FILE = 1
        const val MAPPING = 2
    }
}
