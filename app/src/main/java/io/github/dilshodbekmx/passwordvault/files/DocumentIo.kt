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
}
