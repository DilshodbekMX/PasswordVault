package io.github.dilshodbekmx.passwordvault.data

import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.Data
import io.github.dilshodbekmx.passwordvault.fake.FakeListModelSource
import io.github.dilshodbekmx.passwordvault.fake.FakeVaultRepository
import io.github.dilshodbekmx.passwordvault.fake.StubVaultPort

/**
 * Builds [Data]: `DefaultVaultRepository` as both repository and port, and `ListModelProvider`
 * (build plan 3.1). Until then the in-memory fakes, and a port that nothing calls.
 */
object DataModule {
    fun create(graph: AppGraph): Data = Data(FakeVaultRepository(), StubVaultPort, FakeListModelSource())
}
