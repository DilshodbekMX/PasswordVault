package io.github.dilshodbekmx.passwordvault.ui.common

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [device], on any phone: a shown secret ([SecretText]: an account's password shown, the master
 * password on "Write it down on paper", the generator's password) that is too long for its line
 * wraps between two of its own characters and never shows a character that is not in it. The theme
 * hyphenates every language since build plan 8.1 (`typographyFor`, i18n.md §4.5 rule 4), and a word
 * too long for a line is then broken where the language's hyphenation patterns allow, with a hyphen
 * drawn at the end of the line: a password of letters only in English, or of Cyrillic letters in
 * Russian, would read with a hyphen it does not have, which the owner could copy onto paper.
 */
@RunWith(AndroidJUnit4::class)
class SecretTextLinesTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** What is shown: [secret] in the app's theme in the language [tag], in the style [style] picks from the theme's type scale. */
    private class Shown(val tag: String, val secret: String, val style: (Typography) -> TextStyle)

    private var shown by mutableStateOf<Shown?>(null)

    @Before
    fun theSecretInAColumn160DpWide() {
        compose.setContent {
            val now = shown ?: return@setContent
            key(now) {
                val configuration = Configuration(LocalConfiguration.current).apply { setLocale(Locale.forLanguageTag(now.tag)) }
                CompositionLocalProvider(LocalConfiguration provides configuration) {
                    PasswordVaultTheme(ThemeChoice.LIGHT) {
                        Box(Modifier.width(160.dp)) {
                            val style = now.style(MaterialTheme.typography).copy(localeList = LocaleList(now.tag))
                            SecretText(value = now.secret, style = style, modifier = Modifier.testTag(TAG))
                        }
                    }
                }
            }
        }
    }

    private fun noGlyphAdded(tag: String, secret: String) {
        for ((what, style) in STYLES) {
            compose.runOnUiThread { shown = Shown(tag, secret, style) }
            compose.waitForIdle()
            val laidOut = textLayoutOf(compose.onNodeWithTag(TAG).fetchSemanticsNode())
            assertEquals("$tag, $what: the secret drawn", secret, laidOut.layoutInput.text.text)
            assertTrue("$tag, $what: \"$secret\" takes more than one line here", laidOut.lineCount > 1)
            assertEquals("$tag, $what", emptyList<String>(), linesWithAGlyphNotInTheText(laidOut))
        }
    }

    @Test
    fun aLongSecretOfLettersShowsNoHyphenItDoesNotHave_inEnglish() = noGlyphAdded("en", LATIN_LETTERS)

    @Test
    fun aLongSecretOfLettersShowsNoHyphenItDoesNotHave_inRussian() = noGlyphAdded("ru", CYRILLIC_LETTERS)

    private companion object {
        const val TAG = "secret"

        /** Passwords of letters only, which the language's hyphenation patterns can break. */
        const val LATIN_LETTERS = "correcthorsebatterystaple"
        const val CYRILLIC_LETTERS = "правильнаялошадьбатарейкаскрепка"

        /** The styles the app shows secrets in: an account's rows (the default) and the big preview of "Write it down" and the generator. */
        val STYLES: List<Pair<String, (Typography) -> TextStyle>> = listOf(
            "bodyLarge" to { it.bodyLarge },
            "headlineSmall" to { it.headlineSmall },
        )
    }
}
