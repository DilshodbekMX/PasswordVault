package io.github.dilshodbekmx.passwordvault.ui.list

import androidx.navigation3.runtime.EntryProviderScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.ui.shell.PLACEHOLDER_ENTRY_ID
import io.github.dilshodbekmx.passwordvault.ui.shell.RoutePlaceholder
import io.github.dilshodbekmx.passwordvault.ui.shell.openAction

/**
 * The routes of the list (ux.md §5.11, §5.23): Passwords, the root of the unlocked screens, and
 * RecentlyDeleted. Placeholders until the list package (build plan 3.5) replaces this body.
 */
fun EntryProviderScope<Route>.listNav(graph: AppGraph, nav: Navigator) {
    entry<Route.Passwords> { route ->
        RoutePlaceholder(
            route,
            nav,
            listOf(openAction(nav, Route.Entry(PLACEHOLDER_ENTRY_ID)), openAction(nav, Route.Edit()), openAction(nav, Route.RecentlyDeleted)),
        )
    }
    entry<Route.RecentlyDeleted> { route -> RoutePlaceholder(route, nav) }
}
