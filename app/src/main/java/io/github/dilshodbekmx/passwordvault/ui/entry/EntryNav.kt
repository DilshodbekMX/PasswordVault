package io.github.dilshodbekmx.passwordvault.ui.entry

import androidx.navigation3.runtime.EntryProviderScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.ui.shell.RoutePlaceholder
import io.github.dilshodbekmx.passwordvault.ui.shell.openAction

/**
 * The routes of the entry detail (ux.md §5.12, §5.13): Entry(id) and BigLetters(id, field).
 * Placeholders until the entry detail package (build plan 3.7) replaces this body.
 */
fun EntryProviderScope<Route>.entryNav(graph: AppGraph, nav: Navigator) {
    entry<Route.Entry> { route ->
        RoutePlaceholder(route, nav, listOf(openAction(nav, Route.Edit(route.id)), openAction(nav, Route.BigLetters(route.id, "password"))))
    }
    entry<Route.BigLetters> { route -> RoutePlaceholder(route, nav) }
}
