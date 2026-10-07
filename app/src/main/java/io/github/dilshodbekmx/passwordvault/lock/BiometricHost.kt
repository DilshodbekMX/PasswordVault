package io.github.dilshodbekmx.passwordvault.lock

import androidx.fragment.app.FragmentActivity
import io.github.dilshodbekmx.passwordvault.AppGraph

/**
 * The activity hook that builds the one `BiometricPrompt`, every time in `onCreate`, also after a
 * rotation or a language change (lock.md §9.2 rule 1; architecture.md §3.6), and routes its
 * callbacks to the process-wide lock. Filled by the lock manager package (build plan 4.1); the stub
 * builds nothing.
 */
object BiometricHost {
    fun onCreate(activity: FragmentActivity, graph: AppGraph) = Unit
}
