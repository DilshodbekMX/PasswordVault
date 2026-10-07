package io.github.dilshodbekmx.passwordvault.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 2.7, [device], on API 24, 28 and 37: [OsFiles] as the lock's `RecordFiles` (lock.md
 * §5.2, §5.9; architecture.md §3.4), in a lock root of its own in the app's `noBackupFilesDir`.
 * `replace` is the routine that ran on API 24, 28 and 37 [L-old §4, L-sto]: a temporary file,
 * fsync, rename over the record, fsync of the directory; any exception means "not written", and
 * the record that was there stays whole.
 */
@RunWith(AndroidJUnit4::class)
class OsRecordFilesTest {
    private val records = OsFiles()
    private val scratch = Scratch(appContext().noBackupFilesDir)
    private val root = scratch.path("lock")
    private val vault = "$root/00000000-0000-4000-8000-000000000001"

    @After
    fun removeScratch() = scratch.remove()

    private fun record(size: Int, seed: Int) = ByteArray(size) { (it * 7 + seed).toByte() }

    @Test
    fun replaceMakesTheLockDirectoryAndWritesTheRecord() {
        assertFalse(File(root).exists())
        records.replace(vault, "state.rec", record(300, 1))
        assertArrayEquals(record(300, 1), records.read(vault, "state.rec"))
        assertEquals("no temporary file is left", listOf("state.rec"), File(vault).list()!!.toList())
        assertEquals("rw-------", "600".toInt(8), scratch.mode("$vault/state.rec"))
        assertEquals("rwx------", "700".toInt(8), scratch.mode(vault))
        assertEquals(listOf(vault), records.dirs(root))
    }

    @Test
    fun replaceReplacesTheRecordThatIsThere() {
        records.replace(vault, "pin.rec", record(500, 1))
        records.replace(vault, "pin.rec", record(200, 2))
        assertArrayEquals(record(200, 2), records.read(vault, "pin.rec"))
        records.replace(vault, "pin.rec", ByteArray(0))
        assertArrayEquals(ByteArray(0), records.read(vault, "pin.rec"))
    }

    @Test
    fun aStaleTemporaryFileDoesNotStopAReplace() {
        scratch.put("$vault/pin.rec.tmp", record(9_000, 3))
        records.replace(vault, "pin.rec", record(100, 4))
        assertArrayEquals(record(100, 4), records.read(vault, "pin.rec"))
        assertFalse(File("$vault/pin.rec.tmp").exists())
    }

    @Test
    fun readGivesNullForAMissingRecordOrDirectory() {
        assertNull("no lock root", records.read(vault, "pin.rec"))
        records.replace(vault, "state.rec", record(10, 1))
        assertNull("no such record", records.read(vault, "fingerprint.rec"))
    }

    @Test
    fun aFileLongerThanAnyRecordComesBackCutOneByteAfterTheLargestRecord() {
        val big = record(200_000, 5)
        scratch.put("$vault/state.rec", big)
        val read = records.read(vault, "state.rec")!!
        assertEquals(8 + 4 + 65_536 + 32 + 1, read.size)
        assertArrayEquals(big.copyOf(read.size), read)
    }

    @Test
    fun aFailedReplaceLeavesTheOldRecordWhole() {
        records.replace(vault, "pin.rec", record(400, 1))
        scratch.chmod(vault, "500")
        try {
            records.replace(vault, "pin.rec", record(300, 2))
            fail("a replace in a read-only directory must throw")
        } catch (e: IOException) {
            // not written
        } finally {
            scratch.chmod(vault, "700")
        }
        assertArrayEquals(record(400, 1), records.read(vault, "pin.rec"))
        assertEquals(listOf("pin.rec"), File(vault).list()!!.toList())
    }

    @Test
    fun aReplaceWhereAFileStandsForTheDirectoryThrows() {
        scratch.put(vault, record(5, 1))
        try {
            records.replace(vault, "pin.rec", record(10, 2))
            fail("a file in the directory's place")
        } catch (e: IOException) {
            // not written
        }
        assertArrayEquals(record(5, 1), File(vault).readBytes())
    }

    @Test
    fun dirsListsTheDirectoriesOnly() {
        assertEquals("no lock root yet", emptyList<String>(), records.dirs(root))
        val second = "$root/00000000-0000-4000-8000-000000000002"
        records.replace(second, "state.rec", record(10, 1))
        records.replace(vault, "state.rec", record(10, 2))
        scratch.put("$root/stray.tmp", record(3, 3))
        assertEquals(listOf(vault, second), records.dirs(root))
        scratch.chmod(root, "300")
        try {
            records.dirs(root)
            fail("a lock root that cannot be listed")
        } catch (e: IOException) {
            // a storage error, not "no records"
        } finally {
            scratch.chmod(root, "700")
        }
    }

    @Test
    fun deleteDirRemovesTheDirectoryWithItsRecords() {
        records.replace(vault, "pin.rec", record(10, 1))
        records.replace(vault, "state.rec", record(10, 2))
        scratch.put("$vault/state.rec.tmp", record(10, 3))
        records.deleteDir(vault)
        assertFalse(File(vault).exists())
        assertTrue("the root stays", File(root).isDirectory)
        records.deleteDir(vault) // again: nothing there, nothing thrown
        assertEquals(emptyList<String>(), records.dirs(root))
    }

    @Test
    fun deleteStaleTmpRemovesTheTemporaryFilesOnly() {
        val second = "$root/00000000-0000-4000-8000-000000000002"
        records.replace(vault, "pin.rec", record(10, 1))
        records.replace(second, "state.rec", record(10, 2))
        scratch.put("$vault/pin.rec.tmp", record(10, 3))
        scratch.put("$second/state.rec.tmp", record(10, 4))
        scratch.put("$root/old.tmp", record(10, 5))
        File("$vault/dir.tmp").mkdirs()
        records.deleteStaleTmp(root)
        assertEquals(listOf("dir.tmp", "pin.rec"), File(vault).list()!!.sorted())
        assertEquals(listOf("state.rec"), File(second).list()!!.toList())
        assertEquals(listOf(vault, second), records.dirs(root))
        assertFalse(File("$root/old.tmp").exists())
        records.deleteStaleTmp(scratch.path("missing")) // no lock root: nothing to do
    }

    @Test
    fun aRecordNameIsAPlainName() {
        // IOException, as `FakeRecordFiles` of :testing throws it, so the lock behaves alike on both.
        for (bad in listOf("", ".", "..", "a/b", "../pin.rec")) {
            for (call in listOf<() -> Unit>({ records.replace(vault, bad, record(1, 1)) }, { records.read(vault, bad) })) {
                try {
                    call()
                    fail("'$bad'")
                } catch (e: IOException) {
                    // never a path outside the directory
                }
            }
        }
        assertFalse(File(root).exists())
    }
}
