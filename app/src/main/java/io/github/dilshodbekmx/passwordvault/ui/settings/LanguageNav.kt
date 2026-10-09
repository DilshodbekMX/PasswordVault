package io.github.dilshodbekmx.passwordvault.ui.settings

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.EntryProviderScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.BuildConfig
import io.github.dilshodbekmx.passwordvault.i18n.AppLanguage
import io.github.dilshodbekmx.passwordvault.i18n.AppLanguages
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.ui.setup.WelcomeScreen
import io.github.dilshodbekmx.passwordvault.ui.setup.asideVaults

/**
 * The route of the language switch (build plan 6.1; i18n.md §1.4.5; ux.md §5.1, §5.18): the
 * language dialog ([LanguageDialog]), from Welcome's globe button while the app has no vault and
 * from the language row of Settings while it is unlocked (`NavState.allowedIn`).
 *
 * Like "Appearance" (`Route.Theme`, `SettingsNav.kt`), the dialog is a route of its own that draws the
 * screen it was opened from behind it ([LanguageBackdrop]): Welcome, or the Settings list
 * ([SettingsBackdrop]). So a rotation keeps the dialog open, and a lock closes it, because
 * the route is not kept across a lock (`NavState.keptAcrossLock`; ux.md §4: dialogs are closed). The
 * screen behind is a picture only: it takes no tap and TalkBack does not read it.
 *
 * A choice closes the dialog and hands the language to Android ([LanguageSwitch]). Android then
 * recreates the activity in that language and nothing else (i18n.md §1.4.3, lock.md §11): the
 * process, the lock, the open vault and the back stack go on, so the app stays open on the screen
 * the dialog was opened from, now in the new language, and the list sorts in it.
 */
fun EntryProviderScope<Route>.languageNav(graph: AppGraph, nav: Navigator) {
    entry<Route.Language> { LanguageRoute(graph, nav) }
}

@Composable
private fun LanguageRoute(graph: AppGraph, nav: Navigator) {
    val state by graph.lock.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().clearAndSetSemantics { }) {
        when (LanguageBackdrop.of(state)) {
            LanguageBackdrop.WELCOME -> WelcomeBackdrop(graph, state)
            LanguageBackdrop.SETTINGS -> SettingsBackdrop(graph)
            LanguageBackdrop.NONE -> Unit
        }
    }
    val context = LocalContext.current
    val pseudo = BuildConfig.DEBUG
    // The activity's context: recreated with each language, so these are read again after a change.
    val current = remember(context) { LanguageChoice.of(AppLanguages.appLocales(context), pseudo) }
    val systemShown = remember(context) { phoneShown(context) }
    LanguageSwitch(
        choices = LanguageChoice.offered(pseudo),
        current = current,
        systemShown = systemShown,
        nav = nav,
        apply = { choice -> AppLanguages.setTags(context, choice.tags) },
    )
}

/**
 * The language the app shows when it follows the phone: the line under "System language". From
 * Android 13 that is [AppLanguages.systemShown] of the phone's own list (`LocaleManager`). Before, it
 * is the application's configuration, which AppCompat leaves in the phone's languages (i18n.md
 * §1.4.3) and whose first locale Android has already chosen among the APK's languages; the list of
 * `Resources.getSystem()`, which `AppLanguages.systemShown` reads there, is ordered for the
 * framework's own translations instead, which have no Uzbek Cyrillic: with the phone set to
 * Ўзбекча, then English, it puts English first (seen on API 24).
 */
private fun phoneShown(context: Context): AppLanguage =
    if (Build.VERSION.SDK_INT >= 33) {
        AppLanguages.systemShown(context)
    } else {
        AppLanguages.shown(AppLanguages.localesOf(context.applicationContext.resources.configuration))
    }

/**
 * The language dialog with what its buttons do: a choice closes the dialog ([Navigator.back]) and,
 * when it is not [current], hands it to [apply] (`AppLanguages.setTags`, which makes Android recreate
 * the activity in it); "Cancel", Back and a tap outside only close it. Whatever is tapped, the dialog
 * acts once: a second tap while it closes (the move's animation still shows it) would otherwise go
 * back a second screen, or apply a second language.
 */
@Composable
internal fun LanguageSwitch(
    choices: List<LanguageChoice>,
    current: LanguageChoice,
    systemShown: AppLanguage,
    nav: Navigator,
    apply: (LanguageChoice) -> Unit,
) {
    val once = remember(nav) { Once() }
    LanguageDialog(
        choices = choices,
        selected = current,
        systemShown = systemShown,
        onChoose = { choice ->
            once.run {
                nav.back()
                if (choice != current) apply(choice)
            }
        },
        onDismiss = { once.run { nav.back() } },
    )
}

/**
 * What the language dialog draws behind it: the screen it was opened from, which the lock's state
 * tells (`NavState.allowedIn`: the language route belongs to setup, where Welcome opens it, and to
 * the unlocked app, where Settings does).
 */
internal enum class LanguageBackdrop {
    WELCOME,
    SETTINGS,
    NONE,
    ;

    companion object {
        /** The backdrop for the lock's [state]; [NONE] while the lock starts or is locked, when the route is never shown. */
        fun of(state: LockUiState): LanguageBackdrop = when (state) {
            is LockUiState.NoVault -> WELCOME
            is LockUiState.Unlocked -> SETTINGS
            else -> NONE
        }
    }
}

/** Welcome as its route draws it, with the vaults put aside; its buttons do nothing here. */
@Composable
private fun WelcomeBackdrop(graph: AppGraph, state: LockUiState) {
    val status by graph.lock.status.collectAsStateWithLifecycle()
    WelcomeScreen(
        aside = asideVaults(state, status),
        onStart = {},
        onOpenAside = {},
        onRestore = {},
        onLanguage = {},
    )
}

/**
 * The Settings list as its route draws it, scrolled where [ThemeBackdrop] was marked, or from its top
 * when nothing was (the language row of `SettingsScreen` marks it as the Appearance row does once
 * build plan 6.1's interface request is applied; until then the list behind the dialog starts at its
 * top, and the real list is where it was when the dialog closes). Its rows do nothing here. Its view
 * model is this route's own, cleared when the dialog closes.
 */
@Composable
private fun SettingsBackdrop(graph: AppGraph) {
    val vm = viewModel { SettingsViewModel.from(graph) }
    val (index, offset) = remember { ThemeBackdrop.take() }
    SettingsScreen(vm, NoMoves, listState = rememberLazyListState(index, offset))
}

/** The navigator of a screen drawn behind a dialog: it moves nowhere. */
private object NoMoves : Navigator {
    override fun go(route: Route): Boolean = false

    override fun back(): Boolean = false

    override fun replace(route: Route): Boolean = false

    override fun tab(root: Route): Boolean = false
}

/** Runs the first action given to it, and no other. Main thread only. */
private class Once {
    private var done = false

    fun run(action: () -> Unit) {
        if (done) return
        done = true
        action()
    }
}
