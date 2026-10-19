package io.github.dilshodbekmx.passwordvault.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [device], on any phone: a value typed into one of the app's text fields that wraps ([VaultTextField]
 * with more than one line: an extra field's value, the notes) wraps between two of its own characters,
 * with no hyphen drawn that is not in it, as Android's own editable text does (its hyphenation is off
 * in an `EditText`). The theme hyphenates every language since build plan 8.1.
 */
@RunWith(AndroidJUnit4::class)
class TextFieldLinesTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aLongValueTypedInAFieldShowsNoHyphenItDoesNotHave() {
        val state = TextFieldState(VALUE)
        compose.setContent {
            PasswordVaultTheme(ThemeChoice.LIGHT) {
                Box(Modifier.width(200.dp)) {
                    VaultTextField(state = state, modifier = Modifier.testTag(TAG), lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 6))
                }
            }
        }
        compose.waitForIdle()
        val laidOut = textLayoutOf(compose.onNodeWithTag(TAG).fetchSemanticsNode())
        assertEquals(VALUE, laidOut.layoutInput.text.text)
        assertTrue("the value takes more than one line", laidOut.lineCount > 1)
        assertEquals(emptyList<String>(), linesWithAGlyphNotInTheText(laidOut))
    }

    private companion object {
        const val TAG = "field"

        /** A value of letters only, which English hyphenation patterns can break. */
        const val VALUE = "aleksandrpetrovichivanovich"
    }
}
