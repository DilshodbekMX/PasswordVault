package io.github.dilshodbekmx.passwordvault.window

import android.os.Build
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.MotionEvent
import android.view.Window
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The notice of ux.md §3 "Overlays": the decor view drops a touch that arrives through another app's
 * window ([VaultSecurity]), so on Android 7 to 11, where overlays are not hidden, a screen filter or
 * a chat bubble makes taps "do nothing". When the activity sees such a touch
 * (`MotionEvent.FLAG_WINDOW_IS_OBSCURED`), this shows once per session (per process): "Another app
 * is drawing over this screen, so taps are blocked for safety. Turn off screen filters or chat
 * bubbles and try again."
 *
 * It is not a dialog: a dialog's buttons would be blocked by the same overlay. A card at the bottom,
 * read once by TalkBack (a polite live region), that goes at the next touch that is not obscured,
 * or after 10 seconds (longer when the user asked Android for more time to read).
 *
 * Only the activity's window is watched, as ux.md §3 says: a dialog or sheet drops obscured touches
 * too (`VaultWindow`), but without the notice.
 */
@Composable
fun ObscuredTouchNotice(content: @Composable () -> Unit) {
    val visible by ObscuredTouches.noticeVisible.collectAsStateWithLifecycle()
    Box(propagateMinConstraints = true) {
        content()
        if (visible) NoticeLayer()
    }
}

@Composable
private fun NoticeLayer() {
    val accessibility = LocalAccessibilityManager.current
    val shownForMs = accessibility?.calculateRecommendedTimeoutMillis(
        originalTimeoutMillis = NOTICE_MS,
        containsIcons = false,
        containsText = true,
        containsControls = false,
    ) ?: NOTICE_MS
    LaunchedEffect(Unit) {
        delay(shownForMs)
        ObscuredTouches.dismiss()
    }
    // Drawn outside the app's theme (WindowPolicy.Root wraps it), so with the phone's own light or dark.
    PasswordVaultTheme(ThemeChoice.SYSTEM) {
        Box(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = MaterialTheme.shapes.small,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .testTag(NOTICE_TAG)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(
                    text = stringResource(R.string.common_obscured_touch),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

/** How long the notice stays when nobody touches the screen, before accessibility adjusts it. */
private const val NOTICE_MS = 10_000L

/** The notice's test tag. */
internal const val NOTICE_TAG = "pv.window.obscuredNotice"

/**
 * The touches the activity's window dispatches, seen before the decor view drops an obscured one
 * (Compose never receives a dropped touch). One per process, so that the notice shows once per
 * session even when the activity is recreated. Main thread only.
 */
internal object ObscuredTouches {
    private val visible = MutableStateFlow(false)

    /** Whether the notice is on screen. */
    val noticeVisible: StateFlow<Boolean> = visible.asStateFlow()

    /** The notice was shown in this process. */
    private var told = false

    /**
     * Puts a watcher in front of [window]'s callback, which is the activity (behind AppCompat's own
     * wrapper): the decor view hands every touch to it first. A window already watched is left
     * alone.
     */
    fun watch(window: Window) {
        val current = window.callback ?: return
        if (current is Watcher) return
        window.callback = Watcher(current)
    }

    /** A touch began: an obscured one shows the notice the first time; any other one hides it. */
    fun touchDown(obscured: Boolean) {
        if (!obscured) {
            visible.value = false
        } else if (!told) {
            told = true
            visible.value = true
        }
    }

    fun dismiss() {
        visible.value = false
    }

    /** As in a new process: for tests. */
    @VisibleForTesting
    fun reset() {
        told = false
        visible.value = false
    }

    /**
     * The window's callback with the first touch of each gesture noted. Every other call goes to
     * [inner] unchanged, the two methods with a default body in `Window.Callback` included.
     */
    private class Watcher(private val inner: Window.Callback) : Window.Callback by inner {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                touchDown(obscured = event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0)
            }
            return inner.dispatchTouchEvent(event)
        }

        override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>?, menu: Menu?, deviceId: Int) {
            inner.onProvideKeyboardShortcuts(data, menu, deviceId)
        }

        override fun onPointerCaptureChanged(hasCapture: Boolean) {
            if (Build.VERSION.SDK_INT >= 26) inner.onPointerCaptureChanged(hasCapture)
        }
    }
}
