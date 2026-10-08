package io.github.dilshodbekmx.passwordvault.clipboard

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 3.8, with the phone's own alarms: [AlarmManagerClearAlarm]'s alarm reaches
 * [ClipboardClearReceiver], which hands it to the app's clipboard, and a clip carrying the alarm's
 * token is cleared at the alarm's time, not before it (architecture.md D-14). The alarm is exact on
 * Android 7 to 11 and aimed on later versions, where it may come up to a few seconds late for a time
 * this near; the receiver waits out one that comes early. `tools/emu/clipboard/clear.sh` runs the
 * same path with the process frozen or ended.
 */
@RunWith(AndroidJUnit4::class)
class ClearAlarmDeviceTest {
    @get:Rule
    val activity = ActivityScenarioRule(ComponentActivity::class.java)

    private val alarm = AlarmManagerClearAlarm(ClipDevice.app)
    private val clip = AndroidSystemClip(ClipDevice.manager)
    private var token = ""

    @Before
    fun inFront() {
        var current: ComponentActivity? = null
        activity.scenario.onActivity { current = it }
        ClipDevice.awaitFocus(current!!)
        // A token of this test's own: the app's clipboard acts once on each token it is handed.
        token = "%032x".format(java.util.Locale.ROOT, SystemClock.elapsedRealtimeNanos())
    }

    @After
    fun cleanUp() {
        ClipDevice.onMain { alarm.cancel() }
        ClipDevice.empty()
    }

    @Test
    fun theAlarmClearsTheClipOfItsTokenAtItsTime() {
        ClipDevice.onMain { clip.set("s3cret", sensitive = true, token = token) }
        val clearAt = SystemClock.elapsedRealtime() + AHEAD_MS
        ClipDevice.onMain { alarm.schedule(token, clearAt) }
        assertTrue("the alarm is set: ${setAlarm()}", setAlarm().isNotEmpty())

        while (SystemClock.elapsedRealtime() < clearAt - 300) {
            assertEquals("the clip stays until its time", token, ClipDevice.token())
            Thread.sleep(200)
        }
        ClipDevice.waitFor("the alarm to clear the clip", LATEST_MS) { ClipDevice.isEmpty() }
        assertTrue("never before its time", SystemClock.elapsedRealtime() >= clearAt)
    }

    @Test
    fun theReceiverLeavesAClipThatIsNotItsAlarms() {
        ClipDevice.onMain { clip.set("s3cret", sensitive = true, token = token) }
        val clearAt = SystemClock.elapsedRealtime() + AHEAD_MS
        ClipDevice.onMain { alarm.schedule(token, clearAt) }
        ClipDevice.otherCopy("copied afterwards")

        ClipDevice.waitFor("the alarm to be delivered", LATEST_MS) { setAlarm().isEmpty() }
        // The receiver waits out an alarm that came early, and acts at the alarm's time.
        val rest = clearAt + 1_500 - SystemClock.elapsedRealtime()
        if (rest > 0) Thread.sleep(rest)
        assertEquals("copied afterwards", ClipDevice.text())
    }

    @Test
    fun aCancelledAlarmDoesNotCome() {
        ClipDevice.onMain { clip.set("s3cret", sensitive = true, token = token) }
        ClipDevice.onMain { alarm.schedule(token, SystemClock.elapsedRealtime() + AHEAD_MS) }
        ClipDevice.onMain { alarm.cancel() }
        assertEquals("no alarm is set any more", "", setAlarm())

        Thread.sleep(AHEAD_MS + 6_000)
        assertEquals("the clip is still there", token, ClipDevice.token())
    }

    @Test
    fun aSecondScheduleReplacesTheFirst() {
        ClipDevice.onMain { clip.set("s3cret", sensitive = true, token = token) }
        val first = SystemClock.elapsedRealtime() + AHEAD_MS
        ClipDevice.onMain { alarm.schedule("0000000000000000000000000000dead", first) }
        val second = first + 4_000
        ClipDevice.onMain { alarm.schedule(token, second) }
        assertEquals("one alarm, not two", 1, setAlarm().lines().count { it.trim().startsWith("tag=") })

        ClipDevice.waitFor("the alarm to clear the clip", LATEST_MS + 4_000) { ClipDevice.isEmpty() }
        assertTrue("at the second time, not the first", SystemClock.elapsedRealtime() >= second)
    }

    /** The app's clear alarm among the alarms that are set, as `dumpsys alarm` lists it; empty when there is none. */
    private fun setAlarm(): String {
        val tag = "tag=*walarm*:" + AlarmManagerClearAlarm.ACTION_CLEAR
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys alarm")
        val dump = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        return dump.lines().filter { it.trim() == tag }.joinToString("\n")
    }

    private companion object {
        /** Android does not set an alarm nearer than 5 s. */
        const val AHEAD_MS = 7_000L

        /** How long after it was set an alarm for [AHEAD_MS] ahead has come at the latest, with room for a busy emulator. */
        const val LATEST_MS = 40_000L
    }
}
