package io.github.dilshodbekmx.passwordvault.ui.common

import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Build plan 8.1, [device]; ux.md §8, §12 check 9 ("at 320 dp and 200 % every control keeps its
 * name while scrolled"): a [VaultSecureField] keeps its name for TalkBack and Switch Access when it
 * is scrolled to a sliver at the top of its screen, where its label is out of view. Compose leaves a
 * node that is out of view out of the accessibility tree, so a field named only by its label, a
 * child of the field's node, read with no name there (the master-password fields of the backup,
 * Change master password and the lock screen in 8.1's accessibility walk at 320 dp and 200 %).
 *
 * The tree is the one an accessibility service gets (`UiAutomation`), read as the walk and TalkBack
 * read a field: its own description, text or hint, else the texts of the parts it is made of that
 * are not controls. Also: the name is read once where the label is in view, and no part of the tree
 * ever holds the secret typed into the field while it is masked (lock.md §13.5).
 */
@RunWith(AndroidJUnit4::class)
class SecureFieldNameTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val label get() = compose.activity.getString(R.string.lock_mp_field)

    @Test
    fun aFieldScrolledToASliverAtTheTopKeepsItsName() {
        val state = TextFieldState()
        val scroll = show(state)
        val wrong = mutableListOf<String>()
        for (typed in listOf("", SECRET)) {
            compose.runOnIdle { state.edit { replace(0, length, typed) } }
            for (sliver in listOf(8, 16)) {
                scrollToSliver(scroll, sliver)
                val field = fieldNode { abs(it.boundsHeight() - px(sliver)) <= 3 && (it.text?.length ?: 0) == typed.length }
                val case = (if (typed.isEmpty()) "empty" else "a password typed") + ", $sliver dp showing (${field.boundsHeight()} px)"
                assertTrue("$case: the field shows only a sliver", abs(field.boundsHeight() - px(sliver)) <= 3)
                val name = nameOf(field)
                if (name != label) wrong += "$case: \"$name\""
                assertNoSecret(case)
                scrollToSliver(scroll, null)
            }
        }
        assertEquals("the field's name where only a sliver of it shows", emptyList<String>(), wrong)
    }

    @Test
    fun inViewTheNameIsReadOnceAndNeverHoldsTheSecret() {
        val state = TextFieldState()
        show(state)
        assertEquals("the field's name in view", listOf(label), readAloud(fieldNode { true }))
        compose.runOnIdle { state.edit { append(SECRET) } }
        compose.waitForIdle()
        val masked = fieldNode { it.text?.length == SECRET.length }
        assertEquals("the field's name with the secret typed", listOf(label), readAloud(masked).filter { it != masked.text?.toString() })
        assertNoSecret("masked, in view")
    }

    /**
     * In view the field still tells a screen reader where its cursor is, so that it can move the cursor
     * by character and edit at it (tools/emu/window/a11y.sh's lock-screen step): Compose reports a
     * node's own description instead of its text and cursor once it has one, so the field's own name
     * must not stand while its label names it.
     */
    @Test
    fun inViewTheFieldReportsItsCursorToAScreenReader() {
        val state = TextFieldState()
        show(state)
        compose.runOnIdle { state.edit { append(SECRET) } }
        compose.waitForIdle()
        val masked = fieldNode { it.text?.length == SECRET.length && it.textSelectionStart == SECRET.length }
        assertEquals("the cursor's start", SECRET.length, masked.textSelectionStart)
        assertEquals("the cursor's end", SECRET.length, masked.textSelectionEnd)
    }

    /** Draws the field under 400 dp of space and over 2,000 dp more, 320 dp wide, at a font of 200 %. */
    private fun show(state: TextFieldState): ScrollState {
        lateinit var scroll: ScrollState
        compose.setContent {
            scroll = rememberScrollState()
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                PasswordVaultTheme(ThemeChoice.LIGHT) {
                    Box(Modifier.width(320.dp).fillMaxSize().semantics { testTagsAsResourceId = true }) {
                        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                            Spacer(Modifier.height(400.dp))
                            VaultSecureField(
                                state = state,
                                modifier = Modifier.fillMaxWidth().testTag(FIELD).onGloballyPositioned {
                                    fieldTop = it.positionInParent().y
                                    fieldHeight = it.size.height
                                },
                                label = label,
                            )
                            Spacer(Modifier.height(2_000.dp))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        return scroll
    }

    private var fieldTop = 0f
    private var fieldHeight = 0

    private fun px(dp: Int): Int = (dp * compose.activity.resources.displayMetrics.density).toInt()

    /** Scrolls until only the lowest [sliverDp] of the field shows, at the top of the screen; null scrolls back to the top. */
    private fun scrollToSliver(scroll: ScrollState, sliverDp: Int?) {
        compose.runOnIdle {
            val target = if (sliverDp == null) 0f else fieldTop + fieldHeight - px(sliverDp)
            scroll.dispatchRawDelta(target - scroll.value)
        }
        compose.waitForIdle()
    }

    /** The field's node as a service gets it, once [ready] holds for it (the tree follows the screen a moment later). */
    private fun fieldNode(ready: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        var last: AccessibilityNodeInfo? = null
        while (true) {
            last = nodes().firstOrNull { it.viewIdResourceName == FIELD } ?: last
            if (last != null && ready(last)) return last
            if (SystemClock.uptimeMillis() > deadline) return last ?: throw AssertionError("no node $FIELD in the accessibility tree")
            SystemClock.sleep(200)
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            out += node
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
        return out
    }

    private fun actionable(node: AccessibilityNodeInfo): Boolean = node.isClickable || node.isLongClickable || node.isEditable || node.isCheckable

    private fun own(node: AccessibilityNodeInfo): List<String> =
        listOfNotNull(node.contentDescription, node.text, if (Build.VERSION.SDK_INT >= 26) node.hintText else null)
            .map { it.toString().trim() }.filter { it.isNotEmpty() }

    /**
     * What the accessibility walk takes as the name of [node] (`A11yWalker.spoken`): its own description,
     * text or hint, else those of the parts it is made of that are not controls.
     */
    private fun spoken(node: AccessibilityNodeInfo): String {
        val mine = own(node).distinct()
        if (mine.isNotEmpty()) return mine.joinToString(" ")
        return parts(node, visibleOnly = false).distinct().joinToString(" ")
    }

    /** The name without the field's own text (the mask characters of what is typed): the parts that are not controls. */
    private fun nameOf(node: AccessibilityNodeInfo): String =
        (listOfNotNull(node.contentDescription).map { it.toString() } + parts(node, visibleOnly = false)).distinct().joinToString(" ")

    /** Everything a screen reader says for [node], in order, each time it says it: its own words, then its parts shown to the user. */
    private fun readAloud(node: AccessibilityNodeInfo): List<String> = own(node) + parts(node, visibleOnly = true)

    private fun parts(node: AccessibilityNodeInfo, visibleOnly: Boolean): List<String> {
        val out = mutableListOf<String>()
        fun gather(n: AccessibilityNodeInfo) {
            for (i in 0 until n.childCount) {
                val child = n.getChild(i) ?: continue
                if (actionable(child)) continue
                if (visibleOnly && !child.isVisibleToUser) continue
                listOfNotNull(child.contentDescription, child.text).map { it.toString().trim() }.filter { it.isNotEmpty() }.forEach(out::add)
                gather(child)
            }
        }
        gather(node)
        return out
    }

    /** No node of the window holds the secret, or any four of its characters in a row, in a text, description, hint or error. */
    private fun assertNoSecret(where: String) {
        val pieces = SECRET.windowed(4)
        for (node in nodes()) {
            val texts = listOfNotNull(node.text, node.contentDescription, node.error, if (Build.VERSION.SDK_INT >= 26) node.hintText else null).map { it.toString() }
            for (text in texts) {
                assertTrue("$where: a node reads \"$text\"", pieces.none { it in text })
            }
        }
    }

    private fun AccessibilityNodeInfo.boundsHeight(): Int = Rect().also { getBoundsInScreen(it) }.height()

    private companion object {
        const val FIELD = "pv.test.secure"
        const val SECRET = "Toshkent2020!"
        const val TIMEOUT_MS = 10_000L
    }
}
