package io.github.dilshodbekmx.passwordvault.fake

import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.model.Draft
import io.github.dilshodbekmx.passwordvault.core.model.Edited
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.data.BackupResult
import io.github.dilshodbekmx.passwordvault.data.SaveProblem
import io.github.dilshodbekmx.passwordvault.data.SaveResult
import io.github.dilshodbekmx.passwordvault.data.VaultRepository
import io.github.dilshodbekmx.passwordvault.data.VaultState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * An in-memory [VaultRepository] for the shell, previews and ViewModel tests (architecture.md §2.2
 * `fake`). It keeps one vault, [stored], which [open] publishes and [close] hides; nothing is
 * written anywhere. Scripted with [failures] (the next edits fail), [backupResults] and
 * [backupBytes]; [edits] and [flushes] count what was asked. Single-threaded: use it on one thread.
 *
 * @param clock the wall-clock time an edit receives as `nowMs`.
 */
class FakeVaultRepository(
    vault: Vault? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : VaultRepository {

    /** Set it to show any state; [open] and [close] set it too. */
    override val state = MutableStateFlow<VaultState>(if (vault == null) VaultState.Locked else VaultState.Unlocked(vault, 1L, null, false))

    /** The vault as last saved: kept while closed, shown again by [open]. */
    var stored: Vault = vault ?: Vault()
        private set

    /** The generation of [stored]; every save that changes something adds one. */
    var generation: Long = 1L
        private set

    /** The next edits fail with these problems, one per edit, and change nothing. */
    val failures = ArrayDeque<SaveProblem>()

    /** The answers of [openBackup], one per call; when empty, [BackupResult.NotOurs]. */
    val backupResults = ArrayDeque<BackupResult>()

    /** What [sealBackup] returns (a copy). */
    var backupBytes: ByteArray = "PVAULT01".encodeToByteArray()

    /** What [matchesMasterPassword] returns. */
    var matches: Set<String> = emptySet()

    /** The draft [holdDraft] last received. */
    var heldDraft: Draft? = null
        private set

    /** How many times [edit] was called, and how many of those changed the vault. */
    var edits: Int = 0
        private set
    var saves: Int = 0
        private set

    /** How many times [flushDraft] was called. */
    var flushes: Int = 0
        private set

    /** Unlocks with [vault] (by default the stored one), as the lock does after a right master password, PIN or fingerprint. */
    fun open(vault: Vault = stored, olderCopy: Boolean = false) {
        stored = vault
        state.value = VaultState.Unlocked(vault, generation, null, olderCopy)
    }

    /** Drops the open vault, as `VaultPort.lock()` does; [stored] stays for the next [open]. */
    fun close() {
        heldDraft = null
        state.value = VaultState.Locked
    }

    override suspend fun <T> edit(change: (Vault, nowMs: Long) -> Edited<T>): SaveResult<T> {
        edits++
        val open = state.value as? VaultState.Unlocked ?: return SaveResult.NotSaved(SaveProblem.FAILED)
        failures.removeFirstOrNull()?.let { problem ->
            state.value = VaultState.Unlocked(open.vault, open.generation, problem, open.olderCopy)
            return SaveResult.NotSaved(problem)
        }
        val edited = change(open.vault, clock())
        if (edited.vault !== open.vault) {
            saves++
            generation++
            stored = edited.vault
            state.value = VaultState.Unlocked(edited.vault, generation, null, open.olderCopy)
        }
        return SaveResult.Saved(edited.value)
    }

    override fun holdDraft(draft: Draft?) {
        heldDraft = draft
    }

    override suspend fun flushDraft() {
        flushes++
        val draft = heldDraft ?: return
        val open = state.value as? VaultState.Unlocked ?: return
        if (open.vault.draft != draft) edit { vault, _ -> Edited(vault.copy(draft = draft), Unit) }
    }

    override suspend fun sealBackup(proof: MasterProof): ByteArray = backupBytes.copyOf()

    override suspend fun openBackup(bytes: ByteArray, passwordBytes: ByteArray): BackupResult =
        backupResults.removeFirstOrNull() ?: BackupResult.NotOurs

    override suspend fun matchesMasterPassword(proof: MasterProof): Set<String> = matches

    /** How many times [olderCopyAnswered] was called. */
    var olderCopyAnswers = 0
        private set

    override fun olderCopyAnswered() {
        olderCopyAnswers++
    }
}
