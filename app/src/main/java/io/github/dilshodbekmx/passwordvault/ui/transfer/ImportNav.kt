package io.github.dilshodbekmx.passwordvault.ui.transfer

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.EntryProviderScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route

/**
 * The routes of the import (ux.md §5.21, formats.md §8; build plan 5.4):
 * - `Import(ImportStep.SOURCES)`: the sources and the Google help ([ImportSourcesScreen]); a chosen
 *   file replaces it with the file's step.
 * - `Import(ImportStep.FILE)`: the chosen file, read, previewed, imported, and the question about
 *   it ([ImportScreen]); for a backup file its master password and its accounts added.
 * - `ColumnMapping`: what each column of a CSV file holds ([ColumnMappingScreen]).
 *
 * Each has its own ViewModel, which Navigation 3 clears when the route leaves the back stack or the
 * app locks (architecture.md §5.5 step 5). The file's step reads the file again when it comes back:
 * the task's document is recorded outside the vault (ux.md §4), and what the user chose is kept in
 * memory, not secret ([ImportMemory]). A step number this version does not know shows the sources.
 */
fun EntryProviderScope<Route>.importNav(graph: AppGraph, nav: Navigator) {
    entry<Route.Import> { route ->
        if (route.step == ImportStep.FILE) {
            val vm = viewModel { ImportViewModel.create(graph) }
            ImportScreen(vm, nav)
        } else {
            val vm = viewModel { ImportSourcesViewModel.create(graph) }
            ImportSourcesScreen(vm, nav)
        }
    }
    entry<Route.ColumnMapping> {
        val vm = viewModel { ImportColumnsViewModel.create(graph) }
        ColumnMappingScreen(vm, nav)
    }
}
