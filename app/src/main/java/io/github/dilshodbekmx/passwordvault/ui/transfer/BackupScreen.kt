package io.github.dilshodbekmx.passwordvault.ui.transfer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.files.Place
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.i18n.rememberFormatters
import io.github.dilshodbekmx.passwordvault.i18n.resolve
import io.github.dilshodbekmx.passwordvault.nav.Navigator

/**
 * "Make a backup" (ux.md §5.19), each step of [BackupViewModel] in turn. Back closes the screen,
 * except while a step runs (ignored, ux.md §4) and on a backup whose place was already chosen, where
 * it gives the backup up and says what became of its file, as "Cancel" does.
 */
@Composable
fun BackupScreen(vm: BackupViewModel, nav: Navigator, modifier: Modifier = Modifier) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.leave.collect { nav.back() } }
    BackHandler(enabled = vm.busy || ui.paused) { vm.cancel() }
    BackupScaffold(
        title = stringResource(R.string.backup_title),
        onBack = { if (vm.busy || ui.paused) vm.cancel() else nav.back() },
        modifier = modifier,
    ) {
        BackupContent(ui, vm, done = { nav.back() })
    }
}

/** The content of one [BackupUi]: its texts, its field and its buttons; [done] closes the screen. */
@Composable
fun BackupContent(ui: BackupUi, vm: BackupViewModel, done: () -> Unit) {
    val formatters = rememberFormatters()
    when (val step = ui.step) {
        BackupStep.Explain -> {
            BackupLine(UiText.of(R.string.backup_explain), BackupTags.TEXT, formatters = formatters)
            Button(onClick = vm::explained, modifier = Modifier.fillMaxWidth().testTag(BackupTags.CONTINUE)) {
                Text(stringResource(R.string.backup_explain_continue))
            }
        }
        BackupStep.Pending -> {
            BackupLine(UiText.of(R.string.backup_pending), BackupTags.TEXT, formatters = formatters)
            Button(onClick = done, modifier = Modifier.fillMaxWidth().testTag(BackupTags.DONE)) {
                Text(stringResource(R.string.common_ok))
            }
        }
        is BackupStep.Password -> {
            if (ui.paused) BackupLine(UiText.of(R.string.backup_paused), BackupTags.PAUSED, formatters = formatters)
            BackupLine(UiText.of(R.string.backup_password_text), BackupTags.TEXT, formatters = formatters)
            val waiting = ui.waitSeconds != null
            BackupPasswordField(
                state = vm.password,
                label = stringResource(R.string.backup_password_field),
                enabled = !step.checking && !waiting,
                isError = step.error != null,
                onSubmit = vm::submit,
            )
            step.error?.let { BackupLine(BackupTexts.passwordError(it), BackupTags.ERROR, alert = true, formatters = formatters) }
            if (waiting) {
                BackupLine(UiText.of(R.string.backup_wait), BackupTags.line(0), formatters = formatters)
                BackupLine(UiText.plural(R.plurals.backup_wait_seconds, ui.waitSeconds), BackupTags.COUNTDOWN, formatters = formatters)
            }
            if (step.checking) {
                BackupProgress(UiText.of(R.string.backup_checking))
            } else {
                Button(
                    onClick = vm::submit,
                    enabled = !waiting && vm.password.text.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().testTag(BackupTags.SUBMIT),
                ) {
                    Text(stringResource(R.string.backup_make))
                }
                if (ui.paused) {
                    TextButton(onClick = vm::cancel, modifier = Modifier.testTag(BackupTags.CANCEL)) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            }
        }
        is BackupStep.Working -> BackupProgress(BackupTexts.phase(step.phase))
        is BackupStep.Choose -> {
            BackupLine(UiText.of(R.string.backup_choose_text), BackupTags.TEXT, formatters = formatters)
            Button(onClick = vm::choose, enabled = !step.opening, modifier = Modifier.fillMaxWidth().testTag(BackupTags.CHOOSE)) {
                Text(stringResource(R.string.backup_choose))
            }
        }
        is BackupStep.Saved -> SavedContent(step, vm, done)
        is BackupStep.Failed -> {
            BackupLine(BackupTexts.failure(step.why), BackupTags.ERROR, alert = true, formatters = formatters)
            BackupTexts.failedFile(step.cleanup, step.name)?.let { BackupLine(it, BackupTags.line(0), formatters = formatters) }
            Button(onClick = vm::retry, modifier = Modifier.fillMaxWidth().testTag(BackupTags.RETRY)) {
                Text(stringResource(if (step.retry == Retry.CHOOSE) R.string.backup_choose_other else R.string.common_try_again))
            }
            OutlinedButton(onClick = done, modifier = Modifier.fillMaxWidth().testTag(BackupTags.DONE)) {
                Text(stringResource(R.string.common_close))
            }
        }
        is BackupStep.Stopped -> {
            BackupTexts.stopped(step.cleanup, step.name)?.let { BackupLine(it, BackupTags.TEXT, formatters = formatters) }
            Button(onClick = done, modifier = Modifier.fillMaxWidth().testTag(BackupTags.DONE)) {
                Text(stringResource(R.string.common_ok))
            }
        }
    }
}

/**
 * Step 5 (ux.md §5.19): the result, the line of its place, and "Send to another app…": the main
 * button when the copy is on this phone, an outlined one for another place, none for Google Drive.
 */
@Composable
private fun SavedContent(step: BackupStep.Saved, vm: BackupViewModel, done: () -> Unit) {
    val formatters = rememberFormatters()
    val resources = LocalResources.current
    val lines = BackupTexts.saved(step)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(R.drawable.ic_common_check), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
            text = lines.first().withDates(formatters).resolve(),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).semantics { heading() }.testTag(BackupTags.line(0)),
        )
    }
    lines.drop(1).forEachIndexed { index, line -> BackupLine(line, BackupTags.line(index + 1), formatters = formatters) }
    if (step.sendFailed) BackupLine(UiText.of(R.string.backup_send_failed), BackupTags.ERROR, alert = true, formatters = formatters)
    val send = { vm.send(resources.getString(R.string.backup_send_title)) }
    when (step.place) {
        Place.PhoneStorage -> {
            BackupLine(UiText.of(R.string.backup_send_text), BackupTags.TEXT, formatters = formatters)
            Button(onClick = send, modifier = Modifier.fillMaxWidth().testTag(BackupTags.SEND)) { Text(stringResource(R.string.backup_send)) }
            OutlinedButton(onClick = done, modifier = Modifier.fillMaxWidth().testTag(BackupTags.DONE)) { Text(stringResource(R.string.common_done)) }
        }
        Place.GoogleDrive -> {
            Button(onClick = done, modifier = Modifier.fillMaxWidth().testTag(BackupTags.DONE)) { Text(stringResource(R.string.common_done)) }
        }
        is Place.Other -> {
            Button(onClick = done, modifier = Modifier.fillMaxWidth().testTag(BackupTags.DONE)) { Text(stringResource(R.string.common_done)) }
            BackupLine(UiText.of(R.string.backup_send_text), BackupTags.TEXT, formatters = formatters)
            OutlinedButton(onClick = send, modifier = Modifier.fillMaxWidth().testTag(BackupTags.SEND)) { Text(stringResource(R.string.backup_send)) }
        }
    }
}
