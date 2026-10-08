package io.github.dilshodbekmx.passwordvault.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `OpenedVault.createdMs` (architecture.md §3.3): the `created` of the generation the opened vault
 * holds, as its header gives it (§4.2), on every path that hands one out: creation and a change of
 * password (`publish`), an ordinary save (`commit`), an unlock with the master password
 * (`openWithSlot`) and with a key (`open`), an older copy, and a vault put aside. It is the time the
 * file was sealed, never the time it was opened.
 */
class CreatedTest {
    private val phone = Phone()
    private val password = "first master password"

    /** The `created` in the header of the generation file at [path]. */
    private fun createdIn(path: String): Long = phone.header(path).createdMs

    private fun opened(result: OpenResult): OpenedVault {
        assertTrue("opened: $result", result is OpenResult.Opened)
        return (result as OpenResult.Opened).opened
    }

    @Test
    fun everyWriteHandsOutTheCreatedTimeOfTheGenerationItPlaced() {
        phone.clock.setWall(1_791_244_800_000L)
        val store = phone.process()
        val (_, created) = phone.create(store, vaultOf(1), password)
        assertEquals(1L, created.generation)
        assertEquals("publish of a new vault", 1_791_244_800_000L, created.createdMs)
        assertEquals(createdIn(live(1)), created.createdMs)

        phone.clock.advance(60_000L)
        val saved = phone.save(store, created, vaultOf(2))
        assertEquals(2L, saved.generation)
        assertEquals("commit", 1_791_244_860_000L, saved.createdMs)
        assertEquals(createdIn(live(2)), saved.createdMs)

        phone.clock.advance(60_000L)
        val (_, changed) = phone.changePassword(store, saved, "second master password")
        assertEquals(3L, changed.generation)
        assertEquals("publish of a new key", 1_791_244_920_000L, changed.createdMs)
        assertEquals(createdIn(live(3)), changed.createdMs)
    }

    @Test
    fun anUnlockHandsOutTheCreatedTimeOfTheFileNotTheTimeItOpened() {
        phone.clock.setWall(1_791_244_800_000L)
        val store = phone.process()
        var (keys, opened) = phone.create(store, vaultOf(1), password)
        phone.clock.advance(5_000L)
        opened = phone.save(store, opened, vaultOf(2))
        phone.clock.advance(86_400_000L) // a day later, in a new process

        val byPassword = phone.unlockOpened(phone.process(), password)
        assertEquals(2L, byPassword.generation)
        assertEquals("openWithSlot", 1_791_244_805_000L, byPassword.createdMs)
        assertEquals(createdIn(live(2)), byPassword.createdMs)

        val byKey = opened(phone.process().open(VaultRef.Current, mapOf(keys.vkId to keys.vk)))
        assertEquals(2L, byKey.generation)
        assertEquals("open", 1_791_244_805_000L, byKey.createdMs)
        assertEquals(opened.createdMs, byKey.createdMs)
    }

    @Test
    fun anOlderCopyHandsOutItsOwnCreatedTime() {
        // The notice of ux.md §5.9 names the copy that opened: "We opened the copy from …".
        phone.clock.setWall(1_791_244_800_000L)
        val store = phone.process()
        var (keys, opened) = phone.create(store, vaultOf(1), password)
        phone.clock.advance(1_000L)
        opened = phone.save(store, opened, vaultOf(2))
        phone.clock.advance(1_000L)
        phone.save(store, opened, vaultOf(3))
        phone.breakBody(live(3))

        val byPassword = phone.unlockOpened(phone.process(), password)
        assertEquals(2L, byPassword.generation)
        assertEquals(3L, byPassword.newestOnDisk)
        assertEquals(1_791_244_801_000L, byPassword.createdMs)

        val byKey = opened(phone.process().open(VaultRef.Current, mapOf(keys.vkId to keys.vk)))
        assertEquals(2L, byKey.generation)
        assertEquals(3L, byKey.newestOnDisk)
        assertEquals(1_791_244_801_000L, byKey.createdMs)
    }

    @Test
    fun aVaultPutAsideHandsOutTheCreatedTimeOfItsOwnGeneration() {
        phone.clock.setWall(1_791_244_800_000L)
        val store = phone.process()
        var (keys, opened) = phone.create(store, vaultOf(1), password)
        phone.clock.advance(7_000L)
        opened = phone.save(store, opened, vaultOf(2))
        phone.clock.advance(1_000L)
        assertTrue(store.putAside())
        phone.clock.advance(1_000L)
        phone.create(store, vaultOf(1, from = 10), "another master password") // a new current vault

        val aside = phone.unlockOpened(phone.process(), password, VaultRef.Aside(keys.vaultId))
        assertEquals(keys.vaultId, aside.vaultId)
        assertEquals(2L, aside.generation)
        assertEquals(1_791_244_807_000L, aside.createdMs)
        val byKey = opened(phone.process().open(VaultRef.Aside(keys.vaultId), mapOf(keys.vkId to keys.vk)))
        assertEquals(1_791_244_807_000L, byKey.createdMs)
        assertEquals(opened.createdMs, byKey.createdMs)
    }
}
