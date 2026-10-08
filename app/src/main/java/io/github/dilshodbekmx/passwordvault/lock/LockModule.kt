package io.github.dilshodbekmx.passwordvault.lock

import androidx.lifecycle.ProcessLifecycleOwner
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.core.crypto.CryptoFactory
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockEngine
import io.github.dilshodbekmx.passwordvault.data.DefaultVaultRepository
import io.github.dilshodbekmx.passwordvault.keystore.KeystoreAccess
import io.github.dilshodbekmx.passwordvault.keystore.LauncherRelay

/**
 * Builds [LockManager] (architecture.md §3.6; build plan 4.1): [DefaultLockManager] over one
 * [LockEngine] with the real ports. `AppGraph.lock` calls [create] once, on the main thread, when the
 * process starts.
 *
 * - **The ports.** The lock records are `Platform.records` under `Platform.lockRoot`
 *   (`<noBackupFilesDir>/lock`, lock.md §5.2); the clock and the random source are the platform's;
 *   the three Keystore ports are `AppGraph.keys`; the vault is `Data.port`; and the key derivation is
 *   `Platform.kdf`, the one Argon2id gate that the repository uses too (architecture.md §5.2 item 3).
 *   The engine runs its steps on `Dispatchers.IO` under the lock mutex.
 * - **The prompt.** `Keys.launcher` is a `LauncherRelay` that shows nothing by itself; the
 *   [PromptLauncher] over the activity's one `BiometricPrompt` is attached to it here, and
 *   `BiometricHost` hands it each activity's prompt (lock.md §9.2 rules 1 and 2).
 * - **The test hooks** (lock.md §15.3). In a build that carries the debug commands, which are the
 *   builds whose Keystore faults can be armed (`KeystoreFaults.testBuild`, architecture.md §7.2), a
 *   [LockDebugImpl] wraps the record files and the PIN key before the engine is built. A release
 *   build wraps nothing and has no [LockDebug].
 * - **The process.** The manager starts at once: it reads the records and the vaults (lock.md §5.11)
 *   and follows `ProcessLifecycleOwner` (§10.2).
 */
object LockModule {
    fun create(graph: AppGraph): LockManager {
        val platform = graph.platform
        val keys = graph.keys
        val data = graph.data
        val io = graph.dispatchers.io

        val access = keys.device as? KeystoreAccess
        val debug = access?.takeIf { it.faults.testBuild }?.let { LockDebugImpl(it, io) }

        val prompts = PromptLauncher(AndroidMain)
        (keys.launcher as? LauncherRelay)?.attach(prompts)

        val engine = LockEngine(
            records = debug?.records(platform.records) ?: platform.records,
            lockRoot = platform.lockRoot,
            clock = platform.clock,
            random = platform.random,
            pinKey = debug?.pinKey(keys.pin) ?: keys.pin,
            fingerprintKey = keys.fingerprint,
            deviceKeys = keys.device,
            vault = data.port,
            kdf = platform.kdf,
            aead = CryptoFactory.aead(),
            io = io,
        )
        val manager = DefaultLockManager(
            engine = engine,
            vault = data.repository,
            settings = graph.settings,
            clock = platform.clock,
            scope = graph.appScope,
            io = io,
            prompts = prompts,
            olderCopyAnswered = { (data.repository as? DefaultVaultRepository)?.olderCopyAnswered() },
            debug = debug,
        )
        debug?.attach(engine, manager)
        manager.start(ProcessLifecycleOwner.get().lifecycle)
        return manager
    }
}
