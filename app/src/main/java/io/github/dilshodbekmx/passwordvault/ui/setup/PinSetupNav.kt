package io.github.dilshodbekmx.passwordvault.ui.setup

import androidx.navigation3.runtime.EntryProviderScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.ui.shell.RoutePlaceholder
import io.github.dilshodbekmx.passwordvault.ui.shell.openAction
import io.github.dilshodbekmx.passwordvault.ui.shell.placeholderAction

/**
 * The routes of PIN and fingerprint setup, after the vault exists (ux.md §5.6 to §5.8): CreatePin,
 * ConfirmPin, FingerprintOffer, BackupsExplained. Placeholders until the PIN and fingerprint setup
 * package (build plan 4.3) replaces this body.
 */
fun EntryProviderScope<Route>.pinSetupNav(graph: AppGraph, nav: Navigator) {
    entry<Route.CreatePin> { route -> RoutePlaceholder(route, nav, listOf(openAction(nav, Route.ConfirmPin))) }
    entry<Route.ConfirmPin> { route -> RoutePlaceholder(route, nav, listOf(openAction(nav, Route.FingerprintOffer))) }
    entry<Route.FingerprintOffer> { route -> RoutePlaceholder(route, nav, listOf(openAction(nav, Route.BackupsExplained))) }
    entry<Route.BackupsExplained> { route ->
        RoutePlaceholder(route, nav, listOf(placeholderAction("finish", R.string.shell_placeholder_finish) { nav.tab(Route.Passwords) }))
    }
}
