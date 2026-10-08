package io.github.dilshodbekmx.passwordvault.lock

import io.github.dilshodbekmx.passwordvault.core.lock.flow.PromptUse
import io.github.dilshodbekmx.passwordvault.core.lock.policy.KeystoreErrors
import javax.crypto.Cipher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one fingerprint prompt of one activity (lock.md §9.2 rule 1), as [PromptLauncher] needs it.
 * `BiometricHost` builds the real one over `BiometricPrompt` in every `onCreate`; the JVM tests use
 * a fake. Every member is used on the main thread.
 */
interface PromptWindow {
    /** The activity is RESUMED: only then may a prompt be launched (lock.md §9.2 rule 3). */
    val resumed: Boolean

    /** The activity's own window has the focus: no dialog, sheet or prompt lies over it (§9.2 rule 7). */
    val focused: Boolean

    /**
     * `authenticate()` with [cipher] as the `CryptoObject` and the title and negative button of [use]
     * (§9.2 rule 5); [pinUsable] picks "Use PIN" or "Use master password" for [PromptUse.UNLOCK].
     */
    fun show(cipher: Cipher, use: PromptUse, pinUsable: Boolean)

    /** `cancelAuthentication()`: an open prompt ends with code 5. */
    fun dismiss()
}

/** The main thread, as [PromptLauncher] needs it: the app's is the main looper, a test's runs at once. */
interface MainThread {
    /** Whether the caller is on the main thread. */
    val isCurrent: Boolean

    /** Runs [action] on the main thread in a later turn. */
    fun post(action: () -> Unit)
}

/**
 * The app's [BiometricLauncher] (architecture.md §3.5; lock.md §9.2): the fingerprint prompt as the
 * process sees it, over whichever activity is there now. It lives as long as the process; `LockModule`
 * makes one and attaches it to `Keys.launcher` (`LauncherRelay`), and `KeystoreFingerprintKey` calls
 * it with the cipher it initialised.
 *
 * - **One prompt per activity, built in `onCreate`, every time** (§9.2 rule 1): `BiometricHost` hands
 *   each new activity's prompt over with [attach], and the last one attached shows the prompts and
 *   receives the callbacks. What a prompt is for, and the call that waits for its result, are kept
 *   here and not in the activity (rule 2), so a rotation or a language change with the prompt open
 *   loses nothing: the result reaches the new activity's prompt, and that prompt reports it here
 *   (lock.md §11, [L-bio case 10]).
 * - **Only while the activity is RESUMED** (rule 3): otherwise the library drops the launch without
 *   a callback, so nothing is launched and the answer is [PromptOutcome.NotShown]. The lock tries
 *   again at the next resume ([onReady]).
 * - **One at a time** (rule 4): while a prompt is open a second call is not passed on to the
 *   library, which would ignore it, and answers [PromptOutcome.NotShown].
 * - **A callback is taken only while a prompt of this launcher is open, and it ends it** (rule 8). A
 *   callback that arrives when none is open is dropped: with this version of the library the code-10
 *   error of a prompt that the system closed reaches the app only when the activity is back in front
 *   [L-bio case 4], which is the moment the next visit wants its own prompt. So that such a late
 *   callback is not taken for the answer of a new prompt, a launch that follows a prompt which was
 *   cancelled here waits a moment for the cancelled prompt's own callback first ([STALE_WAIT_MS]).
 * - **Success is not an unlock** (rule 6, I7): [succeeded] passes on `result.cryptoObject?.cipher`,
 *   never a cipher kept here, and only what `KeystoreFingerprintKey` then gets out of it counts.
 * - Nothing here locks, refunds, clears digits or cancels a prompt because an activity paused,
 *   stopped or was destroyed (lock.md §11): [detach] only forgets the activity's prompt. Only the
 *   lock cancels ([cancel]: a lock, the end of a visit), and a caller that is itself cancelled.
 *
 * [authenticate] is called on the main thread (`KeystoreFingerprintKey` switches to it); [cancel] may
 * come from any thread, because the lock ends a launch from where it stands.
 */
class PromptLauncher(private val main: MainThread) : BiometricLauncher {

    /** The prompt that is open: what it is for and the lock's number of its launch. */
    class Open internal constructor(val use: PromptUse, val launch: Int, internal val result: CompletableDeferred<PromptOutcome>)

    private val guard = Any()
    private var window: PromptWindow? = null // guarded by guard
    private var shown: Open? = null // guarded by guard
    private var stale: CompletableDeferred<Unit>? = null // guarded by guard: a cancelled prompt whose own callback has not come
    private var cancels = 0 // guarded by guard: how many times the lock has cancelled

    /**
     * Called on the main thread one turn after the activity was resumed or its window got the focus
     * back (§9.2 rule 7: "The launch is posted to the main thread and runs one turn after the
     * resume, so that a callback left over from an earlier prompt is delivered first"). The lock
     * manager opens the prompt by itself from here when the visit asks for it.
     */
    @Volatile
    var onReady: (() -> Unit)? = null

    /**
     * Called on the main thread when the activity went away for good (not for a rotation or a
     * language change): what its screens held, such as an open sheet, went with it.
     */
    @Volatile
    var onGone: (() -> Unit)? = null

    /** The prompt that is open now, or null. */
    val open: Open? get() = synchronized(guard) { shown }

    /**
     * Whether a prompt may open by itself now (§9.2 rule 7): an activity is there, it is resumed and
     * its window has the focus, so no dialog or sheet is open over the lock screen.
     */
    val canOpenByItself: Boolean
        get() {
            val now = synchronized(guard) { window } ?: return false
            return now.resumed && now.focused
        }

    /** `onCreate` of an activity: [window] is its one prompt, and from now on the one that is used. */
    fun attach(window: PromptWindow) {
        synchronized(guard) { this.window = window }
    }

    /**
     * `onDestroy` of the activity of [window]. A prompt that is open stays as it is: after a rotation
     * its result reaches the next activity's prompt. [forGood]: the activity was not destroyed to be
     * made again.
     */
    fun detach(window: PromptWindow, forGood: Boolean) {
        synchronized(guard) { if (this.window === window) this.window = null }
        if (forGood) onGone?.invoke()
    }

    /** The activity of [window] was resumed, or its window got the focus: [onReady] runs one turn later. */
    fun ready(window: PromptWindow) {
        if (synchronized(guard) { this.window } !== window) return
        main.post { onReady?.invoke() }
    }

    /** `onAuthenticationSucceeded` of the activity's prompt; [cipher] is `result.cryptoObject?.cipher`. */
    fun succeeded(cipher: Cipher?) = deliver(PromptOutcome.Succeeded(cipher))

    /** `onAuthenticationError` of the activity's prompt with [code]. */
    fun failed(code: Int) = deliver(PromptOutcome.Error(code))

    override suspend fun authenticate(cipher: Cipher, use: PromptUse, launch: Int, pinUsable: Boolean): PromptOutcome {
        val cancelsBefore = synchronized(guard) { cancels }
        awaitStale()
        val waiting = CompletableDeferred<PromptOutcome>()
        val on = synchronized(guard) {
            // The lock ended this launch while it waited here: its prompt is not shown at all.
            if (cancels != cancelsBefore) return PromptOutcome.Error(KeystoreErrors.ERROR_CANCELED)
            val now = window
            if (now == null || !now.resumed || shown != null) return PromptOutcome.NotShown
            shown = Open(use, launch, waiting)
            now
        }
        try {
            on.show(cipher, use, pinUsable)
        } catch (e: Exception) {
            // The library refused the launch: nothing is open, and the caller hears why (lock.md §14.1: TEMPORARY).
            synchronized(guard) { if (shown?.result === waiting) shown = null }
            throw e
        }
        try {
            return waiting.await()
        } finally {
            // Left without an answer: the caller was cancelled while the prompt was open.
            val abandoned = synchronized(guard) {
                if (shown?.result === waiting) {
                    shown = null
                    stale = stale ?: CompletableDeferred()
                    true
                } else {
                    false
                }
            }
            if (abandoned) dismiss()
        }
    }

    /**
     * The lock ends the launch that is outstanding (a lock, the end of a visit): the call that waits
     * for the prompt gets code 5 at once, as `cancelAuthentication()` would give it, and the prompt is
     * closed. Its own callback, when it comes, finds no prompt open and is dropped.
     */
    override fun cancel() {
        val ended = synchronized(guard) {
            cancels++
            val now = shown ?: return
            shown = null
            stale = stale ?: CompletableDeferred()
            now
        }
        ended.result.complete(PromptOutcome.Error(KeystoreErrors.ERROR_CANCELED))
        dismiss()
    }

    private fun deliver(outcome: PromptOutcome) {
        val answered = synchronized(guard) {
            val now = shown
            if (now == null) {
                // No prompt of ours is open (rule 8): dropped. It was the callback a cancelled prompt still owed.
                stale?.complete(Unit)
                stale = null
                return
            }
            shown = null
            now
        }
        answered.result.complete(outcome)
    }

    /** A prompt cancelled here still owes its callback: it is given [STALE_WAIT_MS] to come before the next launch. */
    private suspend fun awaitStale() {
        val owed = synchronized(guard) { stale } ?: return
        withTimeoutOrNull(STALE_WAIT_MS) { owed.await() }
        synchronized(guard) { if (stale === owed) stale = null }
    }

    private fun dismiss() {
        val close = {
            try {
                synchronized(guard) { window }?.dismiss()
            } catch (e: Exception) {
                // Nothing to close, or the activity is on its way out: the prompt's result is dropped either way.
            }
            Unit
        }
        if (main.isCurrent) close() else main.post(close)
    }

    companion object {
        /**
         * How long a launch waits for the callback of a prompt that was cancelled just before it. The
         * library answers a cancel within a few milliseconds while the activity is in front; when the
         * callback does not come (the activity was stopped), the launch goes ahead.
         */
        const val STALE_WAIT_MS = 500L
    }
}
