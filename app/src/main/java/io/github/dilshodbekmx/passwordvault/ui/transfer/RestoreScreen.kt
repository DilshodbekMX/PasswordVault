package io.github.dilshodbekmx.passwordvault.ui.transfer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.i18n.rememberFormatters
import io.github.dilshodbekmx.passwordvault.nav.Navigator

/**
 * "Restore from a backup file" (ux.md §5.2, and §5.10's sheet over the lock screen), each step of
 * [RestoreViewModel]. Back gives the restore up (its file task ends) and returns to Welcome or the
 * lock screen; it is ignored while a step runs.
 */
@Composable
fun RestoreScreen(vm: RestoreViewModel, nav: Navigator, modifier: Modifier = Modifier) {
    val step by vm.step.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.leave.collect { nav.back() } }
    val holds = step !is RestoreStep.Start || vm.busy
    BackHandler(enabled = holds) { vm.leave() }
    BackupScaffold(
        title = stringResource(R.string.backup_restore_title),
        onBack = { if (holds) vm.leave() else nav.back() },
        modifier = modifier,
    ) {
        RestoreContent(step, vm)
    }
}

/** The content of one [RestoreStep]. */
@Composable
fun RestoreContent(step: RestoreStep, vm: RestoreViewModel) {
    val formatters = rememberFormatters()
    when (step) {
        is RestoreStep.Start -> {
            BackupLine(UiText.of(R.string.backup_restore_intro), BackupTags.TEXT, formatters = formatters)
            step.problem?.let { BackupLine(BackupTexts.restoreProblem(it), BackupTags.ERROR, alert = true, formatters = formatters) }
            Button(onClick = vm::choose, enabled = !step.opening, modifier = Modifier.fillMaxWidth().testTag(BackupTags.CHOOSE)) {
                Text(stringResource(if (step.problem != null) R.string.backup_restore_choose_other else R.string.backup_restore_choose))
            }
        }
        RestoreStep.Reading -> BackupProgress(UiText.of(R.string.backup_restore_reading))
        is RestoreStep.Password -> {
            BackupLine(UiText.of(R.string.backup_restore_password_text), BackupTags.TEXT, formatters = formatters)
            BackupPasswordField(
                state = vm.password,
                label = stringResource(R.string.backup_restore_field),
                enabled = !step.opening,
                isError = step.problem != null,
                onSubmit = vm::submit,
            )
            step.problem?.let { BackupLine(BackupTexts.restoreProblem(it), BackupTags.ERROR, alert = true, formatters = formatters) }
            if (step.opening) {
                BackupProgress(UiText.of(R.string.backup_restore_opening))
            } else {
                Button(onClick = vm::submit, enabled = vm.password.text.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag(BackupTags.SUBMIT)) {
                    Text(stringResource(R.string.backup_restore_open))
                }
                TextButton(onClick = vm::choose, modifier = Modifier.testTag(BackupTags.CHOOSE)) {
                    Text(stringResource(R.string.backup_restore_choose_other))
                }
            }
        }
        is RestoreStep.Summary -> {
            BackupLine(BackupTexts.summary(step), BackupTags.SUMMARY, formatters = formatters)
            if (step.overCurrent) BackupLine(UiText.of(R.string.backup_restore_stays), BackupTags.STAYS, formatters = formatters)
            step.problem?.let { BackupLine(BackupTexts.restoreProblem(it), BackupTags.ERROR, alert = true, formatters = formatters) }
            if (step.restoring) {
                BackupProgress(UiText.of(R.string.backup_restore_restoring))
            } else {
                Button(onClick = vm::restore, modifier = Modifier.fillMaxWidth().testTag(BackupTags.RESTORE)) {
                    Text(
                        stringResource(
                            when {
                                step.problem != null -> R.string.common_try_again
                                step.overCurrent -> R.string.backup_restore_use
                                else -> R.string.backup_restore_restore
                            },
                        ),
                    )
                }
            }
        }
    }
}
