package io.github.dilshodbekmx.passwordvault.ui.lock

import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PadStatus
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.lock.LockIntent
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.ui.common.PIN_LENGTH
import io.github.dilshodbekmx.passwordvault.ui.common.PinPadTags
import io.github.dilshodbekmx.passwordvault.ui.common.allSemanticsLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 4.2, [device]: the PIN pad screen as drawn, over a lock that holds the digits
 * ([PadLock]) as the real one does (ux.md §5.9; lock.md §13.2, §13.5; E12's assertions):
 *
 * - **No typed digit is ever in a node.** The whole semantics tree, as composed and as TalkBack walks
 *   it, and the platform's accessibility tree that a service gets, are the same after two different
 *   PINs of the same length; the only digits in them are the keys' own labels.
 * - **The dots read "PIN, 3 of 6 digits entered"**, in Compose's tree and in the platform's.
 * - **Every key is reachable and labelled**: in the platform's tree, as an accessibility service
 *   (TalkBack, Switch Access) gets it, each of the ten digits, ⌫ and the fingerprint key is a
 *   visible, enabled, clickable node with its label, and its click action, performed through that
 *   tree, reaches the lock; ⌫'s long press clears; "Forgot PIN? Use master password" too. From API 34
 *   the keys and the dots are marked sensitive to accessibility, so that only accessibility tools
 *   see them (lock.md §13.5).
 * - A hardware keyboard's digits and Backspace work; "Opening…" replaces the keypad; the status line
 *   and the test build's line are drawn; Back on a vault put aside goes back to the current vault.
 */
@RunWith(AndroidJUnit4::class)
class PadScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val resources get() = compose.activity.resources

    private fun show(screen: LockScreen.Pad = pad(), testBuild: Boolean = true): PadLock {
        val lock = PadLock(FakeLockManager(initial = LockUiState.Locked(screen)))
        compose.setContent { LockRouteHost(lock, testBuild = testBuild) }
        compose.waitForIdle()
        return lock
    }

    @Test
    fun noNodeEverHoldsATypedDigit() {
        val lock = show()
        enter(2, 5, 9)
        val composed = compose.allSemanticsLines()
        val platform = platformLines(expectDigits = 3)
        clearByLongPress()
        enter(7, 1, 4)
        assertEquals("the semantics trees after 2 5 9 and after 7 1 4", composed, compose.allSemanticsLines())
        assertEquals("the accessibility trees after 2 5 9 and after 7 1 4", platform, platformLines(expectDigits = 3))

        clearByLongPress()
        enter(0, 8, 1, 3, 6, 2)
        val full = compose.allSemanticsLines()
        clearByLongPress()
        enter(4, 4, 0, 2, 7, 1)
        assertEquals("the trees after two full PINs", full, compose.allSemanticsLines())

        // The keys reached the lock, digit by digit; nothing of them stayed on the screen.
        val digits = lock.fake.sent.filterIsInstance<LockIntent.Digit>().map { it.digit }
        assertEquals(listOf(2, 5, 9, 7, 1, 4, 0, 8, 1, 3, 6, 2, 4, 4, 0, 2, 7, 1), digits)
        val texts = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("any") { true }, useUnmergedTree = true)
            .fetchSemanticsNodes().flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        val digitTexts = texts.filter { text -> text.any(Char::isDigit) }
        assertEquals("the only digits on the screen are the keys' labels", (0..9).map { it.toString() }.sorted(), digitTexts.sorted())
    }

    @Test
    fun theDotsSayHowManyDigitsAreEntered() {
        show()
        enter(2, 5, 9)
        val dots = resources.getQuantityString(R.plurals.common_pin_dots, 3, 3, PIN_LENGTH)
        assertEquals("PIN, 3 of 6 digits entered", dots)
        assertEquals(listOf(dots), compose.onNodeWithTag(PinPadTags.DOTS).fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        val node = platformNodes(expectDigits = 3).singleOrNull { it.contentDescription?.toString() == dots }
        assertNotNull("an accessibility service reads \"$dots\"", node)
        assertTrue(node!!.isVisibleToUser)
        if (Build.VERSION.SDK_INT >= 34) assertTrue("the dots are sensitive to accessibility", node.isAccessibilityDataSensitive)
    }

    @Test
    fun everyKeyIsReachableAndLabelled() {
        val lock = show(pad(fingerprintKey = true))
        val labels = (0..9).map { it.toString() to LockIntent.Digit(it) } +
            listOf(
                resources.getString(R.string.common_pin_delete) to LockIntent.DeleteDigit,
                resources.getString(R.string.common_pin_fingerprint) to LockIntent.UseFingerprint,
                resources.getString(R.string.lock_pad_forgot) to LockIntent.UseMasterPassword,
            )
        for ((label, intent) in labels) {
            val node = platformNode(label)
            assertTrue("$label is shown", node.isVisibleToUser)
            assertTrue("$label is enabled", node.isEnabled)
            assertTrue("$label is clickable", node.isClickable)
            assertTrue("$label has a click action", node.actionList.contains(AccessibilityAction.ACTION_CLICK))
            if (Build.VERSION.SDK_INT >= 34 && intent !is LockIntent.UseMasterPassword) {
                assertTrue("$label is sensitive to accessibility", node.isAccessibilityDataSensitive)
            }
            val before = lock.fake.sent.size
            assertTrue("$label: the click action is performed", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitFor("$label reaches the lock") { lock.fake.sent.size > before }
            assertEquals(label, intent, lock.fake.sent.last())
        }
        // ⌫'s long press, through the same tree, clears every digit.
        val delete = platformNode(resources.getString(R.string.common_pin_delete))
        assertTrue(delete.actionList.contains(AccessibilityAction.ACTION_LONG_CLICK))
        assertTrue(delete.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
        waitFor("the long press reaches the lock") { lock.fake.sent.last() == LockIntent.ClearDigits }
    }

    @Test
    fun aHardwareKeyboardTypesDigitsAndBackspace() {
        val lock = show()
        compose.onRoot().performKeyInput {
            pressKey(Key.Five)
            pressKey(Key.NumPad8)
            pressKey(Key.A)
            pressKey(Key.Backspace)
            pressKey(Key.Zero)
        }
        compose.waitForIdle()
        assertEquals(listOf(LockIntent.Digit(5), LockIntent.Digit(8), LockIntent.DeleteDigit, LockIntent.Digit(0)), lock.fake.sent)
        assertEquals(listOf(resources.getQuantityString(R.plurals.common_pin_dots, 2, 2, PIN_LENGTH)), compose.onNodeWithTag(PinPadTags.DOTS).fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
    }

    @Test
    fun theTextsTheStatusLineAndTheTestBuildLineAreDrawn() {
        show(pad(digits = 2, status = PadStatus.WrongPin(4), fingerprintKey = true))
        compose.onNodeWithTag(LockTags.TITLE).assertTextEquals(resources.getString(R.string.app_name))
        compose.onNodeWithTag(LockTags.SUBTITLE).assertTextEquals(resources.getString(R.string.lock_pad_subtitle))
        compose.onNodeWithTag(LockTags.STATUS).assertTextEquals(resources.getQuantityString(R.plurals.lock_pad_wrong_pin, 4, 4))
        compose.onNodeWithTag(LockTags.TEST_BUILD).assertTextEquals(resources.getString(R.string.lock_test_build))
        compose.onNodeWithTag(LockTags.FORGOT_PIN).assertTextEquals(resources.getString(R.string.lock_pad_forgot))
        compose.onNodeWithTag(PinPadTags.FINGERPRINT).assertExists()
        LockShots.take("pad")
    }

    @Test
    fun withoutFingerprintOrOutsideATestBuildTheirPartsAreNotDrawn() {
        show(pad(), testBuild = false)
        compose.onNodeWithTag(PinPadTags.FINGERPRINT).assertDoesNotExist()
        compose.onNodeWithTag(LockTags.TEST_BUILD).assertDoesNotExist()
        compose.onNodeWithTag(LockTags.STATUS).assertDoesNotExist()
    }

    @Test
    fun openingReplacesTheKeypad() {
        val lock = show(pad(digits = 6, opening = true))
        compose.onNodeWithTag(LockTags.OPENING).assertExists()
        for (n in 0..9) compose.onNodeWithTag(PinPadTags.key(n)).assertDoesNotExist()
        compose.onNodeWithTag(PinPadTags.DOTS).assertExists()
        // Back is ignored while the PIN is checked (ux.md §4).
        Espresso.pressBackUnconditionally()
        compose.waitForIdle()
        assertFalse(compose.activity.isFinishing)
        assertTrue(lock.fake.sent.isEmpty())
    }

    @Test
    fun backOnTheScreenOfAVaultPutAsideGoesBackToTheCurrentVault() {
        val lock = show(pad(aside = AsideLine.LockedOn(1_791_244_800_000L)))
        compose.onNodeWithTag(LockTags.ASIDE).assertExists()
        Espresso.pressBack()
        compose.waitForIdle()
        assertEquals(listOf<LockIntent>(LockIntent.GoBack), lock.fake.sent)
        assertFalse("the app was not left", compose.activity.isFinishing)
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun pad(digits: Int = 0, status: PadStatus? = null, fingerprintKey: Boolean = true, opening: Boolean = false, aside: AsideLine? = null) =
        LockScreen.Pad(digits, status, fingerprintKey, opening, aside)

    private fun enter(vararg keys: Int) {
        for (key in keys) compose.onNodeWithTag(PinPadTags.key(key)).performClick()
        compose.waitForIdle()
    }

    private fun clearByLongPress() {
        compose.onNodeWithTag(PinPadTags.DELETE).performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(
            listOf(resources.getQuantityString(R.plurals.common_pin_dots, 0, 0, PIN_LENGTH)),
            compose.onNodeWithTag(PinPadTags.DOTS).fetchSemanticsNode().config[SemanticsProperties.ContentDescription],
        )
    }

    /**
     * The nodes of the app's window as an accessibility service gets them, read again until the dots
     * say [expectDigits] (the tree follows the screen a moment later).
     */
    private fun platformNodes(expectDigits: Int? = null): List<AccessibilityNodeInfo> {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val dots = expectDigits?.let { resources.getQuantityString(R.plurals.common_pin_dots, it, it, PIN_LENGTH) }
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (true) {
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            fun walk(node: AccessibilityNodeInfo?) {
                if (node == null) return
                nodes += node
                for (i in 0 until node.childCount) walk(node.getChild(i))
            }
            walk(automation.rootInActiveWindow)
            val ready = nodes.isNotEmpty() && (dots == null || nodes.any { it.contentDescription?.toString() == dots })
            if (ready || SystemClock.uptimeMillis() > deadline) return nodes
            SystemClock.sleep(200)
        }
    }

    /** The node whose text or description is [label], as a service finds it. */
    private fun platformNode(label: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (true) {
            val node = platformNodes().firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }
            if (node != null) return node
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("no node labelled \"$label\" in the accessibility tree")
            SystemClock.sleep(200)
        }
    }

    /** What a service learns from each node, in tree order: its class, texts, state and actions; never its bounds or focus. */
    private fun platformLines(expectDigits: Int): List<String> = platformNodes(expectDigits).map { node ->
        val state = if (Build.VERSION.SDK_INT >= 30) node.stateDescription else null
        listOf(
            node.className,
            node.text,
            node.contentDescription,
            state,
            node.viewIdResourceName,
            node.isClickable,
            node.isLongClickable,
            node.isEnabled,
            node.isPassword,
            node.isEditable,
            node.childCount,
        ).joinToString("|")
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (!condition()) {
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("timed out: $what")
            SystemClock.sleep(50)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
