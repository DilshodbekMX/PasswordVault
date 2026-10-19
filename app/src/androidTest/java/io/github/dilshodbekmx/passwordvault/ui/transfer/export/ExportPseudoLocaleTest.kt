package io.github.dilshodbekmx.passwordvault.ui.transfer.export

import android.content.Context
import android.content.res.Configuration
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.layout.LayoutHook
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportActions
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportFrame
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportUiState
import java.util.Locale
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan §1 rule 5: the export's steps walked once in the `en-XA` pseudo-locale (the debug
 * build makes it, i18n.md §1.1, §5), and in `ar-XB` for a right-to-left layout. Every `export_`
 * string comes out pseudo-localized, so none is plain English or marked untranslatable; no step
 * shows an English `export_` text, so none is written into the code; and each step is kept as a
 * picture on the device (`/data/local/tmp/pv-shots-5.3-pseudo/<tag>/`) for a person to look for
 * clipped text.
 */
@RunWith(AndroidJUnit4::class)
class ExportPseudoLocaleTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

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
            // The direction comes from the window, not from these resources: ar-XB is laid out right to left.
            LocalLayoutDirection provides if (configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr,
        ) {
            PasswordVaultTheme(ThemeChoice.LIGHT, content)
        }
    }

    @Test
    fun everyExportStringIsPseudoLocalized() {
        val english = compose.activity
        val pseudo = pseudo("en-XA")
        val strings = R.string::class.java.fields.filter { it.name.startsWith("export_") }
        assertTrue("the export's strings are found: ${strings.size}", strings.size >= 70)
        for (field in strings) {
            val id = field.getInt(null)
            assertNotEquals("string ${field.name}", english.getString(id), pseudo.getString(id))
        }
        val plurals = R.plurals::class.java.fields.filter { it.name.startsWith("export_") }
        assertTrue("the export's plurals are found: ${plurals.size}", plurals.size >= 10)
        for (field in plurals) {
            val id = field.getInt(null)
            for (n in listOf(1, 2, 5)) {
                assertNotEquals("plural ${field.name} ($n)", english.resources.getQuantityString(id, n, n), pseudo.resources.getQuantityString(id, n, n))
            }
        }
    }

    @Test
    fun everyStepInEnXa() = walk("en-XA")

    @Test
    fun everyStepInArXb() = walk("ar-XB")

    /** Draws every step of [ExportStates] in the pseudo-locale [tag] and keeps a picture of each. */
    private fun walk(tag: String) {
        val context = pseudo(tag)
        val english = englishTexts()
        val password = TextFieldState("Toshkent-2026")
        var shown by mutableStateOf<ExportUiState>(ExportStates.all.first().second)
        compose.setContent {
            InLocale(context) {
                ExportFrame(shown, password, ExportActions(), onBack = {})
            }
        }
        val shots = ExportShots.pseudo(tag)
        shots.start()
        val title = context.getString(R.string.export_title)
        for ((name, state) in ExportStates.all) {
            compose.runOnUiThread { shown = state }
            compose.waitForIdle()
            compose.onNodeWithText(title).assertExists()
            val texts = compose.everyText()
            val plain = texts.filter { it in english }
            assertTrue("$tag, step $name shows English texts: $plain", plain.isEmpty())
            LayoutHook.look(compose, "export", "$name-$tag")
            shots.take(compose, name)
        }
    }

    /** The English texts of the `export_` strings that have no placeholder. */
    private fun englishTexts(): Set<String> = R.string::class.java.fields
        .filter { it.name.startsWith("export_") }
        .map { compose.activity.getString(it.getInt(null)) }
        .filter { '%' !in it }
        .toSet()
}
