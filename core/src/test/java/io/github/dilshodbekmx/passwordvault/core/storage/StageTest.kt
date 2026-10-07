package io.github.dilshodbekmx.passwordvault.core.storage

import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.testing.storage.FakeCodec
import io.github.dilshodbekmx.passwordvault.testing.storage.FakeFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Writes that carry a new slot (architecture.md §4.9): `stageVault` (creation, restore, lock.md
 * F11) and `stageRekey` (F8), each proven from disk with the second typing before `publish`, so
 * that a live generation never carries an unproven slot.
 */
class StageTest {
    private val phone = Phone()

    private fun staged(result: StageResult): Staged {
        assertTrue("staged: $result", result is StageResult.Staged)
        return (result as StageResult.Staged).staged
    }

    @Test
    fun aNewVaultIsStagedProvenAndPublished() {
        val store = phone.process()
        val keys = phone.keys("pass")
        val staged = staged(store.stageVault(vaultOf(2), keys.vaultId, keys.vk, keys.vkId, keys.slot))
        assertEquals(keys.vaultId, staged.vaultId)
        assertEquals(keys.vkId, staged.vkId)
        assertEquals(1L, staged.generation)
        assertTrue("the salt as read back", staged.slotSalt.contentEquals(keys.slot.salt))
        assertTrue(phone.files.has("$VAULTS/new-${keys.vaultId}/vault-0000000001.tmp"))
        assertFalse("nothing is live yet", phone.files.has(LIVE))
        assertTrue(store.prove(staged, FakeCodec.kek("pass", staged.slotSalt)))
        val saved = store.publish(staged, putCurrentAside = false) as CommitResult.Saved
        assertEquals(1L, saved.opened.generation)
        assertFalse(phone.files.has("$VAULTS/new-${keys.vaultId}"))
        assertEquals(listOf(1L), phone.liveNumbers())
        val facts = (phone.process().scan().current as Current.Present).facts
        assertEquals(keys.vaultId, facts.vaultId)
        assertEquals(vaultOf(2), phone.unlockOpened(phone.process(), "pass").vault)
        phone.assertNoReplacement()
    }

    @Test
    fun aProofWithAnotherSecondTypingQuarantinesAndPublishesNothing() {
        val store = phone.process()
        val keys = phone.keys("pass")
        val staged = staged(store.stageVault(Vault(), keys.vaultId, keys.vk, keys.vkId, keys.slot))
        assertFalse(store.prove(staged, FakeCodec.kek("pasS", staged.slotSalt)))
        assertTrue(phone.files.has("$VAULTS/new-${keys.vaultId}/vault-0000000001.bad"))
        assertFalse(store.prove(staged, FakeCodec.kek("pass", staged.slotSalt)))
        assertEquals("a live generation never carries an unproven slot", CommitResult.Failed, store.publish(staged, false))
        assertFalse(phone.files.has(LIVE))
        store.discard(staged)
        assertFalse(phone.files.has("$VAULTS/new-${keys.vaultId}"))
    }

    @Test
    fun publishWithoutAProofIsRefused() {
        val store = phone.process()
        val keys = phone.keys("pass")
        val staged = staged(store.stageVault(Vault(), keys.vaultId, keys.vk, keys.vkId, keys.slot))
        assertEquals(CommitResult.Failed, store.publish(staged, false))
        assertFalse(phone.files.has(LIVE))
        assertTrue("still staged", store.prove(staged, FakeCodec.kek("pass", staged.slotSalt)))
        assertTrue(store.publish(staged, false) is CommitResult.Saved)
    }

    @Test
    fun aStagedVaultThatIsDiscardedLeavesNothing() {
        val store = phone.process()
        val keys = phone.keys("pass")
        val staged = staged(store.stageVault(Vault(), keys.vaultId, keys.vk, keys.vkId, keys.slot))
        store.discard(staged)
        assertEquals(emptyList<String>(), phone.files.names(VAULTS))
        assertEquals(CommitResult.Failed, store.publish(staged, false))
    }

    @Test
    fun aVaultIdThatIsNotAUuidNeverBecomesADirectory() {
        val store = phone.process()
        val keys = phone.keys("pass")
        for (bad in listOf("../live", "live", "", UPPER_CASE_ID)) {
            assertEquals(bad, StageResult.Failed, store.stageVault(Vault(), bad, keys.vk, keys.vkId, keys.slot))
        }
        assertEquals(emptyList<String>(), phone.files.names(VAULTS))
    }

    @Test
    fun aNewVaultBesideTheCurrentOneNeedsPutCurrentAside() {
        val store = phone.process()
        val (old, _) = phone.create(store, vaultOf(1))
        val keys = phone.keys("restored")
        val first = staged(store.stageVault(vaultOf(2), keys.vaultId, keys.vk, keys.vkId, keys.slot))
        assertTrue(store.prove(first, FakeCodec.kek("restored", first.slotSalt)))
        assertEquals("never over the current vault", CommitResult.Failed, store.publish(first, putCurrentAside = false))
        assertEquals(old.vaultId, phone.header(live(1)).vaultId)

        val again = staged(store.stageVault(vaultOf(2), keys.vaultId, keys.vk, keys.vkId, keys.slot))
        assertTrue(store.prove(again, FakeCodec.kek("restored", again.slotSalt)))
        assertTrue(store.publish(again, putCurrentAside = true) is CommitResult.Saved)
        val scan = phone.process().scan()
        assertEquals(keys.vaultId, (scan.current as Current.Present).facts.vaultId)
        assertEquals(listOf(old.vaultId), scan.aside.map { it.vaultId })
        assertEquals(phone.clock.wallMs(), scan.aside.single().putAsideAtMs)
        assertEquals(1L, scan.aside.single().newest!!.number)
        phone.assertNoReplacement()
    }

    @Test
    fun aNewKeyIsStagedInLiveProvenAndPublished() {
        val store = phone.process()
        val (old, opened) = phone.create(store, vaultOf(1))
        val keys = phone.keys("second", opened.vaultId)
        val staged = staged(store.stageRekey(opened, vaultOf(1), keys.vk, keys.vkId, keys.slot))
        assertEquals(2L, staged.generation)
        assertTrue(phone.files.has(live(2, Names.TMP)))
        assertTrue(store.prove(staged, FakeCodec.kek("second", staged.slotSalt)))
        val saved = store.publish(staged, false) as CommitResult.Saved
        assertEquals(keys.vkId, saved.opened.vkId)
        assertEquals(2L, saved.opened.generation)
        val facts = (phone.process().scan().current as Current.Present).facts
        assertEquals(keys.vkId, facts.newest.vkId)
        assertEquals(setOf(old.vkId, keys.vkId), facts.keptVkIds)
        assertEquals(2L, phone.unlockOpened(phone.process(), "second").generation)
    }

    @Test
    fun aNewKeyThatDoesNotProveIsQuarantinedAndTheVaultIsAsItWas() {
        // lock.md F8 step 6: "Couldn't change your master password. Nothing was changed."
        val store = phone.process()
        val (_, opened) = phone.create(store, vaultOf(1))
        val keys = phone.keys("second", opened.vaultId)
        val staged = staged(store.stageRekey(opened, vaultOf(1), keys.vk, keys.vkId, keys.slot))
        assertFalse(store.prove(staged, FakeCodec.kek("secomd", staged.slotSalt)))
        assertTrue(phone.files.has(live(2, Names.BAD)))
        assertEquals(CommitResult.Failed, store.publish(staged, false))
        store.discard(staged)
        assertTrue("never deleted", phone.files.has(live(2, Names.BAD)))
        assertEquals(listOf(1L), phone.liveNumbers())
        assertEquals("the next save goes above the quarantined one", 3L, phone.save(store, opened).generation)
        assertEquals(PasswordResult.SlotClosed, phone.unlock(phone.process(), "second"))
    }

    @Test
    fun aNewKeyMustBeNew() {
        val store = phone.process()
        val (keys, opened) = phone.create(store)
        assertEquals(StageResult.Failed, store.stageRekey(opened, opened.vault, keys.vk, keys.vkId, keys.slot))
    }

    @Test
    fun aNewKeyIsNotPublishedAboveALaterSaveOrBesideTheVault() {
        val store = phone.process()
        val (_, opened) = phone.create(store, vaultOf(1))
        val keys = phone.keys("second", opened.vaultId)
        val staged = staged(store.stageRekey(opened, vaultOf(1), keys.vk, keys.vkId, keys.slot))
        assertTrue(store.prove(staged, FakeCodec.kek("second", staged.slotSalt)))
        assertEquals(CommitResult.Failed, store.publish(staged, putCurrentAside = true))
        phone.save(store, opened) // generation 3, above the staged 2
        assertEquals(CommitResult.Failed, store.publish(staged, false))
        assertEquals(listOf(1L, 3L), phone.liveNumbers())
        assertFalse(phone.files.has(live(2, Names.TMP)))
    }

    @Test
    fun aStageThatCannotWriteLeavesNothing() {
        val store = phone.process()
        val keys = phone.keys("pass")
        phone.files.fail(FakeFiles.Op.WRITE_NEW)
        assertEquals(StageResult.Failed, store.stageVault(Vault(), keys.vaultId, keys.vk, keys.vkId, keys.slot))
        assertEquals(emptyList<String>(), phone.files.names(VAULTS))
        val huge = Vault(entries = listOf(entry(1).copy(notes = "x".repeat(8_400_000))))
        assertEquals(StageResult.TooLarge, store.stageVault(huge, keys.vaultId, keys.vk, keys.vkId, keys.slot))
        assertEquals(emptyList<String>(), phone.files.names(VAULTS))
    }

    @Test
    fun aFailedSyncInTheNewDirectoryPublishesNothing() {
        val store = phone.process()
        val keys = phone.keys("pass")
        val staged = staged(store.stageVault(Vault(), keys.vaultId, keys.vk, keys.vkId, keys.slot))
        assertTrue(store.prove(staged, FakeCodec.kek("pass", staged.slotSalt)))
        phone.files.fail(FakeFiles.Op.SYNC_DIR) { it.endsWith("new-${keys.vaultId}") }
        assertEquals(CommitResult.Failed, store.publish(staged, false))
        assertEquals(Current.NoVault, phone.process().scan().current)
    }

    @Test
    fun aFailedSyncOfVaultsIsNotDurable() {
        val store = phone.process()
        val keys = phone.keys("pass")
        val staged = staged(store.stageVault(Vault(), keys.vaultId, keys.vk, keys.vkId, keys.slot))
        assertTrue(store.prove(staged, FakeCodec.kek("pass", staged.slotSalt)))
        phone.files.fail(FakeFiles.Op.SYNC_DIR) { it == VAULTS }
        assertEquals(CommitResult.NotDurable, store.publish(staged, false))
        assertTrue("in place, maybe not durable", phone.files.has(live(1)))
    }
}
