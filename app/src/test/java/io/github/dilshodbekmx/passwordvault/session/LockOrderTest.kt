package io.github.dilshodbekmx.passwordvault.session

import io.github.dilshodbekmx.passwordvault.core.model.Draft
import io.github.dilshodbekmx.passwordvault.core.model.DraftEntry
import io.github.dilshodbekmx.passwordvault.lock.LockReason
import io.github.dilshodbekmx.passwordvault.settings.LockDelay
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build plan 3.2, [JVM]: for each trigger of a lock (the lock delay, "Immediately", screen off, the
 * no-touch timer, the 10-minute cap of an external screen, "Lock now"), a dirty form's draft is
 * written before `VaultPort.lock()`: the order of architecture.md §5.5, decide, then
 * `VaultRepository.flushDraft()`, then `LockManager.lock(reason)`, whose `VaultPort.lock()` drops the
 * session. The real `AutoLock` and gate over the in-memory vault, which logs every call
 * ([SessionRig]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LockOrderTest {
    private val minute = 60_000L

    /** The open form's unsaved state: a new account with a generated password (ux.md §12 check 4). */
    private val draft = Draft(entryId = null, savedAt = 1_791_244_860_000L, entry = DraftEntry(title = "Gmail", password = "kesh-olma-daraxt-73"))

    private suspend fun TestScope.dirtyForm(configure: SessionRig.() -> Unit = {}): SessionRig {
        val s = SessionRig(this).start()
        s.setUp()
        s.configure()
        s.vault.holdDraft(draft) // the edit screen hands its state over as it changes
        return s
    }

    /** The draft is in the vault, written before the lock manager was asked to lock and the port dropped the session. */
    private fun SessionRig.assertDraftWrittenBeforeTheLock(reason: LockReason) {
        assertTrue(locked)
        assertEquals(listOf(reason), reasons)
        assertEquals(draft, vault.vault.draft)
        val log = vault.log
        val written = log.indexOf("draft written")
        val asked = log.indexOf("lock($reason)")
        val dropped = log.indexOf("VaultPort.lock")
        assertTrue("$log", written >= 0 && written < asked && asked < dropped)
        // The lock's own flush is the last one before the lock: nothing but its write comes between.
        val flush = log.subList(0, asked).lastIndexOf("flushDraft")
        assertTrue("$log", flush >= 0 && log.subList(flush + 1, asked).all { it == "draft written" })
        assertFalse(vault.isOpen)
    }

    @Test
    fun theLockDelay() = runTest {
        val s = dirtyForm()
        s.awayFor(30_000)
        s.assertDraftWrittenBeforeTheLock(LockReason.LEFT)
    }

    @Test
    fun immediately() = runTest {
        val s = dirtyForm { assertTrue(settings.update { it.lockDelay = LockDelay.IMMEDIATELY }) }
        s.process.away()
        s.settle()
        s.assertDraftWrittenBeforeTheLock(LockReason.LEFT)
    }

    @Test
    fun theScreenOffSignal() = runTest {
        val s = dirtyForm()
        s.autoLock.screenTurnedOff()
        s.settle()
        s.assertDraftWrittenBeforeTheLock(LockReason.SCREEN_OFF)
    }

    @Test
    fun theScreenOffAtOnStop() = runTest {
        val s = dirtyForm()
        s.screenOn = false
        s.process.away()
        s.settle()
        s.assertDraftWrittenBeforeTheLock(LockReason.SCREEN_OFF)
    }

    @Test
    fun theNoTouchTimer() = runTest {
        // No ON_STOP comes before this lock: only the lock's own flush can write the draft.
        val s = dirtyForm()
        s.idle(5 * minute)
        s.assertDraftWrittenBeforeTheLock(LockReason.NO_TOUCH)
        assertEquals(listOf("flushDraft", "draft written", "lock(NO_TOUCH)", "VaultPort.lock"), s.vault.log.takeLast(4))
    }

    @Test
    fun theExternalScreensCap() = runTest {
        val s = dirtyForm()
        s.autoLock.beginExternal()
        s.awayFor(11 * minute)
        s.assertDraftWrittenBeforeTheLock(LockReason.EXTERNAL_TIMEOUT)
    }

    @Test
    fun lockNow_alsoClearsOurClipFirst() = runTest {
        val s = dirtyForm()
        s.autoLock.lockNow()
        assertEquals("the clip is cleared at once, on the main thread", 1, s.clipboard.clears)
        s.settle()
        s.assertDraftWrittenBeforeTheLock(LockReason.LOCK_NOW)
        assertEquals(listOf("flushDraft", "draft written", "lock(LOCK_NOW)", "VaultPort.lock"), s.vault.log.takeLast(4))
    }

    @Test
    fun leavingWithinTheDelayWritesTheDraftWhileTheVaultIsStillOpen() = runTest {
        // ux.md §5.14: the draft is written when the user leaves, before any lock.
        val s = dirtyForm()
        s.process.away()
        s.settle()
        assertTrue(s.unlocked)
        assertEquals(draft, s.vault.vault.draft)
        assertEquals(listOf("flushDraft", "draft written"), s.vault.log.takeLast(2))
    }

    @Test
    fun aDraftThatIsAlreadyStoredIsNotWrittenAgain() = runTest {
        val s = dirtyForm()
        s.process.away()
        s.settle()
        s.clock.advance(30_000)
        s.process.toFront()
        s.settle()
        assertEquals(1, s.vault.log.count { it == "draft written" })
        assertEquals(listOf("flushDraft", "lock(LEFT)", "VaultPort.lock"), s.vault.log.takeLast(3))
    }

    @Test
    fun aSlowSaveIsWaitedFor_theLockComesAfterIt() = runTest {
        val s = dirtyForm()
        val save = CompletableDeferred<Unit>().also { s.vault.flushHold = it }
        s.autoLock.screenTurnedOff()
        s.settle()
        assertTrue("the vault stays open while the draft's save runs", s.vault.isOpen)
        assertFalse("VaultPort.lock" in s.vault.log)
        // Nothing else starts a second lock meanwhile.
        s.screenOn = false
        s.process.away()
        s.autoLock.lockNow()
        s.settle()
        save.complete(Unit)
        s.settle()
        s.assertDraftWrittenBeforeTheLock(LockReason.SCREEN_OFF)
    }

    @Test
    fun aDraftThatCannotBeWrittenDoesNotStopTheLock() = runTest {
        val s = dirtyForm()
        s.vault.flushFails = IOException("storage full")
        s.idle(5 * minute)
        assertTrue(s.locked)
        assertEquals(listOf(LockReason.NO_TOUCH), s.reasons)
        assertNull(s.vault.vault.draft)
        assertEquals(listOf("flushDraft", "lock(NO_TOUCH)", "VaultPort.lock"), s.vault.log.takeLast(3))
    }

    @Test
    fun withNoDirtyFormTheLockWritesNothing() = runTest {
        val s = SessionRig(this).start()
        s.setUp()
        s.autoLock.screenTurnedOff()
        s.settle()
        assertTrue(s.locked)
        assertEquals(listOf("flushDraft", "lock(SCREEN_OFF)", "VaultPort.lock"), s.vault.log.takeLast(3))
        assertFalse(s.vault.log.any { it == "draft written" || it == "save" })
    }
}
