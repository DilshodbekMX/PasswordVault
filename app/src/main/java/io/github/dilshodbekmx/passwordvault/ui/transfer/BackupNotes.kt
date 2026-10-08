package io.github.dilshodbekmx.passwordvault.ui.transfer

import io.github.dilshodbekmx.passwordvault.core.model.FileRef
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import io.github.dilshodbekmx.passwordvault.data.SaveResult
import io.github.dilshodbekmx.passwordvault.data.VaultRepository
import io.github.dilshodbekmx.passwordvault.files.DocRef

/**
 * The backups this app saved, as the vault notes them (`Vault.backups`: "Last backup", when a backup
 * is due, architecture.md §4.3). The backup screen notes each one it saved (`VaultEdits.noteBackup`);
 * this is the other end: the vault forgets one, and the permission on its document, taken when the
 * picker returned it and kept while the vault notes it, is given up then (architecture.md §3.5
 * "Permissions given up"; Android 7 keeps 128 per app and drops the oldest silently).
 *
 * For "Delete" in the list of places where this app saved backups after a change of the master
 * password (ux.md §5.18 "Change master password" step 6, build plan 5.5): [delete].
 */
class BackupNotes(private val repository: VaultRepository, private val files: BackupFiles) {
    /**
     * Deletes the file of [saved] where its place allows it, then forgets it ([forget]).
     * [NoteDeleted.NOT_DELETABLE]: the place does not allow it or cannot be reached, and the vault
     * still notes it.
     */
    suspend fun delete(saved: FileRef): NoteDeleted {
        if (!files.delete(DocRef(saved.uri))) return NoteDeleted.NOT_DELETABLE
        return if (forget(saved)) NoteDeleted.DELETED else NoteDeleted.NOT_FORGOTTEN
    }

    /**
     * The vault forgets the backup [saved] (one save); once that is saved, the permission on its
     * document is given up. False: the save failed, and the vault still notes it.
     */
    suspend fun forget(saved: FileRef): Boolean {
        if (repository.edit(VaultEdits.forgetBackup(saved.uri)) !is SaveResult.Saved) return false
        files.release(DocRef(saved.uri))
        return true
    }
}

/** What [BackupNotes.delete] did. */
enum class NoteDeleted {
    /** The file was deleted and the vault forgets it. */
    DELETED,

    /** The place does not allow deleting it, or cannot be reached: the user deletes it there themselves. */
    NOT_DELETABLE,

    /** The file was deleted, and the vault could not be saved: it still notes the file until the next try. */
    NOT_FORGOTTEN,
}
