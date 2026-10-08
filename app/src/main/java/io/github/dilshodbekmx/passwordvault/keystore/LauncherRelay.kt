package io.github.dilshodbekmx.passwordvault.keystore

import io.github.dilshodbekmx.passwordvault.core.lock.flow.PromptUse
import io.github.dilshodbekmx.passwordvault.lock.BiometricLauncher
import io.github.dilshodbekmx.passwordvault.lock.PromptOutcome
import javax.crypto.Cipher

/**
 * `Keys.launcher` of the app: the [BiometricLauncher] that [KeystoreFingerprintKey] calls, made
 * with the Keystore classes when the process starts, before any activity and before the lock
 * manager exists. It shows nothing by itself. The lock manager package (build plan 4.1) hands it
 * the launcher over the activity's one `BiometricPrompt` with [attach], once, from the code that
 * builds the lock (`graph.keys.launcher as LauncherRelay`); from then on every call goes there.
 *
 * Until a launcher is attached, a prompt cannot be shown ([PromptOutcome.NotShown], which the lock
 * answers by trying again at the next resume, lock.md §9.2 rule 3) and there is nothing to cancel.
 * That is the state of this package's own branch, where no prompt exists yet.
 *
 * The attached launcher is expected to live as long as the process and to follow the activity
 * itself (lock.md §9.2 rules 1 and 2): a call that is waiting for its callback stays with the
 * launcher it was made on.
 */
class LauncherRelay : BiometricLauncher {
    @Volatile private var target: BiometricLauncher? = null

    /** Whether a launcher is attached. */
    val attached: Boolean get() = target != null

    /** Every later call goes to [launcher]; null detaches, and prompts are not shown again. */
    fun attach(launcher: BiometricLauncher?) {
        require(launcher !== this) { "the relay cannot be its own launcher" }
        target = launcher
    }

    override suspend fun authenticate(cipher: Cipher, use: PromptUse, launch: Int, pinUsable: Boolean): PromptOutcome =
        target?.authenticate(cipher, use, launch, pinUsable) ?: PromptOutcome.NotShown

    override fun cancel() {
        target?.cancel()
    }
}
