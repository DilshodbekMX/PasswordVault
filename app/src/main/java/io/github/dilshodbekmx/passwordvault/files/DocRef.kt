package io.github.dilshodbekmx.passwordvault.files

/**
 * A document the user chose in the system file picker: its `content://` URI as a string
 * (architecture.md §3.5). It becomes an `android.net.Uri` only inside `SafDocumentIo` (build plan
 * 5.1), so that everything else stays testable on the JVM. Not secret: it is kept in the settings for
 * an unfinished file task (ux.md §4).
 */
data class DocRef(val uri: String)
