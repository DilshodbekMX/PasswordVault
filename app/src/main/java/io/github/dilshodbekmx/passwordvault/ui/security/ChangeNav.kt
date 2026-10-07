package io.github.dilshodbekmx.passwordvault.ui.security

import androidx.navigation3.runtime.EntryProviderScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.ui.shell.RoutePlaceholder

/**
 * The route of the guided change (ux.md §5.17): GuidedChange(entryId). A placeholder until the
 * guided change package (build plan 7.2) replaces this body.
 */
fun EntryProviderScope<Route>.changeNav(graph: AppGraph, nav: Navigator) {
    entry<Route.GuidedChange> { route -> RoutePlaceholder(route, nav) }
}
