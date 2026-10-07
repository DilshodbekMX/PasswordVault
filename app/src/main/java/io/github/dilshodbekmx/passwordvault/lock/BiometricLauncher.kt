package io.github.dilshodbekmx.passwordvault.lock

import io.github.dilshodbekmx.passwordvault.core.lock.flow.PromptUse
import javax.crypto.Cipher

/**
 * Shows the one `BiometricPrompt` of the activity (lock.md §9.2; architecture.md §3.5). The real one
 * (build plan 4.1) is built in `onCreate` by `BiometricHost`; `KeystoreFingerprintKey` (3.9) calls
 * it with the cipher it initialised, and `FakeLauncher` stands in for it in tests.
 */
interface BiometricLauncher {
    /**
     * Shows the prompt for [use] with [cipher] as its `CryptoObject` and suspends until its callback.
     * [launch] is the lock's number for this launch (lock.md §9.2 rule 8); [pinUsable] picks the
     * negative button of [PromptUse.UNLOCK]: "Use PIN", or "Use master password" when no PIN is
     * usable (§9.2 rule 5).
     */
    suspend fun authenticate(cipher: Cipher, use: PromptUse, launch: Int, pinUsable: Boolean): PromptOutcome

    /** `cancelAuthentication()`: an open prompt ends with code 5. */
    fun cancel()
}

/** What one prompt came to, before any key operation (lock.md §9.2, §9.3). */
sealed interface PromptOutcome {
    /**
     * `onAuthenticationSucceeded`: [cipher] is `result.cryptoObject?.cipher`, never a reference the
     * app kept; null when the library gave none, which is TEMPORARY (lock.md §9.2 rule 6). Success is
     * not an unlock: only bytes that then open the vault count (I7).
     */
    class Succeeded(val cipher: Cipher?) : PromptOutcome

    /** `onAuthenticationError` with [code]: 10, 13 and 5 close the prompt; 7 and 9 are lockouts; 1 is "not for apps" (lock.md §14.3). */
    data class Error(val code: Int) : PromptOutcome

    /** The prompt could not be shown: the activity is not resumed (lock.md §9.2 rule 3); the lock tries again at the next resume. */
    data object NotShown : PromptOutcome
}
