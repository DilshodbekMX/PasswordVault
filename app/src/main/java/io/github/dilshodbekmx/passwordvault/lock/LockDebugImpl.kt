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
 * [command] is the seven commands as the debug receiver's handlers answer them.
 *
 * A hold parks the thread of the engine's dispatcher that reached it, under the lock mutex, as a
 * slow disk or a slow Keystore would: key presses, a lock and `ON_STOP` wait behind it, which is what
 * the checks want to see. [status] therefore never waits for the engine while a hold is reached. A
 * hold that nobody releases ends by itself after [HOLD_MAX_MS].
 *
 * Nothing here returns or keeps a secret: no digit, password, key or entry text.
 *
 * @param io the dispatcher of the Keystore calls of [dropKeys] and [status].
 */
class LockDebugImpl(private val access: KeystoreAccess, private val io: CoroutineDispatcher) : LockDebug {

    /** The two places a PIN check can be held at; [id] is how the commands and [status] name it. */
    enum class Point(val id: String) { AFTER_CHARGE("after-charge"), BEFORE_HMAC("before-hmac") }

    private val guard = Any()
    private var armed: Point? = null // guarded by guard
    private var reached: Point? = null // guarded by guard
    private var gate: CountDownLatch? = null // guarded by guard
    private var failWrite = false // guarded by guard

    @Volatile private var engine: LockEngine? = null

    @Volatile private var manager: DefaultLockManager? = null

    /** The hold that is armed and not reached yet, or null. */
    val armedHold: Point? get() = synchronized(guard) { armed }

    /** The hold at which a PIN check is parked now, or null. */
    val reachedHold: Point? get() = synchronized(guard) { reached }

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
     * [KeystoreFaults.rows] or `prompt-error-<code>`. `none` disarms. False, with nothing changed,
     * when no fault has that name.
     */
    fun armFault(row: String): Boolean {
        if (row == NO_FAULT) {
            access.faults.disarm()
            return true
        }
        return access.faults.arm(row)
    }

    override suspend fun dropKeys() = withContext(io) { access.dropAllKeys() }

    override suspend fun status(): String {
        val out = StringBuilder()
        val parked = reachedHold
        val described = when {
            // The lock mutex is held by the check that is parked: asking the engine would wait for `release`.
            parked != null -> "engine: held at ${parked.id}, not asked"
            else -> describeEngine() ?: "engine: no answer in $ENGINE_WAIT_MS ms"
        }
        out.append(described).append('\n')
        out.append("hold: ").append(parked?.let { it.id + " (reached)" } ?: armedHold?.let { it.id + " (armed)" } ?: "none").append('\n')
        out.append("fail-next-write: ").append(if (synchronized(guard) { failWrite }) "armed" else "no").append('\n')
        out.append("ks-fault: ").append(access.faults.pending ?: NO_FAULT).append('\n')
        val all = everyAlias()
        out.append("keystore: ").append(if (all.isEmpty()) "no alias listed" else all.joinToString(" "))
        val manager = manager
        if (manager != null) {
            val prompt = manager.prompts.open
            out.append('\n').append("prompt: ").append(if (prompt == null) "none" else prompt.use.name + ", launch " + prompt.launch)
            out.append(", may open by itself: ").append(manager.prompts.canOpenByItself)
            out.append('\n').append("last PIN unlock: ").append(manager.lastPinOpenMs?.let { "$it ms from the sixth digit" } ?: "none")
        }
        return out.toString()
    }

    /**
     * One of [COMMANDS] with the words that followed its name, answered as the debug receiver's
     * handler answers it, in one line (the receiver's answer is the `data` of `am broadcast`, which
     * scripts read as one line): the command's name and what it did, or for `status` the lines of
     * [status] joined with [LINE_SEPARATOR].
     *
     * @throws IllegalArgumentException for a name that is not a command of the lock, words a command
     *   does not take, or a `ks-fault` row that no fault has; the receiver answers "failed" then.
     */
    suspend fun command(name: String, args: List<String>): String {
        if (name == KS_FAULT) return ksFaultCommand(args)
        require(args.isEmpty()) { "usage: $name" }
        return when (name) {
            STATUS -> status().lineSequence().joinToString(LINE_SEPARATOR) { it.trim() }
            HOLD_AFTER_CHARGE -> {
                holdAfterCharge()
                "$name: armed; the next PIN entry stops when its charge is on disk, until release"
            }
            HOLD_BEFORE_HMAC -> {
                holdBeforeHmac()
                "$name: armed; the next PIN check stops before it asks the Keystore, until release"
            }
            RELEASE -> {
                val was = reachedHold?.let { it.id + " (reached)" } ?: armedHold?.let { it.id + " (armed)" }
                release()
                "$name: " + (was?.let { "released $it" } ?: "nothing was held")
            }
            FAIL_NEXT_WRITE -> {
                failNextWrite()
                "$name: armed; the next write of a lock record fails"
            }
            DROP_KEYS -> {
                val before = everyAlias()
                dropKeys()
                val left = everyAlias()
                "$name: ${before.size - left.size} of ${before.size} aliases deleted" + if (left.isEmpty()) "" else "; left: " + left.joinToString(" ")
            }
            else -> throw IllegalArgumentException("not a command of the lock")
        }
    }

    /**
     * The engine's own description, or null when it did not answer in [ENGINE_WAIT_MS]. The engine
     * lists the shortcut aliases for it, which is a Keystore call: a fault that is armed is set aside
     * meanwhile and armed again afterwards, so that looking does not spend it (`keystore-load` would
     * strike here). A step of the lock that runs at the same moment does not meet it either.
     */
    private suspend fun describeEngine(): String? {
        val engine = engine ?: return null
        val fault = access.faults.pending
        if (fault != null) access.faults.disarm()
        try {
            return withTimeoutOrNull(ENGINE_WAIT_MS) { engine.describe() }
        } finally {
            if (fault != null && access.faults.pending == null) access.faults.arm(fault)
        }
    }

    private fun ksFaultCommand(args: List<String>): String {
        val rows = KeystoreFaults.rows.joinToString(" ") + " " + KeystoreFaults.PROMPT_ERROR + "<code> " + NO_FAULT
        if (args.isEmpty()) return "$KS_FAULT: " + (access.faults.pending ?: NO_FAULT) + "; rows: " + rows
        require(args.size == 1) { "usage: $KS_FAULT [ROW]" }
        require(armFault(args[0])) { "no such row" }
        return "$KS_FAULT: " + (access.faults.pending ?: NO_FAULT)
    }

    private suspend fun everyAlias(): List<String> = withContext(io) { access.everyAlias() }.sorted()

    private fun arm(point: Point) {
        synchronized(guard) { armed = point }
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
        const val STATUS = "status"
        const val HOLD_AFTER_CHARGE = "hold-after-charge"
        const val HOLD_BEFORE_HMAC = "hold-before-hmac"
        const val RELEASE = "release"
        const val FAIL_NEXT_WRITE = "fail-next-write"
        const val KS_FAULT = "ks-fault"
        const val DROP_KEYS = "drop-keys"

        /** The debug commands of lock.md §15.3, by the names the receiver takes. */
        val COMMANDS: List<String> = listOf(STATUS, HOLD_AFTER_CHARGE, HOLD_BEFORE_HMAC, RELEASE, FAIL_NEXT_WRITE, KS_FAULT, DROP_KEYS)

        /** After this long a hold that nobody released lets its check go on. */
        const val HOLD_MAX_MS = 10L * 60_000L

        /** How long [status] waits for the engine, which answers under the lock mutex. */
        const val ENGINE_WAIT_MS = 5_000L

        /** What `ks-fault` takes to disarm, and what [status] prints when nothing is armed. */
        const val NO_FAULT = "none"

        /** What stands between the lines of [status] in the one-line answer of the `status` command. */
        const val LINE_SEPARATOR = "; "
    }
}
