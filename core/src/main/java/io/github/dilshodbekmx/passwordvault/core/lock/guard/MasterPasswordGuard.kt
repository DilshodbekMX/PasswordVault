package io.github.dilshodbekmx.passwordvault.core.lock.guard

import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordStore
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.core.util.Uuid
import java.io.IOException

/**
 * What checking a typed master password came to, as the lock engine judges it from storage's answer
 * (lock.md §6.8 steps 3, 4, 6 and 7). The guard counts by it.
 */
enum class MpCheck {
    /**
     * A password slot of this vault opened (step 5): the count of wrong tries starts again. A slot
     * that opened while no generation could be used is not wrong either (step 7: "wrong only when
     * no slot opened"); the engine decides whether such a try is [RIGHT] or [NOT_COUNTED].
     */
    RIGHT,

    /** No password slot opened, or only the slot of an older copy (steps 6 and 7): the try stays counted. */
    WRONG,

    /**
     * The password was not checked to the end: not enough memory for Argon2id (step 3, "not a wrong
     * password and not counted"), or it opened the pending wrap, which is F11 step 14 and not step
     * 5. The try is given back.
     */
    NOT_COUNTED,
}

/** What `MasterPasswordGuard.attempt` came to. */
sealed interface MpAttempt {
    /**
     * Step 1: wrong tries in a row have reached [MasterPasswordGuard.FREE_TRIES], and the last one
     * is less than 30 seconds of uptime ago. Nothing was tried or counted; the field stays disabled
     * and counts down to `elapsedRealtime()` = [untilElapsedMs] (`LockScreen.MasterPassword.waitUntilElapsedMs`).
     */
    data class Wait(val untilElapsedMs: Long) : MpAttempt

    /**
     * The password was tried and counted by [check]. [tooLate]: the visit or prompt it belonged to
     * ended meanwhile, so it opens nothing (step 8, I11), although the records were updated as
     * usual. [pendingCancelled]: a right password deleted a pending new master password (F11 step
     * 9), and the notice "You typed your master password, so the new one was cancelled." follows.
     */
    data class Checked(val check: MpCheck, val tooLate: Boolean, val pendingCancelled: Boolean) : MpAttempt
}

/**
 * The guard of the master password of one vault: lock.md §6.8, over that vault's `state.rec`
 * (`mp_guard`, and what every correct master password brings).
 *
 * There is never a lockout (requirement 1, I10). From the sixth try after five wrong ones in a row
 * each try waits until 30 seconds of uptime have passed since the last one, never more; after a
 * reboot only the time since boot counts, so a reboot cannot skip the wait and no stored clock value
 * can be forged. Each try is counted before Argon2id runs (`failures + 1`, `last_fail_elapsed_ms`,
 * `unseen + 1`), so killing the app during the derivation does not give a free try; a try that
 * turns out right, or was not checked, takes its count back.
 *
 * **One guard per vault**, for the life of the process (lock.md §6.8): the current vault on its lock
 * screen and in the prompts for backup, export, change and settings; a vault put aside on its own
 * lock screen and in "Old passwords", with its own `state.rec` (F17), so guesses at it cost what
 * guesses at the current one cost. A file from outside, a backup being restored or imported, is
 * never guarded: [forOutsideFile] makes the guard for it, which never waits and never counts
 * (§6.8, F13 step 2).
 *
 * **What it holds.** The counts ([counts]) always, in memory; when the counts cannot be written,
 * they are kept here for this process ("best effort", step 2). The record it means to have on disk
 * ([record]), of which the other flows change the other fields through [update], so that every
 * write stores the whole record. A `state.rec` that read MISSING or DAMAGED is not written until the
 * engine makes a new one after the next successful unlock ([establish], lock.md §5.3); one that read
 * NEWER or UNREADABLE is not used and never rewritten: the wait runs from memory.
 *
 * **What a right password brings** (step 5) to this record: `failures = 0`; `unseen` loses only
 * this password's own slips, the wrong ones typed on the same visit in the 60 seconds before it, at
 * most two (§13.4 item 4); practice is updated (§8.3); a pending replacement is cancelled (F11 step
 * 9); `mp_change = null`. Its part in `pin.rec` (`row = 0`, `total = 0`) is `PinGuard.masterPasswordCorrect`,
 * which the engine calls with it; so are the wraps kept by §5.4 and the `MasterProof`.
 *
 * Called by the lock engine under the lock mutex (architecture.md §5.2); not thread-safe by itself.
 * It does not derive: the check passed to [attempt] does, through `VaultPort.openWithPassword`, one
 * Argon2id at a time in the process (`GatedMasterKdf`).
 */
class MasterPasswordGuard private constructor(private val vault: Guarded?, private val clock: Clock?) {

    /** The vault this guard counts for, over its `state.rec`. */
    private class Guarded(val vaultId: String, val store: RecordStore<StateRecord>)

    private class WrongAt(val place: Place, val atElapsedMs: Long)

    /**
     * The guard of the vault [vaultId], over its `state.rec` ([store]) and the clocks. Call [load]
     * before the first try.
     */
    constructor(vaultId: String, store: RecordStore<StateRecord>, clock: Clock) : this(Guarded(vaultId, store), clock) {
        require(Uuid.isValid(vaultId)) { "not a lower-case UUID of version 4" }
    }

    private var read: RecordRead<StateRecord> = RecordRead.Missing
    private var rec: StateRecord? = null
    private var writable = false
    private var tries = NONE
    private val visitWrong = ArrayList<WrongAt>()

    /** The vault whose guard this is; null for [forOutsideFile]. */
    val vaultId: String? get() = vault?.vaultId

    /** True for the guard of a file from outside, which never waits and never counts. */
    val isOutsideFile: Boolean get() = vault == null

    /** What reading `state.rec` gave at [load], or the record a later [establish] wrote. */
    val lastRead: RecordRead<StateRecord> get() = read

    /** `mp_guard` as it stands now, in memory: the wait and the notice of §13.4 item 4 use it. */
    val counts: StateRecord.MpGuard get() = tries

    /** The record this guard means to have on disk, with the counts of now; null while there is none it may use. */
    val record: StateRecord? get() = rec?.copy(mpGuard = tries)

    /**
     * Reads `state.rec`, once per process (or when a vault put aside shows its lock screen for the
     * first time). OK: the counts and the record come from it. MISSING and DAMAGED: the counts start
     * at zero, and nothing is written until [establish]. NEWER and UNREADABLE: the same, and the
     * engine does not establish a new record either (lock.md §5.3).
     */
    fun load(): RecordRead<StateRecord> {
        val guarded = vault ?: return RecordRead.Missing
        visitWrong.clear()
        val result = try {
            guarded.store.read()
        } catch (e: IOException) {
            RecordRead.Unreadable
        }
        read = result
        if (result is RecordRead.Ok) {
            rec = result.record
            tries = result.record.mpGuard
            writable = true
        } else {
            rec = null
            tries = NONE
            writable = false
        }
        return result
    }

    /**
     * Step 1: until when the master-password field stays disabled, as `elapsedRealtime()`; null when
     * the next try may start now. Wrong tries in a row below [FREE_TRIES]: no wait. Otherwise
     * `since = now − last` (or, when `now < last`, the uptime since a reboot, `since = now`), and the
     * wait lasts while `since` is below [WAIT_MS]: never more than 30 seconds.
     */
    fun waitUntilElapsedMs(): Long? {
        if (vault == null || tries.failures < FREE_TRIES) return null
        val now = clock!!.elapsedMs()
        val last = tries.lastFailElapsedMs
        val until = if (last != null && now >= last) last + WAIT_MS else WAIT_MS
        return if (now < until) until else null
    }

    /**
     * One try of a typed master password at [at], lock.md §6.8:
     *
     * 1. If a wait is due: [MpAttempt.Wait], and [check] is not called.
     * 2. The try is counted and written (best effort; the counts stay in memory when the write fails,
     *    and the try runs all the same).
     * 3. [check] derives and tries the slots (steps 3 and 4) and says what came of it.
     * 4. [MpCheck.RIGHT]: step 5 (see the class). [MpCheck.WRONG]: it stays counted; on a lock
     *    screen it is remembered for the visit, for forgiving in `unseen`. [MpCheck.NOT_COUNTED]: the
     *    counts are as before the try, and written so.
     * 5. If [at] has ended meanwhile, the records are updated all the same and the result says
     *    [MpAttempt.Checked.tooLate]: the engine drops the key and stays locked (step 8, I11).
     *
     * If [check] throws, the try stays counted, which is the safe direction, and the exception goes
     * on. For [forOutsideFile] only [check] runs.
     */
    suspend fun attempt(at: Place, check: suspend () -> MpCheck): MpAttempt {
        if (vault == null) return MpAttempt.Checked(check(), at.hasEnded, pendingCancelled = false)
        waitUntilElapsedMs()?.let { return MpAttempt.Wait(it) }

        val before = tries
        val startedAt = clock!!.elapsedMs()
        tries = StateRecord.MpGuard(failures = before.failures + 1, lastFailElapsedMs = startedAt, unseen = before.unseen + 1)
        writeQuietly()

        val result = check()
        val tooLate = at.hasEnded
        var pendingCancelled = false
        when (result) {
            MpCheck.WRONG -> if (at.isVisit) visitWrong += WrongAt(at, startedAt)
            MpCheck.NOT_COUNTED -> {
                tries = before
                writeQuietly()
            }
            MpCheck.RIGHT -> {
                val forgiven = if (at.isVisit) {
                    minOf(
                        PinGuard.FORGIVE_MAX,
                        visitWrong.count { it.place === at && startedAt - it.atElapsedMs <= PinGuard.FORGIVE_WINDOW_MS },
                    )
                } else {
                    0
                }
                visitWrong.removeAll { it.place === at }
                tries = StateRecord.MpGuard(failures = 0, lastFailElapsedMs = null, unseen = maxOf(0, before.unseen - forgiven))
                rec?.let { r ->
                    pendingCancelled = r.pendingMp != null
                    rec = r.copy(practice = practised(r.practice, clock.wallMs()), pendingMp = null, mpChange = null)
                }
                writeQuietly()
            }
        }
        return MpAttempt.Checked(result, tooLate, pendingCancelled)
    }

    /** The visit or prompt [place] has ended: it is marked ended, and its wrong tries are forgotten (they stay counted). */
    fun placeEnded(place: Place) {
        place.end()
        visitWrong.removeAll { it.place === place }
    }

    /**
     * The notice of wrong tries was answered (lock.md §13.4 item 4): the [shown] number of wrong
     * master passwords is taken off `unseen`. Best effort; returns whether the record is on disk.
     */
    fun unseenShown(shown: Int): Boolean {
        require(shown >= 0) { "a negative count" }
        if (vault == null) return true
        val unseen = maxOf(0, tries.unseen - shown)
        if (unseen == tries.unseen) return true
        tries = tries.copy(unseen = unseen)
        return writeQuietly()
    }

    /**
     * Changes the other fields of the record (practice, `mp_change`, `pending_mp`, `replaced`,
     * `max_generation`, the credits of §6.9) for the flows that own them, and writes the whole
     * record. The counts are not [transform]'s to change: it must leave `mp_guard` as it got it.
     *
     * Returns whether the record is on disk. When the write fails the change stays in memory if
     * [bestEffort] (and the next write stores it), otherwise the record stays as it was (F11 step 15:
     * "If the write fails: Couldn't finish now. Nothing was changed."). Without a record it may use
     * (MISSING or DAMAGED before [establish], NEWER, UNREADABLE) nothing changes: false.
     */
    fun update(bestEffort: Boolean = true, transform: (StateRecord) -> StateRecord): Boolean {
        val current = record ?: return false
        val next = transform(current)
        require(next.vaultId == current.vaultId) { "another vault's record" }
        require(next.mpGuard == current.mpGuard) { "the counts change only through attempt" }
        val written = writable && write(next)
        if (written || bestEffort) rec = next
        return written
    }

    /**
     * A new `state.rec` for this vault (lock.md §5.3 after a successful unlock that found it MISSING
     * or DAMAGED; F14 step 2; "Set up again" for an UNREADABLE one): [record] with this guard's
     * counts in place of its `mp_guard`, so that wrong tries typed before it are not lost. From now
     * on the counts are written. A `state.rec` that read NEWER is never replaced: false. Returns
     * whether the record is on disk; it is kept in memory either way.
     *
     * A vault that is new on this phone (F1, F13, F11 step 17) has a new id and so a new guard,
     * whose counts are zero.
     */
    fun establish(record: StateRecord): Boolean {
        val guarded = vault ?: throw IllegalStateException("a file from outside has no record")
        require(record.vaultId == guarded.vaultId) { "another vault's record" }
        if (read is RecordRead.Newer) return false
        rec = record
        writable = true
        read = RecordRead.Ok(record.copy(mpGuard = tries))
        return writeQuietly()
    }

    private fun writeQuietly(): Boolean {
        val r = rec ?: return false
        return writable && write(r.copy(mpGuard = tries))
    }

    private fun write(record: StateRecord): Boolean =
        try {
            vault!!.store.write(record)
            true
        } catch (e: IOException) {
            false
        }

    companion object {
        /** Wrong master passwords in a row that are tried without a wait (lock.md §6.8). */
        const val FREE_TRIES = 5

        /** The wait before each later try, by uptime; never more. */
        const val WAIT_MS = 30_000L

        private val NONE = StateRecord.MpGuard(failures = 0, lastFailElapsedMs = null, unseen = 0)

        /**
         * The guard of a file from outside, a backup being restored or imported (lock.md §6.8, F13
         * step 2): it never waits, never counts and has no record, because the attacker has that
         * file anyway and an owner on a new phone must not be slowed down.
         */
        fun forOutsideFile(): MasterPasswordGuard = MasterPasswordGuard(null, null)

        /** Practice after a correct master password at wall time [nowMs] (lock.md §8.3). */
        private fun practised(practice: StateRecord.Practice, nowMs: Long): StateRecord.Practice {
            val next = PracticeSchedule(practice.lastOkMs, practice.step, practice.snoozedUntilMs).masterPasswordTyped(nowMs)
            return StateRecord.Practice(next.lastOkMs, next.step, next.snoozedUntilMs)
        }
    }
}
