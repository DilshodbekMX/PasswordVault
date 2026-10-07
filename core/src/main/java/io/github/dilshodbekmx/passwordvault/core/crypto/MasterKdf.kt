package io.github.dilshodbekmx.passwordvault.core.crypto

/**
 * The key derivation of a master password (architecture.md §3.2): Argon2id with
 * [Argon2Params.V1], one derivation at a time in the whole process (lock.md §6.8; the KDF gate of
 * architecture.md §5.2). `GatedMasterKdf` (package 2.1) is the implementation.
 */
interface MasterKdf {
    /**
     * KEK, 32 bytes, from [passwordBytes] (the output of `MasterPasswordBytes.of`) and the 16-byte
     * [salt] of a password slot or of the pending wrap. Waits while another derivation runs.
     *
     * @throws KdfUnavailable when it cannot run on this phone now.
     */
    suspend fun kek(passwordBytes: ByteArray, salt: ByteArray): ByteArray
}
