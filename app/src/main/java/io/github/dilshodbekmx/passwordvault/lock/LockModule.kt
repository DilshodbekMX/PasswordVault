package io.github.dilshodbekmx.passwordvault.lock

import androidx.lifecycle.ProcessLifecycleOwner
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.fake.FakeVaultRepository
import io.github.dilshodbekmx.passwordvault.fake.StubVaultPort

/**
 * Builds [LockManager]: the master-password gate of build plan 3.2 ([MasterPasswordGate]) over the
 * vault's port, until `DefaultLockManager` (4.1) replaces this body.
 *
 * While `DataModule` still returns `StubVaultPort`, whose every call throws (on this package's
 * branch before `DefaultVaultRepository`, build plan 3.1, is merged), the fake lock stays, as
 * before, so that the debug app still walks its routes; with a real port it is always the gate.
 */
object LockModule {
    fun create(graph: AppGraph): LockManager {
        val data = graph.data
        if (data.port === StubVaultPort) return FakeLockManager(vault = data.repository as? FakeVaultRepository)
        val platform = graph.platform
        return MasterPasswordGate(
            port = data.port,
            settings = graph.settings,
            random = platform.random,
            clock = platform.clock,
            scope = graph.appScope,
            io = graph.dispatchers.io,
            processLifecycle = ProcessLifecycleOwner.get().lifecycle,
        )
    }
}
