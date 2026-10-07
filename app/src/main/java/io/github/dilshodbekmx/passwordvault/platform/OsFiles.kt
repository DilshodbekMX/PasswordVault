package io.github.dilshodbekmx.passwordvault.platform

import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordFiles
import io.github.dilshodbekmx.passwordvault.core.storage.FilePrimitives
import java.io.File
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.io.IOException

/**
 * The files of the vault and of the lock records on the phone (architecture.md §2.2 `platform`,
 * §3.3 `FilePrimitives`, §3.4 `RecordFiles`; lock.md §5.9). Every call is a system call through
 * `android.system.Os`: `open`, `write`, `fsync`, `rename(2)`, an `fsync` of the directory, as the
 * labs ran them on Android 7, 9 and 17 [L-old §4, L-sto]. Never `AtomicFile`, DataStore or
 * `Files.move`, which swallow failures or are not atomic [V-S7].
 *
 * **Failures.** Every failure throws `IOException` (`FileNotFoundException` when a name does not
 * exist, ENOENT); nothing is logged and nothing is swallowed, except where a call says so. A
 * message names the system call and the errno, never a path or file content (architecture.md §5.6).
 * The calls block: callers run them on `Dispatchers.IO` (§5.3).
 *
 * **Paths** are absolute, with `/`, and have no empty, `.` or `..` part; anything else throws
 * `IOException`, as `FakeFiles` of :testing does, so that a path that works in the JVM tests is one
 * that works here. Symbolic links are never followed when deleting.
 *
 * **What the vault store relies on** (`FilePrimitives`, the same rules as `FakeFiles`):
 * - [list] gives null only when the directory does not exist (ENOENT); a directory that exists but
 *   cannot be read, and a file in its place, throw, because "cannot be listed" is a storage error and
 *   never "no vault" (architecture.md §4.7).
 * - [writeNew] creates the file exclusively (`O_CREAT | O_EXCL`, mode 0600), writes every byte,
 *   fsyncs and closes it; a failed close is a failed write. A file it created stays when a later step
 *   fails (the store deletes its `.tmp` files itself).
 * - [rename] is rename(2): it **replaces** an existing target [L-sto R1, V-S1], so the store checks
 *   first (architecture.md §4.9 step 8).
 * - [syncDir] opens the directory read-only, checks that it is one, fsyncs and closes it. A
 *   failure is `IOException`, which the store reports as "not durable" [V-S2].
 * - [delete] removes a file and refuses a directory; [mkdir] makes one directory (mode 0700) and
 *   refuses an existing name; [deleteTree] removes a directory with everything in it.
 *
 * **The lock records** (`RecordFiles`, lock.md §5.2, §5.9): [replace] writes `<name>.tmp`, fsyncs
 * it, renames it over `<name>` and fsyncs the directory, which it makes first (and its parents, each
 * synced into its own parent) when it is missing, because a vault's lock directory is made when its
 * first record is written (lock.md F1, F14). Any exception means "not written".
 */
class OsFiles : FilePrimitives, RecordFiles {

    // ---------------------------------------------------------------------------------------------
    // FilePrimitives

    override fun list(dir: String): List<String>? {
        checkPath(dir)
        File(dir).list()?.let { return it.toList() }
        // File.list gives null both when the directory does not exist and when it cannot be read;
        // only the first is "no directory".
        val mode = try {
            Os.lstat(dir).st_mode
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.ENOENT) return null
            throw ioException("lstat", e)
        }
        throw IOException(if (OsConstants.S_ISDIR(mode)) "list failed: the directory cannot be read" else "list failed: not a directory")
    }

    override fun read(path: String, maxBytes: Int): ByteArray {
        require(maxBytes >= 0) { "maxBytes is negative" }
        checkPath(path)
        val limit = if (maxBytes == Int.MAX_VALUE) Int.MAX_VALUE else maxBytes + 1
        return readAtMost(path, limit) ?: throw FileNotFoundException("open failed: ENOENT")
    }

    override fun writeNew(path: String, bytes: ByteArray) {
        checkPath(path)
        writeFile(path, bytes, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL)
    }

    override fun exists(path: String): Boolean {
        checkPath(path)
        return try {
            Os.lstat(path)
            true
        } catch (e: ErrnoException) {
            // A missing name, or a missing directory on the way to it (ENOTDIR: a file in its place).
            if (e.errno == OsConstants.ENOENT || e.errno == OsConstants.ENOTDIR) false else throw ioException("lstat", e)
        }
    }

    override fun rename(from: String, to: String) {
        checkPath(from)
        checkPath(to)
        errno("rename") { Os.rename(from, to) }
    }

    override fun syncDir(dir: String) {
        checkPath(dir)
        val fd = errno("open") { Os.open(dir, OsConstants.O_RDONLY or cloexec(), 0) }
        try {
            if (!OsConstants.S_ISDIR(errno("fstat") { Os.fstat(fd) }.st_mode)) throw IOException("syncDir failed: not a directory")
            errno("fsync") { Os.fsync(fd) }
        } catch (t: Throwable) {
            closeQuietly(fd)
            throw t
        }
        errno("close") { Os.close(fd) }
    }

    override fun delete(path: String) {
        checkPath(path)
        if (OsConstants.S_ISDIR(errno("lstat") { Os.lstat(path) }.st_mode)) throw IOException("delete failed: is a directory")
        errno("remove") { Os.remove(path) }
    }

    override fun mkdir(dir: String) {
        checkPath(dir)
        errno("mkdir") { Os.mkdir(dir, DIR_MODE) }
    }

    override fun deleteTree(dir: String) {
        checkPath(dir)
        if (!OsConstants.S_ISDIR(errno("lstat") { Os.lstat(dir) }.st_mode)) throw IOException("deleteTree failed: not a directory")
        removeTree(dir)
    }

    // ---------------------------------------------------------------------------------------------
    // RecordFiles

    /**
     * The bytes of [name] in [dir], or null when there is no such file (or no such directory). A
     * file longer than any record can be (lock.md §5.3: 8 + 4 + 65,536 + 32 bytes) comes back cut
     * one byte after that size, which `RecordCodec` refuses as damaged, so a damaged file cannot
     * fill the heap.
     */
    override fun read(dir: String, name: String): ByteArray? = readAtMost(recordPath(dir, name), MAX_RECORD_FILE + 1)

    override fun replace(dir: String, name: String, bytes: ByteArray) {
        val target = recordPath(dir, name)
        val tmp = target + TMP
        makeDirs(dir)
        try {
            writeFile(tmp, bytes, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_TRUNC)
            errno("rename") { Os.rename(tmp, target) }
        } catch (t: Throwable) {
            // Nothing was replaced; the temporary file would otherwise wait for the next start.
            removeQuietly(tmp)
            throw t
        }
        syncDir(dir)
    }

    /** The directories in [root], as paths, sorted; empty when [root] does not exist yet. */
    override fun dirs(root: String): List<String> {
        val names = list(root) ?: return emptyList()
        return names.sorted().map { "$root/$it" }.filter { isDirectory(it) }
    }

    /** Deletes [dir] with everything in it and syncs its parent; nothing when it does not exist. */
    override fun deleteDir(dir: String) {
        if (!exists(dir)) return
        deleteTree(dir)
        parentOf(dir)?.let { syncDir(it) }
    }

    /**
     * Deletes every `*.tmp` file in [root] and in the directories in it (lock.md §5.9, §5.11 step 1).
     * One that cannot be deleted does not stop the others; the first failure is thrown at the end.
     */
    override fun deleteStaleTmp(root: String) {
        var failure: IOException? = null
        for (dir in listOf(root) + dirs(root)) {
            val names = try {
                list(dir)
            } catch (e: IOException) {
                failure = failure ?: e
                null
            } ?: continue
            for (name in names) {
                if (!name.endsWith(TMP)) continue
                try {
                    val path = "$dir/$name"
                    if (OsConstants.S_ISREG(errno("lstat") { Os.lstat(path) }.st_mode)) errno("remove") { Os.remove(path) }
                } catch (e: IOException) {
                    failure = failure ?: e
                }
            }
        }
        failure?.let { throw it }
    }

    // ---------------------------------------------------------------------------------------------

    /** At most [limit] bytes of the file at [path]; null when there is no such file (ENOENT). */
    private fun readAtMost(path: String, limit: Int): ByteArray? {
        val fd = try {
            Os.open(path, OsConstants.O_RDONLY or cloexec(), 0)
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.ENOENT) return null
            throw ioException("open", e)
        }
        try {
            val stat = errno("fstat") { Os.fstat(fd) }
            if (OsConstants.S_ISDIR(stat.st_mode)) throw IOException("read failed: is a directory")
            // Room for the whole file and one byte more, so that the read that finds its end needs no
            // larger array; a file that grew meanwhile grows the array up to the limit.
            val size = stat.st_size
            var buffer = ByteArray(if (size in 0 until limit) size.toInt() + 1 else minOf(limit, START_BYTES))
            var count = 0
            while (count < limit) {
                if (count == buffer.size) buffer = buffer.copyOf(minOf(limit.toLong(), 2L * buffer.size).toInt())
                val n = errno("read") { Os.read(fd, buffer, count, buffer.size - count) }
                if (n <= 0) break // the end of the file
                count += n
            }
            return if (count == buffer.size) buffer else buffer.copyOf(count)
        } finally {
            closeQuietly(fd) // nothing was written: a failed close loses nothing
        }
    }

    /** open([flags], 0600), every byte written, fsync, close; a failed close is a failed write. */
    private fun writeFile(path: String, bytes: ByteArray, flags: Int) {
        val fd = errno("open") { Os.open(path, flags or cloexec(), FILE_MODE) }
        try {
            var offset = 0
            while (offset < bytes.size) {
                val n = errno("write") { Os.write(fd, bytes, offset, bytes.size - offset) }
                if (n <= 0) throw IOException("write failed: nothing written")
                offset += n
            }
            errno("fsync") { Os.fsync(fd) }
        } catch (t: Throwable) {
            closeQuietly(fd)
            throw t
        }
        errno("close") { Os.close(fd) }
    }

    /** Makes [dir] and every missing parent, each one synced into its parent so that it survives a power cut. */
    private fun makeDirs(dir: String) {
        val mode = try {
            Os.lstat(dir).st_mode
        } catch (e: ErrnoException) {
            if (e.errno != OsConstants.ENOENT) throw ioException("lstat", e)
            null
        }
        if (mode != null) {
            if (!OsConstants.S_ISDIR(mode)) throw IOException("mkdir failed: not a directory")
            return
        }
        val parent = parentOf(dir) ?: throw IOException("mkdir failed: no parent")
        makeDirs(parent)
        try {
            Os.mkdir(dir, DIR_MODE)
        } catch (e: ErrnoException) {
            // Made meanwhile: the open of the temporary file finds out whether it is a directory.
            if (e.errno != OsConstants.EEXIST) throw ioException("mkdir", e)
        }
        syncDir(parent)
    }

    private fun removeTree(dir: String) {
        val names = File(dir).list() ?: throw IOException("deleteTree failed: a directory cannot be read")
        for (name in names) {
            val child = "$dir/$name"
            // lstat: a symbolic link is removed itself, never followed.
            if (OsConstants.S_ISDIR(errno("lstat") { Os.lstat(child) }.st_mode)) removeTree(child) else errno("remove") { Os.remove(child) }
        }
        errno("remove") { Os.remove(dir) }
    }

    private fun isDirectory(path: String): Boolean = try {
        OsConstants.S_ISDIR(Os.lstat(path).st_mode)
    } catch (e: ErrnoException) {
        if (e.errno == OsConstants.ENOENT) false else throw ioException("lstat", e)
    }

    private fun removeQuietly(path: String) {
        try {
            Os.remove(path)
        } catch (_: ErrnoException) {
            // best effort: a stale temporary file is deleted at the next start (lock.md §5.9)
        }
    }

    private fun closeQuietly(fd: FileDescriptor) {
        try {
            Os.close(fd)
        } catch (_: ErrnoException) {
            // the failure that led here is the one reported
        }
    }

    private fun recordPath(dir: String, name: String): String {
        checkPath(dir)
        require(name.isNotEmpty() && !name.contains('/') && !name.contains('\u0000') && name != "." && name != "..") {
            "not a plain file name"
        }
        return "$dir/$name"
    }

    private companion object {
        const val TMP = ".tmp"

        /** The largest record file of lock.md §5.3: magic, length, 65,536 bytes of JSON, SHA-256. */
        const val MAX_RECORD_FILE = 8 + 4 + 65_536 + 32

        /** The first array for a file whose size `fstat` did not tell. */
        const val START_BYTES = 8_192

        /** rw------- */
        val FILE_MODE = OsConstants.S_IRUSR or OsConstants.S_IWUSR

        /** rwx------ */
        val DIR_MODE = OsConstants.S_IRWXU

        /** `O_CLOEXEC` from API 27, where `OsConstants` has it; the app starts no other program anyway. */
        fun cloexec(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) OsConstants.O_CLOEXEC else 0

        /** The same paths as `FakeFiles`: absolute, `/`, no empty, `.` or `..` part. */
        fun checkPath(path: String) {
            if (!path.startsWith("/")) throw IOException("not an absolute path")
            if (path == "/") return
            if (path.substring(1).split('/').any { it.isEmpty() || it == "." || it == ".." || '\u0000' in it }) {
                throw IOException("not a plain path")
            }
        }

        fun parentOf(path: String): String? {
            if (path == "/") return null
            val cut = path.lastIndexOf('/')
            return if (cut <= 0) "/" else path.substring(0, cut)
        }

        inline fun <T> errno(call: String, block: () -> T): T = try {
            block()
        } catch (e: ErrnoException) {
            throw ioException(call, e)
        }

        /** The errno as `IOException`, `FileNotFoundException` for ENOENT; the message has no path. */
        fun ioException(call: String, e: ErrnoException): IOException {
            val message = "$call failed: " + (OsConstants.errnoName(e.errno) ?: "errno ${e.errno}")
            return if (e.errno == OsConstants.ENOENT) FileNotFoundException(message).apply { initCause(e) } else IOException(message, e)
        }
    }
}
