package io.github.dilshodbekmx.passwordvault.ui.transfer

import io.github.dilshodbekmx.passwordvault.files.Cleanup
import io.github.dilshodbekmx.passwordvault.files.DocRef
import io.github.dilshodbekmx.passwordvault.files.DocumentIo
import io.github.dilshodbekmx.passwordvault.files.DocumentNames
import io.github.dilshodbekmx.passwordvault.files.DocumentTypes
import io.github.dilshodbekmx.passwordvault.files.FileTask
import io.github.dilshodbekmx.passwordvault.files.FileTaskStore
import io.github.dilshodbekmx.passwordvault.files.PickResult
import io.github.dilshodbekmx.passwordvault.files.Place
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * The files of the backup and the restore (build plan 5.2; architecture.md §3.5 "The file tasks";
 * ux.md §4, §5.2, §5.19): the process's [FileTaskStore] (the file picker, the share sheet and the
 * task that survives a lock and a new process) and [DocumentIo] (reading back, and the name and the
 * place a document has). The screens of this package call nothing else of `files`, so that a change
 * of those contracts is a change of this class alone.
 *
 * [DocumentIo.name] and [DocumentIo.place] ask the provider, so they run on [io].
 */
class BackupFiles(private val store: FileTaskStore, private val documents: DocumentIo, private val io: CoroutineDispatcher) {
    /** The task as recorded (`FileTaskStore.task`): a backup's or a restore's that a screen opened again continues. */
    val task: StateFlow<FileTask?> get() = store.task

    /** What the picker returned for the task of [kind] (`FileTaskStore.results`). */
    fun results(kind: FileTask.Kind): Flow<PickResult> = store.results(kind)

    /**
     * Records a backup at [step] and opens "Save as" with the suggested name of the day of [nowMs]
     * (`PasswordVault-backup-2026-10-06.pvault`, formats.md §2 rule 5) and the backup's type. False,
     * with nothing opened, as `FileTaskStore.create` says.
     */
    suspend fun saveAs(step: Int, nowMs: Long): Boolean =
        store.create(FileTask(FileTask.Kind.BACKUP, step, null), suggestedName(nowMs), DocumentTypes.BACKUP)

    /** Records a restore at [step] and opens the picker for a document of any type (ux.md §5.2 step 1). */
    suspend fun openBackupFile(step: Int): Boolean = store.open(FileTask(FileTask.Kind.RESTORE, step, null))

    /**
     * Writes the backup [bytes] into the document of the backup task, through the store, which
     * records first that the task's write began (iface-92), so that a write cut off by a lock or the
     * end of the process is removed when the task is given up, in this process or in a new one; the
     * write reads the file back and compares it [V-S6]. False: no backup task has a document, or it
     * was not written, or what was read back differs.
     */
    suspend fun write(bytes: ByteArray): Boolean = store.write(FileTask.Kind.BACKUP, bytes)

    /**
     * What [doc] holds, read again for "Checking the saved file…": at most [size] + 1 bytes, so that
     * a longer file shows as longer. Null when it cannot be read.
     */
    suspend fun readBack(doc: DocRef, size: Int): ByteArray? = try {
        documents.read(doc, size)
    } catch (e: IOException) {
        null
    }

    /**
     * The chosen backup file's bytes: at most [maxBytes] + 1 of them.
     *
     * @throws IOException when it cannot be read.
     */
    suspend fun read(doc: DocRef, maxBytes: Int): ByteArray = documents.read(doc, maxBytes)

    /** The task of [kind] is done (`FileTaskStore.finish`). */
    suspend fun finish(kind: FileTask.Kind) = store.finish(kind)

    /**
     * The task of [kind] is given up (`FileTaskStore.abandon`): the document "Save as" made is
     * deleted when it is empty or, with [written], when this task wrote into it. What was done.
     */
    suspend fun abandon(kind: FileTask.Kind, written: Boolean): Cleanup = store.abandon(kind, written)

    /** The name the place shows for [doc]; null when it cannot be read. */
    suspend fun name(doc: DocRef): String? = withContext(io) { documents.name(doc) }

    /** Where [doc] is. */
    suspend fun place(doc: DocRef): Place = withContext(io) { documents.place(doc) }

    /**
     * The vault no longer notes the backup [doc] (`VaultEdits.forgetBackup` was saved): the
     * permission taken on it when it was chosen is given up (iface-93; Android 7 keeps 128 per app
     * and drops the oldest). Best effort.
     */
    suspend fun release(doc: DocRef) = withContext(io) { documents.release(doc) }

    /** Deletes [doc] (`DocumentsContract.deleteDocument`); false where the place does not allow it or cannot be reached. */
    suspend fun delete(doc: DocRef): Boolean = withContext(io) { documents.delete(doc) }

    /** "Send to another app…": the share sheet titled [title] for a copy of the backup [bytes] named [name]. */
    suspend fun share(bytes: ByteArray, name: String, title: CharSequence): Boolean = store.share(bytes, name, title)

    companion object {
        /** The name "Save as" suggests for a backup made at [nowMs] (wall clock), in the phone's time zone. */
        fun suggestedName(nowMs: Long): String = DocumentNames.backup(DocumentNames.day(nowMs))
    }
}
