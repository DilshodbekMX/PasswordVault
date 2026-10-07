package io.github.dilshodbekmx.passwordvault.core.lock.guard

/**
 * Time that a reboot cannot shorten (lock.md §6.9). Two things wait for real time and use this and
 * nothing else: the 24 hours before a new master password starts working, and the 7 days for which
 * the "replaced" notice is shown (F11). The purge of Recently deleted waits on one too
 * (architecture.md D-24).
 *
 * [creditedMs] is the time credited so far; [seenElapsedMs] is the `elapsedRealtime()` value at the
 * last look. The only input is `elapsedRealtime()`: no boot count, which `adb` can rewrite without a
 * reboot, and no date, which the user can set [J-S §2.1]. So neither can change the result.
 *
 * The holder calls [observe] at process start, at every process-level `ON_START` and `ON_STOP`, at
 * every unlock and whenever the banner is drawn, and writes the result back to `state.rec` (best
 * effort). On one boot the credit is exact. After a restart it cannot know how long the phone was
 * off, so it credits the new uptime only when that is less than the last value it saw, and otherwise
 * only the difference; the time between the last look and a shut-down is never seen. The credit is
 * therefore never more than the time that really passed, and on a phone switched off every night it
 * is less (lock.md §6.9 and appendix F give how much).
 */
data class Credit(val creditedMs: Long, val seenElapsedMs: Long) {

    /**
     * The credit after a look at `elapsedRealtime()` = [nowElapsedMs]: the helper of lock.md §6.9.
     * For the values a phone gives (both times at least 0) it is exactly that helper. Beyond them it
     * stays on the side of crediting less: a negative [nowElapsedMs] counts as 0, a negative
     * [seenElapsedMs] (never a real reading) credits nothing, and the sum stops at `Long.MAX_VALUE`.
     */
    fun observe(nowElapsedMs: Long): Credit {
        val now = maxOf(nowElapsedMs, 0L)
        val gained = when {
            seenElapsedMs < 0 -> 0L
            now >= seenElapsedMs -> now - seenElapsedMs
            else -> now // a restart: only the uptime since it, which is less than what really passed
        }
        val credited = if (creditedMs > Long.MAX_VALUE - gained) Long.MAX_VALUE else creditedMs + gained
        return Credit(credited, now)
    }

    /** Whether at least [needMs] are credited, for example the 24 hours of F11. */
    fun reached(needMs: Long): Boolean = creditedMs >= needMs

    /** What is still to be credited before [needMs] are, never below 0 ("starts working in about N hours"). */
    fun remainingMs(needMs: Long): Long = if (creditedMs >= needMs) 0L else needMs - maxOf(creditedMs, 0L)

    companion object {
        /** A new wait, started at `elapsedRealtime()` = [nowElapsedMs] with nothing credited (F11 steps 5 and 15). */
        fun start(nowElapsedMs: Long): Credit = Credit(0L, maxOf(nowElapsedMs, 0L))
    }
}
