package io.github.dilshodbekmx.passwordvault.core.lock.guard

import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinFixture.Companion.RIGHT
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinFixture.Companion.SLOT
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinFixture.Companion.VK
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinFixture.Companion.VK_ID
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinFixture.Companion.pin
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.ShortcutSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * lock.md test T7, the same wrong PIN twice (§6.6): not checked, not counted, refunded, also an hour
 * later in the same process; forgotten after a restart, after a right PIN and after a master
 * password (and after a PIN change, which §6.6 lists too).
 */
class SamePinTest {
    private val f = PinFixture()
    private val wrongA = pin("111222")
    private val wrongB = pin("905127")

    private fun counts(guard: PinGuard) = guard.record!!.guard

    @Test
    fun t7_theSameWrongPinIsNotCheckedNotCountedAndRefunded() {
        val guard = f.started()
        val visit = Place.visit(1)
        assertEquals(PinOutcome.Wrong(4), guard.enter(wrongA, visit))
        val unseen = guard.record!!.unseen
        val writes = f.store.writes.size

        assertEquals(PinCharge.CHARGED, guard.charge(visit))
        assertEquals(PinOutcome.SameAsBefore, runNow { guard.check(wrongA, setOf(VK_ID)) })
        assertEquals(1, f.keys.hmacCalls.size) // not checked: the Keystore was not asked
        assertEquals(PinRecord.Guard(charged = false, row = 1, total = 1), counts(guard)) // not counted
        assertEquals(unseen, guard.record!!.unseen)
        // refunded: the charge, then its refund, and nothing else
        assertEquals(writes + 2, f.store.writes.size)
        assertTrue(f.store.writes[writes].record.guard.charged)
        assertEquals(PinRecord.Guard(charged = false, row = 1, total = 1), f.store.current!!.guard)
        assertEquals(1, f.clock.waits.size) // no wait for the age of the charge either: it is refused before

        // a different wrong PIN is checked and counted
        assertEquals(PinOutcome.Wrong(3), guard.enter(wrongB, visit))
        assertEquals(2, f.keys.hmacCalls.size)
    }

    @Test
    fun t7_alsoAnHourLaterAndInAPrompt() {
        val guard = f.started()
        val visit = Place.visit(1)
        assertEquals(PinOutcome.Wrong(4), guard.enter(wrongA, visit))
        guard.placeEnded(visit)
        f.clock.advance(3_600_000)
        val prompt = Place.prompt(2)
        assertEquals(PinCharge.CHARGED, guard.charge(prompt))
        assertEquals(PinOutcome.SameAsBefore, runNow { guard.check(wrongA, setOf(VK_ID)) })
        assertEquals(1, f.keys.hmacCalls.size)
        assertEquals(PinRecord.Guard(charged = false, row = 1, total = 1), counts(guard))
    }

    @Test
    fun t7_aWrongPinTypedInAPromptIsRememberedOnTheLockScreen() {
        val guard = f.started()
        assertEquals(PinOutcome.Wrong(4), guard.enter(wrongA, Place.prompt(1)))
        assertEquals(PinCharge.CHARGED, guard.charge(Place.visit(2)))
        assertEquals(PinOutcome.SameAsBefore, runNow { guard.check(wrongA, setOf(VK_ID)) })
    }

    @Test
    fun t7_forgottenAfterARestart() {
        val guard = f.started()
        assertEquals(PinOutcome.Wrong(4), guard.enter(wrongA, Place.visit(1)))
        val next = f.guard().also { it.startUp() } // a new process, with a new nonce
        assertEquals(PinOutcome.Wrong(3), next.enter(wrongA, Place.visit(2)))
        assertEquals(2, f.keys.hmacCalls.size)
    }

    @Test
    fun t7_forgottenAfterARightPin() {
        val guard = f.started()
        val visit = Place.visit(1)
        assertEquals(PinOutcome.Wrong(4), guard.enter(wrongA, visit))
        assertTrue(guard.enter(RIGHT, visit) is PinOutcome.Right)
        guard.flush()
        guard.placeEnded(visit)
        assertEquals(PinOutcome.Wrong(4), guard.enter(wrongA, Place.visit(2)))
        assertEquals(3, f.keys.hmacCalls.size)
    }

    @Test
    fun t7_forgottenAfterAMasterPassword() {
        val guard = f.started()
        assertEquals(PinOutcome.Wrong(4), guard.enter(wrongA, Place.visit(1)))
        guard.masterPasswordCorrect()
        assertEquals(PinOutcome.Wrong(4), guard.enter(wrongA, Place.visit(2)))
        assertEquals(2, f.keys.hmacCalls.size)
    }

    @Test
    fun t7_forgottenAfterAPinChange() {
        val guard = f.started()
        assertEquals(PinOutcome.Wrong(4), guard.enter(wrongA, Place.prompt(1)))
        assertTrue(guard.enter(RIGHT, Place.prompt(2)) is PinOutcome.Right)
        guard.flush()
        // F6 "Change PIN": the current PIN was typed, so total is kept; the new PIN is 368014
        val newSlotId = "00000000-0000-4000-8000-000000000009"
        f.keys.pin.create(PinWrap.alias(newSlotId))
        val newSlot = ShortcutSlot(newSlotId, f.clock.wallMs(), listOf(f.wrap(pin("368014"), VK_ID, VK, newSlotId)))
        assertTrue(guard.setPin(newSlot, masterPasswordTyped = false))
        assertEquals(PinRecord.Guard(charged = false, row = 0, total = 1), counts(guard))
        assertEquals(PinOutcome.Wrong(4), guard.enter(wrongA, Place.visit(3)))
        assertFalse(f.keys.hmacCalls.last().alias == PinWrap.alias(SLOT))
    }

    @Test
    fun t7_theGuardKeepsAHashNotThePin() {
        val guard = f.started()
        assertEquals(PinOutcome.Wrong(4), guard.enter(wrongA, Place.visit(1)))
        // the remembered guess is SHA-256(nonce16 ‖ PIN): no field of the guard holds the digits
        for (field in PinGuard::class.java.declaredFields) {
            field.isAccessible = true
            val value = field.get(guard)
            if (value is ByteArray) assertFalse(field.name, value.contentEquals(wrongA))
            if (value is Collection<*>) {
                for (item in value) {
                    assertFalse(field.name, item is ByteArray && item.contentEquals(wrongA))
                    assertFalse(field.name, item is String && item.contains("111222"))
                }
            }
        }
    }
}
