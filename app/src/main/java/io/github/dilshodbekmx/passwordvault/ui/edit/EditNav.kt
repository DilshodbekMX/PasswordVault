package io.github.dilshodbekmx.passwordvault.ui.edit

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.EntryProviderScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route

/**
 * The route of add and edit (ux.md §5.14, §5.15; build plan 3.6): `Edit(id)`, "New account" when
 * the id is null, otherwise "Edit account" for that entry. Its [EditViewModel] lives in the route's
 * own ViewModel store, so a rotation or a language change keeps everything typed; a lock clears it
 * (architecture.md §5.5), and the form comes back from its draft after the unlock.
 */
fun EntryProviderScope<Route>.editNav(graph: AppGraph, nav: Navigator) {
    entry<Route.Edit> { route ->
        val vm = viewModel { EditViewModel.create(graph, route.id) }
        EditScreen(vm, nav)
    }
}
