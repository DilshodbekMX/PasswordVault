package io.github.dilshodbekmx.passwordvault.keystore

import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.Keys

/**
 * Builds [Keys]: `KeystoreAccess`, `KeystorePinKey`, `KeystoreFingerprintKey` (build plan 3.9) and
 * the prompt's launcher (4.1). A stub until 3.9 replaces the body; nothing the shell runs needs it.
 */
object KeystoreModule {
    fun create(graph: AppGraph): Keys = throw NotImplementedError("KeystoreModule: the Keystore adapters come with build plan 3.9")
}
