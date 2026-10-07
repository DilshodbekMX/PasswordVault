package io.github.dilshodbekmx.passwordvault.core.lock.policy

/**
 * The result of one use of a Keystore key (lock.md §14; architecture.md §3.4), classified by the
 * rules of lock.md §14.2 and §14.3: GONE only on the platform's own word, everything else that
 * stopped it TEMPORARY.
 */
sealed interface Ks<out T> {
    data class Ok<T>(val value: T) : Ks<T>

    /**
     * The platform states that this key, or this wrap, can never work again (lock.md §14.1): its
     * slot is removed and its alias deleted. [why] names the signal, such as "invalidated" or
     * "not found" (§14.2); it holds no secret.
     */
    data class Gone(val why: String) : Ks<Nothing>

    /** Anything else that stopped the key: use another way this time, change nothing (lock.md §14.1). */
    data class Temporary(val cause: Throwable?) : Ks<Nothing>
}
