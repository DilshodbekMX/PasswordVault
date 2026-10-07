package io.github.dilshodbekmx.passwordvault.keystore

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.dilshodbekmx.passwordvault.core.lock.flow.KeyCreate
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinKey
import io.github.dilshodbekmx.passwordvault.core.lock.policy.KeystoreErrors
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.keystore.KeystoreFaults.Row
import javax.crypto.Mac

/**
 * The PIN's Keystore key, `K_pin` (lock.md §4.2; architecture.md §3.4 `PinKey`): an HMAC-SHA256 key
 * that never leaves the secure hardware, so that every PIN guess must pass through this phone
 * (requirement 2 of lock.md). The only thing it ever does is `KEK_pin = HMAC-SHA256(K_pin, pin_msg)`.
 *
 * Both calls are binder calls: the lock runs them on `Dispatchers.IO`, [create] under the lock
 * mutex (architecture.md §5.2, §5.3).
 */
class KeystorePinKey(private val access: KeystoreAccess) : PinKey {
    /**
     * Makes the key under [alias] (`"pv_pin_" + slot id`) and checks it by lock.md §4.5:
     * `KeyGenParameterSpec.Builder(alias, PURPOSE_SIGN).setKeySize(256)` for
     * `KEY_ALGORITHM_HMAC_SHA256`, with no user authentication, so that the PIN works after a reboot
     * and after the phone's fingerprints change. From API 35 it is usable only while the phone is
     * unlocked (`setUnlockedDeviceRequired`), which is where the platform recommends that flag.
     * Never StrongBox, a usage count or a validity period.
     */
    override fun create(alias: String): KeyCreate {
        require(alias.startsWith(RecordCodec.PIN_ALIAS_PREFIX)) { "a PIN alias starts with " + RecordCodec.PIN_ALIAS_PREFIX }
        return access.makeNew(alias, forFingerprint = false) {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).setKeySize(KeystoreAccess.KEY_BITS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) spec.setUnlockedDeviceRequired(true)
            access.generate(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, spec.build())
        }
    }

    /**
     * `HMAC-SHA256(K_pin, message)`: the 32 bytes of `KEK_pin` for `pin_msg` (lock.md §4.3), which
     * the caller wipes. Classified by lock.md §14: the key is loaded by §14.2 ([KeystoreAccess.load]);
     * `Mac.init` is GONE only for `KeyPermanentlyInvalidatedException` and TEMPORARY for anything
     * else, "Device locked" on API 35 and newer among them; a failing `doFinal` is TEMPORARY, with
     * no output and no information, so the try is refunded.
     */
    override fun hmac(alias: String, message: ByteArray): Ks<ByteArray> {
        val key = when (val loaded = access.load(alias)) {
            is Ks.Ok -> loaded.value
            is Ks.Gone -> return loaded
            is Ks.Temporary -> return loaded
        }
        val mac = try {
            access.faults.throwAtInit()
            Mac.getInstance(HMAC_SHA256).apply { init(key) }
        } catch (e: Exception) {
            return KeystoreFailureAdapter.classify(KeystoreErrors.Place.INIT, e)
        }
        val kek: ByteArray? = try {
            access.faults.throwIf(Row.MAC_DO_FINAL) { IllegalStateException(KeystoreFaults.INJECTED) }
            mac.doFinal(message)
        } catch (e: Exception) {
            return KeystoreFailureAdapter.classify(KeystoreErrors.Place.MAC_DO_FINAL, e)
        }
        if (kek == null || kek.size != KEK_BYTES) {
            // Not an HMAC-SHA256 result: nothing to open a wrap with, and nothing the platform said about the key.
            kek?.fill(0)
            return Ks.Temporary(null)
        }
        return Ks.Ok(kek)
    }

    internal companion object {
        /** The platform's name of the MAC; the Keystore's own implementation is chosen by the key at `init`. */
        const val HMAC_SHA256 = "HmacSHA256"

        /** `KEK_pin` is used directly as an AES-256 key (lock.md §4.2). */
        const val KEK_BYTES = 32
    }
}
