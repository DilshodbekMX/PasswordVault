package io.github.dilshodbekmx.passwordvault.core.lock.flow

import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks

/**
 * What a fingerprint prompt is for: the four rows of lock.md §9.2 rule 5, which give its title and
 * its negative button.
 */
enum class PromptUse {
    /** "Open PasswordVault", with "Use PIN" (or "Use master password" when no PIN is usable). */
    UNLOCK,

    /** F9: "Turn on fingerprint unlock", with "Cancel". */
    TURN_ON,

    /** F8 step 7: "Keep fingerprint unlock", with "Not now". */
    KEEP_AFTER_CHANGE,

    /** F11 step 3, when no PIN exists: "Confirm with your fingerprint", with "Cancel". */
    CONFIRM_REPLACEMENT,
}

/**
 * The fingerprint's Keystore key, `K_fp`, and the one prompt that authorises it (lock.md §4.2,
 * §9.2; architecture.md §3.4). `KeystoreFingerprintKey` (package 3.9) is the implementation; it
 * shows the prompt through `BiometricLauncher`.
 */
interface FingerprintKey {
    /**
     * `BiometricManager.canAuthenticate(BIOMETRIC_STRONG)`: 0, -1, 1, 11, 12, -2, 15 and the rest
     * (lock.md §9.4). Asked once per visit, off the main thread: on Android 10 every call makes a
     * Keystore key (lock.md §13.1).
     */
    fun availability(): Int

    /** Makes the key under [alias] (`"pv_fp_" + slot id`) as lock.md §4.2 specifies and checks it by §4.5. */
    fun create(alias: String): KeyCreate

    /**
     * `Cipher.init` for decryption with [nonce], before any prompt (lock.md F3 step 2), classified
     * by §14.3. Only while fingerprint is usable (`availability()` 0 or -1).
     */
    fun probe(alias: String, nonce: ByteArray): Ks<Unit>

    /** Encrypts [vk] under the key behind a prompt for [use]; [launch] is the lock's number for this launch (§9.2 rule 8). */
    suspend fun seal(alias: String, vk: ByteArray, use: PromptUse, launch: Int): FingerprintResult

    /** Decrypts the wrap [ct] made with [nonce] behind a prompt for [use]; [launch] as for [seal]. */
    suspend fun open(alias: String, nonce: ByteArray, ct: ByteArray, use: PromptUse, launch: Int): FingerprintResult

    /** Cancels an open prompt (a lock, the end of a visit). */
    fun cancel()
}

/** What one prompt came to (architecture.md §3.4; the classes of lock.md §14.1). */
sealed interface FingerprintResult {
    /**
     * The key operation after a successful prompt gave [bytes]: for `open` the 32 bytes of VK, for
     * `seal` the 48-byte wrap. [nonce] is the cipher's IV (12 bytes) for `seal`, the nonce it was
     * given for `open`. Only bytes that then open the vault count as an unlock (lock.md I7). The
     * caller wipes [bytes].
     */
    class Ok(val bytes: ByteArray, val nonce: ByteArray) : FingerprintResult

    /** CLOSED: the user or the system closed the prompt; [code] is 10, 13 or 5 (lock.md §14.3). The pad shows. */
    data class Closed(val code: Int) : FingerprintResult

    /** GONE: the key was permanently invalidated, is not there any more, or the wrap does not match it ("bad_wrap"). */
    data class Gone(val why: String) : FingerprintResult

    /**
     * TEMPORARY: [code] is the prompt's error code (7 and 9 lockout, 1 not allowed for apps, and the
     * rest of §14.3), or null when the prompt succeeded and the key operation then failed, or the
     * prompt's cipher was missing (lock.md §9.2 rule 6, §9.3).
     *
     * `Temporary(null)` is one result (build-plan.md §12, decision 3): it also covers a key that
     * could not be made ready before the prompt (its `Cipher.init` failed in a way that is not GONE)
     * and a launcher that threw before the prompt could answer. Whichever it was, nothing opens and
     * nothing is removed; the screen says that the fingerprint did not open the app (lock.md §13.3,
     * "Fingerprint did not open the app this time."), and the next tap probes the key again.
     */
    data class Temporary(val code: Int?) : FingerprintResult

    /** The prompt could not be shown now (the activity is not resumed, lock.md §9.2 rule 3); the lock tries again at the next resume. */
    data object NotShown : FingerprintResult
}
