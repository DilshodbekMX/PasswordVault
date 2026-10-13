package io.github.dilshodbekmx.passwordvault.ui.lock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.i18n.rememberFormatters
import io.github.dilshodbekmx.passwordvault.lock.LockIntent
import io.github.dilshodbekmx.passwordvault.ui.common.KeyboardFirstOnBack
import io.github.dilshodbekmx.passwordvault.ui.common.VaultFieldDefaults
import io.github.dilshodbekmx.passwordvault.ui.common.VaultSecureField
import io.github.dilshodbekmx.passwordvault.ui.common.rememberFieldKeyboard

/**
 * The master-password lock screen (ux.md §5.10; lock.md §13.2, §13.3), shown by the lock route
 * while the lock shows `LockScreen.MasterPassword`: [ui] from [vm]; its buttons go to [onAction],
 * which is [vm]'s unless the route takes some of them first.
 *
 * The field takes the keyboard whenever this screen is composed and whenever it can be typed into
 * again after a wait: at the first show, after a rotation, which builds the screen again and where
 * Android 17 no longer brings the keyboard back by itself (architecture.md §5.4, [V-P7]), on "Try
 * again", and when the sheet over it closes. It asks once the window has focus, so that the request
 * reaches the keyboard, and again for about a second while the keyboard is not shown
 * (`FieldKeyboard`: on API 37 in landscape Gboard can close again by itself). While the "Forgot
 * master password?" sheet or a question [covered] the screen, the keyboard is put away, so that it
 * hides nothing of them. On the screen of a vault put aside, Back returns to the current vault
 * (lock.md F17 step 4).
 */
@Composable
fun MasterPasswordScreen(
    vm: LockViewModel,
    ui: MasterPasswordUi,
    modifier: Modifier = Modifier,
    onAction: (LockAction) -> Unit = vm::onAction,
    covered: Boolean = false,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val fieldKeyboard = rememberFieldKeyboard()
    var fieldFocused by remember { mutableStateOf(false) }

    LaunchedEffect(ui.fieldEnabled, covered, fieldKeyboard) {
        if (covered) keyboard?.hide()
        if (!ui.fieldEnabled || covered) return@LaunchedEffect
        fieldKeyboard.awaitWindowFocus()
        focusRequester.requestFocus()
        fieldKeyboard.show { fieldFocused }
    }

    BackHandler(enabled = ui.goesBack) { onAction(LockAction.Send(LockIntent.GoBack)) }
    KeyboardFirstOnBack()

    MasterPasswordContent(
        ui = ui,
        password = vm.password,
        onSubmit = vm::submit,
        onAction = { action ->
            onAction(action)
            if (action == LockAction.TryAgain) {
                focusRequester.requestFocus()
                keyboard?.show()
            }
        },
        modifier = modifier,
        fieldModifier = Modifier.focusRequester(focusRequester).onFocusChanged { fieldFocused = it.isFocused },
    )
}

/**
 * The screen itself, without its ViewModel: the line of a vault put aside, the title, the reason,
 * the fingerprint line, the field (with a new master password's wait under it), the lines after
 * the last try with their buttons, the countdown of a wait, "Unlock" or "Opening…", the text
 * buttons, and the test build's line. Every text comes from [ui] and the string resources (R-13);
 * dates are formatted here, in the composition's language.
 */
@Composable
fun MasterPasswordContent(
    ui: MasterPasswordUi,
    password: TextFieldState,
    onSubmit: () -> Unit,
    onAction: (LockAction) -> Unit,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
) {
    val formatters = rememberFormatters()
    LockPage(modifier) {
        ui.asideLine?.let { AsideLineText(it, formatters) }
        Text(
            text = ui.title.show(formatters),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() }.testTag(LockTags.TITLE),
        )
        ui.reason?.let {
            Text(
                text = it.show(formatters),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag(LockTags.REASON),
            )
        }
        ui.fingerprintLine?.let {
            Text(
                text = it.show(formatters),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag(LockTags.FINGERPRINT),
            )
        }
        VaultSecureField(
            state = password,
            modifier = fieldModifier.fillMaxWidth().testTag(LockTags.FIELD),
            label = { Text(stringResource(R.string.lock_mp_field)) },
            enabled = ui.fieldEnabled,
            supportingText = ui.pending?.let { pending -> { Text(pending.show(formatters), Modifier.testTag(LockTags.PENDING)) } },
            isError = ui.fieldError,
            keyboardOptions = VaultFieldDefaults.Secret.copy(imeAction = ImeAction.Done),
            onKeyboardAction = { onSubmit() },
        )
        ui.lines.forEachIndexed { index, line ->
            LockLineText(line, formatters, LockTags.line(index))
            line.button?.let { button -> LockButtonView(button, formatters, onAction, enabled = !ui.opening) }
        }
        ui.countdown?.let {
            Text(
                text = it.show(formatters),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag(LockTags.COUNTDOWN),
            )
        }
        if (ui.opening) {
            OpeningRow()
        } else {
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                Button(
                    onClick = onSubmit,
                    enabled = ui.canSubmit && password.text.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().testTag(LockTags.UNLOCK),
                ) {
                    Text(stringResource(R.string.lock_mp_unlock))
                }
            }
        }
        for (button in ui.buttons) LockButtonView(button, formatters, onAction, enabled = !ui.opening)
        if (ui.testBuild) TestBuildLine()
    }
}
