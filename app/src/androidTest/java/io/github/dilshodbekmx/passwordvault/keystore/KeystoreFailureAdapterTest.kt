package io.github.dilshodbekmx.passwordvault.keystore

import android.security.keystore.KeyExpiredException
import android.security.keystore.KeyNotYetValidException
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.core.lock.policy.KeystoreErrors
import io.github.dilshodbekmx.passwordvault.core.lock.policy.KeystoreErrors.Place
import io.github.dilshodbekmx.passwordvault.core.lock.policy.KeystoreFailure
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import java.io.IOException
import java.security.InvalidAlgorithmParameterException
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.ProviderException
import java.security.UnrecoverableEntryException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 3.9, [device] (the exceptions are the platform's own classes): [KeystoreFailureAdapter]
 * turns each exception of lock.md §14.3 into its `KeystoreFailure`, and with the table of
 * `KeystoreErrors` only three cells are GONE: `UnrecoverableKeyException` without a cause at
 * `getKey`, `KeyPermanentlyInvalidatedException` at `Mac.init` or `Cipher.init`, and
 * `AEADBadTagException` at the fingerprint `doFinal`.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreFailureAdapterTest {
    private fun describe(thrown: Throwable) = KeystoreFailureAdapter.describe(thrown)

    private fun withCause(cause: Throwable) = UnrecoverableKeyException("Failed to obtain information about key").apply { initCause(cause) }

    @Test
    fun unrecoverableKeyExceptionIsToldApartByItsCause() {
        // How getKey reports a permanently invalidated key, on old and new versions (lock.md §14.2).
        assertEquals(KeystoreFailure.UnrecoverableNoCause, describe(UnrecoverableKeyException()))
        assertEquals(KeystoreFailure.UnrecoverableNoCause, describe(UnrecoverableKeyException("User changed or deleted their auth credentials")))
        // With a KeyStoreException as its cause it is any other failure.
        assertEquals(KeystoreFailure.UnrecoverableWithCause, describe(withCause(KeyStoreException("-7"))))
        assertEquals(KeystoreFailure.UnrecoverableWithCause, describe(withCause(IllegalArgumentException())))
        // Its superclass is not the platform's word about a key.
        assertEquals(KeystoreFailure.Other, describe(UnrecoverableEntryException()))
    }

    @Test
    fun theInvalidKeyExceptionsAreToldApart() {
        assertEquals(KeystoreFailure.PermanentlyInvalidated, describe(KeyPermanentlyInvalidatedException()))
        assertEquals(KeystoreFailure.PermanentlyInvalidated, describe(KeyPermanentlyInvalidatedException("Key permanently invalidated")))
        assertEquals(KeystoreFailure.UserNotAuthenticated, describe(UserNotAuthenticatedException()))
        // "Keystore operation failed" caused by "Device locked" [L-bio case 7], and every other kind.
        assertEquals(KeystoreFailure.OtherInvalidKey, describe(InvalidKeyException("Keystore operation failed", KeyStoreException("Device locked"))))
        assertEquals(KeystoreFailure.OtherInvalidKey, describe(InvalidKeyException()))
        assertEquals(KeystoreFailure.OtherInvalidKey, describe(KeyExpiredException()))
        assertEquals(KeystoreFailure.OtherInvalidKey, describe(KeyNotYetValidException()))
    }

    @Test
    fun onlyTheExceptionThatWasThrownCountsNeverItsCauseOrItsMessage() {
        val invalidated = KeyPermanentlyInvalidatedException()
        assertEquals(KeystoreFailure.OtherInvalidKey, describe(InvalidKeyException("No provider can be initialized with given key", invalidated)))
        assertEquals(KeystoreFailure.Other, describe(RuntimeException(invalidated)))
        assertEquals(KeystoreFailure.Other, describe(ProviderException("Key permanently invalidated")))
        assertEquals(KeystoreFailure.UnrecoverableWithCause, describe(withCause(invalidated)))
        assertEquals(KeystoreFailure.Other, describe(IllegalStateException(AEADBadTagException())))
    }

    @Test
    fun theBadTagAndEverythingElse() {
        assertEquals(KeystoreFailure.BadTag, describe(AEADBadTagException()))
        // Its superclass, and "Key user not authenticated" as doFinal reports it [L-bio finding b].
        assertEquals(KeystoreFailure.Other, describe(BadPaddingException()))
        assertEquals(KeystoreFailure.Other, describe(IllegalBlockSizeException()))
        for (other in listOf(
            IllegalStateException("Keystore operation failed"),
            ProviderException("Keystore key generation failed"),
            KeyStoreException("Failed to delete entry"),
            InvalidAlgorithmParameterException("Caller-provided IV not permitted"),
            NoSuchAlgorithmException(),
            NullPointerException(),
            ClassCastException(),
            IOException(),
            SecurityException(),
            OutOfMemoryError(),
        )) {
            assertEquals(other.toString(), KeystoreFailure.Other, describe(other))
        }
        // describe never names a null from getKey: that is not an exception.
        val named = listOf<Throwable>(
            UnrecoverableKeyException(), withCause(KeyStoreException()), KeyPermanentlyInvalidatedException(),
            UserNotAuthenticatedException(), InvalidKeyException(), AEADBadTagException(), ProviderException(),
        ).map(::describe).toSet()
        assertEquals(KeystoreFailure.entries.toSet() - KeystoreFailure.NullKey, named)
    }

    @Test
    fun classifyIsGoneInThreeCellsOnlyAndKeepsTheCauseOfATemporaryFailure() {
        val thrown: List<Throwable> = listOf(
            UnrecoverableKeyException(),
            withCause(KeyStoreException()),
            KeyPermanentlyInvalidatedException(),
            UserNotAuthenticatedException(),
            InvalidKeyException("Keystore operation failed", KeyStoreException("Device locked")),
            AEADBadTagException(),
            IllegalBlockSizeException(),
            IllegalStateException(),
            ProviderException(),
        )
        var gone = 0
        for (place in Place.entries) {
            for (e in thrown) {
                val expected: Ks<Nothing>? = when {
                    place == Place.GET_KEY && e is UnrecoverableKeyException && e.cause == null -> Ks.Gone(KeystoreErrors.GONE_INVALIDATED)
                    place == Place.INIT && e is KeyPermanentlyInvalidatedException -> Ks.Gone(KeystoreErrors.GONE_INVALIDATED)
                    place == Place.FINGERPRINT_DO_FINAL && e is AEADBadTagException -> Ks.Gone(KeystoreErrors.GONE_BAD_WRAP)
                    else -> null
                }
                val classified = KeystoreFailureAdapter.classify(place, e)
                if (expected != null) {
                    gone++
                    assertEquals("$place, $e", expected, classified)
                } else {
                    assertTrue("$place, $e: TEMPORARY, but $classified", classified is Ks.Temporary)
                    assertSame("$place, $e", e, (classified as Ks.Temporary).cause)
                }
            }
        }
        assertEquals("GONE only on the platform's own word", 3, gone)
    }
}
