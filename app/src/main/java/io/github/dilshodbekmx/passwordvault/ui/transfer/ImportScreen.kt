package io.github.dilshodbekmx.passwordvault.ui.transfer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.formats.Importer
import io.github.dilshodbekmx.passwordvault.i18n.Formatters
import io.github.dilshodbekmx.passwordvault.i18n.rememberFormatters
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.ui.common.Banner
import io.github.dilshodbekmx.passwordvault.ui.common.BannerKind
import io.github.dilshodbekmx.passwordvault.ui.common.VaultFieldDefaults
import io.github.dilshodbekmx.passwordvault.ui.common.VaultSecureField

/**
 * The import of the chosen file (`Route.Import(ImportStep.FILE)`; ux.md §5.21): "Reading the file…",
 * the preview ([ImportPreviewList]) with "Import 45 accounts" at the bottom, the result with "Undo
 * this import" and the question about the file, a file that cannot be imported, and for a backup
 * file its master password, its summary and the accounts added. [vm] does the work; this screen
 * shows its state and makes its moves.
 */
@Composable
internal fun ImportScreen(vm: ImportViewModel, nav: Navigator) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val formatters = rememberFormatters()
    val texts = remember(resources, formatters) { ImportTexts(resources, formatters) }
    LaunchedEffect(vm, texts) { vm.start(texts) }
    BackHandler { vm.back() }
    LaunchedEffect(ui.move) {
        when (ui.move ?: return@LaunchedEffect) {
            ImportMove.COLUMNS -> nav.go(Route.ColumnMapping)
            ImportMove.LEAVE -> nav.back()
            ImportMove.SOURCES -> nav.replace(Route.Import(ImportStep.SOURCES))
            ImportMove.SECURITY -> nav.tab(Route.Security)
        }
        vm.moved()
    }
    val snackbar = remember { SnackbarHostState() }
    Messages(vm, ui, snackbar)

    val title = stringResource(R.string.import_title)
    Scaffold(
        modifier = Modifier.semantics {
            paneTitle = title
            testTagsAsResourceId = true
        },
        topBar = { ImportTopBar(title, onBack = vm::back) },
        bottomBar = { BottomButton(vm, ui.screen) },
        snackbarHost = { SnackbarHost(snackbar) { data -> Snackbar(data, modifier = Modifier.testTag(ImportTags.MESSAGE)) } },
    ) { padding ->
        when (val screen = ui.screen) {
            is ImportScreen.Preview -> ImportPreviewList(vm, ui, screen, padding, formatters)
            ImportScreen.Gone, ImportScreen.Locked -> Unit
            else -> ImportPage(padding) {
                ImportHeader(ui)
                when (screen) {
                    ImportScreen.Reading -> ImportProgress(stringResource(R.string.import_reading), Modifier.testTag(ImportTags.READING))
                    ImportScreen.NeedsColumns -> NeedsColumns(vm)
                    is ImportScreen.Done -> Done(vm, ui, screen.done)
                    is ImportScreen.Problem -> Problem(vm, screen.problem, formatters)
                    is ImportScreen.BackupPassword -> BackupPassword(vm, screen)
                    is ImportScreen.BackupSummary -> BackupSummary(screen, formatters)
                    is ImportScreen.BackupAdded -> BackupAdded(screen)
                    else -> Unit
                }
            }
        }
    }
}

/** "This was paused when the app locked." and the file's name, above every state but the preview's own list. */
@Composable
internal fun ImportHeader(ui: ImportUi) {
    if (ui.paused) {
        Banner(
            text = stringResource(R.string.import_paused),
            modifier = Modifier.testTag(ImportTags.PAUSED).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    ui.fileName?.let { name ->
        Text(
            text = stringResource(R.string.import_file_name, name),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(ImportTags.FILE_NAME),
        )
    }
}

/** The one-time messages: a failed save with "Try again", the size limit, a failed undo. */
@Composable
private fun Messages(vm: ImportViewModel, ui: ImportUi, snackbar: SnackbarHostState) {
    val failed = stringResource(R.string.import_save_failed)
    val tooLarge = stringResource(R.string.import_too_large)
    val undoFailed = stringResource(R.string.import_undo_failed)
    val tryAgain = stringResource(R.string.common_try_again)
    LaunchedEffect(ui.message) {
        val message = ui.message ?: return@LaunchedEffect
        vm.messageShown()
        when (message) {
            ImportMessage.SAVE_FAILED -> {
                val result = snackbar.showSnackbar(failed, actionLabel = tryAgain, duration = SnackbarDuration.Long)
                if (result == SnackbarResult.ActionPerformed) {
                    if (vm.ui.value.screen is ImportScreen.BackupSummary) vm.addBackup() else vm.importNow()
                }
            }
            ImportMessage.TOO_LARGE -> snackbar.showSnackbar(tooLarge, duration = SnackbarDuration.Long)
            ImportMessage.UNDO_FAILED -> snackbar.showSnackbar(undoFailed, duration = SnackbarDuration.Long)
        }
    }
}

/** The main button of the state, at the bottom: "Import 45 accounts", "Open backup", "Add to my passwords", "Done". */
@Composable
private fun BottomButton(vm: ImportViewModel, screen: ImportScreen) {
    val button: (@Composable () -> Unit)? = when (screen) {
        is ImportScreen.Preview -> {
            {
                val preview = screen.preview
                if (preview.toAdd > 0) {
                    Wide(
                        onClick = vm::importNow,
                        enabled = preview.fits && !screen.planning && !screen.importing,
                        tag = ImportTags.IMPORT,
                        busy = screen.importing,
                        busyText = stringResource(R.string.import_importing),
                    ) {
                        Text(text = pluralStringResource(R.plurals.import_button, preview.toAdd, preview.toAdd))
                    }
                } else {
                    Wide(onClick = vm::chooseAnother, tag = ImportTags.CHOOSE_ANOTHER) {
                        Text(text = stringResource(R.string.import_choose_another))
                    }
                }
            }
        }
        is ImportScreen.Done -> {
            { Wide(onClick = vm::done, enabled = !screen.done.busy, tag = ImportTags.FINISH) { Text(text = stringResource(R.string.common_done)) } }
        }
        is ImportScreen.BackupAdded -> {
            { Wide(onClick = vm::done, tag = ImportTags.FINISH) { Text(text = stringResource(R.string.common_done)) } }
        }
        is ImportScreen.BackupPassword -> {
            {
                Wide(
                    onClick = vm::openBackup,
                    enabled = !screen.opening,
                    tag = ImportTags.BACKUP_OPEN,
                    busy = screen.opening,
                    busyText = stringResource(R.string.import_backup_opening),
                ) {
                    Text(text = stringResource(R.string.import_backup_open))
                }
            }
        }
        is ImportScreen.BackupSummary -> {
            {
                Wide(
                    onClick = vm::addBackup,
                    enabled = !screen.adding,
                    tag = ImportTags.BACKUP_ADD,
                    busy = screen.adding,
                    busyText = stringResource(R.string.import_backup_adding),
                ) {
                    Text(text = stringResource(R.string.import_backup_add))
                }
            }
        }
        is ImportScreen.Problem -> {
            { Wide(onClick = vm::chooseAnother, tag = ImportTags.CHOOSE_ANOTHER) { Text(text = stringResource(R.string.import_choose_another)) } }
        }
        else -> null
    }
    if (button == null) return
    Surface(tonalElevation = 3.dp) {
        Column(
            // 24 dp under the button keeps its middle above the band where the navigation bar may be,
            // which the emulator scripts do not tap (tools/emu/app.sh, pva_tap).
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            button()
        }
    }
}

/** A full-width primary button, at most 640 dp; while [busy], a small spinner named [busyText] in its place. */
@Composable
private fun Wide(
    onClick: () -> Unit,
    tag: String,
    enabled: Boolean = true,
    busy: Boolean = false,
    busyText: String = "",
    content: @Composable () -> Unit,
) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().testTag(tag)) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp).semantics { contentDescription = busyText },
                strokeWidth = 2.dp,
            )
        } else {
            content()
        }
    }
}

@Composable
private fun NeedsColumns(vm: ImportViewModel) {
    Banner(
        text = stringResource(R.string.import_columns_needed),
        kind = BannerKind.WARNING,
        modifier = Modifier.testTag(ImportTags.NEEDS_COLUMNS),
    ) {
        TextButton(onClick = vm::openColumns, modifier = Modifier.testTag(ImportTags.COLUMNS)) {
            Text(text = stringResource(R.string.import_open_columns))
        }
    }
    TextButton(onClick = vm::chooseAnother, modifier = Modifier.testTag(ImportTags.CHOOSE_ANOTHER)) {
        Text(text = stringResource(R.string.import_choose_another))
    }
}

@Composable
private fun Problem(vm: ImportViewModel, problem: ImportProblem, formatters: Formatters) {
    val text = when (problem) {
        ImportProblem.CANNOT_OPEN -> stringResource(R.string.import_problem_cannot_open)
        ImportProblem.EXCEL -> stringResource(R.string.import_problem_excel)
        ImportProblem.KEEPASS -> stringResource(R.string.import_problem_keepass)
        ImportProblem.EMPTY -> stringResource(R.string.import_problem_empty)
        ImportProblem.TOO_BIG -> stringResource(R.string.import_problem_too_big, formatters.number((Importer.MAX_BYTES / (1024 * 1024)).toLong()))
        ImportProblem.TOO_MANY -> stringResource(R.string.import_problem_too_many, formatters.number(Importer.MAX_ROWS.toLong()))
        ImportProblem.UNREADABLE -> stringResource(R.string.import_problem_unreadable)
        ImportProblem.BAD_JSON -> stringResource(R.string.import_problem_bad_json)
        ImportProblem.NO_MEMORY -> stringResource(R.string.import_problem_no_memory)
        ImportProblem.ENCRYPTED -> stringResource(R.string.import_problem_encrypted)
        ImportProblem.NOT_AN_EXPORT -> stringResource(R.string.import_problem_not_export)
        ImportProblem.BACKUP_DAMAGED -> stringResource(R.string.import_problem_backup_damaged)
        ImportProblem.BACKUP_NEWER -> stringResource(R.string.import_problem_backup_newer)
        ImportProblem.BACKUP_NOT_OURS -> stringResource(R.string.import_problem_backup_not_ours)
    }
    Banner(
        text = text,
        kind = BannerKind.WARNING,
        modifier = Modifier.testTag(ImportTags.PROBLEM).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun Done(vm: ImportViewModel, ui: ImportUi, done: ImportDone) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Icon(painterResource(R.drawable.ic_common_check), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        val headline = when {
            done.undone -> stringResource(R.string.import_undone)
            done.added > 0 -> pluralStringResource(R.plurals.import_done, done.added, done.added)
            else -> pluralStringResource(R.plurals.import_all_here, done.alreadyAll, done.alreadyAll)
        }
        Text(
            text = headline,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.testTag(ImportTags.DONE).semantics { heading() },
        )
    }
    if (done.canUndo) {
        TextButton(onClick = vm::undo, enabled = !done.busy, modifier = Modifier.testTag(ImportTags.UNDO)) {
            Text(text = stringResource(R.string.import_undo))
        }
    }
    if (done.askFile) {
        val question = ui.fileName?.let { stringResource(R.string.import_file_question, it) }
            ?: stringResource(R.string.import_file_question_unnamed)
        Banner(text = question, kind = BannerKind.WARNING, modifier = Modifier.testTag(ImportTags.FILE_QUESTION)) {
            OutlinedButton(onClick = vm::keepFile, enabled = !done.busy, modifier = Modifier.testTag(ImportTags.KEEP_FILE)) {
                Text(text = stringResource(R.string.import_keep_file))
            }
            Button(onClick = vm::deleteFile, enabled = !done.busy, modifier = Modifier.testTag(ImportTags.DELETE_FILE)) {
                Text(text = stringResource(R.string.import_delete_file))
            }
        }
    }
    done.file?.let { outcome ->
        val text = when (outcome) {
            FileOutcome.DELETED -> stringResource(R.string.import_file_deleted)
            FileOutcome.NOT_DELETED -> ui.fileName?.let { stringResource(R.string.import_file_not_deleted, it) }
                ?: stringResource(R.string.import_file_not_deleted_unnamed)
            FileOutcome.KEPT -> stringResource(R.string.import_file_kept)
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag(ImportTags.FILE_OUTCOME).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    if (done.manyShared && !done.undone) {
        Banner(text = stringResource(R.string.import_shared), modifier = Modifier.testTag(ImportTags.SHARED)) {
            TextButton(onClick = vm::openSecurity, modifier = Modifier.testTag(ImportTags.OPEN_SECURITY)) {
                Text(text = stringResource(R.string.import_open_security))
            }
        }
    }
}

@Composable
private fun BackupPassword(vm: ImportViewModel, screen: ImportScreen.BackupPassword) {
    Text(text = stringResource(R.string.import_backup_prompt), style = MaterialTheme.typography.bodyLarge)
    val error = when (screen.error) {
        BackupError.WRONG -> stringResource(R.string.import_backup_wrong)
        BackupError.WRONG_OR_DAMAGED -> stringResource(R.string.import_backup_wrong_or_damaged)
        BackupError.NO_MEMORY -> stringResource(R.string.import_backup_no_memory)
        null -> null
    }
    VaultSecureField(
        state = vm.backupPassword,
        modifier = Modifier.fillMaxWidth().testTag(ImportTags.BACKUP_PASSWORD),
        label = { Text(text = stringResource(R.string.import_backup_password)) },
        enabled = !screen.opening,
        isError = error != null,
        supportingText = error?.let { text -> { Text(text = text, modifier = Modifier.testTag(ImportTags.BACKUP_ERROR)) } },
        keyboardOptions = VaultFieldDefaults.Secret.copy(imeAction = ImeAction.Done),
        onKeyboardAction = { vm.openBackup() },
    )
}

@Composable
private fun BackupSummary(screen: ImportScreen.BackupSummary, formatters: Formatters) {
    Text(
        text = pluralStringResource(R.plurals.import_backup_summary, screen.accounts, screen.accounts, formatters.date(screen.createdMs)),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.testTag(ImportTags.BACKUP_SUMMARY).semantics { heading() },
    )
    Text(text = stringResource(R.string.import_backup_rules), style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun BackupAdded(screen: ImportScreen.BackupAdded) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Icon(painterResource(R.drawable.ic_common_check), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
            text = pluralStringResource(R.plurals.import_backup_added, screen.added, screen.added),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.testTag(ImportTags.BACKUP_ADDED).semantics { heading() },
        )
        if (screen.skipped > 0) {
            Text(text = pluralStringResource(R.plurals.import_backup_skipped, screen.skipped, screen.skipped), style = MaterialTheme.typography.bodyLarge)
        }
        if (screen.renamed > 0) {
            Text(text = pluralStringResource(R.plurals.import_backup_renamed, screen.renamed, screen.renamed), style = MaterialTheme.typography.bodyLarge)
        }
    }
}
