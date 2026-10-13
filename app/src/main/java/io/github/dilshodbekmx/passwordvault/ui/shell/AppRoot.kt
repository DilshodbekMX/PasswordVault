package io.github.dilshodbekmx.passwordvault.ui.shell

import androidx.compose.runtime.Composable
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.clipboard.ClipboardRoot
import io.github.dilshodbekmx.passwordvault.nav.AppNavHost
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import io.github.dilshodbekmx.passwordvault.window.WindowPolicy

/**
 * The root content of the activity (architecture.md §3.6): the theme, then the window policy
 * around everything else (`NoPersonalizedLearning`, `ObscuredTouchNotice`), the clipboard bridge,
 * then the navigation.
 *
 * The look follows `graph.settings.style` and `graph.settings.theme` (Settings › Appearance;
 * docs/design/looks/README.md), as §3.6 writes the call; it is the window kit's (build plan 2.8).
 * It lies outermost, so that the notice of an obscured touch, which the window policy draws over
 * everything, has the chosen style too.
 */
@Composable
fun AppRoot(graph: AppGraph) {
    PasswordVaultTheme(graph.settings) {
        WindowPolicy.Root {
            ClipboardRoot(graph) {
                AppNavHost(graph)
            }
        }
    }
}
