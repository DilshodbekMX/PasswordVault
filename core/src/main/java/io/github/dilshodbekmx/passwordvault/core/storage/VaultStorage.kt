package io.github.dilshodbekmx.passwordvault.core.storage

import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.vaultfile.PasswordSlot

/**
 * The generation store of architecture.md §4.7 to §4.12 (§3.3). `VaultStore` (package 2.3) is the
 * implementation; `VaultStores.create` makes it. Callers hold the vault mutex (§5.2). Nothing here
 * derives a key: the caller derives every KEK with `MasterKdf` and passes it in.
 */
interface VaultStorage {
    /** §4.8: the current vault and every vault put aside, before unlock. Safe without a key; deletes stale `.tmp` files and `new-*` directories at process start. */
    fun scan(): Scan

    /** The distinct password slots among the kept generations of [target], newest first [V-S3]. */
    fun slots(target: VaultRef): List<SlotRef>

    /** §4.8 "Opening with the master password", for one slot: Opened | OlderPassword | SlotClosed | Damaged | Newer | Unreadable. */
    fun openWithSlot(target: VaultRef, slot: SlotRef, kek: ByteArray): PasswordResult

    /** §4.8: with a VK per `vk_id` (PIN, fingerprint, the pending wrap of lock.md F11): Opened | NeedsOtherKey | Damaged | Newer | Unreadable. */
    fun open(target: VaultRef, keys: Map<String, ByteArray>): OpenResult

    /** An ordinary save (§4.9 steps 1 to 6 and 8 to 10): Saved | NotDurable | Failed | ReadOnly | TooLarge. */
    fun commit(opened: OpenedVault, vault: Vault): CommitResult

    /** Creation, restore, lock.md F11 (§4.9 steps 1 to 6) in `new-<vaultId>/`, generation 1: Staged | Failed | TooLarge. */
    fun stageVault(vault: Vault, vaultId: String, vk: ByteArray, vkId: String, slot: PasswordSlot): StageResult

    /** A master-password change, lock.md F8 (§4.9 steps 1 to 6): `vault-N.tmp` in `live/` under the new key and slot. */
    fun stageRekey(opened: OpenedVault, vault: Vault, vk: ByteArray, vkId: String, slot: PasswordSlot): StageResult

    /**
     * §4.9 step 7, on the bytes read back from disk: unwraps the slot with [kek] (derived afresh from
     * the second typing and `staged.slotSalt`) and opens the body with the VK it gives. False: the
     * staged file was renamed to `.bad`.
     */
    fun prove(staged: Staged, kek: ByteArray): Boolean

    /** §4.9 steps 8 to 10: Saved | NotDurable | Failed. With [putCurrentAside], `live/` goes aside first (step 9). */
    fun publish(staged: Staged, putCurrentAside: Boolean): CommitResult

    /** Removes the staged file or directory; nothing live changes. */
    fun discard(staged: Staged)

    /** Runs [use] with the VK of [opened]; the only way `:app` reaches it. */
    fun <T> useKey(opened: OpenedVault, use: (vk: ByteArray) -> T): T

    /** §4.10, after a durable save when it is due. */
    fun housekeeping(opened: OpenedVault)

    /** §4.11, lock.md F8 step 10: deletes the live generations of every other `vk_id`; returns the `vk_id`s still kept. */
    fun retireOldKeys(opened: OpenedVault): Set<String>

    /** §4.11, "Start with an empty app" (lock.md F17 step 2): `live/` becomes `aside-<ms>-<vault_id>/`. */
    fun putAside(): Boolean

    /** §4.11: only after `open(VaultRef.Aside(vaultId))` succeeded in this process. */
    fun swapIn(vaultId: String): Boolean

    /** §4.11: every live generation above [generation] becomes `.aside`; an unfinished master-password change only (lock.md F16b). */
    fun putNewerAside(generation: Long): Boolean

    /** §4.11: deletes that vault's directory; the caller has checked that vault's own master password. */
    fun deleteAside(vaultId: String)
}
