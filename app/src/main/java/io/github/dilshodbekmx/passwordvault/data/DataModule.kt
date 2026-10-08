package io.github.dilshodbekmx.passwordvault.data

import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.Data
import io.github.dilshodbekmx.passwordvault.core.crypto.CryptoFactory
import io.github.dilshodbekmx.passwordvault.core.storage.VaultStores
import io.github.dilshodbekmx.passwordvault.core.vaultfile.VaultCodecs
import io.github.dilshodbekmx.passwordvault.i18n.SortKeys

/**
 * Builds [Data] (architecture.md §3.6; build plan 3.1): one [DefaultVaultRepository], which is both
 * the screens' repository and the lock's port, over the vault store of `<filesDir>/vaults` and the
 * container with the app's `BuildInfo`; and the [ListModelProvider] that follows it and the language
 * the screens are shown in. Every derivation goes through the platform's one Argon2id gate
 * (`Platform.kdf`, §5.2 item 3).
 *
 * Nothing here reads a file: the store does no file work before its first call, and the settings are
 * reached only when the purge of D-24 needs them.
 */
object DataModule {
    fun create(graph: AppGraph): Data {
        val platform = graph.platform
        val codec = VaultCodecs.create(CryptoFactory.aead(), platform.random, platform.clock, graph.build)
        val storage = VaultStores.create(platform.files, codec, platform.clock, platform.vaultsDir, graph.build)
        val repository = DefaultVaultRepository(
            storage = storage,
            codec = codec,
            kdf = platform.kdf,
            random = platform.random,
            clock = platform.clock,
            settings = { graph.settings },
            texts = StoredTexts,
            scope = graph.appScope,
            dispatchers = graph.dispatchers,
        )
        val language = ScreenLanguage.watch(graph.app)
        val lists = ListModelProvider(repository.state, language.locale, ::SortKeys, graph.appScope, graph.dispatchers.default)
        lists.start()
        return Data(repository, repository, lists)
    }
}
