package io.github.dilshodbekmx.passwordvault.ui.common

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextDirection
import io.github.dilshodbekmx.passwordvault.window.vaultTextField

/**
 * The app's text field (R-13: the only way, with [VaultSecureField], to make one). An
 * `OutlinedTextField` with `vaultTextField()`: no autofill session and no fill or save request for
 * it, and hidden from accessibility services that are not accessibility tools (lock.md §13.5,
 * ux.md §3). The keyboard flag that stops personalised learning comes from `WindowPolicy.Root`
 * around the whole content, and the text toolbar's "Autofill" item is removed by `VaultWindow` in
 * every window. A label that needs a second line moves above the box, and while it is out of view the
 * field names itself with it for accessibility services ([FieldLabel]). The text is
 * never hyphenated, as Android's own editable text is not: a value too long for its line wraps
 * between two of its own characters, with no hyphen drawn that was not typed, although the theme
 * hyphenates the app's texts (i18n.md §4.5 rule 4; `TextFieldLinesTest`).
 *
 * [state] is held by the screen's ViewModel, never by `rememberTextFieldState()`, which writes the
 * text into the saved-instance state (architecture.md §5.6, [V-C11]). There is no `value` and
 * `onValueChange` form, for the same reason.
 *
 * @param label the field's label, drawn on its border or above its box, and its name.
 * @param readOnly never for a stored secret: a read-only field is an autofill node holding its
 *   text, so stored passwords are shown with [SecretText] [V-P4].
 * @param leftToRight lays the text out left to right whatever the language, as usernames and
 *   websites are (ux.md §3).
 * @param keyboardOptions [VaultFieldDefaults] has the app's kinds of field.
 */
@Composable
fun VaultTextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    inputTransformation: InputTransformation? = null,
    outputTransformation: OutputTransformation? = null,
    keyboardOptions: KeyboardOptions = VaultFieldDefaults.Text,
    onKeyboardAction: KeyboardActionHandler? = null,
    lineLimits: TextFieldLineLimits = TextFieldLineLimits.Default,
    leftToRight: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    interactionSource: MutableInteractionSource? = null,
) {
    val fieldLabel = rememberFieldLabel(label)
    OutlinedTextField(
        state = state,
        modifier = modifier.vaultTextField().named(fieldLabel.name),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = (if (leftToRight) textStyle.copy(textDirection = TextDirection.Ltr) else textStyle).copy(hyphens = Hyphens.None),
        labelPosition = fieldLabel.position,
        label = fieldLabel.label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        prefix = prefix,
        suffix = suffix,
        supportingText = supportingText,
        isError = isError,
        inputTransformation = inputTransformation,
        outputTransformation = outputTransformation,
        keyboardOptions = keyboardOptions,
        onKeyboardAction = onKeyboardAction,
        lineLimits = lineLimits,
        interactionSource = interactionSource,
    )
}

/**
 * The keyboards of the app's fields. Auto-correct is off for usernames, passwords, websites and
 * PINs (ux.md §3); the keyboard's Next or Done key is the screen's choice (`imeAction`, ux.md
 * §5.14).
 */
object VaultFieldDefaults {
    /** Titles, notes and other prose: sentences start with a capital, auto-correct on. */
    val Text = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)

    /** Usernames and email addresses: no capital, no auto-correct. */
    val Username = KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        keyboardType = KeyboardType.Email,
    )

    /** Website addresses. */
    val Website = KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        keyboardType = KeyboardType.Uri,
    )

    /** Passwords and secret values: the password keyboard, no auto-correct ([VaultSecureField]'s default). */
    val Secret = KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        keyboardType = KeyboardType.Password,
    )
}
