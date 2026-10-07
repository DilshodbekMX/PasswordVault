package io.github.dilshodbekmx.passwordvault.ui.entry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.clipboard.SecretClipboard
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.EntryResult
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.model.VaultChange
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.data.SaveProblem
import io.github.dilshodbekmx.passwordvault.data.SaveResult
import io.github.dilshodbekmx.passwordvault.data.VaultRepository
import io.github.dilshodbekmx.passwordvault.data.VaultState
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * One account's screen (ux.md §5.12): its values with their copy and show buttons, the star, the
 * banners of a pending password change, of "left out of the security check" and of a password that
 * equals the master password, the previous passwords, and Delete with Undo.
 *
 * **Where it comes from.** The account is read from the open vault ([VaultRepository.state]) by its
 * id whenever the vault changes, so an edit made in the form shows here when the form closes. Every
 * change made here is one `VaultRepository.edit` with a function of `VaultEdits` (architecture.md
 * §5.4).
 *
 * **Secrets** are hidden until "Show" ([setShown]) and hidden again [REHIDE_AFTER_MS] after the last
 * touch on the screen ([touched]; every action here counts as one), when the screen stops
 * ([screenStopped]), when the vault locks, and whenever the account's secrets change under them
 * (ux.md §3). A hidden secret is not in [state] at all ([EntryDetails]); a copy reads its text from
 * the vault at the moment of the tap.
 *
 * **Copies** go to [SecretClipboard], which flags and clears them (ux.md §6): with the sensitive
 * flag for passwords, previous passwords, hidden field values and the notes; without it for the
 * username, websites and fields that are not hidden.
 *
 * **Delete** moves the account to Recently deleted at once (ux.md §5.23). The screen then shows
 * [EntryUiState.Deleted], for "Gmail deleted" with "Undo" ([undoDelete]). An account that this
 * screen did not delete itself, or that is not in the vault at all, is [EntryUiState.Gone].
 *
 * **A lock** publishes `VaultState.Locked`: everything of the account is dropped here at once,
 * because after a lock in the background the screen leaves only when the activity starts again
 * (architecture.md §5.5 step 5).
 *
 * Every function is called on the main thread.
 *
 * @param justSaved the form has just added this account (`SavedNotice.take`): the screen says "Saved".
 */
class EntryViewModel(
    val entryId: String,
    private val repository: VaultRepository,
    private val clipboard: SecretClipboard,
    private val clock: Clock,
    justSaved: Boolean = false,
) : ViewModel() {

    private val mutable = MutableStateFlow<EntryUiState>(EntryUiState.Loading)

    /** What the screen shows. */
    val state: StateFlow<EntryUiState> = mutable

    private val eventChannel = Channel<EntryEvent>(Channel.BUFFERED)

    /** One-off messages for the screen's snackbar. */
    val events: Flow<EntryEvent> = eventChannel.receiveAsFlow()

    /** The open vault as last published; null while locked. */
    private var vault: Vault? = null

    /** The secrets the person asked to see. */
    private var revealed: Set<EntryValue> = emptySet()
    private var notesExpanded = false
    private var previousExpanded = false

    /** Delete was tapped here and has not been undone: the account's absence is this screen's doing. */
    private var deletedHere = false

    /** `elapsedRealtime()` of the last touch on the screen. */
    private var lastTouchMs = clock.elapsedMs()
    private var hideTimer: Job? = null

    init {
        if (justSaved) eventChannel.trySend(EntryEvent.Saved)
        viewModelScope.launch { repository.state.collect(::onVault) }
    }

    // ---- The vault ---------------------------------------------------------------------------

    private fun onVault(now: VaultState) {
        when (now) {
            VaultState.Locked -> forgetAll()
            is VaultState.Unlocked -> {
                val before = vault?.let(::entryIn)
                vault = now.vault
                val after = entryIn(now.vault)
                // A row is named by its place in the account: when the secrets under the names
                // change, nothing stays shown, so that no other value appears in a shown row.
                if (before != null && revealed.isNotEmpty() && (after == null || secretsDiffer(before, after))) hideSecrets()
                render()
            }
        }
    }

    /** The account in [vault], also when it waits in Recently deleted; null when it is not there. */
    private fun entryIn(vault: Vault): Entry? = vault.entries.firstOrNull { it.id == entryId }

    /** The account outside Recently deleted, from the open vault; null when locked, deleted or gone. */
    private fun liveEntry(): Entry? = vault?.let(::entryIn)?.takeIf { it.deletedAt == null }

    private fun secretsDiffer(a: Entry, b: Entry): Boolean =
        a.password != b.password || a.pendingOldPassword != b.pendingOldPassword || a.changePending != b.changePending ||
            a.fields != b.fields || a.passwordHistory != b.passwordHistory || (a.deletedAt == null) != (b.deletedAt == null)

    private fun render() {
        val open = vault
        if (open == null) {
            mutable.value = EntryUiState.Loading
            return
        }
        val entry = entryIn(open)
        mutable.value = when {
            entry == null -> EntryUiState.Gone(inRecentlyDeleted = false)
            entry.deletedAt != null -> if (deletedHere) EntryUiState.Deleted(entry.title) else EntryUiState.Gone(inRecentlyDeleted = true)
            else -> EntryUiState.Shown(
                EntryDetails.of(entry, entryId in open.masterPasswordMatches, revealed, notesExpanded, previousExpanded),
            )
        }
    }

    /** The vault locked: nothing of the account stays here (architecture.md §5.5 step 5). */
    private fun forgetAll() {
        vault = null
        revealed = emptySet()
        notesExpanded = false
        previousExpanded = false
        deletedHere = false
        stopHideTimer()
        mutable.value = EntryUiState.Loading
    }

    // ---- Showing and hiding (ux.md §3) -------------------------------------------------------

    /** A touch anywhere on the screen: a shown value stays for another [REHIDE_AFTER_MS]. */
    fun touched() {
        lastTouchMs = clock.elapsedMs()
    }

    /**
     * The screen stopped: the app was left, or another screen covers this one. Shown values are
     * hidden again (ux.md §3; §12 check 3: "the last screen with secrets masked").
     */
    fun screenStopped() {
        if (revealed.isEmpty()) return
        hideSecrets()
        render()
    }

    /** "Show" ([shown] true) or "Hide" of the secret [value]. Showing what is not a secret does nothing. */
    fun setShown(value: EntryValue, shown: Boolean) {
        touched()
        val entry = liveEntry() ?: return
        if (shown) {
            if (!entry.hides(value) || entry.textOf(value) == null) return
            revealed = revealed + value
            startHideTimer()
        } else {
            revealed = revealed - value
            if (revealed.isEmpty()) stopHideTimer()
        }
        render()
    }

    private fun hideSecrets() {
        revealed = emptySet()
        stopHideTimer()
    }

    /** Hides everything [REHIDE_AFTER_MS] after the last touch; a touch meanwhile moves the time, and the timer sleeps again. */
    private fun startHideTimer() {
        if (hideTimer?.isActive == true) return
        hideTimer = viewModelScope.launch {
            while (true) {
                val deadline = lastTouchMs + REHIDE_AFTER_MS
                if (clock.elapsedMs() >= deadline) break
                clock.delayUntilElapsed(deadline)
            }
            hideTimer = null
            revealed = emptySet()
            render()
        }
    }

    private fun stopHideTimer() {
        hideTimer?.cancel()
        hideTimer = null
    }

    // ---- Copying (ux.md §6): true when the clip was handed over ------------------------------

    /** The copy button of [value]'s row. */
    fun copy(value: EntryValue): Boolean {
        touched()
        val entry = liveEntry() ?: return false
        val text = entry.textOf(value) ?: return false
        return try {
            clipboard.copy(text, describe(value, entry), entry.copiesAsSensitive(value))
            true
        } catch (e: RuntimeException) {
            // Some vendor builds throw here (ux.md §6, "Failures"); nothing of the value is kept.
            eventChannel.trySend(EntryEvent.CopyBlocked)
            false
        }
    }

    /** What the clipboard row calls a copy of [value]: "Password for Gmail" (ux.md §6). */
    private fun describe(value: EntryValue, entry: Entry): UiText = when (value) {
        EntryValue.Username -> UiText.of(R.string.entry_copied_username, entry.title)
        EntryValue.Password ->
            UiText.of(if (entry.changePending) R.string.entry_copied_new_password else R.string.entry_copied_password, entry.title)
        EntryValue.OldPassword -> UiText.of(R.string.entry_copied_old_password, entry.title)
        is EntryValue.Previous -> UiText.of(R.string.entry_copied_previous, entry.title)
        is EntryValue.Field -> UiText.of(R.string.entry_copied_field, entry.fields[value.index].name, entry.title)
        is EntryValue.Website -> UiText.of(R.string.entry_copied_website, entry.title)
        EntryValue.Notes -> UiText.of(R.string.entry_copied_notes, entry.title)
    }

    // ---- The account's marks (ux.md §5.12) ---------------------------------------------------

    /** The star: "Add to favourites" or "Remove from favourites". */
    fun setFavourite(favourite: Boolean) {
        touched()
        if (liveEntry() != null) save(VaultEdits.setFavourite(entryId, favourite), again = { setFavourite(favourite) })
    }

    /** "Leave out of the security check" ([ignored] true) and the banner's "Check it again" (architecture.md D-9a). */
    fun setCheckIgnored(ignored: Boolean) {
        touched()
        if (liveEntry() != null) save(VaultEdits.setCheckIgnored(entryId, ignored), again = { setCheckIgnored(ignored) })
    }

    /** "Yes, it's changed" on the banner of a pending change: the old password joins the previous ones (ux.md §5.17). */
    fun confirmChange() {
        touched()
        if (liveEntry()?.changePending == true) save(VaultEdits.confirmChange(entryId), again = ::confirmChange)
    }

    /** "No, put back the old password": it is the account's password again, and the new one joins the previous ones. */
    fun revertChange() {
        touched()
        if (liveEntry()?.changePending == true) save(VaultEdits.revertChange(entryId), again = ::revertChange)
    }

    // ---- Notes and previous passwords --------------------------------------------------------

    /** "Show more" and "Show less" under notes of more than [NOTES_LINES] lines. */
    fun setNotesExpanded(expanded: Boolean) {
        touched()
        if (notesExpanded == expanded) return
        notesExpanded = expanded
        render()
    }

    /** "Previous passwords (2)": opens or closes its rows; closing hides those that were shown. */
    fun setPreviousExpanded(expanded: Boolean) {
        touched()
        if (previousExpanded == expanded) return
        previousExpanded = expanded
        if (!expanded) {
            revealed = revealed.filterNot { it is EntryValue.Previous }.toSet()
            if (revealed.isEmpty()) stopHideTimer()
        }
        render()
    }

    // ---- Delete and Undo (ux.md §5.12, §5.23) ------------------------------------------------

    /** "Delete": into Recently deleted at once, with no question; the screen then offers "Undo". */
    fun delete() {
        touched()
        if (liveEntry() == null) return
        deletedHere = true
        save(
            change = VaultEdits.delete(entryId),
            again = ::delete,
            notSaved = {
                deletedHere = false
                render()
            },
            saved = { result ->
                if (result !is EntryResult.Done) {
                    deletedHere = false
                    render()
                }
            },
        )
    }

    /** The snackbar's "Undo" after [delete]: the account is back. */
    fun undoDelete() {
        save(
            change = VaultEdits.restore(entryId),
            again = ::undoDelete,
            saved = { result ->
                if (result is EntryResult.Done) {
                    deletedHere = false
                    render()
                }
            },
        )
    }

    /** The wall-clock time now, for "Password changed 3 days ago". */
    fun wallMs(): Long = clock.wallMs()

    // ---- Inside ------------------------------------------------------------------------------

    /**
     * Saves [change]. When it is not saved, [notSaved] runs and the screen is told, with [again] as
     * its "Try again": the same action from its start, so that it checks and marks what it did the
     * first time.
     */
    private fun <T> save(change: VaultChange<T>, again: () -> Unit, notSaved: () -> Unit = {}, saved: (T) -> Unit = {}) {
        viewModelScope.launch {
            when (val result = repository.edit(change)) {
                is SaveResult.Saved -> saved(result.value)
                is SaveResult.NotSaved -> {
                    notSaved()
                    // Another try cannot make a vault that is too large fit.
                    eventChannel.send(EntryEvent.NotSaved(result.problem, again.takeIf { result.problem != SaveProblem.TOO_LARGE }))
                }
            }
        }
    }

    override fun onCleared() {
        stopHideTimer()
        revealed = emptySet()
        vault = null
    }

    companion object {
        /** A shown value hides again this long after the last touch on the screen (ux.md §3): 30 seconds. */
        const val REHIDE_AFTER_MS: Long = 30_000L

        /** Notes of more lines than this collapse behind "Show more" (ux.md §5.12). */
        const val NOTES_LINES: Int = 12

        /** The screen of the account [id] over the app's own parts. */
        fun create(graph: AppGraph, id: String, justSaved: Boolean): EntryViewModel = EntryViewModel(
            entryId = id,
            repository = graph.data.repository,
            clipboard = graph.clipboard,
            clock = graph.platform.clock,
            justSaved = justSaved,
        )
    }
}

/** What an account's screen shows. */
sealed interface EntryUiState {
    /** The vault is locked, or has not been seen yet: nothing of the account is held. */
    data object Loading : EntryUiState

    /** The account, outside Recently deleted. */
    data class Shown(val details: EntryDetails) : EntryUiState

    /**
     * Delete was tapped on this screen: "[title] deleted" with "Undo" for about 10 seconds, then the
     * screen closes (ux.md §5.12).
     */
    data class Deleted(val title: String) : EntryUiState {
        override fun toString(): String = "Deleted(title hidden)"
    }

    /**
     * The route's account is not shown: it waits in Recently deleted ([inRecentlyDeleted]), from
     * where it can be restored, or it is not in the vault at all: "This account no longer exists."
     * (ux.md §5.12 "States").
     */
    data class Gone(val inRecentlyDeleted: Boolean) : EntryUiState
}

/** One-off messages of an account's screen, shown in its snackbar. */
sealed interface EntryEvent {
    /** The form has just added this account: "Saved" (ux.md §5.14). */
    data object Saved : EntryEvent

    /** The phone refused to copy (ux.md §6, "Failures"). */
    data object CopyBlocked : EntryEvent

    /**
     * A change was not saved, for [problem]: "Couldn't save. Your phone's storage may be full." with
     * "Try again" ([retry]), or the sentence of [SaveProblem.TOO_LARGE], which trying again cannot
     * help ([retry] null).
     */
    class NotSaved(val problem: SaveProblem, val retry: (() -> Unit)?) : EntryEvent {
        override fun toString(): String = "NotSaved($problem, retry=${retry != null})"
    }
}
