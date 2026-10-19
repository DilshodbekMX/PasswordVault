package io.github.dilshodbekmx.passwordvault.ui.edit

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.generator.GeneratorOptions
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.layout.LayoutCheck
import io.github.dilshodbekmx.passwordvault.ui.layout.LayoutProbe
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 8.1, [device], on any phone: the generator sheet (ux.md §5.15) on a phone 320 dp wide at a
 * font of 200 %, in the app's languages. The separator's choices, a list of radio rows there, where the
 * segmented row does not fit,
 * show the symbol and its name in the order of the language's string `edit_gen_separator_choice`, one
 * string a translator can reorder (ux.md §9: no concatenation; the open problem of section 12, R5);
 * the sheet then cuts no word and no label.
 */
@RunWith(AndroidJUnit4::class)
class GeneratorSheetTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * The separator's choices as the sheet draws them, 320 dp wide (the sheet's 24 dp sides off a phone
     * 320 dp wide) at a font scale of 2, in the language [tag]. Drawn without the sheet: a bottom sheet is
     * a window of its own, which takes the phone's own density, font scale and language.
     */
    private fun show(tag: String) {
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply { setLocale(Locale.forLanguageTag(tag)) }
            val context = LocalContext.current.createConfigurationContext(configuration)
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalContext provides context,
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                PasswordVaultTheme(ThemeChoice.LIGHT) {
                    Box(Modifier.width(272.dp)) { SeparatorChoices(GeneratorOptions.Separator.HYPHEN, onChoose = {}) }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun text(tag: String, id: Int, vararg args: Any): String {
        val configuration = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return compose.activity.createConfigurationContext(configuration).getString(id, *args)
    }

    /** What the row of the separator [separator] shows. */
    private fun shown(separator: GeneratorOptions.Separator): String =
        compose.onNodeWithTag(GenTags.SEPARATOR + separator.name, useUnmergedTree = true).fetchSemanticsNode().children
            .flatMap { child -> child.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
            .joinToString("")

    @Test
    fun theSeparatorsShowTheLanguagesStringForASymbolAndItsName_inEnglish() = separators("en")

    @Test
    fun theSeparatorsShowTheLanguagesStringForASymbolAndItsName_inUzbekLatin() = separators("uz")

    @Test
    fun theSeparatorsShowTheLanguagesStringForASymbolAndItsName_inUzbekCyrillic() = separators("uz-Cyrl")

    @Test
    fun theSeparatorsShowTheLanguagesStringForASymbolAndItsName_inRussian() = separators("ru")

    private fun separators(tag: String) {
        show(tag)
        for ((separator, symbol, name) in listOf(
            Triple(GeneratorOptions.Separator.HYPHEN, R.string.edit_gen_symbol_hyphen, R.string.edit_gen_separator_hyphen),
            Triple(GeneratorOptions.Separator.DOT, R.string.edit_gen_symbol_dot, R.string.edit_gen_separator_dot),
            Triple(GeneratorOptions.Separator.UNDERSCORE, R.string.edit_gen_symbol_underscore, R.string.edit_gen_separator_underscore),
        )) {
            val expected = text(tag, R.string.edit_gen_separator_choice, text(tag, symbol), text(tag, name))
            assertEquals("$tag $separator", expected, shown(separator))
            assertEquals("$tag $separator: TalkBack says the name", listOf(text(tag, name)), description(separator))
        }
        assertEquals("$tag: the name alone where it is its own symbol", text(tag, R.string.edit_gen_separator_space), shown(GeneratorOptions.Separator.SPACE))
        assertClean(tag)
    }

    /** The description of the separator [separator]'s row, which TalkBack says in place of what it shows. */
    private fun description(separator: GeneratorOptions.Separator): List<String> =
        compose.onNodeWithTag(GenTags.SEPARATOR + separator.name).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    private fun assertClean(what: String) {
        val found = LayoutProbe(compose, compose.activity).apply { checkEnglish = false }.look()
        val problems = LayoutCheck.entries.flatMap { check -> found.of(check).map { "${check.title}: $it" } }
        assertTrue("$what: $problems", problems.isEmpty())
    }

}
