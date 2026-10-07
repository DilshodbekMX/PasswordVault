package io.github.dilshodbekmx.passwordvault.ui.setup

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.EntryProviderScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.lock.LockIntent
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route

/**
 * The routes of the setup screens (ux.md §5.1 to §5.5; build plan 3.3): Welcome,
 * CreateMasterPassword, WriteDown and TypeAgain. The last makes the vault through
 * `LockManager.createVault`; the lock then unlocks, and `NavState` leaves setup by itself.
 *
 * The three steps share one [SetupViewModel], kept by the activity ([setupViewModel]): the password
 * chosen in the first is shown by the second and checked by the third, and a rotation or a language
 * change keeps it (lock.md §11). Each step also has a small view model of its own entry
 * ([SetupStepScope]), which Navigation 3 clears when the step leaves the back stack; it then tells
 * the shared one what to drop ([SetupViewModel.stepLeft]). A step after the first that finds no
 * chosen password (a new process, whose saved back stack came back without the words) goes back,
 * and the first step shows new words as an interrupted setup (ux.md §5.5).
 */
fun EntryProviderScope<Route>.setupNav(graph: AppGraph, nav: Navigator) {
    entry<Route.Welcome> { WelcomeRoute(graph, nav) }
    entry<Route.CreateMasterPassword> { CreateMasterPasswordRoute(graph, nav) }
    entry<Route.WriteDown> { WriteDownRoute(graph, nav) }
    entry<Route.TypeAgain> { TypeAgainRoute(graph, nav) }
}

/** The resource ids (test tags) of setup's controls, for tests and the emulator scripts; they do not depend on the language. */
object SetupTags {
    /** Welcome's "Start", which opens CreateMasterPassword (the shell's `pv.open.<Route>` form). */
    const val START = "pv.open.CreateMasterPassword"
    const val RESTORE = "pv.open.Restore"
    const val LANGUAGE = "pv.open.Language"

    /** "Open my previous passwords (locked on …)" for the vault [vaultId]. */
    fun aside(vaultId: String): String = "pv.setup.aside.$vaultId"

    const val INTERRUPTED = "pv.setup.interrupted"
    const val WORDS = "pv.setup.words"

    /** The whole suggested password as it is typed, on step 1. */
    const val PASSPHRASE = "pv.setup.passphrase"

    /** "Use these words", which opens WriteDown. */
    const val USE_WORDS = "pv.open.WriteDown"
    const val OTHER_WORDS = "pv.setup.otherWords"
    const val TYPE_OWN = "pv.setup.typeOwn"
    const val SHOW_WORDS = "pv.setup.showWords"
    const val OWN_FIELD = "pv.setup.own"
    const val OWN_MESSAGE = "pv.setup.ownMessage"
    const val RULE_LENGTH = "pv.setup.rule.length"
    const val RULE_HARD = "pv.setup.rule.hard"
    const val USE_OWN = "pv.setup.useOwn"
    const val REUSE_YES = "pv.setup.reuse.yes"
    const val REUSE_NO = "pv.setup.reuse.no"

    /** The chosen password on step 2. */
    const val CHOSEN = "pv.setup.chosen"
    const val BIG_LETTERS = "pv.setup.bigLetters"
    const val BIG_LETTERS_CLOSE = "pv.setup.bigLetters.close"
    const val WROTE = "pv.setup.wrote"

    /** Step 2's "Next", which opens TypeAgain. */
    const val NEXT = "pv.open.TypeAgain"

    const val AGAIN_FIELD = "pv.setup.again"
    const val AGAIN_MESSAGE = "pv.setup.againMessage"
    const val KEYBOARD_HINT = "pv.setup.keyboardHint"
    const val SETTING_UP = "pv.setup.settingUp"

    /** Step 3's "Next" (or "Try again"): the vault is made. */
    const val CREATE = "pv.action.createVault"
    const val SHOW_AGAIN = "pv.setup.showAgain"
}

@Composable
private fun WelcomeRoute(graph: AppGraph, nav: Navigator) {
    val state by graph.lock.state.collectAsStateWithLifecycle()
    val status by graph.lock.status.collectAsStateWithLifecycle()
    val move = rememberMoveOnce(nav)
    WelcomeScreen(
        aside = asideVaults(state, status),
        onStart = { move.go(Route.CreateMasterPassword) },
        onOpenAside = { vaultId -> graph.lock.send(LockIntent.OpenAside(vaultId)) },
        onRestore = { move.go(Route.Restore) },
        onLanguage = { move.go(Route.Language) },
    )
}

@Composable
private fun CreateMasterPasswordRoute(graph: AppGraph, nav: Navigator) {
    val setup = setupViewModel(graph)
    SetupStepScope(SetupStep.CREATE, setup)
    LaunchedEffect(setup) { setup.showWords() }
    val state by setup.state.collectAsStateWithLifecycle()
    val move = rememberMoveOnce(nav)
    CreateMasterPasswordScreen(
        state = state,
        own = setup.own,
        onUseWords = { if (setup.useTheseWords()) move.go(Route.WriteDown) },
        onOtherWords = setup::showOtherWords,
        onTypeOwn = setup::typeOwn,
        onShowWords = setup::showSuggested,
        onUseOwn = setup::submitOwn,
        onReuseAnswer = { usedElsewhere -> if (setup.answerReuse(usedElsewhere)) move.go(Route.WriteDown) },
        onReuseDismiss = setup::dismissReuse,
    )
}

@Composable
private fun WriteDownRoute(graph: AppGraph, nav: Navigator) {
    val setup = setupViewModel(graph)
    SetupStepScope(SetupStep.WRITE_DOWN, setup)
    val state by setup.state.collectAsStateWithLifecycle()
    val chosen = state.chosen
    if (chosen == null) {
        BackToTheFirstStep(graph.lock, nav)
        return
    }
    val move = rememberMoveOnce(nav)
    WriteDownScreen(
        chosen = chosen,
        wroteItDown = state.wroteItDown,
        onWroteItDown = setup::setWroteItDown,
        onNext = { move.go(Route.TypeAgain) },
    )
}

@Composable
private fun TypeAgainRoute(graph: AppGraph, nav: Navigator) {
    val setup = setupViewModel(graph)
    SetupStepScope(SetupStep.TYPE_AGAIN, setup)
    val state by setup.state.collectAsStateWithLifecycle()
    if (state.chosen == null) {
        BackToTheFirstStep(graph.lock, nav)
        return
    }
    val move = rememberMoveOnce(nav)
    TypeAgainScreen(
        state = state,
        field = setup.again,
        onNext = setup::submitAgain,
        onShowAgain = { move.back() },
    )
}

/**
 * The view model of the setup steps, kept by the activity rather than by one route's entry, so that
 * the three steps share it and a recreation keeps it. Outside an activity (a preview) it falls back
 * to the composition's owner.
 */
@Composable
internal fun setupViewModel(graph: AppGraph): SetupViewModel {
    val owner = LocalActivity.current as? ViewModelStoreOwner ?: checkNotNull(LocalViewModelStoreOwner.current)
    return viewModel(viewModelStoreOwner = owner, key = SETUP_VIEW_MODEL_KEY) {
        SetupViewModel(graph.lock, graph.settings, graph.platform.random, graph.dispatchers)
    }
}

/**
 * Ties [step] to the back-stack entry of its route: Navigation 3 clears this entry's view models
 * when the route leaves the back stack (not at a rotation, and not while a predictive Back is only
 * being previewed), and [SetupViewModel.stepLeft] then drops what the step held.
 */
@Composable
internal fun SetupStepScope(step: SetupStep, setup: SetupViewModel) {
    viewModel(key = SETUP_STEP_KEY) { SetupStepViewModel(step, setup) }
}

/** The entry's own view model of [SetupStepScope]. */
internal class SetupStepViewModel(private val step: SetupStep, private val setup: SetupViewModel) : ViewModel() {
    override fun onCleared() {
        setup.stepLeft(step)
    }
}

/**
 * Moves with [nav] at most once while this screen is shown: a quick double tap on a button that opens
 * a route would otherwise push it twice (or go back two steps), because the screen still takes taps
 * while the move's animation runs (seen on API 37: two taps on "Use these words" left two "Write it
 * down on paper" screens, and Back showed the second). A screen that is shown again starts afresh.
 */
@Composable
private fun rememberMoveOnce(nav: Navigator): MoveOnce = remember(nav) { MoveOnce(nav) }

/** See [rememberMoveOnce]. */
private class MoveOnce(private val nav: Navigator) {
    private var moved = false

    fun go(route: Route) {
        if (!moved) moved = nav.go(route)
    }

    fun back() {
        if (!moved) moved = nav.back()
    }
}

/**
 * A step after the first with nothing to show: the words of an earlier process are gone (ux.md
 * §5.5). It goes back one step, while the app is still in setup; the first step then shows new
 * words. After the vault is made the app leaves setup by itself, and nothing is done here.
 */
@Composable
private fun BackToTheFirstStep(lock: LockManager, nav: Navigator) {
    LaunchedEffect(Unit) {
        if (lock.state.value is LockUiState.NoVault) nav.back()
    }
}

/**
 * The frame of a setup screen: a column that scrolls, inside the safe drawing area (the system bars
 * and the keyboard), at most 640 dp wide (ux.md §3 "Layout"), with [title] as the pane title that
 * TalkBack announces (ux.md §8) and as the screen's heading. [top] comes above the heading.
 */
@Composable
internal fun SetupPage(title: String, modifier: Modifier = Modifier, top: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .semantics { paneTitle = title },
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            top?.invoke()
            Text(text = title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

private const val SETUP_VIEW_MODEL_KEY = "pv.setup"
private const val SETUP_STEP_KEY = "pv.setup.step"
