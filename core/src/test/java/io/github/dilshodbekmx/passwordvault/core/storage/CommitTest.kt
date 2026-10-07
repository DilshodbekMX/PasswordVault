package io.github.dilshodbekmx.passwordvault.core.storage

import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.testing.storage.FakeCodec
import io.github.dilshodbekmx.passwordvault.testing.storage.FakeFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An ordinary save (architecture.md §4.9 steps 1 to 6 and 8 to 10). */
class CommitTest {
    private val phone = Phone()

    /** A vault opened in a second process, with generations 1 and 2 on disk. */
    private fun openedVault(): Pair<VaultStore, OpenedVault> {
        val store = phone.process()
        var (_, opened) = phone.create(store, vaultOf(1))
        opened = phone.save(store, opened, vaultOf(2))
        val later = phone.process()
        later.scan()
        return later to phone.unlockOpened(later, "first master password")
    }

    private fun writes(since: Int) = phone.files.log.drop(since).filter { it.startsWith("writeNew") || it.startsWith("rename") }

    @Test
    fun aSaveIsANewGenerationThatOpensInTheNextProcess() {
        val (store, opened) = openedVault()
        val saved = store.commit(opened, vaultOf(5)) as CommitResult.Saved
        assertEquals(3L, saved.opened.generation)
        assertEquals(3L, saved.opened.newestOnDisk)
        assertEquals(vaultOf(5), saved.opened.vault)
        assertEquals(listOf(1L, 2L, 3L), phone.liveNumbers())
        assertEquals("the slot is copied byte for byte (§4.5 rule 4)", phone.header(live(2)).slot, phone.header(live(3)).slot)
        assertEquals(vaultOf(5), phone.unlockOpened(phone.process(), "first master password").vault)
        phone.assertNoReplacement()
    }

    @Test
    fun theNumberIsAboveEveryNameOnDisk() {
        // V-S1: whatever the suffix, so a save never lands on a file that failed, was put aside or is unknown.
        val (store, opened) = openedVault()
        phone.files.put(live(20, Names.BAD), ByteArray(4))
        phone.files.put(live(31, Names.ASIDE), ByteArray(4))
        phone.files.put(live(44, Names.TMP), ByteArray(4)) // after start-up: a staged file of this process
        phone.files.put("$LIVE/vault-0000000050.future", ByteArray(4))
        phone.files.put("$LIVE/vault-99.pvault", ByteArray(4))
        val saved = store.commit(opened, vaultOf(3)) as CommitResult.Saved
        assertEquals(51L, saved.opened.generation)
        assertTrue(phone.files.has(live(51)))
        phone.assertNoReplacement()
    }

    @Test
    fun aTargetThatAppearsBeforeTheRenameIsNeverReplaced() {
        val (store, opened) = openedVault()
        val intruder = byteArrayOf(1, 2, 3)
        for (suffix in listOf(Names.PVAULT, Names.BAD, Names.ASIDE)) {
            val number = phone.files.names(LIVE).mapNotNull { Names.numberOf(it) }.max() + 1
            phone.files.beforeOp = { op, path ->
                if (op == FakeFiles.Op.WRITE_NEW && path == live(number, Names.TMP)) phone.files.put(live(number, suffix), intruder)
            }
            assertEquals(suffix, CommitResult.Failed, store.commit(opened, vaultOf(3)))
            phone.files.beforeOp = null
            assertTrue(suffix, phone.files.bytes(live(number, suffix))!!.contentEquals(intruder))
            assertFalse("the .tmp is deleted", phone.files.has(live(number, Names.TMP)))
        }
        phone.assertNoReplacement()
        assertEquals("then a save goes above them", 6L, (store.commit(opened, vaultOf(3)) as CommitResult.Saved).opened.generation)
    }

    @Test
    fun theCheckInMemoryAbortsAWriterBugBeforeAnythingIsWritten() {
        val (store, opened) = openedVault()
        val before = phone.files.log.size
        phone.codec.sealHook = { bytes -> bytes.also { it[it.size - 100] = (it[it.size - 100].toInt() xor 1).toByte() } }
        assertEquals(CommitResult.Failed, store.commit(opened, vaultOf(3)))
        phone.codec.sealHook = { bytes -> bytes.copyOf(bytes.size - 1) }
        assertEquals(CommitResult.Failed, store.commit(opened, vaultOf(3)))
        phone.codec.sealHook = null
        assertEquals(emptyList<String>(), writes(before))
    }

    @Test
    fun theWriteTimeRulesOfTheBody() {
        // §4.3: every id a valid UUID v4 and unique, no string with an unpaired surrogate. Nothing is written.
        val (store, opened) = openedVault()
        val good = entry(1)
        val bad = listOf(
            Vault(entries = listOf(good.copy(id = "not-an-id"))),
            Vault(entries = listOf(good.copy(id = UPPER_CASE_ID))),
            Vault(entries = listOf(good, entry(2).copy(id = good.id))),
            Vault(entries = listOf(good.copy(notes = "half \uD83D of a pair"))),
            Vault(entries = listOf(good.copy(passwordHistory = listOf(io.github.dilshodbekmx.passwordvault.core.model.HistoryItem("\uDC00", 1L))))),
            Vault(entries = listOf(good.copy(extras = mapOf("k" to "\"\uD800\"")))),
            Vault(entries = listOf(good), extras = mapOf("\uDFFF" to "1")),
            Vault(entries = listOf(good), masterPasswordMatches = listOf("\uD800")),
        )
        val before = phone.files.log.size
        for (vault in bad) assertEquals(vault.toString(), CommitResult.Failed, store.commit(opened, vault))
        assertEquals(emptyList<String>(), writes(before))
        val pair = Vault(entries = listOf(good.copy(notes = "a whole pair 🔑 is fine")))
        assertTrue(store.commit(opened, pair) is CommitResult.Saved)
    }

    @Test
    fun aReadBackThatDiffersIsFailedAndTheTmpDeleted() {
        val (store, opened) = openedVault()
        phone.files.beforeOp = { op, path ->
            if (op == FakeFiles.Op.READ && path.endsWith(Names.TMP)) phone.files.damage(path) { it.copyOf(it.size - 1) }
        }
        assertEquals(CommitResult.Failed, store.commit(opened, vaultOf(3)))
        phone.files.beforeOp = null
        assertEquals(emptyList<Long>(), phone.numbers(Names.TMP))
        assertEquals(listOf(1L, 2L), phone.liveNumbers())
    }

    @Test
    fun aWriteOrRenameThatFailsChangesNothing() {
        val (store, opened) = openedVault()
        phone.files.fail(FakeFiles.Op.WRITE_NEW)
        assertEquals(CommitResult.Failed, store.commit(opened, vaultOf(3)))
        phone.files.fail(FakeFiles.Op.RENAME)
        assertEquals(CommitResult.Failed, store.commit(opened, vaultOf(3)))
        phone.files.fail(FakeFiles.Op.READ) { it.endsWith(Names.TMP) }
        assertEquals(CommitResult.Failed, store.commit(opened, vaultOf(3)))
        assertEquals(emptyList<Long>(), phone.numbers(Names.TMP))
        assertEquals(listOf(1L, 2L), phone.liveNumbers())
        assertEquals(vaultOf(2), phone.unlockOpened(phone.process(), "first master password").vault)
    }

    @Test
    fun aFailedDirectorySyncIsNotDurableAndTryAgainGoesAbove() {
        // V-S2: not reported as saved; "Try again" starts again at step 2.
        val (store, opened) = openedVault()
        phone.files.fail(FakeFiles.Op.SYNC_DIR) { it == LIVE }
        assertEquals(CommitResult.NotDurable, store.commit(opened, vaultOf(3)))
        assertTrue("the file is in place, its name maybe not durable", phone.files.has(live(3)))
        val again = store.commit(opened, vaultOf(3)) as CommitResult.Saved
        assertEquals(4L, again.opened.generation)
        phone.assertNoReplacement()
    }

    @Test
    fun aNewerGenerationMakesTheVaultReadOnly() {
        val (store, opened) = openedVault()
        phone.files.put(live(3), FakeCodec.laterFraming())
        assertEquals(CommitResult.ReadOnly, store.commit(opened, vaultOf(3)))
        assertEquals(listOf(3L), phone.liveNumbers().filter { it > 2 })
        assertTrue(phone.files.bytes(live(3))!!.contentEquals(FakeCodec.laterFraming()))
    }

    @Test
    fun aFileLongerThanTheCapIsNeverWritten() {
        val (store, opened) = openedVault()
        val huge = Vault(entries = listOf(Entry(id = id(1), title = "big", notes = "x".repeat(8_400_000), createdAt = 1L, updatedAt = 1L)))
        val before = phone.files.log.size
        assertEquals(CommitResult.TooLarge, store.commit(opened, huge))
        assertEquals(emptyList<String>(), writes(before))
    }

    @Test
    fun aKeyThatWasWipedIsNeverSealedUnder() {
        // At a lock the repository zeroes VK through useKey; a save that came after it must not write a generation sealed under zeros.
        val (store, opened) = openedVault()
        store.useKey(opened) { it.fill(0) }
        val before = phone.files.log.size
        assertEquals(CommitResult.Failed, store.commit(opened, vaultOf(3)))
        assertEquals(StageResult.Failed, store.stageRekey(opened, vaultOf(3), ByteArray(32), id(9), phone.keys("x").slot))
        assertEquals(emptyList<String>(), writes(before))
    }

    @Test
    fun onlyTheCurrentVaultIsSavedInto() {
        val store = phone.process()
        val (keys, _) = phone.create(store, vaultOf(1))
        assertTrue(store.putAside())
        val (_, current) = phone.create(store, vaultOf(2), "another")
        val later = phone.process()
        val aside = phone.unlockOpened(later, "first master password", VaultRef.Aside(keys.vaultId))
        assertEquals("a vault opened in place is not written to", CommitResult.Failed, later.commit(aside, vaultOf(3)))
        assertEquals(current.vaultId, phone.header(live(1)).vaultId)
    }
}
