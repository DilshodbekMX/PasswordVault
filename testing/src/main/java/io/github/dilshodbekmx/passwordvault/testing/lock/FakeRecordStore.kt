package io.github.dilshodbekmx.passwordvault.testing.lock

import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordStore
import java.io.IOException

/**
 * One lock record of one vault behind [RecordStore], in memory, for the tests of the lock's guards
 * and flows (lock.md §15.1; architecture.md §2.1). It works on whole records; the fake of the files
 * underneath, byte for byte, is `FakeRecordFiles` (package 2.10).
 *
 * **Handed over and durable are kept apart.** A write that returns is handed over: [read] gives it
 * at once, and a [kill] keeps it (a kill changes nothing on disk). A [powerCut] keeps only what was
 * handed over at least `settleMs` before it, the latest such record, which models a drive cache that
 * fsync does not flush (lock.md §6.7, [V-S8]); every later write is lost. Writes are stamped with
 * [now], a clock that never goes back. `FakeClock::elapsedMs` is one as long as the test does not
 * reboot it; a test that reboots passes a clock of its own that keeps counting.
 *
 * **It can fail.** [failNextWrite] makes the next write throw `IOException`: before anything was
 * handed over, or after (`landed = true`), as a failed directory fsync does after the rename
 * (lock.md §5.9, [V-S2]); either way the caller must treat the record as not written. [full] makes
 * every write fail (storage is full). [stopAtNextWrite] ends the "process" inside the next write by
 * throwing [Stopped], an `Error`, so that no `catch (e: IOException)` of the code under test stops
 * it. [readAs] makes [read] give one of the other results of lock.md §5.3 (DAMAGED, NEWER,
 * UNREADABLE) whatever is stored.
 *
 * **It keeps a record.** [writes] lists every record handed over, with its time; [failedWrites]
 * counts the writes that threw; [reads] counts the reads.
 *
 * The records are kept as given, not copied: the lock's records are immutable values.
 */
class FakeRecordStore<T : Any>(initial: T? = null, private val now: () -> Long = { 0L }) : RecordStore<T> {

    /** One record handed over at [atMs]; [failed] when the write that handed it over threw all the same. */
    class Write<T>(val atMs: Long, val record: T, val failed: Boolean) {
        override fun toString(): String = "Write(at=$atMs, failed=$failed, $record)"
    }

    /** Thrown inside the write chosen with [stopAtNextWrite]: the process ended there. */
    class Stopped(val handedOver: Boolean) : Error("the process stopped inside a record write")

    private class Fault(val landed: Boolean)

    private var base: T? = initial
    private val unsettled = ArrayList<Write<T>>()
    private val handed = ArrayList<Write<T>>()
    private val faults = ArrayDeque<Fault>()
    private var stop: Boolean? = null
    private var failed = 0
    private var readCount = 0

    /** Every write fails before anything is handed over, as when the phone's storage is full. */
    @Volatile var full: Boolean = false

    /** While set, [read] gives this instead of what is stored (lock.md §5.3); null: the stored record, or MISSING. */
    @Volatile var readAs: RecordRead<T>? = null

    /** What [read] gives now, unless [readAs] is set: the record last handed over, or null for MISSING. */
    val current: T? @Synchronized get() = unsettled.lastOrNull()?.record ?: base

    /** Every record handed over since the store was made, in order. */
    val writes: List<Write<T>> @Synchronized get() = handed.toList()

    /** How many writes threw `IOException` (landed or not). */
    val failedWrites: Int @Synchronized get() = failed

    /** How many times [read] was called. */
    val reads: Int @Synchronized get() = readCount

    @Synchronized
    override fun read(): RecordRead<T> {
        readCount++
        readAs?.let { return it }
        val record = current ?: return RecordRead.Missing
        return RecordRead.Ok(record)
    }

    @Synchronized
    override fun write(record: T) {
        stop?.let { handOver ->
            stop = null
            if (handOver) handOver(record, failed = false)
            throw Stopped(handOver)
        }
        if (full) {
            failed++
            throw IOException("no space left on the device")
        }
        val fault = faults.removeFirstOrNull()
        if (fault != null) {
            failed++
            if (fault.landed) handOver(record, failed = true)
            throw IOException("the record could not be written")
        }
        handOver(record, failed = false)
    }

    private fun handOver(record: T, failed: Boolean) {
        val write = Write(now(), record, failed)
        unsettled += write
        handed += write
    }

    /** The next write throws `IOException`; with [landed] its record is handed over all the same. Calls queue up. */
    @Synchronized
    fun failNextWrite(landed: Boolean = false) {
        faults.addLast(Fault(landed))
    }

    /** The next write throws [Stopped]: after its record was handed over when [handedOver], before otherwise. */
    @Synchronized
    fun stopAtNextWrite(handedOver: Boolean) {
        stop = handedOver
    }

    /** The process dies: everything handed over stays, and pending faults and stops are dropped. */
    @Synchronized
    fun kill() {
        faults.clear()
        stop = null
    }

    /**
     * The power goes: only what was handed over at least [settleMs] before [now] survives (the latest
     * such record, or what was there before every one of them). Pending faults and stops are dropped.
     */
    @Synchronized
    fun powerCut(settleMs: Long) {
        require(settleMs >= 0) { "negative settle time" }
        val cutoff = now() - settleMs
        val survivor = unsettled.lastOrNull { it.atMs <= cutoff }
        if (survivor != null) base = survivor.record
        unsettled.clear()
        faults.clear()
        stop = null
    }

    /** Puts [record] on disk as if it had always been there (null: no file), durable at once. */
    @Synchronized
    fun set(record: T?) {
        base = record
        unsettled.clear()
    }
}
