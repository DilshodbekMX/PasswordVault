package io.github.dilshodbekmx.passwordvault.ui.settings

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.EntryProviderScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupOffer

/**
 * The routes of changing and replacing the master password (ux.md §5.18 "Change master password",
 * §5.24; lock.md F8, F11; build plan 5.5):
 *
 * - [Route.ChangeMasterPassword], Settings › App lock › "Change master password": the current master
 *   password, the new one, the PIN, the change and the new backup ([ChangeMasterPasswordViewModel]).
 * - [Route.NewMasterPassword]: a new master password while the old one is forgotten, and "Finish now"
 *   once it is ready ([NewMasterPasswordViewModel]). Opened by "I don't remember it" on the first,
 *   "I forgot it" on the practice banner, "Choose a new master password" on the wrong-tries notice,
 *   "Finish now" on the banner and on the notice, and after the lock-screen route
 *   ([NewMasterPasswordAfterUnlock]).
 *
 * Each route's ViewModel lives in the route's own store: a rotation or a language change keeps what
 * it holds (lock.md §11), and the route leaving the back stack clears it, which ends its flow (the
 * proof of F11 step 3 with it). A route key holds no secret; a flow cut short by a lock starts again.
 * [BackupOffer.of] watches the lock from here on as well, so that the backup that "Back up now" opens
 * takes the proof handed to it.
 */
fun EntryProviderScope<Route>.masterPasswordNav(graph: AppGraph, nav: Navigator) {
    BackupOffer.of(graph)
    entry<Route.ChangeMasterPassword> {
        val vm = viewModel { ChangeMasterPasswordViewModel.from(graph) }
        ChangeMasterPasswordScreen(vm, nav)
    }
    entry<Route.NewMasterPassword> {
        val vm = viewModel { NewMasterPasswordViewModel.from(graph) }
        NewMasterPasswordScreen(vm, nav)
    }
}
