package io.github.dilshodbekmx.passwordvault.core.vaultfile

import io.github.dilshodbekmx.passwordvault.core.model.Vault

/**
 * The container of architecture.md §4: bytes in, bytes out. It knows nothing of files, directories
 * or the lock. `DefaultVaultCodec` (package 2.2) is the implementation; `VaultCodecs.create` makes it.
 */
interface VaultCodec {
    /** Rules R1 to R7 of §4.4: no key, no KDF. Never throws for any input. */
    fun check(bytes: ByteArray): FileCheck

    /** VK (32 bytes) from [slot] under [kek], or null when the slot does not open with this key. */
    fun unwrap(slot: PasswordSlot, kek: ByteArray): ByteArray?

    /** Rules R8 to R10 of §4.4: the body of [file] under [vk]. Never throws for any input. */
    fun open(file: VaultFile, vk: ByteArray): BodyResult

    /**
     * A whole version 1 file (§4.5): the canonical header with [vaultId], [vkId], [generation], the
     * `created` time from the clock and the generator and writer version from `BuildInfo`; the
     * canonical body of [vault] padded with spaces; a fresh body nonce; and [slot] copied byte for
     * byte. The caller checks the length against the cap.
     */
    fun seal(vault: Vault, vk: ByteArray, vaultId: String, vkId: String, generation: Long, slot: PasswordSlot): ByteArray

    /** A new password slot: [vk] wrapped under [kek] with a fresh slot nonce; [salt] is the salt [kek] was derived with. */
    fun wrap(vk: ByteArray, kek: ByteArray, salt: ByteArray): PasswordSlot
}
