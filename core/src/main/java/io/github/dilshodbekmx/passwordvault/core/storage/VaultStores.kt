package io.github.dilshodbekmx.passwordvault.core.storage

import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.core.vaultfile.BuildInfo
import io.github.dilshodbekmx.passwordvault.core.vaultfile.VaultCodec

/**
 * How another package gets the store of package 2.3 without naming its class (architecture.md
 * §2.1, §3.3).
 */
object VaultStores {
    /**
     * `VaultStore` over [files] and [codec], in [vaultsDir] (`<filesDir>/vaults`, which holds
     * `live/`, `aside-<ms>-<vault_id>/` and `new-<vault_id>/`, §4.7). [clock] gives only the
     * wall-clock milliseconds in the name of a directory put aside, for display; retention uses no
     * clock [V-S4]. [build] is the running app's: housekeeping always keeps the newest generation of
     * each `writer_version` lower than its own (§4.10).
     *
     * Make one per process: it remembers what this process opened and wrote (§4.10, §5.1). It does
     * no file work until its first call, which deletes what an earlier process left unpublished.
     */
    fun create(files: FilePrimitives, codec: VaultCodec, clock: Clock, vaultsDir: String, build: BuildInfo): VaultStorage =
        VaultStore(files, codec, clock, vaultsDir, build)
}
