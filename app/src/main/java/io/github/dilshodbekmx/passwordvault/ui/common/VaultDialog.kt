package io.github.dilshodbekmx.passwordvault.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.dilshodbekmx.passwordvault.window.VaultWindow

/**
 * The app's alert dialog (R-13: with [VaultSheet], the only way to make a dialog): Material 3's
 * `AlertDialog`, the stable button-slot version (ux.md §10), with every slot inside [VaultWindow].
 * A dialog is a window of its own, which the activity's window policy does not reach: `VaultWindow`
 * applies it to the dialog's window (the touch filter, the autofill and accessibility opt-outs, the
 * hidden overlays, FLAG_SECURE in staging and release) and removes the text toolbar's "Autofill"
 * item from the fields in it (lock.md §13.5).
 */
@Composable
fun VaultDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { VaultWindow(confirmButton) },
        modifier = modifier,
        dismissButton = dismissButton?.let { slot -> { VaultWindow(slot) } },
        icon = icon?.let { slot -> { VaultWindow(slot) } },
        title = title?.let { slot -> { VaultWindow(slot) } },
        text = text?.let { slot -> { VaultWindow(slot) } },
        properties = properties,
    )
}

/**
 * A dialog with content of its own, such as the full-screen "Show in big letters" (ux.md §5.13,
 * with `DialogProperties(usePlatformDefaultWidth = false)`): compose's `Dialog` with its content
 * inside [VaultWindow]. The content draws its own surface.
 */
@Composable
fun VaultDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        VaultWindow(content)
    }
}
