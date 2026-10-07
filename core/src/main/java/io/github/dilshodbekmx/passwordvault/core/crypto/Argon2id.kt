package io.github.dilshodbekmx.passwordvault.core.crypto

/**
 * Argon2id v1.3 (architecture.md §3.2). Implementations: `BouncyCastleArgon2id` (JVM tests and the
 * fallback on a phone), `NativeArgon2id` in `:app` (argon2kt), and `GuardedArgon2id`, which picks
 * one of them; `CryptoFactory` makes them.
 */
fun interface Argon2id {
    /**
     * Derives [Argon2Params.outLen] bytes from [password] and [salt]. Blocks for 0.3 to 1 s and needs
     * 64 MiB with [Argon2Params.V1]. The caller passes exactly the bytes to hash (for a master
     * password, `MasterPasswordBytes.of`) and wipes them afterwards; so does the caller of the result.
     *
     * @throws KdfUnavailable when it cannot run on this phone now ("not enough memory"). It never
     *   returns a wrong key.
     */
    fun derive(password: ByteArray, salt: ByteArray, params: Argon2Params): ByteArray
}
