package com.example.pvlab.clip

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Clipboard lab: copy a sensitive value, schedule a clear by one of several mechanisms,
 * and log everything as one JSON object per line under tag PVClip.
 */
object Lab {
    const val TAG = "PVClip"

    /** ClipDescription.EXTRA_IS_SENSITIVE (constant added in API 33; the extra is honoured from 33). */
    const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    const val ACTION_CLEAR = "com.example.pvlab.clip.action.CLEAR_CLIPBOARD"
    const val ACTION_DECOY = "com.example.pvlab.clip.action.DECOY"
    const val WORK_NAME = "clipboard-clear"

    data class Run(
        val run: String,
        val mech: String,
        val delayMs: Long,
        val copyEl: Long,
        val copyUp: Long,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null

    /** Package of this process, set by LabApp; lets the host tell the app under test from the watcher app. */
    @Volatile
    var pkg: String = "?"

    fun log(ev: String, vararg kv: Pair<String, Any?>) {
        val o = JSONObject()
        o.put("ev", ev)
        o.put("pkg", pkg)
        o.put("pid", Process.myPid())
        o.put("el", SystemClock.elapsedRealtime())
        o.put("up", SystemClock.uptimeMillis())
        for ((k, v) in kv) o.put(k, v ?: JSONObject.NULL)
        Log.i(TAG, o.toString())
    }

    fun importance(): String {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return "imp=${info.importance} lru=${info.lru} trim=${info.lastTrimLevel}"
    }

    // ---------------------------------------------------------------- copy

    fun copy(ctx: Context, run: String, mech: String, delayMs: Long, sensitive: Boolean, valuePrefix: String = "PVLAB"): Run {
        val app = ctx.applicationContext
        val cm = app.getSystemService(ClipboardManager::class.java)
        val value = "$valuePrefix-$run-S3CRET"
        val clip = ClipData.newPlainText("pvlab:$run", value)
        if (sensitive) {
            clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        }
        registerListener(app)
        val el = SystemClock.elapsedRealtime()
        val up = SystemClock.uptimeMillis()
        var threw: String? = null
        try {
            cm.setPrimaryClip(clip)
        } catch (t: Throwable) {
            threw = t.toString()
        }
        log(
            "copy", "run" to run, "mech" to mech, "delayMs" to delayMs, "copyEl" to el, "copyUp" to up,
            "value" to value, "sensitive" to sensitive, "threw" to threw, "pkg" to app.packageName,
            "sdk" to Build.VERSION.SDK_INT,
        )
        return Run(run, mech, delayMs, el, up)
    }

    /** Registered once per process, at the first copy. Logs every callback it receives. */
    fun registerListener(ctx: Context) {
        if (listener != null) return
        val app = ctx.applicationContext
        val cm = app.getSystemService(ClipboardManager::class.java)
        val l = ClipboardManager.OnPrimaryClipChangedListener {
            val has = runCatching { cm.hasPrimaryClip() }.getOrElse { "ex:$it" }
            val label = runCatching { cm.primaryClipDescription?.label?.toString() }.getOrElse { "ex:$it" }
            log("listener", "has" to has, "label" to label, "state" to importance())
        }
        cm.addPrimaryClipChangedListener(l)
        listener = l
        log("listener_registered")
    }

    // ---------------------------------------------------------------- clear

    fun clearNow(ctx: Context, mech: String, run: String?, copyEl: Long, copyUp: Long, delayMs: Long, via: String) {
        val app = ctx.applicationContext
        val cm = app.getSystemService(ClipboardManager::class.java)
        val el = SystemClock.elapsedRealtime()
        val up = SystemClock.uptimeMillis()
        var threw: String? = null
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                cm.clearPrimaryClip()
            } else {
                cm.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        } catch (t: Throwable) {
            threw = t.toString()
        }
        // What a background caller can see right after clearing (reads need window focus):
        val has = runCatching { cm.hasPrimaryClip() }.getOrElse { "ex:$it" }
        val desc = runCatching { cm.primaryClipDescription?.toString() }.getOrElse { "ex:$it" }
        log(
            "clear", "run" to run, "mech" to mech, "via" to via, "delayMs" to delayMs,
            "dt" to (el - copyEl), "late" to (el - copyEl - delayMs), "dtUp" to (up - copyUp),
            "threw" to threw, "bgHas" to has, "bgDesc" to desc, "state" to importance(),
            "thread" to Thread.currentThread().name,
        )
    }

    /** Called by SensitiveClipboard (the "final" candidate) after each clear attempt. */
    fun finalClear(copyId: String?, outcome: String, threw: Throwable?, el0: Long, up0: Long) {
        val cm = runCatching { appContext?.getSystemService(ClipboardManager::class.java) }.getOrNull()
        val has = runCatching { cm?.hasPrimaryClip() }.getOrElse { "ex:$it" }
        log(
            "final_clear", "copyId" to copyId, "outcome" to outcome, "threw" to threw?.toString(),
            "el0" to el0, "up0" to up0, "bgHas" to has, "state" to importance(),
            "thread" to Thread.currentThread().name,
        )
    }

    @Volatile
    var appContext: Context? = null

    /** Reads without clearing: what can a background caller learn about the clipboard? */
    fun peek(ctx: Context, run: String?, via: String) {
        val cm = ctx.applicationContext.getSystemService(ClipboardManager::class.java)
        val has = runCatching { cm.hasPrimaryClip() }.getOrElse { "ex:$it" }
        val desc = runCatching { cm.primaryClipDescription?.toString() }.getOrElse { "ex:$it" }
        val text = runCatching { cm.primaryClip?.getItemAt(0)?.text?.toString() }.getOrElse { "ex:$it" }
        log("peek", "run" to run, "via" to via, "has" to has, "desc" to desc, "text" to text, "state" to importance())
    }

    // ---------------------------------------------------------------- schedule

    fun clearIntent(ctx: Context, r: Run): Intent =
        Intent(ctx, ClearReceiver::class.java)
            .setAction(ACTION_CLEAR)
            .putExtra("mech", r.mech)
            .putExtra("run", r.run)
            .putExtra("copyEl", r.copyEl)
            .putExtra("copyUp", r.copyUp)
            .putExtra("delayMs", r.delayMs)

    private fun clearPi(ctx: Context, r: Run): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, 0, clearIntent(ctx, r),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun canExact(ctx: Context): Any {
        val am = ctx.getSystemService(AlarmManager::class.java)
        return if (Build.VERSION.SDK_INT >= 31) am.canScheduleExactAlarms() else "n/a(sdk<31)"
    }

    fun schedule(ctx: Context, r: Run) {
        val app = ctx.applicationContext
        val am = app.getSystemService(AlarmManager::class.java)
        val at = r.copyEl + r.delayMs
        var info: String? = null
        var threw: String? = null
        try {
            when (r.mech) {
                // (a) in-process timers
                "handler" -> mainHandler.postDelayed(
                    { clearNow(app, r.mech, r.run, r.copyEl, r.copyUp, r.delayMs, "handler") }, r.delayMs,
                )
                "coroutine" -> scope.launch {
                    delay(r.delayMs)
                    clearNow(app, r.mech, r.run, r.copyEl, r.copyUp, r.delayMs, "coroutine")
                }
                // (b) inexact wakeup alarm
                "alarm_set" -> am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, clearPi(app, r))
                // (c) exact, allowed while idle
                "alarm_exact" -> {
                    info = "canScheduleExactAlarms=${canExact(app)}"
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, clearPi(app, r))
                }
                // (d) windowed and inexact-allow-while-idle
                "alarm_window" -> am.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, 5_000L, clearPi(app, r))
                "alarm_awi" -> am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, clearPi(app, r))
                // (e) WorkManager
                "work" -> {
                    val req = OneTimeWorkRequestBuilder<ClearWorker>()
                        .setInitialDelay(r.delayMs, TimeUnit.MILLISECONDS)
                        .setInputData(
                            workDataOf(
                                "run" to r.run, "copyEl" to r.copyEl, "copyUp" to r.copyUp, "delayMs" to r.delayMs,
                            ),
                        )
                        .build()
                    WorkManager.getInstance(app).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, req)
                    info = "workId=${req.id}"
                }
                // (f) short foreground service that keeps the process running until the clear
                "fgs" -> ContextCompat.startForegroundService(
                    app, clearIntent(app, r).setClass(app, ClearShortService::class.java),
                )
                // (f') exact alarm delivered to an in-process listener (no PendingIntent)
                "alarm_listener" -> {
                    info = "canScheduleExactAlarms=${canExact(app)}"
                    am.setExact(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, at, "pvlab-clear",
                        { clearNow(app, r.mech, r.run, r.copyEl, r.copyUp, r.delayMs, "alarmListener") },
                        mainHandler,
                    )
                }
                // Lab only: a background read at the scheduled time instead of a clear.
                "alarm_peek" -> am.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, at,
                    PendingIntent.getBroadcast(
                        app, 1, clearIntent(app, r).putExtra("peekOnly", true),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                "none" -> Unit
                else -> throw IllegalArgumentException("unknown mech ${r.mech}")
            }
        } catch (t: Throwable) {
            threw = t.toString()
        }
        log("scheduled", "run" to r.run, "mech" to r.mech, "at" to at, "info" to info, "threw" to threw)
    }

    fun status(ctx: Context, tag: String) {
        val app = ctx.applicationContext
        val am = app.getSystemService(ActivityManager::class.java)
        val pm = app.getSystemService(PowerManager::class.java)
        val bucket = if (Build.VERSION.SDK_INT >= 28) {
            app.getSystemService(UsageStatsManager::class.java).appStandbyBucket
        } else {
            -1
        }
        log(
            "status", "tag" to tag, "pkg" to app.packageName, "sdk" to Build.VERSION.SDK_INT,
            "canScheduleExactAlarms" to canExact(app),
            "bgRestricted" to (if (Build.VERSION.SDK_INT >= 28) am.isBackgroundRestricted else null),
            "ignoringBatteryOpt" to pm.isIgnoringBatteryOptimizations(app.packageName),
            "standbyBucket" to bucket, "state" to importance(),
        )
    }
}
