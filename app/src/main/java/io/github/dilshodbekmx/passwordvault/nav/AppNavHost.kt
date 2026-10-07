package io.github.dilshodbekmx.passwordvault.nav

import android.os.Bundle
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.defaultPopTransitionSpec
import androidx.navigation3.ui.defaultPredictivePopTransitionSpec
import androidx.navigation3.ui.defaultTransitionSpec
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.ui.edit.editNav
import io.github.dilshodbekmx.passwordvault.ui.entry.entryNav
import io.github.dilshodbekmx.passwordvault.ui.list.listNav
import io.github.dilshodbekmx.passwordvault.ui.lock.lockNav
import io.github.dilshodbekmx.passwordvault.ui.security.changeNav
import io.github.dilshodbekmx.passwordvault.ui.security.securityNav
import io.github.dilshodbekmx.passwordvault.ui.settings.helpNav
import io.github.dilshodbekmx.passwordvault.ui.settings.languageNav
import io.github.dilshodbekmx.passwordvault.ui.settings.masterPasswordNav
import io.github.dilshodbekmx.passwordvault.ui.settings.settingsNav
import io.github.dilshodbekmx.passwordvault.ui.setup.pinSetupNav
import io.github.dilshodbekmx.passwordvault.ui.setup.setupNav
import io.github.dilshodbekmx.passwordvault.ui.shell.UnlockedScaffold
import io.github.dilshodbekmx.passwordvault.ui.transfer.backupNav
import io.github.dilshodbekmx.passwordvault.ui.transfer.exportNav
import io.github.dilshodbekmx.passwordvault.ui.transfer.importNav
import kotlinx.coroutines.launch

/**
 * The app's navigation (architecture.md §3.6): the one [NavDisplay], fed by one registration
 * function per package, each in its own route file, and the back stack that [NavState] shows for
 * `LockManager.state`. The unlocked screens sit in [UnlockedScaffold], whose bottom bar shows only
 * while the app is unlocked. Nobody but the shell edits this file; a new route is an interface
 * request.
 */
@Composable
fun AppNavHost(graph: AppGraph) {
    val nav = rememberNavState(graph.lock)
    UnlockedScaffold(graph, nav) { padding ->
        NavHostDisplay(nav, Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            setupNav(graph, nav)
            pinSetupNav(graph, nav)
            lockNav(graph, nav)
            listNav(graph, nav)
            entryNav(graph, nav)
            editNav(graph, nav)
            settingsNav(graph, nav)
            masterPasswordNav(graph, nav)
            languageNav(graph, nav)
            helpNav(graph, nav)
            backupNav(graph, nav)
            exportNav(graph, nav)
            importNav(graph, nav)
            securityNav(graph, nav)
            changeNav(graph, nav)
        }
    }
}

/**
 * The [NavState] of this activity, which follows [lock]: kept in a ViewModel of the activity, so
 * that a recreation (rotation, a language change) keeps it in memory, and in its saved state, so
 * that a new process gets its route ids back (architecture.md §5.1).
 */
@Composable
fun rememberNavState(lock: LockManager): NavState = viewModel { NavViewModel(lock, createSavedStateHandle()) }.nav

/**
 * The one `NavDisplay` of the app, showing [NavState.visible] with the screens [entries] register.
 *
 * - Each screen gets its own ViewModel store and saved state (the two entry decorators). When the
 *   app locks, the unlocked screens leave the display and Navigation 3 clears both
 *   (architecture.md §5.5 step 5); the ids stay in [nav]. After a lock in the background that
 *   happens when the activity starts again ([NavState] says why).
 * - Each screen's root carries the test tag `pv.route.<name>` ([Route.name]), exposed as its
 *   resource id, so that tests and the emulator scripts find the route whatever the language.
 * - A switch between the setup, locked and unlocked stacks is not animated, so the lock screen
 *   never slides in over the passwords; moves within a stack use Navigation 3's defaults.
 */
@Composable
fun NavHostDisplay(nav: NavState, modifier: Modifier = Modifier, entries: EntryProviderScope<Route>.() -> Unit) {
    val provider = remember(nav) {
        entryProvider<Route> {
            entry<Route.Starting> { Box(Modifier.fillMaxSize()) }
            entries()
        }
    }
    val forward = remember { defaultTransitionSpec<Route>() }
    val pop = remember { defaultPopTransitionSpec<Route>() }
    val predictive = remember { defaultPredictivePopTransitionSpec<Route>() }
    NavDisplay(
        backStack = nav.visible,
        modifier = modifier,
        onBack = { nav.back() },
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
        transitionSpec = { if (sameStack()) forward(this) else NO_TRANSITION },
        popTransitionSpec = { if (sameStack()) pop(this) else NO_TRANSITION },
        predictivePopTransitionSpec = { edge -> if (sameStack()) predictive(this, edge) else NO_TRANSITION },
        entryProvider = { key -> tagged(provider(key)) },
    )
}

/** The test tag of [route]'s screen. */
fun routeTag(route: Route): String = "pv.route.${route.name}"

private val NO_TRANSITION: ContentTransform = EnterTransition.None togetherWith ExitTransition.None

/** Whether both scenes belong to one back stack: each stack has its own root (Starting, Welcome, Locked, Passwords). */
private fun AnimatedContentTransitionScope<Scene<Route>>.sameStack(): Boolean = rootOf(initialState) == rootOf(targetState)

private fun rootOf(scene: Scene<Route>): Any? = (scene.previousEntries.firstOrNull() ?: scene.entries.firstOrNull())?.contentKey

private fun tagged(entry: NavEntry<Route>): NavEntry<Route> = NavEntry(navEntry = entry) { key: Route ->
    Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.testTag(routeTag(key))) {
        entry.Content()
    }
}

/**
 * Holds the activity's [NavState] (architecture.md §5.1, "Which screen is shown"): it follows the
 * lock from its creation, on `Dispatchers.Main.immediate`, so the back stack changes as soon as the
 * lock state does, even while the activity is stopped; and it keeps the three stacks in the saved
 * state, route keys only (§5.6).
 */
class NavViewModel(lock: LockManager, handle: SavedStateHandle) : ViewModel() {
    val nav: NavState = NavState.decode(handle.get<Bundle>(KEY)?.getString(KEY))

    init {
        handle.setSavedStateProvider(KEY) { Bundle().apply { putString(KEY, nav.encode()) } }
        viewModelScope.launch { nav.follow(lock) }
    }

    private companion object {
        const val KEY = "pv.nav"
    }
}
