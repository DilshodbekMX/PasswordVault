package io.github.dilshodbekmx.passwordvault.ui.list

import androidx.navigation3.runtime.EntryProviderScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route

/**
 * The routes of the list (ux.md §5.11, §5.23; build plan 3.5): Passwords, the root of the unlocked
 * screens, and Recently deleted. Each screen has its own ViewModel in its back-stack entry, so the
 * query and the scroll position of Passwords are kept while an entry is open on top of it, and both
 * go when the app locks (architecture.md §5.5).
 */
fun EntryProviderScope<Route>.listNav(graph: AppGraph, nav: Navigator) {
    entry<Route.Passwords> { PasswordsRoute(graph, nav) }
    entry<Route.RecentlyDeleted> { RecentlyDeletedRoute(graph, nav) }
}
