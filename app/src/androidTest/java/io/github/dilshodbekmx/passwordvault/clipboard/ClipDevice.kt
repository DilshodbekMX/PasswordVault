package io.github.dilshodbekmx.passwordvault.clipboard

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.PasswordVaultApp
import org.junit.Assert.fail

/**
 * The phone's clipboard for the device tests of this package. From Android 10 on the clipboard
 * answers only the app whose window has the focus, so every test that reads it keeps an activity in
 * front and waits for its focus first ([awaitFocus]).
 */
internal object ClipDevice {
    val app: PasswordVaultApp get() = ApplicationProvider.getApplicationContext()

    val manager: ClipboardManager get() = app.getSystemService(ClipboardManager::class.java)

    fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /** Returns once [activity]'s window has the focus and the clipboard answers it. */
    fun awaitFocus(activity: Activity) {
        waitFor("the activity's window to get the focus") { onMain { activity.hasWindowFocus() } }
        // The server gives the focus before the window hears of it; one answered read proves it here.
        onMain { manager.setPrimaryClip(ClipData.newPlainText("focus probe", "probe")) }
        waitFor("the clipboard to answer the focused app") { onMain { manager.primaryClipDescription?.label?.toString() == "focus probe" } }
        empty()
    }

    /** The clip's description as the app may read it now, or null. */
    fun description(): ClipDescription? = onMain { manager.primaryClipDescription }

    /** The text on the clipboard; the app's own clip, so no notice is shown for the read. */
    fun text(): String? = onMain { manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() }

    fun token(): String? = description()?.extras?.getString(AndroidSystemClip.EXTRA_TOKEN)

    /** Whether the description carries the sensitive flag; null when it has no such extra. */
    fun sensitive(): Boolean? = description()?.extras?.let { if (it.containsKey(AndroidSystemClip.EXTRA_IS_SENSITIVE)) it.getBoolean(AndroidSystemClip.EXTRA_IS_SENSITIVE) else null }

    /** Another clip, as a copy elsewhere would leave it: no extras. */
    fun otherCopy(text: String) = onMain { manager.setPrimaryClip(ClipData.newPlainText("elsewhere", text)) }

    /** Nothing on the clipboard: no clip from API 28 on, an empty one below. */
    fun isEmpty(): Boolean = onMain {
        if (Build.VERSION.SDK_INT >= 28) !manager.hasPrimaryClip() else manager.primaryClip?.let { it.itemCount == 0 || it.getItemAt(0).text.isNullOrEmpty() } ?: true
    }

    fun empty() = onMain {
        if (Build.VERSION.SDK_INT >= 28) manager.clearPrimaryClip() else manager.setPrimaryClip(ClipData.newPlainText("", ""))
    }

    fun waitFor(what: String, timeoutMs: Long = 10_000L, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition()) {
            if (SystemClock.elapsedRealtime() > end) fail("timed out after $timeoutMs ms waiting for $what")
            Thread.sleep(50)
        }
    }
}
