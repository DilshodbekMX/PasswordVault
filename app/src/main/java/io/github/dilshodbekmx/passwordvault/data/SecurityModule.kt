package io.github.dilshodbekmx.passwordvault.data

import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.i18n.SortKeys

/**
 * Builds [SecurityReports] (architecture.md §3.6; build plan 7.1): one [SecurityCheckRunner] that
 * follows the app's vault and the language the screens are shown in, started at once. Its check
 * runs on one thread's worth of `Dispatchers.Default` (architecture.md §5.3), so that it never holds
 * up the list, which is built on the same pool.
 *
 * `securityNav` asks for it as the app's routes are registered, so that the check follows the vault
 * from the first unlock on, whichever screen is shown.
 */
object SecurityModule {
    fun create(graph: AppGraph): SecurityReports {
        val language = ScreenLanguage.watch(graph.app)
        return SecurityCheckRunner(
            repository = graph.data.repository,
            locales = language.locale,
            collationOf = ::SortKeys,
            scope = graph.appScope,
            dispatcher = graph.dispatchers.default.limitedParallelism(1),
        ).also { it.start() }
    }
}
