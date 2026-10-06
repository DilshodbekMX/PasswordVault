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
