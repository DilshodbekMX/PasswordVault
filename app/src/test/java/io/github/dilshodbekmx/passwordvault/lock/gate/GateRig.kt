package io.github.dilshodbekmx.passwordvault.lock.gate

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.github.dilshodbekmx.passwordvault.core.crypto.MasterPasswordBytes
import io.github.dilshodbekmx.passwordvault.fake.FakeAppSettings
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.MasterPasswordGate
import io.github.dilshodbekmx.passwordvault.settings.AppSettings
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

/**
 * The process lifecycle as `ProcessLifecycleOwner` reports it (lock.md §10.2), on a real
 * `LifecycleRegistry` that the test drives: [toFront] is the first activity started (`ON_START`,
 * `ON_RESUME`), [away] the moment 700 ms after the last activity paused (`ON_PAUSE`, `ON_STOP`). A
 * recreation of the activity reaches none of it. It starts as a process does, `CREATED`, before
 * its first activity.
 */
class FakeProcess : LifecycleOwner {
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

/**
 * [AppSettings] that also writes "writeDefaults while <state>" into [log] when the defaults are
 * written, so that a test sees what the lock showed at that moment.
 */
class RecordingSettings(private val inner: AppSettings, private val log: MutableList<String>, private val state: () -> LockUiState) :
    AppSettings by inner {
    override fun writeDefaults(): Boolean {
        log += "writeDefaults while ${state()::class.simpleName}"
        return inner.writeDefaults()
    }
}

/**
 * The gate as `LockModule` builds it, over an [InMemoryVault], with the fakes of `:testing` and
 * `APP/fake/` for the rest, on the test's scheduler: [clock] moves only when the test advances it,
 * and [settle] runs whatever the events queued.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GateRig(val test: TestScope, base: AppSettings = FakeAppSettings()) {
    val clock = FakeClock(autoAdvance = false)
    val random = FakeRandom()
    val vault = InMemoryVault(clock)
    val process = FakeProcess()
    private var made: MasterPasswordGate? = null
    val settings: AppSettings = RecordingSettings(base, vault.log) { made?.state?.value ?: LockUiState.Starting }

    val gate: MasterPasswordGate get() = made ?: error("start() first")

    /** Builds the gate, as at process start; its first scan is queued until [settle]. */
    fun start(): MasterPasswordGate {
        val built = MasterPasswordGate(vault, settings, random, clock, test.backgroundScope, StandardTestDispatcher(test.testScheduler), process.lifecycle)
        made = built
        return built
    }

    /**
     * Runs every task the last events queued, those of the app's scope (the test's
     * `backgroundScope`) included, which `advanceUntilIdle` would leave. Nothing here waits for
     * virtual time: the clock is [clock].
     */
    fun settle() = test.testScheduler.runCurrent()

    /** A vault already on the phone, whose master password is [password]. */
    fun vaultOnThePhone(password: CharArray, id: String = VAULT_ID) {
        val bytes = MasterPasswordBytes.of(password)
        vault.put(id, bytes)
        bytes.fill(0)
    }

    companion object {
        const val VAULT_ID = "6f9619ff-8b86-4011-b42d-00c04fc964ff"
        const val OTHER_ID = "00000000-0000-4000-8000-0000000000a1"
    }
}
