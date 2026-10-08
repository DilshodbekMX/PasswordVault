package io.github.dilshodbekmx.passwordvault.ui.transfer

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.EntryProviderScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route

/**
 * The routes of backup and restore (ux.md §5.2, §5.10, §5.19; build plan 5.2): `Backup` (Settings ›
 * Make a backup, "Make a backup now?" and the other offers), `Restore` (from Welcome, and from the
 * lock screen's "Forgot master password?" sheet and "Your passwords can't be opened") and
 * `OldPasswords` (Settings › Old passwords).
 *
 * Each screen's ViewModel lives in the route's own store: a rotation or a language change keeps
 * what it holds, and a lock clears it (architecture.md §5.5). A route key holds no id: an
 * unfinished backup or restore is the process's file task (`FileTaskStore.task`), which each screen
 * looks at when it is opened again; the store opens no screen by itself.
 *
 * The backup screen is made with the master-password entry of "Make a backup now?" when its answer
 * opened it ([BackupOffer]). [BackupOffer.of] is called here, as the app's routes are registered, so
 * that it watches the lock from the first screen on and sees every offer the lock shows.
 */
fun EntryProviderScope<Route>.backupNav(graph: AppGraph, nav: Navigator) {
    val offers = BackupOffer.of(graph)
    entry<Route.Backup> {
        val vm = viewModel { BackupViewModel.create(graph, offered = offers.take()) }
        BackupScreen(vm, nav)
    }
    entry<Route.Restore> {
        val vm = viewModel { RestoreViewModel.create(graph) }
        RestoreScreen(vm, nav)
    }
    entry<Route.OldPasswords> {
        val vm = viewModel { OldPasswordsViewModel.create(graph) }
        OldPasswordsScreen(vm, nav)
    }
}
