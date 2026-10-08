package io.github.dilshodbekmx.passwordvault.clipboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dilshodbekmx.passwordvault.AppGraph

/**
 * The app's [SecretClipboard] for the composition, set by [ClipboardRoot]; null outside it. A
 * dialog's or a bottom sheet's content inherits it, which is how [ClipboardWindow] finds it there.
 */
internal val LocalSecretClipboard = staticCompositionLocalOf<SecretClipboard?> { null }

/**
 * Provides `SecretClipboardBridge` as `LocalClipboard` around the whole content, so that text
 * selected and copied in a note goes through [SecretClipboard] with the sensitive flag and the timed
 * clear, while pasting still reads the platform's clipboard (architecture.md D-25, §3.6).
 *
 * It also tells the clipboard when the activity's window has the focus: only from then on does
 * Android 10 and later let the app read the clip's description, so the check that `onResume` asks
 * for ([SecretClipboard.appCameToFront]) is made again then ([AndroidSecretClipboard.windowFocused]).
 */
@Composable
fun ClipboardRoot(graph: AppGraph, content: @Composable () -> Unit) {
    val clipboard = graph.clipboard
    WindowFocusWatch(clipboard)
    CompositionLocalProvider(LocalSecretClipboard provides clipboard) {
        ClipboardWindow(content)
    }
}

/**
 * Provides the bridge of [ClipboardRoot] as `LocalClipboard` in one window: the activity's, where
 * `ClipboardRoot` calls it, and each dialog's and bottom sheet's. Such a window is drawn by a
 * ComposeView of its own, and every ComposeView provides the platform's clipboard as `LocalClipboard`
 * again, over what the content around the dialog provided; so a field or a selection inside a dialog
 * copies past [SecretClipboard] unless the dialog's content is wrapped in this. Outside
 * `ClipboardRoot`, and where the bridge already is the clipboard, it changes nothing.
 */
@Composable
fun ClipboardWindow(content: @Composable () -> Unit) {
    val secret = LocalSecretClipboard.current
    val platform = LocalClipboard.current
    val clipboard = remember(secret, platform) {
        if (secret == null || platform is SecretClipboardBridge) platform else SecretClipboardBridge(platform, secret)
    }
    CompositionLocalProvider(LocalClipboard provides clipboard, content = content)
}

/**
 * The clipboard status row of ux.md §6, in the unlocked screens' bottom area above the navigation
 * bar ("Password for Gmail · clears in 0:52", "Clear now"): [ClipStatusSlot] with the app's
 * clipboard and clock. It takes no room while nothing of ours is on the clipboard.
 */
@Composable
fun ClipboardRowSlot(graph: AppGraph) {
    val clipboard = graph.clipboard
    val status by clipboard.status.collectAsStateWithLifecycle()
    ClipStatusSlot(status = status, elapsedMs = graph.platform.clock::elapsedMs, onClear = clipboard::clearNow)
}

/** Calls [AndroidSecretClipboard.windowFocused] each time the window of this composition gets the focus. */
@Composable
private fun WindowFocusWatch(clipboard: SecretClipboard) {
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(clipboard, focused) {
        if (focused) (clipboard as? AndroidSecretClipboard)?.windowFocused()
    }
}
