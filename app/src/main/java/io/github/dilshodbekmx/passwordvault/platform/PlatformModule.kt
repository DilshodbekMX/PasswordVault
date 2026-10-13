package io.github.dilshodbekmx.passwordvault.platform

import android.content.Context
import android.os.Build
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.Platform
import io.github.dilshodbekmx.passwordvault.core.crypto.Argon2id
import io.github.dilshodbekmx.passwordvault.core.crypto.CryptoFactory
import java.io.File

/**
 * Builds [Platform] (architecture.md §3.6, build plan 2.7): the Android implementations of the
 * `:core` ports and the two roots. `AppGraph.platform` calls [create] once per process.
 *
 * - `files` and `records` are one [OsFiles].
 * - `clock` is [AndroidClock]; `random` one [SystemRandom].
 * - `kdf` is the one Argon2id gate of the process (architecture.md §5.2 item 3): `GatedMasterKdf`
 *   on `Dispatchers.Default` over [argon2id], the `GuardedArgon2id` with argon2kt as its native
 *   implementation and Bouncy Castle as its fallback. argon2kt is loaded at the first derivation
 *   ([NativeArgon2id.loadedOnFirstUse]), behind the gate and off the main thread, rather than here:
 *   [create] runs when the lock is built at process start, on the main thread.
 * - `vaultsDir` is `<filesDir>/vaults` (architecture.md §4.7) and `lockRoot` is
 *   `<noBackupFilesDir>/lock` (lock.md §5.2): the lock records are never in a backup or a
 *   device-to-device transfer, whatever the backup rules say (build check R-3, a device test).
 *   Neither is made here: the vault store makes `vaults/` and `OsFiles.replace` makes a vault's
 *   lock directory when its first record is written.
 */
object PlatformModule {
    private const val MIB = 1024L * 1024L

    fun create(graph: AppGraph): Platform {
        val files = OsFiles()
        return Platform(
            files = files,
            records = files,
            clock = AndroidClock,
            random = SystemRandom(),
            kdf = CryptoFactory.masterKdf(argon2id(NativeArgon2id.loadedOnFirstUse()), graph.dispatchers.default),
            vaultsDir = vaultsDir(graph.app),
            lockRoot = lockRoot(graph.app),
        )
    }

    /** `<filesDir>/vaults` (architecture.md §4.7). */
    internal fun vaultsDir(context: Context): String = File(context.filesDir, "vaults").path

    /** `<noBackupFilesDir>/lock` (lock.md §5.2, R-3). */
    internal fun lockRoot(context: Context): String = File(context.noBackupFilesDir, "lock").path

    /**
     * The app's `GuardedArgon2id` (architecture.md §3.2, [V-C2]): [native] while it works, else
     * [fallback] when the free heap, `maxMemory − totalMemory + freeMemory` of [runtime], is at least
     * [fallbackNeeds] for [sdk], also when it is so only after a garbage collection (`Runtime.gc()`,
     * which ART runs when asked; `System.gc()` may skip it), else `KdfUnavailable`. The collection
     * runs only when the heap looks too small: on Android 7 the 134 MiB work area of the derivation
     * before counts as used until it is collected (build-plan.md §12, decision 22; lock.md E16).
     */
    internal fun argon2id(
        native: Argon2id?,
        fallback: Argon2id = CryptoFactory.bouncyCastleArgon2id(),
        sdk: Int = Build.VERSION.SDK_INT,
        runtime: Runtime = Runtime.getRuntime(),
    ): Argon2id = CryptoFactory.guardedArgon2id(
        native = native,
        fallback = fallback,
        freeHeap = { runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory() },
        fallbackNeeds = fallbackNeeds(sdk),
        collect = { runtime.gc() },
    )

    /**
     * The free Java heap the Bouncy Castle fallback needs for one derivation with `Argon2Params.V1`:
     * 160 MiB below API 28 (about 134 MiB was measured on API 24) and 96 MiB from API 28 (about
     * 70 MiB measured) [L-old §2]. API 25 to 27 were not measured and take the larger number.
     */
    internal fun fallbackNeeds(sdk: Int): Long = if (sdk >= Build.VERSION_CODES.P) 96L * MIB else 160L * MIB
}
