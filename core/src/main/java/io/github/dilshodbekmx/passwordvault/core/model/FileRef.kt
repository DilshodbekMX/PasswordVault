package io.github.dilshodbekmx.passwordvault.core.model

/**
 * A file this app saved through the file picker: an unprotected export not yet deleted, or a backup
 * (ux.md §5.18, §5.20). `{"uri", "name", "savedAt"}` in the vault body (architecture.md §4.3).
 * A restore resets both lists, because their document permissions belong to the old installation
 * (§4.12).
 */
data class FileRef(
    /** The document's `content://` URI, as the picker gave it. */
    val uri: String,
    /** The file name shown to the user, such as `passwords-2026-10-07.csv`. */
    val name: String,
    val savedAt: Long,
)
