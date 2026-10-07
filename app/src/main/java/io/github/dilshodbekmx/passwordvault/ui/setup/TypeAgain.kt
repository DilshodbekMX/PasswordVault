package io.github.dilshodbekmx.passwordvault.ui.setup

import android.os.Build
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.i18n.resolve
import io.github.dilshodbekmx.passwordvault.ui.common.VaultFieldDefaults
import io.github.dilshodbekmx.passwordvault.ui.common.VaultSecureField
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * "Type your master password again" (ux.md §5.5; lock.md F1 steps 2 and 3): the field has the focus
 * and the keyboard when the screen appears, and again after a rotation, because Android 17 no longer
 * restores the keyboard after one [V-P7] (architecture.md §5.4). The keyboard's Done and the full
 * width "Next" do the same. A mismatch says so (with the Cyrillic-keyboard hint where it applies),
 * and from the third on "Show my password again" goes back to "Write it down on paper". While the
 * vault is made, "Setting up…" shows and Back is ignored (ux.md §4); a failure says why, and the
 * button becomes "Try again".
 *
 * [field] is the field's state, held by [SetupViewModel].
 *
 * @param keepScreenOn true in a change or a replacement of the master password (lock.md §10.1,
 *   build plan 5.5); setup does not keep the screen on.
 */
@Composable
fun TypeAgainScreen(
    state: SetupState,
    field: TextFieldState,
    onNext: () -> Unit,
    onShowAgain: () -> Unit,
    keepScreenOn: Boolean = false,
) {
    if (keepScreenOn) KeepScreenOn()
    BackHandler(enabled = state.settingUp) { /* ignored while the vault is made (ux.md §4) */ }
    val focus = remember { FocusRequester() }
    val window = LocalWindowInfo.current
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    // Every new composition, the first and the one after a rotation, gives the field the focus and asks for the
    // keyboard, which Android 17 no longer brings back by itself [V-P7]:
    //  - once the window has the window focus, because a keyboard asked for before that is dropped. After a
    //    rotation the new window gets it a moment after its first layout, and on Android 7 that layout has
    //    already given the field the focus, so asking for the focus alone brings no keyboard (seen on API 24);
    //  - again for a moment while it is not shown, where the screen can see that (Android 11 and newer): a
    //    keyboard asked for while the field's input connection is still being made can close again (seen on
    //    API 37 in landscape, where Gboard opened full screen for the editor before the field's and then closed).
    LaunchedEffect(focus, window) {
        snapshotFlow { window.isWindowFocused }.first { it }
        focus.requestFocus()
        keyboard?.show()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            repeat(KEYBOARD_RETRIES) {
                delay(KEYBOARD_RETRY_MS)
                if (keyboardShown(view)) return@LaunchedEffect
                keyboard?.show()
            }
        }
    }
    val message = state.againMessage
    SetupPage(title = stringResource(R.string.setup_again_title)) {
        Text(text = stringResource(R.string.setup_again_body), style = MaterialTheme.typography.bodyLarge)
        VaultSecureField(
            state = field,
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(SetupTags.AGAIN_FIELD),
            label = { Text(stringResource(R.string.setup_field_label)) },
            keyboardOptions = VaultFieldDefaults.Secret.copy(imeAction = ImeAction.Done),
            onKeyboardAction = KeyboardActionHandler { onNext() },
            isError = message != null,
            supportingText = message?.let { { MessageText(it, SetupTags.AGAIN_MESSAGE) } },
        )
        state.keyboardHint?.let { hint ->
            Text(
                text = hint.resolve(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(SetupTags.KEYBOARD_HINT).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (state.settingUp) {
            Row(
                modifier = Modifier.testTag(SetupTags.SETTING_UP).semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(text = stringResource(R.string.setup_again_setting_up), style = MaterialTheme.typography.bodyLarge)
            }
        }
        Button(onClick = onNext, enabled = !state.settingUp, modifier = Modifier.fillMaxWidth().testTag(SetupTags.CREATE)) {
            Text(stringResource(if (state.canRetry) R.string.common_try_again else R.string.setup_next))
        }
        if (state.canShowAgain) {
            TextButton(onClick = onShowAgain, enabled = !state.settingUp, modifier = Modifier.fillMaxWidth().testTag(SetupTags.SHOW_AGAIN)) {
                Text(stringResource(R.string.setup_again_show_again))
            }
        }
    }
}

/** Whether the keyboard is shown over [view]'s window: its insets say so from Android 11 on. */
private fun keyboardShown(view: View): Boolean = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true

/** How many more times, and how often, the keyboard is asked for while it is not shown. */
private const val KEYBOARD_RETRIES = 6
private const val KEYBOARD_RETRY_MS = 250L
