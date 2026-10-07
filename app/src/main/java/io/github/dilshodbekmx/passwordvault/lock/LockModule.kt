package io.github.dilshodbekmx.passwordvault.lock

import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.fake.FakeVaultRepository

/**
 * Builds [LockManager]: the master-password gate of build plan 3.2, then `DefaultLockManager` (4.1).
 * Until then the fake, which starts with no vault, so that the debug app walks the placeholder
 * routes: setup, the vault made, lock and unlock. It opens and drops the fake vault with it.
 */
object LockModule {
    fun create(graph: AppGraph): LockManager = FakeLockManager(vault = graph.data.repository as? FakeVaultRepository)
}
