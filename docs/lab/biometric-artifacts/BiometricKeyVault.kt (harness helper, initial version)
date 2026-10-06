package com.example.pvlab.bio

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore side of biometric unlock: an AES-256-GCM key that only a strong biometric can use,
 * once per operation. (Initial lab version; see REPORT.md for the final one.)
 */
class BiometricKeyVault(private val alias: String = DEFAULT_ALIAS) {

    sealed interface CipherResult {
        /** Hand [cipher] to the prompt inside a CryptoObject. */
        class Ready(val cipher: Cipher) : CipherResult

        /** The key is missing or can never be used again: remove the biometric option. */
        class KeyGone(val cause: Throwable?) : CipherResult

        /** Something went wrong this time: use the password, keep the biometric option. */
        class TemporaryFailure(val cause: Throwable) : CipherResult
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun hasKey(): Boolean = runCatching { keyStore().containsAlias(alias) }.getOrDefault(false)

    /** Throws if the key cannot be created (no secure lock screen, no strong biometric enrolled, ...). */
    fun createKey(strongBox: Boolean = false, unlockedDeviceRequired: Boolean = false): SecretKey {
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
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            // API 24-29: -1 (the default) means "authenticate every use", and only a
            // fingerprint (via a CryptoObject) can authorise such a key.
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (strongBox) builder.setIsStrongBoxBacked(true)
            if (unlockedDeviceRequired) builder.setUnlockedDeviceRequired(true)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(builder.build())
        return generator.generateKey()
    }

    fun loadKey(): SecretKey? = keyStore().getKey(alias, null) as SecretKey?

    fun cipherForEnable(): CipherResult = initCipher { key -> init(Cipher.ENCRYPT_MODE, key) }

    fun cipherForUnlock(iv: ByteArray): CipherResult =
        initCipher { key -> init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv)) }

    private inline fun initCipher(block: Cipher.(SecretKey) -> Unit): CipherResult {
        val key = try {
            loadKey()
        } catch (e: UnrecoverableKeyException) {
            return CipherResult.KeyGone(e)
        } catch (e: Exception) {
            return CipherResult.TemporaryFailure(e)
        } ?: return CipherResult.KeyGone(null)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.block(key)
            CipherResult.Ready(cipher)
        } catch (e: KeyPermanentlyInvalidatedException) {
            CipherResult.KeyGone(e)
        } catch (e: Exception) {
            CipherResult.TemporaryFailure(e)
        }
    }

    fun deleteKey() {
        runCatching { keyStore().deleteEntry(alias) }
    }

    companion object {
        const val DEFAULT_ALIAS = "pv_bio"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}

/**
 * Prompt launcher. Construct it in the activity's onCreate (every time, including after a
 * rotation) so the library re-attaches the callback to the new activity instance.
 */
class BiometricUnlockPrompt(
    activity: FragmentActivity,
    private val onFailedAttempt: () -> Unit = {},
    private val onOutcome: (Outcome) -> Unit,
) {
    sealed interface Outcome {
        class Success(val cipher: Cipher, val authenticationType: Int) : Outcome
        class Error(val code: Int, val message: CharSequence) : Outcome
    }

    private val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val cipher = result.cryptoObject?.cipher
                if (cipher == null) {
                    onOutcome(Outcome.Error(-1, "no cipher in result"))
                } else {
                    onOutcome(Outcome.Success(cipher, result.authenticationType))
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onOutcome(Outcome.Error(errorCode, errString))
            }

            override fun onAuthenticationFailed() = onFailedAttempt()
        },
    )

    fun launch(cipher: Cipher, title: CharSequence, subtitle: CharSequence?, negativeText: CharSequence) {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { if (subtitle != null) setSubtitle(subtitle) }
            .setNegativeButtonText(negativeText)
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    fun cancel() = prompt.cancelAuthentication()
}
