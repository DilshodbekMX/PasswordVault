package io.github.dilshodbekmx.passwordvault.ui.list

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.data.SaveProblem
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.ui.common.VaultSearchField
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The Passwords route (ux.md §5.11): its ViewModel over the app's parts, and the screen. */
@Composable
fun PasswordsRoute(graph: AppGraph, nav: Navigator) {
    val viewModel = viewModel { PasswordsViewModel.from(graph) }
    PasswordsScreen(viewModel, nav)
}

/**
 * The Passwords screen (ux.md §5.11): the top bar with "Lock now", the search field, at most two
 * banners, the list (favourites, then every account under sticky letter headers, then "Recently
 * deleted (3)") or the search results, the "Add" button, and the snackbar.
 *
 * - **Back** clears a query first (ux.md §4); otherwise it is Navigation's (it leaves the app here).
 * - **The keyboard's search key** hides the keyboard; nothing is focused when the screen opens, so
 *   the keyboard does not cover the favourites (ux.md §5.11 "Focus").
 * - **Saved state** holds only the scroll position (architecture.md §5.6); the query and the rest
 *   are in [viewModel], kept while an entry is open on top of this screen.
 */
@OptIn(ExperimentalMaterial3Api::class) // TopAppBar's signature names TopAppBarScrollBehavior (ux.md §10)
@Composable
fun PasswordsScreen(viewModel: PasswordsViewModel, nav: Navigator) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val practice by viewModel.practice.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // The copy button that shows its check mark for 1.5 s (ux.md §6 item 3), by the row's key.
    var copiedKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copiedKey) {
        if (copiedKey != null) {
            delay(COPY_CONFIRMATION_MS)
            copiedKey = null
        }
    }

    val hasQuery = viewModel.query.text.isNotEmpty()
    BackHandler(enabled = hasQuery) { viewModel.clearQuery() }

    LaunchedEffect(viewModel, snackbar) {
        viewModel.events.collect { event ->
            launch {
                when (event) {
                    is ListEvent.Deleted -> {
                        val result = snackbar.showSnackbar(
                            message = resources.getString(R.string.list_deleted_message, event.title),
                            actionLabel = resources.getString(R.string.common_undo),
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete(event.id)
                    }
                    is ListEvent.NotSaved -> snackbar.showSnackbar(resources.getString(saveProblemText(event.problem)), duration = SnackbarDuration.Long)
                    ListEvent.CopyBlocked -> snackbar.showSnackbar(resources.getString(R.string.list_copy_blocked), duration = SnackbarDuration.Long)
                    ListEvent.CannotDeleteExport ->
                        snackbar.showSnackbar(resources.getString(R.string.list_banner_cannot_delete), duration = SnackbarDuration.Long)
                    ListEvent.PracticeRight -> snackbar.showSnackbar(resources.getString(R.string.list_practice_right))
                }
            }
        }
    }

    val actions = remember(viewModel, nav) {
        fun confirmCopy(copied: Boolean, key: String) {
            if (!copied) return
            copiedKey = key
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        }
        RowActions(
            open = { row -> nav.go(Route.Entry(row.id)) },
            copyPassword = { row, key -> confirmCopy(viewModel.copyPassword(row.id), key) },
            copyOldPassword = { row, key -> confirmCopy(viewModel.copyOldPassword(row.id), key) },
            copyNewPassword = { row, key -> confirmCopy(viewModel.copyNewPassword(row.id), key) },
            copyUsername = { row -> viewModel.copyUsername(row.id) },
            openWebsite = { row ->
                val address = row.website
                if (address != null) {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address)).addCategory(Intent.CATEGORY_BROWSABLE))
                    } catch (e: ActivityNotFoundException) {
                        scope.launch { snackbar.showSnackbar(resources.getString(R.string.list_no_browser)) }
                    }
                }
            },
            bigLetters = { row -> nav.go(Route.BigLetters(row.id, BIG_LETTERS_PASSWORD)) },
            favourite = { row -> viewModel.setFavourite(row.id, !row.favourite) },
            edit = { row -> nav.go(Route.Edit(row.id)) },
            delete = { row -> viewModel.delete(row.id) },
        )
    }
    val banners = remember(viewModel, nav) {
        BannerActions(
            cancelPending = viewModel::cancelPending,
            finishPending = viewModel::finishPending,
            pendingLater = viewModel::pendingLater,
            itWasntMe = viewModel::itWasntMe,
            replacedSeen = viewModel::replacedSeen,
            typeItNow = viewModel::openPractice,
            forgotIt = { nav.go(Route.NewMasterPassword) },
            deleteExport = viewModel::deleteExport,
            exportDeletedMyself = viewModel::exportDeletedMyself,
            backUpNow = { nav.go(Route.Backup) },
            backupLater = viewModel::backupLater,
            answer = { id -> nav.go(Route.Entry(id)) },
            wallMs = viewModel::wallMs,
        )
    }

    // The "Add" button collapses to its icon while the list scrolls down (ux.md §5.11).
    var addExpanded by remember { mutableStateOf(true) }
    val scrollWatch = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -1f) addExpanded = false else if (available.y > 1f) addExpanded = true
                return Offset.Zero
            }
        }
    }

    val title = stringResource(R.string.list_title)
    Scaffold(
        modifier = Modifier.semantics { paneTitle = title },
        topBar = {
            TopAppBar(
                title = { Text(title, modifier = Modifier.semantics { heading() }) },
                actions = {
                    IconButton(onClick = viewModel::lockNow, modifier = Modifier.testTag(ListTags.LOCK_NOW)) {
                        Icon(painterResource(R.drawable.ic_list_lock), contentDescription = stringResource(R.string.list_lock_now))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.list_add)) },
                icon = {
                    Icon(
                        painterResource(R.drawable.ic_list_add),
                        contentDescription = if (addExpanded) null else stringResource(R.string.list_add_account_description),
                    )
                },
                onClick = { nav.go(Route.Edit()) },
                expanded = addExpanded,
                modifier = Modifier.testTag(ListTags.ADD),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            VaultSearchField(
                state = viewModel.query,
                onSearch = { keyboard?.hide() },
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp).testTag(ListTags.SEARCH),
                placeholder = { Text(stringResource(R.string.list_search_placeholder)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_list_search), contentDescription = null) },
                trailingIcon = if (hasQuery) {
                    {
                        IconButton(onClick = viewModel::clearQuery, modifier = Modifier.testTag(ListTags.SEARCH_CLEAR)) {
                            Icon(painterResource(R.drawable.ic_list_clear), contentDescription = stringResource(R.string.list_search_clear))
                        }
                    }
                } else {
                    null
                },
            )
            ListContent(
                state = state,
                listState = listState,
                copiedKey = copiedKey,
                rowActions = actions,
                bannerActions = banners,
                onShowAllFavourites = viewModel::showAllFavourites,
                onAdd = { nav.go(Route.Edit()) },
                onImport = { nav.go(Route.Import()) },
                onRecentlyDeleted = { nav.go(Route.RecentlyDeleted) },
                modifier = Modifier.nestedScroll(scrollWatch),
            )
        }
    }

    practice?.let { now ->
        PracticeDialog(
            state = now,
            password = viewModel.practicePassword,
            elapsedMs = viewModel::elapsedMs,
            onCheck = viewModel::submitPractice,
            onCancel = viewModel::closePractice,
            onWaitOver = viewModel::practiceWaitOver,
        )
    }
}

/** The list, the search results, or the empty and loading states, with the banners above. */
@Composable
private fun ListContent(
    state: PasswordsUiState,
    listState: LazyListState,
    copiedKey: String?,
    rowActions: RowActions,
    bannerActions: BannerActions,
    onShowAllFavourites: () -> Unit,
    onAdd: () -> Unit,
    onImport: () -> Unit,
    onRecentlyDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val body = state.body
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().testTag(ListTags.LIST),
        // Room for the "Add" button, so it never covers the last row's copy button (ux.md §5.11, §8).
        contentPadding = PaddingValues(bottom = LIST_BOTTOM_PADDING),
    ) {
        // While searching only the results show; the banners wait (ux.md §5.11).
        if (body !is ListBody.Results && body !is ListBody.NoResults) {
            items(state.banners, key = { ListTags.banner(it) }, contentType = { "banner" }) { banner ->
                ListBannerItem(banner, bannerActions)
            }
        }
        when (body) {
            ListBody.Loading -> item(key = "loading") { Loading() }
            ListBody.Empty -> item(key = "empty") { EmptyVault(onAdd, onImport) }
            is ListBody.Browse -> browse(body, copiedKey, rowActions, onShowAllFavourites)
            is ListBody.Results -> results(body, copiedKey, rowActions)
            is ListBody.NoResults -> item(key = "none") { NoResults(body.typed, onAdd) }
        }
        if (state.deletedCount > 0 && (body is ListBody.Browse || body is ListBody.Empty)) {
            item(key = "deleted", contentType = "deleted") { RecentlyDeletedRow(state.deletedCount, onRecentlyDeleted) }
        }
    }
}

/** The list without a query: favourites, then all accounts under sticky letter headers (ux.md §5.11). */
private fun LazyListScope.browse(body: ListBody.Browse, copiedKey: String?, actions: RowActions, onShowAllFavourites: () -> Unit) {
    if (body.favourites.isNotEmpty()) {
        item(key = "favourites", contentType = "heading") {
            Heading(stringResource(R.string.list_favourites), Modifier.testTag(ListTags.FAVOURITES))
        }
        items(body.favourites, key = { FAVOURITE_KEY + it.id }, contentType = { "row" }) { row ->
            val key = FAVOURITE_KEY + row.id
            EntryRowItem(row, null, copiedKey == key, key, actions, Modifier.testTag(ListTags.favourite(row.id)))
        }
        if (body.moreFavourites > 0) {
            item(key = "moreFavourites") {
                TextButton(
                    onClick = onShowAllFavourites,
                    modifier = Modifier.padding(horizontal = 8.dp).testTag(ListTags.SHOW_ALL_FAVOURITES),
                ) {
                    Text(stringResource(R.string.list_show_all_favourites))
                }
            }
        }
    }
    item(key = "all", contentType = "heading") {
        Heading(stringResource(R.string.list_all_accounts, body.total), Modifier.testTag(ListTags.ALL_ACCOUNTS))
    }
    body.sections.forEachIndexed { index, section ->
        // Keyed by place, not by letter: a header comes from a title, and keys can reach saved state.
        stickyHeader(key = "header$index", contentType = "letter") {
            LetterHeader(section.header, Modifier.testTag(ListTags.header(index)))
        }
        items(section.rows, key = { ENTRY_KEY + it.id }, contentType = { "row" }) { row ->
            val key = ENTRY_KEY + row.id
            EntryRowItem(row, null, copiedKey == key, key, actions, Modifier.testTag(ListTags.row(row.id)))
        }
    }
}

/** The search results, best first, with matches in bold (ux.md §5.11; search.md §7). */
private fun LazyListScope.results(body: ListBody.Results, copiedKey: String?, actions: RowActions) {
    if (body.retried) {
        item(key = "retried") {
            Text(
                text = stringResource(R.string.list_search_retried, body.searched),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag(ListTags.RETRIED),
            )
        }
    }
    items(body.rows, key = { RESULT_KEY + it.id }, contentType = { "row" }) { row ->
        val key = RESULT_KEY + row.id
        EntryRowItem(row, body.searched, copiedKey == key, key, actions, Modifier.testTag(ListTags.row(row.id)))
    }
}

/** A section heading of the list: "Favourites", "All accounts · 48". A heading for TalkBack (ux.md §8). */
@Composable
private fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** A sticky letter header ("A", "Ш", "Oʻ"); drawn on the surface so that the rows pass under it. */
@Composable
private fun LetterHeader(letter: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Text(
            text = letter,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).semantics { heading() },
        )
    }
}

/** "Recently deleted (3)", the last row of the list when it is not empty (ux.md §5.23). */
@Composable
private fun RecentlyDeletedRow(count: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.list_recently_deleted_row, count)) },
        leadingContent = { Icon(painterResource(R.drawable.ic_list_deleted), contentDescription = null) },
        trailingContent = { Icon(painterResource(R.drawable.ic_list_chevron), contentDescription = null) },
        modifier = Modifier.testTag(ListTags.RECENTLY_DELETED).clickable(onClick = onClick),
    )
}

/** "No accounts yet", with "Add account" and the import from Google Password Manager (ux.md §5.11). */
@Composable
private fun EmptyVault(onAdd: () -> Unit, onImport: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp).testTag(ListTags.EMPTY),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.list_empty_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(text = stringResource(R.string.list_empty_body), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Button(onClick = onAdd, modifier = Modifier.testTag(ListTags.EMPTY_ADD)) {
            Text(stringResource(R.string.list_empty_add))
        }
        OutlinedButton(onClick = onImport, modifier = Modifier.testTag(ListTags.EMPTY_IMPORT)) {
            Text(stringResource(R.string.list_empty_import), textAlign = TextAlign.Center)
        }
    }
}

/** 'No accounts match “gmal”.' and 'Add “gmal” as a new account' (ux.md §5.11). */
@Composable
private fun NoResults(typed: String, onAdd: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp).testTag(ListTags.NO_RESULTS),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = stringResource(R.string.list_no_results, typed), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        TextButton(onClick = onAdd, modifier = Modifier.testTag(ListTags.ADD_AS_NEW)) {
            Text(stringResource(R.string.list_add_as_new, typed), textAlign = TextAlign.Center)
        }
    }
}

/** While the list is being built: a small spinner, shown only when it takes a moment, so that a quick unlock does not flash. */
@Composable
private fun Loading() {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(LOADING_SPINNER_DELAY_MS)
        show = true
    }
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        if (show) CircularProgressIndicator()
    }
}

/** The text of a save that did not happen (architecture.md §3.5). */
private fun saveProblemText(problem: SaveProblem): Int =
    if (problem == SaveProblem.TOO_LARGE) R.string.list_too_large else R.string.list_save_failed

/** The field of "Show in big letters" for the password (architecture.md §3.6, `Route.BigLetters`). */
internal const val BIG_LETTERS_PASSWORD = "password"

/** How long a copy button shows its check mark (ux.md §6 item 3). */
private const val COPY_CONFIRMATION_MS = 1_500L

private const val LOADING_SPINNER_DELAY_MS = 400L

/** The "Add" button's height (56 dp) and its margin (16 dp), and 24 dp more: the list's bottom padding (ux.md §5.11). */
private val LIST_BOTTOM_PADDING = 96.dp

private const val FAVOURITE_KEY = "f."
private const val ENTRY_KEY = "e."
private const val RESULT_KEY = "r."
