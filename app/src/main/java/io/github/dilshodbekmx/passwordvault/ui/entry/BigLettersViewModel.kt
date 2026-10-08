package io.github.dilshodbekmx.passwordvault.ui.entry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.data.VaultRepository
import io.github.dilshodbekmx.passwordvault.data.VaultState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * "Show in big letters" for one value of an account (ux.md §5.13): the value cut into boxes
 * ([BigCell]), the box the person marked to keep their place, and when the screen closes.
 *
 * The route names the value by the account's id and a field name (`Route.BigLetters`, architecture.md
 * §3.6); the text is read from the open vault once, here, and is never in a route or in saved
 * state. Showing a whole secret large is an accepted exception to "show secrets briefly"
 * (architecture.md D-9d), so the screen is strict about when it ends. It is [BigLettersUiState.closed],
 * with nothing left to show, as soon as any of these happens:
 * - [IDLE_CLOSE_MS] pass without a touch ([touched]; marking a box is one);
 * - the screen stops, or Close or Back is used ([close]);
 * - the vault locks;
 * - there is nothing to show: no such account, no such value, an empty one.
 *
 * The screen leaves when it sees [BigLettersUiState.closed]; [takeClose] makes sure it does so once.
 * Every function is called on the main thread.
 */
class BigLettersViewModel(
    entryId: String,
    field: String,
    repository: VaultRepository,
    private val clock: Clock,
) : ViewModel() {

    private val mutable = MutableStateFlow(BigLettersUiState.CLOSED)

    /** What the screen shows. */
    val state: StateFlow<BigLettersUiState> = mutable

    private var lastTouchMs = clock.elapsedMs()
    private var idleTimer: Job? = null
    private var left = false

    init {
        val shown = read(repository.state.value, entryId, field)
        if (shown != null) {
            mutable.value = shown
            startIdleTimer()
            viewModelScope.launch {
                repository.state.collect { if (it is VaultState.Locked) close() }
            }
        }
    }

    /** The boxes of the value that [field] names in the account [entryId] of the open vault; null when there is nothing to show. */
    private fun read(vault: VaultState, entryId: String, field: String): BigLettersUiState? {
        val open = vault as? VaultState.Unlocked ?: return null
        val entry = open.vault.entries.firstOrNull { it.id == entryId && it.deletedAt == null } ?: return null
        val value = EntryValue.of(field) ?: return null
        val text = entry.textOf(value) ?: return null
        val cells = BigCell.of(text)
        val tooLong = cells.size > BigCell.MAX
        return BigLettersUiState(
            closed = false,
            value = value,
            fieldName = (value as? EntryValue.Field)?.let { entry.fields[it.index].name },
            changePending = entry.changePending,
            title = entry.title,
            cells = if (tooLong) emptyList() else cells,
            tooLong = tooLong,
            markedUpTo = NOT_MARKED,
        )
    }

    /** A touch anywhere on the screen: it stays open for another [IDLE_CLOSE_MS]. */
    fun touched() {
        lastTouchMs = clock.elapsedMs()
    }

    /**
     * A tap on the box at [index]: it and every box before it are marked, to keep the person's
     * place (ux.md §5.13). A tap on the last marked box takes the mark away.
     */
    fun mark(index: Int) {
        touched()
        mutable.update { now ->
            if (now.closed || index !in now.cells.indices) now else now.copy(markedUpTo = if (now.markedUpTo == index) NOT_MARKED else index)
        }
    }

    /** Close, Back, the screen stopping, a lock, or two minutes without a touch: nothing of the value is kept. */
    fun close() {
        idleTimer?.cancel()
        idleTimer = null
        if (!mutable.value.closed) mutable.value = BigLettersUiState.CLOSED
    }

    /**
     * Whether the screen should leave now: true once, after [BigLettersUiState.closed]. The close
     * can be seen in several places (the button, Back, the stopped screen, the state), and the
     * screen must go back one step, not two.
     */
    fun takeClose(): Boolean {
        if (left || !mutable.value.closed) return false
        left = true
        return true
    }

    /** Closes [IDLE_CLOSE_MS] after the last touch; a touch meanwhile moves the time, and the timer sleeps again. */
    private fun startIdleTimer() {
        idleTimer = viewModelScope.launch {
            while (true) {
                val deadline = lastTouchMs + IDLE_CLOSE_MS
                if (clock.elapsedMs() >= deadline) break
                clock.delayUntilElapsed(deadline)
            }
            idleTimer = null
            close()
        }
    }

    override fun onCleared() {
        // The screen has left the back stack already: it must not take this end for a reason to go
        // back once more, should it still be composed while it slides away.
        left = true
        close()
    }

    companion object {
        /** "Show in big letters" closes this long after the last touch (ux.md §5.13): 2 minutes. */
        const val IDLE_CLOSE_MS: Long = 120_000L

        /** [BigLettersUiState.markedUpTo] while no box is marked. */
        const val NOT_MARKED: Int = -1

        /** "Show in big letters" for the value [field] of the account [id], over the app's own parts. */
        fun create(graph: AppGraph, id: String, field: String): BigLettersViewModel =
            BigLettersViewModel(id, field, graph.data.repository, graph.platform.clock)
    }
}

/**
 * What "Show in big letters" shows.
 *
 * @property closed the screen is over: nothing else here holds anything, and the screen leaves.
 * @property value which value of the account this is, for the title ("Password", "Username").
 * @property fieldName the label of the extra field, when [value] is one.
 * @property changePending the account's password change is not finished: "password" is the new one.
 * @property title the account's name.
 * @property cells one box per character; empty when [tooLong].
 * @property tooLong the value has more than [BigCell.MAX] characters: "Too long to show in big letters".
 * @property markedUpTo the last marked box, counted from 0; it and every box before it are
 *   highlighted. [BigLettersViewModel.NOT_MARKED] for none.
 *
 * [toString] shows no text of the account (architecture.md §5.6).
 */
data class BigLettersUiState(
    val closed: Boolean,
    val value: EntryValue?,
    val fieldName: String?,
    val changePending: Boolean,
    val title: String,
    val cells: List<BigCell>,
    val tooLong: Boolean,
    val markedUpTo: Int,
) {
    override fun toString(): String =
        "BigLettersUiState(closed=$closed, value=$value, cells=${cells.size}, tooLong=$tooLong, markedUpTo=$markedUpTo, texts hidden)"

    companion object {
        /** Nothing to show. */
        val CLOSED = BigLettersUiState(
            closed = true,
            value = null,
            fieldName = null,
            changePending = false,
            title = "",
            cells = emptyList(),
            tooLong = false,
            markedUpTo = BigLettersViewModel.NOT_MARKED,
        )
    }
}
