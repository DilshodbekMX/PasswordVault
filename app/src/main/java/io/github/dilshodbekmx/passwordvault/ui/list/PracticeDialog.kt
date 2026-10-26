package io.github.dilshodbekmx.passwordvault.ui.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.i18n.rememberFormatters
import io.github.dilshodbekmx.passwordvault.ui.common.VaultDialog
import io.github.dilshodbekmx.passwordvault.ui.common.VaultFieldDefaults
import io.github.dilshodbekmx.passwordvault.ui.common.VaultSecureField
import kotlinx.coroutines.delay

/**
 * "Type it now" on the practice banner (lock.md §13.4 item 6): the master password, typed in the
 * open app and checked by the lock as practice (`MpPurpose.PRACTICE`, lock.md §6.8), in a dialog
 * with the lock's texts for a wrong password, a wait, too little memory and an older password
 * (lock.md §13.3). The field's text is held by the ViewModel ([password]) and never reaches saved
 * state; the field asks for the keyboard when the dialog appears, also after a rotation [V-P7].
 *
 * @param elapsedMs `elapsedRealtime()` now, for the wait's countdown.
 * @param onWaitOver the wait has passed: the field takes a try again.
 */
@Composable
internal fun PracticeDialog(
    state: PracticeState,
    password: TextFieldState,
    elapsedMs: () -> Long,
    onCheck: () -> Unit,
    onCancel: () -> Unit,
    onWaitOver: () -> Unit,
) {
    val error = state.error
    val waiting = error as? PracticeError.Wait
    var secondsLeft by remember(waiting) { mutableLongStateOf(0L) }
    LaunchedEffect(waiting) {
        if (waiting == null) return@LaunchedEffect
        while (true) {
            val left = waiting.untilElapsedMs - elapsedMs()
            if (left <= 0L) {
                onWaitOver()
                return@LaunchedEffect
            }
            secondsLeft = (left + 999L) / 1000L
            delay(minOf(left, 1_000L))
        }
    }
    val formatters = rememberFormatters()
    val message: String? = when (error) {
        null -> null
        PracticeError.Wrong -> stringResource(R.string.list_practice_wrong)
        is PracticeError.Wait -> pluralStringResource(R.plurals.list_practice_wait, secondsLeft.toInt(), secondsLeft.toInt())
        PracticeError.NoMemory -> stringResource(R.string.list_practice_no_memory)
        is PracticeError.Older -> stringResource(R.string.list_practice_older, formatters.date(error.changedAtMs))
    }
    val open = !state.checking && waiting == null
    val focus = remember { FocusRequester() }
    VaultDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            TextButton(onClick = onCheck, enabled = open, modifier = Modifier.testTag(ListTags.PRACTICE_CHECK)) {
                Text(stringResource(R.string.list_practice_check))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
        },
        title = { Text(stringResource(R.string.list_practice_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.list_practice_body))
                VaultSecureField(
                    state = password,
                    label = stringResource(R.string.list_practice_field),
                    enabled = open,
                    isError = message != null,
                    supportingText = message?.let { text -> { Text(text) } },
                    keyboardOptions = VaultFieldDefaults.Secret.copy(imeAction = ImeAction.Done),
                    onKeyboardAction = { onCheck() },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(ListTags.PRACTICE_FIELD),
                )
                if (state.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                // In the dialog's own composition, where the field is: the keyboard comes up with the
                // dialog, after a rotation too, and again once a wait is over.
                LaunchedEffect(open) {
                    if (open) focus.requestFocus()
                }
            }
        },
    )
}
