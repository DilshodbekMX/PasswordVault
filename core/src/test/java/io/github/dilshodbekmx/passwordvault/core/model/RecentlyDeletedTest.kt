package io.github.dilshodbekmx.passwordvault.core.model

import io.github.dilshodbekmx.passwordvault.core.lock.guard.Credit
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.DAY
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.HOUR
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.MINUTE
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.T0
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.entry
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.id
import io.github.dilshodbekmx.passwordvault.core.model.ModelFixtures.vault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Delete, restore, delete forever, and the purge of D-24 (ux.md §5.23). */
class RecentlyDeletedTest {
    @Test
    fun deleteAndRestore() {
        val e = entry(1)
        val v = vault(e, entry(2))
        val deleted = VaultEdits.delete(e.id)(v, T0 + MINUTE)
        assertEquals(EntryResult.Done(e.id), deleted.value)
        assertEquals(e.copy(deletedAt = T0 + MINUTE, updatedAt = T0 + MINUTE), deleted.vault.entries.first())
        // Deleting again keeps the first time: the 30 days do not start again.
        assertSame(deleted.vault, VaultEdits.delete(e.id)(deleted.vault, T0 + DAY).vault)
        val restored = VaultEdits.restore(e.id)(deleted.vault, T0 + 2 * MINUTE)
        assertEquals(EntryResult.Done(e.id), restored.value)
        assertEquals(e.copy(updatedAt = T0 + 2 * MINUTE), restored.vault.entries.first())
        // Restoring an entry that is not deleted changes nothing.
        assertSame(v, VaultEdits.restore(e.id)(v, T0).vault)
        assertEquals(EntryResult.NotFound, VaultEdits.delete(id(9))(v, T0).value)
        assertEquals(EntryResult.NotFound, VaultEdits.restore(id(9))(v, T0).value)
    }

    @Test
    fun aDeletedEntryKeepsEverythingAndItsPendingChange() {
        val e = entry(1).copy(changePending = true, pendingOldPassword = "old", favourite = true)
        val back = VaultEdits.restore(e.id)(VaultEdits.delete(e.id)(vault(e), T0).vault, T0 + 1).vault.entries.single()
        assertEquals(e.copy(updatedAt = T0 + 1), back)
    }

    @Test
    fun deleteForeverOnlyFromRecentlyDeleted() {
        val live = entry(1)
        val gone = entry(2, deletedAt = T0)
        val v = vault(live, gone).copy(masterPasswordMatches = listOf(live.id, gone.id))
        val refused = VaultEdits.deleteForever(live.id)(v, T0)
        assertSame(v, refused.vault)
        assertEquals(EntryResult.NotFound, refused.value)
        val removed = VaultEdits.deleteForever(gone.id)(v, T0)
        assertEquals(EntryResult.Done(gone.id), removed.value)
        assertEquals(listOf(live), removed.vault.entries)
        assertEquals(listOf(live.id), removed.vault.masterPasswordMatches)
        assertEquals(EntryResult.NotFound, VaultEdits.deleteForever(gone.id)(removed.vault, T0).value)
    }

    @Test
    fun emptyRemovesEveryDeletedEntry() {
        val v = vault(entry(1), entry(2, deletedAt = T0), entry(3), entry(4, deletedAt = T0 - DAY))
        val emptied = VaultEdits.emptyRecentlyDeleted()(v, T0)
        assertEquals(2, emptied.value)
        assertEquals(listOf(id(1), id(3)), emptied.vault.entries.map { it.id })
        assertSame(emptied.vault, VaultEdits.emptyRecentlyDeleted()(emptied.vault, T0).vault)
        assertEquals(0, VaultEdits.emptyRecentlyDeleted()(emptied.vault, T0).value)
    }

    @Test
    fun thePurgeRemovesOnlyWhatWasDueAtTheGivenCutoff() {
        val armedAt = T0
        val days30 = VaultEdits.PURGE_AFTER_MS
        assertEquals(30 * DAY, days30)
        val v = vault(
            entry(1, deletedAt = armedAt - days30 - DAY), // due
            entry(2, deletedAt = armedAt - days30), // due: exactly 30 days
            entry(3, deletedAt = armedAt - days30 + 1), // 1 ms short of 30 days
            entry(4), // not deleted
            entry(5, deletedAt = armedAt + HOUR), // deleted after the arming
            entry(6, deletedAt = armedAt - 400 * DAY), // due
        ).copy(masterPasswordMatches = listOf(id(1), id(4)))
        assertEquals(3, VaultEdits.dueCount(v, armedAt))
        val purged = VaultEdits.purge(armedAt)(v, armedAt + 365 * DAY) // nowMs plays no part
        assertEquals(3, purged.value)
        assertEquals(listOf(id(3), id(4), id(5)), purged.vault.entries.map { it.id })
        assertEquals(listOf(id(4)), purged.vault.masterPasswordMatches)
        // The same purge again finds nothing, whatever the clock says.
        assertSame(purged.vault, VaultEdits.purge(armedAt)(purged.vault, armedAt + 1000 * DAY).vault)
        assertEquals(0, VaultEdits.purge(armedAt)(purged.vault, armedAt).value)
    }

    @Test
    fun dueTimesNeverWrap() {
        val e = entry(1, deletedAt = Long.MIN_VALUE)
        assertTrue(VaultEdits.isDue(e, Long.MIN_VALUE))
        assertFalse(VaultEdits.isDue(entry(2, deletedAt = Long.MIN_VALUE + 1), Long.MIN_VALUE))
        assertTrue(VaultEdits.isDue(entry(3, deletedAt = 0), Long.MAX_VALUE))
        assertFalse(VaultEdits.isDue(entry(4, deletedAt = Long.MAX_VALUE), Long.MAX_VALUE))
        assertFalse(VaultEdits.isDue(entry(5), Long.MAX_VALUE))
    }

    /**
     * architecture.md §8.1 for `model`: "a clock set a year ahead removes nothing until 24 hours are
     * credited, and then only what was due at arming". The arming and the credit are the
     * repository's (build plan 3.1, with `PurgeArm` and lock.md's `Credit`); this runs the same two
     * steps of D-24 over this package's functions.
     */
    @Test
    fun aClockSetAYearAheadRemovesNothingUntil24HoursAreCreditedThenOnlyWhatWasDue() {
        class Arm(val atWallMs: Long, val credit: Credit)

        var v = vault(entry(1, deletedAt = T0 - 2 * DAY), entry(2), entry(3, deletedAt = T0 - HOUR))
        var arm: Arm? = null
        var elapsed = 10 * HOUR

        fun unlock(wallMs: Long) {
            val armed = arm
            if (armed == null) {
                if (VaultEdits.dueCount(v, wallMs) > 0) arm = Arm(wallMs, Credit.start(elapsed))
                return
            }
            val credit = armed.credit.observe(elapsed)
            arm = Arm(armed.atWallMs, credit)
            if (credit.reached(VaultEdits.PURGE_WAIT_MS)) {
                v = VaultEdits.purge(armed.atWallMs)(v, wallMs).vault
                arm = null
            }
        }

        val yearAhead = T0 + 365 * DAY
        unlock(yearAhead) // arms: both deleted entries look 30 days old
        assertEquals(yearAhead, arm?.atWallMs)
        assertEquals(3, v.entries.size)
        // Deleted after the arming: never part of this purge.
        v = VaultEdits.delete(id(2))(v, yearAhead + MINUTE).vault
        elapsed += 23 * HOUR
        unlock(yearAhead + 2 * DAY)
        assertEquals("nothing before 24 hours are credited", 3, v.entries.size)
        elapsed += HOUR
        unlock(yearAhead + 3 * DAY)
        assertNull(arm)
        assertEquals(listOf(id(2)), v.entries.map { it.id })
    }
}
