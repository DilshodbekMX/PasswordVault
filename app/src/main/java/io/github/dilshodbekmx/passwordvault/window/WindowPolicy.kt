package io.github.dilshodbekmx.passwordvault.window

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable

/**
 * The activity's window and input policy (architecture.md §3.6; lock.md §13.5; ux.md §3): the hook
 * that `MainActivity` calls. Dialogs and bottom sheets are windows of their own and get the same
 * policy from `VaultDialog` and `VaultSheet` (R-13).
 */
object WindowPolicy {
    /**
     * Called in `onCreate`, before `setContent`: edge to edge (ux.md §3 "Layout"; the theme then
     * sets the bar icons for the app's light or dark choice), [VaultSecurity] on the activity's own
     * window (FLAG_SECURE in staging and release, the touch filter, the autofill and accessibility
     * opt-outs and the hidden overlays where the API level has them, no Recents screenshot), and
     * the watch on obscured touches behind [ObscuredTouchNotice].
     */
    fun onCreate(activity: ComponentActivity) {
        activity.enableEdgeToEdge()
        VaultSecurity.applyToActivity(activity)
        ObscuredTouches.watch(activity.window)
    }

    /**
     * Wraps the whole content: [NoPersonalizedLearning] (the keyboard flag for every field, dialogs
     * and sheets included), [VaultWindow] (no "Autofill" item in the text toolbar of the activity's
     * window) and [ObscuredTouchNotice].
     */
    @Composable
    fun Root(content: @Composable () -> Unit) {
        NoPersonalizedLearning {
            VaultWindow {
                ObscuredTouchNotice(content)
            }
        }
    }
}
