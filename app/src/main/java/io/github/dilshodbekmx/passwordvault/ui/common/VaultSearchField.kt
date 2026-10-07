package io.github.dilshodbekmx.passwordvault.ui.common

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.dilshodbekmx.passwordvault.window.vaultTextField

/**
 * The list's search field (ux.md §5.11): Material 3's `SearchBarDefaults.InputField`, used as an
 * in-place filter, never in the expanded full-screen mode, with `vaultTextField()` like every text
 * field of the app (ux.md §3): no autofill session for it, and hidden from accessibility services
 * that are not accessibility tools. [state] is held by the screen's ViewModel; a search query never
 * reaches saved state (architecture.md §5.6). The keyboard's search key calls [onSearch].
 *
 * `InputField` is a text field that R-13's list does not name yet; this is its wrapper.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultSearchField(
    state: TextFieldState,
    onSearch: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    SearchBarDefaults.InputField(
        state = state,
        onSearch = onSearch,
        expanded = false,
        onExpandedChange = {},
        modifier = modifier.vaultTextField(),
        enabled = enabled,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        interactionSource = interactionSource,
    )
}
