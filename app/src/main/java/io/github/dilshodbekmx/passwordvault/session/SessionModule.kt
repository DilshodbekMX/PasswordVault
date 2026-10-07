package io.github.dilshodbekmx.passwordvault.session

import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.fake.FakeSession

/**
 * Builds [Session]: `AutoLock` from build plan 3.2. Until then a fake that counts touches and whose
 * "Lock now" clears the clip, flushes the draft and locks, in the order of architecture.md §5.5.
 */
object SessionModule {
    fun create(graph: AppGraph): Session = FakeSession(graph.appScope, graph.lock, graph.clipboard, graph.data.repository)
}
