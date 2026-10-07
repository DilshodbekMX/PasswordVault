package io.github.dilshodbekmx.passwordvault.data

import io.github.dilshodbekmx.passwordvault.core.model.EditTexts

/**
 * The texts that the repository's own changes store in the vault (architecture.md §3.7, §5.4):
 * only "(from backup)", which `VaultPort.mergeAside` gives an account of a vault put aside whose
 * id is held by an account with other content (§4.11, ux.md §5.21).
 *
 * Stored texts belong in the string resources, resolved in the language the screens show, but no
 * string file belongs to package 3.1 (build plan §5, 3.1 "Owns"); its interface request asks for
 * one. Until it is granted these are the English texts of every build before wave 6. Nothing calls
 * `mergeAside` before the lock flows of build plan 3.14 and 4.1, so no stored title is made with
 * them in wave 3A.
 */
internal object StoredTexts : EditTexts {
    override fun untitled(nowMs: Long): String = "Untitled"

    override fun fromBackup(title: String): String = "$title (from backup)"

    override fun fromFile(title: String): String = "$title (from file)"
}
