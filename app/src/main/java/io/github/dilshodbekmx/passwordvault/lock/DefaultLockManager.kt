package io.github.dilshodbekmx.passwordvault.lock

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChangeOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.CreateOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintOnOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockEngine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockSummary
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterPasswordOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.OlderPasswordAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinChangeOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.RequestOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.pin.PinBlocklist
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.data.VaultRepository
import io.github.dilshodbekmx.passwordvault.data.VaultState
import io.github.dilshodbekmx.passwordvault.settings.AppSettings
import io.github.dilshodbekmx.passwordvault.settings.OneTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's lock (architecture.md §2.2 `lock`, §3.5, §5; build plan 4.1): [LockManager] and
 * [LockStatus] over one [LockEngine], which holds every rule and every step of lock.md over the real
 * ports. This class is what the engine cannot be, because it needs the process and the screens:
 *
 * - **The process** (lock.md §5.11, §10.2). [start] runs the engine's process start and then follows
 *   `ProcessLifecycleOwner`: `ON_START` and `ON_STOP` are the engine's `appStarted` and `appStopped`,
 *   which start and end the visits of the lock screen. Nothing here listens to an activity, so a
 *   rotation or a language change keeps the digits, the charge, the visit and an open fingerprint
 *   prompt (lock.md §11).
 * - **The screens.** [state] and [status] are the engine's state and summary, case for case, read
 *   straight from the engine: whoever looks sees what the engine has decided at that moment (the
 *   fingerprint prompt's negative button is chosen from it when a prompt opens). [send] takes the
 *   intents of the lock screens on the main thread and hands each to the engine at once, in the
 *   order of the taps; the flows of setup and Settings are the suspending functions.
 * - **The fingerprint prompt by itself** (lock.md §9.2 rule 7). The engine opens it once per visit
 *   when asked (`promptIfDue`); this class asks at the right moments: when the lock screen starts to
 *   offer fingerprint, when the activity is resumed or gets the focus back ([PromptLauncher.onReady],
 *   one turn of the main thread later), and when the "Forgot master password?" sheet closes; and only
 *   while the activity is resumed, its window has the focus and nothing the screens told it about
 *   lies over the lock screen. A prompt runs in the app's scope, never in a screen's, so that a
 *   rotation cannot cancel it.
 * - **Time.** The engine decides again when asked (`refresh`): this class asks, while the app is
 *   visible, when a master-password wait is over and when a pending new master password becomes
 *   ready; and at every unlock and every return to the open app, so that Settings › App lock shows
 *   what the phone says about fingerprint now (lock.md §9.4).
 * - **After a correct master password** (architecture.md D-9b): `VaultRepository.matchesMasterPassword`
 *   with the proof of that entry, once per proof, before the caller gets the proof back (a caller
 *   that drops its proof at once, as the practice prompt does, cannot be faster). Every in-app check
 *   gets a proof of its own from the engine; the object in `LockUiState.Unlocked.proof` is never
 *   handed out for one.
 * - **"Make a backup now?"** (lock.md F12 step 2, §13.4 item 7): after a master password typed on a
 *   lock screen, when the backup reminder is on and not snoozed and some account changed or was
 *   deleted after the newest backup this app saved (architecture.md §4.3).
 * - **Setup** (lock.md §10.1): [createVault] has the engine write the default settings after the
 *   vault is proven and before the app counts as unlocked, so that the session's first decision
 *   already reads them.
 * - **"This phone can't keep a key in protected hardware"** (lock.md F18) is given as the reason of
 *   the master-password screen "the first time only": once an unlock has followed a screen that
 *   showed it, that is kept in the settings (`OneTime.NO_SECURE_HARDWARE_TOLD`).
 *
 * [send] and the callbacks of the process run on the main thread; nothing here blocks it. The
 * arrays passed in belong to the caller, which wipes them after the call.
 *
 * @param scope the app's scope (`Dispatchers.Main.immediate`): every intent, prompt and timer runs in
 *   it, and a step of the engine that has started always finishes.
 * @param io the dispatcher of the settings' writes.
 * @param prompts the fingerprint prompt of the process; `BiometricHost` gives it each activity's.
 * @param lockNow `Session.lockNow` (architecture.md §5.5: our clip cleared, a draft flushed, then
 *   [lock]), for "Finish now"; this class never calls [lock] itself.
 * @param olderCopyAnswered called when the older-copy notice was answered (lock.md §13.4 item 1):
 *   the vault's automatic writes may run again (architecture.md §5.5).
 * @param debug the test hooks of lock.md §15.3 in a build that has the debug commands, else null.
 */
class DefaultLockManager(
    private val engine: LockEngine,
    private val vault: VaultRepository,
    private val settings: AppSettings,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    val prompts: PromptLauncher,
    private val lockNow: () -> Unit,
    private val olderCopyAnswered: () -> Unit = {},
    val debug: LockDebug? = null,
) : LockManager {

    override val state: StateFlow<LockUiState> = Mapped(engine.state, ::uiStateOf)

    override val status: StateFlow<LockStatus> = Mapped(engine.summary, ::statusOf)

    /**
     * How long the last PIN unlock took from the sixth digit to the unlocked state, in milliseconds
     * of `elapsedRealtime()`; null before the first one. For the debug status (lock.md check E1,
     * acceptance A1); nothing secret.
     */
    @Volatile
    var lastPinOpenMs: Long? = null
        private set

    // ---- main thread ------------------------------------------------------------------------------

    /** The "Forgot master password?" sheet lies over the lock screen (`LockIntent.OpenForgotSheet`). */
    private var sheetOpen = false

    /**
     * The lock screen is not what the user is looking at, or not as a way in: the restore screens
     * were opened over it, or "Finish now" asked for the master-password field. No prompt opens by
     * itself until the lock screen is used again or the app has been unlocked.
     */
    private var setAside = false

    /** "Finish now" asked for a lock: when it has come, the master-password field is shown ([finishPending]). */
    private var finishAfterLock = false

    /** The app is visible: between the process-level `ON_START` and `ON_STOP`. */
    private val visible = MutableStateFlow(false)

    private var prompting = false
    private var promptAgain = false
    private var lastDigitAt: Long? = null
    private var stateProof: MasterProof? = null
    private var noSecureHardwareSeen = false
    private var started = false

    private val matching = Any()
    private var matchedProof: MasterProof? = null // guarded by matching
    private var matchJob: Job? = null // guarded by matching

    /**
     * Process start (lock.md §5.11), once: the engine reads the records and the vaults and decides
     * the first screen; from then on [processLifecycle] (`ProcessLifecycleOwner`'s) starts and ends
     * the visits. Called on the main thread when the process starts.
     */
    fun start(processLifecycle: Lifecycle) {
        check(!started) { "the lock was started already" }
        started = true
        val told = OneTime.NO_SECURE_HARDWARE_TOLD in settings.oneTime.value
        // First of all, so that it holds the lock mutex before anything else can ask for it.
        act { engine.start(noSecureHardwareTold = told) }
        prompts.onReady = ::promptIfDue
        prompts.onGone = {
            // The screens went with their activity, and whatever lay over the lock screen with them.
            sheetOpen = false
            setAside = false
        }
        scope.launch { watchState() }
        scope.launch { watchTime() }
        processLifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = cameToFront()

                override fun onStop(owner: LifecycleOwner) = wentAway()
            },
        )
    }

    override fun send(intent: LockIntent) {
        when (intent) {
            is LockIntent.Digit -> {
                if (intent.digit !in 0..9) return // the pad has no other key
                lockScreenUsed()
                lastDigitAt = clock.elapsedMs()
                act { engine.digit(intent.digit) }
            }
            LockIntent.DeleteDigit -> {
                lockScreenUsed()
                lastDigitAt = null
                act { engine.deleteDigit() }
            }
            LockIntent.ClearDigits -> {
                lockScreenUsed()
                lastDigitAt = null
                act { engine.clearDigits() }
            }
            LockIntent.UsePin -> {
                lockScreenUsed()
                act { engine.usePin() }
            }
            LockIntent.UseMasterPassword -> {
                lockScreenUsed()
                // "Try again" on "Your passwords can't be opened": the vaults are listed again.
                act { if (engine.state.value is LockState.Locked) engine.useMasterPassword() else engine.refresh() }
            }
            LockIntent.UseFingerprint -> {
                lockScreenUsed()
                lastDigitAt = null
                act { engine.useFingerprint() }
            }
            LockIntent.NotNow -> {
                lockScreenUsed()
                act { engine.notNow() }
            }
            LockIntent.CantRemember -> {
                lockScreenUsed()
                act { engine.cantRemember() }
            }
            LockIntent.OpenForgotSheet -> {
                lockScreenUsed()
                sheetOpen = true
            }
            LockIntent.CloseSheet -> {
                lockScreenUsed()
                sheetOpen = false
                promptIfDue()
            }
            is LockIntent.OpenAside -> {
                lockScreenUsed()
                act { engine.showAside(intent.vaultId) }
            }
            LockIntent.StartEmpty -> {
                lockScreenUsed()
                act { engine.startEmpty() }
            }
            // The screen that sent it opens the restore screens over the lock screen; the restore itself
            // comes back as createVault(first, again, restoreFrom).
            LockIntent.RestoreFromFile -> setAside = true
            is LockIntent.OlderPasswordAnswer -> {
                lockScreenUsed()
                act { engine.olderPasswordAnswer(answerOf(intent.choice)) }
            }
            is LockIntent.NoticeAnswered -> {
                val olderCopy = (engine.state.value as? LockState.Unlocked)?.notices?.any { it.id == intent.id && it is Notice.OlderCopy } == true
                act {
                    engine.answerNotice(intent.id, intent.answer)
                    if (olderCopy) olderCopyAnswered()
                }
            }
            LockIntent.CancelPending -> act { engine.cancelPending() }
            LockIntent.FinishPending -> finishPending()
            LockIntent.ItWasntMe -> {
                val old = engine.summary.value.replaced?.oldVaultId ?: return
                act { engine.itWasntMe(old) }
            }
            LockIntent.GoBack -> {
                lockScreenUsed()
                act { engine.goBack() }
            }
        }
    }

    override suspend fun lock(reason: LockReason) {
        // The merge of sub-wave 4A passes `reason` to the engine, value for value (build plan §12 item 8, decision 2).
        engine.lock()
    }

    override suspend fun submitMasterPassword(chars: CharArray, purpose: MpPurpose): MpResult =
        when (engine.state.value) {
            is LockState.Unlocked -> {
                // A prompt of the open app: checked against the open vault's own slot, with a proof of its own.
                val outcome = engine.checkMasterPassword(chars)
                if (outcome is MasterPasswordOutcome.Right) matchOnce(outcome.proof).join()
                mpResultOf(outcome)
            }
            is LockState.Locked -> {
                if (purpose != MpPurpose.UNLOCK) {
                    MpResult.TooLate // a step of the open app, and the app is locked
                } else {
                    val outcome = engine.unlockWithMasterPassword(chars)
                    if (outcome is MasterPasswordOutcome.Right) {
                        matchOnce(outcome.proof).join()
                        offerBackupIfDue()
                    }
                    mpResultOf(outcome)
                }
            }
            is LockState.Unreadable -> {
                if (purpose == MpPurpose.UNLOCK) engine.refresh() // "Try again"
                MpResult.TooLate
            }
            LockState.Starting, is LockState.NoVault -> MpResult.TooLate // no vault to check against
        }

    override suspend fun createVault(first: CharArray, again: CharArray, restoreFrom: Vault?): CreateResult =
        when (val outcome = engine.createVault(first, again, restoreFrom, created = ::writeDefaults)) {
            is CreateOutcome.Created -> CreateResult.Created(outcome.vaultId)
            CreateOutcome.Mismatch -> CreateResult.Mismatch
            is CreateOutcome.Unassigned -> CreateResult.Unassigned(outcome.codePoint)
            CreateOutcome.NoMemory -> CreateResult.NoMemory
            CreateOutcome.NotSaved -> CreateResult.NotSaved
        }

    override fun pinProblem(pin: ByteArray): PinBlocklist.Reason? = PinBlocklist.reason(pin)

    override suspend fun setPin(pin: ByteArray, proof: MasterProof): Boolean = engine.setPin(pin, proof)

    override suspend fun changePin(current: ByteArray, new: ByteArray): PinChange =
        when (val outcome = engine.changePin(current, new)) {
            PinChangeOutcome.Changed -> PinChange.Changed
            is PinChangeOutcome.Wrong -> PinChange.Wrong(outcome.left)
            PinChangeOutcome.SameWrongAgain -> PinChange.SameWrongAgain
            PinChangeOutcome.Limit -> PinChange.Limit
            PinChangeOutcome.CannotCheck -> PinChange.CannotCheck
            PinChangeOutcome.SameAsCurrent -> PinChange.SameAsCurrent
            PinChangeOutcome.NotSaved -> PinChange.NotSaved
        }

    override suspend fun fingerprintOn(proof: MasterProof): FingerprintOnResult =
        when (engine.fingerprintOn(proof)) {
            FingerprintOnOutcome.On -> FingerprintOnResult.On
            FingerprintOnOutcome.Cancelled -> FingerprintOnResult.Cancelled
            FingerprintOnOutcome.NoneEnrolled -> FingerprintOnResult.NoneEnrolled
            FingerprintOnOutcome.NoSecureHardware -> FingerprintOnResult.NoSecureHardware
            FingerprintOnOutcome.Failed -> FingerprintOnResult.Failed
            FingerprintOnOutcome.NotShown -> FingerprintOnResult.NotShown
        }

    override suspend fun fingerprintOff() = engine.fingerprintOff()

    override suspend fun changeMasterPassword(proof: MasterProof, first: CharArray, again: CharArray, pin: ByteArray?): ChangeResult =
        when (val outcome = engine.changeMasterPassword(proof, first, again, pin)) {
            is ChangeOutcome.Changed -> ChangeResult.Changed(outcome.pinKept, outcome.fingerprintKept)
            ChangeOutcome.Mismatch -> ChangeResult.Mismatch
            is ChangeOutcome.Unassigned -> ChangeResult.Unassigned(outcome.codePoint)
            is ChangeOutcome.PinWrong -> ChangeResult.PinWrong(outcome.left)
            ChangeOutcome.NoMemory -> ChangeResult.NoMemory
            ChangeOutcome.NothingChanged -> ChangeResult.NothingChanged
            ChangeOutcome.Unclear -> ChangeResult.Unclear
        }

    override suspend fun requestNewMasterPassword(first: CharArray, again: CharArray): RequestResult =
        when (val outcome = engine.requestNewMasterPassword(first, again)) {
            is RequestOutcome.Requested -> RequestResult.Requested(outcome.remainingMs)
            RequestOutcome.Mismatch -> RequestResult.Mismatch
            is RequestOutcome.Unassigned -> RequestResult.Unassigned(outcome.codePoint)
            RequestOutcome.Cancelled -> RequestResult.Cancelled
            RequestOutcome.NotAllowed -> RequestResult.NotAllowed
            RequestOutcome.NoMemory -> RequestResult.NoMemory
            RequestOutcome.NotSaved -> RequestResult.NotSaved
        }

    override fun cancelNewMasterPassword() = act { engine.cancelPending() }

    override suspend fun openAside(vaultId: String, chars: CharArray): AsideResult = asideResultOf(engine.mergeAside(vaultId, chars))

    override suspend fun deleteAside(vaultId: String, chars: CharArray): AsideResult = asideResultOf(engine.deleteAside(vaultId, chars))

    // ---------------------------------------------------------------------------------------------
    // The process (lock.md §10.2)

    private fun cameToFront() {
        visible.value = true
        act {
            engine.appStarted()
            // The phone's settings may have changed while the app was away (a finger added, lock.md §9.4).
            if (engine.state.value is LockState.Unlocked) engine.refresh()
        }
    }

    private fun wentAway() {
        visible.value = false
        act { engine.appStopped() }
    }

    /**
     * Hands a step to the engine at once: it runs on the calling thread up to the engine's first
     * suspension, so that what must happen at the moment of a signal happens then (a key press is
     * stamped with its entry, `ON_STOP` ends the visit), and the steps reach the lock mutex in the
     * order they were sent.
     */
    private fun act(step: suspend () -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { step() }
    }

    // ---------------------------------------------------------------------------------------------
    // What follows the engine's state

    private suspend fun watchState() {
        var before: LockState? = null
        engine.state.collect { now ->
            stateChanged(before, now)
            before = now
        }
    }

    private fun stateChanged(before: LockState?, now: LockState) {
        // A sheet belongs to the lock screen it was opened on; the screens close it without a word.
        if (now !is LockState.Locked) sheetOpen = false
        // The lock screen was left behind: whatever lay over it is gone.
        if ((now is LockState.Unlocked || now is LockState.NoVault) && (before is LockState.Locked || before is LockState.Unreadable)) setAside = false
        val proof = (now as? LockState.Unlocked)?.proof
        if (proof !== stateProof) {
            stateProof = proof
            // D-9b for an entry that left its proof in the state: a master-password unlock, setup, a restore.
            if (proof != null) matchOnce(proof)
        }
        when (now) {
            is LockState.Unlocked -> if (before !is LockState.Unlocked) unlocked(before)
            is LockState.Locked -> {
                if (finishAfterLock) {
                    // The lock that "Finish now" asked for: the field that takes the new master password (F11 step 13).
                    finishAfterLock = false
                    act { engine.useMasterPassword() }
                }
                val screen = now.screen
                if (screen is LockScreen.MasterPassword && screen.reason == MpReason.NoSecureHardware) noSecureHardwareSeen = true
                if (offersFingerprint(now) && !offersFingerprint(before)) promptIfDue()
            }
            LockState.Starting, is LockState.NoVault, is LockState.Unreadable -> Unit
        }
        if (now !is LockState.Locked) lastDigitAt = null
    }

    /** The app has just been unlocked, from [before]. */
    private fun unlocked(before: LockState?) {
        // The last key before a PIN opened the app was its sixth digit.
        if ((before as? LockState.Locked)?.screen is LockScreen.Pad) lastDigitAt?.let { lastPinOpenMs = clock.elapsedMs() - it }
        // What the phone says about fingerprint now, for Settings › App lock (lock.md §9.4), and the credits (§6.9).
        act { engine.refresh() }
        if (noSecureHardwareSeen) {
            // F18, "first time only": its reason was on the screen that this unlock followed.
            noSecureHardwareSeen = false
            scope.launch(io) {
                try {
                    settings.update { it.oneTime = it.oneTime + OneTime.NO_SECURE_HARDWARE_TOLD }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Not kept: the reason is given once more in the next process.
                }
            }
            act { engine.noSecureHardwareShown() }
        }
    }

    /** An intent that only a lock screen sends arrived: the lock screen is what the user is using. */
    private fun lockScreenUsed() {
        setAside = false
    }

    // ---------------------------------------------------------------------------------------------
    // The fingerprint prompt by itself (lock.md §9.2 rule 7)

    /**
     * Asks the engine for the prompt that opens by itself, when the lock screen shown offers
     * fingerprint, the activity is resumed with the focus, and no sheet or other screen lies over the
     * lock screen. The engine opens it once per visit and ignores every other call. A call that
     * arrives while one is running is made again when that one has returned: the first may have
     * looked before the screen offered fingerprint.
     */
    private fun promptIfDue() {
        if (!mayPrompt()) return
        if (prompting) {
            promptAgain = true
            return
        }
        prompting = true
        scope.launch {
            try {
                do {
                    promptAgain = false
                    engine.promptIfDue()
                } while (promptAgain && mayPrompt())
            } finally {
                prompting = false
            }
        }
    }

    private fun mayPrompt(): Boolean = !sheetOpen && !setAside && offersFingerprint(engine.state.value) && prompts.canOpenByItself

    // ---------------------------------------------------------------------------------------------
    // "Finish now" (lock.md F11 step 13)

    /**
     * "Finish now" on the list's banner, which opens no screen of its own: the new master password is
     * typed where a master password is typed, on the lock screen, whose field accepts it (lock.md
     * F11 step 13: "The lock screen's master-password field accepts it as well"). So the app is
     * locked as "Lock now" locks it, through the session ([lockNow]: architecture.md §5.5), and when
     * the lock has come the master-password screen is shown, with no fingerprint prompt over it; the
     * new master password then finishes (steps 14 to 19), the old one cancels the request (step 9),
     * and "Use PIN" leads back as on any lock screen. Only while a new master password is ready.
     *
     * The contract has no way yet to type the new master password in the open app (the engine's
     * `finishPending`, a prompt over the list); build plan 5.5 may add one, and this intent would
     * then open it instead.
     */
    private fun finishPending() {
        if (engine.state.value !is LockState.Unlocked || engine.summary.value.pending?.ready != true) return
        setAside = true
        finishAfterLock = true
        lockNow()
    }

    // ---------------------------------------------------------------------------------------------
    // After a correct master password

    /**
     * architecture.md D-9b: the entries whose password is the master password of [proof], worked
     * out and stored by the repository, once for each proof. The job is the one that does it, so a
     * caller that hands the proof on waits for it first: the repository needs the proof's bytes, and
     * the receiver may drop the proof at once.
     */
    private fun matchOnce(proof: MasterProof): Job {
        val job = synchronized(matching) {
            val running = matchJob
            if (running != null && matchedProof === proof) return running
            matchedProof = proof
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    vault.matchesMasterPassword(proof)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The list of matches is left as it was; the next correct master password makes it again.
                }
            }.also { matchJob = it }
        }
        job.start()
        return job
    }

    /** lock.md F12 step 2: "Make a backup now?" on the entry just made on the lock screen, when a backup is due. */
    private suspend fun offerBackupIfDue() {
        val open = vault.state.value as? VaultState.Unlocked ?: return
        val reminder = settings.backupReminder.value
        if (!reminder.on || reminder.snoozedUntilMs?.let { clock.wallMs() < it } == true) return
        if (backupDue(open.vault)) engine.offerBackup()
    }

    /** lock.md §10.1: setup writes its defaults, off the main thread; a failure leaves the strictest values. */
    private suspend fun writeDefaults() {
        withContext(io) { settings.writeDefaults() }
    }

    // ---------------------------------------------------------------------------------------------
    // Time (the engine decides again when asked)

    /**
     * While the app is visible, asks the engine again at the moment something it shows changes by
     * time alone: the end of a master-password wait on the lock screen (lock.md §6.8), and the
     * moment a pending new master password becomes ready (F11 step 13; its credit runs with this
     * clock while the phone is on, §6.9). Nothing wakes while the app is not visible: every return
     * to it asks anyway.
     */
    private suspend fun watchTime() {
        // Each deadline is worked out once, when its state or summary arrives, because what the engine
        // gives is a time from then on ("ready in N ms").
        val onLockScreen = engine.state.map { state ->
            val field = (state as? LockState.Locked)?.screen as? LockScreen.MasterPassword
            val now = clock.elapsedMs()
            listOfNotNull(
                field?.waitUntilElapsedMs?.takeIf { it > now },
                field?.pendingReadyInMs?.takeIf { it > 0 }?.let { now + it },
            ).minOrNull()
        }
        val inOpenApp = engine.summary.map { summary ->
            summary.pending?.takeIf { !it.ready && it.remainingMs > 0 }?.let { clock.elapsedMs() + it.remainingMs }
        }
        combine(engine.state, onLockScreen, inOpenApp, visible) { state, locked, open, shown ->
            when {
                !shown -> null
                state is LockState.Locked -> locked
                state is LockState.Unlocked -> open
                else -> null
            }
        }.distinctUntilChanged().collectLatest { at ->
            if (at != null) {
                clock.delayUntilElapsed(at)
                engine.refresh()
            }
        }
    }

    private companion object {
        /** Whether the lock screen of [state] shows fingerprint as a way in: the pad with its fingerprint key, or the fingerprint-only screen. */
        fun offersFingerprint(state: LockState?): Boolean = when (val screen = (state as? LockState.Locked)?.screen) {
            is LockScreen.Pad -> screen.fingerprintKey
            is LockScreen.FingerprintOnly -> true
            else -> false
        }

        /**
         * architecture.md §4.3: a backup is due when some account changed or was deleted after the
         * newest backup this app saved; with no backup, as soon as there is an account.
         */
        fun backupDue(vault: Vault): Boolean {
            if (vault.entries.isEmpty()) return false
            val newest = vault.backups.maxOfOrNull { it.savedAt } ?: return true
            return vault.entries.any { it.updatedAt > newest || (it.deletedAt ?: Long.MIN_VALUE) > newest }
        }

        fun uiStateOf(state: LockState): LockUiState = when (state) {
            LockState.Starting -> LockUiState.Starting
            is LockState.NoVault -> LockUiState.NoVault(state.aside)
            is LockState.Unreadable -> LockUiState.Unreadable(state.why, state.aside)
            is LockState.Locked -> LockUiState.Locked(state.screen)
            is LockState.Unlocked -> LockUiState.Unlocked(state.notices, state.proof)
        }

        fun statusOf(summary: LockSummary): LockStatus = LockStatus(
            pin = when (val pin = summary.pin) {
                LockSummary.Pin.On -> PinStatus.On
                LockSummary.Pin.NotSet -> PinStatus.NotSet
                is LockSummary.Pin.Off -> PinStatus.Off(pin.reason)
                LockSummary.Pin.CannotCheck -> PinStatus.CannotCheck
                LockSummary.Pin.NoSecureHardware -> PinStatus.NoSecureHardware
            },
            fingerprint = when (val fingerprint = summary.fingerprint) {
                LockSummary.Fingerprint.On -> FingerprintStatus.On
                LockSummary.Fingerprint.Off -> FingerprintStatus.Off
                LockSummary.Fingerprint.TurnOnAgain -> FingerprintStatus.TurnOnAgain
                is LockSummary.Fingerprint.Unavailable -> FingerprintStatus.Unavailable(fingerprint.canAuthenticate)
                LockSummary.Fingerprint.NoSecureHardware -> FingerprintStatus.NoSecureHardware
            },
            pending = summary.pending?.let { PendingMasterPassword(it.requestedAtMs, it.by, it.creditedMs, it.remainingMs, it.ready) },
            replaced = summary.replaced?.let { ReplacedNotice(it.atMs, it.by, it.oldVaultId) },
            practiceOverdueSinceMs = summary.practiceOverdueSinceMs,
            aside = summary.aside,
        )

        fun mpResultOf(outcome: MasterPasswordOutcome): MpResult = when (outcome) {
            is MasterPasswordOutcome.Right -> MpResult.Right(outcome.proof)
            MasterPasswordOutcome.Wrong -> MpResult.Wrong
            is MasterPasswordOutcome.Wait -> MpResult.Wait(outcome.untilElapsedMs)
            MasterPasswordOutcome.NoMemory -> MpResult.NoMemory
            is MasterPasswordOutcome.OlderPassword -> MpResult.OlderPassword(outcome.changedAtMs, outcome.unfinishedChange)
            MasterPasswordOutcome.TooLate -> MpResult.TooLate
        }

        fun asideResultOf(outcome: AsideOutcome): AsideResult = when (outcome) {
            AsideOutcome.Done -> AsideResult.Done
            AsideOutcome.Wrong -> AsideResult.Wrong
            is AsideOutcome.Wait -> AsideResult.Wait(outcome.untilElapsedMs)
            AsideOutcome.NoMemory -> AsideResult.NoMemory
            AsideOutcome.NotSaved -> AsideResult.NotSaved
        }

        fun answerOf(choice: OlderPasswordChoice): OlderPasswordAnswer = when (choice) {
            OlderPasswordChoice.TRY_AGAIN -> OlderPasswordAnswer.TRY_AGAIN
            OlderPasswordChoice.OPEN_FROM_BEFORE -> OlderPasswordAnswer.OPEN_FROM_BEFORE
            OlderPasswordChoice.OK -> OlderPasswordAnswer.OK
            OlderPasswordChoice.OPEN_OLDER_SET -> OlderPasswordAnswer.OPEN_OLDER_SET
            OlderPasswordChoice.KEEP_NEWER_SET -> OlderPasswordAnswer.KEEP_NEWER_SET
        }
    }

    /**
     * [source] seen through [transform], as a `StateFlow` whose value is always the transform of the
     * source's value now: there is no second copy that could lag behind the engine. The last pair is
     * kept, so that the same source value gives the same object.
     */
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class Mapped<T, R>(private val source: StateFlow<T>, private val transform: (T) -> R) : StateFlow<R> {
        @Volatile private var last: Pair<T, R>? = null

        override val value: R
            get() {
                val now = source.value
                last?.let { if (it.first === now) return it.second }
                return transform(now).also { last = now to it }
            }

        override val replayCache: List<R> get() = listOf(value)

        override suspend fun collect(collector: FlowCollector<R>): Nothing {
            var emitted = false
            var previous: R? = null
            source.collect { now ->
                val mapped = last?.takeIf { it.first === now }?.second ?: transform(now).also { last = now to it }
                if (!emitted || previous != mapped) {
                    emitted = true
                    previous = mapped
                    collector.emit(mapped)
                }
            }
        }
    }
}
