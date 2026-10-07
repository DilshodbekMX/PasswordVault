package io.github.dilshodbekmx.passwordvault.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.core.model.EntryResult
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.model.VaultChange
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import io.github.dilshodbekmx.passwordvault.data.SaveProblem
import io.github.dilshodbekmx.passwordvault.data.SaveResult
import io.github.dilshodbekmx.passwordvault.data.VaultRepository
import io.github.dilshodbekmx.passwordvault.data.VaultState
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Recently deleted (ux.md §5.23): the accounts that wait there, newest deletion first, each with
 * "Restore" and "Delete forever", and "Empty" for all of them. It reads the vault itself
 * ([VaultRepository.state]); the list's model leaves deleted accounts out.
 *
 * The removal 30 days later is not here: the repository arms and carries out the purge of
 * architecture.md D-24 at unlock. "Delete forever" and "Empty" ask first, because they cannot be
 * undone; the question is kept here, so that it survives a rotation without saved state, and goes
 * with everything else when the vault locks.
 */
class RecentlyDeletedViewModel(private val repository: VaultRepository, dispatchers: AppDispatchers) : ViewModel() {

    private val dialogState = MutableStateFlow<DeletedDialog?>(null)
    private val eventChannel = Channel<DeletedEvent>(Channel.BUFFERED)

    /** The deleted accounts, or null while the vault is locked. */
    val rows: StateFlow<List<DeletedRow>?> = repository.state
        .map { vault -> if (vault is VaultState.Unlocked) rowsOf(vault.vault) else null }
        .flowOn(dispatchers.default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The question shown, or null. */
    val dialog: StateFlow<DeletedDialog?> = dialogState.asStateFlow()

    /** One-off messages for the snackbar. */
    val events: Flow<DeletedEvent> = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            repository.state.collect { if (it is VaultState.Locked) dialogState.value = null }
        }
    }

    /** "Restore": back into the list. */
    fun restore(id: String) {
        val title = row(id)?.title ?: return
        save(VaultEdits.restore(id)) { if (it is EntryResult.Done) eventChannel.send(DeletedEvent.Restored(title)) }
    }

    /** "Delete forever" in a row's menu: asks first (ux.md §5.23). */
    fun askDeleteForever(id: String) {
        val row = row(id) ?: return
        dialogState.value = DeletedDialog.Forever(row.id, row.title)
    }

    /** "Empty" in the top bar: asks first, with the count (ux.md §5.23). */
    fun askEmpty() {
        val count = rows.value?.size ?: 0
        if (count > 0) dialogState.value = DeletedDialog.Empty(count)
    }

    /** "Delete" in the question. */
    fun confirm() {
        when (val asked = dialogState.value) {
            is DeletedDialog.Forever -> save(VaultEdits.deleteForever(asked.id))
            is DeletedDialog.Empty -> save(VaultEdits.emptyRecentlyDeleted())
            null -> Unit
        }
        dialogState.value = null
    }

    /** "Cancel", Back or a tap outside the question. */
    fun dismiss() {
        dialogState.value = null
    }

    private fun row(id: String): DeletedRow? = rows.value?.firstOrNull { it.id == id }

    private fun <T> save(change: VaultChange<T>, done: suspend (T) -> Unit = {}) {
        viewModelScope.launch {
            when (val result = repository.edit(change)) {
                is SaveResult.Saved -> done(result.value)
                is SaveResult.NotSaved -> eventChannel.send(DeletedEvent.NotSaved(result.problem))
            }
        }
    }

    companion object {
        /** The ViewModel of the app's Recently deleted screen. */
        fun from(graph: AppGraph): RecentlyDeletedViewModel = RecentlyDeletedViewModel(graph.data.repository, graph.dispatchers)

        /** The deleted accounts of [vault]: the newest deletion first, then by title and id, so that the order is stable. */
        fun rowsOf(vault: Vault): List<DeletedRow> = vault.entries
            .filter { it.deletedAt != null }
            .map { DeletedRow(it.id, it.title, it.deletedAt ?: 0L) }
            .sortedWith(compareByDescending<DeletedRow> { it.deletedAtMs }.thenBy { it.title.lowercase(Locale.ROOT) }.thenBy { it.id })
    }
}

/** One account in Recently deleted: deleted at [deletedAtMs] (wall clock). [toString] shows no title. */
data class DeletedRow(val id: String, val title: String, val deletedAtMs: Long) {
    override fun toString(): String = "DeletedRow(id=$id, deletedAtMs=$deletedAtMs)"
}

/** The questions of Recently deleted (ux.md §5.23). */
sealed interface DeletedDialog {
    /** "Delete Gmail forever? This can't be undone." */
    data class Forever(val id: String, val title: String) : DeletedDialog {
        override fun toString(): String = "Forever(id=$id)"
    }

    /** "Delete all 3 accounts forever? This can't be undone." */
    data class Empty(val count: Int) : DeletedDialog
}

/** One-off messages of Recently deleted. */
sealed interface DeletedEvent {
    /** "Gmail restored". */
    class Restored(val title: String) : DeletedEvent {
        override fun toString(): String = "Restored"
    }

    /** A change was not saved, for [problem]. */
    data class NotSaved(val problem: SaveProblem) : DeletedEvent
}
