package io.github.dilshodbekmx.passwordvault.ui.settings

import androidx.navigation3.runtime.EntryProviderScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.ui.shell.RoutePlaceholder
import io.github.dilshodbekmx.passwordvault.ui.shell.openAction

/**
 * The routes of changing and replacing the master password (ux.md §5.18, §5.24):
 * ChangeMasterPassword, and NewMasterPassword ("I don't remember it"). Placeholders until the
 * master-password package (build plan 5.5) replaces this body.
 */
fun EntryProviderScope<Route>.masterPasswordNav(graph: AppGraph, nav: Navigator) {
    entry<Route.ChangeMasterPassword> { route -> RoutePlaceholder(route, nav, listOf(openAction(nav, Route.NewMasterPassword))) }
    entry<Route.NewMasterPassword> { route -> RoutePlaceholder(route, nav) }
}
