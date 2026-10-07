package io.github.dilshodbekmx.passwordvault.core.lock.flow

/*
 * The types of the lock's view of the vault (architecture.md §3.4). They are declared here, in
 * core.lock.flow, because the lock never names core.storage or core.vaultfile (build check R-10,
 * lock.md T17): VaultRef, GenerationInfo and AsideVault are the lock's own counterparts of the
 * storage types of the same names, and DefaultVaultRepository (package 3.1) maps the storage
 * engine's results onto these. The lock never receives VK except inside VaultPort.withVaultKey.
 */

/** Which vault a call means: the current one, or one put aside (architecture.md §4.11). */
sealed interface VaultRef {
    data object Current : VaultRef

    data class Aside(val vaultId: String) : VaultRef
}

/** One generation as its header gives it: its number, its `vk_id` and its `created` time (wall clock). */
data class GenerationInfo(val number: Long, val vkId: String, val createdMs: Long)

/**
 * A vault that was put aside: its id, the wall-clock time at which it was put aside (display only:
 * "locked on 6 Oct 2026"), and its newest readable generation, null when it cannot be read now.
 */
data class AsideVault(val vaultId: String, val putAsideAtMs: Long, val newest: GenerationInfo?)

/** The vaults on the phone, before anything is typed (lock.md §5.11 step 2, §16.3 "Storage"). */
data class VaultScan(val current: CurrentVault, val aside: List<AsideVault>)

/** The current vault as the lock sees it before unlock. */
sealed interface CurrentVault {
    /** The vault directory does not exist or is empty: Welcome (lock.md §13.1 row 1). */
    data object NoVault : CurrentVault

    /**
     * The newest readable generation's `vault_id` and facts, and the `vk_id`s among the kept
     * generations ([keptVkIds] null when some live file could not be read; the lock then trims no
     * wrap at that unlock, lock.md §5.4).
     *
     * [olderCopyFirst]: a newer generation on disk is damaged and [newest] is an older copy: storage's
     * `VaultFacts.olderCopyFirst` (architecture.md §4.8), which DefaultVaultRepository (3.1) maps here.
     * The lock needs it before anything is typed, for the "older copy" reason of lock.md §13.3 (F16a).
     */
    data class Present(
        val vaultId: String,
        val newest: GenerationInfo,
        val keptVkIds: Set<String>?,
        val olderCopyFirst: Boolean = false,
    ) : CurrentVault

    /** No generation can be read: "Your passwords can't be opened" (lock.md §13.1 row 2), never an offer to delete. */
    data class Unreadable(val why: Why) : CurrentVault

    /** An I/O error (offer "Try again"), every file damaged, or a file made by a newer app. */
    enum class Why { IO, DAMAGED, NEWER }
}

/** What `VaultPort.open` came to: the PIN, the fingerprint, or the pending wrap of lock.md F11. */
sealed interface VaultOpen {
    /**
     * The vault is open in the repository. [createdMs] is the `created` time of the generation that
     * opened; [olderCopy] says that it is not the newest on disk (the notice of ux.md §5.9).
     */
    data class Opened(val vaultId: String, val generation: Long, val vkId: String, val createdMs: Long, val olderCopy: Boolean) :
        VaultOpen

    /** The newest usable generation has a `vk_id` none of the keys opens (lock.md F16a); nothing was opened. */
    data class NeedsOtherKey(val generation: Long, val createdMs: Long) : VaultOpen

    data object Damaged : VaultOpen

    data object Newer : VaultOpen

    data object Unreadable : VaultOpen
}

/** What `VaultPort.openWithPassword` came to, after every distinct slot was tried [V-S3]. */
sealed interface PasswordOpen {
    /** As [VaultOpen.Opened]. */
    data class Opened(val vaultId: String, val generation: Long, val vkId: String, val createdMs: Long, val olderCopy: Boolean) :
        PasswordOpen

    /**
     * The password opens only the slot of an older copy, while a newer copy under another key is
     * intact (lock.md F16b): nothing was opened, and the try stays counted. [changedAtMs] is the
     * `created` time of the first generation under the newer key ("You changed your master password
     * on 7 Oct"). [generation] is the newest generation this password opens: the copy that
     * "Open my passwords from before the change" makes current with `VaultPort.putNewerAside`.
     */
    data class OlderPassword(val changedAtMs: Long, val generation: Long) : PasswordOpen

    /** No slot opened: "wrong master password", and only then (lock.md §6.8 step 7). */
    data object Wrong : PasswordOpen

    /** Argon2id could not run (`KdfUnavailable`): "not enough memory", never wrong, never counted. */
    data object NoMemory : PasswordOpen

    data object Damaged : PasswordOpen

    data object Newer : PasswordOpen

    data object Unreadable : PasswordOpen
}

/**
 * What one of the calls that write vault data came to (lock.md F1, F8, F11, F13, F16b, F17). Each
 * proves a new slot from disk before anything is published (architecture.md §4.9).
 */
sealed interface VaultWrite {
    /** Written, proven and published: the vault [vaultId] is current with [generation] under [vkId]. */
    data class Done(val vaultId: String, val vkId: String, val generation: Long, val createdMs: Long) : VaultWrite

    /** It failed before the new file was in place: nothing changed. */
    data object NothingChanged : VaultWrite

    /** The new file was in place but did not prove; it was moved to a quarantine name, never deleted (lock.md F8 step 6). */
    data object Quarantined : VaultWrite

    /** Saving may not have finished: the new file did not prove and even its move to quarantine failed (lock.md F8 step 6). */
    data object Unclear : VaultWrite
}
