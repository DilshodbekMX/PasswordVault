package io.github.dilshodbekmx.passwordvault.core.lock.policy

import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.KeyCreate

/**
 * The error table of lock.md §14.3, with the key lookup of §14.2: pure functions over a small
 * description of what happened ([KeystoreFailure], a prompt's code, a result's length) and the place
 * where it happened, so that every row runs on the JVM (lock.md T13). `KeystoreFailureAdapter` and
 * the Keystore classes of `:app` (package 3.9) turn real exceptions into that description and call
 * these; the lock engine and the prompt launcher act on the answers.
 *
 * The rule behind every row (§14.1): **GONE only on the platform's own word**, every doubt is
 * TEMPORARY. GONE removes one slot and deletes its alias; TEMPORARY changes nothing and stores
 * nothing, except a refund. No row deletes or rewrites vault data (I1).
 *
 * Rows that need no decision here: `deleteEntry` failures are ignored and the sweep retries
 * (`DeviceKeys.delete`, lock.md §5.7); reading and writing a record are lock.md §5.3 and §5.9
 * (`FileRecordStore`: any read failure is UNREADABLE, any write failure is "not written");
 * Argon2id's `UnsatisfiedLinkError` and `OutOfMemoryError` are `GuardedArgon2id`'s (architecture.md
 * §3.2): the Java fallback or "not enough memory", never a wrong password.
 */
object KeystoreErrors {
    /** The alias of the canary key (lock.md §4.2). */
    const val CANARY_ALIAS = "pv_canary"

    /** [Ks.Gone.why]: the platform said the key was permanently invalidated (§14.2, §14.3). */
    const val GONE_INVALIDATED = "invalidated"

    /** [Ks.Gone.why]: the alias has no key while the canary loads (§14.2). */
    const val GONE_NOT_FOUND = "not found"

    /** [Ks.Gone.why]: the stored fingerprint wrap does not match its key (§14.3): the record's `off.reason` `bad_wrap`. */
    const val GONE_BAD_WRAP = "bad_wrap"

    /** The length of VK, the only fingerprint result that opens a vault (§14.3, I7). */
    const val VK_LENGTH = 32

    /** The places of lock.md §14.3 where using a key can fail. */
    enum class Place {
        /** `KeyStore.getInstance("AndroidKeyStore").load(null)`. */
        KEYSTORE_LOAD,

        /** The first `getKey` of a key (§14.2). A null there goes through the canary: [load]. */
        GET_KEY,

        /** A `getKey` of the canary, or the second `getKey` of the key, inside §14.2. */
        GET_KEY_AGAIN,

        /** `Mac.init` or `Cipher.init`, the fingerprint probe of F3 step 2 among them. */
        INIT,

        /** `Mac.doFinal` of the PIN key: no output, no information, so a refund. */
        MAC_DO_FINAL,

        /** The fingerprint `doFinal` after a successful prompt. */
        FINGERPRINT_DO_FINAL,
    }

    /**
     * [failure] at [place], as lock.md §14.3 classes it: [Ks.Gone] only for
     * - `UnrecoverableKeyException` without a cause at the first `getKey` ([GONE_INVALIDATED]),
     * - `KeyPermanentlyInvalidatedException` at `Mac.init` or `Cipher.init` ([GONE_INVALIDATED]),
     * - `AEADBadTagException` at the fingerprint `doFinal` ([GONE_BAD_WRAP]);
     *
     * [Ks.Temporary] with [cause] for everything else. A null from the first `getKey`
     * ([KeystoreFailure.NullKey]) is believed only when the canary loads in the same breath, which
     * [load] finds out; on its own it is a doubt, so TEMPORARY.
     */
    fun classify(place: Place, failure: KeystoreFailure, cause: Throwable? = null): Ks<Nothing> = when (place) {
        Place.GET_KEY ->
            if (failure == KeystoreFailure.UnrecoverableNoCause) Ks.Gone(GONE_INVALIDATED) else Ks.Temporary(cause)
        Place.INIT ->
            if (failure == KeystoreFailure.PermanentlyInvalidated) Ks.Gone(GONE_INVALIDATED) else Ks.Temporary(cause)
        Place.FINGERPRINT_DO_FINAL ->
            if (failure == KeystoreFailure.BadTag) Ks.Gone(GONE_BAD_WRAP) else Ks.Temporary(cause)
        Place.KEYSTORE_LOAD, Place.GET_KEY_AGAIN, Place.MAC_DO_FINAL -> Ks.Temporary(cause)
    }

    /** What one `KeyStore.getKey(alias, null)` gave, as the adapter saw it. */
    sealed interface Lookup<out K : Any> {
        /** A key. */
        data class Found<K : Any>(val key: K) : Lookup<K>

        /** It threw, described as [failure]; or it returned null, which is [KeystoreFailure.NullKey] with no [cause]. */
        data class Failed(val failure: KeystoreFailure, val cause: Throwable? = null) : Lookup<Nothing>
    }

    /**
     * lock.md §14.2: the key under [alias], or why not. [getKey] is one `KeyStore.getKey(alias,
     * null)`; [makeCanary] makes [CANARY_ALIAS] by lock.md §4.2 with the hardware check of §4.5 and
     * says whether a key that may be used was made.
     *
     * 1. The first lookup gives the key; or GONE for an invalidated key ([KeystoreFailure.UnrecoverableNoCause]);
     *    or TEMPORARY for any other failure.
     * 2. Null is believed only when the canary loads in the same breath. On API 24 to 30 `getKey`
     *    gives null for any Keystore error, so the canary is asked: a failure is TEMPORARY. When it
     *    is missing too, it is made and asked again; if it cannot be made, or does not load, the
     *    Keystore is not answering: TEMPORARY.
     * 3. The key is asked once more: a key is OK (it was a passing null), null is GONE
     *    ([GONE_NOT_FOUND]), a failure TEMPORARY.
     *
     * So a phone that lost every key of the app, while its Keystore works, finds each alias GONE
     * instead of "restart your phone" at every unlock (lock.md F15b, architecture.md D-21).
     * `KeyStore.containsAlias` is never asked: it is false on any error [V-C6].
     */
    fun <K : Any> load(alias: String, getKey: (alias: String) -> Lookup<K>, makeCanary: () -> Boolean): Ks<K> {
        when (val first = getKey(alias)) {
            is Lookup.Found -> return Ks.Ok(first.key)
            is Lookup.Failed -> if (first.failure != KeystoreFailure.NullKey) return classify(Place.GET_KEY, first.failure, first.cause)
        }
        when (val canary = getKey(CANARY_ALIAS)) {
            is Lookup.Found -> Unit
            is Lookup.Failed -> {
                if (canary.failure != KeystoreFailure.NullKey) return classify(Place.GET_KEY_AGAIN, canary.failure, canary.cause)
                // Nothing answers, or every key of this app is gone: can the Keystore make and load a key?
                if (!makeCanary()) return Ks.Temporary(null)
                when (val made = getKey(CANARY_ALIAS)) {
                    is Lookup.Found -> Unit
                    is Lookup.Failed -> return if (made.failure == KeystoreFailure.NullKey) {
                        Ks.Temporary(null)
                    } else {
                        classify(Place.GET_KEY_AGAIN, made.failure, made.cause)
                    }
                }
            }
        }
        return when (val again = getKey(alias)) {
            is Lookup.Found -> Ks.Ok(again.key)
            is Lookup.Failed -> if (again.failure == KeystoreFailure.NullKey) {
                Ks.Gone(GONE_NOT_FOUND)
            } else {
                classify(Place.GET_KEY_AGAIN, again.failure, again.cause)
            }
        }
    }

    /**
     * The fingerprint `doFinal` gave [output] (lock.md §14.3): exactly [VK_LENGTH] bytes are VK, to
     * open the vault with (only a vault that then opens counts, I7); any other length is a wrap that
     * does not match the key: GONE ([GONE_BAD_WRAP]). A refused [output] is wiped.
     */
    fun fingerprintOutput(output: ByteArray): Ks<ByteArray> {
        if (output.size == VK_LENGTH) return Ks.Ok(output)
        output.fill(0)
        return Ks.Gone(GONE_BAD_WRAP)
    }

    /** A callback of the one `BiometricPrompt` (lock.md §9.2), as the launcher saw it. */
    sealed interface Callback {
        /** `onAuthenticationSucceeded`; [cipherPresent]: `result.cryptoObject?.cipher` is not null. */
        data class Succeeded(val cipherPresent: Boolean) : Callback

        /** `onAuthenticationError(code)`. */
        data class Error(val code: Int) : Callback

        /** `onAuthenticationFailed`: a finger that is not recognised. */
        data object NotRecognised : Callback
    }

    /** What the lock does with a prompt [Callback] (lock.md §9.2 rules 6 and 8, §14.3). */
    sealed interface Response {
        /** No launch is outstanding: the callback is dropped (§9.2 rule 8). */
        data object Dropped : Response

        /** The prompt stays open by itself; nothing changes. */
        data object StaysOpen : Response

        /** Take `result.cryptoObject.cipher` and call `doFinal` on it; never a cipher the app kept (§9.2 rule 6). */
        data object UseCipher : Response

        /** CLOSED: the user or the system closed the prompt; the pad (or the screen behind) shows. */
        data object Closed : Response

        /**
         * TEMPORARY: show [line]; with [hidesKey], the fingerprint key is hidden until the next visit
         * (lock.md §13.3). Nothing is removed and nothing stored.
         */
        data class Temporary(val line: FingerprintLine, val hidesKey: Boolean) : Response
    }

    /**
     * What [callback] means while a launch is or is not outstanding ([launchOutstanding]). A
     * callback with no launch outstanding is dropped, whatever it says (§9.2 rule 8): with
     * 1.4.0-alpha07 the code-10 error of a prompt the system closed arrived only when the app came
     * back to the front, just when the next visit wants its own prompt [L-bio case 4].
     */
    fun respond(callback: Callback, launchOutstanding: Boolean): Response {
        if (!launchOutstanding) return Response.Dropped
        return when (callback) {
            Callback.NotRecognised -> Response.StaysOpen
            is Callback.Succeeded ->
                if (callback.cipherPresent) Response.UseCipher else Response.Temporary(FingerprintLine.DidNotOpen, hidesKey = false)
            is Callback.Error -> promptError(callback.code)
        }
    }

    /**
     * `onAuthenticationError(code)` (lock.md §14.3, §9.3): 10 (user cancelled), 13 (negative button)
     * and 5 (cancelled) are CLOSED; everything else is TEMPORARY, with its line of §13.3: 7 and 9
     * (lockout) [FingerprintLine.Locked]; 1 (hardware unavailable: fingerprint switched off for apps)
     * [FingerprintLine.NotForApps]; any other code, 11, 12, 2, 3, 4, 8, 14 and 15 among them,
     * [FingerprintLine.CannotCheck] with the key hidden until the next visit. If the key really died,
     * the next `Cipher.init` says so.
     */
    fun promptError(code: Int): Response = when (code) {
        ERROR_CANCELED, ERROR_USER_CANCELED, ERROR_NEGATIVE_BUTTON -> Response.Closed
        ERROR_LOCKOUT, ERROR_LOCKOUT_PERMANENT -> Response.Temporary(FingerprintLine.Locked, hidesKey = false)
        ERROR_HW_UNAVAILABLE -> Response.Temporary(FingerprintLine.NotForApps, hidesKey = false)
        else -> Response.Temporary(FingerprintLine.CannotCheck, hidesKey = true)
    }

    /**
     * The status line of a TEMPORARY fingerprint result `FingerprintResult.Temporary(code)`: null
     * is a prompt that succeeded and then did not open (§9.3, [FingerprintLine.DidNotOpen]); a code
     * is [promptError]'s line.
     */
    fun temporaryLine(code: Int?): FingerprintLine {
        if (code == null) return FingerprintLine.DidNotOpen
        val response = promptError(code)
        return if (response is Response.Temporary) response.line else FingerprintLine.CannotCheck
    }

    /**
     * Whether the TEMPORARY result `FingerprintResult.Temporary(code)` hides the fingerprint key until
     * the next visit: only [FingerprintLine.CannotCheck]'s codes do; a prompt that succeeded and did
     * not open (null) does not, because a new prompt works (§9.3).
     */
    fun hidesKey(code: Int?): Boolean {
        if (code == null) return false
        val response = promptError(code)
        return response is Response.Temporary && response.hidesKey
    }

    /**
     * Whether a fingerprint key may be probed and prompted for, by `canAuthenticate(BIOMETRIC_STRONG)`
     * at the start of a visit (lock.md §13.1, §14.3): 0 (success) or -1 (unknown) only. With any
     * other value the key is not probed and its slot stays; [unavailableLine] says why.
     */
    fun mayProbe(canAuthenticate: Int): Boolean = canAuthenticate == BIOMETRIC_SUCCESS || canAuthenticate == BIOMETRIC_STATUS_UNKNOWN

    /**
     * The line of lock.md §13.3 while fingerprint unlock is on and `canAuthenticate` gives
     * [canAuthenticate]: null for 0 and -1; [FingerprintLine.NotForApps] for 1;
     * [FingerprintLine.NoneSaved] for 11; [FingerprintLine.UpdateNeeded] for 15; and
     * [FingerprintLine.CannotCheck] for any other value (12, -2 and the rest).
     */
    fun unavailableLine(canAuthenticate: Int): FingerprintLine? = when (canAuthenticate) {
        BIOMETRIC_SUCCESS, BIOMETRIC_STATUS_UNKNOWN -> null
        BIOMETRIC_ERROR_HW_UNAVAILABLE -> FingerprintLine.NotForApps
        BIOMETRIC_ERROR_NONE_ENROLLED -> FingerprintLine.NoneSaved
        BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> FingerprintLine.UpdateNeeded
        else -> FingerprintLine.CannotCheck
    }

    /** Opening the PIN wraps with `KEK_pin` (lock.md §14.3, §6.3 E.4). */
    enum class PinWraps {
        /** Every tag failed: a wrong PIN, counted (§6.3 E.5). */
        WRONG,

        /** One opened: the PIN is right; a wrap whose tag failed is dropped, because it can never open (§6.3 E.6). */
        RIGHT,
    }

    /** Of the PIN wraps, [opened] opened their tags: WRONG when none did. */
    fun pinWraps(opened: Int): PinWraps {
        require(opened >= 0) { "a negative count" }
        return if (opened == 0) PinWraps.WRONG else PinWraps.RIGHT
    }

    /** Why making a key failed (lock.md §14.3, "Making a key"), as the adapter saw it. */
    enum class CreateFailure {
        /** `InvalidAlgorithmParameterException` because no biometric is enrolled [L-bio case 12]. */
        NO_BIOMETRIC_ENROLLED,

        /** Any other exception, the 50,000-key limit of API 37 among them. */
        OTHER,
    }

    /**
     * Making a key failed with [failure] (lock.md §14.3): NOT NOW, and the half-made alias is
     * deleted; for a fingerprint key with no biometric enrolled, "Add a fingerprint in your phone's
     * settings first." Nothing else changes.
     */
    fun createFailed(failure: CreateFailure): KeyCreate = when (failure) {
        CreateFailure.NO_BIOMETRIC_ENROLLED -> KeyCreate.NoneEnrolled
        CreateFailure.OTHER -> KeyCreate.NotNow
    }

    /**
     * A key was made; [insideSecureHardware] is what its `KeyInfo` says (lock.md §4.5). A key in
     * software is refused for good unless the build allows software keys ([allowSoftware]: the
     * `debug` and `staging` builds, `BuildConfig.ALLOW_SOFTWARE_KEYSTORE`); the caller deletes it
     * then (F18).
     */
    fun created(insideSecureHardware: Boolean, allowSoftware: Boolean): KeyCreate =
        if (insideSecureHardware || allowSoftware) KeyCreate.Ok else KeyCreate.NoSecureHardware

    // The codes of androidx.biometric (BiometricPrompt and BiometricManager), which :core cannot name.

    /** `BiometricPrompt.ERROR_HW_UNAVAILABLE`, also what 1.4.0-alpha07 reports when fingerprint is switched off for apps [L-bio finding d]. */
    const val ERROR_HW_UNAVAILABLE = 1

    /** `BiometricPrompt.ERROR_CANCELED`: `cancelAuthentication()`. */
    const val ERROR_CANCELED = 5

    /** `BiometricPrompt.ERROR_LOCKOUT`. */
    const val ERROR_LOCKOUT = 7

    /** `BiometricPrompt.ERROR_LOCKOUT_PERMANENT`. */
    const val ERROR_LOCKOUT_PERMANENT = 9

    /** `BiometricPrompt.ERROR_USER_CANCELED`: Back, the X, Home. */
    const val ERROR_USER_CANCELED = 10

    /** `BiometricPrompt.ERROR_NEGATIVE_BUTTON`. */
    const val ERROR_NEGATIVE_BUTTON = 13

    /** `BiometricManager.BIOMETRIC_SUCCESS`. */
    const val BIOMETRIC_SUCCESS = 0

    /** `BiometricManager.BIOMETRIC_STATUS_UNKNOWN`. */
    const val BIOMETRIC_STATUS_UNKNOWN = -1

    /** `BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE`: fingerprint may not be used in apps now. */
    const val BIOMETRIC_ERROR_HW_UNAVAILABLE = 1

    /** `BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED`: no finger saved, or no screen lock. */
    const val BIOMETRIC_ERROR_NONE_ENROLLED = 11

    /** `BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE`. */
    const val BIOMETRIC_ERROR_NO_HARDWARE = 12

    /** `BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED`. */
    const val BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED = 15

    /** `BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED`. */
    const val BIOMETRIC_ERROR_UNSUPPORTED = -2
}
