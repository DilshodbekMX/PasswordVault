package io.github.dilshodbekmx.passwordvault.core.vaultfile

import io.github.dilshodbekmx.passwordvault.core.crypto.Aead
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.core.util.RandomSource

/**
 * How another package gets the codec of package 2.2 without naming its class (architecture.md
 * §2.1, §3.3). The body throws [NotImplementedError] until package 2.2 (Vault file codec) is merged
 * and replaces it.
 */
object VaultCodecs {
    /**
     * `DefaultVaultCodec` over [aead], drawing nonces from [random] and the `created` time from
     * [clock], and writing [build] into every header.
     */
    fun create(aead: Aead, random: RandomSource, clock: Clock, build: BuildInfo): VaultCodec =
        TODO("package 2.2 (Vault file codec)")
}
