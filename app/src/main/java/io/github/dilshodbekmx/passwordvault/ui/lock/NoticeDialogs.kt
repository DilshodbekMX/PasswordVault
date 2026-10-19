package io.github.dilshodbekmx.passwordvault.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.i18n.rememberFormatters
import io.github.dilshodbekmx.passwordvault.ui.common.LongLabelShape
import io.github.dilshodbekmx.passwordvault.ui.common.VaultDialog
import io.github.dilshodbekmx.passwordvault.ui.common.VaultFieldDefaults
import io.github.dilshodbekmx.passwordvault.ui.common.VaultSecureField

/**
 * A notice after unlock as an `AlertDialog` (ux.md §5.9 "After unlock"; lock.md §13.4): its title if
 * it has one, its lines, and its answers, wrapping onto a second line rather than squeezing (i18n.md
 * §4.5). Back gives the answer that changes nothing ([NoticeUi.dismiss]); a tap outside does
 * nothing, so that a stray tap answers no notice. Its test tags are resource ids of its own window.
 */
@Composable
internal fun NoticeDialog(ui: NoticeUi, onAnswer: (NoticeButton) -> Unit, onDismiss: () -> Unit) {
    val formatters = rememberFormatters()
    VaultDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                for (button in ui.buttons) {
                    TextButton(onClick = { onAnswer(button) }, modifier = Modifier.testTag(button.tag), shape = LongLabelShape) {
                        Text(button.label.show(formatters))
                    }
                }
            }
        },
        title = ui.title?.let { title -> { Text(title.show(formatters), modifier = Modifier.testTag(NoticeTags.TITLE)) } },
        text = if (ui.lines.isEmpty()) {
            null
        } else {
            {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ui.lines.forEachIndexed { index, line ->
                        Text(line.show(formatters), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(NoticeTags.line(index)))
                    }
                }
            }
        },
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag(NoticeTags.NOTICE),
        properties = DialogProperties(dismissOnClickOutside = false),
    )
}

/**
 * "Type master password" (lock.md §13.4 item 4): the master password typed in the open app and
 * checked as practice, in a dialog with the lines of lock.md §13.3 for a wrong password, a wait, too
 * little memory and an older password. The field's text is the ViewModel's ([password]) and never
 * reaches saved state; the field asks for the keyboard when the dialog appears, after a rotation too,
 * and again once a wait is over.
 */
@Composable
internal fun PromptDialog(ui: PromptUi, password: TextFieldState, onCheck: () -> Unit, onCancel: () -> Unit) {
    val formatters = rememberFormatters()
    val focus = remember { FocusRequester() }
    VaultDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            TextButton(onClick = onCheck, enabled = ui.fieldEnabled, modifier = Modifier.testTag(NoticeTags.PROMPT_CHECK)) {
                Text(stringResource(R.string.lock_prompt_check))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.testTag(NoticeTags.PROMPT_CANCEL)) {
                Text(stringResource(R.string.common_cancel))
            }
        },
        title = { Text(stringResource(R.string.lock_mp_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                VaultSecureField(
                    state = password,
                    label = { Text(stringResource(R.string.lock_mp_field)) },
                    enabled = ui.fieldEnabled,
                    isError = ui.lines.any { it.alert },
                    keyboardOptions = VaultFieldDefaults.Secret.copy(imeAction = ImeAction.Done),
                    onKeyboardAction = { onCheck() },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(NoticeTags.PROMPT_FIELD),
                )
                ui.lines.forEachIndexed { index, line -> LockLineText(line, formatters, NoticeTags.promptLine(index)) }
                ui.countdown?.let { Text(it.show(formatters), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(NoticeTags.PROMPT_COUNTDOWN)) }
                if (ui.checking) LinearProgressIndicator(Modifier.fillMaxWidth().testTag(NoticeTags.PROMPT_CHECKING))
                // In the dialog's own composition, where the field is: the keyboard comes up with the
                // dialog, after a rotation too, and again once a wait is over.
                LaunchedEffect(ui.fieldEnabled) {
                    if (ui.fieldEnabled) focus.requestFocus()
                }
            }
        },
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag(NoticeTags.PROMPT),
    )
}
