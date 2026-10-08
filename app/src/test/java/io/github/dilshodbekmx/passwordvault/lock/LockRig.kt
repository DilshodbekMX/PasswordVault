package io.github.dilshodbekmx.passwordvault.lock

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.github.dilshodbekmx.passwordvault.core.crypto.CryptoFactory
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintKey
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintResult
import io.github.dilshodbekmx.passwordvault.core.lock.flow.KeyCreate
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockEngine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.NoticeAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinKey
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PromptUse
import io.github.dilshodbekmx.passwordvault.core.lock.policy.KeystoreErrors
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.core.lock.record.FileRecordStore
import io.github.dilshodbekmx.passwordvault.core.lock.record.FingerprintRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordFiles
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.core.model.Draft
import io.github.dilshodbekmx.passwordvault.core.model.Edited
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.data.BackupResult
import io.github.dilshodbekmx.passwordvault.data.SaveProblem
import io.github.dilshodbekmx.passwordvault.data.SaveResult
import io.github.dilshodbekmx.passwordvault.data.VaultRepository
import io.github.dilshodbekmx.passwordvault.data.VaultState
import io.github.dilshodbekmx.passwordvault.fake.FakeAppSettings
import io.github.dilshodbekmx.passwordvault.settings.AppSettings
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeKdf
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeKeys
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordFiles
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeVaultPort
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * The process lifecycle as `ProcessLifecycleOwner` reports it (lock.md §10.2), driven by the test:
 * [toFront] is the first activity started, [away] the moment 700 ms after the last one paused. A
 * recreation of the activity (a rotation, a language change) reaches none of it.
 */
class TestProcess : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.CREATED }

    override val lifecycle: Lifecycle get() = registry

    fun toFront() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun away() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }
}

/** The main thread of a test: the test's own thread, with [post]ed actions kept until [runPosted]. */
class TestMain : MainThread {
    private val posted = ArrayDeque<() -> Unit>()

    override val isCurrent: Boolean get() = true

    override fun post(action: () -> Unit) {
        posted += action
    }

    /** Runs what was posted, and what that posted; true when anything ran. */
    fun runPosted(): Boolean {
        var ran = false
        while (true) {
            val next = posted.removeFirstOrNull() ?: return ran
            next()
            ran = true
        }
    }
}

/**
 * One activity's fingerprint prompt, for [PromptLauncher]: what was shown is in [shows], the test
 * answers through the launcher (`succeeded`, `failed`), and [dismissals] counts
 * `cancelAuthentication()`. A fresh one is a fresh activity ([LockRig.rotate]).
 */
class FakeWindow : PromptWindow {
    /** One `authenticate()` call. */
    class Show(val cipher: Cipher, val use: PromptUse, val pinUsable: Boolean)

    override var resumed: Boolean = true
    override var focused: Boolean = true

    val shows = mutableListOf<Show>()
    var dismissals = 0
        private set

    /** When set, `authenticate()` throws it, as the library does for a launch it refuses. */
    var showFails: RuntimeException? = null

    override fun show(cipher: Cipher, use: PromptUse, pinUsable: Boolean) {
        showFails?.let { throw it }
        shows += Show(cipher, use, pinUsable)
    }

    override fun dismiss() {
        dismissals++
    }
}

/**
 * The fingerprint key of the tests: the software keys of [FakeKeys] behind the real prompt path, as
 * `KeystoreFingerprintKey` has it. A cipher is initialised with the key, [launcher] shows the prompt
 * with it, and only the cipher that the prompt hands back does the key operation, classified by the
 * table of lock.md §14.3 (`KeystoreErrors.respond`). Making, probing and `canAuthenticate` are
 * [FakeKeys]' own, with its scripts.
 */
class PromptedFingerprintKey(
    private val keys: FakeKeys,
    private val launcher: BiometricLauncher,
    private val pinUsable: () -> Boolean,
) : FingerprintKey {
    override fun availability(): Int = keys.fingerprint.availability()

    override fun create(alias: String): KeyCreate = keys.fingerprint.create(alias)

    override fun probe(alias: String, nonce: ByteArray): Ks<Unit> = keys.fingerprint.probe(alias, nonce)

    override suspend fun seal(alias: String, vk: ByteArray, use: PromptUse, launch: Int): FingerprintResult {
        val key = keys.fingerprintKeyOf(alias) ?: return FingerprintResult.Gone(KeystoreErrors.GONE_NOT_FOUND)
        val cipher = Cipher.getInstance(AES_GCM).apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
        return behindPrompt(cipher, use, launch) { authorised -> FingerprintResult.Ok(authorised.doFinal(vk), authorised.iv) }
    }

    override suspend fun open(alias: String, nonce: ByteArray, ct: ByteArray, use: PromptUse, launch: Int): FingerprintResult {
        val key = keys.fingerprintKeyOf(alias) ?: return FingerprintResult.Gone(KeystoreErrors.GONE_NOT_FOUND)
        val cipher = Cipher.getInstance(AES_GCM).apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) }
        return behindPrompt(cipher, use, launch) { authorised ->
            try {
                FingerprintResult.Ok(authorised.doFinal(ct), nonce.copyOf())
            } catch (e: GeneralSecurityException) {
                FingerprintResult.Gone(KeystoreErrors.GONE_BAD_WRAP)
            }
        }
    }

    override fun cancel() = launcher.cancel()

    private suspend fun behindPrompt(cipher: Cipher, use: PromptUse, launch: Int, operation: (Cipher) -> FingerprintResult): FingerprintResult {
        val outcome = try {
            launcher.authenticate(cipher, use, launch, pinUsable())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return FingerprintResult.Temporary(null)
        }
        return when (outcome) {
            PromptOutcome.NotShown -> FingerprintResult.NotShown
            is PromptOutcome.Succeeded -> outcome.cipher?.let(operation) ?: FingerprintResult.Temporary(null)
            is PromptOutcome.Error -> when (KeystoreErrors.respond(KeystoreErrors.Callback.Error(outcome.code), launchOutstanding = true)) {
                KeystoreErrors.Response.Closed -> FingerprintResult.Closed(outcome.code)
                else -> FingerprintResult.Temporary(outcome.code)
            }
        }
    }

    private companion object {
        const val AES_GCM = "AES/GCM/NoPadding"
    }
}

/**
 * The screens' side of the vault, as far as the lock manager uses it: [state] is what a test shows
 * as the open vault, and [matched] lists every `matchesMasterPassword` call with the vault of its
 * proof and whether the proof still lent its bytes then (architecture.md D-9b).
 */
class RecordingVault : VaultRepository {
    /** One `matchesMasterPassword` call. */
    class Match(val proof: MasterProof, val bytesLent: Boolean)

    override val state = MutableStateFlow<VaultState>(VaultState.Locked)

    val matched = mutableListOf<Match>()

    /** When set, `matchesMasterPassword` throws it. */
    var matchFails: Exception? = null

    /** When set, `matchesMasterPassword` waits for it after it has taken the bytes, as a save of the vault that is still running. */
    var matchRuns: CompletableDeferred<Unit>? = null

    /** The open vault as the screens see it. */
    fun show(vault: Vault) {
        state.value = VaultState.Unlocked(vault, 1L, null, false)
    }

    override suspend fun matchesMasterPassword(proof: MasterProof): Set<String> {
        val lent = try {
            proof.useBytes { it.isNotEmpty() }
        } catch (e: IllegalStateException) {
            false
        }
        matched += Match(proof, lent)
        matchRuns?.await()
        matchFails?.let { throw it }
        return emptySet()
    }

    override suspend fun <T> edit(change: (Vault, nowMs: Long) -> Edited<T>): SaveResult<T> = SaveResult.NotSaved(SaveProblem.FAILED)

    override fun holdDraft(draft: Draft?) = Unit

    override suspend fun flushDraft() = Unit

    override suspend fun sealBackup(proof: MasterProof): ByteArray = throw UnsupportedOperationException("not used by the lock")

    override suspend fun openBackup(bytes: ByteArray, passwordBytes: ByteArray): BackupResult = throw UnsupportedOperationException("not used by the lock")

    override fun olderCopyAnswered() = Unit // the manager's own hook is counted (LockRig.olderCopyAnswers)
}

/** [AppSettings] that writes "writeDefaults while <state>" into [log] when the defaults are written. */
class LoggingSettings(private val inner: AppSettings, private val log: MutableList<String>, private val state: () -> LockUiState) :
    AppSettings by inner {
    override fun writeDefaults(): Boolean {
        log += "writeDefaults while ${state()::class.simpleName}"
        return inner.writeDefaults()
    }
}

/**
 * One phone and one process for the tests of [DefaultLockManager] (build plan 4.1, [JVM] "with fake
 * ports"): the lock engine over the fakes of `:testing` (the lock directories [files], the Keystore
 * [keys], the vaults [port], both clocks [clock]), the real [PromptLauncher] with a [FakeWindow] for
 * the activity, and the manager as `LockModule` builds it, on the test's scheduler.
 *
 * - [clock] moves only when the test advances it ([advance]), so a wait of the engine (the second a
 *   charge must age, lock.md §6.3 E) stands until then.
 * - [settle] runs everything the last events queued, the main thread's posted turns among them.
 * - [restart] is a new process over the same phone; [rotate] a new activity in the same process.
 * - An exception that nobody caught in the app's scope is kept in [crashes] (in the app it would end
 *   the process).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LockRig(val test: TestScope, seed: Long = 1L) {
    val clock = FakeClock(elapsedMs = 3_600_000L, autoAdvance = false)
    val files = FakeRecordFiles(clock)
    val keys = FakeKeys(FakeRandom(seed + 5), now = clock::elapsedMs)
    val port = FakeVaultPort(clock, FakeRandom(seed + 11))
    val kdf = FakeKdf()
    val vault = RecordingVault()
    val log = mutableListOf<String>()
    val baseSettings = FakeAppSettings()
    val settings: AppSettings = LoggingSettings(baseSettings, log) { made?.state?.value ?: LockUiState.Starting }
    val crashes = mutableListOf<Throwable>()
    private val random = FakeRandom(seed + 21)

    /** What the engine is given for these two ports; a test that wraps one sets it before [start]. */
    var recordFiles: RecordFiles = files
    var pinKey: PinKey = keys.pin

    /** The test hooks of lock.md §15.3, when the lock is to be built with them as a test build's is; set before [start]. */
    var hooks: LockDebugImpl? = null

    var olderCopyAnswers = 0
        private set

    /** How many times the manager asked the session to lock as "Lock now" does ("Finish now"). */
    var lockNows = 0
        private set

    var process = TestProcess()
        private set
    var main = TestMain()
        private set
    var window = FakeWindow()
        private set
    lateinit var launcher: PromptLauncher
        private set
    lateinit var engine: LockEngine
        private set
    private var made: DefaultLockManager? = null
    private var processJob: Job? = null

    val manager: DefaultLockManager get() = made ?: error("start() first")

    /** Process start: the lock is built and started as `LockModule` does it; the activity is created, not yet in front. */
    fun start(): DefaultLockManager {
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        // A supervisor, as the app's scope is: a step that fails ends alone, and its error goes to the handler below.
        val job = SupervisorJob(test.backgroundScope.coroutineContext.job)
        processJob = job
        val scope = CoroutineScope(test.backgroundScope.coroutineContext + job + CoroutineExceptionHandler { _, e -> crashes += e })
        process = TestProcess()
        main = TestMain()
        window = FakeWindow()
        launcher = PromptLauncher(main)
        val fingerprintKey = PromptedFingerprintKey(keys, launcher) {
            val shown = made?.state?.value
            shown is LockUiState.Locked && shown.screen is LockScreen.Pad
        }
        val hooks = hooks
        engine = LockEngine(
            hooks?.records(recordFiles) ?: recordFiles,
            ROOT,
            clock,
            random,
            hooks?.pinKey(pinKey) ?: pinKey,
            fingerprintKey,
            keys.device,
            port,
            kdf,
            CryptoFactory.aead(),
            dispatcher,
        )
        val built = DefaultLockManager(
            engine,
            vault,
            settings,
            clock,
            scope,
            dispatcher,
            launcher,
            // As `AutoLock.lockNow`: the lock runs in the session's own coroutine, after its draft flush.
            lockNow = {
                lockNows++
                scope.launch { made?.lock(LockReason.LOCK_NOW) }
            },
            olderCopyAnswered = { olderCopyAnswers++ },
            debug = hooks,
        )
        hooks?.attach(engine, built)
        made = built
        launcher.attach(window)
        built.start(process.lifecycle)
        return built
    }

    /** Process start with the app coming to the front right after it, settled. */
    fun startInFront(): DefaultLockManager = start().also {
        toFront()
    }

    /** A new process over the same phone: nothing in memory survives; the files and the keys do. */
    fun restart(): DefaultLockManager {
        processJob?.cancel()
        files.kill()
        port.restart()
        vault.state.value = VaultState.Locked
        return start()
    }

    /** The app comes to the front: `ON_START`, the activity resumed with the focus. */
    fun toFront() {
        process.toFront()
        window.resumed = true
        window.focused = true
        launcher.ready(window)
        settle()
    }

    /** The app is left: the activity paused and stopped, then the process-level `ON_STOP`. */
    fun away() {
        window.resumed = false
        window.focused = false
        process.away()
        settle()
    }

    /** A rotation or a language change: the activity is destroyed and a new one created and resumed, in one turn (lock.md §11). */
    fun rotate() {
        val old = window
        old.resumed = false
        launcher.detach(old, forGood = false)
        window = FakeWindow()
        launcher.attach(window)
        launcher.ready(window)
        settle()
    }

    /** Runs every task the last events queued, until nothing is left to run now. */
    fun settle() {
        do {
            test.testScheduler.runCurrent()
        } while (main.runPosted())
        test.testScheduler.runCurrent()
    }

    /** Time passes on both clocks, and what waited for it runs. */
    fun advance(ms: Long) {
        clock.advance(ms)
        test.testScheduler.advanceTimeBy(ms)
        settle()
    }

    // ---- what is shown ----------------------------------------------------------------------------

    val state: LockUiState get() = manager.state.value
    val status: LockStatus get() = manager.status.value
    val screen: LockScreen get() = (state as LockUiState.Locked).screen
    val pad: LockScreen.Pad get() = screen as LockScreen.Pad
    val field: LockScreen.MasterPassword get() = screen as LockScreen.MasterPassword
    val unlocked: LockUiState.Unlocked get() = state as LockUiState.Unlocked
    val notices: List<Notice> get() = unlocked.notices
    val isUnlocked: Boolean get() = state is LockUiState.Unlocked

    /** The prompt shown last on the activity that is there now. */
    val prompt: FakeWindow.Show get() = window.shows.last()

    // ---- the user ---------------------------------------------------------------------------------

    fun send(intent: LockIntent) {
        manager.send(intent)
        settle()
    }

    /** Presses the keys of [digits] on the pad, one after another. */
    fun press(digits: String) = digits.forEach { send(LockIntent.Digit(it - '0')) }

    /** Types the six digits of [pin] and lets the charge age, so that the check runs (lock.md §6.3 E). */
    fun typePin(pin: String = PIN) {
        press(pin)
        advance(CHARGE_AGE_MS)
    }

    /** Types [password] into the master-password field of the lock screen. */
    suspend fun unlockWith(password: String = PASSWORD): MpResult = submit(password, MpPurpose.UNLOCK)

    /** Types [password] where a master password is asked for [purpose]. */
    suspend fun submit(password: String, purpose: MpPurpose): MpResult {
        val chars = password.toCharArray()
        val result = manager.submitMasterPassword(chars, purpose)
        settle()
        return result
    }

    /** The finger [touches] the sensor while a prompt is open: the prompt succeeds with the cipher it was shown with. */
    fun touch() {
        launcher.succeeded(prompt.cipher)
        settle()
    }

    /** The open prompt ends with the error [code] (10 Back, 13 the negative button, 7 lockout…). */
    fun promptError(code: Int) {
        launcher.failed(code)
        settle()
    }

    // ---- a phone as an earlier use left it ----------------------------------------------------------

    /** Setup: the vault made with [password], in front, unlocked, with the "Choose a PIN" notice still there. */
    suspend fun setUp(password: String = PASSWORD): MasterProof {
        if (made == null) startInFront()
        val result = manager.createVault(password.toCharArray(), password.toCharArray(), null)
        settle()
        assertTrue("setup made the vault: $result", result is CreateResult.Created)
        return checkNotNull(unlocked.proof) { "setup leaves the proof of the second typing" }
    }

    /** Setup, then a PIN set with setup's proof (lock.md F1 step 6). */
    suspend fun setUpWithPin(pin: String = PIN, password: String = PASSWORD): MasterProof {
        val proof = setUp(password)
        assertEquals("the PIN was set", PinSetResult.Set, manager.setPin(digits(pin), proof))
        settle()
        return proof
    }

    /** Turns fingerprint unlock on with [proof] and an enrolled finger (lock.md F9). */
    suspend fun fingerprintOn(proof: MasterProof) {
        val turnOn = test.backgroundScope.asyncOn { manager.fingerprintOn(proof) }
        settle()
        assertEquals("the prompt of F9 is open", PromptUse.TURN_ON, prompt.use)
        touch()
        assertEquals(FingerprintOnResult.On, turnOn.getCompleted())
    }

    /** Answers every notice that is shown with [answer], the first first. */
    fun answerNotices(answer: NoticeAnswer = NoticeAnswer.LATER) {
        while (isUnlocked && notices.isNotEmpty()) send(LockIntent.NoticeAnswered(notices.first().id, answer))
    }

    /** Locks as `AutoLock` does, for [reason]. */
    suspend fun lock(reason: LockReason = LockReason.LOCK_NOW) {
        manager.lock(reason)
        settle()
    }

    // ---- the flows that package 4.4 fills ------------------------------------------------------------

    /**
     * Whether the last steps ended in a flow that build plan 4.4 has not filled yet (`PasswordFlows`
     * and `OlderCopies` throw `NotImplementedError` until then, and this branch is built beside 4.4):
     * the intent then reached the engine's entry point, which is all that can be shown here, and the
     * test stops. Once 4.4 is merged this is false, and the test goes on to what the flow does.
     * [what] is a part of the stub's own message, so that only the expected flow counts.
     */
    fun reached44(what: String): Boolean {
        val stubs = crashes.filter { isStubOf44(it, what) }
        crashes.removeAll(stubs)
        return stubs.isNotEmpty()
    }

    // ---- what is on disk --------------------------------------------------------------------------

    val vaultId: String get() = checkNotNull(port.current) { "no current vault" }.vaultId

    fun dirOf(vaultId: String): String = FileRecordStore.dir(ROOT, vaultId)

    fun pinOnDisk(vaultId: String = this.vaultId): RecordRead<PinRecord> = FileRecordStore.pin(files, ROOT, vaultId).read()

    fun fingerprintOnDisk(vaultId: String = this.vaultId): RecordRead<FingerprintRecord> = FileRecordStore.fingerprint(files, ROOT, vaultId).read()

    fun stateOnDisk(vaultId: String = this.vaultId): RecordRead<StateRecord> = FileRecordStore.state(files, ROOT, vaultId).read()

    fun pinRecord(vaultId: String = this.vaultId): PinRecord = (pinOnDisk(vaultId) as RecordRead.Ok).record

    fun stateRecord(vaultId: String = this.vaultId): StateRecord = (stateOnDisk(vaultId) as RecordRead.Ok).record

    /** Puts [record] on disk as `state.rec` of its vault, as an earlier process left it. */
    fun putState(record: StateRecord) = files.put(dirOf(record.vaultId), RecordCodec.State.fileName, RecordCodec.State.encode(record))

    companion object {
        const val ROOT = "/data/user/0/app/no_backup/lock"
        const val PASSWORD = "correct horse battery staple"
        const val OTHER_PASSWORD = "Toshkent kuzi 2026 oltin barglar"
        const val PIN = "582913"
        const val WRONG_PIN = "582914"
        const val OTHER_WRONG_PIN = "143637"

        /** The age a charge must have before its PIN is checked (lock.md §6.2). */
        const val CHARGE_AGE_MS = 1_000L

        const val DAY_MS = 86_400_000L

        fun digits(pin: String): ByteArray = pin.toByteArray(Charsets.US_ASCII)

        /** Whether [error] is the `NotImplementedError` of a flow of build plan 4.4 whose message names [what]. */
        fun isStubOf44(error: Throwable, what: String): Boolean =
            error is NotImplementedError && error.message.orEmpty().let { "build plan 4.4" in it && what in it }
    }
}

/** `async` that starts at once on the calling thread, as a screen's own scope does (`Dispatchers.Main.immediate`). */
fun <T> CoroutineScope.asyncOn(block: suspend () -> T): Deferred<T> {
    val result = CompletableDeferred<T>()
    launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            result.complete(block())
        } catch (e: Throwable) {
            result.completeExceptionally(e)
            if (e is CancellationException) throw e
        }
    }
    return result
}
