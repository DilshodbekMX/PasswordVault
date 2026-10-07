package io.github.dilshodbekmx.passwordvault.core.storage

import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.testing.storage.FakeCodec
import io.github.dilshodbekmx.passwordvault.testing.storage.FakeFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A kill or a power cut at every step of architecture.md §4.9 leaves the last good generation
 * openable (§8.1): for `commit`, for `stageVault` with and without `putCurrentAside`, for
 * `stageRekey` (a kill during the proof leaves only a `.tmp`), and for the renames of §4.11.
 *
 * Each case builds the same phone again for every run, stops at the n-th boundary of the step
 * (each call of FakeFiles, and three inside writeNew), and then opens the vault in a new process.
 * A rename never replaces anything, in any run [V-S1].
 */
class CrashTest {
    private class Start(val phone: Phone, val store: VaultStore, val opened: OpenedVault?, val keys: Keys) {
        /** Set by a step when its proof came back true. */
        var proven = false
    }

    /**
     * Runs [step] from [start] once per boundary it passes, stopping there [how], and calls [after]
     * with what the stop interrupted (null: the run that passed every boundary). Returns the number
     * of boundaries.
     */
    private fun everyBoundary(how: FakeFiles.Stop, start: () -> Start, step: (Start) -> Unit, after: (Start, String?) -> Unit): Int {
        var k = 1
        while (true) {
            val s = start()
            s.phone.files.stopAt(k, how)
            val stoppedAt = try {
                step(s)
                null
            } catch (e: FakeFiles.Stopped) {
                e.at
            }
            s.phone.files.clearStop()
            try {
                after(s, stoppedAt)
            } catch (e: AssertionError) {
                throw AssertionError("$how at boundary $k ($stoppedAt): ${e.message}\n${s.phone.files.tree()}", e)
            }
            s.phone.assertNoReplacement()
            if (stoppedAt == null) return k - 1
            k++
        }
    }

    private fun both(start: () -> Start, step: (Start) -> Unit, after: (Start, String?) -> Unit) {
        val kills = everyBoundary(FakeFiles.Stop.KILL, start, step, after)
        val cuts = everyBoundary(FakeFiles.Stop.POWER_CUT, start, step, after)
        assertEquals(kills, cuts)
        assertTrue("a step of some length: $kills", kills >= 4)
    }

    private val first = "first master password"
    private val second = "second master password"

    /** A vault with generations 1 (vaultOf(1)) and 2 (vaultOf(2)) from an earlier process, opened with the password in this one. */
    private fun openedVault(): Start {
        val phone = Phone()
        val earlier = phone.process()
        val (keys, opened) = phone.create(earlier, vaultOf(1), first)
        phone.save(earlier, opened, vaultOf(2))
        val store = phone.process()
        store.scan()
        return Start(phone, store, phone.unlockOpened(store, first), keys)
    }

    private fun currentFacts(phone: Phone, store: VaultStore = phone.process()): VaultFacts =
        (store.scan().current as Current.Present).facts

    @Test
    fun anOrdinarySave() {
        both(::openedVault, { s -> s.store.commit(s.opened!!, vaultOf(3)) }) { s, stoppedAt ->
            val next = s.phone.process()
            assertTrue(currentFacts(s.phone, next).newest.number in 2L..3L)
            val opened = (next.open(VaultRef.Current, mapOf(s.keys.vkId to s.keys.vk)) as OpenResult.Opened).opened
            if (stoppedAt == null) assertEquals(vaultOf(3), opened.vault)
            assertTrue("the last good generation or the new one", opened.vault == vaultOf(2) || opened.vault == vaultOf(3))
            assertTrue(s.phone.liveNumbers().containsAll(listOf(1L, 2L)))
            assertEquals("no .tmp after start-up", emptyList<Long>(), s.phone.numbers(Names.TMP))
        }
    }

    @Test
    fun creatingTheFirstVault() {
        val start = {
            val phone = Phone()
            val store = phone.process()
            store.scan()
            Start(phone, store, null, phone.keys(first))
        }
        val step = { s: Start ->
            val k = s.keys
            val staged = (s.store.stageVault(vaultOf(4), k.vaultId, k.vk, k.vkId, k.slot) as StageResult.Staged).staged
            s.proven = s.store.prove(staged, FakeCodec.kek(first, staged.slotSalt))
            s.store.publish(staged, putCurrentAside = false)
            Unit
        }
        both(start, step) { s, stoppedAt ->
            val next = s.phone.process()
            when (val current = next.scan().current) {
                Current.NoVault -> assertTrue("only an interrupted creation leaves no vault", stoppedAt != null)
                is Current.Present -> {
                    assertTrue("published only after the proof", s.proven)
                    assertEquals(s.keys.vaultId, current.facts.vaultId)
                    assertEquals(vaultOf(4), s.phone.unlockOpened(next, first).vault)
                }
                else -> throw AssertionError("unexpected $current")
            }
            assertTrue("no unpublished directory after start-up", s.phone.files.names(VAULTS).none { it.startsWith("new-") })
        }
    }

    @Test
    fun aRestoreOverTheCurrentVault() {
        // lock.md F17 step 3 and F11 step 16: a new vault beside the current one, proven, then the swap.
        val start = {
            val s = openedVault()
            Start(s.phone, s.store, s.opened, s.phone.keys("restored password"))
        }
        val step = { s: Start ->
            val k = s.keys
            val staged = (s.store.stageVault(vaultOf(7), k.vaultId, k.vk, k.vkId, k.slot) as StageResult.Staged).staged
            s.proven = s.store.prove(staged, FakeCodec.kek("restored password", staged.slotSalt))
            s.store.publish(staged, putCurrentAside = true)
            Unit
        }
        both(start, step) { s, stoppedAt ->
            val next = s.phone.process()
            val scan = next.scan()
            val oldId = s.opened!!.vaultId
            val asideIds = scan.aside.map { it.vaultId }
            when (val current = scan.current) {
                is Current.Present -> if (current.facts.vaultId == oldId) {
                    assertFalse(oldId in asideIds)
                } else {
                    assertEquals(s.keys.vaultId, current.facts.vaultId)
                    assertTrue("published only after the proof", s.proven)
                    assertEquals(listOf(oldId), asideIds)
                    assertEquals(vaultOf(7), s.phone.unlockOpened(next, "restored password").vault)
                }
                Current.NoVault -> {
                    assertTrue("a stop between the two renames", stoppedAt != null)
                    assertEquals("Welcome lists the vault put aside", listOf(oldId), asideIds)
                }
                else -> throw AssertionError("unexpected $current")
            }
            // The old vault always opens: current or put aside.
            val where = if ((scan.current as? Current.Present)?.facts?.vaultId == oldId) VaultRef.Current else VaultRef.Aside(oldId)
            assertEquals(vaultOf(2), s.phone.unlockOpened(next, first, where).vault)
            if (stoppedAt == null) assertEquals(s.keys.vaultId, (scan.current as Current.Present).facts.vaultId)
        }
    }

    @Test
    fun aMasterPasswordChange() {
        val start = {
            val s = openedVault()
            Start(s.phone, s.store, s.opened, s.phone.keys(second, s.opened!!.vaultId))
        }
        val step = { s: Start ->
            val k = s.keys
            val staged = (s.store.stageRekey(s.opened!!, s.opened.vault, k.vk, k.vkId, k.slot) as StageResult.Staged).staged
            s.proven = s.store.prove(staged, FakeCodec.kek(second, staged.slotSalt))
            s.store.publish(staged, putCurrentAside = false)
            Unit
        }
        both(start, step) { s, stoppedAt ->
            val newKeyLive = s.phone.files.has(live(3))
            if (newKeyLive) assertTrue("a live generation never carries an unproven slot", s.proven)
            if (!s.proven && stoppedAt != null) {
                assertTrue("before the proof ends, at most a .tmp", s.phone.numbers(Names.PVAULT).max() <= 2L)
            }
            val next = s.phone.process()
            val facts = currentFacts(s.phone, next)
            if (facts.newest.vkId == s.keys.vkId) {
                assertEquals(3L, facts.newest.number)
                assertEquals(vaultOf(2), s.phone.unlockOpened(next, second).vault)
                assertTrue("the old password is older, never wrong", s.phone.unlock(next, first) is PasswordResult.OlderPassword)
            } else {
                assertEquals(s.opened!!.vkId, facts.newest.vkId)
                assertEquals(vaultOf(2), s.phone.unlockOpened(next, first).vault)
            }
            assertEquals("the next start deletes the .tmp", emptyList<Long>(), s.phone.numbers(Names.TMP))
            if (stoppedAt == null) assertEquals(s.keys.vkId, facts.newest.vkId)
        }
    }

    @Test
    fun aSwapOfTheCurrentVaultAndOnePutAside() {
        val start = {
            val phone = Phone()
            val earlier = phone.process()
            val (a, _) = phone.create(earlier, vaultOf(1), first)
            assertTrue(earlier.putAside())
            phone.clock.advance(1_000L)
            phone.create(earlier, vaultOf(2), second)
            val store = phone.process()
            store.scan()
            phone.unlockOpened(store, first, VaultRef.Aside(a.vaultId))
            Start(phone, store, null, a)
        }
        both(start, { s -> s.store.swapIn(s.keys.vaultId) }) { s, stoppedAt ->
            val next = s.phone.process()
            val scan = next.scan()
            val ids = scan.aside.map { it.vaultId } + listOfNotNull((scan.current as? Current.Present)?.facts?.vaultId)
            assertEquals("neither vault is ever lost", 2, ids.toSet().size)
            if (scan.current == Current.NoVault) assertTrue("only between the two renames", stoppedAt != null)
            for (vault in scan.aside) {
                val password = if (vault.vaultId == s.keys.vaultId) first else second
                s.phone.unlockOpened(next, password, VaultRef.Aside(vault.vaultId))
            }
            if (stoppedAt == null) assertEquals(s.keys.vaultId, (scan.current as Current.Present).facts.vaultId)
        }
    }

    @Test
    fun goingBackFromAnUnfinishedChangeCanBeRepeated() {
        // lock.md F16b step 2: generations above the chosen one go to .aside; a repeat finishes it.
        val start = {
            val s = openedVault()
            s.phone.changePassword(s.store, s.opened!!, second)
            val store = s.phone.process()
            store.scan()
            Start(s.phone, store, null, s.keys)
        }
        both(start, { s -> s.store.putNewerAside(2) }) { s, _ ->
            val next = s.phone.process()
            next.scan()
            assertTrue(next.putNewerAside(2))
            assertEquals(listOf(1L, 2L), s.phone.liveNumbers())
            assertEquals(listOf(3L), s.phone.numbers(Names.ASIDE))
            assertEquals(vaultOf(2), s.phone.unlockOpened(next, first).vault)
        }
    }

    @Test
    fun housekeepingThatIsCutOffDeletesNothingItShouldKeep() {
        val start = {
            val phone = Phone()
            val earlier = phone.process()
            var (keys, opened) = phone.create(earlier, vaultOf(1), first)
            for (n in 2..10) opened = phone.save(earlier, opened, vaultOf(n))
            val store = phone.process()
            store.scan()
            val reopened = phone.unlockOpened(store, first)
            Start(phone, store, phone.save(store, reopened, vaultOf(11)), keys)
        }
        both(start, { s -> s.store.housekeeping(s.opened!!) }) { s, stoppedAt ->
            assertTrue("the five verified and the new one stay", s.phone.liveNumbers().containsAll((6L..11L).toList()))
            val next = s.phone.process()
            assertEquals(vaultOf(11), s.phone.unlockOpened(next, first).vault)
            if (stoppedAt == null) assertEquals((6L..11L).toList(), s.phone.liveNumbers())
        }
    }

    @Test
    fun aSyncThatLiesLosesAtMostTheLastSaveAndTheOlderCopyOpens() {
        // V-S8: fsync returned, the bytes were still in the drive's cache when the power went.
        val s = openedVault()
        s.phone.files.lyingFileSync = true
        assertTrue(s.store.commit(s.opened!!, vaultOf(3)) is CommitResult.Saved)
        s.phone.files.lyingFileSync = false
        s.phone.files.powerCut()
        val next = s.phone.process()
        val facts = currentFacts(s.phone, next)
        assertEquals(2L, facts.newest.number)
        assertTrue("a zero-filled generation 3 lies above it", facts.olderCopyFirst)
        val opened = (next.open(VaultRef.Current, mapOf(s.keys.vkId to s.keys.vk)) as OpenResult.Opened).opened
        assertEquals(vaultOf(2), opened.vault)
        assertEquals(3L, opened.newestOnDisk)
        // The next save goes above the damaged one and never replaces it.
        assertEquals(4L, s.phone.save(next, opened, Vault()).generation)
    }
}
