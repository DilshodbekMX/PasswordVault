package io.github.dilshodbekmx.passwordvault.keystore

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintKey
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintResult
import io.github.dilshodbekmx.passwordvault.core.lock.flow.KeyCreate
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PromptUse
import io.github.dilshodbekmx.passwordvault.core.lock.policy.KeystoreErrors
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.keystore.KeystoreFaults.Row
import io.github.dilshodbekmx.passwordvault.lock.BiometricLauncher
import io.github.dilshodbekmx.passwordvault.lock.PromptOutcome
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withContext

/**
 * The fingerprint's Keystore key, `K_fp`, and the prompt that authorises each use of it (lock.md
 * §4.2, §9.2, §14; architecture.md §3.4 `FingerprintKey`): an AES-256-GCM key that only a Class 3
 * biometric can use, once per authentication, and that dies when the phone's fingerprints change.
 * It wraps the vault key when fingerprint unlock is turned on ([seal]) and unwraps it at unlock
 * ([open]).
 *
 * It is the lab's `docs/lab/biometric-artifacts/BiometricVaultKey.kt` with the key loaded by lock.md
 * §14.2 ([KeystoreAccess.load]). The prompt itself is not here: [launcher] shows it (the real one
 * is the lock manager's, build plan 4.1), and this class only hands it the cipher it initialised
 * and uses the cipher that comes back.
 *
 * **Success is not an unlock** (lock.md I7, §9.2 rule 6). After `onAuthenticationSucceeded` the
 * cipher of the result, never a reference kept here, must still do its `doFinal`; after the phone's
 * own PIN was typed on the prompt's lockout screen it failed four times out of four [L-bio finding
 * b]. And only 32 bytes that then open the vault count, which is the lock's part.
 *
 * **Threads.** The Keystore calls of [seal] and [open] run on `dispatchers.io` and the launcher is
 * called on `dispatchers.main`, whatever the caller's dispatcher is (architecture.md §5.3). The
 * calls that do not suspend ([availability], [create], [probe]) are binder calls the caller runs
 * off the main thread.
 *
 * @param pinUsable whether a PIN is usable at the moment a prompt is shown: it picks the negative
 *   button of [PromptUse.UNLOCK], "Use PIN" or "Use master password" (lock.md §9.2 rule 5). The port
 *   does not carry it, so `KeystoreModule` reads it from the lock's state.
 */
class KeystoreFingerprintKey(
    private val access: KeystoreAccess,
    private val launcher: BiometricLauncher,
    context: Context,
    private val dispatchers: AppDispatchers,
    private val pinUsable: () -> Boolean,
) : FingerprintKey {
    private val appContext: Context = context.applicationContext

    /**
     * `BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)`, the same rule on every
     * Android version (lock.md §9.4): 0, -1, 1, 11, 12, -2, 15 and the rest. On Android 10 the
     * library makes a Keystore key (`androidxBiometric`) each time it is asked, so the lock asks once
     * per visit, off the main thread (lock.md §13.1). [CANNOT_ASK] when the library itself failed:
     * like any value the tables do not name, it leaves the key unprobed and the slot alone.
     */
    override fun availability(): Int = try {
        BiometricManager.from(appContext).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
    } catch (e: Exception) {
        CANNOT_ASK
    }

    /**
     * Makes the key under [alias] (`"pv_fp_" + slot id`) by lock.md §4.2 and checks it by §4.5:
     * AES-256, GCM, no padding, for encryption and decryption, user authentication required and
     * invalidated by a new enrolment. From API 30, `setUserAuthenticationParameters(0,
     * AUTH_BIOMETRIC_STRONG)`; on API 24 to 29 nothing more, where the default validity -1 means
     * "authenticate for every use" and only a fingerprint through a `CryptoObject` can do that.
     * Never StrongBox and never `setUnlockedDeviceRequired`, which adds a "Device locked" failure and
     * buys nothing for a key that needs a fresh fingerprint anyway [L-bio case 7].
     *
     * [KeyCreate.NoneEnrolled] when the platform refuses the specification with
     * `InvalidAlgorithmParameterException`: no finger is enrolled (lock.md F9 step 4).
     */
    override fun create(alias: String): KeyCreate {
        require(alias.startsWith(RecordCodec.FINGERPRINT_ALIAS_PREFIX)) {
            "a fingerprint alias starts with " + RecordCodec.FINGERPRINT_ALIAS_PREFIX
        }
        return access.makeNew(alias, forFingerprint = true) {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(KeystoreAccess.KEY_BITS)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            }
            access.generate(KeyProperties.KEY_ALGORITHM_AES, spec.build())
        }
    }

    /**
     * lock.md F3 step 2, before any prompt: the key by §14.2, then `Cipher.init` for decryption with
     * [nonce], classified by §14.3. `KeyPermanentlyInvalidatedException` is GONE: the phone's
     * fingerprints changed. The lock asks only while `availability()` is 0 or -1, because the
     * platform reports the key as permanently invalid whenever the phone's list of biometric ids is
     * empty at that moment.
     */
    override fun probe(alias: String, nonce: ByteArray): Ks<Unit> = when (val ready = cipherFor(alias, decryptWith(nonce))) {
        is Ks.Ok -> Ks.Ok(Unit)
        is Ks.Gone -> ready
        is Ks.Temporary -> ready
    }

    /**
     * lock.md F9 steps 5 to 7: `Cipher.init(ENCRYPT_MODE, key)` with no IV (a caller's IV is refused
     * [L-bio case 3, V-C7]), the prompt for [use], then `doFinal(vk)` on the cipher of the result:
     * [FingerprintResult.Ok] with the 48-byte wrap and `cipher.iv` (12 bytes). No AAD: this is the
     * call sequence the labs ran.
     */
    override suspend fun seal(alias: String, vk: ByteArray, use: PromptUse, launch: Int): FingerprintResult =
        behindPrompt(alias, { key -> init(Cipher.ENCRYPT_MODE, key) }, use, launch) { authorised ->
            val wrap = authorised.doFinal(vk)
            val iv = authorised.iv
            if (wrap.size != RecordCodec.CT_LENGTH || iv == null || iv.size != RecordCodec.NONCE_LENGTH) {
                FingerprintResult.Temporary(null) // not a wrap a record can hold: the key operation failed
            } else {
                FingerprintResult.Ok(wrap, iv)
            }
        }

    /**
     * lock.md F3 steps 2 to 4: `Cipher.init(DECRYPT_MODE, key, GCMParameterSpec(128, nonce))`, the
     * prompt for [use], then `doFinal(ct)` on the cipher of the result: [FingerprintResult.Ok] with
     * the 32 bytes of VK, which the caller wipes. A tag that fails, or a result of another size, is
     * GONE ("bad_wrap"): the stored wrap does not match the key.
     */
    override suspend fun open(alias: String, nonce: ByteArray, ct: ByteArray, use: PromptUse, launch: Int): FingerprintResult =
        behindPrompt(alias, decryptWith(nonce), use, launch) { authorised ->
            var plain = authorised.doFinal(ct)
            if (access.faults.take(Row.FINGERPRINT_WRONG_SIZE)) {
                plain.fill(0)
                plain = ByteArray(KeystoreErrors.VK_LENGTH / 2)
            }
            when (val vk = KeystoreErrors.fingerprintOutput(plain)) {
                is Ks.Ok -> FingerprintResult.Ok(vk.value, nonce.copyOf())
                is Ks.Gone -> FingerprintResult.Gone(vk.why)
                is Ks.Temporary -> FingerprintResult.Temporary(null)
            }
        }

    /** `cancelAuthentication()`: an open prompt ends with code 5, which is CLOSED. */
    override fun cancel() {
        launcher.cancel()
    }

    // ---------------------------------------------------------------------------------------------

    private fun decryptWith(nonce: ByteArray): Cipher.(SecretKey) -> Unit =
        { key -> init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce)) }

    /**
     * The key under [alias] by lock.md §14.2 and a cipher initialised with it by [init]; a failure of
     * either is classified by §14.3. `init` needs no authentication; `doFinal` always does [L-bio
     * case 2].
     */
    private fun cipherFor(alias: String, init: Cipher.(SecretKey) -> Unit): Ks<Cipher> {
        val key = when (val loaded = access.load(alias)) {
            is Ks.Ok -> loaded.value
            is Ks.Gone -> return loaded
            is Ks.Temporary -> return loaded
        }
        return try {
            access.faults.throwAtInit()
            Ks.Ok(Cipher.getInstance(TRANSFORMATION).apply { init(key) })
        } catch (e: Exception) {
            KeystoreFailureAdapter.classify(KeystoreErrors.Place.INIT, e)
        }
    }

    /**
     * One use of the key behind one prompt: the cipher is made before the prompt, so that a key that
     * is gone is found before the user is asked for a finger; the prompt's answer is read by the
     * table of lock.md §14.3 (`KeystoreErrors.respond`); and [operation] runs on the cipher that the
     * prompt handed back.
     */
    private suspend fun behindPrompt(
        alias: String,
        init: Cipher.(SecretKey) -> Unit,
        use: PromptUse,
        launch: Int,
        operation: (Cipher) -> FingerprintResult,
    ): FingerprintResult {
        val cipher = when (val ready = withContext(dispatchers.io) { cipherFor(alias, init) }) {
            is Ks.Ok -> ready.value
            is Ks.Gone -> return FingerprintResult.Gone(ready.why)
            is Ks.Temporary -> return FingerprintResult.Temporary(null)
        }
        val outcome = access.faults.takePromptError()?.let { PromptOutcome.Error(it) }
            ?: withContext(dispatchers.main) { launcher.authenticate(cipher, use, launch, pinUsable()) }
        val authorised: Cipher?
        val callback = when (outcome) {
            PromptOutcome.NotShown -> return FingerprintResult.NotShown
            is PromptOutcome.Error -> {
                authorised = null
                KeystoreErrors.Callback.Error(outcome.code)
            }
            is PromptOutcome.Succeeded -> {
                authorised = if (access.faults.take(Row.PROMPT_NO_CIPHER)) null else outcome.cipher
                KeystoreErrors.Callback.Succeeded(cipherPresent = authorised != null)
            }
        }
        val code = (outcome as? PromptOutcome.Error)?.code
        return when (KeystoreErrors.respond(callback, launchOutstanding = true)) {
            KeystoreErrors.Response.UseCipher ->
                if (authorised == null) FingerprintResult.Temporary(null) else keyOperation(authorised, operation)
            KeystoreErrors.Response.Closed -> if (code == null) FingerprintResult.Temporary(null) else FingerprintResult.Closed(code)
            // TEMPORARY with the prompt's code, or null after a success without a cipher. The last two
            // answers belong to callbacks this class never passes on (no launch outstanding, a finger
            // that is not recognised); if they ever came, nothing may open.
            is KeystoreErrors.Response.Temporary,
            KeystoreErrors.Response.Dropped,
            KeystoreErrors.Response.StaysOpen,
            -> FingerprintResult.Temporary(code)
        }
    }

    /**
     * The `doFinal` after a successful prompt, on `dispatchers.io`, classified by lock.md §14.3:
     * `AEADBadTagException` is GONE ("bad_wrap"), anything else TEMPORARY, such as
     * `IllegalBlockSizeException` caused by "Key user not authenticated". If the caller was cancelled
     * meanwhile (the visit ended while the prompt was open, lock.md F3 step 4), bytes that were
     * already made are wiped here, because nobody receives them.
     */
    private suspend fun keyOperation(authorised: Cipher, operation: (Cipher) -> FingerprintResult): FingerprintResult {
        var made: FingerprintResult? = null
        try {
            return withContext(dispatchers.io) {
                val result = try {
                    access.faults.throwIf(Row.FINGERPRINT_BAD_TAG) { AEADBadTagException(KeystoreFaults.INJECTED) }
                    access.faults.throwIf(Row.FINGERPRINT_OTHER) { IllegalBlockSizeException(KeystoreFaults.INJECTED) }
                    operation(authorised)
                } catch (e: Exception) {
                    when (val failed = KeystoreFailureAdapter.classify(KeystoreErrors.Place.FINGERPRINT_DO_FINAL, e)) {
                        is Ks.Gone -> FingerprintResult.Gone(failed.why)
                        else -> FingerprintResult.Temporary(null)
                    }
                }
                made = result
                result
            }
        } catch (e: CancellationException) {
            (made as? FingerprintResult.Ok)?.bytes?.fill(0)
            throw e
        }
    }

    companion object {
        /**
         * What [availability] answers when the library could not be asked at all. It is no code of
         * `BiometricManager`, so the lock treats it like every value its tables do not name:
         * fingerprint is not offered on this visit, the key is not probed and the slot stays.
         */
        const val CANNOT_ASK = Int.MIN_VALUE

        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}
