package io.github.dilshodbekmx.passwordvault.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 2.8, [device]: no node of the pad exposes a digit (lock.md §13.5, ux.md §8). The test
 * holds the digits as the lock's ViewModel does and gives the pad only their number. Proof: the
 * whole semantics tree, both as composed and as TalkBack walks it, is the same after two different
 * PINs of the same length, so nothing in it depends on which keys were pressed; the dots say
 * "PIN, 3 of 6 digits entered"; the only texts are the keys' own labels; no node is a text field;
 * every key and the dots carry the sensitive flag (ux.md §3).
 */
@RunWith(AndroidJUnit4::class)
class PinPadNodesTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** The digits, held here as a ViewModel holds them. */
    private val digits = mutableStateListOf<Int>()
    private val fingerprintTaps = AtomicInteger()

    @Before
    fun aPad() {
        compose.setContent {
            PasswordVaultTheme(ThemeChoice.LIGHT) {
                PinPad(
                    entered = digits.size,
                    onDigit = { if (digits.size < PIN_LENGTH) digits += it },
                    onDelete = { if (digits.isNotEmpty()) digits.removeAt(digits.lastIndex) },
                    onFingerprint = { fingerprintTaps.incrementAndGet() },
                    onClear = { digits.clear() },
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun theTreeIsTheSameWhicheverDigitsWereEntered() {
        enter(2, 5, 9)
        val first = compose.allSemanticsLines()
        clearByLongPress()
        enter(7, 1, 4)
        val second = compose.allSemanticsLines()
        assertEquals("the tree after 2 5 9 and after 7 1 4", first, second)

        enter(0, 8, 1)
        val firstFull = compose.allSemanticsLines()
        clearByLongPress()
        enter(4, 4, 0, 2, 7, 1)
        assertEquals("the tree after two full PINs", firstFull, compose.allSemanticsLines())
        assertEquals(listOf(4, 4, 0, 2, 7, 1), digits.toList())
    }

    @Test
    fun theDotsSayHowManyAndTheOnlyTextsAreTheKeys() {
        enter(2, 5, 9)
        val resources = compose.activity.resources
        val dots = resources.getQuantityString(R.plurals.common_pin_dots, 3, 3, PIN_LENGTH)
        assertEquals("PIN, 3 of 6 digits entered", dots)
        assertEquals(listOf(dots), compose.onNodeWithTag(PinPadTags.DOTS).fetchSemanticsNode().config[SemanticsProperties.ContentDescription])

        val nodes = compose.onAllNodes(SemanticsMatcher("any") { true }).fetchSemanticsNodes()
        val texts = nodes.flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        val descriptions = nodes.flatMap { node -> node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        assertEquals("the texts are the keys' labels", (0..9).map { it.toString() }.sorted(), texts.sorted())
        assertEquals(
            listOf(dots, resources.getString(R.string.common_pin_delete), resources.getString(R.string.common_pin_fingerprint)).sorted(),
            descriptions.sorted(),
        )
    }

    @Test
    fun keysAndDotsAreSensitiveAndNoneIsATextField() {
        val tags = (0..9).map { PinPadTags.key(it) } + listOf(PinPadTags.DELETE, PinPadTags.FINGERPRINT, PinPadTags.DOTS)
        for (tag in tags) {
            val config = compose.onNodeWithTag(tag).fetchSemanticsNode().config
            assertEquals("$tag is sensitive", true, config.getOrNull(SemanticsProperties.IsSensitiveData))
        }
        val all = compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(all.isNotEmpty())
        for (node in all) {
            assertTrue("no editable text", node.config.getOrNull(SemanticsProperties.EditableText) == null)
            assertTrue("no text input", node.config.getOrNull(SemanticsActions.SetText) == null)
        }
    }

    @Test
    fun theKeysReportPresses() {
        enter(1, 2, 3)
        compose.onNodeWithTag(PinPadTags.DELETE).performClick()
        compose.onNodeWithTag(PinPadTags.FINGERPRINT).performClick()
        compose.waitForIdle()
        assertEquals(listOf(1, 2), digits.toList())
        assertEquals(1, fingerprintTaps.get())
    }

    @Test
    fun aHardwareKeyboardTypesDigitsAndBackspace() {
        compose.onRoot().performKeyInput {
            pressKey(Key.Five)
            pressKey(Key.NumPad8)
            pressKey(Key.A)
            pressKey(Key.Backspace)
            pressKey(Key.Zero)
        }
        compose.waitForIdle()
        assertEquals(listOf(5, 0), digits.toList())
    }

    private fun enter(vararg keys: Int) {
        for (key in keys) compose.onNodeWithTag(PinPadTags.key(key)).performClick()
        compose.waitForIdle()
    }

    private fun clearByLongPress() {
        compose.onNodeWithTag(PinPadTags.DELETE).performTouchInput { longClick() }
        compose.waitForIdle()
        assertTrue("a long press on delete clears", digits.isEmpty())
    }
}
