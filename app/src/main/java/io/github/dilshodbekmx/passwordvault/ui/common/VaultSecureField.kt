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
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.window.PASSWORD_MASK_CHARACTER
import io.github.dilshodbekmx.passwordvault.window.vaultTextField

/**
 * The app's password field (R-13: with [VaultTextField], the only way to make a text field), for
 * the master password, an account's password or a secret value: an `OutlinedSecureTextField` with
 * `vaultTextField()` and an explicit obfuscation mode, `RevealLastTyped`, plus a show/hide button
 * (ux.md §3, lock.md §13.5). The text is always laid out left to right. While it is masked,
 * accessibility services read one mask character per character, never the text, on every version
 * of Android ([maskedForAccessibility]).
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
    val masked = !(revealable && revealed)
    OutlinedSecureTextField(
        state = state,
        modifier = modifier.vaultTextField().maskedForAccessibility(state, masked),
        enabled = enabled,
        textStyle = textStyle.copy(textDirection = TextDirection.Ltr),
        label = label?.let { slot -> { slot() } },
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailing,
        supportingText = supportingText,
        isError = isError,
        inputTransformation = inputTransformation,
        textObfuscationMode = if (masked) TextObfuscationMode.RevealLastTyped else TextObfuscationMode.Visible,
        keyboardOptions = keyboardOptions,
        onKeyboardAction = onKeyboardAction,
        interactionSource = interactionSource,
    )
}

/**
 * While [masked], the field's semantics hold one mask character for each character of [state]
 * instead of its text, so that no accessibility service, a screen reader or any other, reads the
 * secret on any version of Android (architecture.md §8.4, lock.md §13.5).
 *
 * The text field's own semantics (Compose foundation 1.12) put the plain text into the node's
 * editable text, unlike the platform's password `EditText`, whose node holds what it draws. That
 * editable text is what an accessibility service gets as the node's text, as the text and the
 * "before" text of the text-changed and selection-changed events, and to step through with the
 * movement granularities. `isSensitiveData` keeps the services that are not accessibility tools away
 * from it only from Android 14 on; before, any service the user turned on read the typed master
 * password (tools/emu/window/a11y.sh on Android 9).
 *
 * This modifier is placed before the field's own semantics on the same layout node, and of the
 * semantics modifiers of one node the outermost one's value of a property wins, so its editable
 * text replaces the field's. Its length is the text's length in UTF-16 units, as the platform's
 * masked text has it, so the cursor and selection positions that the field reports, and that an
 * accessibility service sends back to move the cursor, keep their meaning; the field still says that
 * it is a password field and how many characters it holds. Its actions are untouched: a screen
 * reader still focuses it, types and deletes through the keyboard or sets its text, and moves the
 * cursor. Shown ("Show password"), the field reports its text again, which is then on the screen
 * for anybody to read (lock.md §2.3). The event that hiding it again sends would carry that text as
 * its "before" text; the window's [io.github.dilshodbekmx.passwordvault.window.MaskedFieldEvents]
 * masks it.
 */
private fun Modifier.maskedForAccessibility(state: TextFieldState, masked: Boolean): Modifier =
    semantics {
        if (masked) editableText = AnnotatedString(PASSWORD_MASK_CHARACTER.toString().repeat(state.text.length))
    }

/**
 * The show/hide button of a password field and of a stored password (ux.md §3, §5.12, §8): an
 * `IconToggleButton` labelled "Show password" while hidden and "Hide password" while shown, whose
 * on or off state TalkBack reads with it.
 *
 * Compose offers every toggle to the phone's autofill service as a value it can read and set
 * (foundation 1.12.1: `ContentDataType.Toggle` with `OnFillData`), so a fill could switch "Show
 * password" on. Like every field of the app ([V-P4], ux.md §3), the button opts out. The opt-out
 * comes before the button's own toggleable on the same node, so its value is the node's.
 */
@Composable
fun RevealToggle(revealed: Boolean, onRevealedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    IconToggleButton(
        checked = revealed,
        onCheckedChange = onRevealedChange,
        modifier = Modifier.semantics { contentDataType = ContentDataType.None }.then(modifier),
        enabled = enabled,
    ) {
        Icon(
            painter = painterResource(if (revealed) R.drawable.ic_common_visibility_off else R.drawable.ic_common_visibility),
            contentDescription = stringResource(if (revealed) R.string.common_hide_password else R.string.common_show_password),
        )
    }
}
