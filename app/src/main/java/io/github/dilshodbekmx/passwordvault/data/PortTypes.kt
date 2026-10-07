package io.github.dilshodbekmx.passwordvault.data

import io.github.dilshodbekmx.passwordvault.core.lock.flow.CurrentVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultScan
import io.github.dilshodbekmx.passwordvault.core.storage.Current
import io.github.dilshodbekmx.passwordvault.core.storage.Scan
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideVault as LockAsideVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.GenerationInfo as LockGenerationInfo
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultRef as LockVaultRef
import io.github.dilshodbekmx.passwordvault.core.storage.AsideVault as StoreAsideVault
import io.github.dilshodbekmx.passwordvault.core.storage.GenerationInfo as StoreGenerationInfo
import io.github.dilshodbekmx.passwordvault.core.storage.VaultRef as StoreVaultRef

/**
 * The lock's types of the vault and the storage engine's types of the same names (architecture.md
 * §3.4): the lock never names `core.storage` (R-10, lock.md T17), so [DefaultVaultRepository] maps
 * between the two here, one to one.
 */
internal object PortTypes {
    fun scanOf(scan: Scan): VaultScan = VaultScan(currentOf(scan.current), scan.aside.map(::asideOf))

    fun currentOf(current: Current): CurrentVault = when (current) {
        Current.NoVault -> CurrentVault.NoVault
        is Current.Present -> CurrentVault.Present(
            vaultId = current.facts.vaultId,
            newest = generationOf(current.facts.newest),
            keptVkIds = current.facts.keptVkIds,
            olderCopyFirst = current.facts.olderCopyFirst,
        )
        is Current.Unreadable -> CurrentVault.Unreadable(whyOf(current.why))
    }

    fun whyOf(why: Current.Why): CurrentVault.Why = when (why) {
        Current.Why.IO -> CurrentVault.Why.IO
        Current.Why.DAMAGED -> CurrentVault.Why.DAMAGED
        Current.Why.NEWER -> CurrentVault.Why.NEWER
    }

    fun generationOf(info: StoreGenerationInfo): LockGenerationInfo = LockGenerationInfo(info.number, info.vkId, info.createdMs)

    fun asideOf(aside: StoreAsideVault): LockAsideVault = LockAsideVault(aside.vaultId, aside.putAsideAtMs, aside.newest?.let(::generationOf))

    fun refOf(target: LockVaultRef): StoreVaultRef = when (target) {
        LockVaultRef.Current -> StoreVaultRef.Current
        is LockVaultRef.Aside -> StoreVaultRef.Aside(target.vaultId)
    }
}
