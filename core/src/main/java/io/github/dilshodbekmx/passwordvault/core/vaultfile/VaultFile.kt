package io.github.dilshodbekmx.passwordvault.core.vaultfile

/**
 * A file that passed the checks without a key, R1 to R7 of architecture.md §4.4: its header, the
 * AAD (the first 12 + `header_len` bytes exactly as stored: magic ‖ `header_len` ‖ header, without
 * the body nonce [V-C3]), the body nonce and the body ciphertext with its tag. [trailerOk] tells
 * whether the SHA-256 trailer matched (R4); it detects damage and authenticates nothing.
 */
class VaultFile(
    val header: VaultHeader,
    val aad: ByteArray,
    val bodyNonce: ByteArray,
    val bodyCt: ByteArray,
    val trailerOk: Boolean,
)
