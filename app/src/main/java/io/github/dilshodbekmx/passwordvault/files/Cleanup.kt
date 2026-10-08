package io.github.dilshodbekmx.passwordvault.files

/**
 * What [FileTaskStore.abandon] did with the document of a task that will not be finished (ux.md §4
 * item 3), so that the task's screen says the matching sentence.
 */
sealed interface Cleanup {
    /** There is no document to remove: the picker returned none, or the task opened one of the user's. */
    data object NoDocument : Cleanup

    /** The document "Save as" created was deleted: "No backup was made. The empty file was removed." */
    data object Removed : Cleanup

    /**
     * It could not be deleted, because [place] does not allow it or could not be reached: "No backup
     * was made. Delete the empty file … in Google Drive yourself."
     */
    data class Left(val doc: DocRef, val place: Place) : Cleanup

    /**
     * It holds something, or it could not be read, and no write of this task began (neither
     * [FileTask.written], which [FileTaskStore.write] records, nor the caller's `written`): it is left
     * as it was. That is an existing file the user chose to replace, and nothing is said about it.
     * What a try of this task wrote before a lock or the end of the process cut it off is not
     * [Kept]: the task's record says that its write began, and the document is removed.
     */
    data object Kept : Cleanup
}
