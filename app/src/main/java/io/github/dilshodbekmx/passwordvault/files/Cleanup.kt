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
     * It holds something, or it could not be read, and the caller did not say that this task wrote
     * into it (`written`): it is left as it was. Mostly that is an existing file the user chose to
     * replace, and nothing is said about it. It can also be what an earlier try of this same task
     * wrote before it was cut off, when the app locked or its process ended between the write and
     * [FileTaskStore.finish]: the store cannot tell the two apart. So a screen that gives up a task
     * it took up again must not take this answer as "nothing of ours is left": an unprotected export
     * that may be in that file is noted in the vault, so that its banner shows (ux.md §5.20).
     */
    data object Kept : Cleanup
}
