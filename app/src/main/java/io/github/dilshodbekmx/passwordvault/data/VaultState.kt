package io.github.dilshodbekmx.passwordvault.data

import io.github.dilshodbekmx.passwordvault.core.model.Vault

/**
 * The vault as the screens see it (architecture.md §3.5, §5.4): an immutable snapshot that
 * [VaultRepository.state] publishes after every durable save, and [Locked] as soon as the vault is
 * dropped.
 */
sealed interface VaultState {
    /** Nothing is open. Also "no vault at all": `LockManager.state` decides which screen that is. */
    data object Locked : VaultState

    /**
     * The open vault.
     *
     * @property vault the decrypted body; immutable, an edit builds a new one.
     * @property generation the generation on disk that holds [vault] (architecture.md §4.9).
     * @property saveProblem why the last save did not happen, until the next save succeeds; null
     *   when it did.
     * @property olderCopy the vault was opened from an older copy because the newest could not be
     *   read (architecture.md §4.8): automatic writes wait until the older-copy notice is answered
     *   (§5.5).
     *
     * A plain class: every publication is a new object, so `StateFlow` never compares two vaults.
     */
    class Unlocked(val vault: Vault, val generation: Long, val saveProblem: SaveProblem?, val olderCopy: Boolean) : VaultState {
        override fun toString(): String =
            "VaultState.Unlocked(generation=$generation, entries=${vault.entries.size}, saveProblem=$saveProblem, olderCopy=$olderCopy)"
    }
}
