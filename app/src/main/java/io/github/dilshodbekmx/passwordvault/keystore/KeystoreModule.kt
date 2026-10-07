package io.github.dilshodbekmx.passwordvault.keystore

import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.BuildConfig
import io.github.dilshodbekmx.passwordvault.Keys
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.lock.LockUiState

/**
 * Builds [Keys] (architecture.md §3.6; build plan 3.9): the Android Keystore behind the lock's
 * three ports, and the launcher of the fingerprint prompt. `AppGraph.keys` calls [create] once per
 * process, at its first use.
 *
 * - `device` is one [KeystoreAccess], which refuses software keys exactly when
 *   `BuildConfig.ALLOW_SOFTWARE_KEYSTORE` is false: a release build (lock.md §4.5, build check R-2).
 * - `pin` is [KeystorePinKey] and `fingerprint` is [KeystoreFingerprintKey], both over that access,
 *   so the three share its faults (`KeystoreAccess.faults`, the `ks-fault` command of lock.md §15.3),
 *   which a release build cannot arm.
 * - `launcher` is a [LauncherRelay]: no prompt exists when the keys are built. The lock manager
 *   package (build plan 4.1) attaches the launcher over the activity's one `BiometricPrompt` to it;
 *   until then a prompt is "not shown".
 *
 * Nothing here calls the Keystore: the first call is made by whoever uses a port, off the main
 * thread (architecture.md §5.3).
 */
object KeystoreModule {
    fun create(graph: AppGraph): Keys {
        // The builds that allow software keys are the builds with the debug commands: debug, staging
        // and backupCheck (architecture.md §7.2). Only there can a fault be armed or the keys dropped.
        val testBuild = BuildConfig.ALLOW_SOFTWARE_KEYSTORE
        val access = KeystoreAccess(allowSoftwareKeys = testBuild, faults = KeystoreFaults(testBuild))
        val launcher = LauncherRelay()
        return Keys(
            device = access,
            pin = KeystorePinKey(access),
            fingerprint = KeystoreFingerprintKey(
                access = access,
                launcher = launcher,
                context = graph.app,
                dispatchers = graph.dispatchers,
                // Read when a prompt is shown, long after the lock was built from these keys.
                pinUsable = { padIsShown(graph.lock.state.value) },
            ),
            launcher = launcher,
        )
    }

    /**
     * Whether a PIN is usable while [state] is shown, for the negative button of an unlock prompt
     * (lock.md §9.2 rule 5): "Use PIN" on the pad, which `EntryPolicy` shows exactly when the PIN is
     * usable (lock.md §13.1 row 5), and "Use master password" on the fingerprint-only screen. The
     * other prompts (turn on, keep after a change, confirm a replacement) have a button of their own.
     */
    internal fun padIsShown(state: LockUiState): Boolean = state is LockUiState.Locked && state.screen is LockScreen.Pad
}
