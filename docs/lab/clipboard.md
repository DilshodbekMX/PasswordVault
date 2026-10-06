# Clipboard clearing lab: which mechanism clears the clipboard N seconds after a copy

Scope: Android 17 (API 37) emulator, with checks on Android 7.0 (API 24) and Android 9 (API 28) emulators. 315 timed runs (264 on Android 17, 27 on Android 7.0, 24 on Android 9) and 33 scripted scenarios. Everything below was observed on those emulators unless it says "not tested". No physical phone was used, and the emulators never suspended their CPU (section 7).

## 1. Short answer

- **Recommended: an exact alarm plus a timer in the process.** `AlarmManager.setExactAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP, …)` with a PendingIntent to a manifest-declared receiver, and `Handler.postDelayed` as a second path to the same clear. Manifest: `USE_EXACT_ALARM`, plus `SCHEDULE_EXACT_ALARM` with `android:maxSdkVersion="32"`. On Android 17 `canScheduleExactAlarms()` was true right after install; no user action was needed.
- **Worst observed lateness of the delivered code (section 6).** Android 17: cleared in 25 of 25 runs (9 plain background, 4 after `am kill`, 1 after swiping the task away, 4 frozen by the cached-apps freezer, 2 screen off, 2 screen off and PIN-locked, 1 forced Doze, 1 battery saver with 120 s, 1 Home with 120 s), lateness +4 to +463 ms. The late cases are those where the process had been killed and had to be started again (+329 to +463 ms); with a live process, frozen or not, it was at most +31 ms. Android 9: 9 of 9, worst +904 ms (after SIGKILL). Android 7.0: 9 of 9, worst +42 ms.
- **The same Kotlin without the two permission lines** falls back to an inexact alarm aimed so that its delivery window ends at the deadline. Android 17: 68 of 68 runs cleared (37 of them with the code as delivered), +1 to +447 ms, in the same conditions. This path relies on how Android 17 sizes the window of an inexact alarm, which is observed behaviour and not a documented promise, and it was not run on Android 12 to 16.
- **What does not do the job** (Android 17): an in-process timer alone never clears once the process is killed or frozen, and with a 120 s delay it is frozen by Home alone. Plain inexact alarms (`set`, `setWindow` with 5 s, `setAndAllowWhileIdle`) cleared 21.7 to 22.5 s late every time, and 90 s late with a 120 s delay. WorkManager was on time only while the app process was alive; otherwise between 1.2 s late and not at all within 4 minutes, and it adds four permissions. An exact alarm with an in-process listener needs no permission but is lost when the app is cached or killed.
- **Nothing works** after a force-stop (alarm removed with the app, observed on Android 17 and 7.0), and for an app whose battery usage the user set to "Restricted" once it has been in the background for about a minute.
- **From Android 10 on a background app cannot see what is in the clipboard.** On Android 17 the listener got no callback when another app copied later, a background read returned nothing, and the timed clear wiped the later copy. On Android 7.0 and 9 the listener did fire and the code left the later copy alone.
- **Android 17 shows its own "copied" overlay**; with the sensitive flag the preview shows dots instead of the value.

## 2. What was done

**Devices.** Android 17: image `google/sdk_gphone64_x86_64/emu64xa:17/CE2A.260420.019/15611780:user/release-keys`, four read-only instances of the private device `pvlab_api37` (ports 5560, 5562, 5564, 5570) so that runs could go in parallel. Android 7.0 and 9: `pvlab_api24` and `pvlab_api28`, started read-only on 5566 and 5568, unlocked with the lab PIN. All of them are stopped. The device on port 5554 was never addressed.

**Test app** `com.example.pvlab.clip` (targetSdk 37, minSdk 24, WorkManager 2.12.0) in three manifest variants: `full` (USE_EXACT_ALARM + SCHEDULE_EXACT_ALARM maxSdk 32), `sea` (only SCHEDULE_EXACT_ALARM, for all API levels), `noperm` (neither). `noperm` also serves as "the other app".

**One run** (driver in Appendix B): `pm clear` both apps; launch the app; when its window has focus it copies `PVLAB-<run>-S3CRET` with the sensitive flag and schedules the clear; 1 s later it is put in the background (Home key, Settings opened, or the other app's watcher activity opened); the condition is applied; the driver waits for the app's log line of the clear, up to 40 s past the deadline; then the other app comes to the front and reads the clipboard with focus. If no clear was logged, the app is reopened and the clipboard is read again ("on reopen" in the tables).

**Measures.** Delay = `elapsedRealtime()` just before the clear call minus `elapsedRealtime()` just before `setPrimaryClip`, both taken inside the app. Lateness = delay minus requested delay. A run counts as cleared only if the other app then read an empty clipboard (on Android 7.0: an empty clip). The watcher (Appendix C) polls the clipboard every 50 ms while it has focus and logs every change, which gives a second, independent time for the moment the clip disappears. A sampler records pid, `am isfrozen` and `oom_score_adj` of the app every 2 s.

**Cross-checks.** In all 169 runs with the watcher in front, it read the secret before the deadline; where the app cleared (144 runs) the watcher saw the clip gone 0 to 54 ms after the app's logged call. 11 runs brought the app's own probe activity to the front 12 s after the copy: the secret was there in 11 of 11. No run cleared before the deadline. The clear call threw in 0 of 315 runs.

**Conditions.**
- `am kill`: at +8 s; the pid was gone, and `dumpsys alarm` still listed the alarm (`… window=0 exactAllowReason=policy_permission …`).
- Swiped away: Home, Recents key, swipe up on the card at +5 s; system log `Killing …com.example.pvlab.clip/u0a236 (adj 900): remove task`. In 5 runs the gesture missed; they are left out.
- Frozen: Home, Settings, then the other app. The app went from `oom_score_adj` 700 to 900 within about 6 s and `am isfrozen` was true from about 15 s. "Frozen (2)" is a second way that happened by itself: the app's own second activity shown at +12 s and finished, then Home.
- Screen off: `KEYCODE_SLEEP` at about +2.5 s (`mWakefulness=Asleep` until after the clear). Locked: `locksettings set-pin 1111`, power key, `deviceLocked=1` at the time of the clear.
- Doze: `dumpsys battery unplug`, screen off, `dumpsys deviceidle force-idle` (state IDLE). Battery saver: `cmd battery unplug; cmd power set-mode 1`. Restricted: `cmd appops set PKG RUN_ANY_IN_BACKGROUND ignore`. Force-stop: `am force-stop` at +8 s. SIGKILL on the old devices: `run-as PKG kill -9 PID`.

## 3. Results

Cells read "cleared / runs: lateness in ms". "On reopen" means it cleared only when the driver reopened the app after the wait.

### Android 17, 30 s, app in the background and nothing else done

| Mechanism | cleared / runs | lateness, ms |
|---|---|---|
| (a) `Handler.postDelayed` | 6/6 | +3 to +27 |
| (a) coroutine `delay` | 6/6 | +2 to +28 |
| (b) `set()` | 6/6 | +22495 to +22502 |
| (c) `setExactAndAllowWhileIdle` with USE_EXACT_ALARM | 6/6 | +1 to +4 |
| (d) `setWindow`, 5 s window | 6/6 | +21746 to +22504 |
| (d) `setAndAllowWhileIdle` | 6/6 | +22498 to +22502 |
| (e) WorkManager, 30 s initial delay | 6/6 | +23 to +46 |
| (f) `setExact` with an `OnAlarmListener` | 3/3 | +0 to +1 |
| (f) foreground service of type shortService | 3/3 | +13 to +25 |
| **Recommended code, with the permission** | 9/9 | +6 to +31 |
| Recommended code, timer removed (exact alarm only) | 4/4 | +4 to +8 |
| **Recommended code, no permission declared** | 6/6 | +3 to +28 |
| no permission, timer removed (aimed inexact alarm only) | 10/10 | +1 to +11 |
| no permission, alarm only, a second alarm planted so that the clear alarm arrives early | 7/7 | +7 to +25 |

Rows with 6 runs: 2 with Home, 2 with Settings on top, 2 with the other app on top. Rows with 3: one of each. Recommended with permission: 3 + 3 + 2, and 1 with the app left in front. An earlier version of the recommended code (it differed only in that the second path cleared a second time 1 to 2 ms later, and waited up to 10 s instead of 8 s in the receiver) did 6/6 with permission (+20 to +30) and 3/3 without (+3 to +27).

### Android 17, 30 s, harder conditions

| Mechanism | `am kill` | swiped away | frozen | frozen (2) | screen off | off + PIN lock | Doze | force-stop |
|---|---|---|---|---|---|---|---|---|
| (a) Handler | 0/2 | 0/2 | 0/3, on reopen | 0/1, on reopen | 3/3: +26 to +27 | 2/2: +26 to +27 | 1/1: +25 | - |
| (a) coroutine | 0/1 | - | 0/1, on reopen | - | - | - | - | - |
| (b) `set()` | - | - | 1/1: +22503 | 1/1: +22503 | - | - | 0/1, held until Doze ended | - |
| (c) exact alarm | 5/5: +307 to +369 | 2/2: +405 to +408 | 5/5: +3 to +11 | 1/1: +22 | 5/5: +2 to +6 | 3/3: +3 to +9 | 1/1: +2 | 0/1 |
| (d) `setWindow` 5 s | - | - | - | 1/1: +22507 | - | - | - | - |
| (d) `setAndAllowWhileIdle` | 3/3: +22813 to +22898 | 1/1: +22838 | 3/3: +22503 to +22508 | 1/1: +22525 | 3/3: +22501 to +22505 | - | 1/1: +22504 | - |
| (e) WorkManager | 3/3: +1224 to +22856 | 0/2, on reopen | 3/3: +22528 to +36926 | 1/1: +13535 | 3/3: +25 to +39 | 1/1: +51 | 1/1: +49 | - |
| (f) listener alarm | 0/1 | - | 0/2, not on reopen either | - | - | - | - | - |
| **Recommended, with permission** | 4/4: +329 to +463 | 1/1: +343 | 4/4: +4 to +8 | - | 2/2: +14 to +18 | 2/2: +23 to +28 | 1/1: +27 | 0/1 |
| same, earlier version | 3/3: +309 to +346 | 3/3: +323 to +363 | 3/3: +7 to +11 | 1/1: +14 | 3/3: +19 to +26 | 2/2: +7 to +28 | - | - |
| **Recommended, no permission** | 2/2: +369 to +447 | - | 3/3: +6 to +13 | - | - | - | - | - |
| same, earlier version | 3/3: +297 to +362 | - | 3/3: +4 to +6 | - | 2/2: +28 to +30 | - | - | - |
| no permission, alarm only | 5/5: +310 to +387 | 4/4: +322 to +396 | 6/6: +5 to +22 | - | 5/5: +3 to +5 | 3/3: +3 to +10 | 1/1: +3 | 0/1 |
| no permission, alarm only, early arrival forced | 2/2: +4 to +13 | - | - | - | - | - | - | - |

WorkManager left alone for 240 s after the process was gone (no activity started meanwhile): after `am kill` 1 of 2 cleared (+363 ms) and the other did not within 240 s; after swiping away 0 of 1. Both cleared within 0.2 s of the driver reopening the app.

### Android 17, longer delays and power states

| Mechanism | 120 s, Home only | 120 s, battery saver | 120 s, battery usage Restricted | 65 s, Restricted |
|---|---|---|---|---|
| (a) Handler | 0/1, on reopen | - | 0/1, on reopen | 1/1: +5 |
| (b) `set()` | - | 0/1 | - | - |
| (c) exact alarm | 1/1: +13 | 1/1: +4 | 0/1, on reopen | 0/1, on reopen |
| (d) `setAndAllowWhileIdle` | 1/1: +90002 | - | - | - |
| (e) WorkManager | 0/1 (ran at 166.5 s, as the driver's probe activity started) | 0/1, on reopen | 0/1, on reopen | - |
| **Recommended, with permission** | 1/1: +5 | 1/1: +12 | 0/1, on reopen | 1/1: +9 |
| **Recommended, no permission** | 1/1: +14 | - | - | - |
| no permission, alarm only | 1/1: +5 | 1/1: +6 | 0/1, on reopen | 0/1, on reopen |

The wait was 165 s for the 120 s runs and 100 s for the 65 s runs.

### Android 7.0 and Android 9, 30 s

| Android | Mechanism | background (Home, Settings, other app, in front) | `am kill` | SIGKILL | force-stop |
|---|---|---|---|---|---|
| 7.0 | (a) Handler | 3/3: +33 to +41 | refused: 1/1: +33 | 0/1 | - |
| 7.0 | (c) `setExactAndAllowWhileIdle` | 3/3: +0 to +1 | refused: 2/2: +1 to +4 | 1/1: +43 | 0/1 |
| 7.0 | **Recommended code** | 5/5: +22 to +41 | refused: 2/2: +34 | 2/2: +36 to +42 | 0/1 |
| 7.0 | same, earlier version | 3/3: +37 to +40 | refused: 1/1: +34 | - | - |
| 9 | (a) Handler | 3/3: +28 to +41 | 0/1 | 0/1 | - |
| 9 | (c) `setExactAndAllowWhileIdle` | 3/3: +1 | 2/2: +31 to +752 | 1/1: +669 | - |
| 9 | **Recommended code** | 5/5: +22 to +43 | 2/2: +632 to +634 | 2/2: +644 to +904 | - |
| 9 | same, earlier version | 3/3: +40 to +43 | 1/1: +77 | - | - |

"Refused": on Android 7.0 `am kill` left the process alive (same pid afterwards), so those are ordinary background runs; SIGKILL was used instead. On Android 7.0 the clear is `setPrimaryClip` with an empty clip: afterwards the other app read `hasPrimaryClip=true`, text `""`, label `""`. Below API 31 exact alarms need no permission.

## 4. The mechanisms one by one (Android 17)

**(a) In-process timer.** On time only while the process runs. With Home, Settings or one other app on top the process kept `oom_score_adj` 700 and both timers fired on time. It never cleared when the process was killed (0 of 5; the secret was still there after reopening the app) or frozen (0 of 5; the callback ran when the app was reopened, about 72 s after the copy). With a 120 s delay and only Home pressed, the process went to 900 after about 61 s and was frozen from about 71 s (device constants: `max_previous_time=60000`, freezer after 10 s cached); the Handler fired at 166.8 s, on reopen.
```
seconds after copy : oom_score_adj (F = am isfrozen true)
alarm_exact  4.4:700 6.4:900 8.5:900 … 14.6:900F … 28.9:900F 30.9:900    clear at 30003 ms, watcher saw it gone at 30016 ms
handler      4.6:700 6.6:900 … 14.8:900F … 69.9:900F                      no clear; cleared at 71795 ms when the app was reopened
```

**(b) and (d) Inexact alarms.** `dumpsys alarm` right after scheduling:
```
set()                  window=+22s498ms flags=0x0   whenElapsed=+29s917ms maxWhenElapsed=+52s415ms   -> cleared after 52500 ms
setWindow(…, 5000)     window=+22s499ms flags=0x0   whenElapsed=+29s908ms maxWhenElapsed=+52s407ms   -> 52504 ms
setAndAllowWhileIdle   window=+22s496ms flags=0x20  whenElapsed=+29s919ms maxWhenElapsed=+52s415ms   -> 52498 ms
setExactAndAllow…      window=0 exactAllowReason=policy_permission flags=0x5 maxWhenElapsed=+29s917ms -> 30001 ms
```
All three inexact calls got a window of 75% of the delay and were delivered at its end. The 5 s window asked of `setWindow` was replaced by the system (`min_window=+10m0s0ms` on this device, capped at 75% of the delay). With 120 s the clear came at 210.0 s. Under forced Doze `set()` was held (`policyWhenElapsed: … device_idle=+58m21s851ms`) and fired only after the driver ended Doze; under battery saver with 120 s it did not fire within 165 s. `setAndAllowWhileIdle` still fired in Doze, at the end of its window.

**(c) Exact alarm with USE_EXACT_ALARM.**
```
full   : canScheduleExactAlarms=true    dumpsys package: android.permission.USE_EXACT_ALARM: granted=true
sea    : canScheduleExactAlarms=false   java.lang.SecurityException: Caller com.example.pvlab.clip.sea needs to hold
         android.permission.SCHEDULE_EXACT_ALARM or android.permission.USE_EXACT_ALARM to set exact alarms.
         after `cmd appops set … SCHEDULE_EXACT_ALARM allow`: canScheduleExactAlarms=true, exactAllowReason=permission
noperm : canScheduleExactAlarms=false   same SecurityException
```
So with USE_EXACT_ALARM no user action is needed; with SCHEDULE_EXACT_ALARM alone the user would have to switch on "Alarms & reminders" first. On time in every condition except Restricted beyond a minute and force-stop. When the process was dead the system started a new one for the receiver (`Start proc … for broadcast {…/…ClearReceiver}`) and `clearPrimaryClip()` from that background process worked; that restart is the +0.3 to +0.5 s. A frozen process was thawed for the broadcast (+3 to +22 ms).

**(e) WorkManager.** On time while the app process was alive (the worker ran in that process). Once the process was frozen or gone the work depended on JobScheduler: +13.5 to +36.9 s frozen, +0.4 to +22.9 s after `am kill` in 4 of 5 runs, not within 240 s in the fifth, and never before reopen after swiping away (0 of 3). Trace of a run left alone:
```
+40 s    JOB …#ClearWorker…  Run time: earliest=-10s261ms   Ready: true    (no app process)
+236 s   Run time: earliest=-3m26s475ms                                    (no app process)
+241.9 s cleared, 0.2 s after the driver reopened the app
```
The device reports `min_ready_cpu_only_jobs_count=3` and `max_cpu_only_job_batch_delay_ms=1860000`; that JobScheduler was holding the ready job for a batch is my reading of these, not something I proved. What `work-runtime` 2.12.0 merges into the manifest (from the library's own manifest and the merger report; the four permissions show as granted in `dumpsys package`): permissions WAKE_LOCK, ACCESS_NETWORK_STATE, RECEIVE_BOOT_COMPLETED, FOREGROUND_SERVICE; `SystemJobService` (exported, guarded by BIND_JOB_SERVICE), `SystemForegroundService`, `ForceStopRunnable$BroadcastReceiver`, `RescheduleReceiver` (BOOT_COMPLETED, disabled), `DiagnosticsReceiver` (exported, guarded by DUMP), the `WorkManagerInitializer` entry of the androidx.startup provider, and through Room `MultiInstanceInvalidationService`. WorkManager also re-schedules unfinished work after a force-stop, which is why the driver clears app data before every run.

**(f) Others.** `setExact` with an `OnAlarmListener` needs no permission (`exactAllowReason=listener`, also in the `noperm` variant) and was on time while the app was alive, but it was gone after `am kill` and after the app was cached (0 of 2, not even on reopen; `cached_listener_removal_delay=+10s0ms`). A shortService foreground service started at copy time cleared on time (3 of 3) but needs FOREGROUND_SERVICE and a notification; I did not take it further. The mechanism that did turn out useful is the aimed inexact alarm of the recommended code: `setAndAllowWhileIdle` for copy + delay/1.75, whose window then ends at the deadline (`origWhen=+17s60ms window=+12s855ms … maxWhenElapsed=+29s915ms -> cleared after 30011 ms`). In ordinary runs it was delivered at the end of that window, on the deadline. When I planted a second inexact alarm to pull it early (9 runs), it came 6 to 11 s before the deadline and the receiver waited or aimed again, clearing +4 to +25 ms late, also in a freshly started process.

## 5. The other questions

**"Copied" overlay (Android 17).** Yes. About 1 s after the copy SystemUI shows a preview card bottom left with "Share" and "Send to nearby device" buttons, and it was gone 12 s after. With the sensitive flag the UI tree has `com.android.systemui:id/hidden_preview` with text `••••••`; without the flag it has `text_preview` with the full value (`PVLAB-overlay-plain.150129.70-S3CRET`). Screenshots: `shots/overlay-sensitive.145527.60-1s.png` and `shots/overlay-plain.145544.60-1s.png` in the lab directory. No overlay appears when the clipboard is cleared.

**What `clearPrimaryClip()` leaves behind.** Nothing on Android 17 and 9: a focused app then gets `hasPrimaryClip()=false`, `primaryClip=null`, `primaryClipDescription=null`, and a listener in a focused app gets one callback. On Android 7.0 the empty clip stays (`hasPrimaryClip()=true`, text `""`). On Android 17 a background caller sees `hasPrimaryClip()=false` and a null description both before and after, so it cannot check its own clear; only the read from a focused app shows it.

**Listener for a later copy.**
```
Android 17  other app copied at +8545 ms; callbacks of our listener after we went to the background: none
            background read at the deadline: hasPrimaryClip=false text=null description=null (the watcher shows the other value)
            our clear at 30003 ms; watcher: other app's value -> empty at 30031 ms
Android 9   listener callback at +8690 ms (has=true, label of the other clip, importance 400 = background)
            background read at the deadline: has=true text="OTHER-…-S3CRET"
```
Android 17: no callback in 7 of 7 scenarios, and the later copy was wiped whenever our app was in the background at the deadline (3 of 3). Android 7.0 and 9: callback in 6 of 6 scenarios each. The recommended code therefore marks its own clip with an id in the clip description and skips the clear when it can read a description that is not its own: on Android 7.0 and 9 that left the later copy alone (2 of 2 each); on Android 17 it does so only when our app is in front at the deadline (3 of 3). On Android 17 the listener was called twice for each own copy in the foreground; I do not know why.

**Copy again, and clear now (Android 17, same clipboard file in a build with two extra test commands).** A second copy 10 s after the first: nothing happened at the first deadline and the clear came 30005 to 30029 ms after the second copy (3 of 3, one without permission). `clear(context, null)` 6 s after a copy emptied the clipboard, the alarm was gone from `dumpsys alarm`, and what the other app copied afterwards was still there at +37 s (2 of 2).

## 6. Recommendation

Use the code below. It is the file measured above, with the lab's logging lines removed and the package name changed; nothing else differs (the generated file compiles). The earlier version mentioned in the tables differs only as described there.

`app/src/main/java/io/github/dilshodbekmx/passwordvault/clipboard/SensitiveClipboard.kt`
```kotlin
package io.github.dilshodbekmx.passwordvault.clipboard

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
 */
object SensitiveClipboard {
    /** Value of ClipDescription.EXTRA_IS_SENSITIVE (constant added in API 33; older releases ignore the extra). */
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    /** Marks a clip as ours, so that a clear can leave alone what the user copied later, when that can be read. */
    private const val EXTRA_COPY_ID = "io.github.dilshodbekmx.passwordvault.extra.COPY_ID"

    const val ACTION_CLEAR = "io.github.dilshodbekmx.passwordvault.action.CLEAR_CLIPBOARD"
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
        val r = Runnable { clear(app, copyId) }
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
                return false
            }
        }
        return try {
            if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
            handledCopyId = copyId
            true
        } catch (e: RuntimeException) {
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
}

/** Declared in the manifest, not exported: only our own PendingIntent reaches it. */
class ClipboardClearReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SensitiveClipboard.ACTION_CLEAR) return
        val app = context.applicationContext
        val copyId = intent.getStringExtra(SensitiveClipboard.KEY_COPY_ID) ?: return
        val clearAt = intent.getLongExtra(SensitiveClipboard.KEY_CLEAR_AT, 0L)
        val wait = clearAt - SystemClock.elapsedRealtime()
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
```

`AndroidManifest.xml` (the three elements are the ones of the lab manifest; only the receiver's package differs)
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Exact alarm for the clipboard clear.
         Android 13 and later: USE_EXACT_ALARM, granted at install, no user action.
         Android 12 and 12L: SCHEDULE_EXACT_ALARM.
         Android 11 and earlier need no permission for an exact alarm. -->
    <uses-permission
        android:name="android.permission.SCHEDULE_EXACT_ALARM"
        android:maxSdkVersion="32" />
    <uses-permission android:name="android.permission.USE_EXACT_ALARM" />

    <application>
        <!-- existing attributes and activity stay as they are -->

        <!-- Target of the clear alarm. Not exported: only the app's own PendingIntent reaches it. -->
        <receiver
            android:name=".clipboard.ClipboardClearReceiver"
            android:exported="false" />
    </application>
</manifest>
```

Calls (the lab called exactly these two functions, with its own label and value):
```kotlin
SensitiveClipboard.copy(context, "Password", password, clearAfterMs = 30_000L)  // copy button, main thread
SensitiveClipboard.clear(context, null)                                         // "clear now", or when the vault locks
```

Why this combination, from the measurements:
- The alarm is what clears when the process is frozen or dead; the timer alone failed in every such run.
- The timer costs no permission and covered the one tested case where the alarm was held although the process still ran: battery usage Restricted with a 65 s delay (cleared at +9 ms, while the alarm-only variants waited for the app to be reopened).
- When both paths fire, the second call returns without clearing again (one clear per copy in the logs of the delivered code).
- Permissions: one, granted at install on Android 13 and later, plus the Android 12 line. If the app must ship with no permission at all (or go to Google Play, whose policy restricts USE_EXACT_ALARM), delete both `uses-permission` lines and keep the Kotlin: `canScheduleExactAlarms()` is then false and the aimed inexact alarm is used, with the figures of the "no permission" rows.

What the user should be told, from the measurements: a force-stop cancels the clear; with battery usage set to Restricted a clear later than about a minute after leaving the app waits until the app is opened again; on Android 10 and later, anything copied elsewhere before the timer runs out is cleared too. Delays under 5 s cannot be met by an alarm on this device (`min_futurity=+5s0ms`; not tested).

## 7. Not tested

- Any physical phone, and any vendor battery manager or task killer.
- A suspended CPU: `elapsedRealtime` and `uptimeMillis` advanced equally in all screen-off, locked and Doze runs, so the emulator never slept. The in-process timers that "worked" with the screen off may not on a phone that sleeps; the alarms are wake-up alarms.
- Android 10 to 16. In particular Android 12 and 12L, where SCHEDULE_EXACT_ALARM is the permission in force, and the no-permission path on Android 12 to 16.
- A keyboard's own clipboard history (Gboard and others).
- Re-copy and "clear now" on Android 7.0 and 9.
- Doze quotas: one run per mechanism under forced Doze; the device reports `allow_while_idle_quota=72` and `allow_while_idle_compat_quota=7` per hour, and I did not exhaust either.

## Where things are

Everything is in [`docs/lab/clipboard-artifacts/`](clipboard-artifacts/README.txt): the recommended `SensitiveClipboard.kt`, and the lab's kit unpacked in `kit/`: the complete test app sources (`kit/project/`), the driver and the analysis scripts, all run records (`kit/results/runs-emulator-*.jsonl`), the generated tables with a one-line-per-run list (`kit/results/report-tables.md`), the logs and the overlay screenshots (`kit/shots/`). The lab ran in a scratchpad under `/tmp`; build-plan package 0.3 moved the kit into the repository on 2026-10-06, and the paths inside the run records still name that scratchpad.

## Appendix A: how the lab called each mechanism (Lab.kt, abridged)

```kotlin
val at = r.copyEl + r.delayMs          // copyEl = elapsedRealtime() just before setPrimaryClip
when (r.mech) {
    "handler" -> mainHandler.postDelayed({ clearNow(app, …, "handler") }, r.delayMs)
    "coroutine" -> scope.launch { delay(r.delayMs); clearNow(app, …, "coroutine") }      // Dispatchers.Default
    "alarm_set" -> am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, clearPi(app, r))
    "alarm_exact" -> am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, clearPi(app, r))
    "alarm_window" -> am.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, 5_000L, clearPi(app, r))
    "alarm_awi" -> am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, clearPi(app, r))
    "work" -> WorkManager.getInstance(app).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<ClearWorker>().setInitialDelay(r.delayMs, TimeUnit.MILLISECONDS).setInputData(…).build())
    "fgs" -> ContextCompat.startForegroundService(app, clearIntent(app, r).setClass(app, ClearShortService::class.java))
    "alarm_listener" -> am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, "pvlab-clear", { clearNow(app, …, "alarmListener") }, mainHandler)
}
// clearPi: PendingIntent.getBroadcast(ctx, 0, Intent(ctx, ClearReceiver::class.java)…, FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE);
// ClearReceiver is declared in the manifest with exported="false" and calls clearNow.
// clearNow: cm.clearPrimaryClip() on API 28+, else cm.setPrimaryClip(ClipData.newPlainText("", "")); logs times and any exception.
// The copy: ClipData.newPlainText("pvlab:$run", value) with description.extras = { "android.content.extra.IS_SENSITIVE" = true }.
// Mechanisms "final", "final_notimer" (timer removed after the copy) and "final_decoy" call SensitiveClipboard.copy(…).
```

## Appendix B: the driver (clipexp.py)

Complete up to and including `run_once`. Left out here: the scripted scenarios (overlay, clear in the foreground, later copy by another app, re-copy, clear now, permission status), the plan tables that list which runs were made, and `main`; they are in `clipboard-artifacts/kit/clipexp.py`.

```python
#!/usr/bin/env python3
"""Host-side driver for the clipboard-clear lab.

One run: force-stop the app under test, launch it to copy a marked sensitive value and schedule a clear by
one mechanism, put it in the background, apply a condition, wait for the app's clear event, then read the
clipboard from ANOTHER app that has focus. Every run is appended to results/runs-<serial>.jsonl.
App events are JSON lines logged under tag PVClip (see Lab.kt).

Backgrounds   home        HOME key
              settings    Settings opened on top
              watch       the watcher app (another package) opened on top; it polls the clipboard every
                          50 ms with focus, so it is an independent witness of when the clip disappears
              chainwatch  HOME, Settings, then the watcher: the app under test is no longer the "previous"
                          app, becomes cached and is frozen by the cached-apps freezer after 10 s
              stay        the app stays in front
Conditions    none | kill (am kill at +8 s) | screenoff | idle (forced deep Doze) | restricted (battery
              usage "Restricted") | batsaver (battery saver on)

Usage: SERIAL=emulator-5560 python3 clipexp.py PLAN[:i/n] [PLAN...]     (i/n = run every n-th spec, offset i)
"""
import json
import os
import re
import subprocess
import sys
import threading
import time

SDK = '/home/asus/Android/Sdk'
SERIAL = os.environ.get('SERIAL', 'emulator-5560')
TAG = SERIAL[-2:]
ADB = [f'{SDK}/platform-tools/adb', '-s', SERIAL]
NS = 'com.example.pvlab.clip'
FLAVORS = {'full': NS, 'sea': NS + '.sea', 'noperm': NS + '.noperm'}
LAB = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(LAB, 'results')
SHOTS = os.path.join(LAB, 'shots')
os.makedirs(OUT, exist_ok=True)
os.makedirs(SHOTS, exist_ok=True)
RUNS = os.path.join(OUT, f'runs-{SERIAL}.jsonl')
SDK_INT = 0
BUILD = os.environ.get('BUILD', '2')  # which build of the test app is installed (2 = first full build, 3 = final candidate)


def adb(*args, timeout=60):
    try:
        r = subprocess.run(ADB + list(args), capture_output=True, text=True, timeout=timeout, errors='replace')
        return (r.stdout + r.stderr).strip()
    except subprocess.TimeoutExpired:
        return 'TIMEOUT'


def sh(cmd, timeout=60):
    return adb('shell', cmd, timeout=timeout)


def say(*a):
    print(time.strftime('%H:%M:%S'), f'[{TAG}]', *a, flush=True)


def act(pkg, cls):
    return f'{pkg}/{NS}.{cls}'


class LogTail:
    """Follows logcat (tag PVClip) in a background thread, reconnecting if adb drops, and keeps the events."""

    def __init__(self, path):
        self.events = []
        self.lock = threading.Lock()
        self.seen = set()
        self.f = open(path, 'a')
        self.stop = False
        self.p = None
        threading.Thread(target=self._loop, daemon=True).start()

    def _loop(self):
        first = True
        while not self.stop:
            try:
                self.p = subprocess.Popen(ADB + ['logcat', '-v', 'raw', '-T', '1' if first else '300', '-s', 'PVClip:I'],
                                          stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True,
                                          errors='replace')
                first = False
                for line in self.p.stdout:
                    self._line(line)
            except Exception as ex:  # noqa
                say('logtail error', ex)
            if not self.stop:
                time.sleep(2.0)

    def _line(self, line):
        line = line.strip()
        if not line.startswith('{'):
            return
        with self.lock:
            if line in self.seen:
                return
            self.seen.add(line)
        self.f.write(line + '\n')
        self.f.flush()
        try:
            e = json.loads(line)
        except ValueError:
            return
        e['_host'] = time.time()
        with self.lock:
            self.events.append(e)

    def recover(self):
        """Re-reads the device log buffer; fills in anything the tail missed (adb hiccup)."""
        out = adb('logcat', '-d', '-v', 'raw', '-s', 'PVClip:I', timeout=30)
        for line in out.splitlines():
            self._line(line)

    def find(self, pred):
        with self.lock:
            for e in self.events:
                if pred(e):
                    return e
        return None

    def all(self, pred):
        with self.lock:
            return [e for e in self.events if pred(e)]

    def wait(self, pred, timeout):
        end = time.time() + timeout
        while True:
            e = self.find(pred)
            if e or time.time() >= end:
                return e
            time.sleep(0.1)


LOG = None


class Sampler(threading.Thread):
    """Samples pid, frozen flag and oom_score_adj of the app process."""

    def __init__(self, t0, pkg, period=2.0):
        super().__init__(daemon=True)
        self.t0, self.pkg, self.period = t0, pkg, period
        self.samples = []
        self.stop_flag = False

    def run(self):
        while not self.stop_flag:
            out = sh(f'P=$(pidof {self.pkg}); F=$(am isfrozen {self.pkg} 2>&1 | head -1); '
                     f'A=$(cat /proc/$P/oom_score_adj 2>/dev/null); echo "pid=$P frozen=$F adj=$A"', timeout=20)
            self.samples.append([round(time.time() - self.t0, 1), out.replace('\n', ' ')[:120]])
            end = time.time() + self.period
            while time.time() < end and not self.stop_flag:
                time.sleep(0.1)

    def stop(self):
        self.stop_flag = True
        self.join(timeout=30)


def ensure_device():
    for _ in range(90):
        if adb('get-state', timeout=10) == 'device' and sh('getprop sys.boot_completed', timeout=10).strip() == '1':
            return
        time.sleep(2)
    raise RuntimeError(f'{SERIAL} is not available')


def screen_state():
    out = sh('dumpsys power | grep -E "mWakefulness="')
    return ' '.join(out.split())


PIN = None  # set while the 'locked' condition is in use


def lock_state():
    out = sh('dumpsys trust 2>/dev/null | grep -o "deviceLocked=[01]" | head -1; '
             'dumpsys power | grep -o "mWakefulness=[A-Za-z]*" | head -1')
    return ' '.join(out.split())


def set_pin():
    global PIN
    PIN = '1111'
    say('set-pin:', sh(f'locksettings set-pin {PIN}').splitlines()[-1:])


def clear_pin():
    global PIN
    if PIN:
        say('clear pin:', sh(f'locksettings clear --old {PIN}').splitlines()[-1:])
        PIN = None


def wake_unlock():
    st = lock_state()
    if 'mWakefulness=Awake' in st and 'deviceLocked=1' not in st:
        return
    sh('input keyevent KEYCODE_WAKEUP')
    time.sleep(0.7)
    sh('wm dismiss-keyguard')
    time.sleep(1.2)
    if PIN:
        sh(f'input text {PIN}')
        sh('input keyevent 66')
        time.sleep(1.5)


def start_watch(other, rid, ms):
    sh(f'am start -W -f 0x10008000 -n {act(other, "ProbeActivity")} --es run {rid} --ez watch true --el watchMs {int(ms)}')


def background(bg, rid, other, watch_ms):
    if bg == 'home':
        sh('input keyevent KEYCODE_HOME')
    elif bg == 'settings':
        sh('am start -W -a android.settings.SETTINGS')
    elif bg == 'watch':
        start_watch(other, rid, watch_ms)
    elif bg == 'chainwatch':
        sh('input keyevent KEYCODE_HOME')
        time.sleep(1.0)
        sh('am start -W -a android.settings.SETTINGS')
        time.sleep(1.5)
        start_watch(other, rid, watch_ms)
    elif bg == 'stay':
        pass
    else:
        raise ValueError(bg)


def probe(rid, pkg, timeout=15):
    sh(f'am start -W -f 0x10008000 -n {act(pkg, "ProbeActivity")} --es run {rid}')
    return LOG.wait(lambda e: e.get('ev') == 'probe' and e.get('run') == rid, timeout)


def alarm_lines(pkg):
    return sh(f'dumpsys alarm | grep -A7 "Alarm{{.*{pkg}}}"')[:3000]


def job_lines(pkg):
    """The pending WorkManager job as JobScheduler sees it (constraints, readiness, run time)."""
    out = sh(f'dumpsys jobscheduler | grep -A34 "^  JOB .*@{pkg}/androidx" | head -40')
    keep = [l.strip() for l in out.splitlines() if re.search(
        r'JOB |Minimum latency|Required constraints|Satisfied constraints|Unsatisfied constraints|Uid:|'
        r'Standby bucket|Enqueue time|Run time|Restricted due|Ready:', l)]
    return ' || '.join(keep)[:2500]


def jobdelay_alarm():
    """JobScheduler's own alarm for the next job whose minimum latency expires."""
    out = sh('dumpsys alarm | grep -B1 -A5 "tag=.*job.delay" | head -16')
    keep = [l.strip() for l in out.splitlines() if re.search(r'Alarm\{|tag=|type=|policyWhenElapsed|whenElapsed=', l)]
    return ' || '.join(keep)[:1200]


def save(rec):
    rec['serial'] = SERIAL
    rec['sdk'] = SDK_INT
    rec['build'] = BUILD
    with open(RUNS, 'a') as f:
        f.write(json.dumps(rec) + '\n')


def sleep_until(t):
    d = t - time.time()
    if d > 0:
        time.sleep(d)


def shot(name):
    path = os.path.join(SHOTS, name)
    with open(path, 'wb') as f:
        f.write(subprocess.run(ADB + ['exec-out', 'screencap', '-p'], capture_output=True, timeout=30).stdout)
    return path


def ui_nodes(name):
    """uiautomator dump; returns the SystemUI clipboard nodes and every non-empty text on screen."""
    # --windows: every window, so that the SystemUI clipboard overlay is included (not only the focused app).
    sh('uiautomator dump --windows /sdcard/pvui.xml' if SDK_INT >= 33 else 'uiautomator dump /sdcard/pvui.xml', timeout=30)
    xml = adb('exec-out', 'cat', '/sdcard/pvui.xml', timeout=30)
    with open(os.path.join(SHOTS, name), 'w') as f:
        f.write(xml)
    out = []
    for m in re.finditer(r'<node [^>]*?>', xml):
        n = m.group(0)

        def g(k):
            mm = re.search(k + r'="([^"]*)"', n)
            return mm.group(1) if mm else ''
        if g('text') or g('content-desc') or 'clipboard' in g('resource-id'):
            out.append([g('package'), g('resource-id'), g('text'), g('content-desc'), g('bounds')])
    return out


def classify(p, value):
    if not p:
        return 'NOPROBE'
    if p.get('has') is False:
        return 'EMPTY'
    t = p.get('text')
    if t == value:
        return 'VALUE'
    if t == '':
        return 'EMPTYTEXT'
    return f'OTHER:{t}'


def find_clear(rid, copy):
    """The app's clear event for this run, normalised (mechanisms final* log 'final_clear' keyed by copyId)."""
    e = LOG.find(lambda e: e.get('ev') == 'clear' and e.get('run') == rid)
    if e:
        return e
    cid = copy.get('copyId')
    if not cid:
        return None
    f = LOG.find(lambda e: e.get('ev') == 'final_clear' and e.get('copyId') == cid)
    if not f:
        return None
    before = LOG.all(lambda e: e.get('ev') in ('final_timer', 'final_alarm') and e.get('copyId') == cid
                     and e.get('el', 0) <= f.get('el', 0))
    via = before[-1]['ev'].replace('final_', '') if before else '?'
    if f.get('outcome') == 'skipped':
        return {'ev': 'clear', 'pid': f['pid'], 'el': f['el'], 'dt': f['el'] - copy['copyEl'],
                'late': f['el'] - copy['copyEl'] - copy['delayMs'], 'dtUp': None, 'threw': None,
                'outcome': 'skipped', 'via': via, 'state': f.get('state'), '_host': f['_host']}
    return {'ev': 'clear', 'pid': f['pid'], 'el': f['el'], 'dt': f['el0'] - copy['copyEl'],
            'late': f['el0'] - copy['copyEl'] - copy['delayMs'], 'dtUp': f['up0'] - copy['copyUp'],
            'threw': f.get('threw'), 'outcome': f.get('outcome'), 'via': via, 'state': f.get('state'),
            'bgHas': f.get('bgHas'), '_host': f['_host']}


def collect(rec, rid, copy, pkg, other, t0):
    """Adds the per-run event summaries (watcher timeline, listener callbacks, restarts, final-path events)."""
    value, el0 = copy.get('value'), copy['copyEl']
    w = LOG.all(lambda e: e.get('ev') == 'watch' and e.get('run') == rid)
    rec['watch'] = [[e['el'] - el0, classify(e, value), e.get('n')] for e in w]
    rec['watchFocus'] = [[e['el'] - el0, e.get('focus')] for e in
                         LOG.all(lambda e: e.get('ev') == 'watch_focus' and e.get('run') == rid)]
    rec['restarts'] = [[e['el'] - el0, e['pid']] for e in
                       LOG.all(lambda e: e.get('ev') == 'process_start' and e.get('proc') == pkg
                               and e.get('el', 0) > el0 - 1500 and e.get('_host', 0) > t0 - 5)]
    rec['listener'] = [[e['el'] - el0, e.get('has'), e.get('label'), e.get('state')] for e in
                       LOG.all(lambda e: e.get('ev') == 'listener' and e.get('pkg') == pkg
                               and e.get('_host', 0) >= t0 - 1)]
    rec['fgs'] = [[e['el'] - el0, e['ev']] for e in
                  LOG.all(lambda e: str(e.get('ev', '')).startswith('fgs') and e.get('_host', 0) >= t0 - 1)]
    rec['decoy'] = [[e['el'] - el0, e['ev'], e.get('windowEnd') and e['windowEnd'] - el0] for e in
                    LOG.all(lambda e: e.get('ev') in ('decoy', 'decoy_set') and e.get('pkg') == pkg
                            and e.get('el', 0) >= el0 - 50 and e.get('_host', 0) >= t0 - 1)]
    cid = copy.get('copyId')
    if cid:
        rec['final'] = [[e['el'] - el0, e['ev'], e.get('kind') or e.get('outcome') or e.get('wait'), e.get('at'), e['pid']]
                        for e in LOG.all(lambda e: str(e.get('ev', '')).startswith('final_') and e.get('copyId') == cid)]


def run_once(mech, bg='home', cond='none', delay_ms=30000, app='full', probe_before=None, max_wait=None,
             plan='', sensitive=True, kill_at=8.0, trace_every=None, extra_args=''):
    pkg = FLAVORS[app]
    other = FLAVORS['noperm' if app != 'noperm' else 'full']
    rid = f'{mech}.{bg}.{cond}.{time.strftime("%H%M%S")}.{TAG}'
    rec = {'run': rid, 'mech': mech, 'bg': bg, 'cond': cond, 'delayMs': delay_ms, 'plan': plan, 'app': app,
           'pkg': pkg, 'hostStart': time.strftime('%Y-%m-%d %H:%M:%S')}
    say(f'--- run {rid} app={app} delay={delay_ms}')
    ensure_device()
    if max_wait is None:
        max_wait = delay_ms / 1000 + 40
    # Clean start: both apps stopped with their data cleared (cancels alarms and jobs, and also empties
    # WorkManager's database, which would otherwise re-schedule an unfinished work of an earlier run),
    # screen on, unlocked, at home.
    rec['clearOut'] = ' '.join(sh(f'pm clear {pkg}; pm clear {other}').split())
    if cond == 'locked' and not PIN:
        set_pin()
    if cond == 'restricted':
        rec['restrictOut'] = sh(f'cmd appops set {pkg} RUN_ANY_IN_BACKGROUND ignore; cmd appops get {pkg} RUN_ANY_IN_BACKGROUND')
    if cond == 'batsaver':
        rec['batsaverOut'] = sh('cmd battery unplug; cmd power set-mode 1; settings get global low_power')
    wake_unlock()
    sh('input keyevent KEYCODE_HOME')
    time.sleep(1.0)

    # 1. Copy with the app in front and focused.
    sh(f'am start -W -n {act(pkg, "MainActivity")} --es cmd copy --es mech {mech} --es run {rid} --el delayMs {delay_ms}'
       + ('' if sensitive else ' --ez sensitive false') + extra_args)
    copy = LOG.wait(lambda e: e.get('ev') == 'copy' and e.get('run') == rid, 15)
    if not copy:
        LOG.recover()
        copy = LOG.find(lambda e: e.get('ev') == 'copy' and e.get('run') == rid)
    sched = LOG.wait(lambda e: e.get('ev') == 'scheduled' and e.get('run') == rid, 5)
    rec['copy'], rec['scheduled'] = copy, sched
    if not copy:
        rec['error'] = 'no copy event'
        save(rec)
        say('  ERROR no copy event')
        return rec
    t0 = copy['_host']
    pid0 = copy['pid']
    value = copy.get('value')
    if mech.startswith('alarm') or mech.startswith('final'):
        rec['alarmAtSchedule'] = alarm_lines(pkg)
    if mech == 'work':
        rec['jobAtSchedule'] = job_lines(pkg)

    # 2. Background.
    sleep_until(t0 + 1.0)
    background(bg, rid, other, (max_wait + 6) * 1000)
    rec['bgAt'] = round(time.time() - t0, 1)
    sampler = Sampler(t0, pkg, period=6.0 if cond in ('screenoff', 'idle', 'locked') else 2.0)
    sampler.start()

    # 3. Condition.
    if cond in ('kill', 'kill9', 'forcestop'):
        sleep_until(t0 + kill_at)
        rec['pidBeforeKill'] = sh(f'pidof {pkg}')
        if cond == 'forcestop':
            # What Settings > Apps > Force stop does: kills the app AND removes its alarms and jobs.
            rec['killOut'] = sh(f'am force-stop {pkg}')
        elif cond == 'kill9':
            # SIGKILL from the app's own uid (the test app is debuggable): the process just dies, as when
            # the low-memory killer takes it. Used where "am kill" declines to kill (Android 7.0).
            rec['killOut'] = sh(f'run-as {pkg} kill -9 {rec["pidBeforeKill"]}')
        else:
            rec['killOut'] = sh(f'am kill {pkg}')
        time.sleep(1.0)
        rec['pidAfterKill'] = sh(f'pidof {pkg}')
        rec['alarmAfterKill'] = alarm_lines(pkg)
        rec['jobAfterKill'] = job_lines(pkg) if mech == 'work' else None
    elif cond == 'screenoff':
        sh('input keyevent KEYCODE_SLEEP')
        time.sleep(1.0)
        rec['screenAfterSleep'] = screen_state()
        rec['screenOffAt'] = round(time.time() - t0, 1)
    elif cond == 'locked':
        # Power key: screen off and, with a PIN set, locked at once.
        sh('input keyevent KEYCODE_POWER')
        time.sleep(1.5)
        rec['lockAfterPower'] = lock_state()
        rec['screenOffAt'] = round(time.time() - t0, 1)
    elif cond == 'swipe':
        # The user is at Home, opens Recents and swipes our app away.
        sleep_until(t0 + 5.0)
        rec['pidBeforeSwipe'] = sh(f'pidof {pkg}')
        sh('input keyevent KEYCODE_APP_SWITCH')
        time.sleep(1.8)
        sh('input swipe 540 1300 540 250 120')
        time.sleep(1.5)
        sh('input keyevent KEYCODE_HOME')
        time.sleep(0.6)
        rec['pidAfterSwipe'] = sh(f'pidof {pkg}')
        rec['recentsAfterSwipe'] = sh(f'dumpsys activity recents | grep -c "{pkg}/"')
        rec['alarmAfterSwipe'] = alarm_lines(pkg)
        rec['jobAfterSwipe'] = job_lines(pkg) if mech == 'work' else None
        rec['killLog'] = sh(f'logcat -d -v time -s ActivityManager:I | grep "Killing.*{pkg}/" | tail -1')
        rec['swipeDoneAt'] = round(time.time() - t0, 1)
        start_watch(other, rid, (max_wait + 6) * 1000)
    elif cond == 'idle':
        sh('dumpsys battery unplug')
        sh('input keyevent KEYCODE_SLEEP')
        time.sleep(1.0)
        rec['forceIdle'] = sh('dumpsys deviceidle force-idle')
        rec['idleState'] = sh('dumpsys deviceidle get deep') + ' / light=' + sh('dumpsys deviceidle get light')
        rec['screenAfterSleep'] = screen_state()
        rec['idleAt'] = round(time.time() - t0, 1)
        if mech.startswith('alarm') or mech.startswith('final'):
            rec['alarmInIdle'] = alarm_lines(pkg)

    if probe_before:
        # Same package on purpose: another app's probe would take our "previous app" slot.
        sleep_until(t0 + probe_before)
        rec['probeBefore'] = probe(rid + '-pre', pkg)
        rec['probeBeforeAt'] = round(time.time() - t0, 1)
        time.sleep(0.5)
        background('home' if bg not in ('settings',) else 'settings', rid, other, 0)

    # 4. Wait for the clear.
    end = t0 + max_wait
    clear = None
    next_trace = time.time() if trace_every else None
    rec['trace'] = []
    while time.time() < end:
        clear = find_clear(rid, copy)
        if clear:
            break
        if next_trace and time.time() >= next_trace:
            # What the system holds for this run while nothing else happens on the device.
            rec['trace'].append([round(time.time() - t0, 1), sh(f'pidof {pkg}'),
                                 job_lines(pkg) if mech == 'work' else alarm_lines(pkg),
                                 jobdelay_alarm() if mech == 'work' else ''])
            next_trace = time.time() + trace_every
        time.sleep(0.1)
    if not clear:
        LOG.recover()
        clear = find_clear(rid, copy)
    rec['clear'] = clear
    rec['clearSeenAt'] = round(time.time() - t0, 1)
    if cond == 'idle':
        rec['idleStateAtEnd'] = sh('dumpsys deviceidle get deep')
    if cond in ('screenoff', 'idle'):
        rec['screenAtEnd'] = screen_state()
    if cond == 'locked':
        rec['lockAtEnd'] = lock_state()
    if not clear and (mech.startswith('alarm') or mech.startswith('final')):
        rec['alarmPendingAtTimeout'] = alarm_lines(pkg)
    if not clear and mech == 'work':
        rec['jobPendingAtTimeout'] = job_lines(pkg)
    time.sleep(1.0)  # let the watcher log the change
    sampler.stop()
    rec['samples'] = sampler.samples

    # 5. Restore the device, then read the clipboard from the other app (the app under test is not woken).
    if cond == 'idle':
        rec['unforce'] = sh('dumpsys deviceidle unforce') + ' | ' + sh('dumpsys battery reset')
    if cond in ('screenoff', 'idle', 'locked'):
        wake_unlock()
        time.sleep(0.5)
        rec['lockAfterUnlock'] = lock_state()
    rec['probeAt'] = round(time.time() - t0, 1)
    rec['probe'] = probe(rid, other)
    if not clear:
        # Does the clear happen once the user returns to the app? (an in-process timer fires on thaw)
        sh(f'am start -W -n {act(pkg, "MainActivity")}')
        end = time.time() + 6
        late = None
        while time.time() < end and not late:
            late = find_clear(rid, copy)
            time.sleep(0.1)
        rec['clearOnReturn'] = late
        rec['returnAt'] = round(time.time() - t0, 1)
        rec['probeAfterReturn'] = probe(rid + '-ret', other)
    collect(rec, rid, copy, pkg, other, t0)
    if cond == 'restricted':
        sh(f'cmd appops set {pkg} RUN_ANY_IN_BACKGROUND allow')
    if cond == 'batsaver':
        sh('cmd power set-mode 0; cmd battery reset')

    c = clear
    # A poll that lands while our own probe is taking the focus away reads "empty": ignore those.
    gone = next((w for w in rec['watch'] if w[1] != 'VALUE' and w[0] < rec['probeAt'] * 1000 - 250), None)
    seen = [w for w in rec['watch'] if w[1] == 'VALUE']
    adjs = [int(m.group(1)) for s in rec['samples'] for m in [re.search(r'adj=(-?\d+)', s[1])] if m]
    rec['result'] = {
        'cleared': bool(c), 'dt': c['dt'] if c else None, 'late': c['late'] if c else None,
        'threw': c.get('threw') if c else None, 'via': c.get('via') if c else None,
        'outcome': c.get('outcome') if c else None,
        'pidChanged': (c['pid'] != pid0) if c else None,
        'probe': classify(rec['probe'], value),
        'watchFirst': rec['watch'][0][1] if rec['watch'] else None,
        'watchValueFrom': seen[0][0] if seen else None,
        'watchGoneDt': gone[0] if gone else None, 'watchGoneTo': gone[1] if gone else None,
        'frozenSeen': any('frozen=true' in s[1] for s in rec['samples']),
        'adjMax': max(adjs) if adjs else None,
        'clearOnReturnDt': rec['clearOnReturn']['dt'] if rec.get('clearOnReturn') else None,
        'probeAfterReturn': classify(rec.get('probeAfterReturn'), value) if 'probeAfterReturn' in rec else None,
        'probeBefore': classify(rec.get('probeBefore'), value) if 'probeBefore' in rec else None,
    }
    save(rec)
    sh('input keyevent KEYCODE_HOME')
    r = rec['result']
    say(f'  clear={"%d ms (late %+d)" % (r["dt"], r["late"]) if c else "NONE"} via={r["via"]} threw={r["threw"]} '
        f'pidChanged={r["pidChanged"]} probe={r["probe"]} watch={r["watchFirst"]}->{r["watchGoneTo"]}@{r["watchGoneDt"]} '
        f'frozen={r["frozenSeen"]} adjMax={r["adjMax"]} onReturn={r["clearOnReturnDt"]} pre={r["probeBefore"]}')
    return rec
```

## Appendix C: the watcher (ProbeActivity.kt, complete)

```kotlin
package com.example.pvlab.clip

import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Reads the clipboard once it has window focus (reads are refused without focus) and logs it.
 *  - one-shot (default): read once, finish.
 *  - watch (--ez watch true --el watchMs N): stay in front and poll every 50 ms while focused, logging every
 *    change. Run from ANOTHER package than the app under test, this is an independent witness of when the
 *    clip really disappears, and it does not wake the app under test.
 */
class ProbeActivity : ComponentActivity() {
    private var done = false
    private lateinit var text: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var watching = false
    private var lastKey: String? = null
    private var polls = 0
    private var endAt = 0L
    private val runId: String? get() = intent.getStringExtra("run")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply { textSize = 22f; setPadding(48, 200, 48, 48) }
        setContentView(text)
        Lab.log("probe_create", "run" to runId, "state" to Lab.importance())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (intent.getBooleanExtra("watch", false)) {
            Lab.log("watch_focus", "run" to runId, "focus" to hasFocus, "polls" to polls)
            if (hasFocus && !watching) {
                watching = true
                if (endAt == 0L) endAt = SystemClock.elapsedRealtime() + intent.getLongExtra("watchMs", 60_000L)
                handler.post(poll)
            } else if (!hasFocus) {
                watching = false
                handler.removeCallbacks(poll)
                lastKey = null // log the state again when focus returns
            }
            return
        }
        if (!hasFocus || done) return
        done = true
        val cm = getSystemService(ClipboardManager::class.java)
        val has = cm.hasPrimaryClip()
        val desc = cm.primaryClipDescription
        val clip = cm.primaryClip
        val item = if (clip != null && clip.itemCount > 0) clip.getItemAt(0) else null
        val value = item?.coerceToText(this)?.toString()
        Lab.log(
            "probe", "run" to runId, "has" to has,
            "clipNull" to (clip == null), "items" to clip?.itemCount, "text" to value,
            "label" to desc?.label?.toString(),
            "mime" to (if (desc != null && desc.mimeTypeCount > 0) desc.getMimeType(0) else null),
            "sensitive" to desc?.extras?.getBoolean(Lab.EXTRA_IS_SENSITIVE),
            "extraKeys" to desc?.extras?.keySet()?.sorted()?.joinToString(","),
            "descTs" to (if (desc != null && Build.VERSION.SDK_INT >= 26) desc.timestamp else null),
            "desc" to desc?.toString(),
        )
        text.text = "probe: has=$has value=$value"
        if (!intent.getBooleanExtra("stay", false)) finish()
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!watching) return
            val cm = getSystemService(ClipboardManager::class.java)
            val has = cm.hasPrimaryClip()
            val desc = cm.primaryClipDescription
            val ts = if (desc != null && Build.VERSION.SDK_INT >= 26) desc.timestamp else 0L
            val key = "$has|${desc?.label}|$ts"
            polls++
            if (key != lastKey) {
                lastKey = key
                val clip = cm.primaryClip
                val value = if (clip != null && clip.itemCount > 0) {
                    clip.getItemAt(0).coerceToText(this@ProbeActivity)?.toString()
                } else {
                    null
                }
                Lab.log(
                    "watch", "run" to runId, "n" to polls, "has" to has, "clipNull" to (clip == null),
                    "text" to value, "label" to desc?.label?.toString(), "ts" to ts,
                )
                text.text = "watch: has=$has value=$value"
            }
            if (SystemClock.elapsedRealtime() >= endAt) {
                Lab.log("watch_end", "run" to runId, "polls" to polls)
                watching = false
                finish()
            } else {
                handler.postDelayed(this, 50L)
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        super.onDestroy()
    }
}
```

## Unresolved

- Physical phones and vendor battery managers or task killers were not tested; every figure comes from Google and AOSP emulator images.
- A suspended CPU was not tested: the emulators never slept (elapsedRealtime and uptimeMillis advanced equally in all screen-off, locked and Doze runs), so the on-time results of in-process timers with the screen off may not hold on a phone. The alarms used are wake-up alarms.
- Android 10 to 16 were not run. Open in particular: that SCHEDULE_EXACT_ALARM with maxSdkVersion 32 is on by default on Android 12 and 12L, and that the no-permission path (inexact alarm whose window ends at the deadline) behaves on Android 12 to 16 as it did on Android 17. That window rule is observed behaviour, not a documented promise.
- Battery usage set to Restricted: once the app has been in the background for about a minute, neither the alarm nor the timer cleared until the app was reopened (120 s runs). I found no mechanism for this case; a foreground service started at copy time was not tried under Restricted.
- Why JobScheduler left WorkManager's ready job unstarted for more than 4 minutes after the process was gone is not proven; the batching constants reported by the device are my explanation.
- A keyboard's own clipboard history (Gboard and others) was not tested: whether a copied password stays there after the system clipboard is cleared.
- Doze and battery saver quotas were not stressed: one run per mechanism under forced Doze, one under battery saver with 120 s. The no-permission alarm falls under the smaller quota the device reports (allow_while_idle_compat_quota=7 per hour against 72 for the exact alarm).
- Re-copy and manual clear were run only on Android 17 (5 scenarios), not on Android 7.0 and 9.
- Unexplained observations: Android 17 called the listener twice for each own copy in the foreground; am kill left the process alive on Android 7.0; the Recents swipe gesture of the driver missed in 5 runs (those runs are excluded from the swipe columns).
- (Settled 2026-10-06.) The lab directory and the kit archive lived in the scratchpad under /tmp; the kit is now in `docs/lab/clipboard-artifacts/kit/`.
