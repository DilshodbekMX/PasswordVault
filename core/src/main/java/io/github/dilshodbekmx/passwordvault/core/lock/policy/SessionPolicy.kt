package io.github.dilshodbekmx.passwordvault.core.lock.policy

/**
 * When the open app locks by itself (lock.md §10.1 to §10.3): pure arithmetic over
 * `elapsedRealtime()` values, with no clock, no timer and no lifecycle of its own. The session in
 * `:app` (`AutoLock`) passes in the process lifecycle, the screen-off signal, touches and the app's
 * own system screens, does what each [Decision] says, and keeps one timer at [noTouchDeadlineMs].
 *
 * A value is the session's clock state: in memory only, never on disk, so there is nothing to tamper
 * with, and a new process starts locked (§10.2). [unlocked] makes the first value at an unlock; every
 * event answers [Decision.Stay] with the next value, or [Decision.Lock], after which this session is
 * over and the next unlock starts a new one.
 *
 * - **Leaving** (the process-level `ON_STOP`, [stopped]) locks at once when the screen is off, or
 *   when the lock delay is "Immediately" and no external screen is pending.
 * - **Returning** (`ON_START`, [started]) locks when the time away is at least the lock delay; or,
 *   when the app left while an external screen of its own was pending, only when more than
 *   10 minutes have passed since that screen was started.
 * - **Screen off** ([screenOff]) locks at once, whatever else holds; so at `ON_START` no screen-off
 *   can have been seen since `ON_STOP` without a lock (rule 2 of §10.2).
 * - **No touch** for 5 minutes while the app is in front locks ([checkTimer]). The timer is
 *   restarted by every touch and key press; it stands still while the app is away and while an
 *   external screen is pending, and starts afresh when the app comes back and when such a grace ends.
 * - **External screens** (§10.3: the file picker and the share sheet the app opens itself): the
 *   grace starts at [beginExternal] and ends at [endExternal] (the picker's result), when the app
 *   comes back, or 10 minutes after it started, whichever comes first.
 * - **The lock delay** is the setting in milliseconds: 0 is "Immediately", and a missing or
 *   unreadable setting (null) means "Immediately" (§10.1).
 * - A time that runs backwards, which `elapsedRealtime()` never does, counts as a time that has run
 *   out: the answer is the stricter one.
 */
class SessionPolicy private constructor(
    /** The app is visible: between the process-level `ON_START` and `ON_STOP`. */
    val inFront: Boolean,
    /** While away: when the app left. */
    private val stoppedAtMs: Long,
    /** An external screen of the app's own is pending since then, unless its 10 minutes are over. */
    private val externalSinceMs: Long?,
    /** While away: the external screen that was pending when the app left, whose grace covers this absence. */
    private val graceSinceMs: Long?,
    /** In front: the no-touch timer runs from then. Null while it stands still. */
    private val timerFromMs: Long?,
) {

    /** What to do after an event. */
    sealed interface Decision {
        /** Stay open; [next] is the session's state from now on. */
        class Stay(val next: SessionPolicy) : Decision

        /** Lock now, for [why]. */
        class Lock(val why: Why) : Decision
    }

    /** Why the app locks by itself. */
    enum class Why {
        /** The screen turned off, or was off when the app left. */
        SCREEN_OFF,

        /** The app was away for at least the lock delay ("Immediately": at once when it left). */
        DELAY,

        /** Five minutes without a touch or a key press while the app was in front. */
        NO_TOUCH,

        /** The app came back more than 10 minutes after it opened an external screen. */
        EXTERNAL_TOO_LONG,
    }

    /**
     * When the no-touch timer runs out if nothing else happens, as an `elapsedRealtime()` value at
     * which to call [checkTimer]; null while the app is away, when no timer runs. While an external
     * screen is pending in front, it is the end of that grace plus 5 minutes.
     */
    val noTouchDeadlineMs: Long?
        get() = when {
            !inFront -> null
            externalSinceMs != null -> externalSinceMs + EXTERNAL_MAX_MS + NO_TOUCH_MS
            else -> timerFromMs?.let { it + NO_TOUCH_MS }
        }

    /**
     * The process-level `ON_STOP` at [nowMs]. [screenOn] is `PowerManager.isInteractive()`;
     * [delayMs] the lock-delay setting.
     */
    fun stopped(nowMs: Long, screenOn: Boolean, delayMs: Long?): Decision {
        if (!screenOn) return Decision.Lock(Why.SCREEN_OFF)
        val s = graceEndedBy(nowMs)
        if (!s.inFront) return Decision.Stay(s)
        s.timerRanOut(nowMs)?.let { return it }
        if (lockDelayMs(delayMs) == 0L && s.externalSinceMs == null) return Decision.Lock(Why.DELAY)
        return Decision.Stay(
            SessionPolicy(
                inFront = false,
                stoppedAtMs = nowMs,
                externalSinceMs = s.externalSinceMs,
                graceSinceMs = s.externalSinceMs,
                timerFromMs = null,
            ),
        )
    }

    /** The process-level `ON_START` at [nowMs]; [delayMs] the lock-delay setting. */
    fun started(nowMs: Long, delayMs: Long?): Decision {
        if (inFront) return Decision.Stay(graceEndedBy(nowMs))
        val grace = graceSinceMs
        if (grace != null) {
            if (over(grace, nowMs, EXTERNAL_MAX_MS)) return Decision.Lock(Why.EXTERNAL_TOO_LONG)
        } else if (reached(stoppedAtMs, nowMs, lockDelayMs(delayMs))) {
            return Decision.Lock(Why.DELAY)
        }
        // Back in front: any external grace is over, and the no-touch timer starts afresh.
        return Decision.Stay(SessionPolicy(true, 0L, null, null, nowMs))
    }

    /** The screen turned off (`ACTION_SCREEN_OFF`, also when it arrives late): lock at once. */
    fun screenOff(): Decision = Decision.Lock(Why.SCREEN_OFF)

    /** A touch or key press that the activity dispatched at [nowMs]. */
    fun touched(nowMs: Long): Decision {
        val s = graceEndedBy(nowMs)
        if (!s.inFront) return Decision.Stay(s)
        s.timerRanOut(nowMs)?.let { return it }
        if (s.externalSinceMs != null) return Decision.Stay(s) // the timer stands still meanwhile
        return Decision.Stay(s.copy(timerFromMs = nowMs))
    }

    /** Just before the app starts the file picker or the share sheet, at [nowMs] (`Session.beginExternal`). */
    fun beginExternal(nowMs: Long): Decision {
        val s = graceEndedBy(nowMs)
        if (!s.inFront) return Decision.Stay(s)
        s.timerRanOut(nowMs)?.let { return it }
        return Decision.Stay(s.copy(externalSinceMs = nowMs, timerFromMs = null))
    }

    /**
     * The picker's result arrived at [nowMs] (`Session.endExternal`): the grace is over. In front,
     * the no-touch timer starts afresh. While away it changes nothing about this absence, which the
     * grace pending at `ON_STOP` covers whichever of the two calls comes first.
     */
    fun endExternal(nowMs: Long): Decision {
        val s = graceEndedBy(nowMs)
        if (s.externalSinceMs == null) return Decision.Stay(s)
        return Decision.Stay(s.copy(externalSinceMs = null, timerFromMs = if (s.inFront) nowMs else null))
    }

    /** The timer set for [noTouchDeadlineMs] went off, or the session looks at the time [nowMs] for another reason. */
    fun checkTimer(nowMs: Long): Decision {
        val s = graceEndedBy(nowMs)
        if (!s.inFront) return Decision.Stay(s)
        return s.timerRanOut(nowMs) ?: Decision.Stay(s)
    }

    /** This state with an external grace whose 10 minutes are over by [nowMs] ended at its 10th minute. */
    private fun graceEndedBy(nowMs: Long): SessionPolicy {
        val since = externalSinceMs ?: return this
        if (!over(since, nowMs, EXTERNAL_MAX_MS)) return this
        return copy(externalSinceMs = null, timerFromMs = if (inFront) since + EXTERNAL_MAX_MS else timerFromMs)
    }

    private fun timerRanOut(nowMs: Long): Decision.Lock? {
        val from = timerFromMs ?: return null
        return if (reached(from, nowMs, NO_TOUCH_MS)) Decision.Lock(Why.NO_TOUCH) else null
    }

    private fun copy(
        externalSinceMs: Long? = this.externalSinceMs,
        timerFromMs: Long? = this.timerFromMs,
    ) = SessionPolicy(inFront, stoppedAtMs, externalSinceMs, graceSinceMs, timerFromMs)

    companion object {
        /** Lock after this long without a touch or a key press while the app is in front. */
        const val NO_TOUCH_MS = 5 * 60_000L

        /** The longest grace for a system screen the app opened itself. */
        const val EXTERNAL_MAX_MS = 10 * 60_000L

        /** A new session at an unlock at [nowMs], with the app in front and the no-touch timer running. */
        fun unlocked(nowMs: Long): SessionPolicy = SessionPolicy(true, 0L, null, null, nowMs)

        /** The lock delay that applies: the setting, or "Immediately" (0) when it is missing or not a duration. */
        fun lockDelayMs(settingMs: Long?): Long = if (settingMs == null || settingMs < 0) 0L else settingMs

        /** At least [atLeastMs] have passed from [fromMs] to [nowMs]; a time that ran backwards has run out. */
        private fun reached(fromMs: Long, nowMs: Long, atLeastMs: Long) = nowMs < fromMs || nowMs - fromMs >= atLeastMs

        /** More than [limitMs] have passed from [fromMs] to [nowMs]; a time that ran backwards has run out. */
        private fun over(fromMs: Long, nowMs: Long, limitMs: Long) = nowMs < fromMs || nowMs - fromMs > limitMs
    }
}
