package io.github.dilshodbekmx.pvlab.window;

import android.app.assist.AssistStructure;
import android.os.Build;
import android.os.CancellationSignal;
import android.service.autofill.AutofillService;
import android.service.autofill.FillCallback;
import android.service.autofill.FillContext;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.SaveCallback;
import android.service.autofill.SaveInfo;
import android.service.autofill.SaveRequest;
import android.util.Log;
import android.view.View;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;

import java.util.ArrayList;
import java.util.List;

/**
 * A logging-only autofill service, from the window lab's LabAutofillService.kt: what an autofill
 * service is sent. It never fills anything. Every fill request that holds text fields is answered
 * with a SaveInfo over all of them, as a password manager's service does, so that the framework also
 * offers to save what was typed.
 *
 * Each request is written to logcat (tag LabFill) and to files/autofill.log, one line per request
 * and one per node:
 *
 *   FILL n package=P activity=A requestId=R flags=0xF [NAMES] contexts=C
 *   SAVE n package=P activity=A contexts=C
 *   NODE FILL|SAVE n depth=D autofillId=I type=T hints=H inputType=0xX important=M idEntry=E class=K
 *        focused=B text="..." value="..."
 */
public class LabAutofillService extends AutofillService {
    private static final String TAG = "LabFill";

    // Per process; a request's number only has to tell the lines of one request from another's.
    private static int count;

    @Override
    public void onConnected() {
        Log.i(TAG, "onConnected");
    }

    @Override
    public void onDisconnected() {
        Log.i(TAG, "onDisconnected");
    }

    @Override
    public void onFillRequest(FillRequest request, CancellationSignal cancellationSignal, FillCallback callback) {
        int n = ++count;
        List<FillContext> contexts = request.getFillContexts();
        AssistStructure last = contexts.isEmpty() ? null : contexts.get(contexts.size() - 1).getStructure();
        record("FILL " + n + " " + activityOf(last) + " requestId=" + request.getId()
                + " flags=0x" + Integer.toHexString(request.getFlags()) + " [" + flagNames(request.getFlags()) + "]"
                + " contexts=" + contexts.size());
        List<AutofillId> textIds = new ArrayList<>();
        for (int i = 0; i < contexts.size(); i++) {
            dump("FILL", n, contexts.get(i), i == contexts.size() - 1 ? textIds : null);
        }
        if (textIds.isEmpty()) {
            Log.i(TAG, "FILL " + n + " -> no text field in the structure; answering null");
            callback.onSuccess(null);
            return;
        }
        SaveInfo saveInfo = new SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_PASSWORD | SaveInfo.SAVE_DATA_TYPE_GENERIC)
                .setOptionalIds(textIds.toArray(new AutofillId[0]))
                .setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
                .build();
        Log.i(TAG, "FILL " + n + " -> answering with a SaveInfo over " + textIds.size() + " text fields");
        callback.onSuccess(new FillResponse.Builder().setSaveInfo(saveInfo).build());
    }

    @Override
    public void onSaveRequest(SaveRequest request, SaveCallback callback) {
        int n = ++count;
        List<FillContext> contexts = request.getFillContexts();
        AssistStructure last = contexts.isEmpty() ? null : contexts.get(contexts.size() - 1).getStructure();
        record("SAVE " + n + " " + activityOf(last) + " contexts=" + contexts.size());
        for (FillContext context : contexts) dump("SAVE", n, context, null);
        callback.onSuccess();
    }

    private static String activityOf(AssistStructure structure) {
        if (structure == null || structure.getActivityComponent() == null) return "package=? activity=?";
        return "package=" + structure.getActivityComponent().getPackageName()
                + " activity=" + structure.getActivityComponent().getClassName();
    }

    private void dump(String kind, int n, FillContext context, List<AutofillId> textIds) {
        AssistStructure structure = context.getStructure();
        for (int w = 0; w < structure.getWindowNodeCount(); w++) {
            walk(kind, n, structure.getWindowNodeAt(w).getRootViewNode(), 0, textIds);
        }
    }

    private void walk(String kind, int n, AssistStructure.ViewNode node, int depth, List<AutofillId> textIds) {
        StringBuilder b = new StringBuilder();
        b.append("NODE ").append(kind).append(' ').append(n);
        b.append(" depth=").append(depth);
        b.append(" autofillId=").append(node.getAutofillId());
        b.append(" type=").append(typeName(node.getAutofillType()));
        String[] hints = node.getAutofillHints();
        b.append(" hints=").append(hints == null ? "null" : String.join(",", hints));
        b.append(" inputType=0x").append(Integer.toHexString(node.getInputType()));
        if (Build.VERSION.SDK_INT >= 28) b.append(" important=").append(importanceName(node.getImportantForAutofill()));
        b.append(" idEntry=").append(node.getIdEntry());
        b.append(" class=").append(node.getClassName());
        b.append(" focused=").append(node.isFocused());
        b.append(" text=\"").append(LabFiles.oneLine(node.getText())).append('"');
        AutofillValue value = node.getAutofillValue();
        b.append(" value=");
        if (value == null) b.append("null");
        else if (value.isText()) b.append('"').append(LabFiles.oneLine(value.getTextValue())).append('"');
        else b.append(value);
        record(b.toString());
        AutofillId id = node.getAutofillId();
        if (textIds != null && id != null && node.getAutofillType() == View.AUTOFILL_TYPE_TEXT) textIds.add(id);
        for (int i = 0; i < node.getChildCount(); i++) walk(kind, n, node.getChildAt(i), depth + 1, textIds);
    }

    private void record(String line) {
        Log.i(TAG, line);
        LabFiles.append(this, LabFiles.AUTOFILL, line);
    }

    private static String typeName(int type) {
        switch (type) {
            case View.AUTOFILL_TYPE_NONE: return "NONE";
            case View.AUTOFILL_TYPE_TEXT: return "TEXT";
            case View.AUTOFILL_TYPE_TOGGLE: return "TOGGLE";
            case View.AUTOFILL_TYPE_LIST: return "LIST";
            case View.AUTOFILL_TYPE_DATE: return "DATE";
            default: return "?" + type;
        }
    }

    private static String importanceName(int importance) {
        switch (importance) {
            case View.IMPORTANT_FOR_AUTOFILL_AUTO: return "AUTO";
            case View.IMPORTANT_FOR_AUTOFILL_YES: return "YES";
            case View.IMPORTANT_FOR_AUTOFILL_NO: return "NO";
            case View.IMPORTANT_FOR_AUTOFILL_YES_EXCLUDE_DESCENDANTS: return "YES_EXCLUDE_DESCENDANTS";
            case View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS: return "NO_EXCLUDE_DESCENDANTS";
            default: return "?" + importance;
        }
    }

    /** The request flags by their names in FillRequest (some are hidden constants). */
    private static String flagNames(int flags) {
        int[] bits = {0x1, 0x2, 0x4, 0x10, 0x40, 0x80, 0x100, 0x200, 0x400, 0x800};
        String[] names = {"MANUAL_REQUEST", "COMPATIBILITY_MODE_REQUEST", "PASSWORD_INPUT_TYPE", "VIEW_NOT_FOCUSED",
                "SUPPORTS_FILL_DIALOG", "IME_SHOWING", "RESET_FILL_DIALOG_STATE", "PCC_DETECTION",
                "SCREEN_HAS_CREDMAN_FIELD", "VIEW_REQUESTS_CREDMAN_SERVICE"};
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < bits.length; i++) {
            if ((flags & bits[i]) == 0) continue;
            if (b.length() > 0) b.append('|');
            b.append(names[i]);
        }
        return b.toString();
    }
}
