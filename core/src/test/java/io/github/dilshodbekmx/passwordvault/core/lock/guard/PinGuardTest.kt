package io.github.dilshodbekmx.passwordvault.core.lock.guard

import io.github.dilshodbekmx.passwordvault.core.lock.flow.KeyCreate
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinKey
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinFixture.Companion.RIGHT
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinFixture.Companion.SLOT
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinFixture.Companion.VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinFixture.Companion.VK
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinFixture.Companion.VK_ID
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinFixture.Companion.pin
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.core.lock.record.KeyWrap
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.lock.record.ShortcutSlot
import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import io.github.dilshodbekmx.passwordvault.core.util.ParseError
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** [PinGuard]'s steps one at a time (lock.md §6.3 to §6.5, §5.4); T6, T7 and T8 have files of their own. */
class PinGuardTest {
    private val f = PinFixture()
    private val alias = PinWrap.alias(SLOT)
    private val vk2Id = "00000000-0000-4000-8000-000000000004"
    private val vk2: ByteArray get() = FakeRandom.sequence(0x60, 32)

    // ---------------------------------------------------------------------------------------------
    // C, the charge

    @Test
    fun theChargeIsWrittenBeforeAnythingElse() {
        val guard = f.started()
        val visit = Place.visit(1)
        assertEquals(PinCharge.CHARGED, guard.charge(visit))
        assertSame(visit, guard.chargedAt)
        assertEquals(PinRecord.Guard(charged = true, row = 0, total = 0), f.store.current!!.guard)
        assertEquals(1, f.store.writes.size)
        assertEquals(PinCharge.NOT_USABLE, guard.charge(visit)) // a charge is held
        assertEquals(PinCharge.NOT_USABLE, guard.charge(Place.visit(2)))
    }

    @Test
    fun aChargeThatCannotBeWrittenHoldsNothingAndCountsNothing() {
        val guard = f.started(row = 1, total = 2)
        f.store.failNextWrite()
        val visit = Place.visit(1)
        assertEquals(PinCharge.STORAGE_FULL, guard.charge(visit))
        assertNull(guard.chargedAt)
        assertEquals(PinRecord.Guard(charged = false, row = 1, total = 2), guard.record!!.guard)
        assertEquals(PinOutcome.NotCharged, runNow { guard.check(RIGHT, setOf(VK_ID)) })
        assertEquals(0, f.keys.hmacCalls.size)
        // the storage works again: the next first digit charges
        assertEquals(PinCharge.CHARGED, guard.charge(visit))
    }

    @Test
    fun noChargeWithoutASlotAtALimitOrAtAPlaceThatEnded() {
        val noSlot = f.started().also { it.removeSlot(PinRecord.OffReason.KEY_GONE) }
        assertEquals(PinCharge.NOT_USABLE, noSlot.charge(Place.visit(1)))
        assertEquals(PinCharge.NOT_USABLE, f.started(row = 5).charge(Place.visit(1)))
        assertEquals(PinCharge.NOT_USABLE, f.started(total = 10).charge(Place.visit(1)))
        val ended = Place.visit(2).also { it.end() }
        assertEquals(PinCharge.NOT_USABLE, f.started().charge(ended))
        val missing = f.guard().also { f.store.set(null); it.startUp() }
        assertEquals(PinCharge.NOT_USABLE, missing.charge(Place.visit(3)))
    }

    // ---------------------------------------------------------------------------------------------
    // A, giving up

    @Test
    fun givingUpRefundsTheChargeAndNoCount() {
        val guard = f.started(row = 2, total = 6, unseen = PinRecord.Unseen(2, 5L, true))
        val before = guard.record
        assertEquals(PinCharge.CHARGED, guard.charge(Place.visit(1)))
        guard.giveUp()
        assertNull(guard.chargedAt)
        assertEquals(before, guard.record)
        assertEquals(before, f.store.current)
        val writes = f.store.writes.size
        guard.giveUp() // nothing held: nothing written
        assertEquals(writes, f.store.writes.size)
    }

    @Test
    fun aRefundThatCannotBeWrittenLeavesTheTryCounted() {
        val guard = f.started()
        assertEquals(PinCharge.CHARGED, guard.charge(Place.visit(1)))
        f.store.failNextWrite()
        guard.giveUp()
        assertTrue(f.store.current!!.guard.charged) // the safe direction (§6.3 A.3)
        f.store.kill()
        assertTrue(f.guard().startUp().interrupted)
    }

    @Test
    fun theEndOfThePlaceRefundsItsChargeOnly() {
        val guard = f.started()
        val visit = Place.visit(1)
        assertEquals(PinCharge.CHARGED, guard.charge(visit))
        guard.placeEnded(Place.visit(2)) // another visit: nothing of this one
        assertSame(visit, guard.chargedAt)
        guard.placeEnded(visit)
        assertTrue(visit.hasEnded)
        assertNull(guard.chargedAt)
        assertFalse(f.store.current!!.guard.charged)
    }

    // ---------------------------------------------------------------------------------------------
    // E, the check

    @Test
    fun theCheckWaitsUntilTheChargeIsOneSecondOld() = runBlocking {
        val clock = FakeClock(elapsedMs = 50_000L)
        val held = PinFixture(clock)
        val guard = held.started()
        clock.autoAdvance = false
        assertEquals(PinCharge.CHARGED, guard.charge(Place.visit(1)))
        val check = async(start = CoroutineStart.UNDISPATCHED) { guard.check(RIGHT, setOf(VK_ID)) }
        assertEquals(listOf(51_000L), clock.waits)
        assertEquals(0, held.keys.hmacCalls.size)
        clock.advance(999)
        yield()
        assertEquals(0, held.keys.hmacCalls.size) // the charge is not old enough yet
        clock.advance(1)
        assertTrue(check.await() is PinOutcome.Right)
        assertEquals(51_000L, held.keys.hmacCalls.single().atMs)
    }

    @Test
    fun aWrongPinIsCountedAndSaysTheTriesLeft() {
        val guard = f.started(row = 1, total = 7)
        val outcome = guard.enter(pin("111222"), Place.visit(1))
        assertEquals(PinOutcome.Wrong(2), outcome) // min(5 − 2, 10 − 8)
        assertEquals(PinRecord.Guard(charged = false, row = 2, total = 8), f.store.current!!.guard)
        assertEquals(PinRecord.Unseen(1, f.clock.wallMs(), false), f.store.current!!.unseen)
        assertEquals(2, guard.triesLeft)
    }

    @Test
    fun theFifthWrongPinInARowTurnsThePinOff() {
        val guard = f.started(row = 4, total = 4)
        assertEquals(PinOutcome.Limit, guard.enter(pin("111222"), Place.visit(1)))
        val off = f.store.current!!
        assertNull(off.slot)
        assertEquals(PinRecord.Off(PinRecord.OffReason.LIMIT, f.clock.wallMs()), off.off)
        assertEquals(PinRecord.Guard(charged = false, row = 5, total = 5), off.guard)
        assertEquals(listOf(alias), f.keys.deleted)
        assertNull(f.keys.pinKeyOf(alias))
        assertEquals(0, guard.triesLeft)
        assertFalse(guard.canCharge(Place.visit(2)))
    }

    @Test
    fun theTenthWrongPinNotForgivenTurnsThePinOff() {
        val guard = f.started(row = 0, total = 9)
        assertEquals(PinOutcome.Limit, guard.enter(pin("111222"), Place.prompt(1)))
        assertEquals(PinRecord.Guard(charged = false, row = 1, total = 10), f.store.current!!.guard)
        assertNull(f.store.current!!.slot)
    }

    @Test
    fun aLimitWhoseWriteFailsIsOffInMemoryAndCountedAgainAtTheNextStart() {
        val guard = f.started(row = 4, total = 4)
        assertEquals(PinCharge.CHARGED, guard.charge(Place.visit(1)))
        f.store.failNextWrite()
        assertEquals(PinOutcome.Limit, runNow { guard.check(pin("111222"), setOf(VK_ID)) })
        assertNull(guard.record!!.slot)
        assertNull(f.keys.pinKeyOf(alias))
        assertTrue(f.store.current!!.guard.charged)
        assertTrue(f.guard().startUp().limit)
    }

    @Test
    fun keystoreGoneRefundsAndRemovesTheSlot() {
        val guard = f.started(row = 2, total = 3)
        f.keys.scriptHmac(Ks.Gone("invalidated"))
        assertEquals(PinOutcome.KeyGone, guard.enter(RIGHT, Place.visit(1)))
        val r = f.store.current!!
        assertNull(r.slot)
        assertEquals(PinRecord.Off(PinRecord.OffReason.KEY_GONE, f.clock.wallMs()), r.off)
        assertEquals(PinRecord.Guard(charged = false, row = 2, total = 3), r.guard) // refunded: no count
        assertEquals(listOf(alias), f.keys.deleted)
    }

    @Test
    fun aMissingKeyIsGoneWhenTheCanaryAnswers() {
        val guard = f.started()
        f.keys.dropAllKeys() // a reinstall that kept the data but not the keys (lock.md D-21, F15b)
        assertEquals(PinOutcome.KeyGone, guard.enter(RIGHT, Place.visit(1)))
        assertTrue(f.keys.canary) // made again (§14.2)
    }

    @Test
    fun keystoreTemporaryRefundsAndHidesThePinAtThatPlaceOnly() {
        val guard = f.started(row = 1, total = 1)
        val visit = Place.visit(1)
        f.keys.scriptHmac(Ks.Temporary(null))
        assertEquals(PinOutcome.CannotCheck, guard.enter(RIGHT, visit))
        assertEquals(PinRecord.Guard(charged = false, row = 1, total = 1), f.store.current!!.guard)
        assertNotNull(f.store.current!!.slot) // nothing removed (I6)
        assertTrue(guard.cannotCheckOn(visit))
        assertFalse(guard.canCharge(visit))
        assertEquals(PinCharge.NOT_USABLE, guard.charge(visit))
        guard.placeEnded(visit)
        assertTrue(guard.canCharge(Place.visit(2))) // the next visit tries again
        assertTrue(guard.enter(RIGHT, Place.visit(2)) is PinOutcome.Right)
    }

    @Test
    fun aKeystoreThatThrowsOrGivesNoMacIsTemporary() {
        val guard = PinGuard(VAULT, f.store, f.clock, object : PinKey {
            var calls = 0
            override fun create(alias: String): KeyCreate = throw UnsupportedOperationException()
            override fun hmac(alias: String, message: ByteArray): Ks<ByteArray> {
                calls++
                if (calls == 1) throw IllegalStateException("a provider fault")
                return Ks.Ok(ByteArray(20))
            }
        }, f.keys.device, f.aead, FakeRandom(1))
        f.store.set(f.record())
        guard.startUp()
        assertEquals(PinOutcome.CannotCheck, guard.enter(RIGHT, Place.visit(1)))
        assertEquals(PinOutcome.CannotCheck, guard.enter(RIGHT, Place.visit(2)))
        assertEquals(PinRecord.Guard(charged = false, row = 0, total = 0), f.store.current!!.guard)
    }

    @Test
    fun aRightPinIsShownAtOnceAndWrittenByFlush() {
        val guard = f.started(row = 3, total = 3)
        val outcome = guard.enter(RIGHT, Place.visit(1))
        assertTrue(outcome is PinOutcome.Right)
        assertArrayEquals(VK, (outcome as PinOutcome.Right).keys.getValue(VK_ID))
        assertEquals(PinRecord.Guard(charged = false, row = 0, total = 3), guard.record!!.guard)
        assertTrue(f.store.current!!.guard.charged) // not on disk yet: the list comes first
        assertTrue(guard.flush())
        assertEquals(guard.record, f.store.current)
        val writes = f.store.writes.size
        assertTrue(guard.flush()) // nothing waiting
        assertEquals(writes, f.store.writes.size)
    }

    @Test
    fun theNextWriteStoresARightPinThatWasNotFlushed() {
        val guard = f.started(row = 3, total = 3)
        assertTrue(guard.enter(RIGHT, Place.visit(1)) is PinOutcome.Right)
        guard.masterPasswordCorrect()
        assertEquals(PinRecord.Guard(charged = false, row = 0, total = 0), f.store.current!!.guard)
    }

    @Test
    fun aRightPinTrimsTheWrapsByTheRuleOf54() {
        // F8 step 5 left two wraps, for the key of now and the key being written
        val two = ShortcutSlot(SLOT, 1L, listOf(f.wrap(RIGHT, VK_ID, VK), f.wrap(RIGHT, vk2Id, vk2, nonce = FakeRandom.sequence(0x30, 12))))
        f.store.set(f.record(slot = two))
        val guard = f.guard().also { it.startUp() }

        val kept = guard.enter(RIGHT, Place.visit(1), keptVkIds = setOf(VK_ID)) as PinOutcome.Right
        assertEquals(setOf(VK_ID, vk2Id), kept.keys.keys) // both opened
        assertEquals(listOf(VK_ID), guard.record!!.slot!!.wraps.map { it.vkId }) // the change never reached the disk
        guard.flush()

        f.store.set(f.record(slot = two))
        val unknown = f.guard().also { it.startUp() }
        assertTrue(unknown.enter(RIGHT, Place.visit(2), keptVkIds = null) is PinOutcome.Right)
        assertEquals(2, unknown.record!!.slot!!.wraps.size) // not known: nothing dropped
    }

    @Test
    fun aWrapWhoseTagFailsUnderTheRightPinIsDropped() {
        val bad = KeyWrap(vk2Id, FakeRandom.sequence(0x30, 12), ByteArray(48) { 7 })
        val slot = ShortcutSlot(SLOT, 1L, listOf(f.wrap(RIGHT, VK_ID, VK), bad))
        f.store.set(f.record(slot = slot))
        val guard = f.guard().also { it.startUp() }
        val right = guard.enter(RIGHT, Place.visit(1), keptVkIds = setOf(VK_ID, vk2Id)) as PinOutcome.Right
        assertEquals(setOf(VK_ID), right.keys.keys)
        assertEquals(listOf(VK_ID), guard.record!!.slot!!.wraps.map { it.vkId })
    }

    @Test
    fun aSlotLeftWithoutAWrapIsRemoved() {
        val guard = f.started()
        assertTrue(guard.enter(RIGHT, Place.visit(1), keptVkIds = setOf(vk2Id)) is PinOutcome.Right)
        assertNull(guard.record!!.slot)
        assertEquals(PinRecord.OffReason.MASTER_CHANGED, guard.record!!.off!!.reason)
        assertEquals(listOf(alias), f.keys.deleted)
    }

    @Test
    fun aRightPinAfterTheVisitEndedOpensNothingAndIsWrittenAtOnce() = runBlocking {
        val clock = FakeClock(elapsedMs = 50_000L)
        val held = PinFixture(clock)
        val guard = held.started(row = 2, total = 2)
        clock.autoAdvance = false
        val visit = Place.visit(1)
        assertEquals(PinCharge.CHARGED, guard.charge(visit))
        val check = async(start = CoroutineStart.UNDISPATCHED) { guard.check(RIGHT, setOf(VK_ID)) }
        visit.end()
        clock.advance(1_000)
        yield()
        assertEquals(PinOutcome.TooLate, check.await())
        assertEquals(PinRecord.Guard(charged = false, row = 0, total = 2), held.store.current!!.guard)
    }

    @Test
    fun aWrongPinAfterTheVisitEndedIsCountedAllTheSame() = runBlocking {
        val clock = FakeClock(elapsedMs = 50_000L)
        val held = PinFixture(clock)
        val guard = held.started()
        clock.autoAdvance = false
        val visit = Place.visit(1)
        assertEquals(PinCharge.CHARGED, guard.charge(visit))
        val check = async(start = CoroutineStart.UNDISPATCHED) { guard.check(pin("111222"), setOf(VK_ID)) }
        visit.end()
        clock.advance(1_000)
        yield()
        assertEquals(PinOutcome.Wrong(4), check.await())
        assertEquals(PinRecord.Guard(charged = false, row = 1, total = 1), held.store.current!!.guard)
    }

    @Test
    fun aChargeGivenBackDuringTheWaitIsNotChecked() = runBlocking {
        val clock = FakeClock(elapsedMs = 50_000L)
        val held = PinFixture(clock)
        val guard = held.started()
        clock.autoAdvance = false
        val visit = Place.visit(1)
        assertEquals(PinCharge.CHARGED, guard.charge(visit))
        val check = async(start = CoroutineStart.UNDISPATCHED) { guard.check(RIGHT, setOf(VK_ID)) }
        guard.placeEnded(visit) // a caller that did not hold the lock mutex
        clock.advance(1_000)
        yield()
        assertEquals(PinOutcome.NotCharged, check.await())
        assertEquals(0, held.keys.hmacCalls.size) // I4: the charge on disk is gone, so no HMAC
    }

    @Test
    fun theKekIsHandedOverOnlyAfterARightPin() {
        val guard = f.started()
        val keks = ArrayList<ByteArray>()
        assertEquals(PinCharge.CHARGED, guard.charge(Place.prompt(1)))
        assertTrue(runNow { guard.check(pin("111222"), setOf(VK_ID)) { keks += it } } is PinOutcome.Wrong)
        assertTrue(keks.isEmpty())
        assertEquals(PinCharge.CHARGED, guard.charge(Place.prompt(1)))
        assertTrue(runNow { guard.check(RIGHT, setOf(VK_ID)) { keks += it } } is PinOutcome.Right)
        assertEquals("344f054090df8d29988b680e0a4eaaa2256f5195f6f0d9579bb5bd21972f0d74", Bytes.hex(keks.single()))
    }

    @Test
    fun notChargedChecksNothing() {
        val guard = f.started()
        assertEquals(PinOutcome.NotCharged, runNow { guard.check(RIGHT, setOf(VK_ID)) })
        assertEquals(0, f.keys.hmacCalls.size)
        try {
            runNow { guard.check(pin("12345"), setOf(VK_ID)) }
            throw AssertionError("a five-digit PIN was checked")
        } catch (expected: IllegalArgumentException) {
        }
    }

    // ---------------------------------------------------------------------------------------------
    // What else changes the record

    @Test
    fun aMasterPasswordGivesZeroCountsAndDropsTheCharge() {
        val guard = f.started(row = 3, total = 8, unseen = PinRecord.Unseen(4, 9L, true))
        assertEquals(PinCharge.CHARGED, guard.charge(Place.visit(1)))
        guard.masterPasswordCorrect()
        assertNull(guard.chargedAt)
        val r = f.store.current!!
        assertEquals(PinRecord.Guard(charged = false, row = 0, total = 0), r.guard)
        assertEquals(PinRecord.Unseen(4, 9L, true), r.unseen)
    }

    @Test
    fun aNewPinKeepsTheCountsAsSection64Says() {
        val newSlot = newSlot()
        val withMaster = f.started(row = 2, total = 7, unseen = PinRecord.Unseen(3, 4L, false))
        assertTrue(withMaster.setPin(newSlot, masterPasswordTyped = true))
        assertEquals(PinRecord(VAULT, newSlot, PinRecord.Guard(false, 0, 0), null, PinRecord.Unseen(3, 4L, false)), f.store.current)

        val withPin = f.started(row = 0, total = 7)
        assertTrue(withPin.setPin(newSlot, masterPasswordTyped = false))
        assertEquals(PinRecord.Guard(false, 0, 7), f.store.current!!.guard)
        assertTrue(alias !in f.keys.deleted) // the old key is the sweep's
    }

    @Test
    fun aNewPinThatCannotBeWrittenKeepsTheOldRecord() {
        val guard = f.started(row = 1, total = 1)
        val old = guard.record
        f.store.failNextWrite()
        assertFalse(guard.setPin(newSlot(), masterPasswordTyped = true))
        assertEquals(old, guard.record)
        assertEquals(old, f.store.current)
    }

    @Test
    fun aNewPinReplacesADamagedOrUnreadableRecordButNeverANewerOne() {
        for (result in listOf<RecordRead<PinRecord>>(RecordRead.Missing, RecordRead.Unreadable, RecordRead.Damaged(ParseError(0, "magic")))) {
            f.store.set(f.record())
            f.store.readAs = result
            val guard = f.guard().also { it.startUp() }
            f.store.readAs = null
            assertTrue("$result", guard.setPin(newSlot(), masterPasswordTyped = true))
            assertTrue(guard.lastRead is RecordRead.Ok)
            assertTrue(guard.canCharge(Place.visit(1)))
        }
        f.store.set(f.record())
        f.store.readAs = RecordRead.Newer
        val newer = f.guard().also { it.startUp() }
        val writes = f.store.writes.size
        assertFalse(newer.setPin(newSlot(), masterPasswordTyped = true))
        assertEquals(writes, f.store.writes.size)
    }

    @Test
    fun aNewPinEndsAFailedStartUp() {
        f.store.set(f.record(charged = true))
        f.store.failNextWrite()
        val guard = f.guard()
        assertTrue(guard.startUp().storageFull)
        assertTrue(guard.setPin(newSlot(), masterPasswordTyped = true))
        assertFalse(guard.storageFull)
        assertTrue(guard.canCharge(Place.visit(1)))
    }

    @Test
    fun wrapsThatNoKeptGenerationCarriesAreDropped() {
        val two = ShortcutSlot(SLOT, 1L, listOf(f.wrap(RIGHT, VK_ID, VK), f.wrap(RIGHT, vk2Id, vk2, nonce = FakeRandom.sequence(0x44, 12))))
        f.store.set(f.record(slot = two))
        val guard = f.guard().also { it.startUp() }
        assertTrue(guard.keepWrapsFor(setOf(VK_ID, vk2Id)))
        assertEquals(2, guard.record!!.slot!!.wraps.size)
        assertTrue(guard.keepWrapsFor(setOf(vk2Id)))
        assertEquals(listOf(vk2Id), f.store.current!!.slot!!.wraps.map { it.vkId })
        assertTrue(f.keys.deleted.isEmpty())
        assertTrue(guard.keepWrapsFor(setOf("00000000-0000-4000-8000-0000000000aa")))
        assertNull(f.store.current!!.slot)
        assertEquals(PinRecord.OffReason.MASTER_CHANGED, f.store.current!!.off!!.reason)
        assertEquals(listOf(alias), f.keys.deleted)
    }

    @Test
    fun aMasterPasswordChangeNeverLeavesThreeWraps() {
        val stale = "00000000-0000-4000-8000-000000000005"
        val two = ShortcutSlot(SLOT, 1L, listOf(f.wrap(RIGHT, VK_ID, VK), f.wrap(RIGHT, stale, vk2, nonce = FakeRandom.sequence(0x44, 12))))
        f.store.set(f.record(slot = two))
        val guard = f.guard().also { it.startUp() }
        val add = f.wrap(RIGHT, vk2Id, vk2, nonce = FakeRandom.sequence(0x55, 12))
        assertTrue(guard.changeWraps(VK_ID, add))
        assertEquals(listOf(VK_ID, vk2Id), f.store.current!!.slot!!.wraps.map { it.vkId })
        // the same change started again after a failed step 6
        assertTrue(guard.changeWraps(VK_ID, f.wrap(RIGHT, vk2Id, vk2, nonce = FakeRandom.sequence(0x77, 12))))
        assertEquals(listOf(VK_ID, vk2Id), f.store.current!!.slot!!.wraps.map { it.vkId })
        // and the PIN opens both copies
        val right = guard.enter(RIGHT, Place.prompt(1), keptVkIds = setOf(VK_ID, vk2Id)) as PinOutcome.Right
        assertArrayEquals(vk2, right.keys.getValue(vk2Id))
    }

    @Test
    fun removingASlotRefundsAndDeletesItsKey() {
        val guard = f.started(row = 1, total = 1)
        assertEquals(PinCharge.CHARGED, guard.charge(Place.visit(1)))
        assertTrue(guard.removeSlot(PinRecord.OffReason.REPLACED))
        val r = f.store.current!!
        assertNull(r.slot)
        assertEquals(PinRecord.Off(PinRecord.OffReason.REPLACED, f.clock.wallMs()), r.off)
        assertEquals(PinRecord.Guard(charged = false, row = 1, total = 1), r.guard)
        assertEquals(listOf(alias), f.keys.deleted)
        assertNull(guard.chargedAt)
    }

    @Test
    fun theNoticeTakesOffWhatItShowed() {
        val guard = f.started(unseen = PinRecord.Unseen(3, 77L, true))
        assertTrue(guard.unseenShown(2, interruptedShown = true))
        assertEquals(PinRecord.Unseen(1, 77L, false), f.store.current!!.unseen)
        assertTrue(guard.unseenShown(0, interruptedShown = false)) // never shown: nothing changes
        assertTrue(guard.unseenShown(5, interruptedShown = false))
        assertEquals(PinRecord.Unseen(0, null, false), f.store.current!!.unseen)
    }

    @Test
    fun placesAreTheirOwnObjects() {
        val visit = Place.visit(4)
        assertTrue(visit.isVisit)
        assertFalse(Place.prompt(4).isVisit)
        assertFalse(visit.hasEnded)
        visit.end()
        visit.end()
        assertTrue(visit.hasEnded)
        assertEquals("visit 4 (ended)", visit.toString())
        assertEquals("prompt 5", Place.prompt(5).toString())
    }

    private fun newSlot(): ShortcutSlot {
        val id = "00000000-0000-4000-8000-000000000007"
        f.keys.pin.create(PinWrap.alias(id))
        return ShortcutSlot(id, f.clock.wallMs(), listOf(f.wrap(pin("368014"), VK_ID, VK, id)))
    }
}
