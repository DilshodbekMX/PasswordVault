package io.github.dilshodbekmx.passwordvault.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 2.8, [device]: no node of a masked value exposes the secret (ux.md §3, §8). While
 * hidden, the tree is the same for a 16-character password and a 2-character one, so it gives away
 * neither the value nor its length, and no part of the value is anywhere in it; the node has
 * password semantics, the screen's own description and the sensitive flag. When shown, the value is
 * `Text` (never a field, [V-P4]) and still sensitive.
 */
@RunWith(AndroidJUnit4::class)
class MaskedValueNodesTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var value by mutableStateOf(LONG_SECRET)
    private var revealed by mutableStateOf(false)

    @Before
    fun aMaskedValue() {
        compose.setContent {
            PasswordVaultTheme(ThemeChoice.LIGHT) {
                Column {
                    MaskedValue(value = value, revealed = revealed, hiddenDescription = HIDDEN, modifier = Modifier.testTag(TAG))
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun aHiddenValueShowsNothingOfItself() {
        val long = compose.allSemanticsLines()
        compose.runOnUiThread { value = SHORT_SECRET }
        compose.waitForIdle()
        val short = compose.allSemanticsLines()
        assertEquals("the tree of a hidden 16-character and 2-character value", long, short)
        for (secret in listOf(LONG_SECRET, SHORT_SECRET)) {
            for (part in pieces(secret)) {
                assertTrue("\"$part\" of the secret is in the tree: $long", long.none { part in it })
            }
        }
        val config = compose.onNodeWithTag(TAG).fetchSemanticsNode().config
        assertEquals(listOf(HIDDEN), config.getOrNull(SemanticsProperties.ContentDescription))
        assertTrue("password semantics", config.contains(SemanticsProperties.Password))
        assertEquals(true, config.getOrNull(SemanticsProperties.IsSensitiveData))
        assertNull("no text, so no length", config.getOrNull(SemanticsProperties.Text))
    }

    @Test
    fun aShownValueIsSensitiveTextAndNotAField() {
        compose.runOnUiThread { revealed = true }
        compose.waitForIdle()
        val config = compose.onNodeWithTag(TAG).fetchSemanticsNode().config
        assertEquals(listOf(LONG_SECRET), config.getOrNull(SemanticsProperties.Text)?.map { it.text })
        assertEquals(true, config.getOrNull(SemanticsProperties.IsSensitiveData))
        assertNull(config.getOrNull(SemanticsProperties.EditableText))
        assertNull(config.getOrNull(SemanticsActions.SetText))

        compose.runOnUiThread { revealed = false }
        compose.waitForIdle()
        assertFalse(compose.allSemanticsLines().any { LONG_SECRET in it })
    }

    /** Every 4-character piece of [secret], and the whole of a shorter one. */
    private fun pieces(secret: String): List<String> =
        if (secret.length <= 4) listOf(secret) else secret.windowed(4)

    private companion object {
        const val TAG = "masked"
        const val HIDDEN = "Password, hidden"
        const val LONG_SECRET = "Tr0ub4dor&3-xyz!"
        const val SHORT_SECRET = "q7"
    }
}
