package io.github.dilshodbekmx.passwordvault.core.lock.policy

/**
 * The small description of a Keystore failure that the table of lock.md §14.3 classifies
 * ("For the code" in §14.3). `KeystoreFailureAdapter` in `:app` (package 3.9) turns a real
 * exception into one of these; `KeystoreErrors` (package 2.10) is the table, a pure function over
 * them and the place where the failure happened, tested on the JVM (lock.md T13).
 */
enum class KeystoreFailure {
    /** `getKey` returned null: believed only when the canary loads in the same breath (§14.2). */
    NullKey,

    /** `UnrecoverableKeyException` without a cause: how the platform reports a permanently invalidated key. */
    UnrecoverableNoCause,

    /** `UnrecoverableKeyException` with a cause, usually a `KeyStoreException`: any other failure. */
    UnrecoverableWithCause,

    /** `KeyPermanentlyInvalidatedException` from `Mac.init` or `Cipher.init`. */
    PermanentlyInvalidated,

    /** `UserNotAuthenticatedException`; not expected for these keys. */
    UserNotAuthenticated,

    /** Any other `InvalidKeyException`, such as "Keystore operation failed" caused by "Device locked" [V-C6]. */
    OtherInvalidKey,

    /** `AEADBadTagException`, for example from the fingerprint `doFinal` on a wrap that does not match the key. */
    BadTag,

    /** Any other exception. */
    Other,
}
