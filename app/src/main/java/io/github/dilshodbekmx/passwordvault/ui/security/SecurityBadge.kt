package io.github.dilshodbekmx.passwordvault.ui.security

import androidx.compose.material3.Badge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.R

/**
 * The Security tab's dot (ux.md §5.16, "Badge"): drawn in the badge slot of the bottom bar's
 * Security item while `SecurityReports.unseen` is true, that is while the check has results the user
 * has not seen since the tab was last shown. A dot, never a number, and no notification.
 *
 * TalkBack does not reach it there: material3's `NavigationBarItem` clears the semantics of its icon
 * slot, where the badge sits, whenever the item has a label. What TalkBack says of the dot is the
 * tab's own state, [securityTabState].
 */
@Composable
fun SecurityBadge(graph: AppGraph) {
    val unseen by graph.security.unseen.collectAsStateWithLifecycle()
    SecurityDot(unseen)
}

/** The dot itself, while [shown]: Material's small `Badge`, in the theme's error colour. */
@Composable
fun SecurityDot(shown: Boolean) {
    if (shown) Badge(Modifier.testTag(SecurityTags.BADGE))
}

/**
 * The Security tab's state for TalkBack: "New results" while the dot shows, so that the tab is read
 * "Security, new results" (ux.md §8); null otherwise. The bottom bar sets it as the tab's
 * `stateDescription`, beside the tab's name, because the dot inside the item's icon slot is not read
 * (see [SecurityBadge]).
 */
@Composable
fun securityTabState(graph: AppGraph): String? {
    val unseen by graph.security.unseen.collectAsStateWithLifecycle()
    return newResultsState(unseen)
}

/** "New results" while [unseen], else null: [securityTabState] for a given dot. */
@Composable
internal fun newResultsState(unseen: Boolean): String? = if (unseen) stringResource(R.string.security_new_results) else null
