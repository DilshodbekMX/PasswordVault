package io.github.dilshodbekmx.passwordvault.core.model

/**
 * One account (ux.md §2): one field per key of the entry table in architecture.md §4.3, in that
 * table's order, which is also the order of the writer's canonical form. Times are milliseconds
 * since 1970 UTC. The defaults are the ones §4.3 gives for a key that is missing.
 *
 * This is a value and checks nothing. The limits and rules of ux.md §2 belong to `EntryRules`
 * (package 2.4) and to the write-time check of architecture.md §4.9 step 4; a reader never refuses
 * a stored entry for them (§4.3, "What the reader checks").
 *
 * [toString] shows the id, the flags, the times and the sizes of the lists, never a text the user
 * typed: an entry is account data, and none of it may reach a log or an exception message (§5.6).
 */
data class Entry(
    /** UUID v4, lower case; never shown. */
    val id: String,
    val title: String,
    val username: String = "",
    /** Exactly as typed: never trimmed or normalised (ux.md §2). */
    val password: String = "",
    val notes: String = "",
    val urls: List<String> = emptyList(),
    val fields: List<CustomField> = emptyList(),
    val favourite: Boolean = false,
    /** A guided change is waiting for its answer (ux.md §5.17). */
    val changePending: Boolean = false,
    /** "Leave out of the security check" (architecture.md D-9a). */
    val checkIgnored: Boolean = false,
    val createdAt: Long,
    /** On any saved change. */
    val updatedAt: Long,
    /** Set when the password is first set and whenever its value changes. */
    val passwordChangedAt: Long? = null,
    /** Set by Delete: the entry waits in Recently deleted (ux.md §5.23, D-24). */
    val deletedAt: Long? = null,
    /** The website's password while [changePending] is true (ux.md §5.17). */
    val pendingOldPassword: String? = null,
    /** Earlier passwords, newest first, at most 5 (ux.md §2). */
    val passwordHistory: List<HistoryItem> = emptyList(),
    /**
     * The keys of the stored entry object that version 1 does not know, in the order they were
     * read, each with its value as compact JSON text: the same tokens and the same spelling of
     * every number, no white space (`"futureEntryKey"` → `"\"kept\""`, a number written `1.50` →
     * `"1.50"`). They are written back after the known keys, as they are (architecture.md §4.3,
     * "Unknown keys"), so that a later version's data survives a save by this one.
     */
    val extras: Map<String, String> = emptyMap(),
) {
    override fun toString(): String =
        "Entry(id=$id, favourite=$favourite, changePending=$changePending, checkIgnored=$checkIgnored, " +
            "createdAt=$createdAt, updatedAt=$updatedAt, passwordChangedAt=$passwordChangedAt, deletedAt=$deletedAt, " +
            "urls=${urls.size}, fields=${fields.size}, passwordHistory=${passwordHistory.size}, extras=${extras.size}, " +
            "texts hidden)"
}
