package io.github.dilshodbekmx.passwordvault.clipboard

import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.core.util.RandomSource
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.settings.AppSettings
import io.github.dilshodbekmx.passwordvault.settings.PendingClip
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phone's clipboard as [AndroidSecretClipboard] uses it: [AndroidSystemClip] on the phone, a
 * fake in tests. Nothing here reads what is on the clipboard, only whose clip it is (ux.md §3).
 */
internal interface SystemClip {
    /**
     * Whether a read can be refused although the app is running: from Android 10 on only the app
     * whose window has the focus may read the clipboard, so that [holder] says [ClipHolder.UNKNOWN]
     * in the background and for the first moments in front [V-P2].
     */
    val needsFocusToRead: Boolean

    /**
     * Puts [text] on the clipboard as a plain clip with an empty label, the sensitive flag as
     * [sensitive] says and [token] in its extras (ux.md §6 item 1). Throws a [RuntimeException]
     * when the phone refuses.
     */
    fun set(text: CharSequence, sensitive: Boolean, token: String)

    /** Whose clip is on the clipboard now, read from its description alone. Never throws. */
    fun holder(token: String): ClipHolder

    /** Empties the clipboard, whatever is on it. Throws a [RuntimeException] when the phone refuses. */
    fun clear()
}

/** What the clip's description says about the clipboard (ux.md §6 item 2). */
internal enum class ClipHolder {
    /** The description carries our token: the clip is the one we set. */
    OURS,

    /** The description can be read and carries another token or none: the user has copied something else since. */
    OTHER,

    /** There is no description to read: the clipboard is empty, or Android does not let us look. */
    UNKNOWN,
}

/** The alarm that clears the clip when the process is frozen or gone: [AlarmManagerClearAlarm] on the phone. */
internal interface ClearAlarm {
    /** Sets the one alarm, replacing an earlier one, to reach `ClipboardClearReceiver` at [clearAtElapsedMs] with [token]. */
    fun schedule(token: String, clearAtElapsedMs: Long)

    /** Removes the alarm, if one is set. */
    fun cancel()
}

/**
 * [SecretClipboard] on the phone (architecture.md D-14, D-25; ux.md §6; build plan 3.8).
 *
 * **A copy** puts the text on the clipboard with the sensitive flag as asked and a random 128-bit
 * token in the clip's extras, and plans its clear after the chosen delay
 * ([AppSettings.clipDelay]) on three paths that all end in the same check:
 *  - a timer in the process, on time while the process runs;
 *  - an alarm to `ClipboardClearReceiver` ([ClearAlarm]), which Android delivers also to a frozen or
 *    ended process ([alarmFired]);
 *  - the token and the deadline in the settings ([AppSettings.pendingClip]), so that a clip that
 *    neither path reached (after a force-stop, which cancels the alarm, or with battery use
 *    "Restricted") is cleared when the app next comes to the front ([appCameToFront]).
 * A new copy replaces the plan of the one before it.
 *
 * **The check before every clear** reads the clip's description, never its content
 * ([SystemClip.holder]): a description without our token means the user has copied something else,
 * and nothing is cleared; our token, or no readable description (the app in the background on
 * Android 10 and later [V-P2], or an empty clipboard), and the clipboard is cleared. So a later copy
 * survives wherever Android lets the app look, and nothing of ours stays where it does not.
 *
 * **Coming to the front.** `onResume` comes before the window has the focus, and until then Android
 * 10 and later answer every read with "nothing there" (seen on API 37: no description in `onResume`,
 * the description some 100 to 300 ms later). So [appCameToFront] decides only what it can see, and
 * [windowFocused], called by `ClipboardRoot` when the window gets the focus, looks again: a clip of
 * another app ends the plan and hides the row, and a clip of ours that is overdue is cleared. An
 * overdue clip that cannot be read in `onResume` waits for the focus for at most [FOCUS_WAIT_MS] and
 * is then cleared anyway.
 *
 * **The status row** ([status]) shows a pending clip and counts down to its clear; after a clear it
 * says so for [CLEARED_SHOWN_MS] and goes. In a new process a pending clip is known only by its
 * token and deadline (what was copied is account data and is never written to the settings), so its
 * row says "Copied text".
 *
 * Everything runs on the main thread: [copy], [clearNow] and [appCameToFront] are called there, the
 * receiver runs there, and [scope] is the app's (`Dispatchers.Main.immediate`). Only the settings
 * are written on [io], one write at a time and in order.
 *
 * @param scope the app's scope: a clear that has started always finishes.
 * @param io where `AppSettings.update` runs (architecture.md §5.3).
 */
internal class AndroidSecretClipboard(
    private val system: SystemClip,
    private val alarm: ClearAlarm,
    private val settings: AppSettings,
    private val clock: Clock,
    private val random: RandomSource,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) : SecretClipboard {

    /** A clip of ours still to be cleared: its token and the planned clear on the elapsed clock. */
    private class Pending(val token: String, val clearAtElapsedMs: Long)

    private val mutableStatus = MutableStateFlow<ClipStatus?>(null)
    override val status: StateFlow<ClipStatus?> = mutableStatus.asStateFlow()

    private var pending: Pending? = null

    /** The token of the last clip this process dealt with, so that a second path arriving for it does nothing. */
    private var handledToken: String? = null

    /** The timer in the process for [pending]. */
    private var timer: Job? = null

    /** Ends "Clipboard cleared" after [CLEARED_SHOWN_MS]. */
    private var hide: Job? = null

    /** The wait for the window focus of an overdue clip that `onResume` could not read. */
    private var focusWait: Job? = null

    /** What the settings should hold, and the one coroutine that writes it. */
    private var wanted: PendingClip? = null
    private var writer: Job? = null

    init {
        restore()
    }

    override fun copy(value: CharSequence, what: UiText, sensitive: Boolean) {
        val token = Bytes.hex(random.bytes(TOKEN_BYTES))
        try {
            system.set(value, sensitive, token)
        } catch (e: RuntimeException) {
            // Some makers' builds refuse (ux.md §6 "Failures"); the caller tells the user. Should the
            // clip have gone in all the same, it must not stay there with no clear planned.
            if (system.holder(token) == ClipHolder.OURS) quietly { system.clear() }
            throw e
        }
        val delayMs = settings.clipDelay.value.ms
        val clearAt = clock.elapsedMs() + delayMs
        stopWaiting()
        pending = Pending(token, clearAt)
        mutableStatus.value = ClipStatus(what, sensitive, clearAt, delayMs)
        plan(token, clearAt)
        persist(PendingClip(token, clock.wallMs() + delayMs))
    }

    override fun clearNow() = clearPending()

    override fun appCameToFront() = look(focused = false)

    /**
     * The activity's window has the focus: from now on the clip's description can be read on every
     * Android version, so the check of [appCameToFront] is made again.
     */
    fun windowFocused() = look(focused = true)

    /**
     * The alarm reached `ClipboardClearReceiver` with the [token] and the planned time it carries.
     * At or after that time the clip is cleared; up to [MAX_WAIT_IN_RECEIVER_MS] before it (an alarm
     * that is not exact may come early) the receiver is kept until then, so that the process is not
     * frozen meanwhile; earlier than that the alarm is aimed again. [done] is called once, when all
     * of it is over and the settings are written: the process may end right after.
     */
    fun alarmFired(token: String, clearAtElapsedMs: Long, done: () -> Unit) {
        scope.launch {
            try {
                if (expects(token)) {
                    val waitMs = clearAtElapsedMs - clock.elapsedMs()
                    when {
                        waitMs <= 0L -> clearByAlarm(token, clearAtElapsedMs)
                        waitMs > MAX_WAIT_IN_RECEIVER_MS -> quietly { alarm.schedule(token, clearAtElapsedMs) }
                        else -> {
                            clock.delayUntilElapsed(clearAtElapsedMs)
                            if (expects(token)) clearByAlarm(token, clearAtElapsedMs)
                        }
                    }
                }
                written()
            } finally {
                done()
            }
        }
    }

    /**
     * A new process finds a clip of ours in the settings: it is pending again, with the time that is
     * left on the wall clock, but never more than the chosen delay. A deadline further off than
     * that can only come from a date moved back since the copy (or a delay shortened since); the
     * plan made here replaces the alarm, which still knew the right time, so the chosen delay is the
     * most a moved date can put the clear off by.
     * A clip that is overdue is left to [appCameToFront] and [alarmFired], which can check it.
     */
    private fun restore() {
        val saved = settings.pendingClip.value ?: return
        val leftMs = (saved.deadlineMs - clock.wallMs()).coerceIn(0L, settings.clipDelay.value.ms)
        val clearAt = clock.elapsedMs() + leftMs
        pending = Pending(saved.token, clearAt)
        mutableStatus.value = ClipStatus(COPIED_TEXT, sensitive = true, clearsAtElapsedMs = clearAt, delayMs = leftMs)
        if (leftMs > 0L) plan(saved.token, clearAt)
    }

    /** The timer and the alarm for the clip [token]; an alarm that cannot be set leaves the timer and the settings. */
    private fun plan(token: String, clearAt: Long) {
        timer = scope.launch {
            clock.delayUntilElapsed(clearAt)
            if (pending?.token == token) clearPending()
        }
        quietly { alarm.schedule(token, clearAt) }
    }

    /** Whether an alarm for [token] is still to be acted on: it is the pending clip, or one this process never heard of. */
    private fun expects(token: String): Boolean {
        val now = pending
        return if (now != null) now.token == token else token != handledToken
    }

    private fun clearByAlarm(token: String, clearAtElapsedMs: Long) {
        // A process started for this alarm before the settings were written knows the clip only from the alarm.
        if (pending == null) pending = Pending(token, clearAtElapsedMs)
        clearPending()
    }

    /** What [appCameToFront] and [windowFocused] do: see the class comment. */
    private fun look(focused: Boolean) {
        val now = pending ?: return
        if (focused) focusWait?.cancel()
        val holder = system.holder(now.token)
        val overdue = clock.elapsedMs() >= now.clearAtElapsedMs
        when {
            holder == ClipHolder.OTHER -> forget(now, cleared = false)
            !overdue -> Unit
            holder == ClipHolder.OURS || focused || !system.needsFocusToRead -> clearPending(holder)
            focusWait?.isActive != true -> focusWait = scope.launch {
                clock.delayUntilElapsed(clock.elapsedMs() + FOCUS_WAIT_MS)
                if (pending === now) clearPending()
            }
        }
    }

    /**
     * Clears the clipboard if a clip of ours is pending and nothing else can be seen there (ux.md
     * §6: "only if the clip is still the app's own or cannot be checked"). A clear the phone refuses
     * changes nothing: the clip stays pending and the next path tries again.
     */
    private fun clearPending(seen: ClipHolder? = null) {
        val now = pending ?: return
        if ((seen ?: system.holder(now.token)) == ClipHolder.OTHER) {
            forget(now, cleared = false)
            return
        }
        try {
            system.clear()
        } catch (e: RuntimeException) {
            return
        }
        forget(now, cleared = true)
    }

    /** The clip [clip] is dealt with: no timer, no alarm, nothing in the settings; the row says so or goes. */
    private fun forget(clip: Pending, cleared: Boolean) {
        pending = null
        handledToken = clip.token
        stopWaiting()
        quietly { alarm.cancel() }
        persist(null)
        val shown = mutableStatus.value?.takeIf { cleared }?.copy(cleared = true)
        mutableStatus.value = shown
        if (shown != null) {
            hide = scope.launch {
                clock.delayUntilElapsed(clock.elapsedMs() + CLEARED_SHOWN_MS)
                if (mutableStatus.value === shown) mutableStatus.value = null
            }
        }
    }

    private fun stopWaiting() {
        timer?.cancel()
        timer = null
        hide?.cancel()
        hide = null
        focusWait?.cancel()
        focusWait = null
    }

    /**
     * Writes [clip] to the settings off the main thread. Writes follow one another, and one that is
     * overtaken is skipped: only the last value matters. A write that fails is not tried again: the
     * timer and the alarm do not depend on it.
     */
    private fun persist(clip: PendingClip?) {
        wanted = clip
        if (writer?.isActive == true) return // it reads [wanted] again when its write is done
        if (settings.pendingClip.value == clip) return
        writer = scope.launch {
            do {
                val value = wanted
                withContext(io) { settings.update { it.pendingClip = value } }
            } while (wanted != value)
        }
    }

    /** Returns when the settings hold what [persist] was last given, or its write has failed. */
    private suspend fun written() {
        while (true) {
            val running = writer?.takeIf { it.isActive } ?: return
            running.join()
        }
    }

    /** For a call the plan does not depend on: the phone's refusal is not an error here. */
    private inline fun quietly(call: () -> Unit) {
        try {
            call()
        } catch (e: RuntimeException) {
            // Nothing to do: the other paths to the clear remain.
        }
    }

    companion object {
        /** The token's length: 128 random bits, written as 32 hex digits (ux.md §6 item 1). */
        const val TOKEN_BYTES = 16

        /** How long the row says "Clipboard cleared" (ux.md §6). */
        const val CLEARED_SHOWN_MS = 2_000L

        /**
         * The longest time `ClipboardClearReceiver` is kept waiting for the deadline, as measured in
         * the clipboard lab (docs/lab/clipboard.md §6).
         */
        const val MAX_WAIT_IN_RECEIVER_MS = 8_000L

        /** How long an overdue clip that `onResume` cannot read waits for the window focus before it is cleared anyway. */
        const val FOCUS_WAIT_MS = 2_000L

        /** What the row calls a clip of which only the token is known, and text copied from a selection (D-25). */
        val COPIED_TEXT: UiText = UiText.of(R.string.clipboard_copied_text)
    }
}
