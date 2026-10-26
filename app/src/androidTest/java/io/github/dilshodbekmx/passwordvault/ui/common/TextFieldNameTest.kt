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
 * name while scrolled"): a [VaultTextField] (the account form's name, user name, website, notes and
 * fields) keeps its name for TalkBack and Switch Access when it is scrolled to a sliver at the top of
 * its screen, its label above the view, as [VaultSecureField] does (`SecureFieldNameTest`): Compose
 * gives a part that is out of view no text, so a field named only by its drawn label read with no
 * name there. In view the label names it once, and the field still tells a screen reader its text and
 * where its cursor is. Read from the tree an accessibility service gets (`UiAutomation`), as the
 * accessibility walk and TalkBack read a field.
 */
@RunWith(AndroidJUnit4::class)
class TextFieldNameTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val label get() = compose.activity.getString(R.string.edit_username)

    @Test
    fun aFieldScrolledToASliverAtTheTopKeepsItsName() {
        val state = TextFieldState()
        val scroll = show(state)
        val wrong = mutableListOf<String>()
        for (typed in listOf("", VALUE)) {
            compose.runOnIdle { state.edit { replace(0, length, typed) } }
            for (sliver in listOf(8, 16)) {
                scrollToSliver(scroll, sliver)
                val field = fieldNode { abs(it.boundsHeight() - px(sliver)) <= 3 && (it.text?.toString() ?: "") == typed }
                val case = (if (typed.isEmpty()) "empty" else "a value typed") + ", $sliver dp showing (${field.boundsHeight()} px)"
                assertTrue("$case: the field shows only a sliver", abs(field.boundsHeight() - px(sliver)) <= 3)
                val name = nameOf(field)
                if (name != label) wrong += "$case: \"$name\""
                scrollToSliver(scroll, null)
            }
        }
        assertEquals("the field's name where only a sliver of it shows", emptyList<String>(), wrong)
    }

    @Test
    fun inViewTheLabelNamesTheFieldOnce_andTheFieldReportsItsTextAndCursor() {
        val state = TextFieldState()
        show(state)
        assertEquals("the field's name in view", listOf(label), readAloud(fieldNode { true }))
        compose.runOnIdle { state.edit { append(VALUE) } }
        compose.waitForIdle()
        val field = fieldNode { it.text?.toString() == VALUE && it.textSelectionStart == VALUE.length }
        assertEquals("the field's text", VALUE, field.text?.toString())
        assertEquals("the field's name with a value typed", listOf(label), readAloud(field).filter { it != VALUE })
        assertEquals("the cursor's start", VALUE.length, field.textSelectionStart)
        assertEquals("the cursor's end", VALUE.length, field.textSelectionEnd)
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
                            VaultTextField(
                                state = state,
                                modifier = Modifier.fillMaxWidth().testTag(FIELD).onGloballyPositioned {
                                    fieldTop = it.positionInParent().y
                                    fieldHeight = it.size.height
                                },
                                label = label,
                                keyboardOptions = VaultFieldDefaults.Username,
                                leftToRight = true,
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

    /** The name without the field's own text: its description and the parts it is made of that are not controls (`A11yWalker.spoken`). */
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

    private fun AccessibilityNodeInfo.boundsHeight(): Int = Rect().also { getBoundsInScreen(it) }.height()

    private companion object {
        const val FIELD = "pv.test.text"
        const val VALUE = "aziz.karimov@gmail.com"
        const val TIMEOUT_MS = 10_000L
    }
}
