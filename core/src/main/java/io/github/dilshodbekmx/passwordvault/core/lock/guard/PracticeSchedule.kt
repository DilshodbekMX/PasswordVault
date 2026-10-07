package io.github.dilshodbekmx.passwordvault.core.lock.guard

/**
 * When the master password is asked for as practice (lock.md §8.3): the `practice` part of
 * `state.rec` (§5.6) and the arithmetic over it.
 *
 * - [lastOkMs]: when a master password was last typed correctly.
 * - [step]: 0 to 3; it picks the interval of 1, 2, 4 or 14 days. Any other value counts as 0, which
 *   asks soonest (a record codec refuses such a record anyway).
 * - [snoozedUntilMs]: set by "Not now".
 *
 * All three are wall-clock times (`Clock.wallMs`): practice is a memory aid, and the wall clock
 * decides no security question (lock.md I8). Answered on time, practice comes on days 1, 3 and 7
 * after a master password is set, then every 14 days. Each due date has a grace of
 * min(interval, 7 days) in which "Not now" is offered; after it the master password is required,
 * with "I can't remember it" (F11) still there.
 */
data class PracticeSchedule(val lastOkMs: Long, val step: Int, val snoozedUntilMs: Long?) {

    /** What the lock does about practice now (lock.md §8.3, §13.1 rows 3 and 4, §13.4 item 6). */
    enum class Status {
        /** Not due: the lock screen opens as usual. */
        NOT_DUE,

        /** Due, within its grace: the master password is asked for, with "Not now: use PIN" (§13.1 row 4). */
        DUE,

        /** Due, within its grace, and "Not now" was tapped less than 20 hours ago: the lock screen opens as usual. */
        SNOOZED,

        /**
         * Past its grace: the master password is required, with "I can't remember it" (§13.1 row 3),
         * and the standing banner of §13.4 item 6 is shown while the app is open.
         */
        REQUIRED,

        /** A new master password is pending (F11): practice is not asked, and there is no banner. */
        NOT_ASKED,
    }

    private val index: Int get() = if (step in 0..MAX_STEP) step else 0

    /** When practice is due: `last_ok + interval[step]`. */
    val dueMs: Long get() = plus(lastOkMs, INTERVALS_MS[index])

    /** When the grace ends and the master password becomes required: due + min(interval, 7 days). */
    val graceEndsMs: Long get() = plus(dueMs, minOf(INTERVALS_MS[index], MAX_GRACE_MS))

    /**
     * Practice at wall time [nowMs]. [newPasswordPending]: a new master password is pending (F11),
     * when practice is not asked. If the clock is more than an hour before [lastOkMs], practice is
     * due at once, still with "Not now".
     *
     * Whether a shortcut is usable is not decided here: without one, every unlock is the master
     * password already and the lock's entry policy does not ask (§8.3, §13.1).
     */
    fun status(nowMs: Long, newPasswordPending: Boolean): Status = when {
        newPasswordPending -> Status.NOT_ASKED
        clockBehind(nowMs) -> if (snoozedAt(nowMs)) Status.SNOOZED else Status.DUE
        nowMs < dueMs -> Status.NOT_DUE
        nowMs >= graceEndsMs -> Status.REQUIRED
        snoozedAt(nowMs) -> Status.SNOOZED
        else -> Status.DUE
    }

    /**
     * The standing banner of lock.md §13.4 item 6 ("You have not typed your master password since
     * 12 Sep."): [lastOkMs], the date it names, while practice is past its grace and no new master
     * password is pending ([Status.REQUIRED]); null when there is no banner.
     */
    fun bannerSinceMs(nowMs: Long, newPasswordPending: Boolean): Long? =
        if (status(nowMs, newPasswordPending) == Status.REQUIRED) lastOkMs else null

    /**
     * After a correct master password at wall time [nowMs], wherever it was typed (at unlock, on the
     * practice screen, for a backup, an export, a change or a setting): `last_ok` = now and no
     * snooze; the step goes up by one, to at most 3, if at least 20 hours have passed since the
     * previous `last_ok`.
     */
    fun masterPasswordTyped(nowMs: Long): PracticeSchedule {
        val stepUp = nowMs >= plus(lastOkMs, STEP_UP_MS)
        return PracticeSchedule(nowMs, if (stepUp) minOf(index + 1, MAX_STEP) else index, null)
    }

    /** "Not now" at wall time [nowMs]: not asked again for 20 hours, while the grace lasts. */
    fun notNow(nowMs: Long): PracticeSchedule = copy(snoozedUntilMs = plus(nowMs, SNOOZE_MS))

    private fun clockBehind(nowMs: Long): Boolean = plus(nowMs, CLOCK_SLACK_MS) < lastOkMs

    /** A snooze holds from when it was set (an hour of slack, as for `last_ok`) until it ends. */
    private fun snoozedAt(nowMs: Long): Boolean {
        val until = snoozedUntilMs ?: return false
        return nowMs < until && plus(nowMs, SNOOZE_MS + CLOCK_SLACK_MS) >= until
    }

    companion object {
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 24 * HOUR_MS
        const val MAX_STEP = 3

        /** The longest grace: 7 days, once the 14-day rhythm is reached. */
        const val MAX_GRACE_MS = 7 * DAY_MS

        /** How long "Not now" holds. */
        const val SNOOZE_MS = 20 * HOUR_MS

        /** How long after the previous correct master password one counts as the next step. */
        const val STEP_UP_MS = 20 * HOUR_MS

        /** How far the clock may be before `last_ok` before practice is due at once. */
        const val CLOCK_SLACK_MS = HOUR_MS

        private val INTERVALS_MS = longArrayOf(1 * DAY_MS, 2 * DAY_MS, 4 * DAY_MS, 14 * DAY_MS)

        /** The interval that [step] picks: 1, 2, 4 or 14 days. */
        fun intervalMs(step: Int): Long = INTERVALS_MS[if (step in 0..MAX_STEP) step else 0]

        /** Practice when `state.rec` is MISSING or DAMAGED: due now, and still skippable (lock.md §5.3). */
        val STATUS_WITHOUT_RECORD: Status = Status.DUE

        /** A new master password at [nowMs] (setup, change, replacement): step 0. */
        fun newMasterPassword(nowMs: Long): PracticeSchedule = PracticeSchedule(nowMs, 0, null)

        /** A restore on a new phone at [nowMs]: step 3, because the user has just proved they know it. */
        fun restored(nowMs: Long): PracticeSchedule = PracticeSchedule(nowMs, MAX_STEP, null)

        /**
         * The record written after the first successful unlock that found `state.rec` MISSING or
         * DAMAGED (lock.md §5.3): step 0, and `last_ok` = now after a master-password unlock, or one
         * day ago after a PIN or fingerprint unlock, so that practice stays due with its one day of
         * grace.
         */
        fun afterMissingRecord(nowMs: Long, byMasterPassword: Boolean): PracticeSchedule =
            PracticeSchedule(if (byMasterPassword) nowMs else plus(nowMs, -DAY_MS), 0, null)

        /** [a] + [b], held at the ends of `Long` instead of wrapping round. */
        private fun plus(a: Long, b: Long): Long {
            val sum = a + b
            val overflowed = ((a xor sum) and (b xor sum)) < 0
            return if (overflowed) (if (a < 0) Long.MIN_VALUE else Long.MAX_VALUE) else sum
        }
    }
}
