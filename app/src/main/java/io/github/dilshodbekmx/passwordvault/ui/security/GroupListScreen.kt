package io.github.dilshodbekmx.passwordvault.ui.security

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
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
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.ui.common.TextBeside
import io.github.dilshodbekmx.passwordvault.ui.common.VaultSnackbarHost
import io.github.dilshodbekmx.passwordvault.ui.common.VaultTopBar
import io.github.dilshodbekmx.passwordvault.ui.common.mayTruncate
import io.github.dilshodbekmx.passwordvault.ui.common.userText
import io.github.dilshodbekmx.passwordvault.ui.theme.CardsLook
import io.github.dilshodbekmx.passwordvault.ui.theme.LocalLook
import kotlinx.coroutines.launch

/** The route `Group(listId)` (ux.md §5.16): its ViewModel over the app's check and vault, and the screen. */
@Composable
fun GroupListRoute(graph: AppGraph, nav: Navigator, listId: String) {
    val viewModel = viewModel { GroupListViewModel.from(graph, listId) }
    GroupListScreen(viewModel, nav)
}

/**
 * The list behind a card (ux.md §5.16, "Group list"): a top bar with Back and the list's name; the
 * heading "These 17 accounts share one password" (never the password), for a group also "3 changed,
 * 14 to go"; then each account with its name and username, which opens the account, and the row's
 * button: "Change" (the guided change of ux.md §5.17) with "Leave out of the security check" in the ⋮
 * menu, "Continue" for a change not finished, or "Check it again" for an account left out.
 *
 * Where the style groups list rows on cards (`LocalLook.cards`, Emerald), the rows lie on one card of
 * radius 20 dp on the tinted background, as the Passwords list's do.
 */
@OptIn(ExperimentalMaterial3Api::class) // TopAppBar's signature names TopAppBarScrollBehavior (ux.md §10)
@Composable
fun GroupListScreen(viewModel: GroupListViewModel, nav: Navigator) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val formatters = rememberFormatters()
    val kind = viewModel.kind

    LaunchedEffect(viewModel, snackbar) {
        viewModel.events.collect { event ->
            launch {
                when (event) {
                    is GroupEvent.LeftOut -> {
                        val result = snackbar.showSnackbar(
                            message = resources.getString(R.string.security_left_out_message, event.title),
                            actionLabel = resources.getString(R.string.common_undo),
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) viewModel.undoLeaveOut(event.id)
                    }
                    is GroupEvent.CheckedAgain -> snackbar.showSnackbar(resources.getString(R.string.security_checked_again_message, event.title))
                    is GroupEvent.NotSaved -> {
                        val retry = event.retry
                        val result = snackbar.showSnackbar(
                            message = resources.getString(if (event.problem == SaveProblem.TOO_LARGE) R.string.security_too_large else R.string.security_save_failed),
                            actionLabel = if (retry != null) resources.getString(R.string.common_try_again) else null,
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) retry?.invoke()
                    }
                }
            }
        }
    }

    val title = stringResource(
        when (kind) {
            GroupKind.REUSED -> R.string.security_list_reused
            GroupKind.WEAK -> R.string.security_weak_title
            GroupKind.PENDING -> R.string.security_pending_title
            GroupKind.LEFT_OUT -> R.string.security_left_out_title
        },
    )
    val grouped = LocalLook.current.cards == CardsLook.ROW_GROUPS
    Scaffold(
        modifier = Modifier.semantics { paneTitle = title },
        topBar = {
            VaultTopBar(
                title = title,
                navigationIcon = {
                    IconButton(onClick = { nav.back() }) {
                        Icon(painterResource(R.drawable.ic_list_back), contentDescription = stringResource(R.string.security_back))
                    }
                },
            )
        },
        snackbarHost = { VaultSnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag(SecurityTags.GROUP_LIST),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            val rows = state?.rows
            when {
                state == null -> Unit
                rows == null -> item(key = "checking") {
                    Column(Modifier.fillMaxWidth().padding(16.dp).testTag(SecurityTags.GROUP_CHECKING), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.security_checking_start), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                rows.isEmpty() -> item(key = "done") {
                    Text(
                        text = stringResource(
                            when (kind) {
                                GroupKind.REUSED -> R.string.security_group_reused_done
                                GroupKind.WEAK -> R.string.security_group_weak_done
                                GroupKind.PENDING -> R.string.security_group_pending_done
                                GroupKind.LEFT_OUT -> R.string.security_group_left_out_done
                            },
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().padding(16.dp).testTag(SecurityTags.GROUP_DONE),
                    )
                }
                else -> {
                    item(key = "header") {
                        val count = formatters.number(rows.size.toLong())
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = pluralStringResource(
                                    when (kind) {
                                        GroupKind.REUSED -> R.plurals.security_group_reused
                                        GroupKind.WEAK -> R.plurals.security_group_weak
                                        GroupKind.PENDING -> R.plurals.security_group_pending
                                        GroupKind.LEFT_OUT -> R.plurals.security_group_left_out
                                    },
                                    rows.size,
                                    count,
                                ),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { heading() }.testTag(SecurityTags.GROUP_HEADER),
                            )
                            val changed = state?.changed ?: 0
                            if (kind == GroupKind.REUSED && changed > 0) {
                                ReusedProgress(changed, rows.size, formatters, Modifier.testTag(SecurityTags.GROUP_PROGRESS))
                            }
                        }
                    }
                    itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                        val shape = if (grouped) shapeAt(index, rows.size) else RectangleShape
                        Box(if (grouped) Modifier.padding(horizontal = 16.dp).clip(shape) else Modifier) {
                            GroupRowItem(
                                row = row,
                                kind = kind,
                                grouped = grouped,
                                onOpen = { nav.go(Route.Entry(row.id)) },
                                onChange = { nav.go(Route.GuidedChange(row.id)) },
                                onCheckAgain = { viewModel.checkAgain(row.id) },
                                onLeaveOut = { viewModel.leaveOut(row.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One account: its name and username, a tap opens it; at its end the list's button, and for a group or
 * the passwords easy to guess the ⋮ menu with "Leave out of the security check".
 */
@Composable
private fun GroupRowItem(
    row: GroupRow,
    kind: GroupKind,
    grouped: Boolean,
    onOpen: () -> Unit,
    onChange: () -> Unit,
    onCheckAgain: () -> Unit,
    onLeaveOut: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val (label, description, action) = when (kind) {
        GroupKind.REUSED, GroupKind.WEAK -> Triple(R.string.security_change, R.string.security_change_description, onChange)
        GroupKind.PENDING -> Triple(R.string.security_continue, R.string.security_continue_description, onChange)
        GroupKind.LEFT_OUT -> Triple(R.string.security_check_again, R.string.security_check_again_description, onCheckAgain)
    }
    val spoken = stringResource(description, row.title)
    ListItem(
        // The name and the login, with "Change" and the row's menu beside them, or under them where a
        // long label would leave the name too little width (TextBeside; i18n.md §4.5 rule 1). The row may
        // cut the name and the login: the account's screen shows them whole (i18n.md §4.5 rule 3).
        headlineContent = {
            TextBeside(
                text = {
                    Column {
                        Text(row.title, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.mayTruncate().userText())
                        if (row.username.isNotEmpty()) {
                            Text(
                                text = row.username,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.mayTruncate().userText(),
                            )
                        }
                    }
                },
                action = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = action, modifier = Modifier.semantics { contentDescription = spoken }.testTag(SecurityTags.rowAction(row.id))) {
                            Text(stringResource(label))
                        }
                        if (kind == GroupKind.REUSED || kind == GroupKind.WEAK) {
                            Box {
                                IconButton(onClick = { menu = true }, modifier = Modifier.testTag(SecurityTags.rowMore(row.id))) {
                                    Icon(painterResource(R.drawable.ic_list_more), contentDescription = stringResource(R.string.security_more, row.title))
                                }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.security_leave_out)) },
                                        onClick = {
                                            menu = false
                                            onLeaveOut()
                                        },
                                        modifier = Modifier.testTag(SecurityTags.LEAVE_OUT),
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
        colors = if (grouped) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) else ListItemDefaults.colors(),
        modifier = Modifier.clickable(onClick = onOpen).testTag(SecurityTags.row(row.id)),
    )
}

/** The corners of the row at [index] of [count] rows grouped on one card: rounded at the card's top and bottom. */
private fun shapeAt(index: Int, count: Int): Shape {
    val top = if (index == 0) CARD_RADIUS else 0.dp
    val bottom = if (index == count - 1) CARD_RADIUS else 0.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** The radius of a card of rows (docs/design/looks/README.md, Emerald: "radius 20 dp"). */
private val CARD_RADIUS = 20.dp
