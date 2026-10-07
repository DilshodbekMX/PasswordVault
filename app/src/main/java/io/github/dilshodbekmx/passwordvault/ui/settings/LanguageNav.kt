package io.github.dilshodbekmx.passwordvault.ui.settings

import androidx.navigation3.runtime.EntryProviderScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.ui.shell.RoutePlaceholder

/**
 * The route of the language switch (i18n.md §1.4.5; ux.md §5.1, §5.18): Language, from Welcome's
 * globe button and from Settings. A placeholder until the language switch package (build plan 6.1)
 * replaces this body.
 */
fun EntryProviderScope<Route>.languageNav(graph: AppGraph, nav: Navigator) {
    entry<Route.Language> { route -> RoutePlaceholder(route, nav) }
}
