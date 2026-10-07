package io.github.dilshodbekmx.passwordvault.core.storage

import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.testing.storage.FakeCodec
import io.github.dilshodbekmx.passwordvault.testing.storage.FakeFiles
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `scan()` and the four states of one generation file (architecture.md §4.8, D-26): what the lock
 * learns before anything is typed.
 */
class ScanTest {
    private val phone = Phone()

    /** A vault from an earlier process with generations 1 to [count]; returns its keys. */
    private fun vaultWith(count: Int): Keys {
        val store = phone.process()
        var (keys, opened) = phone.create(store, vaultOf(2))
        repeat(count - 1) { opened = phone.save(store, opened) }
        return keys
    }

    private fun present(scan: Scan): VaultFacts {
        assertTrue("present: ${scan.current}", scan.current is Current.Present)
        return (scan.current as Current.Present).facts
    }

    /** [bytes] with the header's `vault_id` changed in its last digit; the trailer fixed only when [fixTrailer]. */
    private fun withOtherVaultId(bytes: ByteArray, fixTrailer: Boolean): ByteArray {
        val text = String(bytes, Charsets.ISO_8859_1)
        val at = text.indexOf("\"vault_id\":\"") + "\"vault_id\":\"".length + 35
        val out = bytes.copyOf()
        out[at] = (if (out[at] == '0'.code.toByte()) '1' else '0').code.toByte()
        if (fixTrailer) {
            val digest = MessageDigest.getInstance("SHA-256").apply { update(out, 0, out.size - 32) }.digest()
            digest.copyInto(out, out.size - 32)
        }
        return out
    }

    @Test
    fun noVaultOnlyWhenLiveIsMissingOrEmpty() {
        assertEquals(Current.NoVault, phone.process().scan().current)
        phone.files.mkdirs(LIVE)
        assertEquals(Current.NoVault, phone.process().scan().current)
    }

    @Test
    fun aDirectoryThatCannotBeListedIsAStorageErrorNeverNoVault() {
        vaultWith(2)
        phone.files.fail(FakeFiles.Op.LIST, times = -1) { it == LIVE }
        assertEquals(Current.Unreadable(Current.Why.IO), phone.process().scan().current)
    }

    @Test
    fun theFactsOfTheNewestReadableGeneration() {
        val keys = vaultWith(3)
        val facts = present(phone.process().scan())
        assertEquals(keys.vaultId, facts.vaultId)
        assertEquals(3L, facts.newest.number)
        assertEquals(keys.vkId, facts.newest.vkId)
        assertEquals(phone.header(live(3)).createdMs, facts.newest.createdMs)
        assertEquals(setOf(keys.vkId), facts.keptVkIds)
        assertFalse(facts.olderCopyFirst)
    }

    @Test
    fun theKeptKeysAreThoseOfEveryLiveGenerationAndNoneInQuarantine() {
        val store = phone.process()
        val (first, opened) = phone.create(store)
        val (second, _) = phone.changePassword(store, phone.save(store, opened), "second master password")
        val other = phone.keys("x")
        phone.files.put(live(9, Names.BAD), phone.codec.seal(Vault(), other.vk, first.vaultId, other.vkId, 9, other.slot))
        val facts = present(phone.process().scan())
        assertEquals(setOf(first.vkId, second.vkId), facts.keptVkIds)
        assertEquals(second.vkId, facts.newest.vkId)
        assertEquals(3L, facts.newest.number)
    }

    @Test
    fun aReadErrorOnTheNewestIsIoAndNothingFallsBackOrIsWritten() {
        val keys = vaultWith(3)
        phone.files.fail(FakeFiles.Op.READ, times = -1) { it == live(3) }
        val store = phone.process()
        assertEquals(Current.Unreadable(Current.Why.IO), store.scan().current)
        val before = phone.files.log.size
        assertEquals("never past it", OpenResult.Unreadable, store.open(VaultRef.Current, mapOf(keys.vkId to keys.vk)))
        assertTrue(phone.files.log.drop(before).all { it.startsWith("list") || it.startsWith("read") })
        assertEquals(listOf(1L, 2L, 3L), phone.liveNumbers())
    }

    @Test
    fun aReadErrorOnAnOldGenerationLeavesTheVaultPresentButItsKeysUnknown() {
        vaultWith(4)
        phone.files.fail(FakeFiles.Op.READ, times = -1) { it == live(2) }
        val facts = present(phone.process().scan())
        assertEquals(4L, facts.newest.number)
        assertNull("its vk_id is unknown, so the lock trims no wrap", facts.keptVkIds)
        assertFalse(facts.olderCopyFirst)
    }

    @Test
    fun aZeroFilledNewestFileIsDamagedAndTheOlderCopyComesFirst() {
        vaultWith(3)
        phone.zeroFill(live(3))
        val facts = present(phone.process().scan())
        assertEquals(2L, facts.newest.number)
        assertTrue(facts.olderCopyFirst)
    }

    @Test
    fun anEmptyTruncatedOrForeignFileIsDamaged() {
        vaultWith(2)
        phone.files.put(live(3), ByteArray(0))
        phone.files.put(live(4), phone.files.bytes(live(2))!!.copyOf(500))
        phone.files.put(live(5), "not a vault at all".toByteArray())
        val facts = present(phone.process().scan())
        assertEquals(2L, facts.newest.number)
        assertTrue(facts.olderCopyFirst)
    }

    @Test
    fun aFileOverTheCapIsDamaged() {
        vaultWith(2)
        phone.files.put(live(3), ByteArray(VaultStore.CAP + 1))
        val facts = present(phone.process().scan())
        assertEquals(2L, facts.newest.number)
        assertTrue(facts.olderCopyFirst)
    }

    @Test
    fun aGenerationWhoseNumberIsNotItsNameIsDamaged() {
        vaultWith(2)
        phone.files.put(live(7), phone.files.bytes(live(2))!!) // generation 2 under the name of 7
        val facts = present(phone.process().scan())
        assertEquals(2L, facts.newest.number)
        assertTrue(facts.olderCopyFirst)
    }

    @Test
    fun anotherVaultIdUnderABadTrailerIsDamaged() {
        val keys = vaultWith(3)
        phone.files.put(live(3), withOtherVaultId(phone.files.bytes(live(3))!!, fixTrailer = false))
        val facts = present(phone.process().scan())
        assertEquals(keys.vaultId, facts.vaultId)
        assertEquals(2L, facts.newest.number)
        assertTrue(facts.olderCopyFirst)
    }

    @Test
    fun theNewestFileWithAGoodTrailerDecidesWhichVaultThisIs() {
        // Its vault_id is the reference; generations of another vault_id are DAMAGED (§4.8, "OK").
        val keys = vaultWith(3)
        phone.files.put(live(2), withOtherVaultId(phone.files.bytes(live(2))!!, fixTrailer = true))
        val facts = present(phone.process().scan())
        assertEquals(keys.vaultId, facts.vaultId)
        assertEquals(3L, facts.newest.number)
        assertFalse("the damaged one is below the newest", facts.olderCopyFirst)
        assertEquals(setOf(keys.vkId), facts.keptVkIds)
    }

    @Test
    fun aLaterFramingWithAGoodTrailerIsNewer() {
        vaultWith(3)
        phone.files.put(live(4), FakeCodec.laterFraming(goodTrailer = true))
        assertEquals(Current.Unreadable(Current.Why.NEWER), phone.process().scan().current)
    }

    @Test
    fun aLaterFramingWithABadTrailerIsDamagedNotNewer() {
        vaultWith(3)
        phone.files.put(live(4), FakeCodec.laterFraming(goodTrailer = false))
        val facts = present(phone.process().scan())
        assertEquals(3L, facts.newest.number)
        assertTrue(facts.olderCopyFirst)
    }

    @Test
    fun aHeaderOfALaterVersionAnywhereIsNewer() {
        val keys = vaultWith(2)
        phone.codec.headerVersion = 2
        val newer = phone.codec.seal(Vault(), keys.vk, keys.vaultId, keys.vkId, 1, keys.slot)
        phone.codec.headerVersion = 1
        phone.files.put(live(1), newer) // below the newest
        assertEquals(Current.Unreadable(Current.Why.NEWER), phone.process().scan().current)
    }

    @Test
    fun noOkFileIsDamaged() {
        vaultWith(2)
        phone.zeroFill(live(1))
        phone.zeroFill(live(2))
        assertEquals(Current.Unreadable(Current.Why.DAMAGED), phone.process().scan().current)
        phone.files.deleteTree(LIVE)
        phone.files.put(live(1, Names.BAD), ByteArray(10))
        assertEquals("only quarantine left", Current.Unreadable(Current.Why.DAMAGED), phone.process().scan().current)
    }

    @Test
    fun startUpDeletesTheUnpublishedAndTouchesNothingElse() {
        vaultWith(2)
        val stale = listOf(live(3, Names.TMP), "$VAULTS/new-${id(5)}/vault-0000000001.tmp", "$VAULTS/new-${id(6)}/vault-0000000001.pvault")
        val kept = listOf(live(2, Names.BAD), live(1, Names.ASIDE), "$LIVE/notes.tmp", "$LIVE/x", "$VAULTS/new-folder/a", "$VAULTS/other")
        for (path in stale + kept) phone.files.put(path, ByteArray(3))
        val facts = present(phone.process().scan())
        assertEquals(2L, facts.newest.number)
        for (path in stale) assertFalse(path, phone.files.has(path))
        assertFalse(phone.files.has("$VAULTS/new-${id(5)}"))
        for (path in kept) assertTrue(path, phone.files.has(path))
        assertEquals(listOf(1L, 2L), phone.liveNumbers())
    }

    @Test
    fun scanAndSlotsOnlyReadAfterStartUp() {
        vaultWith(3)
        val store = phone.process()
        store.scan()
        val before = phone.files.log.size
        store.scan()
        store.slots(VaultRef.Current)
        val calls = phone.files.log.drop(before)
        assertTrue(calls.toString(), calls.all { it.startsWith("list") || it.startsWith("read") })
    }

    @Test
    fun aNewerBodyIsFoundOnlyWhenItOpens() {
        val store = phone.process()
        val (keys, opened) = phone.create(store)
        phone.files.put(live(2), phone.codec.seal(Vault(schema = 2), keys.vk, keys.vaultId, keys.vkId, 2, opened.slot))
        val later = phone.process()
        assertEquals(2L, present(later.scan()).newest.number)
        assertEquals(OpenResult.Newer, later.open(VaultRef.Current, mapOf(keys.vkId to keys.vk)))
        assertEquals("known from now on", Current.Unreadable(Current.Why.NEWER), later.scan().current)
    }
}
