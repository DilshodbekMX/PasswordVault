package io.github.dilshodbekmx.passwordvault.window

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import io.github.dilshodbekmx.passwordvault.BuildConfig

/**
 * The activity's window and input policy (architecture.md §3.6; lock.md §13.5): the hook that
 * `MainActivity` calls, filled by the window kit (build plan 2.8) with `VaultSecurity`,
 * `NoPersonalizedLearning` and `ObscuredTouchNotice`.
 *
 * Until then the stub keeps what the app did before the shell: edge to edge (ux.md §3 "Layout"),
 * and `FLAG_SECURE` exactly where `BuildConfig.SECURE_WINDOW` is true (staging and release,
 * architecture.md §7.2).
 */
object WindowPolicy {
    /** Called in `onCreate`, before `setContent`: the policy of the activity's own window. */
    fun onCreate(activity: ComponentActivity) {
        activity.enableEdgeToEdge()
        if (BuildConfig.SECURE_WINDOW) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /** Wraps the whole content: `NoPersonalizedLearning` and `ObscuredTouchNotice` (build plan 2.8). The stub adds nothing. */
    @Composable
    fun Root(content: @Composable () -> Unit) {
        content()
    }
}
