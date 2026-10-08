package io.github.dilshodbekmx.passwordvault.clipboard

import io.github.dilshodbekmx.passwordvault.fake.FakeAppSettings
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * A clipboard in memory for [AndroidSecretClipboard]'s tests. It holds the token of the clip that is
 * there ([token]; null for another app's clip), or nothing ([has] false), and what was set and
 * cleared. [readable] false is Android 10 and later with the app in the background: [holder] then
 * says [ClipHolder.UNKNOWN] whatever is there.
 */
internal class FakeSystemClip(override var needsFocusToRead: Boolean = false) : SystemClip {
    /** One [set] call. [toString] never shows the text. */
    class Set(val text: String, val sensitive: Boolean, val token: String) {
        override fun toString(): String = "Set(sensitive=$sensitive, token=$token)"
    }

    var has = false
    var token: String? = null
    var readable = true
    val sets = mutableListOf<Set>()
    var clears = 0

    /** The next [set] throws; with [failedSetLeavesTheClip] the clip goes in before it does. */
    var failNextSet = false
    var failedSetLeavesTheClip = false

    /** While true, [clear] throws and leaves the clipboard as it is. */
    var failClears = false

    /** How many times [holder] was asked. */
    var looks = 0

    /** Another app copies: a clip without our token. */
    fun otherAppCopies() {
        has = true
        token = null
    }

    override fun set(text: CharSequence, sensitive: Boolean, token: String) {
        if (failNextSet) {
            failNextSet = false
            if (failedSetLeavesTheClip) {
                has = true
                this.token = token
            }
            throw SecurityException("the phone blocked copying")
        }
        sets += Set(text.toString(), sensitive, token)
        has = true
        this.token = token
    }

    override fun holder(token: String): ClipHolder {
        looks++
        return when {
            !readable || !has -> ClipHolder.UNKNOWN
            this.token == token -> ClipHolder.OURS
            else -> ClipHolder.OTHER
        }
    }

    override fun clear() {
        if (failClears) throw IllegalStateException("the phone blocked clearing")
        clears++
        has = false
        token = null
    }
}

/** A [ClearAlarm] that only records: the alarm that is set now, and every call. */
internal class FakeClearAlarm : ClearAlarm {
    data class Scheduled(val token: String, val clearAtElapsedMs: Long)

    var set: Scheduled? = null
    val schedules = mutableListOf<Scheduled>()
    var cancels = 0
    var failSchedules = false

    override fun schedule(token: String, clearAtElapsedMs: Long) {
        if (failSchedules) throw SecurityException("no alarms")
        set = Scheduled(token, clearAtElapsedMs)
        schedules += Scheduled(token, clearAtElapsedMs)
    }

    override fun cancel() {
        cancels++
        set = null
    }
}

/**
 * Everything an [AndroidSecretClipboard] needs, in memory, on the test's own thread: the clock moves
 * only when the test calls [advance], and a coroutine that waited for that moment runs before
 * `advance` returns. [make] gives a clipboard as a new process would build it over the same settings,
 * clock and phone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ClipRig(io: CoroutineDispatcher? = null) {
    val clock = FakeClock(autoAdvance = false)
    val settings = FakeAppSettings()
    val system = FakeSystemClip()
    val alarm = FakeClearAlarm()
    val random = FakeRandom()
    private val dispatcher = UnconfinedTestDispatcher()
    private val io = io ?: dispatcher

    fun make(): AndroidSecretClipboard = AndroidSecretClipboard(
        system = system,
        alarm = alarm,
        settings = settings,
        clock = clock,
        random = random,
        scope = CoroutineScope(SupervisorJob() + dispatcher),
        io = io,
    )

    fun advance(ms: Long) = clock.advance(ms)
}
