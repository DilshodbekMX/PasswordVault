package io.github.dilshodbekmx.passwordvault.core.vaultfile

/** What `VaultCodec.check` found in a file's bytes, with no key and no KDF: rules R1 to R7 of architecture.md §4.4. */
sealed interface FileCheck {
    /** Every rule passed. */
    class Ok(val file: VaultFile) : FileCheck

    /** Not of the PVAULT family (R1). Inside a vault directory this counts as DAMAGED (§4.8). */
    data object NotOurs : FileCheck

    /** Made by a newer app (R1, R6): never opened, never overwritten. [version] is the header's `version`, or the two digits of the magic for a new framing. */
    data class Newer(val version: Int) : FileCheck

    /** [rule] is the number (1 to 7) of the first rule that failed. */
    data class Damaged(val rule: Int) : FileCheck
}
