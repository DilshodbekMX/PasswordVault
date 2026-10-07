package io.github.dilshodbekmx.passwordvault.core.model

/**
 * The result of one pure change to the vault (architecture.md §3.5): `VaultEdits` (package 2.4)
 * gives every change as `(Vault, nowMs) -> Edited<T>`, and `VaultRepository.edit` writes [vault]
 * as a new generation and hands [value] back to the caller.
 *
 * [vault] is the input itself, the same object, when there is nothing to save; the repository
 * then writes nothing.
 */
class Edited<T>(val vault: Vault, val value: T)
