package io.github.dilshodbekmx.passwordvault.core.lock.flow.vaults

import io.github.dilshodbekmx.passwordvault.core.lock.flow.CreateOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockSummary
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterPasswordOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PracticeSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * A scripted run of lock.md **F18**, a phone that cannot keep keys in secure hardware (build plan
 * 3.14), reached from a restore (F13 step 6 is F1 step 5) and from "Welcome back" (F14): no PIN and no
 * fingerprint is offered, Settings say why, every unlock is the master password, practice is never
 * asked, and the text of step 2 is shown **the first time only**.
 */
class NoSecureHardwareFlowTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val f = LockFixture()

    @Test
    fun f18_afterARestore_masterPasswordOnly_andItsTextTheFirstTimeOnly() {
        f.keys.softwareOnly = true
        f.startInFront()
        assertTrue(f.restoreBackup() is CreateOutcome.Created)

        // Step 1: the check of F1 step 5 fails, so no PIN and no fingerprint is offered (F1 step 9).
        assertEquals(emptyList<Notice>(), f.notices)
        assertFalse(f.keys.canary)
        assertEquals(LockSummary.Pin.NoSecureHardware, f.engine.summary.value.pin)
        assertEquals(LockSummary.Fingerprint.NoSecureHardware, f.engine.summary.value.fingerprint)

        // Step 2: its text, until the owner of the engine says that it has been shown.
        f.lock()
        assertEquals(MpReason.NoSecureHardware, f.field.reason)
        f.leaveAndReturn()
        assertEquals(MpReason.NoSecureHardware, f.field.reason) // not shown yet: still there
        await { f.engine.noSecureHardwareShown() }
        assertNull(f.field.reason)
        assertTrue(f.unlockWith(Secrets.BACKUP) is MasterPasswordOutcome.Right)
        assertEquals(emptyList<Notice>(), f.notices)
        f.lock()
        assertNull(f.field.reason)

        // A later process, told that the text was shown: never again. Step 3: every unlock is the
        // master password, and practice is not asked, a month later either.
        f.restart()
        await { f.engine.start(noSecureHardwareTold = true) }
        await { f.engine.appStarted() }
        assertNull(f.field.reason)
        assertTrue(f.unlockWith(Secrets.BACKUP) is MasterPasswordOutcome.Right)
        assertEquals(emptyList<Notice>(), f.notices)
        assertEquals(LockSummary.Pin.NoSecureHardware, f.engine.summary.value.pin)
        f.lock()
        assertNull(f.field.reason)
        f.clock.advance(30 * PracticeSchedule.DAY_MS)
        f.leaveAndReturn()
        assertNull(f.field.reason)
        assertNull(f.field.practice)
    }

    @Test
    fun f18_aProcessThatWasNeverToldShowsTheTextOnceItHasFoundItOut() {
        f.keys.softwareOnly = true
        f.startInFront()
        assertTrue(f.restoreBackup() is CreateOutcome.Created) // the process ends before the text is shown
        f.restart()
        f.startInFront()
        assertNull(f.field.reason) // this process has not tried to make a key yet

        assertTrue(f.unlockWith(Secrets.BACKUP) is MasterPasswordOutcome.Right) // F1 step 5 runs again for "Choose a PIN"
        assertEquals(emptyList<Notice>(), f.notices)
        f.lock()
        assertEquals(MpReason.NoSecureHardware, f.field.reason)
        await { f.engine.noSecureHardwareShown() }
        assertNull(f.field.reason)
    }

    @Test
    fun f18_welcomeBackOnAPhoneWithoutSecureHardware_offersNoPin() {
        f.keys.softwareOnly = true
        f.seedVault()
        f.startInFront()
        assertEquals(MpReason.WelcomeBack, f.field.reason) // F14 comes first in §13.3's table
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        assertEquals(emptyList<Notice>(), f.notices)
        f.lock()
        assertEquals(MpReason.NoSecureHardware, f.field.reason)
    }

    @Test
    fun f18_aKeystoreThatDoesNotAnswerAtARestore_isNotF18() {
        f.keys.answering = false
        f.startInFront()
        assertTrue(f.restoreBackup() is CreateOutcome.Created)
        assertEquals(emptyList<Notice>(), f.notices) // nothing can be set up right now
        assertEquals(LockSummary.Pin.NotSet, f.engine.summary.value.pin) // and nothing says "this phone can't"
        f.lock()
        assertNull(f.field.reason)
    }
}
