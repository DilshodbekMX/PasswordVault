package io.github.dilshodbekmx.passwordvault.ui.transfer

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.EntryProviderScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route

/**
 * The route of the export (ux.md §5.20; build plan 5.3): `Export(step)`, opened from Settings ›
 * Backup & transfer › Export as `Export()`.
 *
 * The screen's ViewModel lives in the route's own store: a rotation or a language change keeps the
 * step, a lock clears it (architecture.md §5.5). Where an export cut short by a lock continues is
 * not the route's [Route.Export.step] but the file task the process's `FileTaskStore` recorded, which
 * also survives a new process: the screen looks at `FileTaskStore.task` when it is made (ux.md §4,
 * architecture.md §3.5). So the route never skips the warning or the master password, whatever its
 * step says.
 */
fun EntryProviderScope<Route>.exportNav(graph: AppGraph, nav: Navigator) {
    entry<Route.Export> {
        val vm = viewModel { ExportViewModel.create(graph) }
        ExportScreen(vm, nav)
    }
}
