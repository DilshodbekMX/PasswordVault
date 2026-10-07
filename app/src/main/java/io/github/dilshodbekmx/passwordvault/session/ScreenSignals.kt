package io.github.dilshodbekmx.passwordvault.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat

/** Whether the screen is on now: `PowerManager.isInteractive()` on the phone, a fake in tests. */
fun interface ScreenState {
    fun isOn(): Boolean
}

/**
 * The screen as `AutoLock` sees it (lock.md §10.2).
 *
 * - [screenState] is `PowerManager.isInteractive()` (API 20), read at the process-level `ON_STOP`.
 * - [onScreenOff] registers, for the life of the process, a receiver for `ACTION_SCREEN_OFF`. That
 *   broadcast reaches only receivers registered in code. It is a protected broadcast sent by the
 *   system, so a receiver that other apps cannot reach still gets it: `RECEIVER_NOT_EXPORTED`, which
 *   below API 33 is the app's own `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (build check R-9 lists
 *   it). From Android 14 the system holds it back while the app is cached, so it can arrive after
 *   the user has returned; the app then locks late, and the lock delay applies meanwhile (§2.3).
 *   The receiver runs on the main thread.
 */
object ScreenSignals {
    fun screenState(context: Context): ScreenState {
        val power = context.getSystemService(PowerManager::class.java)
        return ScreenState { power?.isInteractive ?: true } // no PowerManager (never on a phone): the screen-off signal still locks
    }

    fun onScreenOff(context: Context, action: () -> Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) action()
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}
