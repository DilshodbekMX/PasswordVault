package io.github.dilshodbekmx.passwordvault.core.crypto

import kotlinx.coroutines.CoroutineDispatcher

/**
 * How another package gets the implementations of package 2.1 without naming their classes
 * (architecture.md §2.1, §3.2). The classes themselves are internal to `:core`.
 */
object CryptoFactory {
    /** `JcaAesGcm`: `Cipher.getInstance("AES/GCM/NoPadding")`, no provider name, never `destroy()` [V-C10]. */
    fun aead(): Aead = JcaAesGcm()

    /** `BouncyCastleArgon2id`: the JVM implementation, and the fallback on a phone. */
    fun bouncyCastleArgon2id(): Argon2id = BouncyCastleArgon2id()

    /**
     * `GuardedArgon2id`: [native] when it is there (null when loading it threw any `Throwable`
     * [V-C2]); otherwise [fallback], but only when `freeHeap()` is at least [fallbackNeeds] bytes,
     * read again after [collect] when it is not, else `KdfUnavailable`. An `OutOfMemoryError` of
     * [fallback] is tried once more after [collect]; one that stays becomes `KdfUnavailable`. A
     * `LinkageError` at a call of [native] counts as a failed load from then on.
     */
    fun guardedArgon2id(native: Argon2id?, fallback: Argon2id, freeHeap: () -> Long, fallbackNeeds: Long, collect: () -> Unit): Argon2id =
        GuardedArgon2id(native, fallback, freeHeap, fallbackNeeds, collect)

    /**
     * `GatedMasterKdf`: the process-wide gate over [argon2], deriving on [dispatcher]. Every
     * `MasterKdf` made here waits on the same gate.
     */
    fun masterKdf(argon2: Argon2id, dispatcher: CoroutineDispatcher): MasterKdf = GatedMasterKdf(argon2, dispatcher)
}
