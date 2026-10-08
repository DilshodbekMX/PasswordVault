package io.github.dilshodbekmx.passwordvault.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.EntryProviderScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route

/**
 * The routes of Settings (ux.md §5.18; build plan 4.3): the Settings list ([SettingsScreen]), the App
 * lock page ([AppLockPage]), "Change PIN" or "Set a PIN" ([ChangePinScreen]) and the theme dialog
 * ([ThemeDialog]). Each has its own view model, which Navigation 3 clears when the route leaves the
 * back stack or the app locks (architecture.md §5.5 step 5).
 *
 * The theme dialog is a route of its own (it is not kept across a lock, `NavState.keptAcrossLock`):
 * it is drawn over the Settings list, as a dialog opened from the list would be.
 */
fun EntryProviderScope<Route>.settingsNav(graph: AppGraph, nav: Navigator) {
    entry<Route.Settings> { SettingsRoute(graph, nav) }
    entry<Route.AppLock> { AppLockRoute(graph, nav) }
    entry<Route.ChangePin> { ChangePinRoute(graph, nav) }
    entry<Route.Theme> { ThemeRoute(graph, nav) }
}

@Composable
private fun SettingsRoute(graph: AppGraph, nav: Navigator) {
    val vm = viewModel { SettingsViewModel.from(graph) }
    SettingsScreen(vm, nav)
}

@Composable
private fun AppLockRoute(graph: AppGraph, nav: Navigator) {
    val vm = viewModel { SettingsViewModel.from(graph) }
    AppLockPage(vm, nav)
}

@Composable
private fun ChangePinRoute(graph: AppGraph, nav: Navigator) {
    val vm = viewModel { ChangePinViewModel.from(graph) }
    ChangePinScreen(vm, nav)
}

@Composable
private fun ThemeRoute(graph: AppGraph, nav: Navigator) {
    val vm = viewModel { SettingsViewModel.from(graph) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    SettingsScreen(vm, nav)
    ThemeDialog(
        current = ui.theme,
        onChoose = { choice ->
            vm.setTheme(choice)
            nav.back()
        },
        onDismiss = { nav.back() },
    )
}
