package com.example.pvlab.clip

import android.content.Context
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Random

/** Experiment 2: durable atomic write in app-private storage. Logs one JSON object per line under PVIo. */
object IoTest {
    const val TAG = "PVIo"

    private fun log(ev: String, vararg kv: Pair<String, Any?>) {
        val o = JSONObject()
        o.put("ev", ev)
        for ((k, v) in kv) o.put(k, v ?: JSONObject.NULL)
        Log.i(TAG, o.toString())
    }

    /** Duration of each step of one commit, in nanoseconds. */
    class Steps(
        val open: Long, val write: Long, val fdSync: Long, val close: Long, val rename: Long, val dirFsync: Long,
    ) {
        val total: Long get() = open + write + fdSync + close + rename + dirFsync
    }

    /**
     * The planned save, instrumented: write NAME.tmp, flush, FileOutputStream.fd.sync(), close,
     * Os.rename(tmp, dst) (dst must not exist yet), then open the directory O_RDONLY, Os.fsync, close.
     */
    fun commit(dir: File, name: String, bytes: ByteArray): Steps {
        val tmp = File(dir, "$name.tmp")
        val dst = File(dir, name)
        val t0 = System.nanoTime()
        val out = FileOutputStream(tmp) // creates or truncates
        val t1 = System.nanoTime()
        var t2 = 0L
        var t3 = 0L
        try {
            out.write(bytes)
            out.flush()
            t2 = System.nanoTime()
            out.fd.sync()
            t3 = System.nanoTime()
        } catch (e: Throwable) {
            runCatching { out.close() }.exceptionOrNull()?.let { e.addSuppressed(it) }
            throw e
        }
        out.close()
        val t4 = System.nanoTime()
        Os.rename(tmp.path, dst.path)
        val t5 = System.nanoTime()
        fsyncDir(dir)
        val t6 = System.nanoTime()
        return Steps(t1 - t0, t2 - t1, t3 - t2, t4 - t3, t5 - t4, t6 - t5)
    }

    fun fsyncDir(dir: File, flags: Int = OsConstants.O_RDONLY) {
        val fd = Os.open(dir.path, flags, 0)
        try {
            Os.fsync(fd)
        } finally {
            Os.close(fd)
        }
    }

    private fun median(values: List<Long>): Double {
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2].toDouble() else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    private fun isIo(t: Throwable) = t is IOException

    private inline fun expectFailure(name: String, block: () -> Unit) {
        try {
            block()
            log("failure_test", "name" to name, "outcome" to "NO EXCEPTION")
        } catch (e: ErrnoException) {
            log(
                "failure_test", "name" to name, "outcome" to "threw", "type" to e.javaClass.name,
                "errno" to OsConstants.errnoName(e.errno), "msg" to e.message, "isIOException" to isIo(e),
            )
        } catch (e: Throwable) {
            log(
                "failure_test", "name" to name, "outcome" to "threw", "type" to e.javaClass.name,
                "msg" to e.message, "cause" to e.cause?.toString(), "isIOException" to isIo(e),
            )
        }
    }

    fun bench(dir: File, label: String, iterations: Int = 20) {
        val rnd = Random(42)
        for (size in intArrayOf(100 * 1024, 1024 * 1024, 5 * 1024 * 1024)) {
            val data = ByteArray(size).also { rnd.nextBytes(it) }
            val steps = ArrayList<Steps>()
            var verified = 0
            var leftovers = 0
            for (i in 0 until iterations) {
                val name = "bench-$size-$i.bin"
                val dst = File(dir, name)
                if (dst.exists()) dst.delete()
                steps += commit(dir, name, data)
                if (dst.readBytes().contentEquals(data)) verified++
                if (File(dir, "$name.tmp").exists()) leftovers++
                dst.delete()
            }
            fun ms(sel: (Steps) -> Long) = "%.2f".format(median(steps.map(sel)) / 1e6)
            log(
                "bench", "dir" to label, "sizeBytes" to size, "n" to iterations, "verified" to verified,
                "tmpLeftovers" to leftovers,
                "medianTotalMs" to ms { it.total },
                "minTotalMs" to "%.2f".format(steps.minOf { it.total } / 1e6),
                "maxTotalMs" to "%.2f".format(steps.maxOf { it.total } / 1e6),
                "medianOpenMs" to ms { it.open }, "medianWriteMs" to ms { it.write },
                "medianFdSyncMs" to ms { it.fdSync }, "medianCloseMs" to ms { it.close },
                "medianRenameMs" to ms { it.rename }, "medianDirFsyncMs" to ms { it.dirFsync },
                "allTotalsMs" to steps.joinToString(" ") { "%.1f".format(it.total / 1e6) },
            )
        }
    }

    fun failures(dir: File, label: String) {
        val data = ByteArray(4096) { it.toByte() }

        // F1: rename to a path in a directory that does not exist
        val tmp1 = File(dir, "f1.bin.tmp").also { it.writeBytes(data) }
        expectFailure("$label F1a Os.rename(tmp, missing-dir/f1.bin)") {
            Os.rename(tmp1.path, File(dir, "missing-dir/f1.bin").path)
        }
        expectFailure("$label F1b commit() with dir = missing-dir") { commit(File(dir, "missing-dir"), "f1.bin", data) }
        expectFailure("$label F1c java.io.File.renameTo into missing-dir (for comparison)") {
            if (!tmp1.renameTo(File(dir, "missing-dir/f1.bin"))) {
                throw IllegalStateException("File.renameTo returned false; it does not throw")
            }
        }
        log("f1_tmp_still_there", "exists" to tmp1.exists())
        tmp1.delete()

        // F2: directory made read-only (mode 0500) while the app owns it
        val ro = File(dir, "ro-dir").also { it.mkdirs() }
        val preTmp = File(ro, "pre.bin.tmp").also { it.writeBytes(data) } // created while writable
        val openedBefore = FileOutputStream(File(ro, "open.bin.tmp")) // opened while writable
        Os.chmod(ro.path, "500".toInt(8))
        log("f2_mode", "mode" to Integer.toOctalString(Os.stat(ro.path).st_mode and "7777".toInt(8)))
        expectFailure("$label F2a commit() into read-only dir") { commit(ro, "f2.bin", data) }
        expectFailure("$label F2b Os.rename inside read-only dir (tmp created before chmod)") {
            Os.rename(preTmp.path, File(ro, "pre.bin").path)
        }
        expectFailure("$label F2c Os.rename from writable dir into read-only dir") {
            val t = File(dir, "f2c.bin.tmp").also { it.writeBytes(data) }
            try {
                Os.rename(t.path, File(ro, "f2c.bin").path)
            } finally {
                t.delete()
            }
        }
        expectFailure("$label F2d write+fd.sync on a file opened before chmod (expected to succeed)") {
            openedBefore.write(data)
            openedBefore.flush()
            openedBefore.fd.sync()
        }
        openedBefore.close()
        expectFailure("$label F2e fsync on the read-only dir itself (expected to succeed)") { fsyncDir(ro) }
        Os.chmod(ro.path, "700".toInt(8))
        ro.deleteRecursively()

        // F3: fsync on a closed descriptor
        val f3 = File(dir, "f3.bin.tmp")
        val out = FileOutputStream(f3)
        val fd = out.fd
        out.write(data)
        out.close()
        expectFailure("$label F3a FileOutputStream.fd.sync() after close") { fd.sync() }
        expectFailure("$label F3b Os.fsync(FileDescriptor of closed stream)") { Os.fsync(fd) }
        val dfd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
        Os.close(dfd)
        expectFailure("$label F3c Os.fsync(dir fd after Os.close)") { Os.fsync(dfd) }
        expectFailure("$label F3d Os.close(dir fd a second time)") { Os.close(dfd) }
        f3.delete()

        // Reference (not the planned path): rename over an existing dst replaces it atomically.
        val a = File(dir, "replace.bin").also { it.writeBytes(byteArrayOf(1)) }
        val b = File(dir, "replace.bin.tmp").also { it.writeBytes(byteArrayOf(2, 2)) }
        expectFailure("$label R1 Os.rename over existing dst (expected to succeed)") {
            Os.rename(b.path, a.path)
            fsyncDir(dir)
        }
        log("r1_result", "dstBytes" to a.readBytes().joinToString(","), "tmpExists" to b.exists())
        a.delete()
    }

    fun runAll(ctx: Context) {
        log("io_start", "sdk" to Build.VERSION.SDK_INT, "device" to Build.FINGERPRINT)
        for ((label, base) in listOf("filesDir" to ctx.filesDir, "noBackupFilesDir" to ctx.noBackupFilesDir)) {
            val dir = File(base, "vault")
            try {
                if (!dir.isDirectory && !dir.mkdirs()) throw IOException("mkdirs failed: $dir")
                val st = Os.statvfs(dir.path)
                log(
                    "dir", "label" to label, "path" to dir.path, "canonical" to dir.canonicalPath,
                    "f_bsize" to st.f_bsize, "f_fsid" to st.f_fsid, "f_flag" to st.f_flag, "f_namemax" to st.f_namemax,
                )
                // One commit with every step checked; any exception lands in io_error below.
                val s = commit(dir, "first.bin", ByteArray(100 * 1024) { 7 })
                log(
                    "first_commit_ok", "dir" to label, "totalMs" to "%.2f".format(s.total / 1e6),
                    "dirFsyncMs" to "%.2f".format(s.dirFsync / 1e6),
                    "dstExists" to File(dir, "first.bin").exists(), "tmpExists" to File(dir, "first.bin.tmp").exists(),
                )
                File(dir, "first.bin").delete()
                expectFailure("$label V1 fsync dir opened O_RDONLY|O_CLOEXEC (expected to succeed)") {
                    fsyncDir(dir, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC)
                }
                bench(dir, label)
                failures(dir, label)
            } catch (t: Throwable) {
                log("io_error", "dir" to label, "ex" to t.toString(), "stack" to Log.getStackTraceString(t))
            }
        }
        log("io_done")
    }
}
