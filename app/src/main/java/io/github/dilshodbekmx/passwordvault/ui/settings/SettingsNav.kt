package io.github.dilshodbekmx.passwordvault.ui.settings

import androidx.navigation3.runtime.EntryProviderScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.nav.HelpPage
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.ui.shell.RoutePlaceholder
import io.github.dilshodbekmx.passwordvault.ui.shell.openAction
import io.github.dilshodbekmx.passwordvault.ui.shell.placeholderAction

/**
 * The routes of Settings (ux.md §5.18): Settings, the App lock page, ChangePin and the Theme dialog.
 * Placeholders until the PIN and fingerprint setup and Settings package (build plan 4.3) replaces
 * this body. The placeholder of Settings opens every route reached from Settings, and "Lock now".
 */
fun EntryProviderScope<Route>.settingsNav(graph: AppGraph, nav: Navigator) {
    entry<Route.Settings> { route ->
        RoutePlaceholder(
            route,
            nav,
            listOf(
                openAction(nav, Route.AppLock),
                openAction(nav, Route.Backup),
                openAction(nav, Route.Export()),
                openAction(nav, Route.Import()),
                openAction(nav, Route.OldPasswords),
                openAction(nav, Route.Language),
                openAction(nav, Route.Theme),
                openAction(nav, Route.Help(HelpPage.PROTECTION)),
                openAction(nav, Route.Licences),
                placeholderAction("lockNow", R.string.shell_placeholder_lock_now) { graph.session.lockNow() },
            ),
        )
    }
    entry<Route.AppLock> { route ->
        RoutePlaceholder(
            route,
            nav,
            listOf(openAction(nav, Route.ChangePin), openAction(nav, Route.CreatePin), openAction(nav, Route.ChangeMasterPassword)),
        )
    }
    entry<Route.ChangePin> { route -> RoutePlaceholder(route, nav) }
    entry<Route.Theme> { route -> RoutePlaceholder(route, nav) }
}
