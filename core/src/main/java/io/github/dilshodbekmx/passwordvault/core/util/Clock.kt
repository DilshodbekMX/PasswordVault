package io.github.dilshodbekmx.passwordvault.core.util

/**
 * The app's two clocks (architecture.md §3.1). Every rule that needs the time takes a [Clock], so
 * that it runs on the JVM with `FakeClock` of :testing; `AndroidClock` (package 2.7) is the real
 * one. There is no boot count: lock.md dropped it with its audit (its decision 15).
 */
interface Clock {
    /**
     * `System.currentTimeMillis()`, milliseconds since 1970 UTC. For display, the practice
     * schedule and the arming of the purge only (lock.md I8, architecture.md §4.3, D-24): it never
     * decides a security question, because the user can set the date.
     */
    fun wallMs(): Long

    /**
     * `SystemClock.elapsedRealtime()`: milliseconds since boot, sleep included. Every wait, delay
     * and credit (lock.md §6.9, §10.2). It starts again near 0 after a reboot.
     */
    fun elapsedMs(): Long

    /**
     * Suspends until [elapsedMs] (the function above) has reached [elapsedMs]; returns at once when
     * it already has. Cancellable like any suspending call.
     */
    suspend fun delayUntilElapsed(elapsedMs: Long)
}
