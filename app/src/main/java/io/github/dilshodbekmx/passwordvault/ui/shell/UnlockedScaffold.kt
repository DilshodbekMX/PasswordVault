package io.github.dilshodbekmx.passwordvault.ui.shell

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.clipboard.ClipboardRowSlot
import io.github.dilshodbekmx.passwordvault.nav.NavState
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.ui.common.FittedText
import io.github.dilshodbekmx.passwordvault.ui.common.topHairline
import io.github.dilshodbekmx.passwordvault.ui.lock.LockNotices
import io.github.dilshodbekmx.passwordvault.ui.security.SecurityBadge
import io.github.dilshodbekmx.passwordvault.ui.security.securityTabState
import io.github.dilshodbekmx.passwordvault.ui.settings.NewMasterPasswordAfterUnlock
import io.github.dilshodbekmx.passwordvault.ui.theme.LocalLook

/**
 * The frame around the screens (architecture.md §2.2 `ui.shell`, §3.6). While the app is unlocked
 * it draws, at the bottom, the clipboard status row (`ClipboardRowSlot`, ux.md §6) and, on the
 * three tabs' roots, the navigation bar (Passwords · Security · Settings, ux.md §4), whose Security
 * item carries `SecurityBadge`; over the screens it calls `LockNotices` (the notices after unlock,
 * lock.md §13.4), `NewMasterPasswordAfterUnlock` (`Route.NewMasterPassword` once, after the PIN or
 * fingerprint opened the app on the lock-screen route of lock.md F11 steps 1 and 3) and
 * `FirstCopyTip` (ux.md §6). While the app is in setup or locked it draws nothing
 * of its own, so the lock screen shows nothing of what is behind it.
 *
 * [content] is the one navigation display of the app, given the bottom area's padding; it stays in
 * place whichever stack is shown.
 */
@Composable
fun UnlockedScaffold(graph: AppGraph, nav: NavState, content: @Composable (PaddingValues) -> Unit) {
    val unlocked = nav.top == NavState.Top.UNLOCKED
    Scaffold(
        bottomBar = {
            if (unlocked) {
                Column(Modifier.semantics { testTagsAsResourceId = true }) {
                    ClipboardRowSlot(graph)
                    if (nav.atTabRoot) TabBar(graph, nav)
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            content(padding)
            if (unlocked) {
                LockNotices(graph, nav)
                NewMasterPasswordAfterUnlock(nav)
                FirstCopyTip(graph)
            }
        }
    }
}

/**
 * The navigation bar of the three tabs: with a hairline along its top where the style has one
 * (Emerald in light, `Look.barOutline`; docs/design/looks/README.md).
 */
@Composable
private fun TabBar(graph: AppGraph, nav: NavState) {
    // The three labels take one size: the one that needs the smallest gets it (FittedText).
    val labels = listOf(R.string.shell_tab_passwords, R.string.shell_tab_security, R.string.shell_tab_settings).map { stringResource(it) }
    NavigationBar(Modifier.testTag(TAB_BAR_TAG).topHairline(LocalLook.current.barOutline)) {
        Tab(nav, Route.Passwords, R.drawable.ic_shell_passwords, R.string.shell_tab_passwords, labels)
        Tab(nav, Route.Security, R.drawable.ic_shell_security, R.string.shell_tab_security, labels, state = securityTabState(graph)) { SecurityBadge(graph) }
        Tab(nav, Route.Settings, R.drawable.ic_shell_settings, R.string.shell_tab_settings, labels)
    }
}

/** The navigation bar's test tag; its items are `pv.tab.<Route>`. */
const val TAB_BAR_TAG = "pv.tabBar"

/**
 * One tab of the bar; its label is a [FittedText], as large as the phone's font makes it while each
 * of its words fits on a line, and as large as the bar's other [labels]. [state] is what TalkBack says
 * after the tab's name ("Security, new results",
 * ux.md §8): material3's `NavigationBarItem` clears the semantics of its icon slot, where [badge]
 * sits, whenever the item has a label, so the badge itself is never read.
 */
@Composable
private fun RowScope.Tab(
    nav: NavState,
    root: Route,
    @DrawableRes icon: Int,
    @StringRes label: Int,
    labels: List<String>,
    state: String? = null,
    badge: (@Composable () -> Unit)? = null,
) {
    NavigationBarItem(
        selected = nav.tabRoot == root,
        onClick = { nav.tab(root) },
        icon = {
            if (badge == null) {
                Icon(painterResource(icon), contentDescription = null)
            } else {
                BadgedBox(badge = { badge() }) { Icon(painterResource(icon), contentDescription = null) }
            }
        },
        // Never a word broken across lines, at 320 dp and 200 % in every language (i18n.md §4.5 rule 5).
        label = {
            val text = stringResource(label)
            FittedText(text, textAlign = TextAlign.Center, alongside = labels - text)
        },
        modifier = Modifier.testTag("pv.tab.${root.name}").semantics { if (state != null) stateDescription = state },
    )
}
