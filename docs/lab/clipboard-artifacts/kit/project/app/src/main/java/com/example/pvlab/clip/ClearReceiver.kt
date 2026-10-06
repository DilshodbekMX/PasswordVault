package com.example.pvlab.clip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Target of the alarm PendingIntent in mechanisms (b), (c) and (d). */
class ClearReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Lab.ACTION_DECOY) {
            Lab.log("decoy", "state" to Lab.importance())
            return
        }
        if (intent.action != Lab.ACTION_CLEAR) return
        if (intent.getBooleanExtra("peekOnly", false)) {
            Lab.peek(context, intent.getStringExtra("run"), "receiver")
            return
        }
        Lab.clearNow(
            context,
            intent.getStringExtra("mech") ?: "?",
            intent.getStringExtra("run"),
            intent.getLongExtra("copyEl", 0L),
            intent.getLongExtra("copyUp", 0L),
            intent.getLongExtra("delayMs", 0L),
            "receiver",
        )
    }
}
