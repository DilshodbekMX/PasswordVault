package io.github.dilshodbekmx.passwordvault.ui.layout

import android.app.Activity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.nav.routeTag

/**
 * Moves through the real app for the layout walk (build plan 6.2) by the test tags of its screens,
 * and looks at each screen with [LayoutProbe]: the checks on what is laid out, then on each further
 * stretch of every list or column that scrolls, so that every control is seen whole once.
 */
class Walker(private val compose: ComposeTestRule, val run: LayoutRun) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val probe = LayoutProbe(compose, instrumentation.targetContext)

    /** The activity in front now, or null. */
    fun activity(): Activity? {
        var found: Activity? = null
        instrumentation.runOnMainSync {
            found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
        }
        return found
    }

    fun exists(tag: String): Boolean = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    fun await(tag: String, timeoutMs: Long = WAIT_MS) {
        last = "wait for $tag"
        compose.waitUntil(timeoutMs) { runCatching { exists(tag) }.getOrDefault(false) }
    }

    fun awaitGone(tag: String, timeoutMs: Long = WAIT_MS) {
        last = "wait for $tag to go"
        compose.waitUntil(timeoutMs) { runCatching { !exists(tag) }.getOrDefault(false) }
    }

    fun onRoute(route: Route, timeoutMs: Long = WAIT_MS) = await(routeTag(route), timeoutMs)

    fun isOn(route: Route): Boolean = exists(routeTag(route))

    /** What the walk did last, for the report of a step that broke. */
    var last: String = ""
        private set

    /**
     * Presses the control tagged [tag]: brought into view first where it lies in a list or column that
     * scrolls (a lazy list's item that is not composed yet is scrolled to), then its click action is
     * run, as TalkBack and Switch Access press it, so that the walk does not depend on where a finger
     * would land. Whether a finger reaches it is the layout's checks' concern.
     */
    fun tap(tag: String) {
        last = "tap $tag"
        if (!exists(tag)) scrollToTag(tag)
        await(tag)
        last = "tap $tag"
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
        runCatching { node.performScrollTo() }
        val clickable = runCatching { node.fetchSemanticsNode().config.contains(SemanticsActions.OnClick) }.getOrDefault(false)
        if (clickable) node.performSemanticsAction(SemanticsActions.OnClick) else node.performClick()
        compose.waitForIdle()
    }

    /** Scrolls every lazy list or column that can scroll to a node to the one tagged [tag], if one has it. */
    private fun scrollToTag(tag: String) {
        val lists = compose.onAllNodes(hasScrollToNodeAction(), useUnmergedTree = true)
        val count = runCatching { lists.fetchSemanticsNodes(atLeastOneRootRequired = false).size }.getOrDefault(0)
        for (index in 0 until count) {
            if (runCatching { lists[index].performScrollToNode(hasTestTag(tag)) }.isSuccess && exists(tag)) return
        }
    }

    /** Closes the keyboard, if it is open, without a Back that a screen could take. */
    fun hideKeyboard() {
        val activity = activity() ?: return
        instrumentation.runOnMainSync {
            androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        compose.waitForIdle()
        Thread.sleep(SETTLE_MS)
    }

    /** Taps [tag] if it is shown; whether it was. */
    fun tapIfShown(tag: String): Boolean {
        if (!exists(tag)) return false
        tap(tag)
        return true
    }

    /** Types [text] at the end of the field tagged [tag]. */
    fun type(tag: String, text: String) {
        last = "type into $tag"
        await(tag)
        val node = compose.onNodeWithTag(tag)
        runCatching { node.performScrollTo() }
        node.performTextInput(text)
        compose.waitForIdle()
    }

    /** Empties the field tagged [tag]. */
    fun clear(tag: String) {
        await(tag)
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("")) }
        compose.waitForIdle()
    }

    /** The text of the node tagged [tag] (its first text), or null. */
    fun textOf(tag: String): String? =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false)
            .firstNotNullOfOrNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } }

    /** System Back, as the navigation bar's button gives it. */
    fun back() {
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }

    /**
     * Looks at the screen now as [screen]: a picture of it as it opens, then the checks on what is laid
     * out, then the checks again after each stretch of every list or column that scrolls, from its top
     * to its end; it is left scrolled back to the top.
     */
    fun look(screen: String) {
        compose.waitForIdle()
        Thread.sleep(SETTLE_MS)
        compose.waitForIdle()
        run.shot(screen)
        var findings = probe.look()
        val scrollers = compose.onAllNodes(hasScrollAction() and scrollsVertically, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false)
        for (index in scrollers.indices) {
            val scroller = compose.onAllNodes(hasScrollAction() and scrollsVertically, useUnmergedTree = true)[index]
            val node = runCatching { scroller.fetchSemanticsNode() }.getOrNull() ?: continue
            val range = node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) ?: continue
            if (range.maxValue() <= 0f) continue
            val step = node.size.height * STEP_OF_VIEWPORT
            runCatching { scroller.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -1_000_000f) } }
            for (stretch in 0 until MAX_STRETCHES) {
                val now = runCatching { scroller.fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) }.getOrNull() ?: break
                if (now.value() >= now.maxValue() - 1f) break
                runCatching { scroller.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, step) } }
                compose.waitForIdle()
                findings += probe.look()
            }
            runCatching { scroller.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -1_000_000f) } }
            compose.waitForIdle()
        }
        run.record(screen, findings)
    }

    /** Runs [action] as the step [name]; a step that fails is recorded and the walk goes on. */
    fun step(name: String, action: () -> Unit): Boolean =
        try {
            action()
            true
        } catch (problem: Throwable) {
            run.broken("$name (at: $last)", problem)
            false
        }

    companion object {
        const val WAIT_MS = 30_000L
        const val SETTLE_MS = 400L
        private const val STEP_OF_VIEWPORT = 0.7f
        private const val MAX_STRETCHES = 40

        private val scrollsVertically = SemanticsMatcher("scrolls vertically") {
            val range = it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
            range != null && range.maxValue() > 0f
        }
    }
}
