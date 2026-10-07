package io.github.dilshodbekmx.passwordvault.ui.settings

import androidx.navigation3.runtime.EntryProviderScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.nav.HelpPage
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.ui.shell.RoutePlaceholder
import io.github.dilshodbekmx.passwordvault.ui.shell.openAction

/**
 * The routes of Help (ux.md §5.22): Help(page) and Licences. Placeholders until the help package
 * (build plan 7.3) replaces this body; each page's placeholder opens the next page.
 */
fun EntryProviderScope<Route>.helpNav(graph: AppGraph, nav: Navigator) {
    entry<Route.Help> { route ->
        val next = HelpPage.entries.getOrNull(route.page.ordinal + 1)
        RoutePlaceholder(route, nav, if (next == null) listOf(openAction(nav, Route.Licences)) else listOf(openAction(nav, Route.Help(next))))
    }
    entry<Route.Licences> { route -> RoutePlaceholder(route, nav) }
}
