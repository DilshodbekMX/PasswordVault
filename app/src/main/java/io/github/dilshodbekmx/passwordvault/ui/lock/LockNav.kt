package io.github.dilshodbekmx.passwordvault.ui.lock

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.EntryProviderScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.nav.NavState
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route

/**
 * The lock's one route, [Route.Locked], whose screen `LockUiState` decides (lock.md §13.1, §13.2):
 * the PIN pad, the fingerprint-only screen, the master-password screen, "This is an older master
 * password", or "Your passwords can't be opened"; each also as the screen of a vault put aside, with
 * its line. Over it, the "Forgot master password?" sheet and the questions asked once more. Its
 * [LockViewModel] belongs to the route's entry, so a rotation keeps it and an unlock clears it
 * (architecture.md §5.5 step 5). "Restore from a backup file" opens [Route.Restore] over it.
 */
fun EntryProviderScope<Route>.lockNav(graph: AppGraph, nav: Navigator) {
    entry<Route.Locked> { LockRoute(graph, nav) }
}

/**
 * The notices after unlock (lock.md §13.4; ux.md §5.9), one at a time over the unlocked screens:
 * `UnlockedScaffold` calls this while the app is unlocked. A notice is a dialog whose answers go to
 * the lock and may open a screen; "Choose a PIN" with the proof of this unlock opens the "Choose a
 * new PIN" screen instead and is answered when the PIN screens are left; "Type master password"
 * opens the master-password prompt. [NoticesViewModel] belongs to the activity.
 */
@Composable
fun LockNotices(graph: AppGraph, nav: Navigator) {
    val vm = viewModel { NoticesViewModel(graph.lock, graph.platform.clock, graph.appScope) }
    NoticesHost(vm, nav)
}

/**
 * What [LockNotices] draws for [vm]: the prompt while it is open, else the first notice. With a
 * [NavState] it knows whether the PIN screens are shown, for "Choose a PIN"; with another
 * [Navigator] that notice is answered as soon as its screen is open.
 */
@Composable
fun NoticesHost(vm: NoticesViewModel, nav: Navigator) {
    val notice by vm.notice.collectAsStateWithLifecycle()
    val prompt by vm.prompt.collectAsStateWithLifecycle()
    val choosePin by vm.choosePin.collectAsStateWithLifecycle()
    val onPinScreens: Boolean? = (nav as? NavState)?.let { it.visible.lastOrNull() in PIN_SCREENS }
    choosePin?.let { id ->
        LaunchedEffect(id, onPinScreens) { vm.choosePinStep(id, onPinScreens) { nav.go(Route.CreatePin) } }
    }
    val shownPrompt = prompt
    val shownNotice = notice
    when {
        shownPrompt != null -> PromptDialog(shownPrompt, vm.password, onCheck = vm::submitPrompt, onCancel = vm::closePrompt)
        shownNotice != null -> NoticeDialog(
            ui = shownNotice,
            onAnswer = { button ->
                vm.answer(shownNotice.id, button.answer)
                button.route?.let(nav::go)
            },
            onDismiss = { vm.answer(shownNotice.id, shownNotice.dismiss) },
        )
    }
}

/**
 * The lock route's screens for [vm]: the screen [LockViewModel.screen] names (nothing before the lock
 * has shown one), the sheet while it is open, and the question asked once more. [onRestore] opens
 * the restore screens after "Restore from a backup file".
 */
@Composable
fun LockScreens(vm: LockViewModel, onRestore: () -> Unit) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val sheet by vm.sheet.collectAsStateWithLifecycle()
    val confirm by vm.confirm.collectAsStateWithLifecycle()
    val onAction: (LockAction) -> Unit = { action ->
        vm.onAction(action)
        if (action == LockAction.Restore) onRestore()
    }
    when (val ui = screen) {
        null -> Box(Modifier.fillMaxSize())
        is PadUi -> PadScreen(ui, onDigit = vm::onDigit, onDelete = vm::onDelete, onClear = vm::onClear, onAction = onAction)
        is FingerprintOnlyUi -> FingerprintOnlyScreen(ui, onAction)
        is MasterPasswordUi -> MasterPasswordScreen(vm, ui, onAction = onAction)
        is OlderPasswordUi -> OlderPasswordScreen(ui, onAction)
        is UnreadableUi -> UnreadableScreen(ui, onAction)
    }
    sheet?.let { ForgotSheet(it, onAction) }
    confirm?.let { LockConfirmDialog(it, onAction) }
}

/** The lock route: its ViewModel from the route's entry, over the app's lock, clock and scope. */
@Composable
private fun LockRoute(graph: AppGraph, nav: Navigator) {
    val vm = viewModel { LockViewModel(graph.lock, graph.platform.clock, graph.appScope) }
    LockScreens(vm, onRestore = { nav.go(Route.Restore) })
}

/** The screens of choosing a PIN (build plan 4.3): while one of them is shown, a "Choose a PIN" notice waits. */
private val PIN_SCREENS: Set<Route> = setOf(Route.CreatePin, Route.ConfirmPin, Route.FingerprintOffer, Route.BackupsExplained)
