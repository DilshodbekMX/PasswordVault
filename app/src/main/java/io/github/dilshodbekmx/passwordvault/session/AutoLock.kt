package io.github.dilshodbekmx.passwordvault.session

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.github.dilshodbekmx.passwordvault.clipboard.SecretClipboard
import io.github.dilshodbekmx.passwordvault.core.lock.policy.SessionPolicy
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.data.VaultRepository
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.lock.LockReason
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * When the open app locks by itself (lock.md §10; architecture.md §3.5 `Session`, §5.5): the
 * session's clock, kept as a [SessionPolicy] (build plan 1.3), which holds the arithmetic, and
 * driven here by the process lifecycle, the screen-off signal, touches, the app's own system
 * screens and one timer. A session runs from each unlock ([LockManager.state] becomes
 * [LockUiState.Unlocked]) to the next lock; nothing of it is on disk, and a new process starts
 * locked (§10.2).
 *
 * - **Leaving** (the process-level `ON_STOP`, 700 ms after the last activity paused) locks at once
 *   when the screen is off or the lock delay is "Immediately" and no external screen is pending.
 *   Otherwise the app stays open and the held draft of a form is written now, while the vault is
 *   still open (ux.md §5.14).
 * - **Returning** (`ON_START`) locks when the time away is at least the lock delay, or more than
 *   10 minutes after an external screen of the app's own was started (§10.3).
 * - **Screen off** ([screenTurnedOff], `ACTION_SCREEN_OFF`, also when Android delivers it late)
 *   locks at once. While the app is locked already it is still passed to the lock
 *   ([LockManager.lock] with [LockReason.SCREEN_OFF], with nothing to flush), which ends the visit
 *   of the lock screen shown, so that a PIN, fingerprint or master-password check that is still
 *   running opens nothing (lock.md §6.3, §10.2, decision 29).
 * - **No touch** for 5 minutes while the app is in front locks; the timer stands still while the
 *   app is away and while an external screen is pending.
 * - **"Lock now"** ([lockNow]) clears our clip, then locks.
 *
 * Every lock runs in the order of architecture.md §5.5: the decision, then
 * [VaultRepository.flushDraft] (best effort and bounded by the repository: a failure does not stop
 * the lock), then [LockManager.lock] with the reason. A recreation of the activity (a rotation, a
 * language change) reaches none of these signals: `ProcessLifecycleOwner` does not report it, so it
 * neither locks nor ends a visit (lock.md §11).
 *
 * The lock delay is read from [AppSettings.lockDelay] when it is needed; a setting that is missing
 * or cannot be read is "Immediately" there (D-7). Everything runs on the main thread, which is
 * where the lifecycle, the receiver and the activity's touch events call it.
 *
 * @param scope the app's scope (`Dispatchers.Main.immediate`): the timer, the draft flush and the
 *   lock run in it, and a lock that has started always finishes.
 * @param screen whether the screen is on now (`PowerManager.isInteractive()`).
 */
class AutoLock(
    private val lock: LockManager,
    private val repository: VaultRepository,
    private val clipboard: SecretClipboard,
    private val settings: AppSettings,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val screen: ScreenState,
) : Session {

    /** The session's clock while the app is unlocked; null while it is locked or locking. */
    private var policy: SessionPolicy? = null

    /** A lock has been decided and is running (the draft flush, then [LockManager.lock]). */
    private var locking = false

    /** The app is visible: between the process-level `ON_START` and `ON_STOP`. */
    private var inFront = false

    /** The one timer, set for [SessionPolicy.noTouchDeadlineMs]. */
    private var timer: Job? = null

    /**
     * Starts watching [processLifecycle] (`ProcessLifecycleOwner`'s) and the lock's state. Called
     * once, when the process starts.
     */
    fun start(processLifecycle: Lifecycle) {
        processLifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = cameToFront()

                override fun onStop(owner: LifecycleOwner) = wentAway()
            },
        )
        scope.launch { lock.state.collect { lockStateChanged(it) } }
    }

    /**
     * `ACTION_SCREEN_OFF` arrived: lock at once (lock.md §10.2). While a lock screen shows, the lock
     * is told too, so that it ends that screen's visit (lock.md §6.3, decision 29).
     */
    fun screenTurnedOff() {
        if (lock.state.value is LockUiState.Locked) {
            scope.launch { lock.lock(LockReason.SCREEN_OFF) }
            return
        }
        decideWith { it.screenOff() }
    }

    override fun beginExternal() {
        decideWith { it.beginExternal(now()) }
        restartTimer()
    }

    override fun endExternal() {
        decideWith { it.endExternal(now()) }
        restartTimer() // the timer starts afresh, so its deadline may come earlier
    }

    /** Called for every touch and key event: only the policy changes, and the timer reads it when it wakes. */
    override fun touched() = decideWith { it.touched(now()) }

    override fun lockNow() {
        clipboard.clearNow()
        if (locking || lock.state.value !is LockUiState.Unlocked) return
        runLock(LockReason.LOCK_NOW)
    }

    private fun cameToFront() {
        inFront = true
        decideWith { it.started(now(), delayMs()) }
        restartTimer()
    }

    private fun wentAway() {
        inFront = false
        stopTimer()
        val current = policy ?: return
        val decision = current.stopped(now(), screen.isOn(), delayMs())
        decide(decision)
        if (decision is SessionPolicy.Decision.Stay) scope.launch { flushDraft() }
    }

    private fun lockStateChanged(state: LockUiState) {
        if (state !is LockUiState.Unlocked) {
            endSession()
            return
        }
        if (policy != null || locking) return // the same session (new notices), or a lock is running
        val now = now()
        val fresh = SessionPolicy.unlocked(now)
        policy = fresh
        if (inFront) {
            restartTimer()
        } else {
            decide(fresh.stopped(now, screen.isOn(), delayMs())) // unlocked after the app was left: it left now
        }
    }

    private inline fun decideWith(event: (SessionPolicy) -> SessionPolicy.Decision) {
        val current = policy ?: return
        decide(event(current))
    }

    private fun decide(decision: SessionPolicy.Decision) {
        when (decision) {
            is SessionPolicy.Decision.Stay -> policy = decision.next
            is SessionPolicy.Decision.Lock -> runLock(reasonOf(decision.why))
        }
    }

    /**
     * The order of architecture.md §5.5 from step 2: the draft first, then the lock. The session
     * ends at once, so that no other trigger starts a second lock meanwhile. An exception of
     * [LockManager.lock] is not caught: the app then stops, which is a lock too.
     */
    private fun runLock(reason: LockReason) {
        endSession()
        locking = true
        scope.launch {
            try {
                flushDraft()
                lock.lock(reason)
            } finally {
                locking = false
            }
        }
    }

    private suspend fun flushDraft() {
        try {
            repository.flushDraft()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best effort (§5.5 step 2): a draft that could not be written does not stop the lock.
        }
    }

    private fun endSession() {
        policy = null
        stopTimer()
    }

    private fun restartTimer() {
        stopTimer()
        if (policy?.noTouchDeadlineMs == null) return
        timer = scope.launch {
            while (true) {
                val deadline = policy?.noTouchDeadlineMs ?: return@launch
                clock.delayUntilElapsed(deadline)
                val current = policy ?: return@launch
                val decision = current.checkTimer(now()) // a touch meanwhile moved the deadline: sleep again
                decide(decision)
                if (decision is SessionPolicy.Decision.Lock) return@launch
            }
        }
    }

    private fun stopTimer() {
        timer?.cancel()
        timer = null
    }

    private fun now(): Long = clock.elapsedMs()

    private fun delayMs(): Long = settings.lockDelay.value.ms

    private fun reasonOf(why: SessionPolicy.Why): LockReason = when (why) {
        SessionPolicy.Why.SCREEN_OFF -> LockReason.SCREEN_OFF
        SessionPolicy.Why.DELAY -> LockReason.LEFT
        SessionPolicy.Why.NO_TOUCH -> LockReason.NO_TOUCH
        SessionPolicy.Why.EXTERNAL_TOO_LONG -> LockReason.EXTERNAL_TIMEOUT
    }
}
