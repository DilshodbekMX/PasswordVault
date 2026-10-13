package io.github.dilshodbekmx.passwordvault.ui.common

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * Back while the keyboard is up closes the keyboard, and does nothing else (Android 13 and newer).
 * There Back reaches the app's own callbacks before the keyboard's when the app registered its
 * callback after the keyboard was shown: a screen that opens over a field that kept the keyboard
 * (the master password of an unlock, then a paused backup: one Back gave the paused backup up, 5.2
 * on Android 17), or a `BackHandler` that is turned on by what is typed (the list's search: one Back
 * emptied the query and left the keyboard up, w5-carry on API 37; the edit form's "Save changes?").
 * The screen's own Back then waits for a second Back. Below Android 13 Back is a key event that the
 * keyboard takes first by itself, and nothing is added.
 *
 * Every screen that has a text field and a `BackHandler` calls it right after its own
 * `BackHandler`s: the callback added last comes first.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyboardFirstOnBack() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler(enabled = WindowInsets.isImeVisible) { keyboard?.hide() }
}
