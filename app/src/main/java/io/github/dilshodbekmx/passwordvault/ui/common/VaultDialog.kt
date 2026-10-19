package io.github.dilshodbekmx.passwordvault.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.dilshodbekmx.passwordvault.window.VaultWindow

/**
 * The app's alert dialog (R-13: with [VaultSheet], the only way to make a dialog), with the look of
 * Material 3's `AlertDialog` (ux.md §10): its colours, shape, padding, type and button row, the
 * buttons from the end, wrapping onto more lines rather than squeezing (i18n.md §4.5 rule 1). Unlike
 * Material's, its [icon], [title] and [text] lie in one column that scrolls, between the top of the
 * dialog and its buttons, which stay in view: a long title in Uzbek or Russian, the phone's font at
 * 200 %, or a dialog opened while the keyboard still takes half the screen otherwise pushed the last
 * buttons out of the dialog, where they could not be reached (build plan 6.2: "Discard" of "Save
 * changes?"). So [text] must not scroll by itself.
 *
 * A dialog is a window of its own, which the activity's window policy does not reach: [VaultWindow]
 * applies it to the dialog's window (the touch filter, the autofill and accessibility opt-outs, the
 * hidden overlays, FLAG_SECURE in staging and release) and removes the text toolbar's "Autofill" item
 * from the fields in it (lock.md §13.5).
 */
@OptIn(ExperimentalMaterial3Api::class) // BasicAlertDialog is experimental in material3 1.4.0 (ux.md §10)
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
    BasicAlertDialog(onDismissRequest = onDismissRequest, modifier = modifier, properties = properties) {
        VaultWindow {
            Surface(
                shape = AlertDialogDefaults.shape,
                color = AlertDialogDefaults.containerColor,
                tonalElevation = AlertDialogDefaults.TonalElevation,
            ) {
                BoxWithConstraints(Modifier.sizeIn(minWidth = DIALOG_MIN_WIDTH, maxWidth = DIALOG_MAX_WIDTH)) {
                    // A text that scrolls by itself (a list of choices) is given the dialog's height as its
                    // limit, so that it can scroll inside the column that scrolls; any other scrolls with it.
                    val textLimit = if (constraints.hasBoundedHeight) maxHeight - DIALOG_PADDING * 2 else Dp.Infinity
                    Column(Modifier.padding(DIALOG_PADDING)) {
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                            if (icon != null) {
                                CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.iconContentColor) {
                                    Box(Modifier.padding(bottom = 16.dp).align(Alignment.CenterHorizontally)) { icon() }
                                }
                            }
                            if (title != null) {
                                CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.titleContentColor) {
                                    ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                                        Box(Modifier.padding(bottom = 16.dp).align(if (icon == null) Alignment.Start else Alignment.CenterHorizontally)) { title() }
                                    }
                                }
                            }
                            if (text != null) {
                                CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.textContentColor) {
                                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                                        Box(Modifier.padding(bottom = 24.dp).heightIn(max = textLimit).align(Alignment.Start)) { text() }
                                    }
                                }
                            }
                        }
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                            ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                                FlowRow(
                                    modifier = Modifier.align(Alignment.End),
                                    horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP, Alignment.End),
                                    verticalArrangement = Arrangement.spacedBy(BUTTON_ROW_GAP),
                                ) {
                                    dismissButton?.invoke()
                                    confirmButton()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Material 3's alert dialog: 280 to 560 dp wide, 24 dp of padding, buttons 8 dp apart and their lines 12 dp apart. */
private val DIALOG_MIN_WIDTH = 280.dp
private val DIALOG_MAX_WIDTH = 560.dp
private val DIALOG_PADDING = 24.dp
private val BUTTON_GAP = 8.dp
private val BUTTON_ROW_GAP = 12.dp

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
