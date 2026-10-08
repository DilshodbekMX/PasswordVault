package io.github.dilshodbekmx.passwordvault.ui.transfer

import io.github.dilshodbekmx.passwordvault.core.crypto.MasterPasswordBytes
import io.github.dilshodbekmx.passwordvault.core.model.EditTexts
import io.github.dilshodbekmx.passwordvault.core.model.Merged as MergedEntries
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import io.github.dilshodbekmx.passwordvault.core.util.RandomSource
import io.github.dilshodbekmx.passwordvault.data.BackupResult
import io.github.dilshodbekmx.passwordvault.data.SaveProblem
import io.github.dilshodbekmx.passwordvault.data.SaveResult
import io.github.dilshodbekmx.passwordvault.data.VaultRepository

/**
 * "Backup file into an existing vault" (ux.md §5.21; architecture.md §4.11, §4.12; formats.md §3):
 * a backup file of this app chosen on the import screen (`FileKind.BACKUP`) is opened with that
 * backup's master password, without waits (a file from outside, lock.md §6.8), and its entries only
 * are added to the open vault, as one save: an entry with a new id is added; one identical to the
 * entry with its id is skipped; one with the id of an entry with other content is added with a new
 * id and the title "Gmail (from backup)" ([EditTexts.fromBackup]). Nothing is overwritten.
 *
 * For the import screens (build plan 5.4), which recognise the file; the messages of a backup that
 * does not open are the restore's ([RestoreProblem], `BackupTexts.restoreProblem`).
 */
class BackupMerge(private val repository: VaultRepository, private val random: RandomSource) {
    /**
     * Opens the backup [bytes] with [chars], the master password typed for it, and merges its
     * entries into the open vault with the titles of [texts]. [chars] stays the caller's, which
     * wipes it.
     */
    suspend fun merge(bytes: ByteArray, chars: CharArray, texts: EditTexts): MergeOutcome {
        val passwordBytes = MasterPasswordBytes.of(chars)
        val opened = try {
            repository.openBackup(bytes, passwordBytes)
        } finally {
            passwordBytes.fill(0)
        }
        val vault = when (opened) {
            is BackupResult.Opened -> opened.vault
            BackupResult.Wrong -> return MergeOutcome.NotOpened(RestoreProblem.WRONG)
            BackupResult.WrongOrDamaged -> return MergeOutcome.NotOpened(RestoreProblem.WRONG_OR_DAMAGED)
            BackupResult.Damaged -> return MergeOutcome.NotOpened(RestoreProblem.DAMAGED)
            BackupResult.Newer -> return MergeOutcome.NotOpened(RestoreProblem.NEWER)
            BackupResult.NotOurs -> return MergeOutcome.NotOpened(RestoreProblem.NOT_OURS_LOCKED)
            BackupResult.NoMemory -> return MergeOutcome.NotOpened(RestoreProblem.NO_MEMORY)
        }
        return when (val saved = repository.edit(VaultEdits.mergeEntries(vault.entries, texts, random))) {
            is SaveResult.Saved -> MergeOutcome.Merged(saved.value)
            is SaveResult.NotSaved -> MergeOutcome.NotSaved(saved.problem)
        }
    }
}

/** What [BackupMerge.merge] came to. */
sealed interface MergeOutcome {
    /** Merged, as one save: what was added, skipped and renamed. */
    data class Merged(val merged: MergedEntries) : MergeOutcome

    /**
     * The backup did not open, for [problem]: one of the restore's messages. [RestoreProblem.WRONG]
     * and [RestoreProblem.WRONG_OR_DAMAGED] allow another try.
     */
    data class NotOpened(val problem: RestoreProblem) : MergeOutcome

    /** It opened, and the save failed for [problem] (`TOO_LARGE`: architecture.md §3.5's sentence); nothing changed. */
    data class NotSaved(val problem: SaveProblem) : MergeOutcome
}
