package io.github.dilshodbekmx.passwordvault.lock.gate

import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.CurrentVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.GenerationInfo
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PasswordOpen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultOpen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultPort
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultRef
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultScan
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultWrite
import io.github.dilshodbekmx.passwordvault.core.model.Draft
import io.github.dilshodbekmx.passwordvault.core.model.Edited
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.data.BackupResult
import io.github.dilshodbekmx.passwordvault.data.SaveProblem
import io.github.dilshodbekmx.passwordvault.data.SaveResult
import io.github.dilshodbekmx.passwordvault.data.VaultRepository
import io.github.dilshodbekmx.passwordvault.data.VaultState
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The vault of the tests of the gate and the session: one object that is both the lock's
 * [VaultPort] and the screens' [VaultRepository], as `DefaultVaultRepository` (build plan 3.1) is,
 * kept in memory. It holds one vault with one master password (its `MasterPasswordBytes`), opens it
 * with those bytes only, and records in [log], in order, every call that the order of
 * architecture.md §5.5 is about: "flushDraft", "draft written" (the held draft went into the vault),
 * "save" (any other edit), "VaultPort.lock", and the lock's "vaults", "createVault",
 * "openWithPassword" and "checkPassword".
 *
 * Scripted with [scanFails], [current] and [aside] (before a vault is made here), [opens] and
 * [checks] (the next answers, in place of the comparison), [createAnswer] and [createFails],
 * [flushFails], and the holds: while [hold], [checkHold], [createHold] or [flushHold] is set and not complete,
 * [openWithPassword], [checkPassword], [createVault] or [flushDraft] waits for it, as if Argon2id or a save were
 * still running. Single-threaded, like the main thread it stands for.
 */
class InMemoryVault(private val clock: Clock) : VaultPort, VaultRepository {

    /** The calls, in order. */
    val log = mutableListOf<String>()

    /** What [vaults] says while no vault was made or [put] here. */
    var current: CurrentVault = CurrentVault.NoVault

    var aside: List<AsideVault> = emptyList()

    /** [vaults] throws `IOException`: the directory cannot be listed. */
    var scanFails = false

    /** The next answers of [openWithPassword], used before the comparison. */
    val opens = ArrayDeque<PasswordOpen>()

    /** The next answers of [checkPassword], used before the comparison. */
    val checks = ArrayDeque<Boolean?>()

    /** When set, the answer of [createVault]; nothing is made. */
    var createAnswer: VaultWrite? = null

    /** When set, [createVault] throws it (KdfUnavailable: not enough memory). */
    var createFails: Exception? = null

    /** While set and not complete, [openWithPassword] waits for it (Argon2id is running). */
    var hold: CompletableDeferred<Unit>? = null

    /** While set and not complete, [createVault] waits for it (the vault is being written and proven). */
    var createHold: CompletableDeferred<Unit>? = null

    /** While set and not complete, [checkPassword] waits for it (Argon2id is running). */
    var checkHold: CompletableDeferred<Unit>? = null

    /** While set and not complete, [flushDraft] waits for it (the draft's save is running). */
    var flushHold: CompletableDeferred<Unit>? = null

    /** When set, [flushDraft] throws it after logging the call: the draft could not be written. */
    var flushFails: Exception? = null

    /** The next unlock opens an older copy (architecture.md §4.8). */
    var olderCopy = false

    /** Copies of the bytes [createVault] received: the first typing and the second. */
    val created = mutableListOf<Pair<ByteArray, ByteArray>>()

    /** Copies of the bytes [openWithPassword] received. */
    val typed = mutableListOf<ByteArray>()

    /** The vault id [createVault] was given. */
    var createdId: String? = null
        private set

    private var password: ByteArray? = null
    private var vaultId: String? = null
    private var stored = Vault()
    private var generation = 0L
    private var heldDraft: Draft? = null

    override val state = MutableStateFlow<VaultState>(VaultState.Locked)

    /** A vault already on the phone: [id], with the master-password bytes [bytes]. */
    fun put(id: String, bytes: ByteArray, vault: Vault = Vault()) {
        vaultId = id
        password = bytes.copyOf()
        stored = vault
        generation = 1L
    }

    /** The vault as last written, whether open or not. */
    val vault: Vault get() = stored

    val isOpen: Boolean get() = state.value is VaultState.Unlocked

    // ---- VaultPort ---------------------------------------------------------------------------------

    override fun vaults(): VaultScan {
        log += "vaults"
        if (scanFails) throw IOException("cannot list")
        val id = vaultId
        val now = if (id == null) current else CurrentVault.Present(id, GenerationInfo(generation, VK_ID, CREATED_MS), setOf(VK_ID))
        return VaultScan(now, aside)
    }

    override suspend fun open(target: VaultRef, keys: Map<String, ByteArray>): VaultOpen = unsupported()

    override suspend fun openWithPassword(target: VaultRef, passwordBytes: ByteArray): PasswordOpen {
        log += "openWithPassword"
        typed += passwordBytes.copyOf()
        hold?.await()
        val scripted = opens.removeFirstOrNull()
        val result = scripted
            ?: if (password != null && passwordBytes.contentEquals(password)) {
                PasswordOpen.Opened(vaultId!!, generation, VK_ID, CREATED_MS, olderCopy)
            } else {
                PasswordOpen.Wrong
            }
        if (result is PasswordOpen.Opened) state.value = VaultState.Unlocked(stored, generation, null, result.olderCopy)
        return result
    }

    override suspend fun checkPassword(passwordBytes: ByteArray): Boolean? {
        log += "checkPassword"
        checkHold?.await()
        if (checks.isNotEmpty()) return checks.removeFirst()
        return password != null && passwordBytes.contentEquals(password)
    }

    override suspend fun <T> withVaultKey(use: (vkId: String, vk: ByteArray) -> T): T = unsupported()

    override suspend fun lock() {
        log += "VaultPort.lock"
        heldDraft = null
        state.value = VaultState.Locked
    }

    override suspend fun dropAside(): Unit = unsupported()

    override suspend fun createVault(vaultId: String, first: ByteArray, again: ByteArray, from: Vault?, putCurrentAside: Boolean): VaultWrite {
        log += "createVault"
        created += first.copyOf() to again.copyOf()
        createdId = vaultId
        createHold?.await()
        createFails?.let { throw it }
        createAnswer?.let { return it }
        if (!first.contentEquals(again)) return VaultWrite.Quarantined // the proof with the second typing failed
        put(vaultId, first, from ?: Vault())
        state.value = VaultState.Unlocked(stored, generation, null, false)
        return VaultWrite.Done(vaultId, VK_ID, generation, CREATED_MS)
    }

    override suspend fun replaceVault(newVaultId: String, passwordBytes: ByteArray): VaultWrite = unsupported()

    override suspend fun newKey(): Pair<String, ByteArray> = unsupported()

    override suspend fun changePassword(first: ByteArray, again: ByteArray, vkId: String, vk: ByteArray): VaultWrite = unsupported()

    override suspend fun mergeAside(vaultId: String): VaultWrite = unsupported()

    override suspend fun swapIn(vaultId: String): Boolean = unsupported()

    override suspend fun putAside(): Boolean = unsupported()

    override suspend fun putNewerAside(generation: Long): Boolean = unsupported()

    override suspend fun deleteAside(vaultId: String): Boolean = unsupported()

    override suspend fun retireOldKeys(): Set<String> = unsupported()

    // ---- VaultRepository ---------------------------------------------------------------------------

    override suspend fun <T> edit(change: (Vault, nowMs: Long) -> Edited<T>): SaveResult<T> {
        val open = state.value as? VaultState.Unlocked ?: return SaveResult.NotSaved(SaveProblem.FAILED)
        val edited = change(open.vault, clock.wallMs())
        if (edited.vault !== open.vault) {
            stored = edited.vault
            generation++
            log += if (edited.vault.draft != open.vault.draft) "draft written" else "save"
            state.value = VaultState.Unlocked(stored, generation, null, open.olderCopy)
        }
        return SaveResult.Saved(edited.value)
    }

    override fun holdDraft(draft: Draft?) {
        heldDraft = draft
    }

    override suspend fun flushDraft() {
        log += "flushDraft"
        flushHold?.await()
        flushFails?.let { throw it }
        val draft = heldDraft ?: return
        val open = state.value as? VaultState.Unlocked ?: return
        if (open.vault.draft != draft) edit { vault, _ -> Edited(vault.copy(draft = draft), Unit) }
    }

    override suspend fun sealBackup(proof: MasterProof): ByteArray = unsupported()

    override suspend fun openBackup(bytes: ByteArray, passwordBytes: ByteArray): BackupResult = unsupported()

    override suspend fun matchesMasterPassword(proof: MasterProof): Set<String> = unsupported()

    override fun olderCopyAnswered() = Unit // this vault opens no older copy

    private fun unsupported(): Nothing = throw UnsupportedOperationException("not used by the gate of build plan 3.2")

    companion object {
        const val VK_ID = "00000000-0000-4000-8000-0000000000b2"
        const val CREATED_MS = 1_791_244_800_000L
    }
}
