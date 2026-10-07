package io.github.dilshodbekmx.passwordvault.core.storage

import io.github.dilshodbekmx.passwordvault.testing.storage.FakeFiles
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The model of [FakeFiles] that the crash tests rely on: what was handed over shows at once, what
 * survives a power cut is what was synced, a kill changes nothing on disk, and a rename replaces.
 */
class FakeFilesTest {
    private val bytes = ByteArray(10) { it.toByte() }

    private fun disk() = FakeFiles().apply { mkdirs("/d") }

    @Test
    fun aWrittenFileShowsAtOnceAndItsNameNeedsTheDirectorySync() {
        val fs = disk()
        fs.writeNew("/d/f", bytes)
        assertArrayEquals(bytes, fs.read("/d/f", 100))
        fs.powerCut()
        assertFalse("the name was never synced", fs.exists("/d/f"))

        fs.writeNew("/d/f", bytes)
        fs.syncDir("/d")
        fs.powerCut()
        assertArrayEquals("bytes synced by writeNew, name by syncDir", bytes, fs.read("/d/f", 100))
    }

    @Test
    fun aRenameAndADeleteAreUndoneByAPowerCutUntilSynced() {
        val fs = disk()
        fs.writeNew("/d/a", bytes)
        fs.syncDir("/d")
        fs.rename("/d/a", "/d/b")
        fs.powerCut()
        assertTrue(fs.exists("/d/a"))
        assertFalse(fs.exists("/d/b"))
        fs.rename("/d/a", "/d/b")
        fs.syncDir("/d")
        fs.delete("/d/b")
        fs.powerCut()
        assertTrue("the delete was not synced", fs.exists("/d/b"))
    }

    @Test
    fun aLyingFsyncLosesTheBytesButNotTheName() {
        val fs = disk()
        fs.lyingFileSync = true
        fs.writeNew("/d/f", bytes)
        fs.syncDir("/d")
        fs.powerCut()
        assertArrayEquals(ByteArray(bytes.size), fs.read("/d/f", 100))
        fs.lostWrite = FakeFiles.LostWrite.EMPTY
        fs.powerCut()
        assertEquals(0, fs.read("/d/f", 100).size)
    }

    @Test
    fun renameReplacesAnExistingFileAndSaysSo() {
        val fs = disk()
        fs.writeNew("/d/a", bytes)
        fs.writeNew("/d/b", ByteArray(3))
        fs.rename("/d/a", "/d/b")
        assertArrayEquals(bytes, fs.read("/d/b", 100))
        assertEquals(listOf("/d/a -> /d/b"), fs.replacements)
        fs.mkdirs("/d/x/y")
        fs.mkdirs("/d/z")
        try {
            fs.rename("/d/z", "/d/x")
            fail("a directory onto one that is not empty")
        } catch (_: IOException) {
        }
    }

    @Test
    fun theStrictCallsOfAFileSystem() {
        val fs = disk()
        assertNull(fs.list("/d/none"))
        fs.writeNew("/d/f", bytes)
        for (call in listOf<() -> Unit>(
            { fs.writeNew("/d/f", bytes) }, // create exclusively
            { fs.delete("/d/none") },
            { fs.deleteTree("/d/none") },
            { fs.mkdir("/d") },
            { fs.mkdir("/nowhere/x") },
            { fs.rename("/d/none", "/d/g") },
            { fs.read("/d/none", 10) },
            { fs.list("/d/f") },
            { fs.read("/d/../d/f", 10) },
            { fs.read("d/f", 10) },
        )) {
            try {
                call()
                fail("should throw")
            } catch (_: IOException) {
            }
        }
        assertEquals("read gives at most maxBytes + 1", 4, fs.read("/d/f", 3).size)
    }

    @Test
    fun aStopThrowsAndAPowerCutStopReverts() {
        val fs = disk()
        fs.writeNew("/d/a", bytes)
        fs.stopAt(1, FakeFiles.Stop.POWER_CUT)
        try {
            fs.syncDir("/d")
            fail("stopped")
        } catch (e: FakeFiles.Stopped) {
            assertTrue(e.at, e.at.startsWith("syncDir"))
        }
        assertFalse("the sync never ran", fs.exists("/d/a"))

        val other = disk()
        other.stopAt(3, FakeFiles.Stop.KILL) // writeNew: before, created, written
        try {
            other.writeNew("/d/b", bytes)
            fail("stopped")
        } catch (_: FakeFiles.Stopped) {
        }
        assertArrayEquals("a kill keeps what was handed over", bytes, other.read("/d/b", 100))
    }

    @Test
    fun failuresAreInjected() {
        val fs = disk()
        fs.writeNew("/d/a", bytes)
        fs.fail(FakeFiles.Op.READ, times = -1) { it == "/d/a" }
        repeat(3) {
            try {
                fs.read("/d/a", 100)
                fail("always")
            } catch (_: IOException) {
            }
        }
        fs.clearFaults()
        assertArrayEquals(bytes, fs.read("/d/a", 100))
        fs.fail(FakeFiles.Op.WRITE_NEW)
        try {
            fs.writeNew("/d/b", bytes)
            fail("no space")
        } catch (_: IOException) {
        }
        assertEquals("half written", 5, fs.read("/d/b", 100).size)
    }

    @Test
    fun aCopyIsIndependent() {
        val fs = disk()
        fs.writeNew("/d/a", bytes)
        fs.syncDir("/d")
        fs.writeNew("/d/b", bytes)
        val copy = fs.copy()
        copy.delete("/d/a")
        assertTrue(fs.exists("/d/a"))
        copy.powerCut()
        assertTrue("its durable state was copied too", copy.exists("/d/a"))
        assertFalse(copy.exists("/d/b"))
        assertTrue(fs.exists("/d/b"))
    }
}
