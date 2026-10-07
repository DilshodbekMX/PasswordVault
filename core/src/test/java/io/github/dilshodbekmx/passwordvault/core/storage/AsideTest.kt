package io.github.dilshodbekmx.passwordvault.core.storage

import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.testing.storage.FakeCodec
import io.github.dilshodbekmx.passwordvault.testing.storage.FakeFiles
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Vaults put aside and swapping (architecture.md §4.11, lock.md F16b and F17): one rename puts a
 * whole vault aside; one opens in place, apart from the current one, and only then becomes current
 * or gives its accounts to the current one; nothing put aside is pruned.
 */
class AsideTest {
    private val phone = Phone()
    private val a = "password of A"
    private val b = "password of B"

    private fun scanOf(): Scan = phone.process().scan()

    private fun asideDirs(): List<String> = phone.files.names(VAULTS).filter { it.startsWith("aside-") }

    @Test
    fun puttingAsideMovesTheWholeVaultAndWelcomeListsIt() {
        val store = phone.process()
        var (keys, opened) = phone.create(store, vaultOf(1), a)
        opened = phone.save(store, opened, vaultOf(2))
        assertTrue(store.putAside())
        assertFalse(phone.files.has(LIVE))
        val scan = scanOf()
        assertEquals(Current.NoVault, scan.current)
        val aside = scan.aside.single()
        assertEquals(keys.vaultId, aside.vaultId)
        assertEquals(phone.clock.wallMs(), aside.putAsideAtMs)
        assertEquals(GenerationInfo(2L, keys.vkId, phone.header("$VAULTS/${asideDirs().single()}/vault-0000000002.pvault").createdMs), aside.newest)
        assertEquals(listOf("aside-${phone.clock.wallMs()}-${keys.vaultId}"), asideDirs())
        assertEquals(opened.generation, 2L)
    }

    @Test
    fun withNoVaultThereIsNothingToPutAside() {
        assertFalse(phone.process().putAside())
        phone.files.mkdirs(LIVE)
        assertFalse(phone.process().putAside())
        assertEquals(emptyList<String>(), asideDirs())
    }

    @Test
    fun aVaultPutAsideThatCannotBeReadIsListedWithoutFacts() {
        val store = phone.process()
        val (keys, _) = phone.create(store, vaultOf(1), a)
        assertTrue(store.putAside())
        phone.zeroFill("$VAULTS/${asideDirs().single()}/vault-0000000001.pvault")
        val aside = scanOf().aside.single()
        assertEquals(keys.vaultId, aside.vaultId)
        assertNull(aside.newest)
    }

    @Test
    fun aVaultPutAsideOpensInPlaceAndStaysApart() {
        val store = phone.process()
        val (keysA, _) = phone.create(store, vaultOf(1), a)
        assertTrue(store.putAside())
        phone.clock.advance(1_000L)
        val (keysB, openedB) = phone.create(store, vaultOf(2), b)

        val later = phone.process()
        later.scan()
        val inPlace = (later.open(VaultRef.Aside(keysA.vaultId), mapOf(keysA.vkId to keysA.vk)) as OpenResult.Opened).opened
        assertEquals(vaultOf(1), inPlace.vault)
        assertEquals(keysB.vaultId, (later.scan().current as Current.Present).facts.vaultId)
        assertTrue("the current vault is saved as usual", phone.save(store, openedB, vaultOf(3)).generation == 2L)
        assertEquals(listOf(keysA.vaultId), later.scan().aside.map { it.vaultId })
    }

    @Test
    fun swapInNeedsTheVaultToHaveOpenedInPlaceInThisProcess() {
        val store = phone.process()
        val (keysA, _) = phone.create(store, vaultOf(1), a)
        assertTrue(store.putAside())
        phone.clock.advance(1_000L)
        val (keysB, _) = phone.create(store, vaultOf(2), b)

        val later = phone.process()
        later.scan()
        assertFalse("not opened in place", later.swapIn(keysA.vaultId))
        phone.unlockOpened(later, a, VaultRef.Aside(keysA.vaultId))
        assertTrue(later.swapIn(keysA.vaultId))
        val scan = scanOf()
        assertEquals(keysA.vaultId, (scan.current as Current.Present).facts.vaultId)
        assertEquals(listOf(keysB.vaultId), scan.aside.map { it.vaultId })
        assertEquals(vaultOf(1), phone.unlockOpened(phone.process(), a).vault)
        assertEquals(vaultOf(2), phone.unlockOpened(phone.process(), b, VaultRef.Aside(keysB.vaultId)).vault)
        phone.assertNoReplacement()
    }

    @Test
    fun swapInWithNoCurrentVault() {
        val store = phone.process()
        val (keysA, _) = phone.create(store, vaultOf(1), a)
        assertTrue(store.putAside())
        val later = phone.process()
        phone.unlockOpened(later, a, VaultRef.Aside(keysA.vaultId))
        assertTrue(later.swapIn(keysA.vaultId))
        val scan = scanOf()
        assertEquals(keysA.vaultId, (scan.current as Current.Present).facts.vaultId)
        assertEquals(emptyList<AsideVault>(), scan.aside)
    }

    @Test
    fun theAccountsOfAVaultPutAsideAreMergedIntoTheCurrentOneInOneSave() {
        // F17 step 5 "Open": the merge itself is VaultEdits' (build plan 2.4); storage opens in place and saves once.
        val store = phone.process()
        val (keysA, _) = phone.create(store, vaultOf(3, from = 1), a)
        assertTrue(store.putAside())
        phone.clock.advance(1_000L)
        phone.create(store, vaultOf(3, from = 10), b)

        val later = phone.process()
        later.scan()
        val current = phone.unlockOpened(later, b)
        val aside = phone.unlockOpened(later, a, VaultRef.Aside(keysA.vaultId))
        val ids = current.vault.entries.map { it.id }.toSet()
        val merged = current.vault.copy(entries = current.vault.entries + aside.vault.entries.filter { it.id !in ids })
        val saved = later.commit(current, merged) as CommitResult.Saved
        assertEquals(2L, saved.opened.generation)
        assertEquals(merged, phone.unlockOpened(phone.process(), b).vault)
        val dir = "$VAULTS/${asideDirs().single()}"
        assertEquals("the vault put aside is untouched", listOf("vault-0000000001.pvault"), phone.files.names(dir))
        assertEquals(vaultOf(3, from = 1), phone.unlockOpened(phone.process(), a, VaultRef.Aside(keysA.vaultId)).vault)
    }

    @Test
    fun goingBackFromAnUnfinishedChangePutsTheNewerGenerationsAside() {
        // lock.md F16b: "Open my passwords from before the change".
        val store = phone.process()
        var (_, opened) = phone.create(store, vaultOf(1), a)
        opened = phone.save(store, opened, vaultOf(2))
        val (_, changed) = phone.changePassword(store, opened, b)
        phone.save(store, changed, vaultOf(4))

        val later = phone.process()
        later.scan()
        assertTrue(phone.unlock(later, a) is PasswordResult.OlderPassword)
        assertFalse("never every generation", later.putNewerAside(0))
        assertEquals((1L..4L).toList(), phone.liveNumbers())
        phone.files.fail(FakeFiles.Op.RENAME) { it == live(3) }
        assertFalse("interrupted", later.putNewerAside(2))
        assertTrue("repeated", later.putNewerAside(2))
        assertTrue("again: nothing left to do", later.putNewerAside(2))
        assertEquals(listOf(1L, 2L), phone.liveNumbers())
        assertEquals(listOf(3L, 4L), phone.numbers(Names.ASIDE))
        val reopened = phone.unlockOpened(later, a)
        assertEquals(vaultOf(2), reopened.vault)
        assertEquals("the next save goes above every name", 5L, phone.save(later, reopened).generation)
        assertTrue(phone.files.deleted.none { it.endsWith(Names.ASIDE) })
    }

    @Test
    fun deletingAVaultPutAsideRemovesOnlyThatOne() {
        val store = phone.process()
        val (keysA, _) = phone.create(store, vaultOf(1), a)
        assertTrue(store.putAside())
        phone.clock.advance(1_000L)
        val (keysB, _) = phone.create(store, vaultOf(2), b)
        assertTrue(store.putAside())
        phone.clock.advance(1_000L)
        val (keysC, _) = phone.create(store, vaultOf(3), "password of C")

        val later = phone.process()
        later.deleteAside(keysA.vaultId)
        val scan = later.scan()
        assertEquals(listOf(keysB.vaultId), scan.aside.map { it.vaultId })
        assertEquals(keysC.vaultId, (scan.current as Current.Present).facts.vaultId)
        later.deleteAside(id(99)) // nothing there: nothing happens
        phone.files.fail(FakeFiles.Op.DELETE_TREE)
        try {
            later.deleteAside(keysB.vaultId)
            fail("the caller must learn that it was not deleted")
        } catch (_: IOException) {
        }
        assertEquals(listOf(keysB.vaultId), later.scan().aside.map { it.vaultId })
    }

    @Test
    fun aRestoreOverAVaultThatCannotBeOpenedPutsItAsideStill() {
        // lock.md F17 step 3 from "Your passwords can't be opened": the old files are kept, under a stand-in id.
        val store = phone.process()
        phone.create(store, vaultOf(1), a)
        phone.zeroFill(live(1))
        val later = phone.process()
        assertEquals(Current.Unreadable(Current.Why.DAMAGED), later.scan().current)
        val keys = phone.keys("restored")
        val staged = (later.stageVault(Vault(), keys.vaultId, keys.vk, keys.vkId, keys.slot) as StageResult.Staged).staged
        assertTrue(later.prove(staged, FakeCodec.kek("restored", staged.slotSalt)))
        assertTrue(later.publish(staged, putCurrentAside = true) is CommitResult.Saved)
        val scan = scanOf()
        assertEquals(keys.vaultId, (scan.current as Current.Present).facts.vaultId)
        val aside = scan.aside.single()
        assertTrue(aside.vaultId, aside.vaultId.startsWith("00000000-0000-4000-8000-"))
        assertNull(aside.newest)
        assertEquals(1, phone.files.names("$VAULTS/${asideDirs().single()}").size)
    }
}
