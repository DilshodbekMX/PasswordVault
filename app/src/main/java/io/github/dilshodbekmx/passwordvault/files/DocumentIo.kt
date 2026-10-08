package io.github.dilshodbekmx.passwordvault.files

import java.io.IOException

/**
 * Reading and writing documents the user chose in the system file picker (architecture.md §3.5;
 * ux.md §4, §5.19 to §5.21): `SafDocumentIo` (build plan 5.1) over the Storage Access Framework.
 * The launchers that open the picker are registered by the activity (`DocumentLaunchers`).
 */
interface DocumentIo {
    /**
     * Writes [bytes] with mode "wt", then opens the document again, reads it back and compares
     * hashes [V-S6]. False: the write failed or the read-back differs (a provider that wrote 0 bytes
     * or cut the file); the caller then deletes the file and says so (ux.md §4 item 3).
     */
    suspend fun write(doc: DocRef, bytes: ByteArray): Boolean

    /**
     * Reads at most [maxBytes] + 1 bytes: a longer result means "too long" (architecture.md §3.3's
     * rule for files).
     *
     * @throws IOException when the document cannot be opened or read.
     */
    suspend fun read(doc: DocRef, maxBytes: Int): ByteArray

    /** Takes a persistable permission on [doc], so that a task can continue in a new process (ux.md §4 item 1). */
    fun keep(doc: DocRef)

    /** Deletes [doc] (`DocumentsContract.deleteDocument`); false where the place does not allow it. */
    fun delete(doc: DocRef): Boolean

    /** Where [doc] lives. */
    fun place(doc: DocRef): Place

    /**
     * The name the place shows for [doc]. It may differ from the name the picker suggested: a
     * provider adds "(1)" on a clash. The screens show this one ("Backup saved: …", ux.md §5.19;
     * "Delete the empty file … yourself", ux.md §4 item 3), and it is the `name` kept with the file's
     * reference in the vault (`plainExports`, `backups`; architecture.md §4.3). Null when it cannot
     * be read. It asks the provider, so it is called off the main thread.
     */
    fun name(doc: DocRef): String?
}
