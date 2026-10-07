package io.github.dilshodbekmx.passwordvault.core.storage

import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.storage.Housekeeping.Gen
import io.github.dilshodbekmx.passwordvault.core.vaultfile.BuildInfo
import io.github.dilshodbekmx.passwordvault.testing.storage.FakeFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which generations are kept (architecture.md §4.10): five verified ones of the current key, never
 * a file that failed, nothing below a file that could not be read, the newest of each older app
 * version, no clock [V-S4]; and only after a durable save [V-S2].
 */
class HousekeepingTest {
    private val password = "first master password"
    private val day = 86_400_000L

    // ---------------------------------------------------------------------------------------------
    // The rule

    private fun ok(range: LongRange, vk: String = "k", writer: Int = 1) = range.map { Gen(it, FileState.OK, vk, writer) }

    @Test
    fun theTriggerIsDueAfterTheFirstDurableSaveAndAfterEveryTwentiethMore() {
        val t = Housekeeping.Trigger()
        assertFalse(t.due)
        t.saved(durable = false)
        assertFalse("only after a syncDir that succeeded", t.due)
        t.saved(durable = true)
        assertTrue(t.due)
        t.ran()
        assertFalse(t.due)
        repeat(19) { t.saved(durable = true) }
        assertFalse(t.due)
        t.saved(durable = true)
        assertTrue(t.due)
        t.saved(durable = false)
        assertFalse("the last save was not durable", t.due)
        t.saved(durable = true)
        assertTrue("a run that did not happen stays due", t.due)
    }

    @Test
    fun fiveVerifiedThenEverythingOfTheKeyBelowThemGoes() {
        assertEquals((1L..5L).reversed().toList(), Housekeeping.deletions(ok(1L..10L), "k", (6L..10L).toList(), emptySet(), 1))
    }

    @Test
    fun withFewerThanFiveVerifiedNothingIsDeleted() {
        assertEquals(emptyList<Long>(), Housekeeping.deletions(ok(1L..10L), "k", (7L..10L).toList(), emptySet(), 1))
        // A verified number that is not an OK generation of this key does not count.
        val gens = ok(1L..9L) + Gen(10, FileState.OK, "other", 1)
        assertEquals(emptyList<Long>(), Housekeeping.deletions(gens, "k", (6L..10L).toList(), emptySet(), 1))
    }

    @Test
    fun nothingBelowAFileThatCouldNotBeReadOrThatANewerAppWrote() {
        val unreadable = ok(1L..10L).map { if (it.number == 3L) Gen(3, FileState.UNREADABLE, null, null) else it }
        assertEquals(listOf(5L, 4L), Housekeeping.deletions(unreadable, "k", (6L..10L).toList(), emptySet(), 1))
        val newer = ok(1L..10L).map { if (it.number == 4L) Gen(4, FileState.NEWER, null, null) else it }
        assertEquals(listOf(5L), Housekeeping.deletions(newer, "k", (6L..10L).toList(), emptySet(), 1))
    }

    @Test
    fun theNewestGenerationOfEachOlderWriterVersionIsKept() {
        val gens = ok(1L..4L, writer = 1) + ok(5L..6L, writer = 2) + ok(7L..12L, writer = 3)
        assertEquals(listOf(7L, 5L, 3L, 2L, 1L), Housekeeping.deletions(gens, "k", (8L..12L).toList(), emptySet(), 3))
    }

    @Test
    fun ofTheGenerationsThisProcessWroteTheNewestTwentyStay() {
        val gens = ok(1L..40L)
        val deleted = Housekeeping.deletions(gens, "k", (1L..5L).toList(), (6L..40L).toSet(), 1)
        assertEquals((6L..20L).reversed().toList(), deleted)
    }

    @Test
    fun anotherKeyAndAFileThatFailedAreNeverDeleted() {
        val gens = ok(1L..3L, vk = "old") + listOf(Gen(4, FileState.DAMAGED, null, null)) + ok(5L..12L)
        assertEquals(listOf(7L, 6L, 5L), Housekeeping.deletions(gens, "k", (8L..12L).toList(), emptySet(), 1))
    }

    // ---------------------------------------------------------------------------------------------
    // The store

    /** A vault whose generations 1 to [count] an earlier process wrote; generation n holds vaultOf(n). */
    private fun earlier(phone: Phone, count: Int): Keys {
        val store = phone.process()
        var (keys, opened) = phone.create(store, vaultOf(1), password)
        for (n in 2..count) opened = phone.save(store, opened, vaultOf(n))
        return keys
    }

    /** A new process: scan, unlock with the password, one save of [content], housekeeping when due. */
    private fun day(phone: Phone, content: Vault): OpenedVault {
        val store = phone.process()
        store.scan()
        val saved = phone.save(store, phone.unlockOpened(store, password), content)
        store.housekeeping(saved)
        phone.clock.advance(day)
        return saved
    }

    @Test
    fun theFiveNewestVerifiedCopiesStayAndTheOldOnesGo() {
        val phone = Phone()
        earlier(phone, 10)
        val store = phone.process()
        store.scan()
        val opened = phone.unlockOpened(store, password)
        assertEquals(10L, opened.generation)
        val saved = phone.save(store, opened, vaultOf(11))
        store.housekeeping(saved)
        assertEquals((6L..11L).toList(), phone.liveNumbers())
        assertEquals(vaultOf(11), phone.unlockOpened(phone.process(), password).vault)
    }

    @Test
    fun itRunsAfterTheFirstDurableSaveAndAfterEveryTwentiethMore() {
        val phone = Phone()
        earlier(phone, 10)
        val store = phone.process()
        store.scan()
        var opened = phone.unlockOpened(store, password)
        opened = phone.save(store, opened, vaultOf(11))
        store.housekeeping(opened)
        assertEquals((6L..11L).toList(), phone.liveNumbers())
        for (n in 12..30) { // saves 2 to 20 of this process: W is 11..30, twenty files
            opened = phone.save(store, opened, vaultOf(n))
            store.housekeeping(opened)
        }
        assertEquals((6L..30L).toList(), phone.liveNumbers())
        opened = phone.save(store, opened, vaultOf(31)) // save 21: due, and W has 21 files
        store.housekeeping(opened)
        assertEquals((6L..10L).toList() + (12L..31L).toList(), phone.liveNumbers())
    }

    @Test
    fun aVaultMadeInThisProcessIsNotPrunedInIt() {
        val phone = Phone()
        val store = phone.process()
        var (_, opened) = phone.create(store, vaultOf(1), password)
        for (n in 2..45) {
            opened = phone.save(store, opened, vaultOf(n))
            store.housekeeping(opened)
        }
        assertEquals("no L, nothing verified (§4.10)", (1L..45L).toList(), phone.liveNumbers())
        // A lock and an unlock in the same process verify nothing either: every file is in W.
        val reopened = phone.unlockOpened(store, password)
        store.housekeeping(phone.save(store, reopened, vaultOf(46)))
        assertEquals((1L..46L).toList(), phone.liveNumbers())
    }

    @Test
    fun aSaveThatIsNotDurablePrunesNothing() {
        // V-S2: prune only after a later save's directory sync succeeds.
        val phone = Phone()
        earlier(phone, 10)
        val store = phone.process()
        store.scan()
        val opened = phone.unlockOpened(store, password)
        phone.files.fail(FakeFiles.Op.SYNC_DIR) { it == LIVE }
        assertEquals(CommitResult.NotDurable, store.commit(opened, vaultOf(11)))
        store.housekeeping(opened)
        assertEquals((1L..11L).toList(), phone.liveNumbers())
        val saved = phone.save(store, opened, vaultOf(12))
        store.housekeeping(saved)
        assertEquals((6L..12L).toList(), phone.liveNumbers())
    }

    @Test
    fun severalUnlocksInOneProcess() {
        // §8.1: unlock, three saves, lock, unlock, thirty saves: the five copies verified before this process stay.
        val phone = Phone()
        earlier(phone, 10)
        val store = phone.process()
        store.scan()
        var opened = phone.unlockOpened(store, password)
        repeat(3) { i ->
            opened = phone.save(store, opened, vaultOf(11 + i))
            store.housekeeping(opened)
        }
        // Lock: the repository drops what it holds; the store keeps what this process learned.
        store.scan()
        opened = phone.unlockOpened(store, password)
        assertEquals(13L, opened.generation)
        repeat(30) { i ->
            opened = phone.save(store, opened, vaultOf(14 + i))
            store.housekeeping(opened)
        }
        val live = phone.liveNumbers()
        assertTrue("the five verified copies: $live", live.containsAll((6L..10L).toList()))
        assertEquals("and the newest twenty written here", (24L..43L).toList(), live.filter { it > 10 }.takeLast(20))
    }

    @Test
    fun whatDidNotOpenAboveTheFirstUnlockGoesToQuarantine() {
        val phone = Phone()
        earlier(phone, 10)
        phone.breakBody(live(9)) // passes check; fails only under its key
        phone.zeroFill(live(10)) // fails check: known before unlock
        val store = phone.process()
        assertTrue((store.scan().current as Current.Present).facts.olderCopyFirst)
        val opened = phone.unlockOpened(store, password)
        assertEquals(8L, opened.generation)
        store.housekeeping(phone.save(store, opened, vaultOf(11)))
        assertEquals(listOf(9L, 10L), phone.numbers(Names.BAD))
        assertEquals((4L..8L).toList() + 11L, phone.liveNumbers())
        assertTrue("quarantine is never deleted", phone.files.deleted.none { it.endsWith(Names.BAD) })
    }

    @Test
    fun aDamagedGenerationBelowIsQuarantinedOnTheWay() {
        val phone = Phone()
        earlier(phone, 10)
        phone.breakBody(live(8))
        val store = phone.process()
        store.scan()
        val opened = phone.unlockOpened(store, password)
        store.housekeeping(phone.save(store, opened, vaultOf(11)))
        assertEquals(listOf(8L), phone.numbers(Names.BAD))
        assertEquals(listOf(5L, 6L, 7L, 9L, 10L, 11L), phone.liveNumbers())
    }

    @Test
    fun aReadErrorOnAnOldGenerationRenamesNothingAndStopsPruningBelowIt() {
        val phone = Phone()
        earlier(phone, 12)
        phone.files.fail(FakeFiles.Op.READ, times = -1) { it == live(3) }
        val store = phone.process()
        val facts = (store.scan().current as Current.Present).facts
        assertEquals(12L, facts.newest.number)
        val opened = phone.unlockOpened(store, password)
        assertEquals(vaultOf(12), opened.vault)
        store.housekeeping(phone.save(store, opened, vaultOf(13)))
        assertEquals(listOf(1L, 2L, 3L) + (8L..13L).toList(), phone.liveNumbers())
        assertEquals(emptyList<Long>(), phone.numbers(Names.BAD))
    }

    @Test
    fun aFileThatCannotBeReadEndsTheWalk() {
        val phone = Phone()
        earlier(phone, 12)
        phone.files.fail(FakeFiles.Op.READ, times = -1) { it == live(11) }
        val store = phone.process()
        store.scan()
        store.housekeeping(phone.save(store, phone.unlockOpened(store, password), vaultOf(13)))
        assertEquals("one verified copy only: nothing is deleted", (1L..13L).toList(), phone.liveNumbers())
    }

    @Test
    fun theLastCopyOfEachEarlierAppVersionStays() {
        val phone = Phone(build = BuildInfo("PasswordVault 1.0", 1))
        earlier(phone, 4)
        phone.build = BuildInfo("PasswordVault 1.1", 2)
        for (n in 5..14) day(phone, vaultOf(n))
        val live = phone.liveNumbers()
        assertTrue("the newest copy written by version 1: $live", 4L in live)
        assertFalse(1L in live || 2L in live || 3L in live)
        assertEquals(1, phone.header(live(4)).writerVersion)
    }

    @Test
    fun badAndAsideFilesAreNeverDeletedNorAnythingPutAside() {
        val phone = Phone()
        val keys = earlier(phone, 10)
        phone.files.put(live(1, Names.BAD), ByteArray(5))
        phone.files.put(live(2, Names.ASIDE), ByteArray(5))
        phone.files.put("$VAULTS/aside-0000000000001-${id(77)}/vault-0000000001.pvault", ByteArray(5))
        for (n in 11..60) day(phone, vaultOf(n))
        assertTrue(phone.files.has(live(1, Names.BAD)))
        assertTrue(phone.files.has(live(2, Names.ASIDE)))
        assertTrue(phone.files.has("$VAULTS/aside-0000000000001-${id(77)}/vault-0000000001.pvault"))
        assertTrue(phone.files.deleted.none { it.endsWith(Names.BAD) || it.endsWith(Names.ASIDE) || "/aside-" in it })
        assertEquals(keys.vaultId, phone.header(live(60)).vaultId)
    }

    // ---------------------------------------------------------------------------------------------
    // V-S4: three ways the old rule deleted every good copy. A bad save is one the save could not
    // see: it read back fine, and the next process finds it damaged.

    /** Runs [days] days, a bad save on the days [bad] says; checks every day that the last good copy is on disk and opens. */
    private fun scenario(phone: Phone, days: Int, bad: (Int) -> Boolean, beforeDay: (Int) -> Unit = {}): List<String> {
        val start = phone.unlockOpened(phone.process(), password) // the last good copy is the one that opens
        var lastGood = start.generation
        var lastGoodVault = start.vault
        for (d in 1..days) {
            beforeDay(d)
            val saved = day(phone, vaultOf(100 + d))
            if (bad(d)) {
                phone.zeroFill(live(saved.generation))
            } else {
                lastGood = saved.generation
                lastGoodVault = saved.vault
            }
            assertTrue("day $d: the last good copy $lastGood is on disk: ${phone.liveNumbers()}", lastGood in phone.liveNumbers())
            assertEquals("day $d", lastGoodVault, phone.unlockOpened(phone.process(), password).vault)
        }
        return phone.files.names(LIVE)
    }

    @Test
    fun vS4aBadEarlySavesOnANewInstall() {
        val phone = Phone()
        val store = phone.process()
        var (_, opened) = phone.create(store, vaultOf(1), password)
        for (n in 2..7) {
            opened = phone.save(store, opened, vaultOf(n))
            store.housekeeping(opened)
        }
        for (n in 3L..7L) phone.zeroFill(live(n)) // saves 3 to 7 were bad
        assertEquals(vaultOf(2), phone.unlockOpened(phone.process(), password).vault)
        scenario(phone, 10, bad = { false })
        assertTrue(phone.numbers(Names.BAD).containsAll((3L..7L).toList()))
        assertTrue("and pruning goes on once five copies are verified: ${phone.liveNumbers()}", phone.liveNumbers().size <= 7)
    }

    @Test
    fun vS4bAClockJumpChangesNothing() {
        fun run(jump: Boolean): List<String> {
            val phone = Phone()
            earlier(phone, 1)
            scenario(phone, 20, bad = { false })
            return scenario(phone, 5, bad = { true }) { d -> if (d == 1 && jump) phone.clock.jumpWall(30 * day) }
        }
        val withJump = run(jump = true)
        val without = run(jump = false)
        assertEquals("retention uses no clock", without, withJump)
    }

    @Test
    fun vS4cAFaultLastingFifteenDays() {
        val phone = Phone()
        earlier(phone, 1)
        scenario(phone, 5, bad = { false })
        scenario(phone, 15, bad = { true })
        scenario(phone, 10, bad = { false })
        assertEquals("every bad copy is in quarantine", 15, phone.numbers(Names.BAD).size)
        assertTrue("and pruning goes on: ${phone.liveNumbers()}", phone.liveNumbers().size <= 7)
    }
}
