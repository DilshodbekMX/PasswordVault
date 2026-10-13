package io.github.dilshodbekmx.passwordvault.ui.security

import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.EntryProviderScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.i18n.AppLanguages
import io.github.dilshodbekmx.passwordvault.i18n.SortKeys
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route

/**
 * The route of the guided change (ux.md §5.17; build plan 7.2): `GuidedChange(entryId)`, opened by
 * "Change" on a row of the Security tab's group list, and by "Next: Facebook" at the end of a change,
 * in place of itself.
 *
 * The screen's ViewModel lives in the route's own store: a rotation or a language change keeps what
 * it holds, and a lock clears it (architecture.md §5.5). The route key holds only the account's id;
 * the change itself is in the account, so the screen shows the step the account is at whenever it
 * opens. [GuidedChangeMemory.of] is called here, as the app's routes are registered, so that it sees
 * every lock from the first screen on (a lock ends the session of the backup line).
 */
fun EntryProviderScope<Route>.changeNav(graph: AppGraph, nav: Navigator) {
    val memory = GuidedChangeMemory.of(graph)
    entry<Route.GuidedChange> { route ->
        // "Next" offers the group's accounts in the group list's order: A to Z in the screen's language.
        val configuration = LocalConfiguration.current
        val locale = remember(configuration) { AppLanguages.screenLocale(AppLanguages.localesOf(configuration)) }
        val vm = viewModel {
            val keys = SortKeys(locale)
            GuidedChangeViewModel.create(graph, route.entryId, memory, Comparator(keys::compare))
        }
        GuidedChangeScreen(vm, nav)
    }
}
