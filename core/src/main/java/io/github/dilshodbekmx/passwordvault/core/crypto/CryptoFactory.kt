package io.github.dilshodbekmx.passwordvault.core.crypto

import kotlinx.coroutines.CoroutineDispatcher

/**
 * How another package gets the implementations of package 2.1 without naming their classes
 * (architecture.md §2.1, §3.2). Every body throws [NotImplementedError] until package 2.1
 * (Cryptography) is merged and replaces it.
 */
object CryptoFactory {
    /** `JcaAesGcm`: `Cipher.getInstance("AES/GCM/NoPadding")`, no provider name, never `destroy()` [V-C10]. */
    fun aead(): Aead = TODO("package 2.1 (Cryptography)")

    /** `BouncyCastleArgon2id`: the JVM implementation, and the fallback on a phone. */
    fun bouncyCastleArgon2id(): Argon2id = TODO("package 2.1 (Cryptography)")

    /**
     * `GuardedArgon2id`: [native] when it is there (null when loading it threw any `Throwable`
     * [V-C2]); otherwise [fallback], but only when `freeHeap()` is at least [fallbackNeeds] bytes,
     * else `KdfUnavailable`. An `OutOfMemoryError` becomes `KdfUnavailable`.
     */
    fun guardedArgon2id(native: Argon2id?, fallback: Argon2id, freeHeap: () -> Long, fallbackNeeds: Long): Argon2id =
        TODO("package 2.1 (Cryptography)")

    /** `GatedMasterKdf`: the process-wide gate over [argon2], deriving on [dispatcher]. */
    fun masterKdf(argon2: Argon2id, dispatcher: CoroutineDispatcher): MasterKdf = TODO("package 2.1 (Cryptography)")
}
