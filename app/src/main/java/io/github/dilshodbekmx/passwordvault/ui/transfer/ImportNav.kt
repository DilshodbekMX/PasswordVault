package io.github.dilshodbekmx.passwordvault.ui.transfer

import androidx.navigation3.runtime.EntryProviderScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.ui.shell.RoutePlaceholder
import io.github.dilshodbekmx.passwordvault.ui.shell.openAction

/**
 * The routes of the import (ux.md §5.21): Import(step) and ColumnMapping. Placeholders until the
 * import package (build plan 5.4) replaces this body.
 */
fun EntryProviderScope<Route>.importNav(graph: AppGraph, nav: Navigator) {
    entry<Route.Import> { route -> RoutePlaceholder(route, nav, listOf(openAction(nav, Route.ColumnMapping))) }
    entry<Route.ColumnMapping> { route -> RoutePlaceholder(route, nav) }
}
