package com.example.pvlab.clip

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Mechanism (f): a foreground service of type shortService (API 34+), started while the app is in the
 * foreground at copy time. It keeps the process out of the cached state (so it is neither frozen nor
 * killed as a cached app) until it clears the clipboard and stops itself. The system allows a
 * shortService about 3 minutes before calling onTimeout.
 */
class ClearShortService : Service() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("clip", "Clipboard", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(this, "clip")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Clipboard will be cleared")
            .setSilent(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0
        try {
            ServiceCompat.startForeground(this, 42, n, type)
            Lab.log("fgs_started", "startId" to startId, "state" to Lab.importance())
        } catch (t: Throwable) {
            Lab.log("fgs_start_failed", "ex" to t.toString())
        }
        val i = intent ?: return START_NOT_STICKY
        val run = i.getStringExtra("run")
        val copyEl = i.getLongExtra("copyEl", 0L)
        val copyUp = i.getLongExtra("copyUp", 0L)
        val delayMs = i.getLongExtra("delayMs", 30_000L)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            Lab.clearNow(this, "fgs", run, copyEl, copyUp, delayMs, "shortService")
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }, (copyEl + delayMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) {
        Lab.log("fgs_timeout", "startId" to startId)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Lab.log("fgs_timeout2", "startId" to startId, "fgsType" to fgsType)
        stopSelf()
    }

    override fun onDestroy() {
        Lab.log("fgs_destroyed")
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
