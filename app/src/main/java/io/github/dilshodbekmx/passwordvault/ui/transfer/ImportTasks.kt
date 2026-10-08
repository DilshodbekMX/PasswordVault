package io.github.dilshodbekmx.passwordvault.ui.transfer

import io.github.dilshodbekmx.passwordvault.files.DocRef
import io.github.dilshodbekmx.passwordvault.files.DocumentTypes
import io.github.dilshodbekmx.passwordvault.files.FileTask
import io.github.dilshodbekmx.passwordvault.files.FileTaskStore
import io.github.dilshodbekmx.passwordvault.files.PickResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The import's unfinished file task (ux.md §4, "Unfinished file tasks"; architecture.md §3.5, "The
 * file tasks"): the one place where the import screens call the process's [FileTaskStore], so that a
 * change of its calls is one change here.
 *
 * The import has one task step, [ImportStep.FILE]: the picker is opened with it, and the recorded
 * task then holds the chosen document until the import is finished ([finish]: the file was deleted or
 * kept) or given up ([abandon]).
 */
internal interface ImportTasks {
    /** The task as recorded, of any kind; the import's is the one whose kind is [FileTask.Kind.IMPORT]. */
    val task: StateFlow<FileTask?>

    /** The picker's answers for the import: a document, again after every unlock until [finish]; or a closed picker. */
    fun results(): Flow<PickResult>

    /** Records the import's task with no document and opens the picker for a file of any type. False: nothing opened. */
    suspend fun open(): Boolean

    /** The import is done: forgets its task. The chosen file was deleted or kept before this. */
    suspend fun finish()

    /** The import is given up before anything was saved: forgets its task. The chosen file is never touched. */
    suspend fun abandon()

    /** The import's document, when an import task has one. */
    val document: DocRef?
        get() = task.value?.takeIf { it.kind == FileTask.Kind.IMPORT }?.doc
}

/** [ImportTasks] over the process's [FileTaskStore]. */
internal class StoreImportTasks(private val store: FileTaskStore) : ImportTasks {
    override val task: StateFlow<FileTask?> get() = store.task

    override fun results(): Flow<PickResult> = store.results(FileTask.Kind.IMPORT)

    override suspend fun open(): Boolean = store.open(FileTask(FileTask.Kind.IMPORT, ImportStep.FILE, null), DocumentTypes.ANY)

    override suspend fun finish() = store.finish(FileTask.Kind.IMPORT)

    override suspend fun abandon() {
        store.abandon(FileTask.Kind.IMPORT)
    }
}

/**
 * The steps of the import (`Route.Import(step)`, and the step of its `FileTask`).
 *
 * - [SOURCES]: where the passwords come from, and the Google help shown before the picker (ux.md
 *   §5.21, "Sources screen"). Nothing is recorded yet.
 * - [FILE]: a file was chosen. It is read again whenever the screen opens, because its preview
 *   holds passwords and is never kept (ux.md §4 item 2); then the column mapping, the preview, the
 *   import and the question whether to delete the file. A backup file asks for its master password
 *   instead.
 */
internal object ImportStep {
    const val SOURCES: Int = 0
    const val FILE: Int = 1
}
