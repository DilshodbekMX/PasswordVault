package io.github.dilshodbekmx.passwordvault.ui.common

import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.WindowInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The keyboard of a field that has the focus when its screen is made: at the first show, and after
 * a rotation, after which Android 17 no longer brings the keyboard back by itself [V-P7]
 * (architecture.md §5.4). Used by "Type your master password again" (`TypeAgainScreen`), the edit
 * form (`EditScreen`) and the master-password lock screen (`MasterPasswordScreen`); made for the
 * window it is composed in by [rememberFieldKeyboard].
 *
 * One request is not always enough. On API 37 in touch mode, after a rotation to landscape, Gboard
 * opens full screen for the new window's empty editor before the field's own input has started, and
 * closes itself again (`HIDE_SOFT_INPUT_EXTRACT_INPUT_CHANGED`) after the screen's request; nothing
 * asked again, and the keyboard stayed hidden (job 4.2b, 2026-10-08: 17 of 17 rotations on the lock
 * screen). So [show] asks again for about a second, whenever the field still has the focus and the
 * keyboard is not shown. A keyboard closed by hand within that second comes back; after it,
 * nothing is asked.
 */
@Stable
class FieldKeyboard internal constructor(
    private val view: View,
    private val keyboard: SoftwareKeyboardController?,
    private val window: WindowInfo,
) {
    /** Returns once this window has the window focus: a keyboard asked for before that is dropped. */
    suspend fun awaitWindowFocus() {
        snapshotFlow { window.isWindowFocused }.first { it }
    }

    /**
     * Whether the keyboard is shown over this window, as the window's insets say from Android 11
     * on; false below, where an app cannot see it.
     */
    fun shown(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true

    /**
     * Asks for the keyboard now unless it is [shown], and then every [STEP_MS] for about a second
     * ([AGAIN_MS]) again while [focused] (the field has the focus) and the keyboard is not shown.
     * It does not stop at the first time the keyboard is seen: the keyboard that closes by itself
     * was shown a moment before. Below Android 11, where the screen cannot see the keyboard, it
     * asks once. Call it once the field has been given the focus, in the coroutine of the effect
     * that gave it.
     */
    suspend fun show(focused: () -> Boolean) {
        if (!shown()) keyboard?.show()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        // The keyboard closes itself in real time, so the second is the phone's, also under a test's clock, which
        // holds back the delays of a composition: the waits run on Dispatchers.Default and the end is uptime.
        val end = SystemClock.uptimeMillis() + AGAIN_MS
        while (true) {
            withContext(Dispatchers.Default) { delay(STEP_MS) }
            if (SystemClock.uptimeMillis() > end) return
            if (focused() && !shown()) keyboard?.show()
        }
    }

    companion object {
        /** How long the keyboard is asked for again after the first request. */
        const val AGAIN_MS = 1_000L

        /** How often it is looked at meanwhile. */
        const val STEP_MS = 100L
    }
}

/** The [FieldKeyboard] of the window this is composed in. */
@Composable
fun rememberFieldKeyboard(): FieldKeyboard {
    val view = LocalView.current
    val keyboard = LocalSoftwareKeyboardController.current
    val window = LocalWindowInfo.current
    return remember(view, keyboard, window) { FieldKeyboard(view, keyboard, window) }
}
