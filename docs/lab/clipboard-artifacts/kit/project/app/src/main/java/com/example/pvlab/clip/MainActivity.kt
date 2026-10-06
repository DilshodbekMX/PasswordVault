package com.example.pvlab.clip

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import androidx.activity.ComponentActivity
import kotlin.concurrent.thread

/**
 * Driven from adb:
 *   am start -n PKG/com.example.pvlab.clip.MainActivity --es cmd copy --es mech alarm_set --es run R1 --el delayMs 30000
 *   --es cmd status | --es cmd io | --es cmd copyother --es run R2 (copy only, no clear scheduled)
 * Copy commands run when the window has focus, as a tap on a "copy" button would.
 */
class MainActivity : ComponentActivity() {
    private var pending: Intent? = null
    private lateinit var text: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply { textSize = 22f; setPadding(48, 200, 48, 48) }
        setContentView(text)
        text.text = "PV clipboard lab"
        // Only for a fresh launch: a task restored after its process was killed gets the old intent again.
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent) {
        when (i.getStringExtra("cmd")) {
            "status" -> Lab.status(this, i.getStringExtra("tag") ?: "")
            "io" -> {
                text.text = "IO test running"
                thread(name = "io-test") { IoTest.runAll(applicationContext) }
            }
            // Lab only: the app's "clear now" action (also what locking the vault would call).
            "clearnow" -> Lab.log("clearnow", "cleared" to SensitiveClipboard.clear(this, null))
            "copy", "copyother" -> {
                pending = i
                if (hasWindowFocus()) consume()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) consume()
    }

    private fun consume() {
        val i = pending ?: return
        pending = null
        val run = i.getStringExtra("run") ?: "R${System.currentTimeMillis()}"
        val delayMs = i.getLongExtra("delayMs", 30_000L)
        val sensitive = i.getBooleanExtra("sensitive", true)
        if (i.getStringExtra("cmd") == "copyother") {
            Lab.copy(this, run, "none", 0L, sensitive, valuePrefix = "OTHER")
            text.text = "copied OTHER value $run"
            return
        }
        val mech = i.getStringExtra("mech") ?: "none"
        if (mech == "final" || mech == "final_notimer" || mech == "final_decoy") {
            // The candidate for the real app, driven exactly as the app would drive it.
            val value = "PVLAB-$run-S3CRET"
            Lab.registerListener(this)
            val el = SystemClock.elapsedRealtime()
            val up = SystemClock.uptimeMillis()
            val copyId = SensitiveClipboard.copy(this, "pvlab:$run", value, delayMs)
            if (mech != "final") SensitiveClipboard.dropTimerForLab()
            if (mech == "final_decoy") {
                // Lab only: another inexact wake-up alarm whose window ends inside the window of the clear
                // alarm. The system then delivers both together, i.e. the clear alarm arrives early.
                val decoyMs = i.getLongExtra("decoyMs", 10_900L)
                val am = getSystemService(AlarmManager::class.java)
                am.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, el + decoyMs,
                    PendingIntent.getBroadcast(
                        applicationContext, 7,
                        Intent(applicationContext, ClearReceiver::class.java).setAction(Lab.ACTION_DECOY),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                Lab.log("decoy_set", "run" to run, "at" to (el + decoyMs), "windowEnd" to (el + (decoyMs * 1.75).toLong()))
            }
            Lab.log(
                "copy", "run" to run, "mech" to mech, "delayMs" to delayMs, "copyEl" to el, "copyUp" to up,
                "value" to value, "sensitive" to true, "threw" to null, "sdk" to Build.VERSION.SDK_INT,
                "copyId" to copyId,
            )
            Lab.log(
                "scheduled", "run" to run, "mech" to mech, "at" to (el + delayMs),
                "info" to "canScheduleExactAlarms=${Lab.canExact(this)}", "threw" to null,
            )
            text.text = "copied $run, clear by $mech in ${delayMs / 1000}s"
            return
        }
        val r = Lab.copy(this, run, mech, delayMs, sensitive)
        Lab.schedule(this, r)
        text.text = "copied $run, clear by $mech in ${delayMs / 1000}s"
    }
}
