package io.github.dilshodbekmx.passwordvault.core.storage

import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.vaultfile.PasswordSlot

/**
 * Which vault a call means (architecture.md §4.7, §4.11): the current one in `live/`, or one put
 * aside in its `aside-<ms>-<vault_id>/` directory.
 */
sealed interface VaultRef {
    data object Current : VaultRef

    data class Aside(val vaultId: String) : VaultRef
}

/** The state of one generation file in a vault directory (architecture.md §4.8, D-26). */
enum class FileState { OK, DAMAGED, NEWER, UNREADABLE }

/** What `VaultStorage.scan` found, before any key (architecture.md §4.8). */
data class Scan(val current: Current, val aside: List<AsideVault>)

/** The current vault as `scan` found it (architecture.md §4.8 step 4). */
sealed interface Current {
    /** `live/` does not exist or holds no file at all (lock.md F1). */
    data object NoVault : Current

    data class Present(val facts: VaultFacts) : Current

    /**
     * No generation can be used: [Why.IO] when listing `live/` threw or a file above the newest
     * OK one could not be read (the app never falls back past it), [Why.DAMAGED] when no file is
     * OK, [Why.NEWER] when a newer app wrote one of them.
     */
    data class Unreadable(val why: Why) : Current

    enum class Why { IO, DAMAGED, NEWER }
}

/**
 * The facts of the current vault that the lock needs before anything is typed (architecture.md
 * §4.8, lock.md §16.3 "Storage"). [keptVkIds] are the `vk_id`s among the live generations, null
 * when some live file could not be read (its `vk_id` is unknown, and the lock then trims no wrap).
 * [olderCopyFirst]: the newest generation was DAMAGED and an older one is the newest readable.
 */
data class VaultFacts(val vaultId: String, val newest: GenerationInfo, val keptVkIds: Set<String>?, val olderCopyFirst: Boolean)

/** One generation as its header gives it: its number, its `vk_id` and its `created` time. */
data class GenerationInfo(val number: Long, val vkId: String, val createdMs: Long)

/**
 * A vault that was put aside (architecture.md §4.11): its id, the wall-clock time in its directory
 * name (display only), and its newest readable generation, null when it cannot be read now.
 */
data class AsideVault(val vaultId: String, val putAsideAtMs: Long, val newest: GenerationInfo?)

/**
 * One distinct password slot among the kept generations of a vault (architecture.md §4.8 step 1),
 * as `VaultStorage.slots` lists them, newest first [V-S3]. The caller derives a KEK with [salt]
 * and hands both back to `openWithSlot`. [vkId] and [generation] are those of the newest
 * generation that carries this slot.
 */
class SlotRef internal constructor(
    val salt: ByteArray,
    val vkId: String,
    val generation: Long,
    internal val slot: PasswordSlot,
)

/**
 * A vault opened from disk (architecture.md §3.3). `:app` holds it but reaches VK only through
 * `VaultStorage.useKey`: VK and the slot are `internal` to `:core`. [newestOnDisk] above
 * [generation] tells the caller that an older copy opened (the notice of ux.md §5.9).
 */
class OpenedVault internal constructor(
    val vault: Vault,
    val vaultId: String,
    val generation: Long,
    val vkId: String,
    val newestOnDisk: Long,
    internal val vk: ByteArray,
    internal val slot: PasswordSlot,
)

/**
 * A generation written and read back but not yet published (architecture.md §4.9 steps 1 to 6):
 * a `vault-N.tmp` in `live/`, or a whole `new-<vault_id>/` directory. [slotSalt] is the salt as read
 * back from disk, for the proof of step 7.
 */
class Staged internal constructor(
    val vaultId: String,
    val vkId: String,
    val generation: Long,
    val slotSalt: ByteArray,
    internal val path: String,
)

/** What `VaultStorage.openWithSlot` found. */
sealed interface PasswordResult {
    class Opened(val opened: OpenedVault) : PasswordResult

    /**
     * The slot opened, but a generation with another `vk_id` and a higher number is OK with a good
     * trailer (lock.md F16b): nothing is opened. [changedAtMs] is the `created` of the
     * lowest-numbered generation with that newer `vk_id`.
     */
    data class OlderPassword(val changedAtMs: Long) : PasswordResult

    /** This KEK does not open this slot. */
    data object SlotClosed : PasswordResult
}

/** What `VaultStorage.open` found with the keys it was given (architecture.md §4.8). */
sealed interface OpenResult {
    class Opened(val opened: OpenedVault) : OpenResult

    /** The newest usable generation has a `vk_id` with no key here (lock.md F16a); nothing older is tried. */
    data class NeedsOtherKey(val generation: Long, val createdMs: Long) : OpenResult

    data object Damaged : OpenResult

    data object Newer : OpenResult

    /** Reading a file threw; the store never falls back past it. */
    data object Unreadable : OpenResult
}

/** What a save came to (architecture.md §4.9). */
sealed interface CommitResult {
    class Saved(val opened: OpenedVault) : CommitResult

    /** The `syncDir` after the rename failed: not reported as saved, no housekeeping [V-S2]. */
    data object NotDurable : CommitResult

    /** Nothing changed on disk. */
    data object Failed : CommitResult

    /** A NEWER generation is in the live directory: the whole vault is read-only (§4.4). */
    data object ReadOnly : CommitResult

    /** The file would be longer than 16,777,216 bytes, so it was not written (§4.5 rule 5). */
    data object TooLarge : CommitResult
}

/** What `stageVault` and `stageRekey` came to (architecture.md §4.9 steps 1 to 6). */
sealed interface StageResult {
    class Staged(val staged: io.github.dilshodbekmx.passwordvault.core.storage.Staged) : StageResult

    data object Failed : StageResult

    data object TooLarge : StageResult
}
