package io.github.dilshodbekmx.passwordvault.core.util

/**
 * Every random value of the app (architecture.md §3.1): keys, salts, nonces, ids, generated
 * passwords. `SystemRandom` (package 2.7) is `SecureRandom()` with no arguments
 * (`getInstanceStrong` is API 26); `FakeRandom` of :testing gives fixed bytes to the tests.
 */
interface RandomSource {
    /** [n] random bytes (n ≥ 0), in a new array the caller owns. */
    fun bytes(n: Int): ByteArray

    /**
     * A uniformly distributed number from 0 until [bound] (bound > 0). Unbiased: drawn by rejection,
     * never as a random number `% bound`.
     */
    fun int(bound: Int): Int

    /** A new random UUID, version 4, in lower case: [Uuid.make] over 16 random bytes. */
    fun uuid(): String
}
