package io.github.dilshodbekmx.passwordvault.core.strength

/**
 * The result of the security check over one vault (ux.md §5.16; architecture.md §3.7): what each
 * card of the Security tab counts, as entry ids, in the order of the cards. Deleted entries are in
 * none of the lists (ux.md §2), and no password is in the report. `SecurityCheck.run` (package
 * 2.12) makes it; `SecurityReports` in `:app` (package 7.1) publishes it.
 */
data class SecurityReport(
    /** Card 1: entries whose password is the master password (the vault's `masterPasswordMatches`, D-9b). */
    val sameAsMasterPassword: List<String>,
    /** Card 2: the groups of entries that share one password, the biggest first. */
    val reused: List<ReuseGroup>,
    /** Card 3: "easy to guess", zxcvbn score 2 or less with the bundled dictionary. */
    val weak: List<String>,
    /** Card 4: changes not finished (`changePending`). */
    val pending: List<String>,
    /** Card 5: left out of the check (`checkIgnored`, D-9a). */
    val leftOut: List<String>,
)

/**
 * One group of reused passwords (ux.md §5.16, "Group list"): [entryIds] still share the password,
 * in the order the group list shows them; [changed] counts the accounts that left the group by a
 * change ("3 changed, 14 to go"). [id] names the group in the route `Group(id)` and never derives
 * from the password.
 */
data class ReuseGroup(val id: String, val entryIds: List<String>, val changed: Int)
