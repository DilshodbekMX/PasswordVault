package com.example.pvlab.old

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * An AndroidKeyStore AES-256-GCM key that can only be used after a strong biometric
 * authentication bound to the individual cipher operation (BiometricPrompt + CryptoObject).
 */
object BiometricKey {
    const val ALIAS = "pvlab_biometric_aes"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /**
     * Throws (IllegalStateException / InvalidAlgorithmParameterException) when no fingerprint is
     * enrolled, because an auth-per-use key needs an enrolled biometric on API 24-29.
     */
    fun create(alias: String = ALIAS): SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true) // API 24+
        if (Build.VERSION.SDK_INT >= 30) {
            // timeout 0 = every use must be authorised, biometric (class 3) only
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        }
        // API 24-29: setUserAuthenticationValidityDurationSeconds is deliberately NOT called. Its
        // default (-1) means "authentication required for every use", which on these releases can
        // only be satisfied by a fingerprint through a CryptoObject - the same meaning as
        // setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG) on API 30+.
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(builder.build())
        return generator.generateKey()
    }

    fun get(alias: String = ALIAS): SecretKey? {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return ks.getKey(alias, null) as SecretKey?
    }

    fun delete(alias: String = ALIAS) {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }

    /** Throws KeyPermanentlyInvalidatedException once a fingerprint was added (or all removed). */
    fun encryptCipher(key: SecretKey): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }

    /** Throws KeyPermanentlyInvalidatedException once a fingerprint was added (or all removed). */
    fun decryptCipher(key: SecretKey, iv: ByteArray): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }

    @Suppress("DEPRECATION")
    fun describe(key: SecretKey): String {
        val info = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        return "keySize=${info.keySize} insideSecureHardware=${info.isInsideSecureHardware} " +
            "userAuthRequired=${info.isUserAuthenticationRequired} " +
            "validitySeconds=${info.userAuthenticationValidityDurationSeconds} " +
            "invalidatedByBiometricEnrollment=${info.isInvalidatedByBiometricEnrollment} " +
            "authEnforcedBySecureHw=${info.isUserAuthenticationRequirementEnforcedBySecureHardware}"
    }
}
