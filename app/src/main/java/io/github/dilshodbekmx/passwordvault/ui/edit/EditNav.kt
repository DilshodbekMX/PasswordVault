package io.github.dilshodbekmx.passwordvault.ui.edit

import androidx.navigation3.runtime.EntryProviderScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.ui.shell.RoutePlaceholder

/**
 * The route of add and edit (ux.md §5.14, §5.15): Edit(id?). A placeholder until the add and edit
 * package (build plan 3.6) replaces this body.
 */
fun EntryProviderScope<Route>.editNav(graph: AppGraph, nav: Navigator) {
    entry<Route.Edit> { route -> RoutePlaceholder(route, nav) }
}
