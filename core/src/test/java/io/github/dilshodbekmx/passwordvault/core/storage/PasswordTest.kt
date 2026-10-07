package io.github.dilshodbekmx.passwordvault.core.storage

import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.testing.storage.FakeCodec
import io.github.dilshodbekmx.passwordvault.testing.storage.FakeFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Opening with the master password (architecture.md §4.8): `slots()` lists every distinct slot
 * among the kept generations, newest first, and "wrong" is only what no slot opens [V-S3].
 */
class PasswordTest {
    private val phone = Phone()
    private val first = "first master password"
    private val second = "second master password"

    @Test
    fun oneSlotNormallyTwoAfterAChangeNewestFirst() {
        val store = phone.process()
        var (keys1, opened) = phone.create(store)
        opened = phone.save(store, opened)
        assertEquals(1, phone.process().slots(VaultRef.Current).size)
        val (keys2, _) = phone.changePassword(store, opened, second)
        val slots = phone.process().slots(VaultRef.Current)
        assertEquals(2, slots.size)
        assertEquals(keys2.vkId, slots[0].vkId)
        assertEquals(3L, slots[0].generation)
        assertEquals(keys1.vkId, slots[1].vkId)
        assertEquals("the newest generation that carries it", 2L, slots[1].generation)
        assertTrue(slots[1].salt.contentEquals(keys1.slot.salt))
    }

    @Test
    fun theRightPasswordOpensAndAWrongOneOpensNoSlot() {
        val store = phone.process()
        var (keys, opened) = phone.create(store, vaultOf(2))
        opened = phone.save(store, opened, vaultOf(3))
        val later = phone.process()
        val reopened = phone.unlockOpened(later, first)
        assertEquals(2L, reopened.generation)
        assertEquals(vaultOf(3), reopened.vault)
        assertEquals(keys.vkId, reopened.vkId)
        for (slot in later.slots(VaultRef.Current)) {
            assertEquals(PasswordResult.SlotClosed, later.openWithSlot(VaultRef.Current, slot, FakeCodec.kek("wrong", slot.salt)))
        }
        assertEquals(opened.generation, reopened.generation)
    }

    @Test
    fun anOlderPasswordOpensOnlyAnOlderCopyAndNothingIsOpened() {
        val store = phone.process()
        val (_, opened) = phone.create(store)
        phone.clock.advance(86_400_000L)
        val (_, changed) = phone.changePassword(store, phone.save(store, opened), second)
        phone.clock.advance(86_400_000L)
        phone.save(store, changed)
        val later = phone.process()
        val result = phone.unlock(later, first)
        assertEquals(PasswordResult.OlderPassword(phone.header(live(3)).createdMs), result)
        assertEquals("the new one opens", 4L, phone.unlockOpened(later, second).generation)
    }

    @Test
    fun theWrongPasswordIsOnlyWhatNoSlotOpens() {
        // V-S3: generations sealed under different passwords; the first slot tried does not open, the second does.
        val store = phone.process()
        val (_, opened) = phone.create(store)
        phone.changePassword(store, opened, second)
        val later = phone.process()
        val slots = later.slots(VaultRef.Current)
        assertEquals(PasswordResult.SlotClosed, later.openWithSlot(VaultRef.Current, slots[0], FakeCodec.kek(first, slots[0].salt)))
        val result = later.openWithSlot(VaultRef.Current, slots[1], FakeCodec.kek(first, slots[1].salt))
        assertTrue("not wrong: $result", result is PasswordResult.OlderPassword)
    }

    @Test
    fun damageConfinedToTheTrailerStillOpens() {
        val store = phone.process()
        var (_, opened) = phone.create(store, vaultOf(1))
        opened = phone.save(store, opened, vaultOf(2))
        phone.breakTrailer(live(2))
        val reopened = phone.unlockOpened(phone.process(), first)
        assertEquals("a body that opens under VK is good even when the trailer is wrong", 2L, reopened.generation)
        assertEquals(vaultOf(2), reopened.vault)
    }

    @Test
    fun aNewerKeysFileWithABadTrailerDoesNotMakeItAnOlderPassword() {
        val store = phone.process()
        val (_, opened) = phone.create(store, vaultOf(1))
        val saved = phone.save(store, opened, vaultOf(2))
        phone.changePassword(store, saved, second)
        phone.breakBody(live(3))
        val reopened = phone.unlockOpened(phone.process(), first)
        assertEquals(2L, reopened.generation)
        assertEquals(3L, reopened.newestOnDisk)
    }

    @Test
    fun theSlotOpensButEveryGenerationOfItsKeyIsDamaged() {
        val store = phone.process()
        var (_, opened) = phone.create(store)
        opened = phone.save(store, opened)
        phone.breakBody(live(1))
        phone.breakBody(live(2))
        val later = phone.process()
        val slot = later.slots(VaultRef.Current).single()
        val kek = FakeCodec.kek(first, slot.salt)
        assertEquals(VaultStore.SlotOutcome.Damaged, later.slotOutcome(VaultRef.Current, slot, kek))
        // The public result has no case for this yet (interface request); it never claims an open.
        val result = later.openWithSlot(VaultRef.Current, slot, kek)
        assertFalse(result is PasswordResult.Opened || result is PasswordResult.OlderPassword)
    }

    @Test
    fun theSlotOpensButAFileAboveCannotBeRead() {
        val store = phone.process()
        var (_, opened) = phone.create(store)
        opened = phone.save(store, opened)
        val later = phone.process()
        val slot = later.slots(VaultRef.Current).single()
        phone.breakBody(live(2))
        phone.files.fail(FakeFiles.Op.READ, times = -1) { it == live(2) }
        assertEquals(VaultStore.SlotOutcome.Unreadable, later.slotOutcome(VaultRef.Current, slot, FakeCodec.kek(first, slot.salt)))
    }

    @Test
    fun theSlotOpensButANewerAppWroteTheBody() {
        val store = phone.process()
        val (keys, opened) = phone.create(store)
        phone.files.put(live(2), phone.codec.seal(Vault(schema = 2), keys.vk, keys.vaultId, keys.vkId, 2, opened.slot))
        val later = phone.process()
        val slot = later.slots(VaultRef.Current).single()
        assertEquals(VaultStore.SlotOutcome.Newer, later.slotOutcome(VaultRef.Current, slot, FakeCodec.kek(first, slot.salt)))
    }

    @Test
    fun aVaultPutAsideOpensInPlaceWithItsOwnPassword() {
        val store = phone.process()
        val (keys, _) = phone.create(store, vaultOf(2))
        assertTrue(store.putAside())
        val later = phone.process()
        val reopened = phone.unlockOpened(later, first, VaultRef.Aside(keys.vaultId))
        assertEquals(vaultOf(2), reopened.vault)
        assertEquals(Current.NoVault, later.scan().current)
    }
}
