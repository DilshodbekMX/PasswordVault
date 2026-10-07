package io.github.dilshodbekmx.passwordvault.clipboard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock

/**
 * [SystemClip] over the phone's `ClipboardManager`: the clip and its two extras as ux.md §6 item 1
 * gives them, the description check of item 2, and the clear of [V-P2].
 *
 * @param sdk the API level, a parameter so that both forms of [clear] can be tested on one device.
 */
internal class AndroidSystemClip(
    private val manager: ClipboardManager,
    private val sdk: Int = Build.VERSION.SDK_INT,
) : SystemClip {

    /** From Android 10 on a read is answered only for the app whose window has the focus [V-P2]. */
    override val needsFocusToRead: Boolean get() = sdk >= 29

    override fun set(text: CharSequence, sensitive: Boolean, token: String) {
        val clip = ClipData.newPlainText("", text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(EXTRA_IS_SENSITIVE, sensitive)
            putString(EXTRA_TOKEN, token)
        }
        manager.setPrimaryClip(clip)
    }

    /**
     * Reads the description, never the clip: Android shows no "pasted from your clipboard" notice
     * for that (ux.md §3). No description means an empty clipboard, or, on Android 10 and later, an
     * app that may not look now; the two cannot be told apart.
     */
    override fun holder(token: String): ClipHolder {
        val description = try {
            manager.primaryClipDescription
        } catch (e: RuntimeException) {
            null
        }
        if (description == null) return ClipHolder.UNKNOWN
        val found = try {
            description.extras?.getString(EXTRA_TOKEN)
        } catch (e: RuntimeException) {
            null // extras of another app that cannot be read here: not ours
        }
        return if (found == token) ClipHolder.OURS else ClipHolder.OTHER
    }

    /** `clearPrimaryClip()` from API 28; below it an empty clip, which is all Android 7 and 8 offer [V-P2]. */
    override fun clear() {
        if (sdk >= 28) manager.clearPrimaryClip() else manager.setPrimaryClip(ClipData.newPlainText("", ""))
    }

    companion object {
        /**
         * The value of `ClipDescription.EXTRA_IS_SENSITIVE`. The constant is from API 33; the text
         * works on every version, and Android 13 and later hide such a clip in their preview
         * (ux.md §6 item 1, [V-P10]).
         */
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

        /** The extra that marks a clip as ours: the copy's random token, in hex. Not a secret (ux.md §6). */
        const val EXTRA_TOKEN = "io.github.dilshodbekmx.passwordvault.clipboard.TOKEN"
    }
}

/**
 * [ClearAlarm] over `AlarmManager`: one alarm to [ClipboardClearReceiver], which is not exported, so
 * only this `PendingIntent` reaches it (architecture.md D-14). The scheduling is the clipboard lab's
 * as measured (docs/lab/clipboard.md §6, `docs/lab/clipboard-artifacts/SensitiveClipboard.kt`),
 * without its two permission lines:
 *  - an exact alarm where none needs a permission: below API 31, or when
 *    `canScheduleExactAlarms()` says so;
 *  - otherwise `setAndAllowWhileIdle` aimed early. Android may deliver an alarm that is not exact
 *    and was set for time t as late as t + 0.75 × (t − now), and with nothing else waking the phone
 *    that is when it comes; so the alarm is set for now + (deadline − now) / 1.75, whose window ends
 *    at the deadline. Should it come before the deadline, the receiver waits or aims again.
 * The alarm carries the token and the deadline on the elapsed clock, which is all the receiver
 * needs in a process that Android started for it. A force-stop removes the alarm (D-14).
 */
internal class AlarmManagerClearAlarm(context: Context) : ClearAlarm {
    private val app: Context = context.applicationContext
    private val alarms: AlarmManager = app.getSystemService(AlarmManager::class.java)

    override fun schedule(token: String, clearAtElapsedMs: Long) {
        val operation = PendingIntent.getBroadcast(
            app,
            REQUEST_CODE,
            clearIntent().putExtra(EXTRA_TOKEN, token).putExtra(EXTRA_CLEAR_AT, clearAtElapsedMs),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, clearAtElapsedMs, operation)
                return
            } catch (e: SecurityException) {
                // The access was taken away between the question and the call: the aimed alarm below.
            }
        }
        val now = SystemClock.elapsedRealtime()
        val at = now + ((clearAtElapsedMs - now) / WINDOW_FACTOR).toLong()
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, operation)
    }

    override fun cancel() {
        val operation = PendingIntent.getBroadcast(
            app,
            REQUEST_CODE,
            clearIntent(),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarms.cancel(operation)
    }

    private fun clearIntent(): Intent = Intent(app, ClipboardClearReceiver::class.java).setAction(ACTION_CLEAR)

    companion object {
        const val ACTION_CLEAR = "io.github.dilshodbekmx.passwordvault.action.CLEAR_CLIPBOARD"

        /** The clip's token, as in its extras. */
        const val EXTRA_TOKEN = "token"

        /** The planned clear, `SystemClock.elapsedRealtime()`; an alarm does not outlive a restart of the phone. */
        const val EXTRA_CLEAR_AT = "clearAtElapsed"

        private const val REQUEST_CODE = 0

        /** 1 + 0.75: the alarm's window, 0.75 of its distance from now, then ends at the deadline. */
        private const val WINDOW_FACTOR = 1.75
    }
}
