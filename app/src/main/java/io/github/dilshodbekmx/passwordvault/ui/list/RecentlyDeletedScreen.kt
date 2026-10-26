package io.github.dilshodbekmx.passwordvault.ui.list

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.data.SaveProblem
import io.github.dilshodbekmx.passwordvault.i18n.rememberFormatters
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.ui.common.TextBeside
import io.github.dilshodbekmx.passwordvault.ui.common.VaultDialog
import io.github.dilshodbekmx.passwordvault.ui.common.VaultSnackbarHost
import io.github.dilshodbekmx.passwordvault.ui.common.VaultTopBar
import io.github.dilshodbekmx.passwordvault.ui.common.mayTruncate
import io.github.dilshodbekmx.passwordvault.ui.common.userText
import kotlinx.coroutines.launch

/** The Recently deleted route (ux.md §5.23): its ViewModel over the app's vault, and the screen. */
@Composable
fun RecentlyDeletedRoute(graph: AppGraph, nav: Navigator) {
    val viewModel = viewModel { RecentlyDeletedViewModel.from(graph) }
    RecentlyDeletedScreen(viewModel, nav)
}

/**
 * Recently deleted (ux.md §5.23): a top bar with Back and "Empty", a line that says how long
 * accounts wait here, and each deleted account with the date it was deleted, "Restore" and, in its
 * menu, "Delete forever". Both deletions ask first.
 */
@OptIn(ExperimentalMaterial3Api::class) // TopAppBar's signature names TopAppBarScrollBehavior (ux.md §10)
@Composable
fun RecentlyDeletedScreen(viewModel: RecentlyDeletedViewModel, nav: Navigator) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val formatters = rememberFormatters()

    LaunchedEffect(viewModel, snackbar) {
        viewModel.events.collect { event ->
            launch {
                when (event) {
                    is DeletedEvent.Restored -> snackbar.showSnackbar(resources.getString(R.string.list_restored_message, event.title))
                    is DeletedEvent.NotSaved -> {
                        val retry = event.retry
                        val result = snackbar.showSnackbar(
                            message = resources.getString(if (event.problem == SaveProblem.TOO_LARGE) R.string.list_too_large else R.string.list_save_failed),
                            actionLabel = if (retry != null) resources.getString(R.string.common_try_again) else null,
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) retry?.invoke()
                    }
                }
            }
        }
    }

    val title = stringResource(R.string.list_deleted_title)
    Scaffold(
        modifier = Modifier.semantics { paneTitle = title },
        topBar = {
            VaultTopBar(
                title = title,
                navigationIcon = {
                    IconButton(onClick = { nav.back() }) {
                        Icon(painterResource(R.drawable.ic_list_back), contentDescription = stringResource(R.string.list_back))
                    }
                },
                actions = {
                    if (!rows.isNullOrEmpty()) {
                        TextButton(onClick = viewModel::askEmpty, modifier = Modifier.testTag(ListTags.DELETED_EMPTY)) {
                            Text(stringResource(R.string.list_deleted_empty))
                        }
                    }
                },
            )
        },
        snackbarHost = { VaultSnackbarHost(snackbar) },
    ) { padding ->
        val shown = rows
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag(ListTags.DELETED_LIST),
        ) {
            if (shown != null) {
                item(key = "info") {
                    Text(
                        text = stringResource(if (shown.isEmpty()) R.string.list_deleted_nothing else R.string.list_deleted_info),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .then(if (shown.isEmpty()) Modifier.testTag(ListTags.DELETED_NOTHING) else Modifier),
                    )
                }
                items(shown, key = { it.id }) { row ->
                    DeletedRowItem(
                        row = row,
                        deletedOn = stringResource(R.string.list_deleted_on, formatters.date(row.deletedAtMs)),
                        onRestore = { viewModel.restore(row.id) },
                        onDeleteForever = { viewModel.askDeleteForever(row.id) },
                    )
                }
            }
        }
    }

    when (val asked = dialog) {
        null -> Unit
        is DeletedDialog.Forever -> Question(
            text = stringResource(R.string.list_deleted_forever_question, asked.title),
            onConfirm = viewModel::confirm,
            onDismiss = viewModel::dismiss,
        )
        is DeletedDialog.Empty -> Question(
            text = pluralStringResource(R.plurals.list_deleted_empty_question, asked.count, formatters.number(asked.count.toLong())),
            onConfirm = viewModel::confirm,
            onDismiss = viewModel::dismiss,
        )
    }
}

/**
 * One deleted account: its name, "Deleted Oct 6, 2026", "Restore" and the ⋮ menu with "Delete forever".
 * The two buttons stand beside the name and the date while those keep half the row, and under them
 * otherwise (TextBeside): at 320 dp and 200 % the date, whose parts no-break spaces hold together, was
 * squeezed into a column narrower than itself and broke inside its year (build plan 6.2).
 */
@Composable
private fun DeletedRowItem(row: DeletedRow, deletedOn: String, onRestore: () -> Unit, onDeleteForever: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val restore = stringResource(R.string.list_deleted_restore_description, row.title)
    ListItem(
        headlineContent = {
            TextBeside(
                text = {
                    Column {
                        // The row may cut the name: the account's screen, opened from it, shows it whole (i18n.md §4.5 rule 3).
                        Text(row.title, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.mayTruncate().userText())
                        Text(deletedOn, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                action = {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = onRestore,
                                modifier = Modifier.semantics { contentDescription = restore }.testTag(ListTags.restore(row.id)),
                            ) {
                                Text(stringResource(R.string.list_deleted_restore))
                            }
                            Box {
                                IconButton(onClick = { menu = true }, modifier = Modifier.testTag(ListTags.deletedMore(row.id))) {
                                    Icon(painterResource(R.drawable.ic_list_more), contentDescription = stringResource(R.string.list_deleted_more, row.title))
                                }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.list_deleted_delete_forever)) },
                                        onClick = {
                                            menu = false
                                            onDeleteForever()
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
        modifier = Modifier.testTag(ListTags.deletedRow(row.id)),
    )
}

/** A question before something that cannot be undone: "Delete" and "Cancel" (ux.md §5.23). */
@Composable
private fun Question(text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    VaultDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(ListTags.DIALOG_CONFIRM)) {
                Text(stringResource(R.string.common_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
        text = { Text(text) },
    )
}
