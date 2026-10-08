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
     * It holds something this task did not write, or it could not be read: the user chose an existing
     * file to replace. It is left as it was, and nothing is said about it.
     */
    data object Kept : Cleanup
}
