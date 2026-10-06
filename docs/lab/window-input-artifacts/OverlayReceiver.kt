package com.example.pvlab.helper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Draws a TYPE_APPLICATION_OVERLAY window from a *different app* (different UID) so that the app
 * under test sees an untrusted overlay.
 *
 * adb shell appops set com.example.pvlab.helper SYSTEM_ALERT_WINDOW allow
 * adb shell am broadcast -n com.example.pvlab.helper/.OverlayReceiver \
 *     --ei x 100 --ei y 900 --ei w 600 --ei h 250 --ef alpha 0.5 --ez touchable false
 * adb shell am broadcast -n com.example.pvlab.helper/.OverlayReceiver --ez remove true
 *
 * touchable=false adds FLAG_NOT_TOUCHABLE, so taps pass through to the window below (the classic
 * tapjacking set-up). Since Android 12 the system itself blocks pass-through taps when the
 * overlay's window alpha is above 0.8, so alpha 0.5 is used to reach the app's own filter.
 *
 * Log tag: LabOverlay
 */
class OverlayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val wm = app.getSystemService(WindowManager::class.java)
        current?.let {
            runCatching { wm.removeView(it) }
            current = null
            Log.i(TAG, "removed previous overlay")
        }
        if (intent.getBooleanExtra("remove", false)) return
        if (!Settings.canDrawOverlays(app)) {
            Log.e(TAG, "no overlay permission: run appops set ... SYSTEM_ALERT_WINDOW allow")
            return
        }
        val touchable = intent.getBooleanExtra("touchable", false)
        val alpha = intent.getFloatExtra("alpha", 0.5f)
        val view = TextView(app).apply {
            text = "OVERLAY (helper app)"
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(255, 220, 0, 0))
            gravity = Gravity.CENTER
        }
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val lp = WindowManager.LayoutParams(
            intent.getIntExtra("w", 600),
            intent.getIntExtra("h", 250),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = intent.getIntExtra("x", 100)
            y = intent.getIntExtra("y", 900)
            this.alpha = alpha
            title = "LabOverlay"
        }
        wm.addView(view, lp)
        current = view
        Log.i(TAG, "added overlay x=${lp.x} y=${lp.y} w=${lp.width} h=${lp.height} alpha=$alpha touchable=$touchable")
    }

    companion object {
        private const val TAG = "LabOverlay"
        private var current: View? = null
    }
}
