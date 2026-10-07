package io.github.dilshodbekmx.passwordvault.ui.common

import androidx.compose.ui.semantics.AccessibilityAction
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.text.AnnotatedString

/**
 * Every semantics property of every node, one line per node in tree order, indented by depth:
 * what an accessibility service, an autofill service or a test can learn from the tree. Actions
 * are written by their label only and lambdas not at all, so that two compositions of the same
 * screen give the same lines.
 *
 * @param useUnmergedTree true: every node as composed; false: the tree TalkBack navigates.
 */
fun SemanticsNodeInteractionsProvider.semanticsLines(useUnmergedTree: Boolean): List<String> {
    val lines = mutableListOf<String>()
    fun walk(node: SemanticsNode, depth: Int) {
        val properties = node.config.map { (key, value) -> "${key.name}=${describe(value)}" }.sorted()
        lines += "  ".repeat(depth) + properties.joinToString(", ")
        for (child in node.children) walk(child, depth + 1)
    }
    for (root in onAllNodes(isRoot(), useUnmergedTree).fetchSemanticsNodes()) walk(root, 0)
    return lines
}

/** [semanticsLines] of both trees. */
fun SemanticsNodeInteractionsProvider.allSemanticsLines(): List<String> =
    semanticsLines(useUnmergedTree = true) + listOf("--- merged ---") + semanticsLines(useUnmergedTree = false)

private fun describe(value: Any?): String = when (value) {
    is AccessibilityAction<*> -> "action(${value.label})"
    is Function<*> -> "function"
    is AnnotatedString -> value.text
    is List<*> -> value.joinToString(prefix = "[", postfix = "]") { describe(it) }
    else -> value.toString()
}
