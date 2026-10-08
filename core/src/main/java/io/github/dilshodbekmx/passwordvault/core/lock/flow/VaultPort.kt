package io.github.dilshodbekmx.passwordvault.core.lock.flow

import io.github.dilshodbekmx.passwordvault.core.model.Vault

/**
 * The lock's whole view of the vault (architecture.md §3.4). `DefaultVaultRepository` (package 3.1)
 * implements it; `FakeVaultPort` of :testing (package 3.10) records every write call and can stop
 * at every boundary (lock.md §15.1).
 *
 * `open` and `openWithPassword` on [VaultRef.Aside] open that vault in place and keep it, still
 * apart from the current one, until [swapIn], [mergeAside], a [deleteAside] that deleted it,
 * [dropAside] or a lock; only one vault put aside is held at a time. The lock checks the master password of a vault put aside under that vault's own
 * guard and records (lock.md §6.8). Byte arrays passed in belong to the caller, which wipes them.
 *
 * What a caller must know (architecture.md §3.4): [vaults] reads every live generation file, so it is
 * called off the main thread. [createVault], [replaceVault] and [changePassword] throw
 * `KdfUnavailable` (core.crypto) before anything is written when Argon2id cannot run; [VaultWrite]
 * has no case for it, and the lock answers "not enough memory". A writing call runs to its end when
 * its caller is cancelled, but `withContext` then drops its answer, so the lock runs [createVault],
 * [replaceVault], [changePassword], [mergeAside], [swapIn], [putAside], [putNewerAside] and
 * [deleteAside] in a `NonCancellable` context when it needs the answer (after [deleteAside] it
 * deletes that vault's lock directory and keys, lock.md F17 step 5). An [open] or [openWithPassword]
 * whose caller is cancelled opens nothing: the implementation undoes it (lock.md I11).
 */
interface VaultPort {
    /** The vaults on the phone, before anything is typed (lock.md §5.11 step 2, §16.3 "Storage"). */
    fun vaults(): VaultScan

    /** Opens [target] with a VK per `vk_id` ([keys]): the PIN, the fingerprint, the pending wrap of F11. */
    suspend fun open(target: VaultRef, keys: Map<String, ByteArray>): VaultOpen

    /**
     * Opens [target] with the master-password bytes (`MasterPasswordBytes.of`): one derivation per
     * distinct slot, and "wrong" only when none opened [V-S3].
     */
    suspend fun openWithPassword(target: VaultRef, passwordBytes: ByteArray): PasswordOpen

    /** Whether [passwordBytes] open the open vault's own slot; null: not enough memory. */
    suspend fun checkPassword(passwordBytes: ByteArray): Boolean?

    /** Runs [use] with the open vault's VK and its `vk_id`, to wrap VK for a PIN or a fingerprint. */
    suspend fun <T> withVaultKey(use: (vkId: String, vk: ByteArray) -> T): T

    /** Waits for a save that is running, then drops the session. Never writes. */
    suspend fun lock()

    /**
     * Drops only the vault put aside that is held open in place, with its key zeroed; the open
     * current vault stays as it is, and nothing happens when none is held. For a check in the open
     * app that opened such a vault and then neither merged nor deleted it (lock.md §6.8 step 8, I11;
     * F17 step 5). Never writes.
     */
    suspend fun dropAside()

    // The only calls that write vault data (lock.md I1): F1, F8, F11, F13, F16b, F17. Each proves a
    // new slot from disk before it publishes (architecture.md §4.9).

    /**
     * F1, F13, F17 step 3: a new vault [vaultId] from [from] (null: empty), with a new VK and a slot
     * from [first], proven with [again]; with [putCurrentAside] the current vault goes aside in the
     * same step.
     */
    suspend fun createVault(vaultId: String, first: ByteArray, again: ByteArray, from: Vault?, putCurrentAside: Boolean): VaultWrite

    /** F11 step 16: vault [newVaultId] made from the open one under [passwordBytes]; then the old one goes aside, whole. */
    suspend fun replaceVault(newVaultId: String, passwordBytes: ByteArray): VaultWrite

    /** F8 step 4: a new `vk_id` and VK; nothing is written. */
    suspend fun newKey(): Pair<String, ByteArray>

    /** F8 step 6: a generation under [vk] and [vkId] with a slot from [first], proven with [again]. */
    suspend fun changePassword(first: ByteArray, again: ByteArray, vkId: String, vk: ByteArray): VaultWrite

    /** F17 step 5 "Open": the accounts of the vault put aside and opened in place go into the current vault (architecture.md §4.11). */
    suspend fun mergeAside(vaultId: String): VaultWrite

    /** F16b step 4, F17 step 4: the vault put aside and opened in place becomes current; the current one goes aside. */
    suspend fun swapIn(vaultId: String): Boolean

    /** F17 step 2, "Start with an empty app". */
    suspend fun putAside(): Boolean

    /** F16b, an unfinished change: the live generations above [generation] go aside, so that it is the newest. */
    suspend fun putNewerAside(generation: Long): Boolean

    /** F17 step 5, after the lock has checked that vault's own master password. */
    suspend fun deleteAside(vaultId: String): Boolean

    /** F8 step 10: retires the generations under other keys; returns the `vk_id`s still kept. */
    suspend fun retireOldKeys(): Set<String>
}
