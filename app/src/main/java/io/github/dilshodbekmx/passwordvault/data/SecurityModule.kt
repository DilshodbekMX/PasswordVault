package io.github.dilshodbekmx.passwordvault.data

import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.fake.FakeSecurityReports

/** Builds [SecurityReports]: `SecurityCheckRunner` from build plan 7.1; until then an empty report. */
object SecurityModule {
    fun create(graph: AppGraph): SecurityReports = FakeSecurityReports()
}
