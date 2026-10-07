package io.github.dilshodbekmx.passwordvault.keystore

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import io.github.dilshodbekmx.passwordvault.core.lock.flow.DeviceKeys
import io.github.dilshodbekmx.passwordvault.core.lock.flow.KeyCreate
import io.github.dilshodbekmx.passwordvault.core.lock.policy.KeystoreErrors
import io.github.dilshodbekmx.passwordvault.core.lock.policy.KeystoreErrors.Lookup
import io.github.dilshodbekmx.passwordvault.core.lock.policy.KeystoreFailure
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.keystore.KeystoreFaults.Row
import java.security.InvalidAlgorithmParameterException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/**
 * The Android Keystore as the lock uses it (architecture.md §2.2 `keystore`, §3.4 `DeviceKeys`;
 * lock.md §4.2, §4.5, §5.7, §14.2): loading a key and telling "this key is gone" from "the Keystore
 * is not answering", the reference key `pv_canary`, the hardware check of every key that is made,
 * the list of the app's shortcut aliases, and deletion. [KeystorePinKey] and
 * [KeystoreFingerprintKey] make and use their keys through it.
 *
 * It starts from the lab's `docs/lab/biometric-artifacts/BiometricVaultKey.kt`, with the two changes
 * lock.md §14.2 names: `UnrecoverableKeyException` is GONE only without a cause, and a null from
 * `getKey` goes through the canary.
 *
 * **Rules it keeps.**
 * - GONE only on the platform's own word; every doubt is TEMPORARY (lock.md §14.1). The decisions are
 *   `KeystoreErrors` in `:core`; this class only asks the Keystore and describes what it said
 *   ([KeystoreFailureAdapter]).
 * - `KeyStore.containsAlias` is never asked: it is false on any error [V-C6].
 * - A key that is made is read back at once (`KeyInfo`, lock.md §4.5) and deleted again unless it
 *   is in secure hardware or the build allows software keys ([allowSoftwareKeys]).
 * - A key that exists is never replaced or deleted by making one: [makeNew] refuses an alias that
 *   has a key. Only [delete] and [dropAllKeys] remove a key that was in use.
 * - Never StrongBox, a usage count or a validity period (lock.md §4.2).
 *
 * Every call is a binder call to the Keystore service, so the caller runs it off the main thread
 * (`Dispatchers.IO`, architecture.md §5.3) and, where a key is made or deleted, under the lock mutex
 * (§5.2). The class keeps no key and no state of its own besides [faults]; a fresh `KeyStore` object
 * is loaded for every call.
 *
 * @param allowSoftwareKeys `BuildConfig.ALLOW_SOFTWARE_KEYSTORE`: true in the `debug` and `staging`
 *   builds, whose emulators keep every key in software; false in a release build, which then offers
 *   neither PIN nor fingerprint on a phone without secure hardware (lock.md §4.5, F18).
 * @param faults the faults a test build can ask for (`ks-fault`, lock.md §15.3); in a release
 *   build none can be armed.
 */
class KeystoreAccess(
    private val allowSoftwareKeys: Boolean,
    val faults: KeystoreFaults = KeystoreFaults(),
) : DeviceKeys {

    // ---------------------------------------------------------------------------------------------
    // DeviceKeys

    /**
     * Makes `pv_canary` when it is missing, and checks it by lock.md §4.5 whether it was there or
     * not: [KeyCreate.Ok] for a canary that may be used; [KeyCreate.NoSecureHardware] when it is in
     * software and the build refuses that (it is deleted, F18); [KeyCreate.NotNow] when the Keystore
     * does not answer, the canary cannot be read or made, or its `KeyInfo` cannot be read. A canary
     * that is there is never made again here.
     */
    override fun ensureCanary(): KeyCreate {
        val keyStore = try {
            open()
        } catch (e: Exception) {
            return KeyCreate.NotNow
        }
        return when (val found = lookup(keyStore, CANARY)) {
            is Lookup.Found -> {
                val judged = try {
                    KeystoreErrors.created(insideSecureHardware(found.key), allowSoftwareKeys)
                } catch (e: Exception) {
                    return KeyCreate.NotNow
                }
                if (judged != KeyCreate.Ok) remove(CANARY)
                judged
            }
            is Lookup.Failed -> if (found.failure == KeystoreFailure.NullKey) makeCanary() else KeyCreate.NotNow
        }
    }

    /**
     * Every alias that starts with `pv_pin_` or `pv_fp_`, and nothing else: not `pv_canary`, and not
     * `androidxBiometric`, which the fingerprint library makes on Android 10 (lock.md §5.7). Empty
     * when the Keystore is not answering, so that a failing Keystore makes the sweep delete nothing.
     */
    override fun aliases(): List<String> = try {
        open().aliases().toList().filter(::isShortcutAlias)
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Deletes [alias] when it is a shortcut's (`pv_pin_…` or `pv_fp_…`); errors are ignored and the
     * sweep tries again (lock.md §5.7, §14.3). Any other alias is left alone: the app never deletes
     * `pv_canary`, and the sweep cannot touch `androidxBiometric`.
     */
    override fun delete(alias: String) {
        if (!isShortcutAlias(alias)) return
        try {
            val keyStore = open()
            faults.throwIf(Row.DELETE) { KeyStoreException(KeystoreFaults.INJECTED) }
            keyStore.deleteEntry(alias)
        } catch (e: Exception) {
            // Ignored: a key that could not be deleted is named by no record and waits for the sweep.
        }
    }

    // ---------------------------------------------------------------------------------------------
    // For the two key classes of this package

    /**
     * lock.md §14.2: the key under [alias], or why not.
     *
     * - [Ks.Ok]: the key.
     * - [Ks.Gone] "invalidated": `getKey` threw `UnrecoverableKeyException` without a cause.
     * - [Ks.Gone] "not found": `getKey` gave null twice while the canary loaded in the same breath;
     *   a canary that was missing too was made again first, so a phone that lost every key of the
     *   app finds each alias GONE instead of "restart your phone" for ever (lock.md F15b).
     * - [Ks.Temporary]: everything else, a Keystore that cannot be loaded, a canary that cannot be
     *   made or does not load, and any other exception among them.
     */
    internal fun load(alias: String): Ks<SecretKey> {
        val keyStore = try {
            open()
        } catch (e: Exception) {
            return KeystoreFailureAdapter.classify(KeystoreErrors.Place.KEYSTORE_LOAD, e)
        }
        val fault = faults.takeOneOf(GET_KEY_FAULTS)
        var asked = 0
        return KeystoreErrors.load(
            alias = alias,
            getKey = { name ->
                val first = asked++ == 0
                when (fault) {
                    Row.GET_KEY_NULL -> if (name == alias) NO_KEY else lookup(keyStore, name)
                    Row.GET_KEY_NULL_ONCE -> if (first) NO_KEY else lookup(keyStore, name)
                    Row.GET_KEY_NOTHING_ANSWERS -> NO_KEY
                    Row.GET_KEY_UNRECOVERABLE -> lookup(keyStore, name, UnrecoverableKeyException(KeystoreFaults.INJECTED))
                    Row.GET_KEY_UNRECOVERABLE_CAUSE -> lookup(
                        keyStore,
                        name,
                        UnrecoverableKeyException(KeystoreFaults.INJECTED).apply { initCause(KeyStoreException(KeystoreFaults.INJECTED)) },
                    )
                    Row.GET_KEY_OTHER -> lookup(keyStore, name, ProviderException(KeystoreFaults.INJECTED))
                    else -> lookup(keyStore, name)
                }
            },
            makeCanary = { fault != Row.GET_KEY_NOTHING_ANSWERS && makeCanary() == KeyCreate.Ok },
        )
    }

    /**
     * Makes a key under [alias], which must have none yet, with [generate] and checks it (see
     * [make]). [KeyCreate.NotNow] without touching anything when the Keystore does not answer or
     * when [alias] already has a key: every shortcut key is made under the id of a new slot (lock.md
     * §4.3), so an alias that has a key belongs to a slot, and making it again would destroy that
     * slot's key.
     */
    internal fun makeNew(alias: String, forFingerprint: Boolean, generate: () -> SecretKey): KeyCreate {
        val keyStore = try {
            open()
        } catch (e: Exception) {
            return KeyCreate.NotNow
        }
        val there = lookup(keyStore, alias)
        if (there !is Lookup.Failed || there.failure != KeystoreFailure.NullKey) return KeyCreate.NotNow
        return make(alias, forFingerprint, generate)
    }

    /** One `KeyGenerator` of the Keystore for [algorithm] with [spec] (lock.md §4.2). */
    internal fun generate(algorithm: String, spec: KeyGenParameterSpec): SecretKey =
        KeyGenerator.getInstance(algorithm, ANDROID_KEYSTORE).run {
            init(spec)
            generateKey()
        }

    // ---------------------------------------------------------------------------------------------
    // For the debug commands of lock.md §15.3 (`status`, `drop-keys`), behind `LockDebug`

    /** Every alias the app has in the Keystore, `pv_canary` and the fingerprint library's own among them; empty when the Keystore is not answering. */
    fun everyAlias(): List<String> = try {
        keyStore().aliases().toList()
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * `drop-keys`: deletes every alias of the app, the canary included, as a reinstall that kept the
     * data and lost the keys would leave it (lock.md §15.3, check E10; architecture.md D-21). The
     * app itself never calls this, and in a build without the test hooks (`faults.testBuild` false:
     * a release build) it does nothing.
     */
    fun dropAllKeys() {
        if (!faults.testBuild) return
        try {
            val keyStore = keyStore()
            for (alias in keyStore.aliases().toList()) {
                try {
                    keyStore.deleteEntry(alias)
                } catch (e: Exception) {
                    // The next alias; what stays is listed by everyAlias().
                }
            }
        } catch (e: Exception) {
            // The Keystore is not answering: nothing was dropped.
        }
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * The row "Making a key" of lock.md §14.3 with the check of §4.5: [generate] makes the key under
     * [alias]; its `KeyInfo` is read at once; [KeyCreate.Ok] when it is in secure hardware or the
     * build allows software keys. In every other case the alias is deleted again:
     * [KeyCreate.NoSecureHardware] for a key in software that the build refuses,
     * [KeyCreate.NoneEnrolled] when making a fingerprint key threw
     * `InvalidAlgorithmParameterException` (no finger is enrolled, lock.md F9 step 4), and
     * [KeyCreate.NotNow] for any other exception, the 50,000-key limit of API 37 among them.
     */
    private fun make(alias: String, forFingerprint: Boolean, generate: () -> SecretKey): KeyCreate {
        val made = try {
            faults.throwIf(Row.CREATE_NONE_ENROLLED) { InvalidAlgorithmParameterException(KeystoreFaults.INJECTED) }
            val key = generate()
            faults.throwIf(Row.CREATE_OTHER) { ProviderException(KeystoreFaults.INJECTED) }
            val refused = faults.take(Row.CREATE_SOFTWARE) // judged as a release build judges a key in software
            KeystoreErrors.created(
                insideSecureHardware = !refused && insideSecureHardware(key),
                allowSoftware = !refused && allowSoftwareKeys,
            )
        } catch (e: Exception) {
            val noneEnrolled = forFingerprint && e is InvalidAlgorithmParameterException
            KeystoreErrors.createFailed(
                if (noneEnrolled) KeystoreErrors.CreateFailure.NO_BIOMETRIC_ENROLLED else KeystoreErrors.CreateFailure.OTHER,
            )
        }
        if (made != KeyCreate.Ok) remove(alias)
        return made
    }

    /**
     * Makes `pv_canary` (lock.md §4.2): an HMAC-SHA256 key of 256 bits for signing, with no user
     * authentication and no unlocked-device requirement. It is never used on a secret: it shows
     * whether this phone keeps keys in secure hardware, and whether the Keystore is answering.
     */
    private fun makeCanary(): KeyCreate = make(CANARY, forFingerprint = false) {
        generate(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
            KeyGenParameterSpec.Builder(CANARY, KeyProperties.PURPOSE_SIGN).setKeySize(KEY_BITS).build(),
        )
    }

    /**
     * lock.md §4.5, from the key's `KeyInfo`. API 31 and newer: the security level is the trusted
     * environment, StrongBox or "unknown, but secure". API 24 to 30: `isInsideSecureHardware`.
     * Emulators report software keys on API 24, 28 and 37 [L-bio case 7, L-old §3].
     */
    private fun insideSecureHardware(key: SecretKey): Boolean {
        val info = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE).getKeySpec(key, KeyInfo::class.java) as KeyInfo
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT,
                KeyProperties.SECURITY_LEVEL_STRONGBOX,
                KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE,
                -> true
                else -> false
            }
        } else {
            @Suppress("DEPRECATION")
            info.isInsideSecureHardware
        }
    }

    /** One `getKey`, as `KeystoreErrors.load` wants it described; [injected] is thrown in its place when a fault asks for it. */
    private fun lookup(keyStore: KeyStore, alias: String, injected: Exception? = null): Lookup<SecretKey> = try {
        if (injected != null) throw injected
        when (val key = keyStore.getKey(alias, null)) {
            null -> NO_KEY
            is SecretKey -> Lookup.Found(key)
            else -> Lookup.Failed(KeystoreFailure.Other) // not a secret key: never one of ours
        }
    } catch (e: Exception) {
        Lookup.Failed(KeystoreFailureAdapter.describe(e), e)
    }

    /** Deletes [alias] after a key could not be made or was refused; errors are ignored. */
    private fun remove(alias: String) {
        try {
            keyStore().deleteEntry(alias)
        } catch (e: Exception) {
            // Ignored: an alias that no record names is the sweep's (lock.md §5.7).
        }
    }

    /** `KeyStore.getInstance("AndroidKeyStore").load(null)`, the first row of lock.md §14.3. */
    private fun open(): KeyStore {
        faults.throwIf(Row.KEYSTORE_LOAD) { KeyStoreException(KeystoreFaults.INJECTED) }
        return keyStore()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        /** The Keystore's provider name. */
        internal const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** Every key of the lock has 256 bits (lock.md §4.2); a Keystore AES key without a size is 128-bit [V-P10]. */
        internal const val KEY_BITS = 256

        private const val CANARY = KeystoreErrors.CANARY_ALIAS
        private val NO_KEY = Lookup.Failed(KeystoreFailure.NullKey)
        private val GET_KEY_FAULTS = setOf(
            Row.GET_KEY_NULL,
            Row.GET_KEY_NULL_ONCE,
            Row.GET_KEY_NOTHING_ANSWERS,
            Row.GET_KEY_UNRECOVERABLE,
            Row.GET_KEY_UNRECOVERABLE_CAUSE,
            Row.GET_KEY_OTHER,
        )

        /** Whether [alias] is a PIN's or a fingerprint's: the only aliases the sweep lists and deletes (lock.md §5.7). */
        fun isShortcutAlias(alias: String): Boolean =
            alias.startsWith(RecordCodec.PIN_ALIAS_PREFIX) || alias.startsWith(RecordCodec.FINGERPRINT_ALIAS_PREFIX)
    }
}
