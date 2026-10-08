package io.github.dilshodbekmx.passwordvault.lock

import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockEngine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinKey
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordFiles
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.keystore.KeystoreAccess
import io.github.dilshodbekmx.passwordvault.keystore.KeystoreFaults
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The lock's test hooks (lock.md §15.3; architecture.md §3.5 [LockDebug]) over the real lock, for the
 * debug commands of the `debug` and `staging` source sets (`debug/lock/`). `LockModule` makes one only
 * in a build that carries those commands (`KeystoreFaults.testBuild`: the builds that allow software
 * keys, architecture.md §7.2); a release build makes none, wraps no port and contains no command
 * that could reach it (build check R-2).
 *
 * The hooks sit on two ports of the lock engine, which `LockModule` wraps before it builds the
 * engine ([records], [pinKey]), and on the Keystore's own faults (`KeystoreAccess.faults`):
 *
 * - **`hold-after-charge`** ([holdAfterCharge]): the next write of a `pin.rec` whose guard is charged
 *   (lock.md §6.3 C, the first digit of an entry) returns only after [release]. The charge is on disk
 *   then, and nothing has been checked: a kill or a reboot here must cost one try (checks E2, E3).
 * - **`hold-before-hmac`** ([holdBeforeHmac]): the next `PinKey.hmac` waits for [release] before it
 *   asks the Keystore (§6.3 E.3; checks E2, E13).
 * - **`release`** ([release]) lets a held check go on, and disarms a hold that was not reached.
 * - **`fail-next-write`** ([failNextWrite]): the next write of any lock record throws `IOException`
 *   before anything is written (lock.md §5.9: any exception means "not written"; check E14).
 * - **`ks-fault <row>`** ([ksFault]) arms a fault of `KeystoreFaults` ([KeystoreFaults.rows], or
 *   `prompt-error-<code>`) for the next Keystore call that meets it; `none` disarms (check E10).
 * - **`drop-keys`** ([dropKeys]) deletes every alias of the app, the canary included (check E10).
 * - **`status`** ([status]): the engine's own description (the inputs and the result of lock.md
 *   §13.1, the counts, the shortcut aliases), then what these hooks and the prompt hold.
 *
 * A hold parks the thread of the engine's dispatcher that reached it, under the lock mutex, as a
 * slow disk or a slow Keystore would: key presses, a lock and `ON_STOP` wait behind it, which is what
 * the checks want to see. [status] therefore never waits for the engine while a hold is reached. A
 * hold that nobody releases ends by itself after [HOLD_MAX_MS].
 *
 * Nothing here returns or keeps a secret: no digit, password, key or entry text.
 */
class LockDebugImpl(private val access: KeystoreAccess, private val io: CoroutineDispatcher) : LockDebug {

    /** The two places a PIN check can be held at. */
    private enum class Point(val id: String) { AFTER_CHARGE("after-charge"), BEFORE_HMAC("before-hmac") }

    private val guard = Any()
    private var armed: Point? = null // guarded by guard
    private var reached: Point? = null // guarded by guard
    private var gate: CountDownLatch? = null // guarded by guard
    private var failWrite = false // guarded by guard

    @Volatile private var engine: LockEngine? = null

    @Volatile private var manager: DefaultLockManager? = null

    /** The lock these hooks belong to, once `LockModule` has built it over the wrapped ports. */
    fun attach(engine: LockEngine, manager: DefaultLockManager) {
        this.engine = engine
        this.manager = manager
    }

    /** [real] with `fail-next-write` and `hold-after-charge`: the lock engine's record files. */
    fun records(real: RecordFiles): RecordFiles = object : RecordFiles by real {
        override fun replace(dir: String, name: String, bytes: ByteArray) {
            val fail = synchronized(guard) { failWrite.also { failWrite = false } }
            if (fail) throw IOException("fail-next-write") // not written (lock.md §5.9)
            real.replace(dir, name, bytes)
            if (name == RecordCodec.Pin.fileName && isCharged(dir, bytes)) holdAt(Point.AFTER_CHARGE)
        }
    }

    /** [real] with `hold-before-hmac`: the lock engine's PIN key. */
    fun pinKey(real: PinKey): PinKey = object : PinKey by real {
        override fun hmac(alias: String, message: ByteArray): Ks<ByteArray> {
            holdAt(Point.BEFORE_HMAC)
            return real.hmac(alias, message)
        }
    }

    override fun holdAfterCharge() = arm(Point.AFTER_CHARGE)

    override fun holdBeforeHmac() = arm(Point.BEFORE_HMAC)

    override fun release() {
        synchronized(guard) {
            armed = null
            gate?.countDown()
            gate = null
        }
    }

    override fun failNextWrite() {
        synchronized(guard) { failWrite = true }
    }

    /** As [armFault]; a name that no fault has changes nothing. */
    override fun ksFault(row: String) {
        armFault(row)
    }

    /**
     * Arms the Keystore fault [row] in place of the one that was armed: a name of
     * [KeystoreFaults.rows] or `prompt-error-<code>`. `none` (or an empty name) disarms. False, with
     * nothing changed, when no fault has that name.
     */
    fun armFault(row: String): Boolean {
        if (row.isEmpty() || row == NO_FAULT) {
            access.faults.disarm()
            return true
        }
        return access.faults.arm(row)
    }

    override suspend fun dropKeys() = withContext(io) { access.dropAllKeys() }

    override suspend fun status(): String {
        val out = StringBuilder()
        val hold = holdState()
        val described = when {
            // The engine's mutex is held by the check that is parked: asking would wait for `release`.
            hold.endsWith(REACHED) -> null
            else -> engine?.let { withTimeoutOrNull(ENGINE_WAIT_MS) { it.describe() } }
        }
        out.append(described ?: ("engine: " + if (hold.endsWith(REACHED)) "held, not asked" else "no answer in $ENGINE_WAIT_MS ms")).append('\n')
        out.append("hold: ").append(hold).append('\n')
        out.append("fail-next-write: ").append(if (synchronized(guard) { failWrite }) "armed" else "no").append('\n')
        out.append("ks-fault: ").append(access.faults.pending ?: NO_FAULT).append('\n')
        val all = withContext(io) { access.everyAlias() }.sorted()
        out.append("keystore: ").append(if (all.isEmpty()) "no alias listed" else all.joinToString(" ")).append('\n')
        val manager = manager
        if (manager != null) {
            val prompt = manager.prompts.open
            out.append("prompt: ").append(if (prompt == null) "none" else prompt.use.name + ", launch " + prompt.launch)
            out.append(", may open by itself: ").append(manager.prompts.canOpenByItself).append('\n')
            out.append("last PIN unlock: ").append(manager.lastPinOpenMs?.let { "$it ms from the sixth digit" } ?: "none")
        }
        return out.toString().trimEnd()
    }

    private fun arm(point: Point) {
        synchronized(guard) { armed = point }
    }

    /** `armed`, `reached` or neither, as [status] prints it. */
    private fun holdState(): String = synchronized(guard) {
        reached?.let { it.id + REACHED } ?: armed?.let { it.id + " (armed)" } ?: "none"
    }

    /** Parks the calling thread at [point] while that hold is armed, until [release]. */
    private fun holdAt(point: Point) {
        val latch = synchronized(guard) {
            if (armed != point) return
            armed = null
            reached = point
            CountDownLatch(1).also { gate = it }
        }
        try {
            latch.await(HOLD_MAX_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            synchronized(guard) {
                if (gate === latch) gate = null
                reached = null
            }
        }
    }

    /** Whether [bytes], written as the `pin.rec` of the lock directory [dir], holds a charge (lock.md §5.4 `guard.charged`). */
    private fun isCharged(dir: String, bytes: ByteArray): Boolean {
        val read = RecordCodec.Pin.decode(bytes, dir.substring(dir.lastIndexOf('/') + 1))
        return (read as? RecordRead.Ok)?.record?.guard?.charged == true
    }

    companion object {
        /** After this long a hold that nobody released lets its check go on. */
        const val HOLD_MAX_MS = 10L * 60_000L

        /** How long [status] waits for the engine, which answers under the lock mutex. */
        const val ENGINE_WAIT_MS = 5_000L

        /** What `ks-fault` takes to disarm, and what [status] prints when nothing is armed. */
        const val NO_FAULT = "none"

        private const val REACHED = " (reached)"
    }
}
