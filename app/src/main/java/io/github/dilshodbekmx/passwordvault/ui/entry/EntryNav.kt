package io.github.dilshodbekmx.passwordvault.ui.entry

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.EntryProviderScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.ui.edit.SavedNotice

/**
 * The routes of one account (ux.md §5.12, §5.13; build plan 3.7): `Entry(id)`, the account's
 * screen, and `BigLetters(id, field)`, one of its values in big letters.
 *
 * Each screen's ViewModel lives in the route's own store: a rotation or a language change keeps
 * what is shown, and a lock clears it (architecture.md §5.5). A route key holds only the id and the
 * field's name; the texts are read from the open vault.
 *
 * The form opens `Entry(id)` in place of itself after it saved a new account, and leaves a mark
 * for it (`SavedNotice`): the account's screen takes the mark once, when its ViewModel is made, and
 * then says "Saved" (ux.md §5.14).
 */
fun EntryProviderScope<Route>.entryNav(graph: AppGraph, nav: Navigator) {
    entry<Route.Entry> { route ->
        val vm = viewModel { EntryViewModel.create(graph, route.id, justSaved = SavedNotice.take(route.id)) }
        EntryScreen(vm, nav)
    }
    entry<Route.BigLetters> { route ->
        val vm = viewModel { BigLettersViewModel.create(graph, route.id, route.field) }
        BigLettersScreen(vm, nav)
    }
}
