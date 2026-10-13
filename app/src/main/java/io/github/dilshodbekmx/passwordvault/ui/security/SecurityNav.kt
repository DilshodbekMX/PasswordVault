package io.github.dilshodbekmx.passwordvault.ui.security

import androidx.navigation3.runtime.EntryProviderScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route

/**
 * The routes of the Security tab (ux.md §5.16; build plan 7.1): `Security`, the tab's root, and
 * `Group(id)`, the list behind a card: a group of reused passwords by its `ReuseGroup.id`, or the
 * fixed ids of [GroupKind] for the passwords easy to guess, the changes not finished and the accounts
 * left out. Each screen's ViewModel lives in the route's own store, so a lock clears it
 * (architecture.md §5.5).
 *
 * The app's check (`SecurityModule`) is made here, as the app's routes are registered, so that it
 * follows the vault from the first unlock on, whichever screen is shown, and the tab's dot is right
 * as soon as the bottom bar appears.
 */
fun EntryProviderScope<Route>.securityNav(graph: AppGraph, nav: Navigator) {
    graph.security.report // makes the check, which starts following the vault
    entry<Route.Security> { SecurityRoute(graph, nav) }
    entry<Route.Group> { route -> GroupListRoute(graph, nav, route.id) }
}
