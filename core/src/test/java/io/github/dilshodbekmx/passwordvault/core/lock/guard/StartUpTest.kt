package io.github.dilshodbekmx.passwordvault.core.lock.guard

import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinFixture.Companion.RIGHT
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinFixture.Companion.SLOT
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinFixture.Companion.VK_ID
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinFixture.Companion.pin
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.util.ParseError
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * lock.md test T6, the start-up step of §6.3 S, with the crash points of §6.10: a charge found is
 * counted and marked interrupted; reaching a limit turns the PIN off; a failed start-up write leaves
 * the PIN unusable in that process, with the "storage is full" reason.
 */
class StartUpTest {
    private val f = PinFixture()
    private val alias = PinWrap.alias(SLOT)
    private val lastWrong = 1_791_000_000_000L

    @Test
    fun t6_aChargeFoundIsCountedAndMarkedInterrupted() {
        f.store.set(f.record(row = 1, total = 3, charged = true, unseen = PinRecord.Unseen(2, lastWrong, false)))
        val guard = f.guard()
        val start = guard.startUp()

        assertTrue(start.read is RecordRead.Ok)
        assertTrue(start.interrupted)
        assertFalse(start.limit)
        assertFalse(start.storageFull)
        val counted = f.record(row = 2, total = 4, unseen = PinRecord.Unseen(3, lastWrong, true))
        assertEquals(counted, guard.record)
        assertEquals(counted, f.store.current) // written
        assertEquals(1, f.store.writes.size)
        assertEquals(3, guard.triesLeft)
        assertTrue(guard.canCharge(Place.visit(1)))
        assertTrue(alias in f.keys.aliases)
        assertEquals(0, f.keys.hmacCalls.size) // counted without being checked: nothing was learned
    }

    @Test
    fun t6_aChargeThatReachesTheRowLimitTurnsThePinOffInTheSameWrite() {
        f.store.set(f.record(row = 4, total = 4, charged = true))
        val guard = f.guard()
        val start = guard.startUp()

        assertTrue(start.interrupted)
        assertTrue(start.limit)
        assertFalse(start.storageFull)
        val off = guard.record!!
        assertNull(off.slot)
        assertEquals(PinRecord.Off(PinRecord.OffReason.LIMIT, f.clock.wallMs()), off.off)
        assertEquals(PinRecord.Guard(charged = false, row = 5, total = 5), off.guard)
        assertEquals(PinRecord.Unseen(1, null, true), off.unseen)
        assertEquals(listOf(off), f.store.writes.map { it.record }) // one write
        assertEquals(listOf(alias), f.keys.deleted) // the key at once (§6.5 step 2)
        assertFalse(alias in f.keys.aliases)
        assertFalse(guard.canCharge(Place.visit(1)))
        assertEquals(PinCharge.NOT_USABLE, guard.charge(Place.visit(1)))
    }

    @Test
    fun t6_aChargeThatReachesTheTotalLimitTurnsThePinOff() {
        f.store.set(f.record(row = 0, total = 9, charged = true))
        val start = f.guard().startUp()
        assertTrue(start.limit)
        assertNull(f.store.current!!.slot)
        assertEquals(PinRecord.Guard(charged = false, row = 1, total = 10), f.store.current!!.guard)
        assertEquals(listOf(alias), f.keys.deleted)
    }

    @Test
    fun t6_aFailedStartUpWriteLeavesThePinUnusableInThatProcessWithStorageFull() {
        f.store.set(f.record(row = 1, total = 1, charged = true))
        f.store.failNextWrite()
        val guard = f.guard()
        val start = guard.startUp()

        assertTrue(start.interrupted)
        assertTrue(start.storageFull)
        assertTrue(guard.storageFull)
        assertEquals(PinRecord.Guard(charged = false, row = 2, total = 2), guard.record!!.guard) // counted in memory
        assertTrue(f.store.current!!.guard.charged) // the charge stays on disk
        val visit = Place.visit(1)
        assertFalse(guard.canCharge(visit))
        assertEquals(PinCharge.NOT_USABLE, guard.charge(visit))
        // the storage works again an hour later, and the PIN is still not offered in this process
        f.clock.advance(3_600_000)
        assertEquals(PinCharge.NOT_USABLE, guard.charge(Place.visit(2)))
        assertEquals(PinOutcome.NotCharged, runNow { guard.check(RIGHT, setOf(VK_ID)) })
        assertEquals(0, f.keys.hmacCalls.size)

        // the next process counts the charge once (it is the same try), and offers the PIN again
        val next = f.guard()
        val again = next.startUp()
        assertTrue(again.interrupted)
        assertFalse(again.storageFull)
        assertEquals(PinRecord.Guard(charged = false, row = 2, total = 2), next.record!!.guard)
        assertTrue(next.canCharge(Place.visit(3)))
    }

    @Test
    fun t6_aStartUpWriteThatFailsAtALimitIsDoneAgainAtTheNextStart() {
        f.store.set(f.record(row = 4, total = 4, charged = true))
        f.store.failNextWrite()
        val guard = f.guard()
        val start = guard.startUp()
        assertTrue(start.limit)
        assertTrue(start.storageFull)
        assertNull(guard.record!!.slot) // off in memory for this process (§6.5)
        assertNull(f.keys.pinKeyOf(alias)) // and the key is deleted at once
        assertTrue(f.store.current!!.guard.charged)

        val next = f.guard().startUp()
        assertTrue(next.limit)
        assertFalse(next.storageFull)
        assertNull(f.store.current!!.slot)
        assertEquals(PinRecord.Guard(charged = false, row = 5, total = 5), f.store.current!!.guard)
    }

    @Test
    fun t6_withoutAChargeNothingIsWritten() {
        f.store.set(f.record(row = 2, total = 7))
        val guard = f.guard()
        val start = guard.startUp()
        assertFalse(start.interrupted)
        assertFalse(start.storageFull)
        assertEquals(0, f.store.writes.size)
        assertEquals(f.record(row = 2, total = 7), guard.record)
        assertEquals(3, guard.triesLeft)
    }

    @Test
    fun t6_onlyARecordThatReadsOkOffersAPin() {
        val results = listOf<RecordRead<PinRecord>>(
            RecordRead.Missing,
            RecordRead.Damaged(ParseError(12, "hash")),
            RecordRead.Newer,
            RecordRead.Unreadable,
        )
        for (result in results) {
            val store = FakeRecordStore<PinRecord>(f.record(row = 1, charged = true))
            store.readAs = result
            val guard = PinGuard(PinFixture.VAULT, store, f.clock, f.keys.pin, f.keys.device, f.aead, FakeRandom(3))
            val start = guard.startUp()
            assertEquals(result, start.read)
            assertFalse(start.interrupted) // a charge in a record that does not read OK is not counted from it
            assertNull(guard.record)
            assertFalse(guard.canCharge(Place.visit(1)))
            guard.masterPasswordCorrect()
            guard.giveUp()
            assertTrue(guard.keepWrapsFor(setOf(VK_ID)))
            assertTrue(guard.unseenShown(1, interruptedShown = true))
            assertEquals("$result is never rewritten automatically", 0, store.writes.size)
        }
    }

    @Test
    fun t6_crashAfterTheChargeBeforeTheCheckIsOneWrongTryAtTheNextStart() {
        // lock.md §6.10, first row: the process dies after the charge, before the check
        f.store.set(f.record())
        val first = f.guard().also { it.startUp() }
        assertEquals(PinCharge.CHARGED, first.charge(Place.visit(1)))
        f.store.kill()

        val next = f.guard()
        val start = next.startUp()
        assertTrue(start.interrupted)
        assertEquals(PinRecord.Guard(charged = false, row = 1, total = 1), next.record!!.guard)
        assertEquals(PinRecord.Unseen(1, null, true), next.record!!.unseen)
        assertEquals(0, f.keys.hmacCalls.size) // nothing was learned
    }

    @Test
    fun t6_crashAfterAWrongResultBeforeItsWriteGivesTheSameCount() {
        // §6.10, second row
        f.store.set(f.record(row = 1, total = 1))
        val first = f.guard().also { it.startUp() }
        val visit = Place.visit(1)
        assertEquals(PinCharge.CHARGED, first.charge(visit))
        f.store.stopAtNextWrite(handedOver = false)
        try {
            runNow { first.check(pin("111222"), setOf(VK_ID)) }
            throw AssertionError("the write should have stopped the process")
        } catch (stopped: FakeRecordStore.Stopped) {
        }
        assertTrue(f.store.current!!.guard.charged)

        val next = f.guard().also { it.startUp() }
        assertEquals(PinRecord.Guard(charged = false, row = 2, total = 2), next.record!!.guard) // as if the write had happened
    }

    @Test
    fun t6_crashAfterARightResultBeforeItsWriteCountsOneTryNextTime() {
        // §6.10, third row: the list was shown, the background write never ran
        f.store.set(f.record())
        val first = f.guard().also { it.startUp() }
        assertTrue(first.enter(RIGHT, Place.visit(1)) is PinOutcome.Right)
        assertTrue(f.store.current!!.guard.charged) // not yet flushed
        f.store.kill()

        val next = f.guard()
        assertTrue(next.startUp().interrupted)
        assertEquals(PinRecord.Guard(charged = false, row = 1, total = 1), next.record!!.guard)
    }

    @Test
    fun t6_crashAtALimitBeforeTheWriteTurnsThePinOffAtTheNextStart() {
        // §6.10, fourth row: charged = true with row 4
        f.store.set(f.record(row = 4, total = 4))
        val first = f.guard().also { it.startUp() }
        assertEquals(PinCharge.CHARGED, first.charge(Place.visit(1)))
        f.store.stopAtNextWrite(handedOver = false)
        try {
            runNow { first.check(pin("111222"), setOf(VK_ID)) }
            throw AssertionError("the write should have stopped the process")
        } catch (stopped: FakeRecordStore.Stopped) {
        }
        assertTrue(alias in f.keys.aliases) // the process died before the key was deleted

        val start = f.guard().startUp()
        assertTrue(start.limit)
        assertNull(f.store.current!!.slot)
        assertFalse(alias in f.keys.aliases)
    }

    @Test
    fun t6_aStartUpForgetsWhatTheGuardHeldInMemory() {
        f.store.set(f.record())
        val guard = f.guard().also { it.startUp() }
        val visit = Place.visit(1)
        assertEquals(PinOutcome.Wrong(4), guard.enter(pin("111222"), visit))
        assertEquals(PinCharge.CHARGED, guard.charge(visit))
        assertTrue(f.store.current!!.guard.charged)
        assertTrue(guard.startUp().interrupted) // a vault put aside opens its lock screen again, or a new process
        assertNull(guard.chargedAt) // the charge in memory is forgotten; the one on disk is counted
        assertEquals(PinRecord.Guard(charged = false, row = 2, total = 2), guard.record!!.guard)
        assertEquals(guard.record, f.store.current)
    }
}
