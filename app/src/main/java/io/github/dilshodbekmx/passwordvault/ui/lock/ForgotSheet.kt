package io.github.dilshodbekmx.passwordvault.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.i18n.rememberFormatters
import io.github.dilshodbekmx.passwordvault.ui.common.VaultDialog
import io.github.dilshodbekmx.passwordvault.ui.common.VaultSheet

/**
 * The "Forgot master password?" sheet (ux.md §5.10; lock.md F17 step 1): "Forgot your master
 * password?", "Nobody can recover it: it is not stored anywhere.", then [ForgotSheetUi.options], each
 * with its sentence and its button, in the order of F17 step 1. A `VaultSheet`, so the window policy
 * holds in it too (lock.md §13.5); dismissing it is its "Try again". Its test tags are resource ids
 * of its own window.
 */
@Composable
fun ForgotSheet(ui: ForgotSheetUi, onAction: (LockAction) -> Unit) {
    val formatters = rememberFormatters()
    VaultSheet(onDismissRequest = { onAction(LockAction.CloseSheet) }, skipPartiallyExpanded = true) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { testTagsAsResourceId = true }
                .testTag(LockTags.SHEET)
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.lock_sheet_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(text = stringResource(R.string.lock_sheet_body), style = MaterialTheme.typography.bodyLarge)
            for (option in ui.options) {
                option.text?.let {
                    Text(
                        text = it.show(formatters),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                option.button?.let { LockButtonView(it, formatters, onAction) }
            }
        }
    }
}

/**
 * The question asked once more (ux.md §5.10 "Start with an empty app?"; lock.md F16b "Open your
 * passwords as they were before 7 Oct?"): its title, its sentence, the confirming button and
 * "Cancel". Back and "Cancel" do nothing; a tap outside does not close it, so that a stray tap neither
 * confirms nor drops the question.
 */
@Composable
fun LockConfirmDialog(confirm: LockConfirm, onAction: (LockAction) -> Unit) {
    val formatters = rememberFormatters()
    val texts = confirmTexts(confirm)
    VaultDialog(
        onDismissRequest = { onAction(LockAction.CancelConfirm) },
        confirmButton = {
            TextButton(onClick = { onAction(LockAction.Confirm) }, modifier = Modifier.testTag(LockTags.CONFIRM_YES)) {
                Text(texts.yes.show(formatters))
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(LockAction.CancelConfirm) }, modifier = Modifier.testTag(LockTags.CONFIRM_CANCEL)) {
                Text(stringResource(R.string.common_cancel))
            }
        },
        title = { Text(texts.title.show(formatters)) },
        text = { Text(texts.body.show(formatters)) },
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag(LockTags.CONFIRM),
        properties = DialogProperties(dismissOnClickOutside = false),
    )
}

/** The texts of a question asked once more: its title, its sentence and its confirming button. */
internal data class ConfirmTexts(val title: UiText, val body: UiText, val yes: UiText)

internal fun confirmTexts(confirm: LockConfirm): ConfirmTexts = when (confirm) {
    LockConfirm.StartEmpty -> ConfirmTexts(
        UiText.of(R.string.lock_empty_title),
        UiText.of(R.string.lock_empty_body),
        UiText.of(R.string.lock_empty_confirm),
    )
    is LockConfirm.OpenFromBefore -> ConfirmTexts(
        UiText.of(R.string.lock_older_confirm_title, confirm.date),
        UiText.of(R.string.lock_older_confirm_body),
        UiText.of(R.string.lock_older_confirm_open),
    )
}
