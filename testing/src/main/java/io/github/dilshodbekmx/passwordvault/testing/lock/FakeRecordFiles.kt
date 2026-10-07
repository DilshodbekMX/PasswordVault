package io.github.dilshodbekmx.passwordvault.testing.lock

import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordFiles
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import java.io.IOException
import java.util.TreeMap
import java.util.TreeSet

/**
 * The lock directories in memory, behind [RecordFiles], for the lock's tests (lock.md §15.1;
 * architecture.md §2.1).
 *
 * **Handed over and durable are kept apart.** What the code under test sees is what it handed over:
 * every write, rename and delete shows at once. What survives a power cut is what was handed over
 * at least a "settle" period earlier, the model of lock.md §6.7 and appendix B: a drive cache that
 * fsync does not flush [V-S8]. [powerCut] takes the settle period; with 0 it keeps everything that
 * was handed over. Every change is stamped with [Clock.elapsedMs] of the [clock] the code under test
 * uses, so "the charge is 1,000 ms old" and "1,000 ms have settled" are measured on the same clock.
 * [kill] keeps everything handed over (a later power cut may still lose the newest writes, which
 * the drive has not settled); [reboot] is a clean shutdown and start, after which everything handed
 * over is durable. Call [powerCut] or [reboot] here before rebooting a `FakeClock`; if the clock
 * goes back anyway, what was handed over before counts as durable.
 *
 * **A write has four boundaries** (lock.md T14): before the temp write, after the file sync, after
 * the rename, after the directory sync. [RecordFiles.deleteDir] and [RecordFiles.deleteStaleTmp]
 * have two each, before and after. [stopAt] makes the n-th boundary from now throw [Stopped] (an
 * `Error`, so that no `catch (e: Exception)` in the code under test stops it). From then on the
 * process is dead: every call throws [Stopped], so that a `finally` block of the code under test
 * cannot write after its death, until the test restarts it with [kill], [powerCut] or [reboot].
 * Reads and listings are not boundaries.
 *
 * **It can fail.** [failNextWrite] makes the next write fail at a chosen step: at the temp write
 * (half of the bytes reach a `.tmp` file, as when the disk fills up; the record is unchanged), at
 * the file sync or the rename (a full `.tmp` file is left, the record is unchanged), or at the
 * directory sync (the record is already replaced, and the call throws all the same: lock.md §5.9
 * calls that "not written"). [fail] makes any call fail, a record's reads among them. Faults last
 * across restarts, as a full disk does, until they are used up or cleared.
 *
 * **`replace` makes the directory it writes into**, with its parents, when it is missing: the
 * first record of a new vault (lock.md F1 step 4) needs no other call.
 *
 * Paths are absolute, with `/`, without a trailing `/` and without an empty, `.` or `..` part; a
 * name has no `/`. Anything else throws `IOException`. [RecordFiles.dirs] lists in an order that is
 * neither sorted nor the order of creation, so that code relying on an order fails here too.
 *
 * @param clock the clock of the code under test, for the settle periods.
 * @param horizonMs the longest settle period [powerCut] is asked for; older history is folded into
 *   the durable state, so that a long run does not keep every write.
 */
class FakeRecordFiles(
    private val clock: Clock,
    private val horizonMs: Long = 60_000L,
) : RecordFiles {
    /** The calls of [RecordFiles], for [fail] and [log]. */
    enum class Op { READ, REPLACE, DIRS, DELETE_DIR, DELETE_STALE_TMP }

    /** The steps of a write (lock.md §5.9), for [failNextWrite]. */
    enum class WriteStep { TEMP_WRITE, FILE_SYNC, RENAME, DIR_SYNC }

    /** Thrown at the boundary chosen with [stopAt], and by every call after it until a restart: the process ended there. */
    class Stopped(val boundary: Int, val at: String) : Error("stopped at boundary $boundary: $at")

    /** One state of the files: file path to bytes, and the directories. Never changed once made. */
    private class Disk(val files: TreeMap<String, ByteArray>, val dirs: TreeSet<String>) {
        fun edit(change: (TreeMap<String, ByteArray>, TreeSet<String>) -> Unit): Disk {
            val f = TreeMap(files)
            val d = TreeSet(dirs)
            change(f, d)
            return Disk(f, d)
        }
    }

    /** A state that was handed over at [elapsedMs]. */
    private class Point(val elapsedMs: Long, val disk: Disk)

    private class Fault(val op: Op, val step: WriteStep, val matches: (String) -> Boolean, var times: Int)

    private var visible = Disk(TreeMap(), TreeSet())
    private var durable = visible
    private val history = ArrayList<Point>()
    private val faults = mutableListOf<Fault>()
    private val calls = mutableListOf<String>()
    private var passed = 0
    private var stopAtBoundary = 0
    private var dead: Stopped? = null

    init {
        require(horizonMs >= 0) { "a negative horizon" }
    }

    /** Called at every boundary, before a stop: its index (from 1) and what is about to happen or has just happened. */
    @Volatile var onBoundary: ((index: Int, at: String) -> Unit)? = null

    /** Every call so far, such as `replace /lock/<id>/pin.rec`, in order; calls of a dead process are not listed. */
    val log: List<String> @Synchronized get() = calls.toList()

    /** The number of boundaries passed so far. */
    val boundaries: Int @Synchronized get() = passed

    /** False after a stop, until [kill], [powerCut] or [reboot]. */
    val alive: Boolean @Synchronized get() = dead == null

    // ---------------------------------------------------------------------------------------------
    // RecordFiles

    @Synchronized
    override fun read(dir: String, name: String): ByteArray? {
        val path = file(dir, name)
        enter(Op.READ, "read $path", path)
        return visible.files[path]?.copyOf()
    }

    @Synchronized
    override fun replace(dir: String, name: String, bytes: ByteArray) {
        val target = file(dir, name)
        val tmp = "$target.tmp"
        val fault = enter(Op.REPLACE, "replace $target", target, failNow = false)
        val data = bytes.copyOf()
        boundary("replace $target: before the temp write")
        if (fault == WriteStep.TEMP_WRITE) {
            change { files, dirs ->
                mkdirs(dirs, dir)
                files[tmp] = data.copyOf(data.size / 2)
            }
            throw IOException("injected failure: no space left on the device")
        }
        change { files, dirs ->
            mkdirs(dirs, dir)
            files[tmp] = data
        }
        if (fault == WriteStep.FILE_SYNC) throw IOException("injected failure: fsync")
        boundary("replace $target: after the file sync")
        if (fault == WriteStep.RENAME) throw IOException("injected failure: rename")
        change { files, _ ->
            files.remove(tmp)
            files[target] = data
        }
        boundary("replace $target: after the rename")
        if (fault == WriteStep.DIR_SYNC) throw IOException("injected failure: directory fsync")
        boundary("replace $target: after the directory sync")
    }

    @Synchronized
    override fun dirs(root: String): List<String> {
        val base = path(root)
        enter(Op.DIRS, "dirs $base", base)
        val prefix = "$base/"
        return visible.dirs
            .filter { it.startsWith(prefix) && it.indexOf('/', prefix.length) < 0 }
            .sortedWith(compareBy<String> { it.hashCode() }.thenBy { it })
    }

    @Synchronized
    override fun deleteDir(dir: String) {
        val base = path(dir)
        enter(Op.DELETE_DIR, "deleteDir $base", base)
        boundary("deleteDir $base: before")
        val prefix = "$base/"
        if (base in visible.dirs || visible.files.keys.any { it.startsWith(prefix) }) {
            change { files, dirs ->
                files.keys.removeAll { it.startsWith(prefix) }
                dirs.removeAll { it == base || it.startsWith(prefix) }
            }
        }
        boundary("deleteDir $base: after")
    }

    @Synchronized
    override fun deleteStaleTmp(root: String) {
        val base = path(root)
        enter(Op.DELETE_STALE_TMP, "deleteStaleTmp $base", base)
        boundary("deleteStaleTmp $base: before")
        val prefix = "$base/"
        if (visible.files.keys.any { it.startsWith(prefix) && it.endsWith(".tmp") }) {
            change { files, _ -> files.keys.removeAll { it.startsWith(prefix) && it.endsWith(".tmp") } }
        }
        boundary("deleteStaleTmp $base: after")
    }

    // ---------------------------------------------------------------------------------------------
    // Stopping, failing and restarting

    /** The [boundaries]-th boundary from now stops the process. */
    @Synchronized
    fun stopAt(boundaries: Int) {
        require(boundaries >= 1) { "a boundary from now: 1 or more" }
        stopAtBoundary = passed + boundaries
    }

    /** No stop is armed any more. */
    @Synchronized
    fun clearStop() {
        stopAtBoundary = 0
    }

    /**
     * The next [times] calls of [op] on a path that [matches] accepts throw `IOException` (a
     * negative [times]: every such call, until [clearFaults]). The path is the record's for
     * [Op.READ] and [Op.REPLACE], the directory's for the others. A write fails at [step].
     */
    @Synchronized
    fun fail(op: Op, times: Int = 1, step: WriteStep = WriteStep.TEMP_WRITE, matches: (path: String) -> Boolean = { true }) {
        require(times != 0) { "times: 1 or more, or negative for always" }
        faults += Fault(op, step, matches, times)
    }

    /** The next write of any record fails at [step]. */
    fun failNextWrite(step: WriteStep = WriteStep.TEMP_WRITE) = fail(Op.REPLACE, 1, step)

    @Synchronized
    fun clearFaults() {
        faults.clear()
    }

    /** The process dies: everything handed over stays, as the next process will read it. */
    @Synchronized
    fun kill() {
        restart()
    }

    /**
     * The power goes, and with it the process: what was handed over less than [settleMs] ago is
     * lost (0: nothing is lost). Every file and directory is as it was at the last change at least
     * [settleMs] old.
     */
    @Synchronized
    fun powerCut(settleMs: Long) {
        require(settleMs in 0..horizonMs) { "a settle period from 0 to the horizon" }
        val now = clock.elapsedMs()
        fold(now)
        val cutoff = now - settleMs
        val survivor = history.lastOrNull { it.elapsedMs <= cutoff }?.disk ?: durable
        visible = survivor
        durable = survivor
        history.clear()
        restart()
    }

    /** A clean shutdown and start: everything handed over is durable. */
    @Synchronized
    fun reboot() {
        durable = visible
        history.clear()
        restart()
    }

    private fun restart() {
        dead = null
        stopAtBoundary = 0
    }

    // ---------------------------------------------------------------------------------------------
    // For setting up and looking (no boundary, no record)

    /** Puts a record at [dir]/[name] (its directories made too), durable, as an earlier process would have left it. */
    @Synchronized
    fun put(dir: String, name: String, bytes: ByteArray) {
        val path = file(dir, name)
        val data = bytes.copyOf()
        val add = { files: TreeMap<String, ByteArray>, dirs: TreeSet<String> ->
            mkdirs(dirs, dir)
            files[path] = data
        }
        visible = visible.edit(add)
        durable = durable.edit(add)
        for (i in history.indices) history[i] = Point(history[i].elapsedMs, history[i].disk.edit(add))
    }

    /** The bytes of [dir]/[name] as the code under test would read them now, or null. */
    @Synchronized
    fun bytes(dir: String, name: String): ByteArray? = visible.files[file(dir, name)]?.copyOf()

    /** The bytes of [dir]/[name] that a power cut now with [settleMs] would leave, or null. */
    @Synchronized
    fun durableBytes(dir: String, name: String, settleMs: Long): ByteArray? {
        require(settleMs in 0..horizonMs) { "a settle period from 0 to the horizon" }
        val cutoff = clock.elapsedMs() - settleMs
        val disk = history.lastOrNull { it.elapsedMs <= cutoff }?.disk ?: durable
        return disk.files[file(dir, name)]?.copyOf()
    }

    /** The names of the files in [dir], sorted; empty when it does not exist. */
    @Synchronized
    fun names(dir: String): List<String> {
        val prefix = path(dir) + "/"
        return visible.files.keys.filter { it.startsWith(prefix) && it.indexOf('/', prefix.length) < 0 }
            .map { it.substring(prefix.length) }
    }

    /** Whether the directory [dir] exists now. */
    @Synchronized
    fun hasDir(dir: String): Boolean = path(dir) in visible.dirs

    /** Damage on the disk itself: the bytes of [dir]/[name], durable ones included, become what [change] makes of them. */
    @Synchronized
    fun damage(dir: String, name: String, change: (ByteArray) -> ByteArray) {
        val path = file(dir, name)
        val current = visible.files[path] ?: throw IOException("no such file")
        val damaged = change(current.copyOf())
        val apply = { files: TreeMap<String, ByteArray>, _: TreeSet<String> -> if (path in files) files[path] = damaged }
        visible = visible.edit(apply)
        durable = durable.edit(apply)
        for (i in history.indices) history[i] = Point(history[i].elapsedMs, history[i].disk.edit(apply))
    }

    /** Every directory and file, sorted, with sizes: for a test's message. */
    @Synchronized
    fun tree(): String {
        val out = StringBuilder()
        for (dir in visible.dirs) out.append(dir).append("/\n")
        for ((path, bytes) in visible.files) out.append(path).append(" (").append(bytes.size).append(" bytes)\n")
        return out.toString()
    }

    /**
     * A copy with the same files, durable state and history, on [clock], with no record of calls, no
     * fault and no stop: a fresh start for each run of a crash test.
     */
    @Synchronized
    fun copy(clock: Clock = this.clock): FakeRecordFiles {
        val copy = FakeRecordFiles(clock, horizonMs)
        copy.visible = visible
        copy.durable = durable
        copy.history.addAll(history)
        return copy
    }

    // ---------------------------------------------------------------------------------------------

    /** Applies [edit] to a new state, which is now handed over. */
    private fun change(edit: (TreeMap<String, ByteArray>, TreeSet<String>) -> Unit) {
        val now = clock.elapsedMs()
        fold(now)
        visible = visible.edit(edit)
        history.add(Point(now, visible))
    }

    /**
     * Folds history that no settle period up to the horizon can reach into the durable state; all
     * of it when the clock has gone back (a reboot this fake was not told of).
     */
    private fun fold(now: Long) {
        if (history.isNotEmpty() && now < history.last().elapsedMs) {
            durable = history.last().disk
            history.clear()
            return
        }
        while (history.size > 1 && history[1].elapsedMs < now - horizonMs) history.removeAt(0)
        if (history.isNotEmpty() && history[0].elapsedMs < now - horizonMs) {
            durable = history.removeAt(0).disk
        }
    }

    /**
     * The start of every call: a dead process throws; then the call is logged and a matching fault
     * is used. Returns the step at which a write must fail, or null; other calls fail at once.
     */
    private fun enter(op: Op, at: String, path: String, failNow: Boolean = true): WriteStep? {
        dead?.let { throw Stopped(it.boundary, "after the stop: $at") }
        calls += at
        val fault = faults.firstOrNull { it.op == op && it.matches(path) } ?: return null
        if (fault.times > 0 && --fault.times == 0) faults.remove(fault)
        if (failNow) throw IOException("injected failure: $at")
        return fault.step
    }

    private fun boundary(at: String) {
        passed++
        onBoundary?.invoke(passed, at)
        if (passed == stopAtBoundary) {
            stopAtBoundary = 0
            val stop = Stopped(passed, at)
            dead = stop
            throw stop
        }
    }

    private fun mkdirs(dirs: TreeSet<String>, dir: String) {
        var at = path(dir)
        while (at.isNotEmpty()) {
            dirs.add(at)
            at = at.substring(0, at.lastIndexOf('/'))
        }
    }

    /** [dir] checked as an absolute path without a trailing `/`, empty, `.` or `..` part. */
    private fun path(dir: String): String {
        if (!dir.startsWith("/") || dir.length < 2 || dir.endsWith("/")) throw IOException("not a plain absolute path")
        val parts = dir.substring(1).split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) throw IOException("not a plain absolute path")
        return dir
    }

    private fun file(dir: String, name: String): String {
        if (name.isEmpty() || '/' in name || name == "." || name == "..") throw IOException("not a plain file name")
        return path(dir) + "/" + name
    }
}
