package io.github.dilshodbekmx.passwordvault.files

/** What the system file picker returned for a task, as [FileTaskStore.results] hands it to the task's screen. */
sealed interface PickResult {
    /** The task as recorded. */
    val task: FileTask

    /**
     * The picker returned a document: its persistable permission was taken, and the task was recorded
     * with it ([FileTask.doc]) before this was handed out.
     */
    data class Picked(override val task: FileTask) : PickResult {
        /** The chosen document. */
        val doc: DocRef get() = checkNotNull(task.doc) { "a picked task has its document" }
    }

    /** The picker was closed without a document: nothing was created, and the task's record is gone. */
    data class Cancelled(override val task: FileTask) : PickResult
}
