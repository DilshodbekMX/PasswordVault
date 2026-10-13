package io.github.dilshodbekmx.passwordvault.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.EntryProviderScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.nav.HelpPage
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route

/**
 * The routes of Help (ux.md §5.22; build plan 7.3): `Route.Help(page)`, a help page ([HelpScreen],
 * whose text [HelpPages] gives with the clipboard delay chosen in Settings), and `Route.Licences`,
 * the open-source licences ([LicencesScreen]). Settings › Help opens them, and the pages open each
 * other where one points to another. Nothing on them is typed or secret.
 */
fun EntryProviderScope<Route>.helpNav(graph: AppGraph, nav: Navigator) {
    entry<Route.Help> { route -> HelpRoute(graph, nav, route.page) }
    entry<Route.Licences> { LicencesRoute(graph, nav) }
}

@Composable
private fun HelpRoute(graph: AppGraph, nav: Navigator, page: HelpPage) {
    val clipDelay by graph.settings.clipDelay.collectAsStateWithLifecycle()
    val blocks = remember(page, clipDelay) { HelpPages.blocks(page, HelpFacts.of(clipDelay)) }
    HelpScreen(page, blocks, nav)
}

@Composable
private fun LicencesRoute(graph: AppGraph, nav: Navigator) {
    val vm = viewModel {
        val reader = LicenceTextReader(graph.app.resources)
        LicencesViewModel(reader::read, graph.dispatchers.io)
    }
    LicencesScreen(vm, nav)
}
