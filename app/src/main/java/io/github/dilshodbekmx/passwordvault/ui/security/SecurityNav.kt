package io.github.dilshodbekmx.passwordvault.ui.security

import androidx.navigation3.runtime.EntryProviderScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.ui.shell.PLACEHOLDER_ENTRY_ID
import io.github.dilshodbekmx.passwordvault.ui.shell.PLACEHOLDER_GROUP_ID
import io.github.dilshodbekmx.passwordvault.ui.shell.RoutePlaceholder
import io.github.dilshodbekmx.passwordvault.ui.shell.openAction

/**
 * The routes of the Security tab (ux.md §5.16): Security and Group(id). Placeholders until the
 * security tab package (build plan 7.1) replaces this body.
 */
fun EntryProviderScope<Route>.securityNav(graph: AppGraph, nav: Navigator) {
    entry<Route.Security> { route -> RoutePlaceholder(route, nav, listOf(openAction(nav, Route.Group(PLACEHOLDER_GROUP_ID)))) }
    entry<Route.Group> { route -> RoutePlaceholder(route, nav, listOf(openAction(nav, Route.GuidedChange(PLACEHOLDER_ENTRY_ID)))) }
}
