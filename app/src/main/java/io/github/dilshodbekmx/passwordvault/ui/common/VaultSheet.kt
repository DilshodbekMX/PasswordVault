package io.github.dilshodbekmx.passwordvault.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.dilshodbekmx.passwordvault.window.VaultWindow

/**
 * The app's bottom sheet (R-13: with [VaultDialog], the only way to make one), such as "Forgot
 * master password?" and the generator (ux.md §5.10, §5.15): Material 3's `ModalBottomSheet`
 * (experimental in material3 1.4.0, accepted with the BOM pinned, ux.md §10) with its content inside
 * [VaultWindow]. The sheet is a window of its own: `VaultWindow` applies the window policy to it
 * and removes the text toolbar's "Autofill" item from the fields in it (lock.md §13.5).
 *
 * This form needs no opt-in from the caller: the sheet goes when the caller stops composing it.
 * To slide it away first, use the form that takes a `SheetState`.
 *
 * @param skipPartiallyExpanded true: the sheet opens fully, never half way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    skipPartiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    VaultSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded),
        modifier = modifier,
        content = content,
    )
}

/**
 * [VaultSheet] with a [sheetState] of the caller's, which can hide the sheet with its animation
 * (`sheetState.hide()`, then stop composing it). `SheetState` is experimental in material3 1.4.0,
 * so the caller opts in.
 */
@ExperimentalMaterial3Api
@Composable
fun VaultSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismissRequest, modifier = modifier, sheetState = sheetState) {
        VaultWindow {
            Column(content = content)
        }
    }
}
