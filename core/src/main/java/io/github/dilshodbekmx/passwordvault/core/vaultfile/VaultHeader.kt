package io.github.dilshodbekmx.passwordvault.core.vaultfile

/**
 * The values of a version 1 header (architecture.md §4.2), as `VaultCodec.check` read them. The
 * header is not authenticated until the body has opened under VK: until then [vaultId] and [vkId]
 * may only choose a lock screen and a record directory (§4.4, lock.md §5.1).
 */
data class VaultHeader(
    /** UUID v4, lower case: made when a vault is created, restored or made by a replacement; never changes. */
    val vaultId: String,
    /** UUID v4, lower case: made together with every new VK. */
    val vkId: String,
    /** 1 to 9,999,999,999; in a vault generation it equals the number in the file name [V-C8]. */
    val generation: Long,
    /** Wall-clock milliseconds when the file was sealed; for display only. */
    val createdMs: Long,
    /** `PasswordVault ` and the writing build's `versionName`, for people (R-14). */
    val generator: String,
    /** The writing build's `versionCode`; retention pins by it (§4.10). */
    val writerVersion: Int,
    /** The length of the body ciphertext with its tag. */
    val bodyLen: Int,
    val slot: PasswordSlot,
)
