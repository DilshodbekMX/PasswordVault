package io.github.dilshodbekmx.passwordvault.files

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.fail

/**
 * The test APK's documents and its fake file picker, seen from the app's process, where the tests
 * run ([TestDocuments], [TestDocumentsProvider], [FakePickerActivity], [TestDocumentsReceiver]).
 */
internal object DocumentsRig {
    val instrumentation: Instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** The app under test. */
    val app: Context get() = instrumentation.targetContext

    /** The test APK's package, which holds the documents provider and the fake picker. */
    val testPackage: String get() = instrumentation.context.packageName

    /** A new document id of [behaviour] ([TestDocuments.PLAIN], [TestDocuments.ZERO], ...). */
    fun newId(behaviour: String): String = behaviour + "-" + UUID.randomUUID()

    /** The address of the document [id]. */
    fun docOf(id: String): DocRef = DocRef(TestDocuments.uri(id).toString())

    /**
     * Creates the document [id] in the test APK's provider with [content], and has the provider grant
     * the app read, write and persistable permission on it, as a picker's result does. Its address.
     */
    fun newDocument(id: String, content: ByteArray = ByteArray(0), name: String = "$id.bin"): DocRef {
        val answer = command(TestDocuments.ACTION_CREATE) {
            putExtra(TestDocuments.EXTRA_ID, id)
            putExtra(TestDocuments.EXTRA_NAME, name)
            putExtra(TestDocuments.EXTRA_CONTENT, content)
        }
        if (answer.code != Activity.RESULT_OK || answer.data == null) fail("the test provider did not make $id (${answer.code})")
        return DocRef(answer.data!!)
    }

    /** What the test APK's provider holds for the document [id]; null when it has none. */
    fun stored(id: String): ByteArray? {
        val answer = command(TestDocuments.ACTION_BYTES) { putExtra(TestDocuments.EXTRA_ID, id) }
        return if (answer.code == Activity.RESULT_OK) answer.extras?.getByteArray(TestDocuments.EXTRA_BYTES) else null
    }

    /** Ends the fake picker shown, with [resultCode]; false when none is shown. */
    fun finishPicker(resultCode: Int): Boolean =
        command(TestDocuments.ACTION_FINISH_PICKER) { putExtra(TestDocuments.EXTRA_RESULT, resultCode) }.code == Activity.RESULT_OK

    /**
     * Starts the fake picker for a result from [activity], under the request code of the app's own
     * launcher [key], as the system picker would be: it makes the document [id] named [name] with
     * [content] (no document when [id] is null), and grants nothing until it finishes.
     */
    fun openFakePicker(activity: Activity, key: String, id: String?, name: String, content: ByteArray? = null) {
        val intent = Intent()
            .setClassName(testPackage, FakePickerActivity::class.java.name)
            .putExtra(TestDocuments.EXTRA_NAME, name)
        if (id != null) intent.putExtra(TestDocuments.EXTRA_ID, id)
        if (content != null) intent.putExtra(TestDocuments.EXTRA_CONTENT, content)
        instrumentation.runOnMainSync {
            ActivityCompat.startActivityForResult(activity, intent, requestCode(activity as ComponentActivity, key), null)
        }
    }

    /**
     * The request code under which [activity]'s registry launches [key] ([DocumentLaunchers]'s keys).
     * The registry saves its keys and codes in two lists under these names (androidx.activity's
     * `ActivityResultRegistry.onSaveInstanceState`); the test fails if a new version renames them.
     */
    fun requestCode(activity: ComponentActivity, key: String): Int {
        val saved = Bundle()
        activity.activityResultRegistry.onSaveInstanceState(saved)
        val keys = saved.getStringArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_KEYS")
        val codes = saved.getIntegerArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_RCS")
        if (keys == null || codes == null) fail("ActivityResultRegistry saves its keys under other names now")
        val at = keys!!.indexOf(key)
        if (at < 0) fail("$key is not registered on $activity")
        return codes!![at]
    }

    /**
     * Keeps the system pickers (`ACTION_CREATE_DOCUMENT`, `ACTION_OPEN_DOCUMENT`) and the share sheet
     * from opening while it is open: the app's launch reaches the activity manager no further, and
     * nothing comes back.
     */
    class SystemScreens : AutoCloseable {
        private val create = monitor(Intent.ACTION_CREATE_DOCUMENT, Intent.CATEGORY_OPENABLE)
        private val open = monitor(Intent.ACTION_OPEN_DOCUMENT, Intent.CATEGORY_OPENABLE)
        private val chooser = monitor(Intent.ACTION_CHOOSER, null)

        /** How many times "Save as", the open picker and the share sheet were started. */
        val creates: Int get() = create.hits
        val opens: Int get() = open.hits
        val shares: Int get() = chooser.hits

        override fun close() {
            listOf(create, open, chooser).forEach(instrumentation::removeMonitor)
        }

        private fun monitor(action: String, category: String?): Instrumentation.ActivityMonitor {
            val filter = IntentFilter(action)
            if (category != null) {
                filter.addCategory(category)
                filter.addDataType("*/*")
            }
            return instrumentation.addMonitor(filter, null, true)
        }
    }

    /** Waits until [activity]'s own window has the input focus: what the user does next goes to it. */
    fun awaitInFront(activity: Activity) {
        await("the app in front") { hasWindowFocus(activity) && activity.packageName + "/" in focusedWindow() }
    }

    /**
     * Waits until the system's share sheet has the input focus. The activity under it is paused as
     * soon as the sheet is asked for, before the sheet is there: a Back in between goes to the
     * activity and leaves the app.
     */
    fun awaitShareSheet(activity: Activity) {
        var focus = ""
        try {
            await("the share sheet in front") {
                focus = focusedWindow()
                shareSheetOver(activity, focus)
            }
        } catch (e: AssertionError) {
            throw AssertionError(e.message + " (the focus: " + focus + ")", e)
        }
    }

    /**
     * Closes the share sheet over [activity] with the system's Back, as a user does, and waits until
     * the activity is in front again. A Back is sent only while the sheet has the focus, and again
     * when nothing happened: Android 7 loses one that comes while the sheet is still starting.
     */
    fun closeShareSheet(activity: Activity) {
        val end = SystemClock.elapsedRealtime() + 20_000
        fun inFront() = activity in activities(activity.javaClass, Stage.RESUMED)
        while (!inFront()) {
            val focus = focusedWindow()
            if (SystemClock.elapsedRealtime() > end) fail("timed out waiting: the app in front again (the focus: $focus)")
            if (shareSheetOver(activity, focus)) {
                if (!instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) fail("Back was not taken")
            }
            val retry = SystemClock.elapsedRealtime() + 1_500
            while (!inFront() && SystemClock.elapsedRealtime() < retry) SystemClock.sleep(50)
        }
    }

    /**
     * The share sheet has the input focus, and [activity]'s window has lost it: the window manager
     * names the sheet's window ("…ChooserActivity" on Android 7 and 9, "…ChooserActivityLauncher" on
     * Android 17), and the activity was told that its own focus is gone.
     */
    private fun shareSheetOver(activity: Activity, focus: String): Boolean = "Chooser" in focus && !hasWindowFocus(activity)

    private fun hasWindowFocus(activity: Activity): Boolean {
        var focused = false
        instrumentation.runOnMainSync { focused = activity.hasWindowFocus() }
        return focused
    }

    /** The window that has the input focus, as the window manager names it (`mCurrentFocus=Window{… package/activity}`). */
    private fun focusedWindow(): String {
        val out = instrumentation.uiAutomation.executeShellCommand("dumpsys window")
        val text = ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes().toString(Charsets.UTF_8) }
        return text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("mCurrentFocus=") }.orEmpty()
    }

    /** The system's Home: the app is left as a user leaves it. */
    fun pressHome() {
        if (!instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)) fail("Home was not taken")
    }

    /** The activities of [type] in one of [stages]; main thread inside. */
    fun <T : Activity> activities(type: Class<T>, vararg stages: Stage): List<T> {
        val found = mutableListOf<T>()
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            for (stage in stages) monitor.getActivitiesInStage(stage).filterIsInstance(type).forEach { found += it }
        }
        return found
    }

    /** Waits up to [timeoutMs] for [condition], checking every 50 ms; fails with [what] after that. */
    fun await(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition()) {
            if (SystemClock.elapsedRealtime() > end) fail("timed out waiting: $what")
            SystemClock.sleep(50)
        }
    }

    /** The persisted permission the app holds on [doc], as (read, write); null when it holds none. */
    fun persisted(doc: DocRef): Pair<Boolean, Boolean>? =
        app.contentResolver.persistedUriPermissions.firstOrNull { it.uri == Uri.parse(doc.uri) }
            ?.let { it.isReadPermission to it.isWritePermission }

    /** Gives up the persisted permission on [doc], if the app holds one. */
    fun release(doc: DocRef) {
        val uri = Uri.parse(doc.uri)
        val held = app.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri } ?: return
        var flags = 0
        if (held.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (held.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            app.contentResolver.releasePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            // Revoked meanwhile (the document was deleted).
        }
    }

    private class Answer(val code: Int, val data: String?, val extras: Bundle?)

    /** An ordered broadcast to [TestDocumentsReceiver] in the test APK's process, and its answer. */
    private fun command(action: String, fill: Intent.() -> Unit): Answer {
        val intent = Intent(action)
            .setClassName(testPackage, TestDocumentsReceiver::class.java.name)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            .apply(fill)
        val done = CountDownLatch(1)
        var answer: Answer? = null
        val thread = HandlerThread("documents-rig").apply { start() }
        try {
            app.sendOrderedBroadcast(
                intent,
                null,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, result: Intent) {
                        answer = Answer(resultCode, resultData, getResultExtras(false))
                        done.countDown()
                    }
                },
                Handler(thread.looper),
                NOT_ANSWERED,
                null,
                null,
            )
            if (!done.await(30, TimeUnit.SECONDS)) fail("no answer to $action from the test APK")
        } finally {
            thread.quitSafely()
        }
        val got = answer!!
        if (got.code == NOT_ANSWERED) fail("the test APK's receiver did not get $action: is the test package visible to the app?")
        return got
    }

    /** The initial result code of a command: still there when the receiver never ran. */
    private const val NOT_ANSWERED = 4711
}
