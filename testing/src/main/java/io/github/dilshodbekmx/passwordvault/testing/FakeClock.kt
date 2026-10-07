package io.github.dilshodbekmx.passwordvault.testing

import io.github.dilshodbekmx.passwordvault.core.util.Clock
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * A [Clock] the test sets (lock.md §15.1): both clocks are settable, the wall clock can jump either
 * way, and a reboot starts the elapsed clock again near 0.
 *
 * [delayUntilElapsed] either moves time forward to its target at once ([autoAdvance], the default,
 * as if the wait had simply passed) or suspends until the test has [advance]d the elapsed clock that
 * far. Every target it was asked for is kept in [waits], so a test can see that a wait happened.
 *
 * The default wall time is 2026-10-06 00:00 UTC (1,791,244,800,000 ms, the `created` of
 * architecture.md §4.13 vector A); the elapsed clock starts at 0.
 */
class FakeClock(
    wallMs: Long = 1_791_244_800_000L,
    elapsedMs: Long = 0L,
    @Volatile var autoAdvance: Boolean = true,
) : Clock {
    private val lock = Any()
    private val elapsed = MutableStateFlow(elapsedMs)

    @Volatile private var wall = wallMs

    /** The targets of every [delayUntilElapsed] call, in order. */
    val waits: MutableList<Long> = CopyOnWriteArrayList()

    override fun wallMs(): Long = wall

    override fun elapsedMs(): Long = elapsed.value

    override suspend fun delayUntilElapsed(elapsedMs: Long) {
        waits += elapsedMs
        if (autoAdvance) {
            synchronized(lock) {
                val now = elapsed.value
                if (elapsedMs > now) advanceLocked(elapsedMs - now)
            }
        } else {
            elapsed.first { it >= elapsedMs }
        }
    }

    /** Time passes: both clocks move forward by [ms] (≥ 0). */
    fun advance(ms: Long) {
        require(ms >= 0) { "time does not run backwards; use setWall or reboot" }
        synchronized(lock) { advanceLocked(ms) }
    }

    private fun advanceLocked(ms: Long) {
        wall += ms
        elapsed.value = elapsed.value + ms
    }

    /** The user sets the date: only the wall clock changes, to [ms]. */
    fun setWall(ms: Long) {
        synchronized(lock) { wall = ms }
    }

    /** The user moves the date by [deltaMs], forward or back: only the wall clock changes. */
    fun jumpWall(deltaMs: Long) {
        synchronized(lock) { wall += deltaMs }
    }

    /**
     * The phone is off for [offMs] and has been on again for [uptimeMs]: the wall clock moves on by
     * both, and the elapsed clock starts again at [uptimeMs], as `elapsedRealtime()` does after a
     * reboot.
     */
    fun reboot(offMs: Long = 0L, uptimeMs: Long = 0L) {
        require(offMs >= 0 && uptimeMs >= 0) { "negative time" }
        synchronized(lock) {
            wall += offMs + uptimeMs
            elapsed.value = uptimeMs
        }
    }
}
