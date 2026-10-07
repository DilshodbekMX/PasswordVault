package io.github.dilshodbekmx.passwordvault.core.storage

import io.github.dilshodbekmx.passwordvault.testing.storage.FakeFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Old keys after a master-password change (architecture.md §4.11, [V-S5], [V-C5]): every older
 * generation still opens with the old password until the new one, typed, has opened the vault
 * from disk in a later process; then `retireOldKeys` deletes them and says which keys are kept.
 */
class RetireTest {
    private val phone = Phone()
    private val first = "first master password"
    private val second = "second master password"

    /** Generations 1 and 2 under the first password, 3 and 4 under the second; returns both keys. */
    private fun changed(): Triple<Keys, Keys, OpenedVault> {
        val store = phone.process()
        var (old, opened) = phone.create(store, vaultOf(1), first)
        opened = phone.save(store, opened, vaultOf(2))
        val (new, changed) = phone.changePassword(store, opened, second)
        val saved = phone.save(store, changed, vaultOf(4))
        assertEquals("not in the process that made the change", setOf(old.vkId, new.vkId), store.retireOldKeys(saved))
        // A lock and an unlock in the same process: the generation that opens was written here.
        assertEquals(setOf(old.vkId, new.vkId), store.retireOldKeys(phone.unlockOpened(store, second)))
        assertEquals((1L..4L).toList(), phone.liveNumbers())
        return Triple(old, new, saved)
    }

    @Test
    fun theOldKeysStayUntilTheNewPasswordOpensInALaterProcess() {
        val (old, new, _) = changed()
        val byPin = phone.process()
        val opened = (byPin.open(VaultRef.Current, mapOf(new.vkId to new.vk)) as OpenResult.Opened).opened
        assertEquals("a shortcut is not the password typed", setOf(old.vkId, new.vkId), byPin.retireOldKeys(opened))
        assertEquals((1L..4L).toList(), phone.liveNumbers())

        val typed = phone.process()
        val proven = phone.unlockOpened(typed, second)
        assertEquals(setOf(new.vkId), typed.retireOldKeys(proven))
        assertEquals(listOf(3L, 4L), phone.liveNumbers())
        assertEquals("the old password opens nothing now", PasswordResult.SlotClosed, phone.unlock(phone.process(), first))
        assertEquals(setOf(new.vkId), typed.retireOldKeys(proven))
    }

    @Test
    fun aFileThatFailedOrCouldNotBeReadIsNotRetired() {
        val (_, new, _) = changed()
        phone.zeroFill(live(1))
        phone.files.fail(FakeFiles.Op.READ, times = -1) { it == live(2) }
        val typed = phone.process()
        typed.scan()
        assertEquals(setOf(new.vkId), typed.retireOldKeys(phone.unlockOpened(typed, second)))
        assertEquals((1L..4L).toList(), phone.liveNumbers())
        phone.files.clearFaults()
        val again = phone.process()
        again.scan()
        again.retireOldKeys(phone.unlockOpened(again, second))
        assertEquals("the one that reads now goes; the damaged one stays", listOf(1L, 3L, 4L), phone.liveNumbers())
    }

    @Test
    fun noRetirementWhenTheOldPasswordOpenedAnOlderCopy() {
        val (old, new, _) = changed()
        phone.breakBody(live(4))
        phone.breakBody(live(3))
        val typed = phone.process()
        val opened = phone.unlockOpened(typed, first) // the newer key's files have bad trailers: an older copy opens
        assertEquals(old.vkId, opened.vkId)
        assertEquals(setOf(old.vkId, new.vkId), typed.retireOldKeys(opened))
        assertEquals((1L..4L).toList(), phone.liveNumbers())
    }

    @Test
    fun aVaultOpenedInPlaceRetiresNothing() {
        val (_, _, saved) = changed()
        val store = phone.process()
        assertTrue(store.putAside())
        phone.create(store, vaultOf(9), "other")
        val later = phone.process()
        val aside = phone.unlockOpened(later, second, VaultRef.Aside(saved.vaultId))
        later.retireOldKeys(aside)
        assertEquals((1L..4L).toList(), phone.numbers(Names.PVAULT, phone.files.names(VAULTS).first { it.startsWith("aside-") }.let { "$VAULTS/$it" }))
    }
}
