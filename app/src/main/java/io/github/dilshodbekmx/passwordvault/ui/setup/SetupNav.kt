package io.github.dilshodbekmx.passwordvault.ui.setup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation3.runtime.EntryProviderScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.ui.shell.RoutePlaceholder
import io.github.dilshodbekmx.passwordvault.ui.shell.openAction
import io.github.dilshodbekmx.passwordvault.ui.shell.placeholderAction
import kotlinx.coroutines.launch

/**
 * The routes of the setup screens (ux.md §5.1 to §5.5): Welcome, CreateMasterPassword, WriteDown,
 * TypeAgain. Placeholders until the setup screens package (build plan 3.3) replaces this body.
 */
fun EntryProviderScope<Route>.setupNav(graph: AppGraph, nav: Navigator) {
    entry<Route.Welcome> { route ->
        RoutePlaceholder(
            route,
            nav,
            listOf(openAction(nav, Route.CreateMasterPassword), openAction(nav, Route.Restore), openAction(nav, Route.Language)),
        )
    }
    entry<Route.CreateMasterPassword> { route -> RoutePlaceholder(route, nav, listOf(openAction(nav, Route.WriteDown))) }
    entry<Route.WriteDown> { route -> RoutePlaceholder(route, nav, listOf(openAction(nav, Route.TypeAgain))) }
    entry<Route.TypeAgain> { route -> TypeAgainPlaceholder(graph, nav, route) }
}

/** "Type it again": creates the vault through the lock, which then unlocks (lock.md F1 step 3). */
@Composable
private fun TypeAgainPlaceholder(graph: AppGraph, nav: Navigator, route: Route) {
    val scope = rememberCoroutineScope()
    RoutePlaceholder(
        route,
        nav,
        listOf(
            placeholderAction("createVault", R.string.shell_placeholder_create_vault) {
                scope.launch { graph.lock.createVault(CharArray(0), CharArray(0), null) }
            },
        ),
    )
}
