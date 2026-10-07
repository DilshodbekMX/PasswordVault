package io.github.dilshodbekmx.passwordvault.ui.list

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.clipboard.SecretClipboard
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.EntryResult
import io.github.dilshodbekmx.passwordvault.core.model.VaultChange
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import io.github.dilshodbekmx.passwordvault.core.search.SearchIndex
import io.github.dilshodbekmx.passwordvault.core.search.SearchOutcome
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.data.ListModelSource
import io.github.dilshodbekmx.passwordvault.data.SaveProblem
import io.github.dilshodbekmx.passwordvault.data.SaveResult
import io.github.dilshodbekmx.passwordvault.data.VaultRepository
import io.github.dilshodbekmx.passwordvault.data.VaultState
import io.github.dilshodbekmx.passwordvault.files.DocRef
import io.github.dilshodbekmx.passwordvault.files.DocumentIo
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.lock.LockIntent
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.lock.MpPurpose
import io.github.dilshodbekmx.passwordvault.lock.MpResult
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.session.Session
import io.github.dilshodbekmx.passwordvault.settings.AppSettings
import io.github.dilshodbekmx.passwordvault.ui.common.clearSecret
import io.github.dilshodbekmx.passwordvault.ui.common.readSecret
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Passwords screen (ux.md §5.11): the list with its favourites, letter headers and banners, the
 * search in place, the row's copy button and its menu, and "Lock now".
 *
 * **Where the list comes from.** The rows come from [ListModelSource] (`ListModelProvider`, build
 * plan 3.1), which sorts and cuts the open vault for the app's language and builds the search index;
 * this ViewModel never sorts. The banners and the Recently deleted count come from the vault itself
 * ([VaultRepository.state]), the lock's [LockManager.status] and the backup reminder of
 * [AppSettings], and are computed even while those sources are empty ([ListBanners]).
 *
 * **Search** runs on [AppDispatchers.default] on every change of [query], with "latest query wins"
 * (architecture.md §5.3): a newer query or a newer list cancels the search that is running, so a
 * slower, older search can never replace the result of a newer one. A query that finds nothing is
 * retried on the other keyboard layout by `SearchIndex.searchOrRetry` (search.md §7); the screen
 * makes the matches bold with `matchedRanges` for the visible rows only.
 *
 * **No secret in the state.** [state] holds titles, usernames and hosts, never a password: a copy
 * reads the entry from the vault at the moment of the tap. The search text is held in [query], here
 * and never in saved state (architecture.md §5.6); the master password of "Type it now" in
 * [practicePassword], emptied after it is read. When the vault locks, this ViewModel drops what it
 * holds itself (architecture.md §5.5 step 5): the list, the query and the dialog, because after a
 * lock in the background its screen leaves only when the activity starts again.
 *
 * Every function is called on the main thread.
 *
 * @param searcher the search of one query over one index; `SearchIndex.searchOrRetry` (a test may
 *   pass a slower one, to show that the latest query wins).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PasswordsViewModel(
    private val repository: VaultRepository,
    private val lists: ListModelSource,
    private val lock: LockManager,
    private val settings: AppSettings,
    private val clipboard: SecretClipboard,
    private val session: Session,
    private val files: DocumentIo,
    private val clock: Clock,
    private val dispatchers: AppDispatchers,
    private val searcher: suspend (SearchIndex, String) -> SearchOutcome = { index, text -> index.searchOrRetry(text) },
) : ViewModel() {

    /** The search field's text (ux.md §5.11). It never reaches saved state (architecture.md §5.6). */
    val query = TextFieldState()

    /** The master-password field of "Type it now" (lock.md §13.4 item 6); emptied after every read. */
    val practicePassword = TextFieldState()

    private val typed = MutableStateFlow("")
    private val allFavourites = MutableStateFlow(false)
    private val dismissed = MutableStateFlow<Set<Dismissed>>(emptySet())
    private val practiceState = MutableStateFlow<PracticeState?>(null)
    private val eventChannel = Channel<ListEvent>(Channel.BUFFERED)

    /** One-off messages for the screen's snackbar. */
    val events: Flow<ListEvent> = eventChannel.receiveAsFlow()

    /** The "Type it now" dialog, or null while it is closed. */
    val practice: StateFlow<PracticeState?> = practiceState.asStateFlow()

    /** The rows of the current list, made once per list off the main thread; null while there is none. */
    private val prepared: Flow<PreparedList?> = lists.model.mapLatest { model ->
        model?.let { withContext(dispatchers.default) { PreparedList.of(it) } }
    }

    /** The list or the search results for the latest query and the latest list. */
    private val body: Flow<ListBody> = combine(prepared, typed, ::Pair).mapLatest { (list, text) -> bodyOf(list, text) }

    private val banners: Flow<List<ListBanner>> =
        combine(repository.state, lock.status, settings.backupReminder, dismissed) { vault, status, reminder, off ->
            if (vault is VaultState.Unlocked) ListBanners.of(vault.vault, status, reminder, clock.wallMs(), off) else emptyList()
        }.flowOn(dispatchers.default)

    private val deletedCount: Flow<Int> = repository.state
        .map { vault -> if (vault is VaultState.Unlocked) vault.vault.entries.count { it.deletedAt != null } else 0 }
        .distinctUntilChanged()

    /**
     * What the screen shows. Computed whether or not the screen is visible, so that a lock drops
     * the rows at once even while the activity is stopped (architecture.md §5.5 step 5).
     */
    val state: StateFlow<PasswordsUiState> =
        combine(body, banners, deletedCount, allFavourites) { list, shown, deleted, all ->
            PasswordsUiState(if (list is ListBody.Browse) list.withFavourites(all) else list, shown, deleted)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, PasswordsUiState.LOADING)

    init {
        viewModelScope.launch { snapshotFlow { query.text.toString() }.collect { typed.value = it } }
        viewModelScope.launch {
            repository.state.collect { if (it is VaultState.Locked) forgetAll() }
        }
    }

    // ---- The list ----

    /** "Show all favourites" under the first [FAVOURITES_SHOWN] (ux.md §5.11). */
    fun showAllFavourites() {
        allFavourites.value = true
    }

    /** The search field's × and Back while it holds a query (ux.md §4): the list comes back. */
    fun clearQuery() {
        query.clearText()
    }

    /** "Lock now" in the top bar: clears our clip, writes a dirty form's draft, locks (architecture.md §5.5). */
    fun lockNow() {
        session.lockNow()
    }

    /** The wall-clock time now, for "saved 2 hours ago". */
    fun wallMs(): Long = clock.wallMs()

    // ---- Copying (ux.md §6): true when the clip was handed over ----

    /** The row's copy button, and "Copy password" in its menu. */
    fun copyPassword(id: String): Boolean {
        val entry = entry(id) ?: return false
        if (entry.password.isEmpty()) return false
        return copy(entry.password, UiText.of(R.string.list_copied_password, entry.title), sensitive = true)
    }

    /** "Copy old password" while a change is pending: the password the website still has (ux.md §5.17). */
    fun copyOldPassword(id: String): Boolean {
        val entry = entry(id) ?: return false
        val old = entry.pendingOldPassword?.takeIf { entry.changePending && it.isNotEmpty() } ?: return false
        return copy(old, UiText.of(R.string.list_copied_old_password, entry.title), sensitive = true)
    }

    /** "Copy new password (not confirmed)" while a change is pending (ux.md §5.17). */
    fun copyNewPassword(id: String): Boolean {
        val entry = entry(id) ?: return false
        if (entry.password.isEmpty()) return false
        return copy(entry.password, UiText.of(R.string.list_copied_new_password, entry.title), sensitive = true)
    }

    /** "Copy username": without the sensitive flag (ux.md §6 item 1). */
    fun copyUsername(id: String): Boolean {
        val entry = entry(id) ?: return false
        if (entry.username.isEmpty()) return false
        return copy(entry.username, UiText.of(R.string.list_copied_username, entry.title), sensitive = false)
    }

    // ---- The row's menu (ux.md §5.11) ----

    /** "Add to favourites" or "Remove from favourites". */
    fun setFavourite(id: String, favourite: Boolean) {
        save(VaultEdits.setFavourite(id, favourite))
    }

    /** "Delete": into Recently deleted at once, with "Undo" in the snackbar (ux.md §5.23). */
    fun delete(id: String) {
        val title = entry(id)?.title ?: return
        save(VaultEdits.delete(id)) { result -> if (result is EntryResult.Done) eventChannel.send(ListEvent.Deleted(id, title)) }
    }

    /** The snackbar's "Undo" after a delete. */
    fun undoDelete(id: String) {
        save(VaultEdits.restore(id))
    }

    // ---- The banners (ux.md §5.11) ----

    /** "Cancel it" on a pending new master password: no proof, it is the safe direction (lock.md F11 step 8). */
    fun cancelPending() {
        lock.send(LockIntent.CancelPending)
    }

    /** "Finish now" when the new master password is ready (lock.md F11 step 13). */
    fun finishPending() {
        lock.send(LockIntent.FinishPending)
    }

    /** "Later" on the "ready" banner: it waits until the next unlock. */
    fun pendingLater() {
        dismissed.update { it + Dismissed.PENDING_READY }
    }

    /** "It wasn't me" after a replacement (lock.md F16b). */
    fun itWasntMe() {
        lock.send(LockIntent.ItWasntMe)
    }

    /** "OK" on the replacement banner: it waits until the next unlock, as the notice does (lock.md §13.4 item 3). */
    fun replacedSeen() {
        dismissed.update { it + Dismissed.REPLACED }
    }

    /**
     * "Delete" on the banner of an unprotected export (ux.md §5.20): the document is deleted through
     * the file picker's provider, then forgotten. A place that does not allow it is said so, and the
     * banner stays.
     */
    fun deleteExport(uri: String) {
        viewModelScope.launch {
            val deleted = withContext(dispatchers.io) {
                try {
                    files.delete(DocRef(uri))
                } catch (e: Exception) {
                    false
                }
            }
            if (deleted) save(VaultEdits.forgetPlainExport(uri)) else eventChannel.send(ListEvent.CannotDeleteExport)
        }
    }

    /** "I deleted it myself": the banner goes. */
    fun exportDeletedMyself(uri: String) {
        save(VaultEdits.forgetPlainExport(uri))
    }

    /** "Later" on the backup banner: put off for 3 days (ux.md §5.11). */
    fun backupLater() {
        val until = clock.wallMs() + ListBanners.BACKUP_SNOOZE_MS
        viewModelScope.launch {
            val written = withContext(dispatchers.io) {
                settings.update { it.backupReminder = it.backupReminder.copy(snoozedUntilMs = until) }
            }
            if (!written) eventChannel.send(ListEvent.NotSaved(SaveProblem.FAILED, ::backupLater))
        }
    }

    // ---- "Type it now" (lock.md §13.4 item 6, F10) ----

    /** Opens the dialog with an empty field. */
    fun openPractice() {
        practicePassword.clearSecret()
        practiceState.value = PracticeState()
    }

    /** Cancel, Back or a tap outside: the field is emptied. */
    fun closePractice() {
        practicePassword.clearSecret()
        practiceState.value = null
    }

    /**
     * "Check": the typed characters go to the lock under its guard (lock.md §6.8), as practice. A
     * right one closes the dialog; its proof is not needed by anything here and is dropped.
     */
    fun submitPractice() {
        val now = practiceState.value ?: return
        if (now.checking || now.error is PracticeError.Wait) return
        val chars = practicePassword.readSecret()
        practicePassword.clearSecret()
        if (chars.isEmpty()) return
        practiceState.value = PracticeState(checking = true)
        viewModelScope.launch {
            val result = try {
                lock.submitMasterPassword(chars, MpPurpose.PRACTICE)
            } finally {
                chars.fill('\u0000')
            }
            if (practiceState.value == null) {
                (result as? MpResult.Right)?.proof?.drop()
                return@launch
            }
            practiceState.value = when (result) {
                is MpResult.Right -> {
                    result.proof.drop()
                    eventChannel.send(ListEvent.PracticeRight)
                    null
                }
                MpResult.Wrong -> PracticeState(error = PracticeError.Wrong)
                is MpResult.Wait -> PracticeState(error = PracticeError.Wait(result.untilElapsedMs))
                MpResult.NoMemory -> PracticeState(error = PracticeError.NoMemory)
                is MpResult.OlderPassword -> PracticeState(error = PracticeError.Older(result.changedAtMs))
                MpResult.TooLate -> null
            }
        }
    }

    /** The wait of the lock's guard is over: the field takes a try again. */
    fun practiceWaitOver() {
        if (practiceState.value?.error is PracticeError.Wait) practiceState.value = PracticeState()
    }

    /** `elapsedRealtime()` now, for the wait's countdown. */
    fun elapsedMs(): Long = clock.elapsedMs()

    // ---- Inside ----

    private suspend fun bodyOf(list: PreparedList?, text: String): ListBody {
        if (list == null) return ListBody.Loading
        if (text.isBlank()) {
            if (list.all.isEmpty()) return ListBody.Empty
            return ListBody.Browse(list.favourites, 0, list.all.size, list.sections)
        }
        val outcome = withContext(dispatchers.default) { searcher(list.model.index, text) }
        if (outcome.hits.isEmpty()) return ListBody.NoResults(text)
        return ListBody.Results(text, outcome.query, outcome.retried, outcome.hits.map { list.all[it.index] })
    }

    /** The entry [id] outside Recently deleted, from the open vault; null when locked or gone. */
    private fun entry(id: String): Entry? {
        val vault = repository.state.value as? VaultState.Unlocked ?: return null
        return vault.vault.entries.firstOrNull { it.id == id && it.deletedAt == null }
    }

    private fun copy(value: String, what: UiText, sensitive: Boolean): Boolean = try {
        clipboard.copy(value, what, sensitive)
        true
    } catch (e: RuntimeException) {
        // Some vendor builds throw here (ux.md §6, "Failures"); nothing of the value is kept.
        eventChannel.trySend(ListEvent.CopyBlocked)
        false
    }

    private fun <T> save(change: VaultChange<T>, done: suspend (T) -> Unit = {}) {
        viewModelScope.launch {
            when (val result = repository.edit(change)) {
                is SaveResult.Saved -> done(result.value)
                is SaveResult.NotSaved -> eventChannel.send(ListEvent.NotSaved(result.problem, retryUnless(result.problem) { save(change, done) }))
            }
        }
    }

    /** "Try again" for a save that failed for [problem]; none when the vault is too large, which another try cannot change. */
    private fun retryUnless(problem: SaveProblem, again: () -> Unit): (() -> Unit)? = if (problem == SaveProblem.TOO_LARGE) null else again

    /** The vault locked: drop the query, the dialog and its field, and what was put off until the next unlock. */
    private fun forgetAll() {
        query.clearText()
        practicePassword.clearSecret()
        practiceState.value = null
        dismissed.value = emptySet()
        allFavourites.value = false
    }

    companion object {
        /** The favourites shown before "Show all favourites" (ux.md §5.11). */
        const val FAVOURITES_SHOWN = 10

        /** The ViewModel of the app's Passwords screen, over [graph]'s parts. */
        fun from(graph: AppGraph): PasswordsViewModel = PasswordsViewModel(
            repository = graph.data.repository,
            lists = graph.data.lists,
            lock = graph.lock,
            settings = graph.settings,
            clipboard = graph.clipboard,
            session = graph.session,
            files = graph.files,
            clock = graph.platform.clock,
            dispatchers = graph.dispatchers,
        )
    }
}

/**
 * What the Passwords screen shows: the list or the search results ([body]), at most two [banners]
 * in priority order, and how many accounts wait in Recently deleted ([deletedCount]).
 */
data class PasswordsUiState(val body: ListBody, val banners: List<ListBanner>, val deletedCount: Int) {
    companion object {
        /** Before the first list: nothing to show yet. */
        val LOADING = PasswordsUiState(ListBody.Loading, emptyList(), 0)
    }
}

/** The main part of the Passwords screen. [toString] never shows a title or what was typed. */
sealed interface ListBody {
    /** The vault is locked, or its list is not built yet. */
    data object Loading : ListBody

    /** No account outside Recently deleted (ux.md §5.11 "Empty vault"). */
    data object Empty : ListBody

    /**
     * The list without a query: the [favourites] (the first [PasswordsViewModel.FAVOURITES_SHOWN]
     * until "Show all favourites", with [moreFavourites] hidden), then all [total] accounts in their
     * [sections].
     */
    data class Browse(val favourites: List<EntryRow>, val moreFavourites: Int, val total: Int, val sections: List<RowSection>) : ListBody {
        /** This list with every favourite ([all]) or only the first ones. */
        fun withFavourites(all: Boolean): Browse {
            val count = favourites.size + moreFavourites
            if (all || favourites.size <= PasswordsViewModel.FAVOURITES_SHOWN) return this
            return copy(favourites = favourites.take(PasswordsViewModel.FAVOURITES_SHOWN), moreFavourites = count - PasswordsViewModel.FAVOURITES_SHOWN)
        }

        override fun toString(): String =
            "Browse(favourites=${favourites.size}, moreFavourites=$moreFavourites, total=$total, sections=${sections.size})"
    }

    /**
     * The results for what was [typed], best first; [searched] is the query they were found for,
     * which differs when what was typed found nothing and its keys on the other keyboard layout did
     * ([retried]): the screen then says "Showing results for “[searched]”" and makes the ranges of
     * [searched] bold (search.md §7).
     */
    data class Results(val typed: String, val searched: String, val retried: Boolean, val rows: List<EntryRow>) : ListBody {
        override fun toString(): String = "Results(retried=$retried, rows=${rows.size})"
    }

    /** Nothing matches what was [typed], on either keyboard layout: 'No accounts match “gmal”.' */
    data class NoResults(val typed: String) : ListBody {
        override fun toString(): String = "NoResults"
    }
}

/** One-off messages of the Passwords screen, shown in its snackbar. */
sealed interface ListEvent {
    /** "Gmail deleted", with "Undo" for about 10 seconds (ux.md §5.23). */
    class Deleted(val id: String, val title: String) : ListEvent {
        override fun toString(): String = "Deleted(id=$id)"
    }

    /**
     * A change was not saved, for [problem]: "Couldn't save. Your phone's storage may be full." with
     * "Try again" ([retry]), or the sentence of [SaveProblem.TOO_LARGE], which trying again cannot
     * help ([retry] null) (ux.md §5.11 "Error").
     */
    class NotSaved(val problem: SaveProblem, val retry: (() -> Unit)?) : ListEvent {
        override fun toString(): String = "NotSaved($problem, retry=${retry != null})"
    }

    /** The phone refused to copy (ux.md §6, "Failures"). */
    data object CopyBlocked : ListEvent

    /** The place of an unprotected export does not let apps delete files (ux.md §5.20). */
    data object CannotDeleteExport : ListEvent

    /** "Type it now" was answered with the right master password. */
    data object PracticeRight : ListEvent
}

/** The "Type it now" dialog: [checking] while the lock checks what was typed; the [error] of the last try. */
data class PracticeState(val checking: Boolean = false, val error: PracticeError? = null)

/** Why the last try of "Type it now" did not pass (lock.md §13.3). */
sealed interface PracticeError {
    /** "That's not the right master password. …" */
    data object Wrong : PracticeError

    /** The guard makes the field wait until `elapsedRealtime()` reaches [untilElapsedMs] (lock.md §6.8). */
    data class Wait(val untilElapsedMs: Long) : PracticeError

    /** Argon2id could not run: not counted, not wrong. */
    data object NoMemory : PracticeError

    /** The master password from before the change of [changedAtMs] (lock.md F16b). */
    data class Older(val changedAtMs: Long) : PracticeError
}
