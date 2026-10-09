package io.github.dilshodbekmx.passwordvault.ui.security

import androidx.compose.material3.Badge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.R

/**
 * The Security tab's dot (ux.md §5.16, "Badge"): drawn in the badge slot of the bottom bar's
 * Security item while `SecurityReports.unseen` is true, that is while the check has results the user
 * has not seen since the tab was last shown. A dot, never a number, and no notification.
 */
@Composable
fun SecurityBadge(graph: AppGraph) {
    val unseen by graph.security.unseen.collectAsStateWithLifecycle()
    SecurityDot(unseen)
}

/**
 * The dot itself, while [shown]: Material's small `Badge`, in the theme's error colour, which TalkBack
 * reads as "New results" with the tab's name (ux.md §8: "Security, new results").
 */
@Composable
fun SecurityDot(shown: Boolean) {
    if (!shown) return
    val description = stringResource(R.string.security_new_results)
    Badge(Modifier.semantics { contentDescription = description }.testTag(SecurityTags.BADGE))
}
