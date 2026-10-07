package io.github.dilshodbekmx.passwordvault.platform

import android.os.SystemClock
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import kotlinx.coroutines.delay

/**
 * The phone's two clocks (architecture.md §3.1; lock.md I8, §6.9, §10.2).
 *
 * - [wallMs] is `System.currentTimeMillis()`: for display, the practice schedule and the arming of
 *   the purge only. The user can set it, so it never decides a security question.
 * - [elapsedMs] is `SystemClock.elapsedRealtime()`: milliseconds since boot, deep sleep included,
 *   and the only clock of every wait, delay and credit. It starts again near 0 after a reboot.
 *
 * [delayUntilElapsed] waits in steps of at most [STEP_MS] and reads [elapsedMs] again after each
 * one. A coroutine `delay` is measured on a clock that stops while the phone sleeps (the main
 * looper's uptime, or the monotonic clock of `Dispatchers.Default`), so one long `delay` would end
 * late by the whole time the phone slept. With short steps a wait ends at most one step after its
 * target on the clock that counts, however long the phone slept meanwhile, and never before it.
 */
object AndroidClock : Clock {
    /** The longest single `delay` of [delayUntilElapsed]. */
    const val STEP_MS = 1_000L

    override fun wallMs(): Long = System.currentTimeMillis()

    override fun elapsedMs(): Long = SystemClock.elapsedRealtime()

    override suspend fun delayUntilElapsed(elapsedMs: Long) {
        while (true) {
            val left = elapsedMs - SystemClock.elapsedRealtime()
            if (left <= 0) return
            delay(minOf(left, STEP_MS))
        }
    }
}
