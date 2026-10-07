package io.github.dilshodbekmx.passwordvault.ui.shell

import androidx.compose.runtime.Composable
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.clipboard.ClipboardRoot
import io.github.dilshodbekmx.passwordvault.nav.AppNavHost
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import io.github.dilshodbekmx.passwordvault.window.WindowPolicy

/**
 * The root content of the activity (architecture.md §3.6): the window policy around everything
 * (`NoPersonalizedLearning`, `ObscuredTouchNotice`), the clipboard bridge, the theme, then the
 * navigation.
 *
 * The theme follows `graph.settings.theme` (System, Light or Dark), as §3.6 writes the call; it is
 * the window kit's (build plan 2.8).
 */
@Composable
fun AppRoot(graph: AppGraph) {
    WindowPolicy.Root {
        ClipboardRoot(graph) {
            PasswordVaultTheme(graph.settings) {
                AppNavHost(graph)
            }
        }
    }
}
