package io.github.dilshodbekmx.passwordvault.keystore

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import io.github.dilshodbekmx.passwordvault.core.lock.policy.KeystoreErrors
import io.github.dilshodbekmx.passwordvault.core.lock.policy.KeystoreFailure
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import java.security.InvalidKeyException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

/**
 * Turns what a Keystore call threw into the small description that the table of lock.md §14.3
 * classifies ("For the code" in §14.3; architecture.md §2.2 `keystore`). The table itself is
 * `KeystoreErrors` in `:core`, a pure function tested on the JVM (lock.md T13); this is the short
 * adapter in front of it, and every Keystore class of this package classifies through [classify].
 *
 * Only the class of the exception that was thrown counts, and for `UnrecoverableKeyException`
 * whether it has a cause. No message is read and no cause is searched: GONE is reserved for what
 * the platform says outright (lock.md §14.1), and a message is not its word.
 * `KeyStoreException.isTransientFailure()` and `getRetryPolicy()` are never asked: "Device locked"
 * reports itself as not transient (lock.md §14.2).
 */
object KeystoreFailureAdapter {
    /**
     * [thrown] as lock.md §14.3 names it. The two subclasses of `InvalidKeyException` that the
     * table tells apart are looked for before `InvalidKeyException` itself.
     *
     * - `UnrecoverableKeyException` without a cause is how `getKey` reports a permanently
     *   invalidated key on old and new Android versions; with a cause (a `KeyStoreException`) it is
     *   any other failure (lock.md §14.2).
     * - `KeyPermanentlyInvalidatedException` comes from `Mac.init` and `Cipher.init`: seen after a
     *   finger was added and after all fingers were deleted [L-bio case 6].
     * - Any other `InvalidKeyException` is, for example, "Keystore operation failed" caused by
     *   "Device locked" for the PIN key on API 35 and newer [L-bio case 7, V-C6].
     */
    fun describe(thrown: Throwable): KeystoreFailure = when (thrown) {
        is UnrecoverableKeyException ->
            if (thrown.cause == null) KeystoreFailure.UnrecoverableNoCause else KeystoreFailure.UnrecoverableWithCause
        is KeyPermanentlyInvalidatedException -> KeystoreFailure.PermanentlyInvalidated
        is UserNotAuthenticatedException -> KeystoreFailure.UserNotAuthenticated
        is InvalidKeyException -> KeystoreFailure.OtherInvalidKey
        is AEADBadTagException -> KeystoreFailure.BadTag
        else -> KeystoreFailure.Other
    }

    /** [thrown] at [place], as the table classes it: GONE only on the platform's own word, else TEMPORARY with [thrown] as its cause. */
    fun classify(place: KeystoreErrors.Place, thrown: Throwable): Ks<Nothing> =
        KeystoreErrors.classify(place, describe(thrown), thrown)
}
