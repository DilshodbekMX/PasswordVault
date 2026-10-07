package io.github.dilshodbekmx.passwordvault.platform

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
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
 * Build plan 2.7, [device], on API 24, 28 and 37: the file tests of architecture.md §8.3 for
 * [OsFiles] as the vault store's `FilePrimitives`. Write, sync, rename, directory sync; `rename`
 * onto an existing name replaces it (so the check of §4.9 step 8 is needed); each failure that the
 * storage lab saw throw [L-sto] throws here too, as `IOException`, and leaves the files as they
 * were. Everything runs in a directory of its own in the app's `filesDir`, where the vault lives.
 */
@RunWith(AndroidJUnit4::class)
class OsFilesTest {
    private val files = OsFiles()
    private val scratch = Scratch()

    @After
    fun removeScratch() = scratch.remove()

    private fun bytes(size: Int, seed: Int = 7) = ByteArray(size) { (it * 31 + seed).toByte() }

    private inline fun <reified T : Throwable> assertThrows(what: String, block: () -> Unit): T {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) return t
            throw AssertionError("$what: expected ${T::class.java.simpleName}, got $t", t)
        }
        fail("$what: expected ${T::class.java.simpleName}, nothing was thrown")
        throw IllegalStateException()
    }

    // --- write, read, sync ------------------------------------------------------------------------

    @Test
    fun writeNewThenReadBackGivesTheSameBytes() {
        for (size in listOf(0, 1, 4_096, 4_667, 1_048_576 + 3)) {
            val path = scratch.path("vault-$size.tmp")
            val written = bytes(size)
            files.writeNew(path, written)
            assertArrayEquals("$size bytes", written, files.read(path, 16_777_216))
            assertEquals("rw------- for $size bytes", "600".toInt(8), scratch.mode(path))
        }
        files.syncDir(scratch.dir)
    }

    @Test
    fun readGivesAtMostOneByteMoreThanTheCap() {
        val path = scratch.path("f")
        val content = bytes(100)
        files.writeNew(path, content)
        assertArrayEquals(content.copyOf(11), files.read(path, 10))
        assertArrayEquals(content.copyOf(1), files.read(path, 0))
        assertArrayEquals("one less than the size: too long", content, files.read(path, 99))
        assertArrayEquals(content, files.read(path, 100))
        assertArrayEquals(content, files.read(path, Int.MAX_VALUE))
        files.writeNew(scratch.path("empty"), ByteArray(0))
        assertEquals(0, files.read(scratch.path("empty"), 10).size)
    }

    @Test
    fun writeNewRefusesAnExistingName() {
        val path = scratch.path("vault-0000000001.tmp")
        files.writeNew(path, bytes(10, 1))
        assertThrows<IOException>("a second writeNew") { files.writeNew(path, bytes(20, 2)) }
        assertArrayEquals("the first content stays", bytes(10, 1), files.read(path, 100))
        Os.mkdir(scratch.path("d"), OsConstants.S_IRWXU)
        assertThrows<IOException>("over a directory") { files.writeNew(scratch.path("d"), bytes(1)) }
    }

    @Test
    fun aWholeSaveAsTheStoreRunsIt() {
        // architecture.md §4.9 steps 5 to 9: write the .tmp, read it back, refuse to replace, rename, sync.
        val live = scratch.path("live")
        files.mkdir(live)
        files.syncDir(scratch.dir)
        val tmp = "$live/vault-0000000001.tmp"
        val target = "$live/vault-0000000001.pvault"
        val sealed = bytes(4_667)
        files.writeNew(tmp, sealed)
        assertArrayEquals(sealed, files.read(tmp, 16_777_216))
        assertFalse(files.exists(target))
        files.rename(tmp, target)
        files.syncDir(live)
        assertEquals(listOf("vault-0000000001.pvault"), files.list(live))
        assertArrayEquals(sealed, files.read(target, 16_777_216))
    }

    // --- rename ------------------------------------------------------------------------------------

    @Test
    fun renameOntoAnExistingNameReplacesIt() {
        // [L-sto] test R1, [V-S1]: rename(2) replaces the target, so the store must check first.
        val from = scratch.path("vault-0000000002.tmp")
        val to = scratch.path("vault-0000000002.pvault")
        files.writeNew(from, bytes(10, 1))
        files.writeNew(to, bytes(20, 2))
        files.rename(from, to)
        assertFalse(files.exists(from))
        assertArrayEquals("the target was replaced", bytes(10, 1), files.read(to, 100))
    }

    @Test
    fun renameMovesADirectoryAndReplacesOnlyAnEmptyOne() {
        val live = scratch.path("live")
        files.mkdir(live)
        files.writeNew("$live/vault-0000000001.pvault", bytes(10))
        val aside = scratch.path("aside-1791244800000-00000000-0000-4000-8000-000000000001")
        files.rename(live, aside)
        assertFalse(files.exists(live))
        assertEquals(listOf("vault-0000000001.pvault"), files.list(aside))

        val empty = scratch.path("empty")
        files.mkdir(empty)
        files.rename(aside, empty)
        assertEquals(listOf("vault-0000000001.pvault"), files.list(empty))

        val full = scratch.path("full")
        files.mkdir(full)
        files.writeNew("$full/x", bytes(1))
        assertThrows<IOException>("onto a directory that is not empty") { files.rename(empty, full) }
        assertEquals(listOf("vault-0000000001.pvault"), files.list(empty))
    }

    @Test
    fun renameOfAMissingNameThrows() {
        assertThrows<FileNotFoundException>("no such source") { files.rename(scratch.path("missing"), scratch.path("x")) }
    }

    // --- directories -------------------------------------------------------------------------------

    @Test
    fun syncDirSyncsADirectoryAndRefusesEverythingElse() {
        files.syncDir(scratch.dir)
        assertThrows<FileNotFoundException>("a missing directory") { files.syncDir(scratch.path("missing")) }
        files.writeNew(scratch.path("f"), bytes(3))
        assertThrows<IOException>("a file") { files.syncDir(scratch.path("f")) }
    }

    @Test
    fun listGivesTheNamesNullForAMissingDirectoryAndThrowsWhenItCannotBeListed() {
        val dir = scratch.path("live")
        assertNull("no such directory", files.list(dir))
        files.mkdir(dir)
        assertEquals(emptyList<String>(), files.list(dir))
        for (name in listOf("vault-0000000001.pvault", "vault-0000000002.bad", "notes.txt")) files.writeNew("$dir/$name", bytes(1))
        files.mkdir("$dir/sub")
        assertEquals(listOf("notes.txt", "sub", "vault-0000000001.pvault", "vault-0000000002.bad"), files.list(dir)!!.sorted())

        assertThrows<IOException>("a file in its place") { files.list("$dir/notes.txt") }
        // A directory that exists but cannot be read is a storage error, never "no vault" (§4.7).
        scratch.chmod(dir, "300")
        assertThrows<IOException>("a directory that cannot be read") { files.list(dir) }
        scratch.chmod(dir, "700")
    }

    @Test
    fun existsSeesFilesAndDirectories() {
        val dir = scratch.path("live")
        assertFalse(files.exists(dir))
        files.mkdir(dir)
        assertTrue(files.exists(dir))
        files.writeNew("$dir/f", bytes(1))
        assertTrue(files.exists("$dir/f"))
        assertFalse("below a file", files.exists("$dir/f/g"))
        assertFalse(files.exists("$dir/g"))
    }

    @Test
    fun mkdirMakesOneDirectory() {
        val dir = scratch.path("vaults")
        files.mkdir(dir)
        assertTrue(File(dir).isDirectory)
        assertEquals("rwx------", "700".toInt(8), scratch.mode(dir))
        assertThrows<IOException>("an existing name") { files.mkdir(dir) }
        assertThrows<FileNotFoundException>("a missing parent") { files.mkdir(scratch.path("a", "b")) }
    }

    @Test
    fun deleteRemovesAFileAndRefusesADirectory() {
        val path = scratch.path("vault-0000000001.tmp")
        files.writeNew(path, bytes(5))
        files.delete(path)
        assertFalse(files.exists(path))
        assertThrows<FileNotFoundException>("a missing file") { files.delete(path) }
        files.mkdir(scratch.path("d"))
        assertThrows<IOException>("a directory") { files.delete(scratch.path("d")) }
        assertTrue(files.exists(scratch.path("d")))
    }

    @Test
    fun deleteTreeRemovesEverythingButNeverFollowsALink() {
        val outside = Scratch()
        try {
            val kept = outside.path("kept")
            outside.put(kept, bytes(9))
            val dir = scratch.path("new-00000000-0000-4000-8000-000000000001")
            files.mkdir(dir)
            files.writeNew("$dir/vault-0000000001.tmp", bytes(10))
            files.mkdir("$dir/sub")
            files.writeNew("$dir/sub/x", bytes(3))
            files.mkdir("$dir/sub/empty")
            Os.symlink(kept, "$dir/link")
            files.deleteTree(dir)
            assertFalse(files.exists(dir))
            assertArrayEquals("the link's target is untouched", bytes(9), File(kept).readBytes())
            assertThrows<FileNotFoundException>("a missing directory") { files.deleteTree(dir) }
            files.writeNew(scratch.path("f"), bytes(1))
            assertThrows<IOException>("a file") { files.deleteTree(scratch.path("f")) }
        } finally {
            outside.remove()
        }
    }

    @Test
    fun pathsAreAbsoluteAndPlain() {
        for (bad in listOf("relative/path", "", scratch.dir + "/../x", scratch.dir + "/./x", scratch.dir + "//x", scratch.dir + "/")) {
            assertThrows<IOException>("'$bad'") { files.exists(bad) }
        }
    }

    // --- the failures of [L-sto] -------------------------------------------------------------------

    @Test
    fun writingIntoAMissingDirectoryThrows() {
        assertThrows<FileNotFoundException>("writeNew") { files.writeNew(scratch.path("missing", "vault-0000000001.tmp"), bytes(4)) }
        val from = scratch.path("a")
        files.writeNew(from, bytes(4))
        assertThrows<FileNotFoundException>("rename") { files.rename(from, scratch.path("missing", "a")) }
        assertArrayEquals("the source stays", bytes(4), files.read(from, 10))
    }

    @Test
    fun writingIntoAReadOnlyDirectoryThrowsAndChangesNothing() {
        val dir = scratch.path("live")
        files.mkdir(dir)
        val old = "$dir/vault-0000000001.pvault"
        val tmp = "$dir/vault-0000000002.tmp"
        files.writeNew(old, bytes(8, 1))
        files.writeNew(tmp, bytes(8, 2))
        scratch.chmod(dir, "500")
        try {
            assertThrows<IOException>("the commit into a read-only directory") { files.writeNew("$dir/vault-0000000003.tmp", bytes(8)) }
            assertThrows<IOException>("rename inside a read-only directory") { files.rename(tmp, "$dir/vault-0000000002.pvault") }
            assertTrue("the temporary file stays", files.exists(tmp))
            assertThrows<IOException>("delete in a read-only directory") { files.delete(old) }
            assertThrows<IOException>("mkdir in a read-only directory") { files.mkdir("$dir/sub") }
            // An fsync of a read-only directory succeeds (the lab saw the same).
            files.syncDir(dir)
        } finally {
            scratch.chmod(dir, "700")
        }
        assertArrayEquals(bytes(8, 1), files.read(old, 100))
        assertEquals(listOf("vault-0000000001.pvault", "vault-0000000002.tmp"), files.list(dir)!!.sorted())
    }

    @Test
    fun renamingIntoAReadOnlyDirectoryThrows() {
        val readOnly = scratch.path("ro")
        files.mkdir(readOnly)
        val from = scratch.path("vault-0000000001.tmp")
        files.writeNew(from, bytes(6))
        scratch.chmod(readOnly, "500")
        try {
            assertThrows<IOException>("rename from a writable directory into a read-only one") {
                files.rename(from, "$readOnly/vault-0000000001.pvault")
            }
        } finally {
            scratch.chmod(readOnly, "700")
        }
        assertArrayEquals("the source stays", bytes(6), files.read(from, 10))
        assertEquals(emptyList<String>(), files.list(readOnly))
    }

    @Test
    fun aFileOpenedBeforeItsDirectoryBecameReadOnlyStillWritesAndSyncs() {
        // [L-sto]: "These succeeded, as expected".
        val dir = scratch.path("d")
        files.mkdir(dir)
        val path = "$dir/f"
        FileOutputStream(path).use { out ->
            scratch.chmod(dir, "500")
            try {
                out.write(bytes(100))
                out.flush()
                out.fd.sync()
            } finally {
                scratch.chmod(dir, "700")
            }
        }
        assertArrayEquals(bytes(100), files.read(path, 1_000))
    }

    @Test
    fun theSystemCallsUnderOsFilesReportMisuse() {
        // [L-sto]: each of these threw, instead of failing silently; OsFiles relies on that.
        val path = scratch.path("f")
        val stream = FileOutputStream(path)
        val fd = stream.fd
        stream.close()
        assertThrows<IOException>("fd.sync() after the stream was closed") { fd.sync() }
        val file = Os.open(path, OsConstants.O_RDONLY, 0)
        Os.close(file)
        assertThrows<ErrnoException>("Os.fsync on a closed descriptor") { Os.fsync(file) }
        val dirFd = Os.open(scratch.dir, OsConstants.O_RDONLY, 0)
        Os.fsync(dirFd)
        Os.close(dirFd)
        assertThrows<ErrnoException>("Os.fsync on a directory descriptor after Os.close") { Os.fsync(dirFd) }
        assertThrows<ErrnoException>("closing a directory descriptor twice") { Os.close(dirFd) }
    }
}
