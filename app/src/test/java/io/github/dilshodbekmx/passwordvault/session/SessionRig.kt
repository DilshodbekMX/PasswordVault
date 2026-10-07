package io.github.dilshodbekmx.passwordvault.session

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.github.dilshodbekmx.passwordvault.fake.FakeAppSettings
import io.github.dilshodbekmx.passwordvault.fake.FakeSecretClipboard
import io.github.dilshodbekmx.passwordvault.lock.CreateResult
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.lock.LockReason
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.MasterPasswordGate
import io.github.dilshodbekmx.passwordvault.lock.MpPurpose
import io.github.dilshodbekmx.passwordvault.lock.MpResult
import io.github.dilshodbekmx.passwordvault.lock.gate.GateRig
import io.github.dilshodbekmx.passwordvault.settings.AppSettings
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertTrue

/** The lock manager, with every [lock] call written into [log] ("lock(REASON)") and [reasons]. */
class RecordingLock(private val inner: LockManager, private val log: MutableList<String>) : LockManager by inner {
    val reasons = mutableListOf<LockReason>()

    override suspend fun lock(reason: LockReason) {
        reasons += reason
        log += "lock($reason)"
        inner.lock(reason)
    }
}

/**
 * An activity's own lifecycle, which nothing of the lock or the session observes: [recreate] is a
 * rotation or a language change (lock.md §11), the old activity paused, stopped and destroyed and a
 * new one created, started and resumed in one turn of the main thread. `ProcessLifecycleOwner`
 * reports none of it: its "stopped" message, posted 700 ms after the old activity paused, is
 * removed when the new one resumes (§11 item 2); the emulator checks it (build plan 3.2 [script],
 * lock.md E11).
 */
class FakeActivity : LifecycleOwner {
    private var registry = resumed()

    override val lifecycle: Lifecycle get() = registry

    private fun resumed() = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }

    fun recreate() {
        registry.currentState = Lifecycle.State.DESTROYED
        registry = resumed()
    }
}

/**
 * The session as `SessionModule` builds it, on the gate as `LockModule` builds it ([GateRig]): the
 * real [AutoLock] over a [RecordingLock] around the real gate, the in-memory vault as both port and
 * repository, a fake clipboard, a screen that is on unless [screenOn] is false, the fake process
 * lifecycle and the fake clock. [setUp] makes the vault as setup does.
 */
class SessionRig(val test: TestScope, base: AppSettings = FakeAppSettings()) {
    private val gates = GateRig(test, base)
    val vault = gates.vault
    val clock = gates.clock
    val process = gates.process
    val settings = gates.settings
    val clipboard = FakeSecretClipboard()
    var screenOn = true

    lateinit var gate: MasterPasswordGate
        private set
    lateinit var lock: RecordingLock
        private set
    lateinit var autoLock: AutoLock
        private set

    val reasons: List<LockReason> get() = lock.reasons
    val locked: Boolean get() = lock.state.value is LockUiState.Locked
    val unlocked: Boolean get() = lock.state.value is LockUiState.Unlocked

    /** The process starts: the lock reads the vaults, the session watches the lifecycle and the lock. */
    fun start(): SessionRig {
        gate = gates.start()
        lock = RecordingLock(gate, vault.log)
        autoLock = AutoLock(lock, vault, clipboard, settings, clock, test.backgroundScope, ScreenState { screenOn })
        autoLock.start(process.lifecycle)
        settle()
        return this
    }

    fun settle() = gates.settle()

    /** Setup, as lock.md F1 makes it: the app in front, the vault created (the defaults written), unlocked. */
    suspend fun setUp() {
        process.toFront()
        settle()
        assertTrue(gate.createVault(PASSWORD, PASSWORD.copyOf(), null) is CreateResult.Created)
        settle()
        assertTrue(unlocked)
    }

    /** A vault already on the phone (no setup in this process), opened with its master password. */
    suspend fun unlockExisting() {
        process.toFront()
        settle()
        unlock()
    }

    /** The master password on the lock screen. */
    suspend fun unlock() {
        assertTrue(lock.submitMasterPassword(PASSWORD, MpPurpose.UNLOCK) is MpResult.Right)
        settle()
        assertTrue(unlocked)
    }

    fun vaultOnThePhone() = gates.vaultOnThePhone(PASSWORD)

    /** The user leaves the app (`ON_STOP`), [awayMs] pass, and the user comes back (`ON_START`). */
    fun awayFor(awayMs: Long) {
        process.away()
        settle()
        clock.advance(awayMs)
        settle()
        process.toFront()
        settle()
    }

    /** Time passes with the app in front and untouched. */
    fun idle(ms: Long) {
        clock.advance(ms)
        settle()
    }

    companion object {
        val PASSWORD = "Toshkent kuzi 2026 oltin barglar".toCharArray()
    }
}
