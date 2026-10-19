package io.github.dilshodbekmx.passwordvault.ui.shell

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Walks the real app the way Switch Access and TalkBack reach it (build plan 8.1; ux.md §8, §12 check
 * 9): through the accessibility tree that an accessibility tool gets (`UiAutomation`, which Android
 * treats as a tool, as it treats Switch Access, which declares `isAccessibilityTool`), pressing every
 * control by its accessibility actions (click, long click, a custom action, scrolling, setting a
 * field's text) and never by a touch.
 *
 * [look] reads every window of the app at a screen, scrolled through from top to end, and for each
 * node that a user can act on (click, long click, a custom action, a field, a switch) checks:
 * - it has a name TalkBack can say: its own text, description or hint, or the texts of the parts it is
 *   made of that are not controls themselves;
 * - each custom action has a label (the list's row actions, ux.md §8);
 * - what it says it is it can do: a clickable node offers `ACTION_CLICK`, a long-clickable one
 *   `ACTION_LONG_CLICK`, so that Switch Access's "Select" and "Long press" reach it.
 * Every such node is also written down, one line each (`NODE screen|name|class|actions|flags`): how
 * far the tree shows what TalkBack would read. The run's report goes to the app's cache
 * (`cache/pv-a11y/<run>.txt`), to the instrumentation status (`pva11y`) and to logcat ([TAG]).
 */
class A11yWalker(private val compose: ComposeTestRule, private val run: String, private val layout: Boolean = false) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation: UiAutomation = instrumentation.uiAutomation.also { ua ->
        ua.serviceInfo = ua.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
    }
    private val app = instrumentation.targetContext.packageName
    private val report = File(instrumentation.targetContext.cacheDir, "pv-a11y/$run.txt").apply {
        parentFile?.mkdirs()
        writeText("")
    }
    private var failures = 0
    private var screens = 0

    /** What the walk did last, for a step that broke. */
    var last: String = ""
        private set

    init {
        line("RUN $run|API ${Build.VERSION.SDK_INT}")
    }

    // ---- the tree --------------------------------------------------------------------------------

    /** The roots of the app's windows now, the top one first. */
    private fun roots(): List<AccessibilityNodeInfo> =
        automation.windows.mapNotNull { window -> runCatching { window.root }.getOrNull() }.filter { it.refresh() && it.packageName == app }

    /**
     * Every node under [node], each read afresh: Compose sends its accessibility events only while a
     * real accessibility service is on and leaves `UiAutomation` out (tools/emu/README.md), so this
     * connection's cache would keep the tree as it was before a scroll or a new screen.
     */
    private fun walk(node: AccessibilityNodeInfo, depth: Int = 0, out: MutableList<AccessibilityNodeInfo> = mutableListOf()): List<AccessibilityNodeInfo> {
        out += node
        if (depth < MAX_DEPTH) {
            for (i in 0 until node.childCount) node.getChild(i)?.takeIf { it.refresh() }?.let { walk(it, depth + 1, out) }
        }
        return out
    }

    private fun everyNode(): List<AccessibilityNodeInfo> = roots().flatMap { walk(it) }

    /** The node whose view id (a test tag; the app reports them as ids) is [tag], in any window of the app. */
    fun find(tag: String): AccessibilityNodeInfo? = everyNode().firstOrNull { it.viewIdResourceName == tag || it.viewIdResourceName?.endsWith("/$tag") == true }

    private fun has(node: AccessibilityNodeInfo, action: AccessibilityAction) = node.actionList.any { it.id == action.id }

    /**
     * The custom actions of [node]: the app's own (Compose gives them the app's resource ids,
     * `accessibility_custom_action_N`), not the platform's, which an older Android may not name (an
     * `ACTION_IME_ENTER` on API 28 has no constant there).
     */
    private fun customActions(node: AccessibilityNodeInfo): List<AccessibilityAction> =
        node.actionList.filter { it.id !in STANDARD_IDS && (it.id ushr 24) == APP_PACKAGE_ID }

    private fun actionable(node: AccessibilityNodeInfo): Boolean =
        node.isClickable || node.isLongClickable || node.isEditable || node.isCheckable || customActions(node).isNotEmpty()

    /** What TalkBack says for [node]: its own words, else those of the parts it is made of that are not controls. */
    private fun spoken(node: AccessibilityNodeInfo): String {
        val own = listOfNotNull(node.contentDescription, node.text, if (Build.VERSION.SDK_INT >= 26) node.hintText else null)
            .map { it.toString().trim() }.filter { it.isNotEmpty() }.distinct().joinToString(" ")
        if (own.isNotEmpty()) return own
        val parts = mutableListOf<String>()
        fun gather(n: AccessibilityNodeInfo, depth: Int) {
            for (i in 0 until n.childCount) {
                val child = n.getChild(i) ?: continue
                if (actionable(child)) continue
                listOfNotNull(child.contentDescription, child.text).map { it.toString().trim() }.filter { it.isNotEmpty() }.forEach(parts::add)
                if (depth < MAX_DEPTH) gather(child, depth + 1)
            }
        }
        gather(node, 0)
        return parts.distinct().joinToString(" ")
    }

    private fun actionNames(node: AccessibilityNodeInfo): String = node.actionList.mapNotNull { action ->
        when (action.id) {
            AccessibilityAction.ACTION_CLICK.id -> "click" + (action.label?.let { "($it)" } ?: "")
            AccessibilityAction.ACTION_LONG_CLICK.id -> "longClick" + (action.label?.let { "($it)" } ?: "")
            AccessibilityAction.ACTION_SCROLL_FORWARD.id -> "scrollForward"
            AccessibilityAction.ACTION_SCROLL_BACKWARD.id -> "scrollBackward"
            AccessibilityAction.ACTION_SET_TEXT.id -> "setText"
            AccessibilityAction.ACTION_EXPAND.id -> "expand"
            AccessibilityAction.ACTION_COLLAPSE.id -> "collapse"
            AccessibilityAction.ACTION_DISMISS.id -> "dismiss"
            AccessibilityAction.ACTION_SET_PROGRESS.id -> "setProgress"
            in STANDARD_IDS -> null
            else -> if ((action.id ushr 24) == APP_PACKAGE_ID) "custom:" + (action.label ?: "(no label)") else null
        }
    }.joinToString(",")

    private fun flags(node: AccessibilityNodeInfo): String = buildList {
        if (!node.isEnabled) add("disabled")
        if (node.isPassword) add("password")
        if (node.isCheckable) add(if (node.isChecked) "checked" else "unchecked")
        if (node.isEditable) add("field")
        if (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive) add("sensitive")
        if (Build.VERSION.SDK_INT >= 28 && node.isHeading) add("heading")
        if (Build.VERSION.SDK_INT >= 30) node.stateDescription?.takeIf { it.isNotEmpty() }?.let { add("state=$it") }
    }.joinToString(",")

    // ---- a screen ------------------------------------------------------------------------------------

    /**
     * Looks at the screen now as [screen]: every actionable node of every window of the app, then again
     * after each scroll forward of every scrolling node (as Switch Access scrolls), which are then
     * scrolled back to their start.
     */
    fun look(screen: String) {
        settle()
        screens++
        val seen = linkedMapOf<String, String>()
        val problems = linkedSetOf<String>()
        collect(screen, seen, problems)
        val scrollers = everyNode().filter { it.isVisibleToUser && has(it, AccessibilityAction.ACTION_SCROLL_FORWARD) }.mapNotNull { it.viewIdResourceName ?: boundsKey(it) }
        for (key in scrollers.distinct()) {
            var steps = 0
            while (steps < MAX_SCROLLS) {
                val scroller = everyNode().firstOrNull { (it.viewIdResourceName ?: boundsKey(it)) == key && has(it, AccessibilityAction.ACTION_SCROLL_FORWARD) } ?: break
                if (!scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) break
                steps++
                settle()
                collect(screen, seen, problems)
            }
            repeat(steps + 2) {
                everyNode().firstOrNull { (it.viewIdResourceName ?: boundsKey(it)) == key && has(it, AccessibilityAction.ACTION_SCROLL_BACKWARD) }
                    ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                settle()
            }
        }
        // With -e pvA11yLayout true (a run on a phone set to 320 dp and 200 % by the script), the layout
        // walk's probe looks at the screen too: no text cut or split, no control under 48 dp.
        if (layout) {
            val found = io.github.dilshodbekmx.passwordvault.ui.layout.LayoutProbe(compose, instrumentation.targetContext).apply { checkEnglish = false }.look()
            for (check in io.github.dilshodbekmx.passwordvault.ui.layout.LayoutCheck.entries) {
                for (finding in found.of(check)) problems += "layout, ${check.title}: $finding"
            }
        }
        for (node in seen.values) line(node)
        if (problems.isEmpty()) {
            line("SCREEN $screen|PASS|${seen.size} controls")
        } else {
            failures += problems.size
            line("SCREEN $screen|FAIL|" + problems.joinToString("; ").replace('|', '/').replace('\n', ' '))
        }
    }

    /** Material's name for a sheet's drag handle in the phone's language ("Drag handle"). */
    private val dragHandle: String by lazy {
        val id = instrumentation.targetContext.resources.getIdentifier("m3c_bottom_sheet_drag_handle_description", "string", app)
        if (id != 0) instrumentation.targetContext.getString(id) else "Drag handle"
    }

    private fun sameBounds(a: AccessibilityNodeInfo, b: AccessibilityNodeInfo): Boolean {
        val ra = Rect().also(a::getBoundsInScreen)
        val rb = Rect().also(b::getBoundsInScreen)
        return Rect.intersects(ra, rb)
    }

    private fun boundsKey(node: AccessibilityNodeInfo): String {
        val r = Rect()
        node.getBoundsInScreen(r)
        return "${node.className}@${r.left},${r.top}"
    }

    private fun collect(screen: String, seen: MutableMap<String, String>, problems: MutableSet<String>) {
        for (node in everyNode()) {
            if (!node.isVisibleToUser || !actionable(node)) continue
            val name = spoken(node)
            val actions = actionNames(node)
            val key = "${node.viewIdResourceName}|$name|$actions"
            if (key in seen) continue
            val bounds = Rect().also(node::getBoundsInScreen).toShortString()
            seen[key] = "NODE $screen|$name|${node.className}|${node.viewIdResourceName ?: ""}|$actions|${flags(node)}|$bounds".replace('\n', ' ')
            val what = node.viewIdResourceName ?: "${node.className} \"${name.take(40)}\" at $bounds"
            if (name.isEmpty()) {
                // Material 3 1.4 wraps a bottom sheet's drag handle in a tooltip's anchor (DragHandleWithTooltip):
                // a node of the library's own with a long click that shows the tooltip "Drag handle", whose
                // handle beside it is named. Written down as KNOWN; any other node without a name fails.
                val handle = everyNode().any { other -> other.contentDescription?.toString() == dragHandle && sameBounds(other, node) }
                if (handle && customActions(node).isEmpty() && !has(node, AccessibilityAction.ACTION_CLICK) && !node.isEditable) {
                    seen["$key|known"] = "KNOWN $screen|Material's tooltip anchor around the drag handle: a long click shows \"$dragHandle\"|$bounds"
                } else {
                    problems += "no name for TalkBack: $what ($actions)"
                }
            }
            if (customActions(node).any { it.label.isNullOrBlank() }) problems += "a custom action without a label: $what"
            // A disabled control offers no action, which is right; an enabled one must offer what it says it does.
            if (node.isEnabled && node.isClickable && !has(node, AccessibilityAction.ACTION_CLICK) && !(node.isSelected || node.isChecked)) problems += "clickable without ACTION_CLICK: $what"
            if (node.isEnabled && node.isLongClickable && !has(node, AccessibilityAction.ACTION_LONG_CLICK)) problems += "long-clickable without ACTION_LONG_CLICK: $what"
        }
    }

    // ---- acting ----------------------------------------------------------------------------------

    /**
     * Waits for the node [tag], scrolling the scrolling nodes to it if it is not shown: forward to their
     * ends, then back to their starts.
     */
    fun await(tag: String, timeoutMs: Long = WAIT_MS): AccessibilityNodeInfo {
        last = "wait for $tag"
        val end = System.currentTimeMillis() + timeoutMs
        var forward = true
        while (System.currentTimeMillis() < end) {
            find(tag)?.takeIf { it.isVisibleToUser }?.let { return it }
            if (!scrollTowards(tag, forward)) {
                if (forward) forward = false else Thread.sleep(POLL_MS)
            }
        }
        throw AssertionError("no node $tag in the accessibility tree after ${timeoutMs / 1000} s; ids: ${ids()}")
    }

    /** Whether the keyboard's window is up. */
    fun keyboardShown(): Boolean = automation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }

    /** Whether [tag] offers a click now (a selected tab does not). */
    fun clickable(tag: String): Boolean = find(tag)?.let { has(it, AccessibilityAction.ACTION_CLICK) } == true

    /** Whether [tag] is in the tree now and shown. */
    fun shown(tag: String): Boolean = find(tag)?.isVisibleToUser == true

    /** Whether [tag] is in the tree now (shown or not). */
    fun exists(tag: String): Boolean = find(tag) != null

    fun awaitGone(tag: String, timeoutMs: Long = WAIT_MS) {
        last = "wait for $tag to go"
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (find(tag) == null) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("$tag is still in the tree")
    }

    /** One scroll [forward] or back of the scrolling nodes, where [tag] is not yet in the tree; whether one moved. */
    private fun scrollTowards(tag: String, forward: Boolean): Boolean {
        if (find(tag) != null) {
            // In the tree but off the screen: shown by the scroller holding it.
            val node = find(tag) ?: return false
            return node.performAction(AccessibilityAction.ACTION_SHOW_ON_SCREEN.id).also { if (it) settle() }
        }
        // Every scrolling node shown moves one step forward (the largest first), as a person scanning the
        // screen with Switch Access would scroll each in turn.
        val action = if (forward) AccessibilityAction.ACTION_SCROLL_FORWARD else AccessibilityAction.ACTION_SCROLL_BACKWARD
        val scrollers = everyNode().filter { it.isVisibleToUser && has(it, action) }
            .sortedByDescending { node -> Rect().also(node::getBoundsInScreen).let { it.width().toLong() * it.height() } }
        var moved = false
        for (scroller in scrollers) {
            if (scroller.performAction(action.id)) {
                moved = true
                settle()
                if (find(tag) != null) return true
            }
        }
        return moved
    }

    /** Presses [tag] by `ACTION_CLICK`, as Switch Access's "Select" and TalkBack's double tap do. */
    fun click(tag: String) {
        val node = await(tag)
        last = "click $tag"
        check(has(node, AccessibilityAction.ACTION_CLICK)) { "$tag offers no ACTION_CLICK (${actionNames(node)})" }
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "ACTION_CLICK on $tag was refused" }
        settle()
    }

    fun clickIfShown(tag: String): Boolean {
        val node = find(tag)?.takeIf { it.isVisibleToUser } ?: return false
        last = "click $tag"
        val done = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        settle()
        return done
    }

    /** Long-presses [tag] by `ACTION_LONG_CLICK`. */
    fun longClick(tag: String) {
        val node = await(tag)
        last = "long-click $tag"
        check(node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) { "ACTION_LONG_CLICK on $tag was refused" }
        settle()
    }

    /** Runs the custom action [label] of [tag] (the list's row actions, ux.md §8). */
    fun custom(tag: String, label: String) {
        val node = await(tag)
        last = "custom action \"$label\" of $tag"
        val action = customActions(node).firstOrNull { it.label?.toString() == label }
            ?: throw AssertionError("$tag has no custom action \"$label\": ${customActions(node).map { it.label }}")
        check(node.performAction(action.id)) { "the custom action \"$label\" of $tag was refused" }
        settle()
    }

    /** The custom actions' labels of [tag]. */
    fun customLabels(tag: String): List<String> = customActions(await(tag)).map { it.label?.toString().orEmpty() }

    /** Sets the text of the field [tag] (`ACTION_SET_TEXT`, as an accessibility service edits a field). */
    fun setText(tag: String, text: String) {
        val node = await(tag)
        last = "set the text of $tag"
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) { "ACTION_SET_TEXT on $tag was refused" }
        settle()
    }

    /** The text of the node [tag] (or of the Compose node, where the tree has it only as a description). */
    fun textOf(tag: String): String? = find(tag)?.text?.toString()

    /** Back, as Switch Access's and TalkBack's global action gives it. */
    fun back() {
        last = "back"
        check(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) { "GLOBAL_ACTION_BACK was refused" }
        settle()
    }

    /** Whether the Compose tree has a node tagged [tag] (a route's root, which the accessibility tree may merge away). */
    fun composeHas(tag: String): Boolean =
        runCatching { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }.getOrDefault(false)

    fun awaitCompose(tag: String, timeoutMs: Long = WAIT_MS) {
        last = "wait for $tag (Compose)"
        compose.waitUntil(timeoutMs) { composeHas(tag) }
    }

    private fun ids(): String = everyNode().mapNotNull { it.viewIdResourceName }.distinct().take(60).joinToString(",") +
        "; scrolling: " + everyNode().filter { has(it, AccessibilityAction.ACTION_SCROLL_FORWARD) || has(it, AccessibilityAction.ACTION_SCROLL_BACKWARD) }
            .joinToString(",") { "${it.className}/${it.viewIdResourceName}/${actionNames(it)}/visible=${it.isVisibleToUser}" }

    fun settle() {
        compose.waitForIdle()
        Thread.sleep(SETTLE_MS)
        compose.waitForIdle()
    }

    // ---- the run ---------------------------------------------------------------------------------------

    /** Runs [action] as the step [name]; a step that fails is recorded and the walk goes on. */
    fun step(name: String, action: () -> Unit): Boolean = try {
        action()
        true
    } catch (problem: Throwable) {
        failures++
        val message = problem.message.orEmpty().lineSequence().filter { it.isNotBlank() }.take(3).joinToString(" / ")
        line("BROKEN $name (at: $last)|${problem.javaClass.simpleName}: ${message.take(600).replace('|', '/')}")
        Log.w(TAG, "step $name", problem)
        false
    }

    fun note(text: String) = line("NOTE " + text.replace('\n', ' '))

    /** A check of its own, outside a screen's look: PASS or FAIL. */
    fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) line("CHECK $name|PASS") else {
            failures++
            line("CHECK $name|FAIL|${detail.replace('|', '/')}")
        }
    }

    /** The run's last line; the number of failed checks and broken steps. */
    fun end(): Int {
        line("END $screens screens|$failures failed")
        return failures
    }

    private fun line(text: String) {
        report.appendText(text + "\n")
        Log.i(TAG, text)
        instrumentation.sendStatus(STATUS_CODE, Bundle().apply { putString("pva11y", text) })
    }

    companion object {
        const val TAG = "PVA11Y"
        const val WAIT_MS = 30_000L
        private const val POLL_MS = 200L
        private const val SETTLE_MS = 350L
        private const val MAX_DEPTH = 60
        private const val MAX_SCROLLS = 40
        private const val STATUS_CODE = 2

        /** The package byte of an app's own resource ids (0x7f…), which Compose's custom actions carry. */
        private const val APP_PACKAGE_ID = 0x7f

        /** The platform's own actions; any other id is a custom action. */
        private val STANDARD_IDS: Set<Int> = listOfNotNull(
            AccessibilityAction.ACTION_FOCUS, AccessibilityAction.ACTION_CLEAR_FOCUS, AccessibilityAction.ACTION_SELECT,
            AccessibilityAction.ACTION_CLEAR_SELECTION, AccessibilityAction.ACTION_CLICK, AccessibilityAction.ACTION_LONG_CLICK,
            AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS, AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS,
            AccessibilityAction.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, AccessibilityAction.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY,
            AccessibilityAction.ACTION_NEXT_HTML_ELEMENT, AccessibilityAction.ACTION_PREVIOUS_HTML_ELEMENT,
            AccessibilityAction.ACTION_SCROLL_FORWARD, AccessibilityAction.ACTION_SCROLL_BACKWARD, AccessibilityAction.ACTION_COPY,
            AccessibilityAction.ACTION_PASTE, AccessibilityAction.ACTION_CUT, AccessibilityAction.ACTION_SET_SELECTION,
            AccessibilityAction.ACTION_EXPAND, AccessibilityAction.ACTION_COLLAPSE, AccessibilityAction.ACTION_DISMISS,
            AccessibilityAction.ACTION_SET_TEXT, AccessibilityAction.ACTION_SHOW_ON_SCREEN, AccessibilityAction.ACTION_SCROLL_TO_POSITION,
            AccessibilityAction.ACTION_SCROLL_UP, AccessibilityAction.ACTION_SCROLL_LEFT, AccessibilityAction.ACTION_SCROLL_DOWN,
            AccessibilityAction.ACTION_SCROLL_RIGHT, AccessibilityAction.ACTION_CONTEXT_CLICK, AccessibilityAction.ACTION_SET_PROGRESS,
            if (Build.VERSION.SDK_INT >= 26) AccessibilityAction.ACTION_MOVE_WINDOW else null,
            if (Build.VERSION.SDK_INT >= 28) AccessibilityAction.ACTION_SHOW_TOOLTIP else null,
            if (Build.VERSION.SDK_INT >= 28) AccessibilityAction.ACTION_HIDE_TOOLTIP else null,
            if (Build.VERSION.SDK_INT >= 29) AccessibilityAction.ACTION_PAGE_UP else null,
            if (Build.VERSION.SDK_INT >= 29) AccessibilityAction.ACTION_PAGE_DOWN else null,
            if (Build.VERSION.SDK_INT >= 29) AccessibilityAction.ACTION_PAGE_LEFT else null,
            if (Build.VERSION.SDK_INT >= 29) AccessibilityAction.ACTION_PAGE_RIGHT else null,
            if (Build.VERSION.SDK_INT >= 30) AccessibilityAction.ACTION_PRESS_AND_HOLD else null,
            if (Build.VERSION.SDK_INT >= 30) AccessibilityAction.ACTION_IME_ENTER else null,
            if (Build.VERSION.SDK_INT >= 32) AccessibilityAction.ACTION_DRAG_START else null,
            if (Build.VERSION.SDK_INT >= 32) AccessibilityAction.ACTION_DRAG_DROP else null,
            if (Build.VERSION.SDK_INT >= 32) AccessibilityAction.ACTION_DRAG_CANCEL else null,
            if (Build.VERSION.SDK_INT >= 33) AccessibilityAction.ACTION_SHOW_TEXT_SUGGESTIONS else null,
            if (Build.VERSION.SDK_INT >= 34) AccessibilityAction.ACTION_SCROLL_IN_DIRECTION else null,
        ).map { it.id }.toSet()
    }
}
