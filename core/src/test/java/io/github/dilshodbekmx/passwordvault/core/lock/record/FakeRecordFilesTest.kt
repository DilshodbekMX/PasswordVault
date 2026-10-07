package io.github.dilshodbekmx.passwordvault.core.lock.record

import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordFiles
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordFiles.Op
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordFiles.WriteStep
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The fake of lock.md §15.1 that the lock's tests rely on: handed over and durable kept apart with
 * a settle period, `kill()`, `powerCut(settleMs)`, `failNextWrite()`, and a stop at every boundary
 * of a write.
 */
class FakeRecordFilesTest {
    private val clock = FakeClock()
    private val files = FakeRecordFiles(clock)
    private val dir = "/no_backup/lock/0f6b2f0e-5d3b-4c1a-9a7e-2b1c8d4e6f70"

    @Test
    fun aPowerCutKeepsWhatWasHandedOverAtLeastTheSettlePeriodEarlier() {
        files.replace(dir, "pin.rec", bytes(1))
        clock.advance(999)
        files.replace(dir, "pin.rec", bytes(2))
        assertArrayEquals(bytes(2), files.read(dir, "pin.rec")) // the code sees what it handed over
        assertArrayEquals(bytes(1), files.durableBytes(dir, "pin.rec", 999))
        assertNull(files.durableBytes(dir, "pin.rec", 1_000))
        clock.advance(1)
        assertArrayEquals(bytes(1), files.durableBytes(dir, "pin.rec", 1_000))
        files.powerCut(1_000)
        assertArrayEquals(bytes(1), files.read(dir, "pin.rec"))
        // what survived a power cut is durable from then on
        files.powerCut(0)
        assertArrayEquals(bytes(1), files.read(dir, "pin.rec"))
    }

    @Test
    fun aPowerCutWithNothingSettledLosesTheDirectoryItself() {
        files.replace(dir, "state.rec", bytes(1))
        files.powerCut(1_000)
        assertNull(files.read(dir, "state.rec"))
        assertFalse(files.hasDir(dir))
        assertEquals(emptyList<String>(), files.dirs("/no_backup/lock"))
    }

    @Test
    fun aKillKeepsEverythingHandedOverButAPowerCutRightAfterItMayStillLoseIt() {
        files.replace(dir, "pin.rec", bytes(1))
        clock.advance(5_000)
        files.replace(dir, "pin.rec", bytes(2))
        files.kill()
        assertArrayEquals(bytes(2), files.read(dir, "pin.rec"))
        files.powerCut(1_000) // the drive had not settled the last write
        assertArrayEquals(bytes(1), files.read(dir, "pin.rec"))
    }

    @Test
    fun aRebootIsACleanShutdown() {
        files.replace(dir, "pin.rec", bytes(1))
        files.reboot()
        clock.reboot(uptimeMs = 10)
        files.powerCut(1_000)
        assertArrayEquals(bytes(1), files.read(dir, "pin.rec"))
    }

    @Test
    fun aClockThatGoesBackCountsWhatCameBeforeAsDurable() {
        clock.advance(5_000)
        files.replace(dir, "pin.rec", bytes(1))
        clock.reboot(uptimeMs = 0) // nobody told the fake
        files.powerCut(1_000)
        assertArrayEquals(bytes(1), files.read(dir, "pin.rec"))
    }

    @Test
    fun historyOlderThanTheHorizonIsFoldedIntoTheDurableState() {
        val short = FakeRecordFiles(clock, horizonMs = 2_000)
        for (i in 1..50) {
            short.replace(dir, "pin.rec", bytes(i))
            clock.advance(500)
        }
        // the last write was 500 ms ago: a cut with 1,000 ms of settle keeps the one before it
        short.powerCut(1_000)
        assertArrayEquals(bytes(49), short.read(dir, "pin.rec"))
        try {
            short.powerCut(2_001)
            fail("a settle period beyond the horizon")
        } catch (_: IllegalArgumentException) {
            // refused
        }
    }

    @Test
    fun aWriteHasFourBoundariesAndAStopKillsTheProcess() {
        val seen = mutableListOf<String>()
        files.onBoundary = { _, at -> seen += at.substringAfter(": ") }
        files.replace(dir, "pin.rec", bytes(1))
        assertEquals(listOf("before the temp write", "after the file sync", "after the rename", "after the directory sync"), seen)
        assertEquals(4, files.boundaries)
        // what each stop leaves behind
        val expected = mapOf(
            1 to (bytes(1) to false), // nothing was written
            2 to (bytes(1) to true), // a full temp file
            3 to (bytes(2) to false), // renamed
            4 to (bytes(2) to false),
        )
        for ((boundary, outcome) in expected) {
            val run = files.copy()
            run.stopAt(boundary)
            try {
                run.replace(dir, "pin.rec", bytes(2))
                fail("no stop at $boundary")
            } catch (e: FakeRecordFiles.Stopped) {
                assertEquals(boundary, e.boundary)
            }
            assertFalse(run.alive)
            for (call in listOf<() -> Unit>(
                { run.read(dir, "pin.rec") },
                { run.replace(dir, "pin.rec", bytes(3)) },
                { run.dirs("/no_backup/lock") },
                { run.deleteDir(dir) },
                { run.deleteStaleTmp("/no_backup/lock") },
            )) {
                try {
                    call()
                    fail("a dead process ran a call")
                } catch (_: FakeRecordFiles.Stopped) {
                    // nothing runs after death, finally blocks included
                }
            }
            run.kill()
            assertTrue(run.alive)
            assertArrayEquals("boundary $boundary", outcome.first, run.read(dir, "pin.rec"))
            assertEquals("boundary $boundary", outcome.second, "pin.rec.tmp" in run.names(dir))
        }
    }

    @Test
    fun failNextWriteFailsAtTheChosenStep() {
        files.replace(dir, "pin.rec", bytes(1))
        val outcomes = mapOf(
            WriteStep.TEMP_WRITE to Triple(bytes(1), true, 50), // half of the bytes reached the temp file
            WriteStep.FILE_SYNC to Triple(bytes(1), true, 100),
            WriteStep.RENAME to Triple(bytes(1), true, 100),
            WriteStep.DIR_SYNC to Triple(bytes(2), false, 0),
        )
        for ((step, outcome) in outcomes) {
            val run = files.copy()
            run.failNextWrite(step)
            try {
                run.replace(dir, "pin.rec", bytes(2))
                fail("$step did not fail")
            } catch (_: IOException) {
                // not written
            }
            assertArrayEquals("$step", outcome.first, run.read(dir, "pin.rec"))
            assertEquals("$step", outcome.second, "pin.rec.tmp" in run.names(dir))
            if (outcome.second) assertEquals(outcome.third, run.bytes(dir, "pin.rec.tmp")!!.size)
            run.replace(dir, "pin.rec", bytes(3)) // one failure only
            assertArrayEquals(bytes(3), run.read(dir, "pin.rec"))
        }
    }

    @Test
    fun faultsCanFailReadsListingsAndDeletionsAndLastAcrossRestarts() {
        files.replace(dir, "pin.rec", bytes(1))
        files.fail(Op.READ, times = 2) { it.endsWith("pin.rec") }
        repeat(2) {
            try {
                files.read(dir, "pin.rec")
                fail("the read did not fail")
            } catch (_: IOException) {
                files.kill() // a full disk or a bad sector outlives the process
            }
        }
        assertArrayEquals(bytes(1), files.read(dir, "pin.rec"))
        for (op in listOf(Op.DIRS, Op.DELETE_DIR, Op.DELETE_STALE_TMP)) {
            files.fail(op)
            try {
                when (op) {
                    Op.DIRS -> files.dirs("/no_backup/lock")
                    Op.DELETE_DIR -> files.deleteDir(dir)
                    else -> files.deleteStaleTmp("/no_backup/lock")
                }
                fail("$op did not fail")
            } catch (_: IOException) {
                // as asked
            }
        }
        assertArrayEquals(bytes(1), files.read(dir, "pin.rec"))
    }

    @Test
    fun dirsListsTheVaultDirectoriesAndDeletionsRemoveWhatTheySay() {
        val root = "/no_backup/lock"
        val ids = listOf("00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002", "00000000-0000-4000-8000-000000000003")
        for (id in ids) files.replace("$root/$id", "state.rec", bytes(1))
        files.put("$root/${ids[0]}", "pin.rec.tmp", bytes(9))
        files.put("$root/${ids[1]}", "fingerprint.rec.tmp", bytes(9))
        assertEquals(ids.map { "$root/$it" }.toSet(), files.dirs(root).toSet())
        assertEquals(emptyList<String>(), files.dirs("/elsewhere"))
        files.deleteStaleTmp(root)
        assertEquals(listOf("state.rec"), files.names("$root/${ids[0]}"))
        assertEquals(listOf("state.rec"), files.names("$root/${ids[1]}"))
        files.deleteDir("$root/${ids[1]}")
        assertEquals(setOf("$root/${ids[0]}", "$root/${ids[2]}"), files.dirs(root).toSet())
        files.deleteDir("$root/${ids[1]}") // a directory that is gone already: nothing happens
        assertEquals(
            listOf("dirs $root", "dirs /elsewhere", "deleteStaleTmp $root", "deleteDir $root/${ids[1]}", "dirs $root", "deleteDir $root/${ids[1]}"),
            files.log.filterNot { it.startsWith("replace") },
        )
    }

    @Test
    fun onlyPlainAbsolutePathsAndNamesAreAccepted() {
        for (bad in listOf("relative/dir", "/a//b", "/a/../b", "/a/./b", "/a/", "/", "")) {
            try {
                files.replace(bad, "pin.rec", bytes(1))
                fail("'$bad' was accepted")
            } catch (_: IOException) {
                // refused
            }
        }
        for (bad in listOf("", "a/b", "..", ".")) {
            try {
                files.read(dir, bad)
                fail("'$bad' was accepted")
            } catch (_: IOException) {
                // refused
            }
        }
    }

    @Test
    fun aCopyStartsFreshWithTheSameFilesAndHistory() {
        files.replace(dir, "pin.rec", bytes(1))
        files.fail(Op.READ, times = -1)
        files.stopAt(1)
        val copy = files.copy()
        assertArrayEquals(bytes(1), copy.read(dir, "pin.rec")) // no fault
        copy.replace(dir, "pin.rec", bytes(2)) // no stop
        copy.powerCut(1_000) // the same history: the first write had not settled either
        assertNull(copy.read(dir, "pin.rec"))
        assertTrue(copy.log.isNotEmpty())
    }

    private fun bytes(n: Int) = ByteArray(100) { n.toByte() }
}
