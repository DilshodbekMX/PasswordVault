package io.github.dilshodbekmx.passwordvault.keystore

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.PasswordVaultApp
import io.github.dilshodbekmx.passwordvault.core.lock.policy.KeystoreErrors
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertFalse

/**
 * What the device tests of the Keystore package share (build plan 3.9, on API 24, 28 and 37): the
 * real Android Keystore of the app under test, looked at without the classes under test, and aliases
 * that are deleted again after each test.
 *
 * The tests run in the app's process, so every alias here is one of the app's own. They use ids of
 * their own for shortcut keys; `pv_canary` is the app's one canary, which some tests delete and make
 * again. The connected tests run on a freshly installed app (`tools/emu/adb.sh --connected`), whose
 * Keystore is empty.
 */
internal class KeystoreKit {
    private val made = LinkedHashSet<String>()

    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** The app's own graph, as `PasswordVaultApp` built it at process start. */
    val graph: AppGraph get() = (context.applicationContext as PasswordVaultApp).graph

    /** The Keystore as the platform gives it, not through the classes under test. */
    fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** A PIN alias of this test, `"pv_pin_" + id`, deleted by [cleanUp]. */
    fun pinAlias(id: String = newId()): String = track(RecordCodec.PIN_ALIAS_PREFIX + id)

    /** A fingerprint alias of this test, `"pv_fp_" + id`, deleted by [cleanUp]. */
    fun fingerprintAlias(id: String = newId()): String = track(RecordCodec.FINGERPRINT_ALIAS_PREFIX + id)

    /** Any alias, deleted now (a test that was cut off may have left it) and again by [cleanUp]. */
    fun track(alias: String): String {
        delete(alias)
        made += alias
        return alias
    }

    fun newId(): String = UUID.randomUUID().toString()

    /** The key under [alias], or null: `getKey`, never `containsAlias`. */
    fun key(alias: String): SecretKey? = keyStore().getKey(alias, null) as SecretKey?

    fun has(alias: String): Boolean = key(alias) != null

    fun info(alias: String): KeyInfo {
        val key = key(alias) ?: throw AssertionError("no key under $alias")
        return SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE).getKeySpec(key, KeyInfo::class.java) as KeyInfo
    }

    fun delete(alias: String) {
        keyStore().deleteEntry(alias)
    }

    fun deleteCanary() = delete(KeystoreErrors.CANARY_ALIAS)

    val hasCanary: Boolean get() = has(KeystoreErrors.CANARY_ALIAS)

    fun cleanUp() {
        for (alias in made) {
            try {
                delete(alias)
            } catch (e: Exception) {
                // The next alias.
            }
        }
        made.clear()
    }

    /**
     * Whether this device keeps Keystore keys in software, as the emulators of API 24, 28 and 37 do
     * (lock.md §4.5): read from the `KeyInfo` of a key made for the question, by the rule of §4.5.
     */
    fun keysAreInSoftware(): Boolean {
        val alias = track("pvtest_hardware_question")
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).setKeySize(256).build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE).run {
            init(spec)
            generateKey()
        }
        val info = info(alias)
        delete(alias)
        return if (Build.VERSION.SDK_INT >= 31) {
            info.securityLevel != KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT &&
                info.securityLevel != KeyProperties.SECURITY_LEVEL_STRONGBOX &&
                info.securityLevel != KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE
        } else {
            @Suppress("DEPRECATION")
            !info.isInsideSecureHardware
        }
    }

    /** Whether this is one of the emulators of tools/emu, whose Keystore keeps every key in software [L-bio case 7, L-old §3]. */
    val isEmulator: Boolean
        get() = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish" || Build.FINGERPRINT.contains("generic") ||
            Build.PRODUCT.startsWith("sdk_")

    /** Puts the HMAC-SHA256 key [key] into the Keystore under [alias], for signing, as `K_pin` is. */
    fun importHmacKey(alias: String, key: ByteArray) {
        keyStore().setEntry(
            alias,
            KeyStore.SecretKeyEntry(SecretKeySpec(key, "HmacSHA256")),
            KeyProtection.Builder(KeyProperties.PURPOSE_SIGN).build(),
        )
    }

    /**
     * An AES-256-GCM key like `K_fp` that needs no finger, made by the test under [alias]: with it
     * the whole of `seal` and `open` runs on the real Keystore without a prompt. The app never makes
     * such a key.
     */
    fun makeAesKeyWithoutAuthentication(alias: String): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    /** `AES-256-GCM(key under alias, plaintext)` by the Keystore: the IV it chose and the ciphertext with its tag. */
    fun encryptWith(alias: String, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(AES_GCM).apply { init(Cipher.ENCRYPT_MODE, key(alias)) }
        val ct = cipher.doFinal(plaintext)
        return cipher.iv to ct
    }

    /**
     * The PIN key of API 35 and newer works only while the phone is unlocked, and a fingerprint key
     * can only be made while a finger is enrolled: the test devices are unlocked, with the device PIN
     * 1111 and fingers 1 and 2 (tools/emu/README.md).
     */
    fun requireUnlockedDevice() {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        assertFalse(
            "the test device is locked; unlock it first (tools/emu/adb.sh -s SERIAL --unlock)",
            keyguard.isDeviceLocked,
        )
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val AES_GCM = "AES/GCM/NoPadding"

        fun softwareHmac(key: ByteArray, message: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(message)

        /** A software AES-256-GCM cipher, as a prompt could hand one back. */
        fun softwareCipher(mode: Int, key: ByteArray, nonce: ByteArray): Cipher =
            Cipher.getInstance(AES_GCM).apply { init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) }

        fun sequence(first: Int, size: Int): ByteArray = ByteArray(size) { (first + it).toByte() }
    }
}
