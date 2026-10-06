package com.example.pvlab.helper

import android.app.assist.AssistStructure
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillContext
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.util.Log
import android.view.View
import android.view.autofill.AutofillId

/**
 * Logging-only autofill service used to see exactly what an autofill service is sent.
 * It never fills anything. It answers every fill request that contains text fields with a
 * SaveInfo covering all of them, so that the framework will also offer to *save* what was typed
 * (that is what a real password-manager autofill service does).
 *
 * Log tag: LabFill
 */
class LabAutofillService : AutofillService() {

    override fun onConnected() {
        Log.i(TAG, "onConnected")
    }

    override fun onDisconnected() {
        Log.i(TAG, "onDisconnected")
    }

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        val n = ++fillCount
        val tag = "FILL#$n"
        Log.i(
            TAG,
            "$tag requestId=${request.id} flags=0x${Integer.toHexString(request.flags)} " +
                "[${flagNames(request.flags)}] contexts=${request.fillContexts.size} " +
                "inlineRequest=${request.inlineSuggestionsRequest != null}",
        )
        val textIds = ArrayList<AutofillId>()
        request.fillContexts.forEachIndexed { ci, ctx ->
            dumpContext(tag, ci, ctx, if (ci == request.fillContexts.lastIndex) textIds else null)
        }
        if (textIds.isEmpty()) {
            Log.i(TAG, "$tag -> no text fields found, answering null")
            callback.onSuccess(null)
            return
        }
        val saveInfo =
            SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_PASSWORD or SaveInfo.SAVE_DATA_TYPE_GENERIC)
                .setOptionalIds(textIds.toTypedArray())
                .setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
                .build()
        Log.i(TAG, "$tag -> answering with SaveInfo optionalIds=$textIds")
        callback.onSuccess(FillResponse.Builder().setSaveInfo(saveInfo).build())
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val n = ++saveCount
        val tag = "SAVE#$n"
        Log.i(TAG, "$tag contexts=${request.fillContexts.size} datasetIds=${request.datasetIds}")
        request.fillContexts.forEachIndexed { ci, ctx -> dumpContext(tag, ci, ctx, null) }
        callback.onSuccess()
    }

    private fun dumpContext(tag: String, ci: Int, ctx: FillContext, textIds: MutableList<AutofillId>?) {
        val s = ctx.structure
        Log.i(
            TAG,
            "$tag ctx[$ci] requestId=${ctx.requestId} activity=${s.activityComponent?.flattenToShortString()} " +
                "windows=${s.windowNodeCount}",
        )
        for (w in 0 until s.windowNodeCount) {
            val wn = s.getWindowNodeAt(w)
            Log.i(
                TAG,
                "$tag  window[$w] title=${wn.title} bounds=${wn.left},${wn.top} ${wn.width}x${wn.height} " +
                    "display=${wn.displayId}",
            )
            walk(tag, wn.rootViewNode, 1, textIds)
        }
    }

    private fun walk(tag: String, node: AssistStructure.ViewNode, depth: Int, textIds: MutableList<AutofillId>?) {
        val sb = StringBuilder()
        sb.append(tag).append(' ').append("  ".repeat(depth))
        sb.append("node autofillId=").append(node.autofillId)
        sb.append(" autofillType=").append(typeName(node.autofillType))
        sb.append(" hints=").append(node.autofillHints?.joinToString(",") ?: "null")
        sb.append(" inputType=0x").append(Integer.toHexString(node.inputType))
        sb.append(" importantForAutofill=").append(importanceName(node.importantForAutofill))
        sb.append(" idEntry=").append(node.idEntry)
        sb.append(" className=").append(node.className)
        node.text?.let { sb.append(" text=\"").append(it.toString().replace("\n", "\\n")).append('"') }
        node.hint?.let { sb.append(" hint=\"").append(it).append('"') }
        val v = node.autofillValue
        sb.append(" autofillValue=")
        when {
            v == null -> sb.append("null")
            v.isText -> sb.append('"').append(v.textValue).append('"')
            v.isToggle -> sb.append("toggle:").append(v.toggleValue)
            v.isList -> sb.append("list:").append(v.listValue)
            v.isDate -> sb.append("date:").append(v.dateValue)
            else -> sb.append(v)
        }
        if (node.isFocused) sb.append(" FOCUSED")
        if (node.visibility != View.VISIBLE) sb.append(" visibility=").append(node.visibility)
        if (node.maxTextLength >= 0) sb.append(" maxTextLength=").append(node.maxTextLength)
        node.contentDescription?.let { sb.append(" contentDescription=\"").append(it).append('"') }
        sb.append(" children=").append(node.childCount)
        Log.i(TAG, sb.toString())
        val id = node.autofillId
        if (textIds != null && id != null && node.autofillType == View.AUTOFILL_TYPE_TEXT) textIds.add(id)
        for (i in 0 until node.childCount) walk(tag, node.getChildAt(i), depth + 1, textIds)
    }

    private fun typeName(t: Int) = when (t) {
        View.AUTOFILL_TYPE_NONE -> "NONE"
        View.AUTOFILL_TYPE_TEXT -> "TEXT"
        View.AUTOFILL_TYPE_TOGGLE -> "TOGGLE"
        View.AUTOFILL_TYPE_LIST -> "LIST"
        View.AUTOFILL_TYPE_DATE -> "DATE"
        else -> "?$t"
    }

    private fun importanceName(i: Int) = when (i) {
        View.IMPORTANT_FOR_AUTOFILL_AUTO -> "AUTO"
        View.IMPORTANT_FOR_AUTOFILL_YES -> "YES"
        View.IMPORTANT_FOR_AUTOFILL_NO -> "NO"
        View.IMPORTANT_FOR_AUTOFILL_YES_EXCLUDE_DESCENDANTS -> "YES_EXCLUDE_DESCENDANTS"
        View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS -> "NO_EXCLUDE_DESCENDANTS"
        else -> "?$i"
    }

    private fun flagNames(flags: Int): String {
        val names = linkedMapOf(
            0x1 to "MANUAL_REQUEST",
            0x2 to "COMPATIBILITY_MODE_REQUEST",
            0x4 to "PASSWORD_INPUT_TYPE",
            0x10 to "VIEW_NOT_FOCUSED",
            0x40 to "SUPPORTS_FILL_DIALOG",
            0x80 to "IME_SHOWING",
            0x100 to "RESET_FILL_DIALOG_STATE",
            0x200 to "PCC_DETECTION",
            0x400 to "SCREEN_HAS_CREDMAN_FIELD",
            0x800 to "VIEW_REQUESTS_CREDMAN_SERVICE",
        )
        return names.filterKeys { flags and it != 0 }.values.joinToString("|")
    }

    companion object {
        private const val TAG = "LabFill"
        private var fillCount = 0
        private var saveCount = 0
    }
}
