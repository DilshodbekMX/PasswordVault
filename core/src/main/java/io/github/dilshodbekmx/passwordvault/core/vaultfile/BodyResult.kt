package io.github.dilshodbekmx.passwordvault.core.vaultfile

import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.util.ParseError

/** What `VaultCodec.open` found when it opened a body with a key: rules R8 to R10 of architecture.md §4.4. */
sealed interface BodyResult {
    data class Opened(val vault: Vault) : BodyResult

    /** R8: the tag failed, so this file does not open under this key. */
    data object TagFailed : BodyResult

    /** R9 or R10 refused the plaintext; [error] says where and why, never with a piece of it (§5.6). */
    data class Damaged(val error: ParseError) : BodyResult

    /** R9: `schema` is higher than 1, so a newer app wrote it. */
    data class Newer(val schema: Long) : BodyResult
}
