package io.github.dilshodbekmx.passwordvault.ui.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.EntryResult
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.model.VaultChange
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import io.github.dilshodbekmx.passwordvault.core.strength.SecurityReport
import io.github.dilshodbekmx.passwordvault.data.SaveProblem
import io.github.dilshodbekmx.passwordvault.data.SaveResult
import io.github.dilshodbekmx.passwordvault.data.SecurityReports
import io.github.dilshodbekmx.passwordvault.data.VaultRepository
import io.github.dilshodbekmx.passwordvault.data.VaultState
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The list behind a card of the Security tab (ux.md §5.16, "Group list"), for the route
 * `Group(listId)`: one group of reused passwords ([listId] is its `ReuseGroup.id`), or the accounts
 * whose password is easy to guess, whose change is not finished, or that are left out of the check
 * (the ids of [GroupKind]).
 *
 * - **Rows** are title and username, in the report's order: accounts that look like email first,
 *   then favourites, then A–Z (`SecurityCheck.listOrder`). The password is never shown.
 * - **What a row offers** depends on the list ([GroupKind]): "Change" opens the guided change of
 *   ux.md §5.17 and the ⋮ menu has "Leave out of the security check" (D-9a); "Continue" goes on with
 *   a change not finished; "Check it again" brings an account back into the check. Nothing here
 *   combines accounts (ux.md §5.16).
 * - **When the list is empty** (every account of a group has its own password now), the screen says
 *   so instead of closing by itself.
 * - **Dropped with the vault.** [state] is null while the vault is locked; it is shared eagerly, so it
 *   drops the account names at once, also while the screen is in the background (architecture.md
 *   §5.5 step 5).
 */
class GroupListViewModel(
    val listId: String,
    reports: SecurityReports,
    private val repository: VaultRepository,
    dispatchers: AppDispatchers,
) : ViewModel() {

    /** Which list this is. */
    val kind: GroupKind = GroupKind.of(listId)

    private val eventChannel = Channel<GroupEvent>(Channel.BUFFERED)

    /** One-off messages for the snackbar. */
    val events: Flow<GroupEvent> = eventChannel.receiveAsFlow()

    /** What the list shows; null while the vault is locked. */
    val state: StateFlow<GroupUi?> = combine(repository.state, reports.report) { vault, report ->
        if (vault !is VaultState.Unlocked) null else uiOf(listId, report, vault.vault)
    }
        .flowOn(dispatchers.default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** "Leave out of the security check" in a row's menu (D-9a). */
    fun leaveOut(id: String) {
        val row = row(id) ?: return
        save(VaultEdits.setCheckIgnored(id, true)) { if (it is EntryResult.Done) eventChannel.send(GroupEvent.LeftOut(id, row.title)) }
    }

    /** "Undo" after [leaveOut]: the account is checked again. */
    fun undoLeaveOut(id: String) {
        save(VaultEdits.setCheckIgnored(id, false))
    }

    /** "Check it again" in the list of accounts left out (D-9a). */
    fun checkAgain(id: String) {
        val row = row(id) ?: return
        save(VaultEdits.setCheckIgnored(id, false)) { if (it is EntryResult.Done) eventChannel.send(GroupEvent.CheckedAgain(row.title)) }
    }

    private fun row(id: String): GroupRow? = state.value?.rows?.firstOrNull { it.id == id }

    private fun save(change: VaultChange<EntryResult>, done: suspend (EntryResult) -> Unit = {}) {
        viewModelScope.launch {
            when (val result = repository.edit(change)) {
                is SaveResult.Saved -> done(result.value)
                is SaveResult.NotSaved -> {
                    val retry = if (result.problem == SaveProblem.TOO_LARGE) null else ({ save(change, done) })
                    eventChannel.send(GroupEvent.NotSaved(result.problem, retry))
                }
            }
        }
    }

    companion object {
        /** The ViewModel of the app's list [listId]. */
        fun from(graph: AppGraph, listId: String): GroupListViewModel =
            GroupListViewModel(listId, graph.security, graph.data.repository, graph.dispatchers)

        /**
         * The list [listId] of [report] for [vault]: its rows, in the report's order, of the accounts the
         * vault still has outside Recently deleted; null rows while there is no report yet (the first
         * check after unlock is running).
         */
        fun uiOf(listId: String, report: SecurityReport?, vault: Vault): GroupUi {
            if (report == null) return GroupUi(rows = null, changed = 0)
            val live = HashMap<String, Entry>()
            for (entry in vault.entries) if (entry.deletedAt == null) live[entry.id] = entry
            fun rowsOf(ids: List<String>) = ids.mapNotNull { id -> live[id]?.let { GroupRow(it.id, it.title, it.username) } }
            return when (GroupKind.of(listId)) {
                GroupKind.WEAK -> GroupUi(rowsOf(report.weak), changed = 0)
                GroupKind.PENDING -> GroupUi(rowsOf(report.pending), changed = 0)
                GroupKind.LEFT_OUT -> GroupUi(rowsOf(report.leftOut), changed = 0)
                GroupKind.REUSED -> {
                    val group = report.reused.firstOrNull { it.id == listId }
                    val rows = group?.let { rowsOf(it.entryIds) }.orEmpty()
                    // A group of one is no group: that account has its own password now.
                    if (rows.size < 2) GroupUi(emptyList(), changed = group?.changed ?: 0) else GroupUi(rows, group?.changed ?: 0)
                }
            }
        }
    }
}

/**
 * The lists behind the cards (ux.md §5.16). The route `Group(id)` holds a group's `ReuseGroup.id`
 * (an entry's UUID, never derived from the password) or one of the fixed ids [WEAK_ID],
 * [PENDING_ID] and [LEFT_OUT_ID], which no UUID can be.
 */
enum class GroupKind {
    /** One group of accounts that share a password: "Change", and "Leave out of the security check". */
    REUSED,

    /** Card 3, easy to guess: "Change", and "Leave out of the security check". */
    WEAK,

    /** Card 4, changes not finished: "Continue". */
    PENDING,

    /** Card 5, left out of the check: "Check it again". */
    LEFT_OUT,
    ;

    /** The id of this list in the route `Group(id)`; for [REUSED] it is the group's own id. */
    val fixedId: String?
        get() = when (this) {
            WEAK -> WEAK_ID
            PENDING -> PENDING_ID
            LEFT_OUT -> LEFT_OUT_ID
            REUSED -> null
        }

    companion object {
        const val WEAK_ID: String = "weak"
        const val PENDING_ID: String = "pending"
        const val LEFT_OUT_ID: String = "left-out"

        /** Which list the route's [listId] names: a fixed id, or else a group of reused passwords. */
        fun of(listId: String): GroupKind = when (listId) {
            WEAK_ID -> WEAK
            PENDING_ID -> PENDING
            LEFT_OUT_ID -> LEFT_OUT
            else -> REUSED
        }
    }
}

/**
 * What a list shows: its [rows], null while the first check after unlock is running and empty when
 * nothing is left in it; for a group of reused passwords how many accounts [changed] it.
 */
data class GroupUi(val rows: List<GroupRow>?, val changed: Int)

/** One account of a list: its name and username. Its [toString] shows neither. */
data class GroupRow(val id: String, val title: String, val username: String) {
    override fun toString(): String = "GroupRow(id=$id)"
}

/** One-off messages of a list. */
sealed interface GroupEvent {
    /** "Gmail is left out of the security check." with Undo. */
    class LeftOut(val id: String, val title: String) : GroupEvent {
        override fun toString(): String = "LeftOut(id=$id)"
    }

    /** "Gmail is checked again." */
    class CheckedAgain(val title: String) : GroupEvent {
        override fun toString(): String = "CheckedAgain"
    }

    /** A change was not saved, for [problem]; [retry] is "Try again", null when another try cannot help. */
    class NotSaved(val problem: SaveProblem, val retry: (() -> Unit)?) : GroupEvent {
        override fun toString(): String = "NotSaved($problem, retry=${retry != null})"
    }
}
