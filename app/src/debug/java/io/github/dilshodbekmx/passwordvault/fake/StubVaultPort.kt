package io.github.dilshodbekmx.passwordvault.fake

import io.github.dilshodbekmx.passwordvault.core.lock.flow.PasswordOpen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultOpen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultPort
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultRef
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultScan
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultWrite
import io.github.dilshodbekmx.passwordvault.core.model.Vault

/**
 * The lock's [VaultPort] while `DataModule` still returns the fakes: nothing calls it, because the
 * fake lock has no engine, and every call throws [NotImplementedError]. `DefaultVaultRepository`
 * (build plan 3.1) is the real one; the JVM tests of the lock use `FakeVaultPort` of `:testing`.
 */
object StubVaultPort : VaultPort {
    private fun missing(): Nothing = throw NotImplementedError("VaultPort: DefaultVaultRepository comes with build plan 3.1")

    override fun vaults(): VaultScan = missing()

    override suspend fun open(target: VaultRef, keys: Map<String, ByteArray>): VaultOpen = missing()

    override suspend fun openWithPassword(target: VaultRef, passwordBytes: ByteArray): PasswordOpen = missing()

    override suspend fun checkPassword(passwordBytes: ByteArray): Boolean? = missing()

    override suspend fun <T> withVaultKey(use: (vkId: String, vk: ByteArray) -> T): T = missing()

    override suspend fun lock(): Unit = missing()

    override suspend fun dropAside(): Unit = missing()

    override suspend fun createVault(vaultId: String, first: ByteArray, again: ByteArray, from: Vault?, putCurrentAside: Boolean): VaultWrite =
        missing()

    override suspend fun replaceVault(newVaultId: String, passwordBytes: ByteArray): VaultWrite = missing()

    override suspend fun newKey(): Pair<String, ByteArray> = missing()

    override suspend fun changePassword(first: ByteArray, again: ByteArray, vkId: String, vk: ByteArray): VaultWrite = missing()

    override suspend fun mergeAside(vaultId: String): VaultWrite = missing()

    override suspend fun swapIn(vaultId: String): Boolean = missing()

    override suspend fun putAside(): Boolean = missing()

    override suspend fun putNewerAside(generation: Long): Boolean = missing()

    override suspend fun deleteAside(vaultId: String): Boolean = missing()

    override suspend fun retireOldKeys(): Set<String> = missing()
}
