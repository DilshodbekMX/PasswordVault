package io.github.dilshodbekmx.passwordvault.clipboard

import androidx.compose.runtime.Composable
import io.github.dilshodbekmx.passwordvault.AppGraph

/**
 * Provides `SecretClipboardBridge` as `LocalClipboard` around the whole content, so that text
 * selected and copied in a note goes through [SecretClipboard] with the sensitive flag and the timed
 * clear, while pasting still reads the platform's clipboard (architecture.md D-25, §3.6). Filled by
 * the clipboard package (build plan 3.8); the stub provides nothing.
 */
@Composable
fun ClipboardRoot(graph: AppGraph, content: @Composable () -> Unit) {
    content()
}

/**
 * The clipboard status row of ux.md §6, in the unlocked screens' bottom area above the navigation
 * bar ("Password for Gmail · clears in 0:52", "Clear now"). Filled by the clipboard package (build
 * plan 3.8); the stub draws nothing.
 */
@Composable
fun ClipboardRowSlot(graph: AppGraph) = Unit
