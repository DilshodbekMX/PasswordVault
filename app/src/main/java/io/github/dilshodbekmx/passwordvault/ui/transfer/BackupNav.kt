package io.github.dilshodbekmx.passwordvault.ui.transfer

import androidx.navigation3.runtime.EntryProviderScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.ui.shell.RoutePlaceholder

/**
 * The routes of backup and restore (ux.md §5.2, §5.10, §5.19): Backup, Restore (from Welcome and
 * from the "Forgot master password?" sheet) and OldPasswords. Placeholders until the backup package
 * (build plan 5.2) replaces this body.
 */
fun EntryProviderScope<Route>.backupNav(graph: AppGraph, nav: Navigator) {
    entry<Route.Backup> { route -> RoutePlaceholder(route, nav) }
    entry<Route.Restore> { route -> RoutePlaceholder(route, nav) }
    entry<Route.OldPasswords> { route -> RoutePlaceholder(route, nav) }
}
