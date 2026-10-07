package io.github.dilshodbekmx.passwordvault.data

import io.github.dilshodbekmx.passwordvault.core.strength.SecurityReport
import kotlinx.coroutines.flow.StateFlow

/**
 * The security check of the open vault (ux.md §5.16): `SecurityCheckRunner` (build plan 7.1), which
 * runs `SecurityCheck.run` in the background; empty until then.
 */
interface SecurityReports {
    /** The latest report; null while locked or while the first check runs. */
    val report: StateFlow<SecurityReport?>

    /** The Security tab's dot: the report has results the user has not seen (ux.md §5.16, `securitySeenAt`). */
    val unseen: StateFlow<Boolean>
}
