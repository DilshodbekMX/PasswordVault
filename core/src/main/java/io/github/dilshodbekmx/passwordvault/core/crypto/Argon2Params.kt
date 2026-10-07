package io.github.dilshodbekmx.passwordvault.core.crypto

/**
 * The parameters of one Argon2id v1.3 derivation (architecture.md §3.2, §4.6). Version 1 uses
 * [V1] everywhere and nothing else: a file with other KDF constants is not a version 1 file (§4.2).
 */
data class Argon2Params(val memoryKiB: Int, val passes: Int, val lanes: Int, val outLen: Int) {
    companion object {
        /** m = 65,536 KiB (64 MiB), t = 3, p = 4, a 32-byte key (crypto.md §1, lock.md §4.2). */
        val V1 = Argon2Params(65_536, 3, 4, 32)
    }
}
