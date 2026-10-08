package io.github.dilshodbekmx.passwordvault.ui.transfer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideVault
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.i18n.rememberFormatters
import io.github.dilshodbekmx.passwordvault.i18n.resolve
import io.github.dilshodbekmx.passwordvault.nav.Navigator

/**
 * Settings › Old passwords (ux.md §5.10 "Archived vaults", §5.18): the vaults put aside, each with
 * "Open" and "Delete", and the step that asks for that vault's own master password
 * ([OldPasswordsViewModel]). Back on a step returns to the list; while the lock works on it, Back is
 * ignored.
 */
@Composable
fun OldPasswordsScreen(vm: OldPasswordsViewModel, nav: Navigator, modifier: Modifier = Modifier) {
    val step by vm.step.collectAsStateWithLifecycle()
    val vaults by vm.vaults.collectAsStateWithLifecycle()
    val waitSeconds by vm.waitSeconds.collectAsStateWithLifecycle()
    BackHandler(enabled = step is OldStep.Asking) { vm.back() }
    BackupScaffold(
        title = stringResource(R.string.backup_old_title),
        onBack = { if (!vm.back()) nav.back() },
        modifier = modifier,
    ) {
        when (val now = step) {
            is OldStep.List -> OldList(now, vaults, vm)
            is OldStep.Asking -> OldAsking(now, waitSeconds, vm)
        }
    }
}

@Composable
private fun OldList(step: OldStep.List, vaults: List<AsideVault>, vm: OldPasswordsViewModel) {
    val formatters = rememberFormatters()
    step.done?.let { BackupLine(BackupTexts.oldDone(it), BackupTags.OLD_DONE, formatters = formatters) }
    if (vaults.isEmpty()) {
        BackupLine(UiText.of(R.string.backup_old_none), BackupTags.OLD_NONE, formatters = formatters)
        return
    }
    BackupLine(UiText.of(R.string.backup_old_intro), BackupTags.TEXT, formatters = formatters)
    Column(Modifier.fillMaxWidth().testTag(BackupTags.OLD_LIST), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (vault in vaults) {
            OutlinedCard(Modifier.fillMaxWidth().testTag(BackupTags.oldItem(vault.vaultId))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = BackupTexts.oldItem(vault.putAsideAtMs).withDates(formatters).resolve(),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (vault.newest == null) {
                        Text(
                            text = stringResource(R.string.backup_old_unreadable),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        TextButton(onClick = { vm.ask(vault, OldAction.DELETE) }, modifier = Modifier.testTag(BackupTags.oldDelete(vault.vaultId))) {
                            Text(stringResource(R.string.common_delete))
                        }
                        TextButton(onClick = { vm.ask(vault, OldAction.OPEN) }, modifier = Modifier.testTag(BackupTags.oldOpen(vault.vaultId))) {
                            Text(stringResource(R.string.backup_old_open))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OldAsking(step: OldStep.Asking, waitSeconds: Int?, vm: OldPasswordsViewModel) {
    val formatters = rememberFormatters()
    val open = step.action == OldAction.OPEN
    Text(
        text = BackupTexts.oldTitle(step.action, step.vault.putAsideAtMs).withDates(formatters).resolve(),
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { heading() }.testTag(BackupTags.TEXT),
    )
    BackupLine(UiText.of(if (open) R.string.backup_old_open_text else R.string.backup_old_delete_text), BackupTags.line(0), formatters = formatters)
    val waiting = waitSeconds != null
    BackupPasswordField(
        state = vm.password,
        label = stringResource(R.string.backup_old_field),
        enabled = !step.working && !waiting,
        isError = step.problem != null,
        onSubmit = vm::submit,
    )
    step.problem?.let { BackupLine(BackupTexts.oldProblem(it, step.action), BackupTags.ERROR, alert = true, formatters = formatters) }
    if (waiting) {
        BackupLine(UiText.of(R.string.backup_wait), BackupTags.line(1), formatters = formatters)
        BackupLine(UiText.plural(R.plurals.backup_wait_seconds, waitSeconds ?: 0), BackupTags.COUNTDOWN, formatters = formatters)
    }
    if (step.working) {
        BackupProgress(UiText.of(if (open) R.string.backup_old_adding else R.string.backup_old_deleting))
    } else {
        Button(
            onClick = vm::submit,
            enabled = !waiting && vm.password.text.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().testTag(BackupTags.SUBMIT),
        ) {
            Text(stringResource(if (open) R.string.backup_old_open_button else R.string.backup_old_delete_button))
        }
        TextButton(onClick = { vm.back() }, modifier = Modifier.testTag(BackupTags.CANCEL)) {
            Text(stringResource(R.string.common_cancel))
        }
    }
}
