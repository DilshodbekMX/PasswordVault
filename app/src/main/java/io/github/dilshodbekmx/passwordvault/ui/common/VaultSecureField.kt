package io.github.dilshodbekmx.passwordvault.ui.common

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.window.vaultTextField

/**
 * The app's password field (R-13: with [VaultTextField], the only way to make a text field), for
 * the master password, an account's password or a secret value: an `OutlinedSecureTextField` with
 * `vaultTextField()` and an explicit obfuscation mode, `RevealLastTyped`, plus a show/hide button
 * (ux.md §3, lock.md §13.5). The text is always laid out left to right.
 *
 * [state] is held by the screen's ViewModel (architecture.md §5.6, lock.md §5.8): this field adds
 * nothing to the saved-instance state, not even whether the password is shown, which starts hidden
 * again after a rotation. After reading the password, the ViewModel calls [clearSecret] [V-C11].
 *
 * @param trailingIcon an extra button before the show/hide button, such as the generator's.
 * @param revealable whether the show/hide button is there.
 */
@Composable
fun VaultSecureField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    inputTransformation: InputTransformation? = null,
    keyboardOptions: KeyboardOptions = VaultFieldDefaults.Secret,
    onKeyboardAction: KeyboardActionHandler? = null,
    revealable: Boolean = true,
    textStyle: TextStyle = LocalTextStyle.current,
    interactionSource: MutableInteractionSource? = null,
) {
    var revealed by remember { mutableStateOf(false) }
    val trailing: (@Composable () -> Unit)? = when {
        revealable -> {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    trailingIcon?.invoke()
                    RevealToggle(revealed = revealed, onRevealedChange = { revealed = it })
                }
            }
        }
        else -> trailingIcon
    }
    OutlinedSecureTextField(
        state = state,
        modifier = modifier.vaultTextField(),
        enabled = enabled,
        textStyle = textStyle.copy(textDirection = TextDirection.Ltr),
        label = label?.let { slot -> { slot() } },
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailing,
        supportingText = supportingText,
        isError = isError,
        inputTransformation = inputTransformation,
        textObfuscationMode = if (revealable && revealed) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
        keyboardOptions = keyboardOptions,
        onKeyboardAction = onKeyboardAction,
        interactionSource = interactionSource,
    )
}

/**
 * The show/hide button of a password field and of a stored password (ux.md §3, §5.12, §8): an
 * `IconToggleButton` labelled "Show password" while hidden and "Hide password" while shown, whose
 * on or off state TalkBack reads with it.
 */
@Composable
fun RevealToggle(revealed: Boolean, onRevealedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    IconToggleButton(checked = revealed, onCheckedChange = onRevealedChange, modifier = modifier, enabled = enabled) {
        Icon(
            painter = painterResource(if (revealed) R.drawable.ic_common_visibility_off else R.drawable.ic_common_visibility),
            contentDescription = stringResource(if (revealed) R.string.common_hide_password else R.string.common_show_password),
        )
    }
}
