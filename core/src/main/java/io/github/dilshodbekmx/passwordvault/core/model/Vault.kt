package io.github.dilshodbekmx.passwordvault.core.model

/**
 * The decrypted vault body (architecture.md §4.3): the entries and the vault-level data of
 * ux.md §2, one field per top-level key, in the order of the top-level table. Immutable: every
 * change builds a new one (`VaultEdits`, package 2.4), and screens see it only as a snapshot.
 *
 * Deleted entries ([Entry.deletedAt] set) stay here, in every generation and in backups, until the
 * purge of D-24 removes them.
 */
data class Vault(
    /** Always [SCHEMA] in version 1: a reader treats a higher one as NEWER and builds no [Vault] from it (R9). */
    val schema: Long = SCHEMA,
    val entries: List<Entry> = emptyList(),
    /** The one unsaved form, if any (ux.md §5.14, D-8a). */
    val draft: Draft? = null,
    /** Unprotected exports not yet deleted (ux.md §5.20). */
    val plainExports: List<FileRef> = emptyList(),
    /**
     * Backups this app saved (ux.md §5.18). A backup is due when some entry's `updatedAt` or
     * `deletedAt` is later than the newest [FileRef.savedAt] here; no counter is kept.
     */
    val backups: List<FileRef> = emptyList(),
    /** Ids of the entries whose password equals the master password (ux.md §5.16, D-9b). */
    val masterPasswordMatches: List<String> = emptyList(),
    /** When the Security tab was last opened; written only when it would clear its dot (§4.3). */
    val securitySeenAt: Long? = null,
    /**
     * The top-level keys version 1 does not know, in the order they were read, each with its value
     * as compact JSON text, written back after the known keys as they are ([Entry.extras] says how).
     */
    val extras: Map<String, String> = emptyMap(),
) {
    override fun toString(): String =
        "Vault(schema=$schema, entries=$entries, draft=$draft, plainExports=${plainExports.size}, " +
            "backups=${backups.size}, masterPasswordMatches=${masterPasswordMatches.size}, " +
            "securitySeenAt=$securitySeenAt, extras=${extras.size})"

    companion object {
        /** The body schema a version 1 writer writes. */
        const val SCHEMA: Long = 1L
    }
}
