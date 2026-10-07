package io.github.dilshodbekmx.passwordvault.session

/**
 * When the app locks (lock.md §10; architecture.md §3.5, §5.5): `AutoLock` (build plan 3.2) watches
 * the process lifecycle, the screen-off signal and the no-touch timer, and runs the order of §5.5
 * for every trigger. The activity and the screens tell it only these four things.
 */
interface Session {
    /**
     * Called just before the app opens `CreateDocument`, `OpenDocument` or the share sheet (lock.md
     * §10.3): for up to 10 minutes, leaving the app for that screen does not lock it.
     */
    fun beginExternal()

    /** The picker's result arrived, or the app is in front again: the grace of [beginExternal] ends. */
    fun endExternal()

    /** Every touch and key event the activity dispatches: restarts the 5-minute no-touch timer. */
    fun touched()

    /** Settings › "Lock now": clears our clip, flushes the draft, locks (architecture.md §5.5). */
    fun lockNow()
}
