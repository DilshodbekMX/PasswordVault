package io.github.dilshodbekmx.passwordvault.ui.transfer

import androidx.navigation3.runtime.EntryProviderScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.ui.shell.RoutePlaceholder

/**
 * The route of the export (ux.md §5.20): Export(step). A placeholder until the export package
 * (build plan 5.3) replaces this body.
 */
fun EntryProviderScope<Route>.exportNav(graph: AppGraph, nav: Navigator) {
    entry<Route.Export> { route -> RoutePlaceholder(route, nav) }
}
