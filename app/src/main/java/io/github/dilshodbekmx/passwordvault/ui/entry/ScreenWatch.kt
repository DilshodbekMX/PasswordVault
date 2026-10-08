package io.github.dilshodbekmx.passwordvault.ui.entry

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Calls [onTouch] for every touch that reaches this part of the screen, before its children see it
 * and without taking it from them: the "last touch on the screen" of ux.md §3 and §5.13. Touches in
 * a menu or another window do not pass here; what they do counts as a touch where it is handled.
 */
internal fun Modifier.onAnyTouch(onTouch: () -> Unit): Modifier = pointerInput(onTouch) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial)
            onTouch()
        }
    }
}

/**
 * Calls [onStopped] when the screen stops (ux.md §3: a shown value is hidden "whenever the screen
 * stops"; §5.13: big letters are "never kept after the screen stops"): when the activity stops
 * because the app was left or the phone's screen went off, and, with [whenCovered], when this
 * screen leaves the composition because another screen of the app covers it or it was closed.
 *
 * A rotation or a language change recreates the activity and stops it on the way, but the person
 * never left the screen: that stop is not reported, and what the screen's ViewModel shows stays.
 *
 * The activity's own lifecycle is watched, not the one Navigation gives each screen, which also
 * stops when the screen is closed with Back.
 */
@Composable
internal fun OnScreenStopped(whenCovered: Boolean, onStopped: () -> Unit) {
    val activity = LocalActivity.current
    val stopped by rememberUpdatedState(onStopped)
    DisposableEffect(activity, whenCovered) {
        val lifecycle = (activity as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && activity?.isChangingConfigurations != true) stopped()
        }
        lifecycle?.addObserver(observer)
        onDispose {
            lifecycle?.removeObserver(observer)
            if (whenCovered && activity?.isChangingConfigurations != true) stopped()
        }
    }
}

/** Keeps the phone's screen on while this is in the composition (`FLAG_KEEP_SCREEN_ON` through the view, ux.md §5.13). */
@Composable
internal fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
