package io.github.dilshodbekmx.passwordvault.data

import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.model.Draft
import io.github.dilshodbekmx.passwordvault.core.model.Edited
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import kotlinx.coroutines.flow.StateFlow

/**
 * The one owner of the open vault (architecture.md §1 item 3, §3.5, §5.1): `DefaultVaultRepository`
 * (build plan 3.1), which also implements the lock's `VaultPort`. Every change to the vault is one
 * call of [edit] with a pure function of `core.model.VaultEdits` (§5.4); a screen never holds a
 * mutable entry. Until package 3.1 is merged, `DataModule` returns `FakeVaultRepository`.
 *
 * Byte and character arrays passed in belong to the caller, which wipes them.
 */
interface VaultRepository {
    /** The open vault, or [VaultState.Locked]; an edit is published only once it is durable on disk. */
    val state: StateFlow<VaultState>

    /**
     * Applies a pure change from `core.model.VaultEdits` to the open vault, writes a new generation
     * and publishes it only when the write is durable (architecture.md §4.9). Runs to the end even
     * if the caller is cancelled (§5.3). [Edited.vault] being the input itself means nothing to
     * save: nothing is written, and the result is [SaveResult.Saved].
     */
    suspend fun <T> edit(change: (Vault, nowMs: Long) -> Edited<T>): SaveResult<T>

    /** The open form's unsaved state, kept in memory only until [flushDraft]; null: no form is dirty. */
    fun holdDraft(draft: Draft?)

    /**
     * Writes a held draft that differs from the vault's stored one, as one [edit]. Best effort and
     * bounded: skipped when the write has not started within 2 seconds; a write that has started
     * always finishes (architecture.md §5.5 step 2).
     */
    suspend fun flushDraft()

    /** A new backup container with its own key, from the checked master-password bytes of [proof] (architecture.md §4.12). */
    suspend fun sealBackup(proof: MasterProof): ByteArray

    /**
     * Opens a backup file's [bytes] with [passwordBytes] (`MasterPasswordBytes.of` of what was
     * typed), without the waits of the lock (lock.md §6.8: a file from outside).
     */
    suspend fun openBackup(bytes: ByteArray, passwordBytes: ByteArray): BackupResult

    /**
     * The ids of the entries whose password equals the master password of [proof] (architecture.md
     * D-9b), compared through `MasterPasswordBytes`. Called after a correct master-password entry
     * only.
     */
    suspend fun matchesMasterPassword(proof: MasterProof): Set<String>

    /**
     * The older-copy notice was answered (lock.md §13.4 item 1; architecture.md §5.5 "Automatic
     * writes"): the automatic writes and housekeeping that waited since an unlock opened an older
     * copy may run again. Nothing happens when no older copy is open. Returns at once; the lock
     * manager calls it when it receives the answer.
     */
    fun olderCopyAnswered()
}

/** Why a save did not happen (architecture.md §3.5, §4.9). */
enum class SaveProblem {
    /** The new generation was written but the directory sync failed: it may not survive a power cut, so it was not published. */
    NOT_DURABLE,

    /** Writing failed; nothing changed: "Couldn't save. Your phone's storage may be full." */
    FAILED,

    /** The vault directory cannot be written now. */
    READ_ONLY,

    /**
     * The vault no longer fits in one file: "Your passwords no longer fit in one file. Delete
     * accounts you don't need, or shorten very long notes." The import preview refuses an import
     * that would not fit with the same sentence (architecture.md §3.5).
     */
    TOO_LARGE,
}

/** What [VaultRepository.edit] came to. */
sealed interface SaveResult<out T> {
    /** Written, durable and published; [value] is the change's own result. */
    class Saved<T>(val value: T) : SaveResult<T>

    /** Nothing changed, for [problem]. */
    data class NotSaved(val problem: SaveProblem) : SaveResult<Nothing>
}

/** What [VaultRepository.openBackup] came to (architecture.md §4.4). */
sealed interface BackupResult {
    /** The backup opened and was checked: its [vault], and [createdMs], its `created` time ("backup made on 3 October 2026"). */
    class Opened(val vault: Vault, val createdMs: Long) : BackupResult {
        override fun toString(): String = "BackupResult.Opened(entries=${vault.entries.size}, createdMs=$createdMs)"
    }

    /** The password slot did not open and the file is intact: "This password does not open this backup. …" */
    data object Wrong : BackupResult

    /**
     * The slot did not open and the trailer is wrong: "This file may be damaged, or the password is
     * wrong." Another try is allowed (architecture.md §3.5).
     */
    data object WrongOrDamaged : BackupResult

    /** "This backup file is damaged and cannot be opened. Try another backup file." */
    data object Damaged : BackupResult

    /** Made by a newer version of the app: "Install the newest version of PasswordVault and try again." */
    data object Newer : BackupResult

    /** Not a PasswordVault backup (ux.md §5.2). */
    data object NotOurs : BackupResult

    /** Argon2id could not run: "Your phone does not have enough free memory to open the file. …" */
    data object NoMemory : BackupResult
}
