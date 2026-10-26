package io.github.dilshodbekmx.passwordvault.ui.entry

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.data.SaveProblem
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.ui.common.VaultSnackbarHost
import io.github.dilshodbekmx.passwordvault.ui.common.VaultTopBar
import io.github.dilshodbekmx.passwordvault.ui.list.GoneNotice
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One account's screen (ux.md §5.12): a top bar with Back, the account's name on one line, the
 * star, "Edit" and a menu (Show in big letters · Leave out of the security check · Delete); then the
 * whole name, the banners, and a row for each value with its buttons ([EntryRows]). [vm] holds
 * what is shown; [nav] opens the form, "Show in big letters" and the screens the banners lead to.
 *
 * - **Hidden values** stay hidden until "Show". Every touch on the screen is reported to [vm], which
 *   hides them again 30 seconds after the last one; the screen stopping hides them at once ([OnScreenStopped]).
 * - **A copy** turns the row's copy button into a check mark for 1.5 seconds, with a haptic tick
 *   (ux.md §6 item 3). No toast and no snackbar says "copied": the clipboard row at the bottom of
 *   the app does (ux.md §6 item 4).
 * - **Delete** needs no question: the screen says "Gmail deleted" with "Undo" for about 10 seconds
 *   and then closes; the account waits in Recently deleted (ux.md §5.23).
 * - **An account that is not in the vault at all** (for example removed for good from Recently
 *   deleted while this screen was kept): the screen goes back to the list by itself and leaves
 *   [GoneNotice]'s mark, so that the list says "This account no longer exists." (ux.md §5.12). One
 *   deleted elsewhere that waits in Recently deleted says so here, with "Open Recently deleted".
 * - **Saved state** holds only the scroll position (architecture.md §5.6).
 *
 * The test tags (resource ids, for the emulator scripts) are in [EntryTags].
 */
@OptIn(ExperimentalMaterial3Api::class) // TopAppBar's signature names TopAppBarScrollBehavior (ux.md §10)
@Composable
fun EntryScreen(vm: EntryViewModel, nav: Navigator) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    OnScreenStopped(whenCovered = true, onStopped = vm::screenStopped)

    // The copy button that shows its check mark for 1.5 s (ux.md §6 item 3).
    var copied by remember { mutableStateOf<EntryValue?>(null) }
    LaunchedEffect(copied) {
        if (copied != null) {
            delay(COPY_CONFIRMATION_MS)
            copied = null
        }
    }

    LaunchedEffect(vm, snackbar) {
        vm.events.collect { event ->
            launch {
                when (event) {
                    EntryEvent.Saved -> snackbar.showSnackbar(resources.getString(R.string.entry_saved))
                    EntryEvent.CopyBlocked ->
                        snackbar.showSnackbar(resources.getString(R.string.entry_copy_blocked), duration = SnackbarDuration.Long)
                    is EntryEvent.NotSaved -> {
                        val retry = event.retry
                        val large = event.problem == SaveProblem.TOO_LARGE
                        val result = snackbar.showSnackbar(
                            message = resources.getString(if (large) R.string.entry_too_large else R.string.entry_save_failed),
                            actionLabel = if (retry != null) resources.getString(R.string.common_try_again) else null,
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) retry?.invoke()
                    }
                }
            }
        }
    }

    // "Gmail deleted" with "Undo" for about 10 seconds; then the screen closes (ux.md §5.12).
    val deletedTitle = (state as? EntryUiState.Deleted)?.title
    LaunchedEffect(deletedTitle) {
        if (deletedTitle == null) return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message = resources.getString(R.string.entry_deleted_message, deletedTitle),
            actionLabel = resources.getString(R.string.common_undo),
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) {
            vm.undoDelete()
        } else if (vm.state.value is EntryUiState.Deleted) {
            nav.back()
        }
    }

    // Not in the vault at all: back to the list by itself, which says "This account no longer exists."
    // (ux.md §5.12) from the mark left for it. Should there be nowhere to go back to, this screen says it.
    val notThere = (state as? EntryUiState.Gone)?.inRecentlyDeleted == false
    LaunchedEffect(notThere) {
        if (!notThere) return@LaunchedEffect
        GoneNotice.mark()
        if (!nav.back()) GoneNotice.clear()
    }

    val actions = remember(vm, nav) {
        EntryActions(
            setShown = vm::setShown,
            copy = { value ->
                if (vm.copy(value)) {
                    copied = value
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                }
            },
            bigLetters = { value -> value.field?.let { nav.go(Route.BigLetters(vm.entryId, it)) } },
            openWebsite = { row ->
                if (row.kind == WebsiteKind.WEB) {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, row.address.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
                    } catch (e: ActivityNotFoundException) {
                        scope.launch { snackbar.showSnackbar(resources.getString(R.string.entry_no_browser)) }
                    }
                }
            },
            edit = { nav.go(Route.Edit(vm.entryId)) },
            setNotesExpanded = vm::setNotesExpanded,
            setPreviousExpanded = vm::setPreviousExpanded,
            confirmChange = vm::confirmChange,
            revertChange = vm::revertChange,
            checkAgain = { vm.setCheckIgnored(false) },
            newMasterPassword = { nav.go(Route.ChangeMasterPassword) },
            wallMs = vm::wallMs,
        )
    }

    val details = (state as? EntryUiState.Shown)?.details
    val title = details?.title ?: deletedTitle ?: stringResource(R.string.entry_pane)
    Scaffold(
        modifier = Modifier
            .semantics {
                paneTitle = title
                testTagsAsResourceId = true
            }
            .onAnyTouch(vm::touched),
        topBar = {
            VaultTopBar(
                title = details?.title ?: deletedTitle.orEmpty(),
                navigationIcon = {
                    IconButton(onClick = { nav.back() }, modifier = Modifier.testTag(EntryTags.BACK)) {
                        Icon(painterResource(R.drawable.ic_entry_back), contentDescription = stringResource(R.string.entry_back))
                    }
                },
                actions = {
                    if (details != null) {
                        TopActions(
                            details = details,
                            onFavourite = { vm.setFavourite(!details.favourite) },
                            onEdit = actions.edit,
                            onBigLetters = { actions.bigLetters(EntryValue.Password) },
                            onLeaveOut = { vm.setCheckIgnored(true) },
                            onDelete = vm::delete,
                        )
                    }
                },
                titleMayBeCut = true,
                titleIsUserText = details != null || deletedTitle != null,
            )
        },
        snackbarHost = { VaultSnackbarHost(snackbar, tag = EntryTags.MESSAGE) },
    ) { padding ->
        when (val now = state) {
            EntryUiState.Loading -> Unit
            is EntryUiState.Shown -> EntryRows(now.details, copied, actions, padding)
            is EntryUiState.Deleted -> Notice(padding, EntryTags.DELETED, stringResource(R.string.entry_deleted_body))
            is EntryUiState.Gone -> Notice(
                padding = padding,
                tag = EntryTags.GONE,
                text = stringResource(if (now.inRecentlyDeleted) R.string.entry_gone_deleted else R.string.entry_gone),
            ) {
                if (now.inRecentlyDeleted) {
                    // In place of this screen, so that Back from Recently deleted goes to the list.
                    OutlinedButton(onClick = { nav.replace(Route.RecentlyDeleted) }, modifier = Modifier.testTag(EntryTags.GONE_OPEN_DELETED)) {
                        Text(stringResource(R.string.entry_open_deleted))
                    }
                }
                TextButton(onClick = { nav.back() }, modifier = Modifier.testTag(EntryTags.GONE_BACK)) {
                    Text(stringResource(R.string.entry_back))
                }
            }
        }
    }
}

/** The star, "Edit" and the menu of the top bar (ux.md §5.12). */
@Composable
private fun TopActions(
    details: EntryDetails,
    onFavourite: () -> Unit,
    onEdit: () -> Unit,
    onBigLetters: () -> Unit,
    onLeaveOut: () -> Unit,
    onDelete: () -> Unit,
) {
    IconButton(onClick = onFavourite, modifier = Modifier.testTag(EntryTags.FAVOURITE)) {
        Icon(
            painter = painterResource(if (details.favourite) R.drawable.ic_entry_star else R.drawable.ic_entry_star_outline),
            contentDescription = stringResource(if (details.favourite) R.string.entry_remove_favourite else R.string.entry_add_favourite),
        )
    }
    IconButton(onClick = onEdit, modifier = Modifier.testTag(EntryTags.EDIT)) {
        Icon(painterResource(R.drawable.ic_entry_edit), contentDescription = stringResource(R.string.entry_edit))
    }
    Box {
        var menu by remember { mutableStateOf(false) }
        IconButton(onClick = { menu = true }, modifier = Modifier.testTag(EntryTags.MORE)) {
            Icon(painterResource(R.drawable.ic_entry_more), contentDescription = stringResource(R.string.entry_more))
        }
        // A menu is a window of its own: its items get their resource ids here.
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.semantics { testTagsAsResourceId = true }) {
            // While a change is pending there are two passwords, each with its own button on its row.
            if (details.passwords.size == 1) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.entry_big_letters)) },
                    onClick = {
                        menu = false
                        onBigLetters()
                    },
                    modifier = Modifier.testTag(EntryTags.MENU_BIG_LETTERS),
                )
            }
            // An account that is left out says so in a banner, whose button takes it back in.
            if (!details.checkIgnored) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.entry_leave_out)) },
                    onClick = {
                        menu = false
                        onLeaveOut()
                    },
                    modifier = Modifier.testTag(EntryTags.MENU_LEAVE_OUT),
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.common_delete)) },
                onClick = {
                    menu = false
                    onDelete()
                },
                modifier = Modifier.testTag(EntryTags.MENU_DELETE),
            )
        }
    }
}

/** A sentence in place of the account, with [buttons] under it: the account was deleted here, or is gone. */
@Composable
private fun Notice(padding: PaddingValues, tag: String, text: String, buttons: @Composable () -> Unit = {}) {
    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(tag))
            buttons()
        }
    }
}

/** How long a copy button shows its check mark (ux.md §6 item 3). */
private const val COPY_CONFIRMATION_MS = 1_500L

/** The content is at most this wide on large screens (ux.md §3 "Layout"). */
internal val CONTENT_MAX_WIDTH = 640.dp
