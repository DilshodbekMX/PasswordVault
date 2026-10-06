package com.example.pvlab.helper

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Non-tool accessibility service that logs what it can see of the lab app.
 * Log tag: LabA11y. Each dump prints one summary line: number of nodes, number of editable
 * nodes, and the texts it could read.
 */
class LabA11yService : AccessibilityService() {
    private var lastDump = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            Log.i(TAG, "TEXT_CHANGED event: class=${event.className} text=${event.text} password=${event.isPassword}")
        }
        if (now - lastDump < 1500) return
        lastDump = now
        val roots = windows.mapNotNull { it.root }
        var nodes = 0
        var editable = 0
        val texts = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            nodes++
            if (n.isEditable) editable++
            n.text?.let { if (it.isNotBlank()) texts.add((if (n.isEditable) "EDIT:" else "") + it.toString().take(40)) }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        roots.forEach { walk(it) }
        Log.i(TAG, "dump on ${AccessibilityEvent.eventTypeToString(event.eventType)}: windows=${roots.size} nodes=$nodes editable=$editable texts=$texts")
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        Log.i(TAG, "connected")
    }

    companion object {
        private const val TAG = "LabA11y"
    }
}
