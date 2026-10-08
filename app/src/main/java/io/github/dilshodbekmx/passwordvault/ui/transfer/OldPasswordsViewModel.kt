package io.github.dilshodbekmx.passwordvault.ui.transfer

import androidx.compose.foundation.text.input.TextFieldState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideVault
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.lock.AsideResult
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.ui.common.clearSecret
import io.github.dilshodbekmx.passwordvault.ui.common.readSecret
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings › Backup & transfer › Old passwords (ux.md §5.10 "Archived vaults", §5.18; lock.md F17
 * step 5): the vaults put aside on this phone ("Start with an empty app", a restore over the
 * passwords on the phone, a replacement), each with "Open" and "Delete".
 *
 * - **"Open"** adds that vault's accounts to the current vault, by the rules of a backup file
 *   (ux.md §5.21): `LockManager.openAside`.
 * - **"Delete"** deletes it for good: `LockManager.deleteAside`, which holds the lock for up to two
 *   seconds while it settles what another vault's records say about it, so the step shows its
 *   progress ([OldStep.Asking.working]).
 *
 * **Both need that vault's own master password, and nothing else will do**: not the current master
 * password, not a proof of this session, not a pending password. Otherwise anyone holding the phone
 * could start over and then delete the owner's passwords from the new vault's Settings (ux.md
 * §5.10). So each try sends what was typed in this step, for that vault, to the lock, which checks
 * it under that vault's own guard and waits (lock.md §6.8). After a refusal or a failure the lock has
 * dropped the vault it opened for the check (`VaultPort.dropAside`), so the next try is typed again:
 * the field is emptied at every try.
 */
class OldPasswordsViewModel(private val lock: LockManager, private val clock: Clock) : ViewModel() {

    /** The field of the step that opens or deletes a vault. */
    val password = TextFieldState()

    private val mutableStep = MutableStateFlow<OldStep>(OldStep.List())

    /** What the screen shows. */
    val step: StateFlow<OldStep> = mutableStep.asStateFlow()

    private val mutableWaitSeconds = MutableStateFlow<Int?>(null)

    /** The seconds left of a wait after several wrong master passwords, or null. */
    val waitSeconds: StateFlow<Int?> = mutableWaitSeconds.asStateFlow()

    private var work: Job? = null
    private var ticker: Job? = null

    init {
        viewModelScope.launch {
            lock.state.collect { if (it !is LockUiState.Unlocked) locked() }
        }
        viewModelScope.launch {
            lock.status.collect { status ->
                // A vault that is no longer put aside (deleted, or opened from Welcome meanwhile) has no step.
                val asking = step.value as? OldStep.Asking ?: return@collect
                if (!asking.working && status.aside.none { it.vaultId == asking.vault.vaultId }) back()
            }
        }
    }

    /** The vaults put aside, newest first (`LockStatus.aside`). */
    val vaults: StateFlow<List<AsideVault>> = lock.status
        .map { status -> status.aside.sortedByDescending { it.putAsideAtMs } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, lock.status.value.aside.sortedByDescending { it.putAsideAtMs })

    /** "Open" or "Delete" of [vault]: its master-password step. */
    fun ask(vault: AsideVault, action: OldAction) {
        if ((step.value as? OldStep.Asking)?.working == true) return
        password.clearSecret()
        show(OldStep.Asking(vault, action))
    }

    /** The step's button, or the keyboard's Done: that vault's master password, typed now. */
    fun submit() {
        val now = step.value as? OldStep.Asking ?: return
        if (now.working || waiting(now)) return
        val chars = password.readSecret()
        password.clearSecret()
        if (chars.isEmpty()) return
        show(now.copy(working = true, problem = null, waitUntilElapsedMs = null))
        work = viewModelScope.launch {
            val result = try {
                when (now.action) {
                    OldAction.OPEN -> lock.openAside(now.vault.vaultId, chars)
                    OldAction.DELETE -> lock.deleteAside(now.vault.vaultId, chars)
                }
            } finally {
                chars.fill(ZERO)
            }
            if ((step.value as? OldStep.Asking)?.working != true) return@launch // locked meanwhile
            when (result) {
                AsideResult.Done -> show(OldStep.List(done = if (now.action == OldAction.OPEN) OldDone.ADDED else OldDone.DELETED))
                AsideResult.Wrong -> show(now.copy(problem = OldProblem.WRONG))
                is AsideResult.Wait -> waitUntil(now, result.untilElapsedMs)
                AsideResult.NoMemory -> show(now.copy(problem = OldProblem.NO_MEMORY))
                AsideResult.NotSaved -> show(now.copy(problem = OldProblem.NOT_SAVED))
            }
        }
    }

    /** "Cancel" or Back on a step: back to the list. Ignored while the lock works on it. */
    fun back(): Boolean {
        val now = step.value as? OldStep.Asking ?: return false
        if (now.working) return true
        password.clearSecret()
        show(OldStep.List())
        return true
    }

    override fun onCleared() {
        password.clearSecret()
    }

    private fun waitUntil(asking: OldStep.Asking, untilElapsedMs: Long) {
        show(asking.copy(waitUntilElapsedMs = untilElapsedMs))
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (true) {
                val left = untilElapsedMs - clock.elapsedMs()
                if (left <= 0) break
                val seconds = ((left + SECOND_MS - 1) / SECOND_MS).toInt()
                mutableWaitSeconds.value = seconds
                clock.delayUntilElapsed(untilElapsedMs - (seconds - 1) * SECOND_MS)
            }
            mutableWaitSeconds.value = null
        }
    }

    private fun waiting(now: OldStep.Asking): Boolean {
        val until = now.waitUntilElapsedMs ?: return false
        return clock.elapsedMs() < until
    }

    private fun show(next: OldStep) {
        if (next !is OldStep.Asking || next.waitUntilElapsedMs == null) {
            ticker?.cancel()
            ticker = null
            mutableWaitSeconds.value = null
        }
        mutableStep.value = next
    }

    private fun locked() {
        work?.cancel()
        work = null
        password.clearSecret()
        show(OldStep.List())
    }

    companion object {
        private const val SECOND_MS = 1_000L
        private const val ZERO = '\u0000'

        /** The screen over the app's own lock. */
        fun create(graph: AppGraph): OldPasswordsViewModel = OldPasswordsViewModel(graph.lock, graph.platform.clock)
    }
}

/** What the old-passwords screen shows. */
sealed interface OldStep {
    /** The list of vaults put aside; [done] says what the last action did, until it was shown. */
    data class List(val done: OldDone? = null) : OldStep

    /**
     * The master-password step of [action] for [vault]; [working] while the lock checks it and acts;
     * [problem] about the last try; a wait until [waitUntilElapsedMs].
     */
    data class Asking(
        val vault: AsideVault,
        val action: OldAction,
        val working: Boolean = false,
        val problem: OldProblem? = null,
        val waitUntilElapsedMs: Long? = null,
    ) : OldStep
}

/** The two buttons of a vault put aside. */
enum class OldAction { OPEN, DELETE }

/** What an action did. */
enum class OldDone {
    /** "Their accounts were added to your passwords." */
    ADDED,

    /** "The old passwords were deleted." */
    DELETED,
}

/** Why the last try did nothing. */
enum class OldProblem {
    /** Not that vault's master password. */
    WRONG,

    /** Too little memory to check it; not counted. */
    NO_MEMORY,

    /** The accounts could not be added, or the vault could not be deleted; nothing changed. */
    NOT_SAVED,
}
