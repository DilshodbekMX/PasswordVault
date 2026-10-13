package io.github.dilshodbekmx.passwordvault.fake

import io.github.dilshodbekmx.passwordvault.core.strength.SecurityReport
import io.github.dilshodbekmx.passwordvault.data.SecurityReports
import kotlinx.coroutines.flow.MutableStateFlow

/** [SecurityReports] whose values a test sets (architecture.md §2.2 `fake`); empty, with no dot, until then. */
class FakeSecurityReports : SecurityReports {
    override val report = MutableStateFlow<SecurityReport?>(null)
    override val unseen = MutableStateFlow(false)
}
