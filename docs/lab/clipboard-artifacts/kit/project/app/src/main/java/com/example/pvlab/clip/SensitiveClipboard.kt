package com.example.pvlab.clip

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.annotation.MainThread
import java.util.UUID

/**
 * Copies a secret with the sensitive flag and clears the clipboard a fixed time later, also when the app
 * is in the background, cached, frozen, or its process has been killed.
 *
 * Two paths lead to the same [clear]:
 *  - an alarm to [ClipboardClearReceiver], which the system delivers whatever state the process is in:
 *    exact where exact alarms are allowed, otherwise an inexact alarm aimed so that its delivery window
 *    ends at the deadline;
 *  - a timer in the process, which needs no alarm delivery but only runs while the process is alive and
 *    not frozen.
 *
 * (Lab build: the lines marked LAB-ONLY write the measurements and are not part of the recommendation.)
 */
object SensitiveClipboard {
    /** Value of ClipDescription.EXTRA_IS_SENSITIVE (constant added in API 33; older releases ignore the extra). */
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    /** Marks a clip as ours, so that a clear can leave alone what the user copied later, when that can be read. */
    private const val EXTRA_COPY_ID = "com.example.pvlab.clip.extra.COPY_ID"

    const val ACTION_CLEAR = "com.example.pvlab.clip.action.CLEAR_SENSITIVE_CLIP"
    const val KEY_COPY_ID = "copyId"
    const val KEY_CLEAR_AT = "clearAtElapsed"

    /** Longest time [ClipboardClearReceiver] stays in onReceive waiting for the deadline. */
    const val MAX_WAIT_IN_RECEIVER_MS = 8_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var timer: Runnable? = null
    private var handledCopyId: String? = null

    /** Copies [secret] and schedules the clear [clearAfterMs] from now. */
    @MainThread
    fun copy(context: Context, label: CharSequence, secret: CharSequence, clearAfterMs: Long): String {
        val app = context.applicationContext
        val copyId = UUID.randomUUID().toString()
        val clip = ClipData.newPlainText(label, secret)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(EXTRA_IS_SENSITIVE, true)
            putString(EXTRA_COPY_ID, copyId)
        }
        app.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        val clearAt = SystemClock.elapsedRealtime() + clearAfterMs

        timer?.let(mainHandler::removeCallbacks)
        val r = Runnable {
            Lab.log("final_timer", "copyId" to copyId, "state" to Lab.importance()) // LAB-ONLY
            clear(app, copyId)
        }
        timer = r
        mainHandler.postDelayed(r, clearAfterMs)
        scheduleAlarm(app, copyId, clearAt)
        return copyId
    }

    /** Arms (or re-arms) the one alarm that clears the clip [copyId] at [clearAt] (elapsedRealtime). */
    fun scheduleAlarm(context: Context, copyId: String, clearAt: Long) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            app, 0,
            clearIntent(app).putExtra(KEY_COPY_ID, copyId).putExtra(KEY_CLEAR_AT, clearAt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, clearAt, pi)
                Lab.log("final_sched", "copyId" to copyId, "kind" to "exact", "at" to clearAt) // LAB-ONLY
                return
            } catch (e: SecurityException) {
                // Exact-alarm access was revoked between the check and the call: fall through.
            }
        }
        // No exact alarms. The system may deliver an inexact alarm set for time t as late as
        // t + 0.75 * (t - now), and when nothing else wakes the device in between that is when it
        // comes. So aim early enough for that window to end at the deadline; if the alarm comes
        // before the deadline, ClipboardClearReceiver waits or aims again.
        val now = SystemClock.elapsedRealtime()
        val at = now + ((clearAt - now) / 1.75).toLong()
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        Lab.log("final_sched", "copyId" to copyId, "kind" to "inexact", "at" to at, "clearAt" to clearAt) // LAB-ONLY
    }

    /**
     * Clears the clipboard, unless it can be seen to hold something other than the clip [copyId]
     * (null: clear whatever is there). Returns true if the clipboard was cleared by this call.
     */
    @MainThread
    fun clear(context: Context, copyId: String?): Boolean {
        val app = context.applicationContext
        cancelPending(app)
        if (copyId != null && copyId == handledCopyId) return false // the other path was here first
        val cm = app.getSystemService(ClipboardManager::class.java)
        if (copyId != null) {
            // The description can be read only with window focus from Android 10 on (always on 7 to 9).
            // null means "cannot tell" or "already empty": clear anyway.
            val current = try {
                cm.primaryClipDescription
            } catch (e: RuntimeException) {
                null
            }
            if (current != null && current.extras?.getString(EXTRA_COPY_ID) != copyId) {
                handledCopyId = copyId
                Lab.finalClear(copyId, "skipped", null, 0L, 0L) // LAB-ONLY
                return false
            }
        }
        val el0 = SystemClock.elapsedRealtime() // LAB-ONLY
        val up0 = SystemClock.uptimeMillis() // LAB-ONLY
        return try {
            if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
            handledCopyId = copyId
            Lab.finalClear(copyId, "cleared", null, el0, up0) // LAB-ONLY
            true
        } catch (e: RuntimeException) {
            Lab.finalClear(copyId, "failed", e, el0, up0) // LAB-ONLY
            false
        }
    }

    private fun cancelPending(app: Context) {
        timer?.let(mainHandler::removeCallbacks)
        timer = null
        PendingIntent.getBroadcast(
            app, 0, clearIntent(app), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )?.let { app.getSystemService(AlarmManager::class.java).cancel(it) }
    }

    private fun clearIntent(app: Context): Intent =
        Intent(app, ClipboardClearReceiver::class.java).setAction(ACTION_CLEAR)

    /** LAB-ONLY: removes the in-process timer so that the alarm path can be measured on its own. */
    fun dropTimerForLab() {
        timer?.let(mainHandler::removeCallbacks)
        timer = null
    }
}

/** Declared in the manifest, not exported: only our own PendingIntent reaches it. */
class ClipboardClearReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SensitiveClipboard.ACTION_CLEAR) return
        val app = context.applicationContext
        val copyId = intent.getStringExtra(SensitiveClipboard.KEY_COPY_ID) ?: return
        val clearAt = intent.getLongExtra(SensitiveClipboard.KEY_CLEAR_AT, 0L)
        val wait = clearAt - SystemClock.elapsedRealtime()
        Lab.log("final_alarm", "copyId" to copyId, "wait" to wait, "state" to Lab.importance()) // LAB-ONLY
        when {
            wait <= 0L -> SensitiveClipboard.clear(app, copyId)
            // An inexact alarm that came early and far from the deadline: aim again.
            wait > SensitiveClipboard.MAX_WAIT_IN_RECEIVER_MS -> SensitiveClipboard.scheduleAlarm(app, copyId, clearAt)
            // Close to the deadline: stay in the receiver until then (the process is not frozen meanwhile).
            else -> {
                val pending = goAsync()
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        SensitiveClipboard.clear(app, copyId)
                    } finally {
                        pending.finish()
                    }
                }, wait)
            }
        }
    }
}
