package io.github.dilshodbekmx.passwordvault.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.dilshodbekmx.passwordvault.ui.theme.LocalLook
import io.github.dilshodbekmx.passwordvault.window.vaultTextField

/**
 * The list's search field (ux.md §5.11): Material 3's `SearchBarDefaults.InputField`, used as an
 * in-place filter, never in the expanded full-screen mode, with `vaultTextField()` like every text
 * field of the app (ux.md §3): no autofill session for it, and hidden from accessibility services
 * that are not accessibility tools. [state] is held by the screen's ViewModel; a search query never
 * reaches saved state (architecture.md §5.6). The keyboard's search key calls [onSearch].
 *
 * `InputField` is a text field (a `BasicTextField` underneath); R-13 allows it only here. Where the
 * style's field has nearly the colour of the screen around it, it has an outline of 1 dp (Emerald in
 * light, `Look.searchOutline`; docs/design/looks/README.md).
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
    val outline = LocalLook.current.searchOutline
    val shape = SearchBarDefaults.inputFieldShape
    SearchBarDefaults.InputField(
        state = state,
        onSearch = onSearch,
        expanded = false,
        onExpandedChange = {},
        modifier = modifier
            .then(if (outline != null) Modifier.border(OUTLINE_WIDTH, outline, shape) else Modifier)
            .vaultTextField(),
        enabled = enabled,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = shape,
        interactionSource = interactionSource,
    )
}

private val OUTLINE_WIDTH = 1.dp
