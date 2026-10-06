package com.example.pvlab.bio.lift

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Biometric unlock key for the vault.
 *
 * An AndroidKeyStore AES-256-GCM key that only a strong biometric can use, and only for one
 * operation per authentication. It wraps (encrypts) the vault's 32-byte key when the user turns
 * fingerprint unlock on, and unwraps it at unlock time. The password always remains the primary
 * way in; every failure below ends in either [Gone] or [Temporary].
 *
 * Every failure listed in the comments was observed on an Android 17 (API 37) emulator with
 * androidx.biometric 1.1.0 and 1.4.0-alpha07.
 */
class BiometricVaultKey(private val alias: String = "vault_biometric_key_v1") {

    /** Outcome of every step. [Gone] means: delete the key and the wrapped blob, turn the option off. */
    sealed interface Result<out T> {
        class Ok<T>(val value: T) : Result<T>

        /** The key or the wrapped blob can never be used again. Remove the biometric option. */
        class Gone(val reason: String, val cause: Throwable?) : Result<Nothing>

        /** It did not work this time. Use the password; keep the biometric option. */
        class Temporary(val reason: String, val cause: Throwable?) : Result<Nothing>
    }

    class Wrapped(val ciphertext: ByteArray, val iv: ByteArray)

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /**
     * Creates (replacing any old one) the key. Call it when the user turns fingerprint unlock on.
     * Seen failing with InvalidAlgorithmParameterException("...At least one biometric must be
     * enrolled to create keys requiring user authentication for every use") when there is no
     * fingerprint or no screen lock: tell the user to set up a fingerprint first.
     */
    fun createKey(): Result<Unit> = try {
        deleteKey()
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Strong biometrics only; 0 = an authentication for every single use.
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            // API 24-29 equivalent: validity -1 (the default) = every use needs a fingerprint
            // through a CryptoObject. The PIN cannot authorise such a key there.
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        // Deliberately not used: setIsStrongBoxBacked(true) (StrongBoxUnavailableException on
        // devices without StrongBox) and setUnlockedDeviceRequired(true) (works, but adds a
        // "Device locked" failure and nothing this flow needs).
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(builder.build())
            generateKey()
        }
        Result.Ok(Unit)
    } catch (e: Exception) {
        Result.Temporary("cannot create the key: ${e.message}", e)
    }

    /** Cipher to hand to the prompt when turning the option on. Needs no IV; the key makes one. */
    fun cipherForEnable(): Result<Cipher> = initCipher { init(Cipher.ENCRYPT_MODE, it) }

    /** Cipher to hand to the prompt at unlock time. */
    fun cipherForUnlock(iv: ByteArray): Result<Cipher> =
        initCipher { init(Cipher.DECRYPT_MODE, it, GCMParameterSpec(GCM_TAG_BITS, iv)) }

    /**
     * After the prompt succeeded: encrypt the vault key with the cipher from
     * `result.cryptoObject.cipher` (never with your own reference). Store both fields.
     */
    fun wrap(authenticatedCipher: Cipher, vaultKey: ByteArray): Result<Wrapped> = try {
        val ciphertext = authenticatedCipher.doFinal(vaultKey)
        Result.Ok(Wrapped(ciphertext, authenticatedCipher.iv))
    } catch (e: Exception) {
        classifyDoFinal(e)
    }

    /** After the prompt succeeded: decrypt the stored blob with `result.cryptoObject.cipher`. */
    fun unwrap(authenticatedCipher: Cipher, ciphertext: ByteArray): Result<ByteArray> = try {
        Result.Ok(authenticatedCipher.doFinal(ciphertext))
    } catch (e: Exception) {
        classifyDoFinal(e)
    }

    fun deleteKey() {
        try {
            keyStore().deleteEntry(alias)
        } catch (_: Exception) {
        }
    }

    private inline fun initCipher(init: Cipher.(SecretKey) -> Unit): Result<Cipher> {
        val key = try {
            keyStore().getKey(alias, null) as SecretKey?
        } catch (e: UnrecoverableKeyException) {
            // Not seen on the emulator; the platform throws it for a key it reports as permanently
            // invalidated while loading it.
            return Result.Gone("key cannot be loaded", e)
        } catch (e: Exception) {
            return Result.Temporary("keystore unavailable", e)
        }
            // Seen after the screen lock was removed: the system deleted the key (and all fingerprints).
            ?: return Result.Gone("key does not exist", null)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(key)
            Result.Ok(cipher)
        } catch (e: KeyPermanentlyInvalidatedException) {
            // Seen after a new fingerprint was enrolled and after all fingerprints were deleted.
            Result.Gone("key invalidated by a fingerprint change", e)
        } catch (e: Exception) {
            // Seen: InvalidKeyException("Keystore operation failed") caused by
            // KeyStoreException("Device locked") for a setUnlockedDeviceRequired key while locked.
            Result.Temporary("cipher init failed", e)
        }
    }

    private fun classifyDoFinal(e: Exception): Result<Nothing> = when (e) {
        // Seen when the stored blob was altered: the blob can never decrypt; enable again.
        is AEADBadTagException -> Result.Gone("stored data does not match the key", e)
        // Seen: IllegalBlockSizeException caused by KeyStoreException("Key user not
        // authenticated") after onAuthenticationSucceeded when the user had entered the PIN on
        // the prompt's "too many attempts" screen first. A fresh prompt works.
        else -> Result.Temporary("operation not authorised this time", e)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        /**
         * True when a strong biometric can be used right now. Seen: 11 with no fingerprint (or no
         * screen lock), 21 (lib 1.1.0) or 1 (lib 1.4.0-alpha07) when "Use fingerprint to verify
         * it's you in apps" is off, and 0 even while the sensor is locked out.
         */
        fun canUseBiometrics(activity: FragmentActivity): Boolean =
            BiometricManager.from(activity).canAuthenticate(BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
    }
}

/**
 * The prompt. Construct it in the activity's onCreate, every time (also after a rotation), so the
 * library delivers the result to the live activity. Keep "what was I doing" (enable or unlock) in
 * a ViewModel, not in the activity.
 */
class BiometricPromptLauncher(
    private val activity: FragmentActivity,
    private val onResult: (PromptResult) -> Unit,
) {
    sealed interface PromptResult {
        /** Use [cipher] (it came from result.cryptoObject) for wrap/unwrap. */
        class Authenticated(val cipher: Cipher) : PromptResult

        /**
         * The prompt closed without a fingerprint. Temporary: show the password field.
         * [userChoice] is true for 13 ("Use password") and 10 (Back, the X, Home). Code 10 also
         * arrives when the phone locked itself after five wrong fingers.
         */
        class Closed(val code: Int, val message: CharSequence, val userChoice: Boolean) : PromptResult
    }

    private val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val cipher = result.cryptoObject?.cipher
                if (cipher != null) {
                    onResult(PromptResult.Authenticated(cipher))
                } else {
                    onResult(PromptResult.Closed(-1, "no cipher in result", userChoice = false))
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Seen: 10 USER_CANCELED (Back, the X, Home, the phone locking itself after five
                // wrong fingers), 13 NEGATIVE_BUTTON, 5 CANCELED (cancel()), 7 LOCKOUT (prompt
                // started during a timed lockout), 8 VENDOR (lib 1.1.0) or 1 HW_UNAVAILABLE
                // (lib 1.4.0-alpha07) with fingerprint for apps turned off. All temporary.
                val userChoice = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                onResult(PromptResult.Closed(errorCode, errString, userChoice))
            }

            // A finger that is not enrolled: the prompt stays open and says so itself. Nothing to do.
            override fun onAuthenticationFailed() = Unit
        },
    )

    /**
     * Shows the prompt. Returns false, and shows nothing, when the activity is not resumed:
     * the library silently ignores authenticate() after onSaveInstanceState and no callback
     * would ever arrive. Call it once per attempt; a second call while a prompt is open is
     * ignored by the library.
     */
    fun launch(
        cipher: Cipher,
        title: CharSequence,
        subtitle: CharSequence?,
        negativeButtonText: CharSequence,
    ): Boolean {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return false
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { if (subtitle != null) setSubtitle(subtitle) }
            .setNegativeButtonText(negativeButtonText)
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        return true
    }

    fun cancel() = prompt.cancelAuthentication()
}
