package io.github.dilshodbekmx.passwordvault.ui.security.change

import io.github.dilshodbekmx.passwordvault.ui.security.GuidedChangeMemory
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeKit.Companion.BANK
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeKit.Companion.FACEBOOK
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeKit.Companion.GMAIL
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeKit.Companion.sharing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Build plan 7.2, [JVM]: what the guided change keeps outside the account ([GuidedChangeMemory]). */
class GuidedChangeMemoryTest {

    @Test
    fun anEndIsMarkedUntilItIsForgotten() = ChangeKit.run {
        open(sharing())
        assertNull(memory.doneAt(GMAIL))
        memory.markDone(GMAIL, 42L, notInBackup = false)
        assertEquals(42L, memory.doneAt(GMAIL))
        assertFalse(memory.backupLine(GMAIL))
        assertNull("one account's end only", memory.doneAt(FACEBOOK))

        // A lock keeps it: the route comes back with the unlock.
        lock()
        unlock()
        assertEquals(42L, memory.doneAt(GMAIL))

        memory.forget(GMAIL)
        assertNull(memory.doneAt(GMAIL))
    }

    @Test
    fun theBackupLineIsShownOncePerSession() = ChangeKit.run {
        open(sharing())
        assertTrue(memory.markDone(GMAIL, 1L, notInBackup = true))
        assertTrue(memory.backupLine(GMAIL))
        assertFalse("not again in the same session", memory.markDone(FACEBOOK, 2L, notInBackup = true))
        assertFalse(memory.backupLine(FACEBOOK))
        assertTrue("the first end keeps its line", memory.backupLine(GMAIL))

        lock()
        unlock()
        assertFalse("a change that a backup holds has none", memory.markDone(BANK, 3L, notInBackup = false))
        assertTrue("the session's one line is still to be shown", memory.markDone(FACEBOOK, 4L, notInBackup = true))
    }

    @Test
    fun aNewProcessRemembersNothing() = ChangeKit.run {
        open(sharing())
        memory.markDone(GMAIL, 1L, notInBackup = true)
        newProcess()
        assertNull(memory.doneAt(GMAIL))
        assertTrue("a new session", memory.markDone(FACEBOOK, 2L, notInBackup = true))
    }
}
