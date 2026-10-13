package io.github.dilshodbekmx.passwordvault.ui.list

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButtonDefaults
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInputModeManager
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
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.data.SaveProblem
import io.github.dilshodbekmx.passwordvault.i18n.rememberFormatters
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.ui.common.CardPart
import io.github.dilshodbekmx.passwordvault.ui.common.KeyboardFirstOnBack
import io.github.dilshodbekmx.passwordvault.ui.common.VaultSearchField
import io.github.dilshodbekmx.passwordvault.ui.common.cardPart
import io.github.dilshodbekmx.passwordvault.ui.theme.LocalLook
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
 * deleted (3)") or the search results, the "Add" button (tonal, filled, or filled and fully rounded,
 * as the style says), and the snackbar, which also says "This account no longer exists." when an
 * account's screen came back for that reason (ux.md §5.12).
 *
 * - **Back** clears a query first (ux.md §4); otherwise it is Navigation's (it leaves the app here).
 * - **The keyboard's search key** hides the keyboard; nothing is focused when the screen opens, so
 *   the keyboard does not cover the favourites (ux.md §5.11 "Focus"): the search field refuses the
 *   focus Android hands on in touch mode ([refusesHandedOnFocus]).
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
    val inputModes = LocalInputModeManager.current
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
    KeyboardFirstOnBack() // Back with the keyboard up closes it and keeps the query

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
                    is ListEvent.NotSaved -> {
                        val retry = event.retry
                        val result = snackbar.showSnackbar(
                            message = resources.getString(saveProblemText(event.problem)),
                            actionLabel = if (retry != null) resources.getString(R.string.common_try_again) else null,
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) retry?.invoke()
                    }
                    ListEvent.CopyBlocked -> snackbar.showSnackbar(resources.getString(R.string.list_copy_blocked), duration = SnackbarDuration.Long)
                    ListEvent.CannotDeleteExport ->
                        snackbar.showSnackbar(resources.getString(R.string.list_banner_cannot_delete), duration = SnackbarDuration.Long)
                    ListEvent.PracticeRight -> snackbar.showSnackbar(resources.getString(R.string.list_practice_right))
                    ListEvent.AccountGone -> snackbar.showSnackbar(resources.getString(R.string.list_account_gone))
                }
            }
        }
    }
    // Shown, at first or again: an account's screen that went back because its account is gone says so here (ux.md §5.12).
    LaunchedEffect(viewModel) { viewModel.shown() }

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
                        context.startActivity(Intent(Intent.ACTION_VIEW, address.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
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
            finishPending = { nav.go(Route.NewMasterPassword) }, // the new master password is typed there; nothing locks (decision 14)
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
            // Drawn as the style says (docs/design/looks/README.md "\"Add\" button").
            val add = LocalLook.current.addButton
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.list_add)) },
                icon = {
                    Icon(
                        painterResource(R.drawable.ic_list_add),
                        contentDescription = if (addExpanded) null else stringResource(R.string.list_add_account_description),
                    )
                },
                onClick = {
                    viewModel.addAccount()
                    nav.go(Route.Edit())
                },
                expanded = addExpanded,
                modifier = Modifier.testTag(ListTags.ADD),
                shape = if (add.fullyRounded) CircleShape else FloatingActionButtonDefaults.extendedFabShape,
                containerColor = add.container(MaterialTheme.colorScheme),
                contentColor = add.content(MaterialTheme.colorScheme),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            VaultSearchField(
                state = viewModel.query,
                onSearch = { keyboard?.hide() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                    .refusesHandedOnFocus(inputModes)
                    .testTag(ListTags.SEARCH),
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
                onAdd = {
                    viewModel.addAccount()
                    nav.go(Route.Edit())
                },
                onAddAsNew = { typed ->
                    viewModel.addAsNewAccount(typed)
                    nav.go(Route.Edit())
                },
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
    onAddAsNew: (typed: String) -> Unit,
    onImport: () -> Unit,
    onRecentlyDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val body = state.body
    // A new query, or the list after the results, starts at the top: the best results first. The
    // list keeps its place by the key of its first row otherwise, which would leave an older row
    // there. Not at the first composition, so that a position restored after a rotation, or on
    // coming back from an account, is kept (ux.md §5.11 "Focus").
    val identity = identityOf(body)
    val shownIdentity = remember { arrayOfNulls<String>(1) }
    SideEffect {
        val before = shownIdentity[0]
        if (before != null && before != identity) listState.requestScrollToItem(0)
        shownIdentity[0] = identity
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().testTag(ListTags.LIST),
        // Room for the "Add" button, so it never covers the last row's copy button (ux.md §5.11, §8).
        contentPadding = PaddingValues(bottom = LIST_BOTTOM_PADDING),
    ) {
        // While searching only the results show; the banners wait (ux.md §5.11). The banners are one
        // item with a key of its own that is always first, even with no banner: so a banner that
        // comes later shows at the top for someone who is at the top, rather than above the screen.
        if (body !is ListBody.Results && body !is ListBody.NoResults) {
            item(key = BANNERS_KEY, contentType = "banners") {
                Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    for (banner in state.banners) {
                        key(ListTags.banner(banner)) { ListBannerItem(banner, bannerActions) }
                    }
                }
            }
        }
        when (body) {
            ListBody.Loading -> item(key = "loading") { Loading() }
            ListBody.Empty -> item(key = "empty") { EmptyVault(onAdd, onImport) }
            is ListBody.Browse -> browse(body, copiedKey, rowActions, onShowAllFavourites)
            is ListBody.Results -> results(body, copiedKey, rowActions)
            is ListBody.NoResults -> item(key = "none") { NoResults(body.typed, onAddAsNew) }
        }
        if (state.deletedCount > 0 && (body is ListBody.Browse || body is ListBody.Empty)) {
            item(key = "deleted", contentType = "deleted") { RecentlyDeletedRow(state.deletedCount, onRecentlyDeleted) }
        }
    }
}

/**
 * The list without a query: favourites, then all accounts under sticky letter headers (ux.md
 * §5.11); in a style that groups rows on cards, each group on its card ([ListCards]).
 */
private fun LazyListScope.browse(body: ListBody.Browse, copiedKey: String?, actions: RowActions, onShowAllFavourites: () -> Unit) {
    if (body.favourites.isNotEmpty()) {
        val parts = ListCards.favourites(body.favourites.size, showAll = body.moreFavourites > 0)
        item(key = "favourites", contentType = "heading") {
            Heading(stringResource(R.string.list_favourites), Modifier.testTag(ListTags.FAVOURITES))
        }
        itemsIndexed(body.favourites, key = { _, row -> FAVOURITE_KEY + row.id }, contentType = { _, _ -> "row" }) { index, row ->
            val key = FAVOURITE_KEY + row.id
            EntryRowItem(row, null, copiedKey == key, key, actions, Modifier.testTag(ListTags.favourite(row.id)), card = parts[index])
        }
        if (body.moreFavourites > 0) {
            item(key = "moreFavourites") {
                val look = LocalLook.current
                Box(Modifier.fillMaxWidth().cardPart(rowGroup(look, parts.last()), look)) {
                    TextButton(
                        onClick = onShowAllFavourites,
                        modifier = Modifier.padding(horizontal = 8.dp).testTag(ListTags.SHOW_ALL_FAVOURITES),
                    ) {
                        Text(stringResource(R.string.list_show_all_favourites))
                    }
                }
            }
        }
    }
    item(key = "all", contentType = "heading") {
        val total = rememberFormatters().number(body.total.toLong())
        Heading(stringResource(R.string.list_all_accounts, total), Modifier.testTag(ListTags.ALL_ACCOUNTS))
    }
    val parts = ListCards.allAccounts(body.sections.map { it.rows.size })
    body.sections.forEachIndexed { index, section ->
        val sectionParts = parts[index]
        // Keyed by place, not by letter: a header comes from a title, and keys can reach saved state.
        stickyHeader(key = "header$index", contentType = "letter") {
            LetterHeader(section.header, Modifier.testTag(ListTags.header(index)), card = sectionParts.header)
        }
        itemsIndexed(section.rows, key = { _, row -> ENTRY_KEY + row.id }, contentType = { _, _ -> "row" }) { i, row ->
            val key = ENTRY_KEY + row.id
            EntryRowItem(row, null, copiedKey == key, key, actions, Modifier.testTag(ListTags.row(row.id)), card = sectionParts.rows[i])
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
    val parts = ListCards.results(body.rows.size)
    itemsIndexed(body.rows, key = { _, row -> RESULT_KEY + row.id }, contentType = { _, _ -> "row" }) { index, row ->
        val key = RESULT_KEY + row.id
        EntryRowItem(row, body.searched, copiedKey == key, key, actions, Modifier.testTag(ListTags.row(row.id)), card = parts[index])
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

/**
 * A sticky letter header ("A", "Ш", "Oʻ"); drawn on the surface so that the rows pass under it, or
 * on its part of the card ([card]) where the style groups rows on cards.
 */
@Composable
private fun LetterHeader(letter: String, modifier: Modifier = Modifier, card: CardPart? = null) {
    val look = LocalLook.current
    val part = rowGroup(look, card)
    val text: @Composable () -> Unit = {
        Text(
            text = letter,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).semantics { heading() },
        )
    }
    if (part == null) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) { text() }
    } else {
        Box(modifier.fillMaxWidth().cardPart(part, look)) { text() }
    }
}

/** "Recently deleted (3)", the last row of the list when it is not empty (ux.md §5.23); on a card of its own where the style groups rows on cards. */
@Composable
private fun RecentlyDeletedRow(count: Int, onClick: () -> Unit) {
    val look = LocalLook.current
    val part = rowGroup(look, ListCards.recentlyDeleted)
    ListItem(
        headlineContent = { Text(stringResource(R.string.list_recently_deleted_row, rememberFormatters().number(count.toLong()))) },
        leadingContent = { Icon(painterResource(R.drawable.ic_list_deleted), contentDescription = null) },
        trailingContent = { Icon(painterResource(R.drawable.ic_list_chevron), contentDescription = null) },
        colors = rowColours(part),
        modifier = Modifier
            .padding(top = if (part != null) CARD_GAP else 0.dp)
            .cardPart(part, look)
            .testTag(ListTags.RECENTLY_DELETED)
            .clickable(onClick = onClick),
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

/** 'No accounts match “gmal”.' and 'Add “gmal” as a new account', which opens the form with that name (ux.md §5.11). */
@Composable
private fun NoResults(typed: String, onAddAsNew: (typed: String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp).testTag(ListTags.NO_RESULTS),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = stringResource(R.string.list_no_results, typed), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        TextButton(onClick = { onAddAsNew(typed) }, modifier = Modifier.testTag(ListTags.ADD_AS_NEW)) {
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

/** Which list is shown: the list itself, or the results of one query. A change of it starts the list at the top. */
private fun identityOf(body: ListBody): String = when (body) {
    ListBody.Loading, ListBody.Empty, is ListBody.Browse -> "list"
    is ListBody.Results -> "results:" + body.typed
    is ListBody.NoResults -> "none:" + body.typed
}

/**
 * The search field takes the focus only when it is asked for: a tap, TalkBack or `requestFocus`,
 * which come without a direction ([FocusDirection.Enter]). In touch mode a focus that arrives with a
 * direction is refused: on Android 7 and 8 a view whose focus is cleared hands it to the window's
 * first focusable view also in touch mode, and Compose clears it when a focused field leaves (the
 * lock screen at an unlock, the form when it closes), then passes that focus on to this field, whose
 * keyboard covered the list and its Add button (architecture.md §5.4). With a keyboard the focus
 * moves as the user moves it, so it is not refused then.
 */
private fun Modifier.refusesHandedOnFocus(inputModes: InputModeManager): Modifier = this
    .focusProperties {
        onEnter = {
            if (inputModes.inputMode == InputMode.Touch && requestedFocusDirection != FocusDirection.Enter) cancelFocusChange()
        }
    }
    .focusGroup()

/** The text of a save that did not happen (architecture.md §3.5). */
private fun saveProblemText(problem: SaveProblem): Int =
    if (problem == SaveProblem.TOO_LARGE) R.string.list_too_large else R.string.list_save_failed

/** The field of "Show in big letters" for the password (architecture.md §3.6, `Route.BigLetters`). */
internal const val BIG_LETTERS_PASSWORD = "password"

/** How long a copy button shows its check mark (ux.md §6 item 3). */
private const val COPY_CONFIRMATION_MS = 1_500L

private const val LOADING_SPINNER_DELAY_MS = 400L

/** Above "Recently deleted" when it lies on a card of its own, after the card of all accounts. */
private val CARD_GAP = 12.dp

/** The "Add" button's height (56 dp) and its margin (16 dp), and 24 dp more: the list's bottom padding (ux.md §5.11). */
private val LIST_BOTTOM_PADDING = 96.dp

/** The keys of the list's rows (`LazyColumn` keys): an account is listed among the favourites and A to Z, so the key says where. */
internal const val BANNERS_KEY = "banners"
internal const val FAVOURITE_KEY = "f."
internal const val ENTRY_KEY = "e."
internal const val RESULT_KEY = "r."
