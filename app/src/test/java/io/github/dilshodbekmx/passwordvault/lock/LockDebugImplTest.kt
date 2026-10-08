package io.github.dilshodbekmx.passwordvault.lock

import io.github.dilshodbekmx.passwordvault.core.lock.flow.KeyCreate
import io.github.dilshodbekmx.passwordvault.core.lock.flow.NoticeAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PadStatus
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinKey
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.core.lock.record.KeyWrap
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.ShortcutSlot
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.keystore.KeystoreAccess
import io.github.dilshodbekmx.passwordvault.keystore.KeystoreFaults
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordFiles
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build plan 4.1, [JVM]: [LockDebugImpl], the test hooks of lock.md §15.3 behind the debug commands
 * `status`, `hold-after-charge`, `hold-before-hmac`, `release`, `fail-next-write`, `ks-fault` and
 * `drop-keys`: each hook on the port it wraps, each command's answer, and the status of the lock as
 * `LockModule` builds it with the hooks. On the JVM the Android Keystore is not there, so
 * `KeystoreAccess` lists no alias and deletes nothing; its faults are plain code and work as on a
 * phone. The checks themselves (E1 to E17) run on emulators (build plan 4.5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LockDebugImplTest {
    private val faults = KeystoreFaults(testBuild = true)
    private val access = KeystoreAccess(allowSoftwareKeys = true, faults = faults)
    private val hooks = LockDebugImpl(access, Dispatchers.Unconfined)
    private val clock = FakeClock()
    private val files = FakeRecordFiles(clock)

    private fun pinRecord(charged: Boolean): ByteArray {
        val slot = ShortcutSlot(SLOT, 1L, listOf(KeyWrap(VK, ByteArray(12), ByteArray(48))))
        return RecordCodec.Pin.encode(PinRecord(VAULT, slot, PinRecord.Guard(charged, 0, 0), off = null, unseen = PinRecord.Unseen(0, null, false)))
    }

    private fun stateRecord(): ByteArray = RecordCodec.State.encode(
        StateRecord(VAULT, StateRecord.Practice(1L, 0, null), StateRecord.MpGuard(0, null, 0), null, null, null, StateRecord.MaxGeneration(1, 1L)),
    )

    /** Waits until [condition] holds, for at most two seconds. */
    private fun await(what: String, condition: () -> Boolean) {
        val end = System.nanoTime() + 2_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > end) throw AssertionError("did not happen: $what")
            Thread.sleep(2)
        }
    }

    // ---- hold-after-charge, release ------------------------------------------------------------------------

    @Test
    fun holdAfterCharge_parksTheWriteOfAChargeOnceItIsOnDisk_untilRelease() {
        val records = hooks.records(files)
        hooks.holdAfterCharge()
        assertEquals(LockDebugImpl.Point.AFTER_CHARGE, hooks.armedHold)

        records.replace(DIR, "state.rec", stateRecord()) // not a PIN record
        records.replace(DIR, "pin.rec", pinRecord(charged = false)) // a PIN record without a charge
        assertEquals("neither of them is the charge", LockDebugImpl.Point.AFTER_CHARGE, hooks.armedHold)

        val done = AtomicInteger()
        val writer = thread {
            records.replace(DIR, "pin.rec", pinRecord(charged = true)) // lock.md §6.3 C: the first digit
            done.incrementAndGet()
        }
        await("the hold is reached") { hooks.reachedHold == LockDebugImpl.Point.AFTER_CHARGE }
        assertNull("it is reached, no longer armed", hooks.armedHold)
        assertTrue("the charge is on disk while the check stands still", files.bytes(DIR, "pin.rec").contentEquals(pinRecord(charged = true)))
        Thread.sleep(30)
        assertEquals("the write has not returned to the lock", 0, done.get())

        hooks.release()
        writer.join(2_000)
        assertEquals(1, done.get())
        assertNull(hooks.reachedHold)

        records.replace(DIR, "pin.rec", pinRecord(charged = true)) // a hold holds once
        assertNull(hooks.reachedHold)
    }

    @Test
    fun aReachedHoldIsInTheStatus_whichDoesNotWaitForTheEngine() {
        val records = hooks.records(files)
        hooks.holdAfterCharge()
        val writer = thread { records.replace(DIR, "pin.rec", pinRecord(charged = true)) }
        await("the hold is reached") { hooks.reachedHold != null }

        val status = runBlocking { hooks.command("status", emptyList()) }

        assertEquals("engine: held at after-charge, not asked", status.lines().first())
        assertTrue(status, "hold: after-charge (reached)" in status.lines())
        assertEquals("release: released after-charge (reached)", runBlocking { hooks.command("release", emptyList()) })
        writer.join(2_000)
        assertFalse(writer.isAlive)
    }

    @Test
    fun releaseDisarmsAHoldThatWasNotReached() = runTest {
        hooks.holdBeforeHmac()
        assertEquals("release: released before-hmac (armed)", hooks.command("release", emptyList()))
        assertNull(hooks.armedHold)
        assertEquals("release: nothing was held", hooks.command("release", emptyList()))
        hooks.records(files).replace(DIR, "pin.rec", pinRecord(charged = true)) // nothing parks it
    }

    // ---- hold-before-hmac ----------------------------------------------------------------------------------

    @Test
    fun holdBeforeHmac_parksTheCheckBeforeTheKeystoreIsAsked_untilRelease() {
        val asked = AtomicInteger()
        val real = object : PinKey {
            override fun create(alias: String): KeyCreate = KeyCreate.Ok

            override fun hmac(alias: String, message: ByteArray): Ks<ByteArray> {
                asked.incrementAndGet()
                return Ks.Ok(ByteArray(32) { 7 })
            }
        }
        val key = hooks.pinKey(real)
        assertTrue("without a hold it is the real key", (key.hmac("pv_pin_x", ByteArray(56)) as Ks.Ok).value.all { it == 7.toByte() })
        assertEquals(1, asked.get())

        hooks.holdBeforeHmac()
        val answer = AtomicReference<Ks<ByteArray>>()
        val checker = thread { answer.set(key.hmac("pv_pin_x", ByteArray(56))) }
        await("the hold is reached") { hooks.reachedHold == LockDebugImpl.Point.BEFORE_HMAC }
        Thread.sleep(30)
        assertEquals("the Keystore was not asked yet (lock.md §6.3 E.3)", 1, asked.get())

        hooks.release()
        checker.join(2_000)
        assertEquals(2, asked.get())
        assertTrue(answer.get() is Ks.Ok)
        assertEquals(KeyCreate.Ok, key.create("pv_pin_y"))
    }

    // ---- fail-next-write ------------------------------------------------------------------------------------

    @Test
    fun failNextWrite_failsOneWriteOfALockRecord_withNothingWritten() {
        val records = hooks.records(files)
        hooks.failNextWrite()
        assertThrows(IOException::class.java) { records.replace(DIR, "state.rec", stateRecord()) }
        assertNull("not written (lock.md §5.9)", files.bytes(DIR, "state.rec"))

        records.replace(DIR, "state.rec", stateRecord())
        assertNotNull("the write after it goes through", files.bytes(DIR, "state.rec"))
        assertEquals(listOf(DIR), records.dirs(ROOT))
    }

    // ---- ks-fault, drop-keys ------------------------------------------------------------------------------------

    @Test
    fun ksFault_armsARowOfTheKeystoresFaults_andNoneDisarms() = runTest {
        hooks.ksFault("getkey-null")
        assertEquals("getkey-null", faults.pending)
        assertTrue(hooks.armFault("prompt-error-7"))
        assertEquals("prompt-error-7", faults.pending)
        assertFalse("a name that no fault has changes nothing", hooks.armFault("no-such-row"))
        assertEquals("prompt-error-7", faults.pending)
        hooks.ksFault("none")
        assertNull(faults.pending)

        assertEquals("ks-fault: mac-dofinal", hooks.command("ks-fault", listOf("mac-dofinal")))
        val listed = hooks.command("ks-fault", emptyList())
        assertTrue(listed, listed.startsWith("ks-fault: mac-dofinal; rows: "))
        for (row in KeystoreFaults.rows) assertTrue("$row is listed", " $row " in "$listed ")
        assertTrue("prompt-error-<code>" in listed)
        assertEquals("ks-fault: none", hooks.command("ks-fault", listOf("none")))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { hooks.command("ks-fault", listOf("no-such-row")) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { hooks.command("ks-fault", listOf("a", "b")) } }
    }

    @Test
    fun theCommandsAnswerWithTheirNames_andRefuseWhatTheyDoNotTake() = runTest {
        assertEquals(
            listOf("status", "hold-after-charge", "hold-before-hmac", "release", "fail-next-write", "ks-fault", "drop-keys"),
            LockDebugImpl.COMMANDS,
        )
        assertTrue(hooks.command("hold-after-charge", emptyList()).startsWith("hold-after-charge: armed"))
        assertEquals(LockDebugImpl.Point.AFTER_CHARGE, hooks.armedHold)
        assertTrue(hooks.command("hold-before-hmac", emptyList()).startsWith("hold-before-hmac: armed"))
        assertEquals("the hold armed last is the one that is armed", LockDebugImpl.Point.BEFORE_HMAC, hooks.armedHold)
        assertTrue(hooks.command("fail-next-write", emptyList()).startsWith("fail-next-write: armed"))
        // No Android Keystore on the JVM: nothing is listed and nothing deleted.
        assertEquals("drop-keys: 0 of 0 aliases deleted", hooks.command("drop-keys", emptyList()))

        assertThrows(IllegalArgumentException::class.java) { runBlocking { hooks.command("release", listOf("now")) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { hooks.command("lock", emptyList()) } }
    }

    // ---- status, on the lock as LockModule builds it with the hooks -----------------------------------------

    @Test
    fun status_describesTheLockItsHooksAndThePrompt_withNothingSecret() = runTest {
        val rig = LockRig(this)
        val hooks = LockDebugImpl(access, StandardTestDispatcher(testScheduler))
        rig.hooks = hooks
        rig.setUpWithPin()
        rig.answerNotices(NoticeAnswer.LATER)
        rig.lock()
        hooks.failNextWrite()
        hooks.ksFault("getkey-null")
        hooks.holdBeforeHmac()

        val locked = hooks.status()

        val lines = locked.lines()
        assertEquals("state: locked: pad", lines.first())
        assertTrue(locked, lines.any { it.startsWith("policy: row PIN_PAD, pin USABLE") })
        assertTrue(locked, lines.any { it.startsWith("lock " + rig.vaultId) })
        assertTrue(locked, lines.any { it.trim().startsWith("pin.rec: OK, slot set, 1 wrap(s), off none, charged false, row 0, total 0") })
        assertTrue("the shortcut aliases, as the engine lists them", lines.any { it.startsWith("aliases: pv_pin_") })
        assertTrue(locked, "hold: before-hmac (armed)" in lines)
        assertTrue(locked, "fail-next-write: armed" in lines)
        assertTrue(locked, "ks-fault: getkey-null" in lines)
        assertTrue(locked, "keystore: no alias listed" in lines)
        assertTrue(locked, "prompt: none, may open by itself: true" in lines)
        assertEquals("last PIN unlock: none", lines.last())
        assertFalse("no digit, password or key is in it", LockRig.PIN in locked || LockRig.PASSWORD in locked)

        hooks.release()
        faults.disarm()
        rig.press("5") // fail-next-write: the charge of this entry cannot be written
        assertEquals("\"Your phone's storage is full, so the PIN can't be checked now.\" (check E14)", PadStatus.StorageFull, rig.pad.status)
        assertEquals(0, rig.pad.digits)
        assertFalse(rig.pinRecord().guard.charged)

        rig.typePin()
        assertTrue(rig.isUnlocked)
        val open = hooks.status().lines()
        assertTrue(open.first().startsWith("state: unlocked"))
        assertTrue("fail-next-write: no" in open)
        assertEquals("the time of check E1", "last PIN unlock: 1000 ms from the sixth digit", open.last())
    }

    @Test
    fun aKillWithAChargeOnDiskCostsOneTry_andTheNextStartSaysSo() = runTest {
        // What the checks E2 and E3 show with `hold-after-charge` and a kill, here without the hold:
        // the process ends between the first digit and the check.
        val rig = LockRig(this)
        rig.setUpWithPin()
        rig.answerNotices(NoticeAnswer.LATER)
        rig.lock()
        rig.press(LockRig.PIN) // the charge is on disk; the check waits for its second
        assertTrue(rig.pinRecord().guard.charged)

        rig.restart() // am force-stop, kill -9 or a reboot
        rig.toFront()

        val record = rig.pinRecord()
        assertFalse(record.guard.charged)
        assertEquals("one try counted (lock.md §6.3 S)", 1 to 1, record.guard.row to record.guard.total)
        assertTrue(record.unseen.interrupted)
        assertEquals("\"Your last PIN entry was interrupted and counted as a try. 4 tries left.\"", PadStatus.Interrupted(left = 4), rig.pad.status)
        rig.typePin()
        assertTrue(rig.isUnlocked)
    }

    private companion object {
        const val ROOT = "/data/user/0/app/no_backup/lock"
        const val VAULT = "00000000-0000-4000-8000-000000000001"
        const val SLOT = "00000000-0000-4000-8000-0000000000c1"
        const val VK = "00000000-0000-4000-8000-0000000000b1"
        const val DIR = "$ROOT/$VAULT"
    }
}
