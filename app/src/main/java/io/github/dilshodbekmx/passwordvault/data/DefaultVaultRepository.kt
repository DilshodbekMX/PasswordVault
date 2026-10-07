package io.github.dilshodbekmx.passwordvault.data

import io.github.dilshodbekmx.passwordvault.core.crypto.KdfUnavailable
import io.github.dilshodbekmx.passwordvault.core.crypto.MasterKdf
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PasswordOpen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultOpen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultPort
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultScan
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultWrite
import io.github.dilshodbekmx.passwordvault.core.lock.guard.Credit
import io.github.dilshodbekmx.passwordvault.core.model.Draft
import io.github.dilshodbekmx.passwordvault.core.model.EditTexts
import io.github.dilshodbekmx.passwordvault.core.model.Edited
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import io.github.dilshodbekmx.passwordvault.core.storage.CommitResult
import io.github.dilshodbekmx.passwordvault.core.storage.OpenResult
import io.github.dilshodbekmx.passwordvault.core.storage.OpenedVault
import io.github.dilshodbekmx.passwordvault.core.storage.PasswordResult
import io.github.dilshodbekmx.passwordvault.core.storage.SlotRef
import io.github.dilshodbekmx.passwordvault.core.storage.StageResult
import io.github.dilshodbekmx.passwordvault.core.storage.Staged
import io.github.dilshodbekmx.passwordvault.core.storage.VaultStorage
import io.github.dilshodbekmx.passwordvault.core.strength.SecurityCheck
import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.core.util.RandomSource
import io.github.dilshodbekmx.passwordvault.core.vaultfile.BodyResult
import io.github.dilshodbekmx.passwordvault.core.vaultfile.FileCheck
import io.github.dilshodbekmx.passwordvault.core.vaultfile.PasswordSlot
import io.github.dilshodbekmx.passwordvault.core.vaultfile.VaultCodec
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.settings.AppSettings
import io.github.dilshodbekmx.passwordvault.settings.PurgeArm
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultRef as LockVaultRef
import io.github.dilshodbekmx.passwordvault.core.storage.VaultRef as StoreVaultRef

/**
 * The one owner of the open vault (architecture.md §1 item 3, §3.5, §5.1; build plan 3.1): the
 * screens' [VaultRepository] and the lock's [VaultPort] in one object, over the generation store
 * ([storage], `VaultStore`) and the container ([codec]).
 *
 * **What it holds.** From an unlock to a lock: the open vault ([OpenedVault], whose VK only the
 * store reaches), the slot it opened with, the form's held draft, and, apart from it, at most one
 * vault put aside that was opened in place (§3.4). A lock zeroes both keys and drops every one of
 * these; nothing of the vault stays in this object (§5.5 step 3).
 *
 * **The vault mutex** (§5.2 item 1) is held for every use of VK and every call of [storage], except
 * [vaults], which cannot suspend: `scan` takes the store's own lock and reads nothing of the open
 * vault. Order: the lock's mutex, then this one, then the KDF gate of [kdf]; this object never calls
 * the lock. Every call that changes the session runs to its end, also when its caller is
 * cancelled, so that a write is never cut in half (§5.3); but an open whose caller was cancelled
 * while it ran opens nothing (lock.md I11).
 *
 * **Publishing.** [state] shows a change only once the generation that holds it is on disk and its
 * directory synced (§4.9 step 10); a save that was not durable leaves the old vault on show with its
 * [SaveProblem]. Housekeeping (§4.10) runs after a durable save, under the mutex, after the caller
 * has its answer.
 *
 * **Automatic writes** (§5.5): the purge of Recently deleted (D-24), the master-password matches
 * (D-9b), the draft flush, and housekeeping. After an unlock that opened an older copy (its newest
 * generation could not be used, §4.8) none of them runs until [olderCopyAnswered]: the answer to the
 * older-copy notice of lock.md §13.4 item 1. The user's own saves go on.
 *
 * **The purge of D-24.** At every unlock of the current vault: with no arm, entries deleted 30 days
 * or more before the wall clock arm it (`PurgeArm` in [settings]: the vault, the wall time, a
 * `Credit` started now); an arm of another vault is cleared (a switch of vault disarms); an arm of
 * this vault counts the phone-on time since it was last seen (lock.md §6.9), and once 24 hours are
 * counted removes the entries that were due when it was armed, then disarms, or arms again when
 * others are due now. A lock also counts the time of an arm. Settings are written best effort.
 *
 * **Keys and bytes.** Every KEK is derived with [kdf]; `KdfUnavailable` is "not enough memory",
 * never "wrong". A call that writes a new slot proves it from disk with a KEK derived afresh from
 * the second typing and the salt read back (§4.9 step 7). Byte arrays passed in belong to the
 * caller; arrays made here are wiped. [VaultPort.createVault], [VaultPort.replaceVault] and
 * [VaultPort.changePassword] throw `KdfUnavailable` before anything is written when Argon2id cannot
 * run, because `VaultWrite` has no case for it.
 *
 * @param settings the app's settings, reached only when the purge needs them (off the main thread).
 * @param texts the texts that [mergeAside] stores ("(from backup)").
 * @param scope where saves, automatic writes and housekeeping run (`AppGraph.appScope`).
 */
class DefaultVaultRepository internal constructor(
    private val storage: VaultStorage,
    private val codec: VaultCodec,
    private val kdf: MasterKdf,
    private val random: RandomSource,
    private val clock: Clock,
    private val settings: () -> AppSettings,
    private val texts: EditTexts,
    private val headers: GenerationHeaders,
    private val scope: CoroutineScope,
    private val dispatchers: AppDispatchers,
) : VaultRepository, VaultPort {

    /**
     * The open current vault. Its fields change under [mutex]. [id] tells one session from the next:
     * work that runs later in the background holds the id, never the session, so that after a lock
     * nothing of the vault is reachable from it.
     */
    private class Session(val id: Long, opened: OpenedVault, val olderCopy: Boolean, slot: SlotRef?) {
        @Volatile var opened: OpenedVault = opened

        /** Automatic writes wait for the older-copy notice (architecture.md §5.5). */
        @Volatile var held: Boolean = olderCopy

        /** The open vault's own password slot, for [checkPassword]; null until it is known. */
        @Volatile var slot: SlotRef? = slot
    }

    /** A vault put aside and opened in place (§3.4), kept apart from the current one. */
    private class AsideOpen(val vaultId: String, val opened: OpenedVault, val olderCopy: Boolean)

    /** What one save under the mutex came to; [saved] is the session when a durable save was made. */
    private class Committed<T>(val result: SaveResult<T>, val saved: Session?)

    private val mutex = Mutex()
    private val published = MutableStateFlow<VaultState>(VaultState.Locked)
    override val state: StateFlow<VaultState> = published.asStateFlow()

    @Volatile private var session: Session? = null

    private var sessions = 0L // under mutex: the id of the last session

    @Volatile private var aside: AsideOpen? = null

    private val draftLock = Any()
    private var heldDraft: Draft? = null // guarded by draftLock

    private val jobsLock = Any()
    private var automatic: Job? = null // guarded by jobsLock

    // ---------------------------------------------------------------------------------------------
    // VaultRepository: the screens

    override suspend fun <T> edit(change: (Vault, nowMs: Long) -> Edited<T>): SaveResult<T> {
        val done = CompletableDeferred<SaveResult<T>>()
        val job = scope.launch(dispatchers.io) {
            withContext(NonCancellable) {
                mutex.withLock {
                    val committed = try {
                        commitLocked(session, change, quiet = false)
                    } catch (e: Throwable) {
                        done.completeExceptionally(e)
                        return@withLock
                    }
                    done.complete(committed.result)
                    committed.saved?.let(::housekeepingLocked)
                }
            }
        }
        job.invokeOnCompletion { cause -> if (!done.isCompleted) done.completeExceptionally(cause ?: IllegalStateException("the save did not run")) }
        return done.await()
    }

    override fun holdDraft(draft: Draft?) {
        synchronized(draftLock) { heldDraft = if (session != null) draft else null }
    }

    /**
     * Writes the held draft as the vault's draft when it differs from the stored one (architecture.md
     * §5.5 step 2): skipped when nothing is held, while automatic writes are held, or when the save
     * cannot start within [FLUSH_WAIT_MS] because another one is running. Once started it finishes.
     */
    override suspend fun flushDraft() {
        val s = session ?: return
        if (s.held || synchronized(draftLock) { heldDraft } == null) return
        val owner = Any()
        withTimeoutOrNull(FLUSH_WAIT_MS) { mutex.lock(owner) }
        if (!mutex.holdsLock(owner)) return
        try {
            withContext(dispatchers.io + NonCancellable) {
                val draft = synchronized(draftLock) { heldDraft }
                if (session === s && !s.held && draft != null) {
                    commitLocked(s, VaultEdits.setDraft(draft), quiet = true).saved?.let(::housekeepingLocked)
                }
            }
        } finally {
            mutex.unlock(owner)
        }
    }

    override suspend fun sealBackup(proof: MasterProof): ByteArray {
        val opened = underMutex { session?.opened } ?: throw IllegalStateException("no vault is open")
        check(opened.vaultId == proof.vaultId) { "the proof is for another vault" }
        return withContext(dispatchers.default) {
            val vk = random.bytes(KEY_SIZE)
            try {
                val vkId = random.uuid()
                val salt = random.bytes(SALT_SIZE)
                val password = proof.useBytes { it.copyOf() }
                val kek = try {
                    kdf.kek(password, salt)
                } finally {
                    Bytes.wipe(password)
                }
                val slot = try {
                    codec.wrap(vk, kek, salt)
                } finally {
                    Bytes.wipe(kek)
                }
                val sealed = codec.seal(opened.vault, vk, opened.vaultId, vkId, opened.generation, slot)
                // The check in memory of §4.9 step 4, for a file that leaves the phone.
                val file = (codec.check(sealed) as? FileCheck.Ok)?.file
                check(sealed.size <= GenerationHeaders.CAP && file != null && file.header.vkId == vkId) { "the backup did not check" }
                val body = codec.open(file, vk)
                check(body is BodyResult.Opened && body.vault == opened.vault) { "the backup did not check" }
                sealed
            } finally {
                Bytes.wipe(vk)
            }
        }
    }

    override suspend fun openBackup(bytes: ByteArray, passwordBytes: ByteArray): BackupResult = withContext(dispatchers.default) {
        openBackupFile(bytes, passwordBytes)
    }

    private suspend fun openBackupFile(bytes: ByteArray, passwordBytes: ByteArray): BackupResult {
        val file = when (val check = codec.check(bytes)) {
            is FileCheck.Ok -> check.file
            FileCheck.NotOurs -> return BackupResult.NotOurs
            is FileCheck.Newer -> return BackupResult.Newer
            is FileCheck.Damaged -> return BackupResult.Damaged
        }
        val slot = file.header.slot
        val kek = try {
            kdf.kek(passwordBytes, slot.salt)
        } catch (e: KdfUnavailable) {
            return BackupResult.NoMemory
        }
        val vk = try {
            codec.unwrap(slot, kek)
        } finally {
            Bytes.wipe(kek)
        } ?: return if (file.trailerOk) BackupResult.Wrong else BackupResult.WrongOrDamaged
        try {
            return when (val body = codec.open(file, vk)) {
                is BodyResult.Opened -> BackupResult.Opened(body.vault, file.header.createdMs)
                is BodyResult.Newer -> BackupResult.Newer
                else -> BackupResult.Damaged
            }
        } finally {
            Bytes.wipe(vk)
        }
    }

    /**
     * The entries whose password is the master password of [proof] (D-9b), stored in the vault with
     * `VaultEdits.setMasterPasswordMatches`, an automatic write. Empty when no vault is open, the
     * proof is for another vault, or it was dropped (its flow ended). While automatic writes are held
     * the ids are worked out and nothing is written.
     */
    override suspend fun matchesMasterPassword(proof: MasterProof): Set<String> {
        val s = session ?: return emptySet()
        if (s.opened.vaultId != proof.vaultId) return emptySet()
        val password = try {
            proof.useBytes { it.copyOf() }
        } catch (e: IllegalStateException) {
            return emptySet()
        }
        try {
            var ids: Set<String>? = null
            val saved = automaticEdit(s.id) { vault, nowMs ->
                val found = SecurityCheck.matchesMasterPassword(vault, password)
                ids = found
                VaultEdits.setMasterPasswordMatches(found)(vault, nowMs)
            }
            val found = ids
            if (saved != null && found != null) return found
            // Skipped (held, or locked meanwhile): worked out on the vault as it was, and not written.
            val vault = s.opened.vault
            return withContext(dispatchers.default) { SecurityCheck.matchesMasterPassword(vault, password) }
        } finally {
            Bytes.wipe(password)
        }
    }

    /**
     * The older-copy notice was answered (lock.md §13.4 item 1): automatic writes and housekeeping
     * may run again for this session, and the work of its unlock (the purge) runs now. It does
     * nothing when no older copy is open. Not part of `VaultRepository` yet: package 3.1 asked for it
     * (interface request), so that the lock manager can call it when it receives the answer.
     */
    fun olderCopyAnswered() {
        val s = session ?: return
        if (!s.held) return
        s.held = false
        startAutomatic(s)
    }

    // ---------------------------------------------------------------------------------------------
    // VaultPort: the lock

    /** §4.8 `scan`, mapped onto the lock's types. It reads files: call it off the main thread. */
    override fun vaults(): VaultScan = PortTypes.scanOf(storage.scan())

    override suspend fun open(target: LockVaultRef, keys: Map<String, ByteArray>): VaultOpen {
        val caller = currentCoroutineContext()[Job]
        return underMutex { openLocked(target, keys, caller) }
    }

    private fun openLocked(target: LockVaultRef, keys: Map<String, ByteArray>, caller: Job?): VaultOpen =
        when (val result = storage.open(PortTypes.refOf(target), keys)) {
            is OpenResult.Opened -> {
                val opened = result.opened
                if (caller?.isCancelled == true) return dropLate(opened)
                val created = take(target, opened, slot = null)
                VaultOpen.Opened(opened.vaultId, opened.generation, opened.vkId, created, olderCopyOf(opened))
            }
            is OpenResult.NeedsOtherKey -> VaultOpen.NeedsOtherKey(result.generation, result.createdMs)
            OpenResult.Damaged -> VaultOpen.Damaged
            OpenResult.Newer -> VaultOpen.Newer
            OpenResult.Unreadable -> VaultOpen.Unreadable
        }

    override suspend fun openWithPassword(target: LockVaultRef, passwordBytes: ByteArray): PasswordOpen {
        val caller = currentCoroutineContext()[Job]
        return underMutex { openWithPasswordLocked(target, passwordBytes, caller) }
    }

    /** §4.8 "Opening with the master password": one derivation per distinct slot, newest first, and "wrong" only when none opened [V-S3]. */
    private suspend fun openWithPasswordLocked(target: LockVaultRef, passwordBytes: ByteArray, caller: Job?): PasswordOpen {
        val ref = PortTypes.refOf(target)
        val slots = storage.slots(ref)
        if (slots.isEmpty()) return noSlot(ref)
        for (slot in slots) {
            val kek = try {
                kdf.kek(passwordBytes, slot.salt)
            } catch (e: KdfUnavailable) {
                return PasswordOpen.NoMemory
            }
            val result = try {
                storage.openWithSlot(ref, slot, kek)
            } finally {
                Bytes.wipe(kek)
            }
            when (result) {
                PasswordResult.SlotClosed -> continue
                is PasswordResult.Opened -> {
                    val opened = result.opened
                    if (caller?.isCancelled == true) {
                        dropLate(opened)
                        return PasswordOpen.Unreadable
                    }
                    val created = take(target, opened, slot)
                    return PasswordOpen.Opened(opened.vaultId, opened.generation, opened.vkId, created, olderCopyOf(opened))
                }
                is PasswordResult.OlderPassword -> return PasswordOpen.OlderPassword(result.changedAtMs, generationOpenedBy(ref, slot))
                PasswordResult.Damaged -> return PasswordOpen.Damaged
                PasswordResult.Newer -> return PasswordOpen.Newer
                PasswordResult.Unreadable -> return PasswordOpen.Unreadable
            }
        }
        return PasswordOpen.Wrong
    }

    /**
     * The caller of an open was cancelled while it ran (its visit of the lock screen ended): a result
     * that comes after its visit opens nothing (lock.md I11). The key is zeroed and nothing is shown;
     * the answer is never delivered, because the cancelled caller does not resume with it.
     */
    private fun dropLate(opened: OpenedVault): VaultOpen {
        wipe(opened)
        return VaultOpen.Unreadable
    }

    /** No slot to try: why no generation can be used, which is never "wrong". */
    private fun noSlot(ref: StoreVaultRef): PasswordOpen = when (storage.open(ref, emptyMap())) {
        OpenResult.Newer -> PasswordOpen.Newer
        OpenResult.Damaged -> PasswordOpen.Damaged
        else -> PasswordOpen.Unreadable // listing failed now, or a generation appeared meanwhile: try again
    }

    /**
     * The newest generation the typed password opens (lock.md F16b step 2): the walk that found the
     * older password marked every generation of its key above that one as damaged, so the store now
     * lists this slot with that generation.
     */
    private fun generationOpenedBy(ref: StoreVaultRef, slot: SlotRef): Long =
        storage.slots(ref).firstOrNull { it.vkId == slot.vkId && it.salt.contentEquals(slot.salt) }?.generation ?: slot.generation

    /**
     * Whether [passwordBytes] open the open vault's own slot (lock.md §6.8): one derivation outside
     * the mutex, then the slot is tried and the generation opened once more, as at an unlock, and
     * dropped. Null: not enough memory, or the slot cannot be read now (never "wrong").
     */
    override suspend fun checkPassword(passwordBytes: ByteArray): Boolean? {
        val slot = underMutex {
            val s = session ?: throw IllegalStateException("no vault is open")
            s.slot ?: slotOf(StoreVaultRef.Current, s.opened).also { s.slot = it }
        } ?: return null
        val kek = try {
            kdf.kek(passwordBytes, slot.salt)
        } catch (e: KdfUnavailable) {
            return null
        }
        try {
            return underMutex {
                when (val result = storage.openWithSlot(StoreVaultRef.Current, slot, kek)) {
                    PasswordResult.SlotClosed -> false
                    is PasswordResult.Opened -> {
                        wipe(result.opened)
                        true
                    }
                    else -> true // the slot opened: the password is right
                }
            }
        } finally {
            Bytes.wipe(kek)
        }
    }

    override suspend fun <T> withVaultKey(use: (vkId: String, vk: ByteArray) -> T): T = underMutex {
        val opened = session?.opened ?: throw IllegalStateException("no vault is open")
        storage.useKey(opened) { vk ->
            val copy = vk.copyOf()
            try {
                use(opened.vkId, copy)
            } finally {
                Bytes.wipe(copy)
            }
        }
    }

    override suspend fun lock() {
        val wasOpen = underMutex { endAll() }
        if (wasOpen) countPurgeTime()
    }

    override suspend fun createVault(vaultId: String, first: ByteArray, again: ByteArray, from: Vault?, putCurrentAside: Boolean): VaultWrite =
        underMutex {
            val vault = from?.let(VaultEdits::forRestore) ?: Vault()
            writeNewVault(vault, vaultId, first, again, putCurrentAside, keepDraft = false)
        }

    override suspend fun replaceVault(newVaultId: String, passwordBytes: ByteArray): VaultWrite = underMutex {
        val s = session
        if (s == null) {
            VaultWrite.NothingChanged
        } else {
            writeNewVault(s.opened.vault, newVaultId, passwordBytes, passwordBytes, putCurrentAside = true, keepDraft = true)
        }
    }

    override suspend fun newKey(): Pair<String, ByteArray> = random.uuid() to random.bytes(KEY_SIZE)

    override suspend fun changePassword(first: ByteArray, again: ByteArray, vkId: String, vk: ByteArray): VaultWrite =
        underMutex { changePasswordLocked(first, again, vkId, vk) }

    private suspend fun changePasswordLocked(first: ByteArray, again: ByteArray, vkId: String, vk: ByteArray): VaultWrite {
        val s = session ?: return VaultWrite.NothingChanged
        val slot = newSlot(vk, first, random.bytes(SALT_SIZE))
        val staged = when (val result = storage.stageRekey(s.opened, s.opened.vault, vk, vkId, slot)) {
            is StageResult.Staged -> result.staged
            StageResult.Failed, StageResult.TooLarge -> return VaultWrite.NothingChanged
        }
        if (!prove(staged, again)) return VaultWrite.Quarantined
        return when (val result = storage.publish(staged, putCurrentAside = false)) {
            is CommitResult.Saved -> {
                val old = s.opened
                s.opened = result.opened
                s.slot = slotOf(StoreVaultRef.Current, result.opened)
                wipe(old)
                publish(s, null)
                housekeepingLocked(s)
                done(result.opened)
            }
            // In place, but perhaps not durable: "Saving may not have finished" (lock.md F8 step 6).
            CommitResult.NotDurable -> VaultWrite.Unclear
            else -> VaultWrite.NothingChanged
        }
    }

    override suspend fun mergeAside(vaultId: String): VaultWrite = underMutex { mergeAsideLocked(vaultId) }

    private fun mergeAsideLocked(vaultId: String): VaultWrite {
        val s = session ?: return VaultWrite.NothingChanged
        val a = aside?.takeIf { it.vaultId == vaultId } ?: return VaultWrite.NothingChanged
        val committed = commitLocked(s, VaultEdits.mergeEntries(a.opened.vault.entries, texts, random), quiet = false)
        if (committed.result !is SaveResult.Saved) return VaultWrite.NothingChanged
        aside = null
        wipe(a.opened)
        committed.saved?.let(::housekeepingLocked)
        return done(s.opened)
    }

    override suspend fun swapIn(vaultId: String): Boolean = underMutex {
        val a = aside?.takeIf { it.vaultId == vaultId }
        if (a == null || !storage.swapIn(vaultId)) {
            false
        } else {
            aside = null
            startCurrent(a.opened, a.olderCopy, slotOf(StoreVaultRef.Current, a.opened), keepDraft = false)
            true
        }
    }

    override suspend fun putAside(): Boolean {
        var ended = false
        val done = underMutex {
            val ok = storage.putAside()
            if (ok && session != null) {
                endCurrent()
                ended = true
            }
            ok
        }
        if (ended) countPurgeTime()
        return done
    }

    override suspend fun putNewerAside(generation: Long): Boolean = underMutex { storage.putNewerAside(generation) }

    override suspend fun deleteAside(vaultId: String): Boolean = underMutex {
        aside?.takeIf { it.vaultId == vaultId }?.let { held ->
            aside = null
            wipe(held.opened)
        }
        try {
            storage.deleteAside(vaultId)
            true
        } catch (e: IOException) {
            false
        }
    }

    override suspend fun retireOldKeys(): Set<String> = underMutex {
        val opened = session?.opened ?: throw IllegalStateException("no vault is open")
        storage.retireOldKeys(opened)
    }

    // ---------------------------------------------------------------------------------------------
    // Sessions

    private suspend fun <R> underMutex(block: suspend () -> R): R = withContext(dispatchers.io + NonCancellable) { mutex.withLock { block() } }

    private fun olderCopyOf(opened: OpenedVault): Boolean = opened.newestOnDisk > opened.generation

    /**
     * A vault opened from disk: the current one becomes the session (and the vault is shown), one
     * put aside is kept apart. Returns the `created` time of the generation that opened.
     */
    private fun take(target: LockVaultRef, opened: OpenedVault, slot: SlotRef?): Long {
        val created = headers.createdMs(PortTypes.refOf(target), opened.vaultId, opened.vkId, opened.generation) ?: UNKNOWN_CREATED
        when (target) {
            LockVaultRef.Current -> startCurrent(opened, olderCopyOf(opened), slot ?: slotOf(StoreVaultRef.Current, opened), keepDraft = false)
            is LockVaultRef.Aside -> {
                aside?.let { wipe(it.opened) }
                aside = AsideOpen(target.vaultId, opened, olderCopyOf(opened))
            }
        }
        return created
    }

    /**
     * [opened] becomes the open current vault: the one before it (now put aside, or an earlier
     * session) has its key zeroed, the new one is shown, and the work of an unlock starts. A held
     * draft stays only when the session goes on with the same accounts ([keepDraft], lock.md F11).
     */
    private fun startCurrent(opened: OpenedVault, olderCopy: Boolean, slot: SlotRef?, keepDraft: Boolean) {
        cancelAutomatic()
        session?.let { wipe(it.opened) }
        if (!keepDraft) synchronized(draftLock) { heldDraft = null }
        val s = Session(++sessions, opened, olderCopy, slot)
        session = s
        publish(s, null)
        startAutomatic(s)
    }

    /** Ends every session: both keys zeroed, the draft and the vaults dropped, [VaultState.Locked] shown. True when one was open. */
    private fun endAll(): Boolean {
        val held = aside
        aside = null
        held?.let { wipe(it.opened) }
        return endCurrent()
    }

    private fun endCurrent(): Boolean {
        cancelAutomatic()
        val s = session
        session = null
        synchronized(draftLock) { heldDraft = null }
        s?.let { wipe(it.opened) }
        published.value = VaultState.Locked
        return s != null
    }

    private fun publish(s: Session, problem: SaveProblem?) {
        val opened = s.opened
        published.value = VaultState.Unlocked(opened.vault, opened.generation, problem, s.olderCopy)
    }

    /** Zeroes the VK of [opened]: the store hands its own array to [VaultStorage.useKey]. */
    private fun wipe(opened: OpenedVault) {
        storage.useKey(opened) { vk -> Bytes.wipe(vk) }
    }

    /** The password slot of [opened]'s key among the kept generations of [ref], or null when it cannot be read now. */
    private fun slotOf(ref: StoreVaultRef, opened: OpenedVault): SlotRef? = storage.slots(ref).firstOrNull { it.vkId == opened.vkId }

    // ---------------------------------------------------------------------------------------------
    // Writing

    /**
     * One ordinary save under the mutex (§4.9): [change] on the open vault at the wall-clock time,
     * nothing written when it gives the vault back, else a new generation that is shown only when it
     * is durable. A [quiet] save (an automatic one) that fails leaves the state as it was.
     */
    private fun <T> commitLocked(s: Session?, change: (Vault, Long) -> Edited<T>, quiet: Boolean): Committed<T> {
        if (s == null) return Committed(SaveResult.NotSaved(SaveProblem.FAILED), null)
        val before = s.opened.vault
        val edited = change(before, clock.wallMs())
        if (edited.vault === before) return Committed(SaveResult.Saved(edited.value), null)
        val problem = when (val result = storage.commit(s.opened, edited.vault)) {
            is CommitResult.Saved -> {
                s.opened = result.opened
                publish(s, null)
                return Committed(SaveResult.Saved(edited.value), s)
            }
            CommitResult.NotDurable -> SaveProblem.NOT_DURABLE
            CommitResult.Failed -> SaveProblem.FAILED
            CommitResult.ReadOnly -> SaveProblem.READ_ONLY
            CommitResult.TooLarge -> SaveProblem.TOO_LARGE
        }
        if (!quiet) publish(s, problem)
        return Committed(SaveResult.NotSaved(problem), null)
    }

    /** §4.10 when it is due, under the mutex, never while automatic writes are held (§5.5). */
    private fun housekeepingLocked(s: Session) {
        if (s.held || session !== s) return
        try {
            storage.housekeeping(s.opened)
        } catch (e: IOException) {
            // It stays due, and runs after a later save.
        }
    }

    /** An automatic write (§5.5) on the session [sessionId]: null when it was skipped (locked since, or held). */
    private suspend fun <T> automaticEdit(sessionId: Long, change: (Vault, Long) -> Edited<T>): SaveResult<T>? = underMutex {
        val s = liveSession(sessionId)
        if (s == null) {
            null
        } else {
            val committed = commitLocked(s, change, quiet = true)
            committed.saved?.let(::housekeepingLocked)
            committed.result
        }
    }

    /** Under the mutex: the session [sessionId] while it is open and its automatic writes are not held. */
    private fun liveSession(sessionId: Long): Session? = session?.takeIf { it.id == sessionId && !it.held }

    /**
     * A new vault [vaultId] holding [vault] (lock.md F1, F11 step 16, F13, F17 step 3; §4.9): a new
     * VK, `vk_id`, salt and slot from [first]; staged in `new-<vaultId>/`, proven from disk with
     * [again], published (with the current vault put aside when [putCurrentAside]); then it is the
     * open vault.
     */
    private suspend fun writeNewVault(
        vault: Vault,
        vaultId: String,
        first: ByteArray,
        again: ByteArray,
        putCurrentAside: Boolean,
        keepDraft: Boolean,
    ): VaultWrite {
        val vk = random.bytes(KEY_SIZE)
        try {
            val vkId = random.uuid()
            val slot = newSlot(vk, first, random.bytes(SALT_SIZE))
            val staged = when (val result = storage.stageVault(vault, vaultId, vk, vkId, slot)) {
                is StageResult.Staged -> result.staged
                StageResult.Failed, StageResult.TooLarge -> return VaultWrite.NothingChanged
            }
            if (!prove(staged, again)) return VaultWrite.Quarantined
            return when (val result = storage.publish(staged, putCurrentAside)) {
                is CommitResult.Saved -> {
                    startCurrent(result.opened, olderCopy = false, slot = slotOf(StoreVaultRef.Current, result.opened), keepDraft = keepDraft)
                    session?.let(::housekeepingLocked)
                    done(result.opened)
                }
                CommitResult.NotDurable -> {
                    // The new vault is in place but perhaps not durable; the one that was open went aside.
                    if (putCurrentAside) endCurrent()
                    VaultWrite.Unclear
                }
                else -> VaultWrite.NothingChanged
            }
        } finally {
            Bytes.wipe(vk)
        }
    }

    /** A password slot for [vk] from [passwordBytes] and [salt] (§4.6). Throws `KdfUnavailable` before anything is written. */
    private suspend fun newSlot(vk: ByteArray, passwordBytes: ByteArray, salt: ByteArray): PasswordSlot {
        val kek = kdf.kek(passwordBytes, salt)
        try {
            return codec.wrap(vk, kek, salt)
        } finally {
            Bytes.wipe(kek)
        }
    }

    /**
     * §4.9 step 7: a KEK derived afresh from [again] and the salt read back from disk opens the
     * staged file. When it does not, the store has quarantined it, and the staged work is released;
     * when Argon2id cannot run, the staged work is discarded and `KdfUnavailable` goes on.
     */
    private suspend fun prove(staged: Staged, again: ByteArray): Boolean {
        val kek = try {
            kdf.kek(again, staged.slotSalt)
        } catch (e: KdfUnavailable) {
            storage.discard(staged)
            throw e
        }
        val proven = try {
            storage.prove(staged, kek)
        } finally {
            Bytes.wipe(kek)
        }
        if (!proven) storage.discard(staged)
        return proven
    }

    private fun done(opened: OpenedVault): VaultWrite.Done = VaultWrite.Done(
        opened.vaultId,
        opened.vkId,
        opened.generation,
        headers.createdMs(StoreVaultRef.Current, opened.vaultId, opened.vkId, opened.generation) ?: UNKNOWN_CREATED,
    )

    // ---------------------------------------------------------------------------------------------
    // The work of an unlock: the purge of Recently deleted (D-24)

    private fun startAutomatic(s: Session) {
        if (s.held) return
        val sessionId = s.id
        val job = scope.launch(dispatchers.io) { purgeAtUnlock(sessionId) }
        synchronized(jobsLock) {
            automatic?.cancel()
            automatic = job
        }
    }

    private fun cancelAutomatic() {
        synchronized(jobsLock) {
            automatic?.cancel()
            automatic = null
        }
    }

    private suspend fun purgeAtUnlock(sessionId: Long) {
        val settings = settings()
        val vaultId = underMutex { liveSession(sessionId)?.opened?.vaultId } ?: return
        var arm = settings.purgeArm.value
        if (arm != null && arm.vaultId != vaultId) {
            val other = arm
            settings.update { if (it.purgeArm == other) it.purgeArm = null } // a switch of vault disarms
            arm = null
        }
        if (arm == null) {
            armWhenDue(settings, sessionId, vaultId, expected = null)
            return
        }
        val credit = arm.credit.observe(clock.elapsedMs())
        if (!credit.reached(VaultEdits.PURGE_WAIT_MS)) {
            val seen = arm
            settings.update { if (it.purgeArm == seen) it.purgeArm = seen.copy(credit = credit) }
            return
        }
        when (automaticEdit(sessionId, VaultEdits.purge(arm.armedAtMs))) {
            is SaveResult.Saved -> armWhenDue(settings, sessionId, vaultId, expected = arm)
            else -> {
                val seen = arm
                settings.update { if (it.purgeArm == seen) it.purgeArm = seen.copy(credit = credit) }
            }
        }
    }

    /**
     * Arms the purge when entries of the open vault are due now, else leaves it disarmed; [expected]
     * is the arm it replaces. Nothing when the session ended or is held meanwhile.
     */
    private suspend fun armWhenDue(settings: AppSettings, sessionId: Long, vaultId: String, expected: PurgeArm?) {
        val now = clock.wallMs()
        val due = underMutex { liveSession(sessionId)?.let { VaultEdits.dueCount(it.opened.vault, now) > 0 } } ?: return
        val next = if (due) PurgeArm(vaultId, now, Credit.start(clock.elapsedMs())) else null
        if (next != expected) settings.update { if (it.purgeArm == expected) it.purgeArm = next }
    }

    /** At a lock: the arm counts the phone-on time since it was last seen (lock.md §6.9). Best effort. */
    private fun countPurgeTime() {
        scope.launch(dispatchers.io) {
            val settings = settings()
            val arm = settings.purgeArm.value ?: return@launch
            val credit = arm.credit.observe(clock.elapsedMs())
            if (credit != arm.credit) settings.update { if (it.purgeArm == arm) it.purgeArm = arm.copy(credit = credit) }
        }
    }

    internal companion object {
        /** How long [flushDraft] waits for a running save before it gives up (architecture.md §5.5 step 2). */
        const val FLUSH_WAIT_MS = 2_000L

        /** `created` when the header of a generation that was just opened or written cannot be read again now. */
        const val UNKNOWN_CREATED = 0L

        private const val KEY_SIZE = 32
        private const val SALT_SIZE = 16
    }
}
