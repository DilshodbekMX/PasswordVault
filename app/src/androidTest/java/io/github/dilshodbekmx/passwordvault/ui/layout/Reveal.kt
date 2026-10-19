package io.github.dilshodbekmx.passwordvault.ui.layout

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction

/*
 * For the device tests that walk a screen's states (the areas' pseudo-locale tests): on a phone of
 * 320 dp at 200 % (build plan 6.2, `tools/emu/layout/walk.sh`), a node that a phone of normal size
 * shows can lie in a lazy list's item that is not composed yet, and a touch at a node can land on the
 * keyboard. These find the node first and press it by its click action.
 */

/** Scrolls the screen's lazy lists, one after another, until a node matching [matcher] is composed. */
fun ComposeTestRule.reveal(matcher: SemanticsMatcher) {
    fun found() = onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    if (found()) return
    val lists = onAllNodes(hasScrollToNodeAction(), useUnmergedTree = true)
    val count = runCatching { lists.fetchSemanticsNodes(atLeastOneRootRequired = false).size }.getOrDefault(0)
    for (index in 0 until count) {
        runCatching { lists[index].performScrollToNode(matcher) }
        waitForIdle()
        if (found()) return
    }
}

/** The node with [text], once [reveal]ed. */
fun ComposeTestRule.revealText(text: String, substring: Boolean = false, useUnmergedTree: Boolean = false): SemanticsNodeInteraction {
    reveal(hasText(text, substring))
    return onNode(hasText(text, substring), useUnmergedTree)
}

/** The node tagged [tag], once [reveal]ed. */
fun ComposeTestRule.revealTag(tag: String, useUnmergedTree: Boolean = false): SemanticsNodeInteraction {
    reveal(hasTestTag(tag))
    return onNode(hasTestTag(tag), useUnmergedTree)
}

/** Presses [node] by its click action, scrolled into view first where it can be. */
fun SemanticsNodeInteraction.press(): SemanticsNodeInteraction {
    runCatching { performScrollTo() }
    return performSemanticsAction(SemanticsActions.OnClick)
}
