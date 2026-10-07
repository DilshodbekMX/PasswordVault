package io.github.dilshodbekmx.passwordvault.core.vaultfile

import io.github.dilshodbekmx.passwordvault.core.crypto.Aead
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.core.util.RandomSource

/**
 * How another package gets the codec of package 2.2 without naming its class (architecture.md
 * §2.1, §3.3).
 */
object VaultCodecs {
    /**
     * `DefaultVaultCodec` over [aead], drawing nonces from [random] and the `created` time from
     * [clock], and writing [build] into every header.
     *
     * @throws IllegalArgumentException when [build] cannot stand in a header: a generator outside
     *   `[A-Za-z0-9 ._()-]{1,64}` (the build check R-14 keeps the app's own inside it) or a writer
     *   version below 1.
     */
    fun create(aead: Aead, random: RandomSource, clock: Clock, build: BuildInfo): VaultCodec =
        DefaultVaultCodec(aead, random, clock, build)
}
