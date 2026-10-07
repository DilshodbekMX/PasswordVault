package io.github.dilshodbekmx.passwordvault.ui.common

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan §1 rule 5: the kit walked once in the `en-XA` pseudo-locale (the debug build makes
 * it, i18n.md §1.1). The kit has no screen of its own, so the walk is: every `common_` string and
 * plural comes out pseudo-localized, so none is plain English or marked untranslatable; and the
 * parts drawn in `en-XA` show the pseudo-localized texts, so none is written into the code.
 */
@RunWith(AndroidJUnit4::class)
class CommonStringsPseudoLocaleTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun pseudo(base: Context): Context {
        val configuration = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag("en-XA")) }
        return base.createConfigurationContext(configuration)
    }

    @Test
    fun everyCommonStringIsPseudoLocalized() {
        val english = compose.activity
        val pseudo = pseudo(english)
        val strings = R.string::class.java.fields.filter { it.name.startsWith("common_") }
        assertTrue("the common strings are found", strings.size >= 10)
        for (field in strings) {
            val id = field.getInt(null)
            assertNotEquals("common string ${field.name}", english.getString(id), pseudo.getString(id))
        }
        assertNotEquals(
            english.resources.getQuantityString(R.plurals.common_pin_dots, 3, 3, PIN_LENGTH),
            pseudo.resources.getQuantityString(R.plurals.common_pin_dots, 3, 3, PIN_LENGTH),
        )
    }

    @Test
    fun theKitDrawsItsTextsFromResources() {
        val pseudo = pseudo(compose.activity)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides pseudo, LocalConfiguration provides pseudo.resources.configuration) {
                PasswordVaultTheme(ThemeChoice.LIGHT) {
                    Column {
                        PinDots(entered = 2)
                        StrengthBar(score = 2, modifier = Modifier.testTag(STRENGTH))
                        VaultSecureField(state = remember { TextFieldState() })
                    }
                }
            }
        }
        val resources = pseudo.resources
        compose.onNodeWithTag(PinPadTags.DOTS).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].let {
            assertEquals(listOf(resources.getQuantityString(R.plurals.common_pin_dots, 2, 2, PIN_LENGTH)), it)
        }
        val weak = resources.getString(R.string.common_strength_weak)
        assertEquals(
            listOf(resources.getString(R.string.common_strength_description, weak)),
            compose.onNodeWithTag(STRENGTH).fetchSemanticsNode().config[SemanticsProperties.ContentDescription],
        )
        compose.onNodeWithContentDescription(resources.getString(R.string.common_show_password)).fetchSemanticsNode()
    }

    private companion object {
        const val STRENGTH = "strength"
    }
}
