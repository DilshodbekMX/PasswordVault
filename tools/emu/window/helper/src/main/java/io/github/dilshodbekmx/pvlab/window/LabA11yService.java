package io.github.dilshodbekmx.pvlab.window;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An accessibility service that is not an accessibility tool (res/xml/lab_a11y.xml), from the window
 * lab's LabA11yService.kt: what such a service can read of the app under test. [LabA11yToolService]
 * is the same service declared as a tool.
 *
 * It writes down two things, in the helper's files directory:
 *  - every event that carries something readable, as it arrives (files/a11y-KIND-events.log):
 *      EVENT TAB type TAB package TAB class TAB id TAB password=B TAB text=... TAB before=... TAB desc=...
 *            TAB from=N TAB to=N TAB added=N TAB removed=N TAB count=N TAB granularity=N
 *  - on the command "a11y-dump" ([LabCommandReceiver]), every node of every window it can reach
 *    (files/a11y-KIND-dump.txt):
 *      DUMP nonce
 *      WINDOW TAB index TAB type TAB active=B TAB focused=B TAB title TAB root-package
 *      NODE TAB depth TAB package TAB class TAB id TAB password=B TAB editable=B TAB focused=B
 *           TAB clickable=B TAB enabled=B TAB visible=B TAB text=... TAB desc=... TAB hint=...
 *           TAB state=... TAB error=... TAB pane=... TAB tooltip=... TAB bounds=[l,t][r,b]
 *      FOCUS TAB ... (the node that has the input focus, asked for directly; the fields of NODE)
 *      END nonce nodes=N windows=W
 * A text is written on one line: a line break in it as \n, a tab as \t.
 * On the command "a11y-act" it does to a text field what a screen reader does ([act]).
 */
public class LabA11yService extends AccessibilityService {
    /** The connected services by kind ("plain", "tool"); both run in the helper's one process. */
    static final Map<String, LabA11yService> RUNNING = new ConcurrentHashMap<>();

    private static final String TAG = "LabA11y";

    /** "plain": not an accessibility tool. */
    String kind() {
        return "plain";
    }

    @Override
    public void onServiceConnected() {
        RUNNING.put(kind(), this);
        Log.i(TAG, kind() + " connected");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        RUNNING.remove(kind(), this);
        Log.i(TAG, kind() + " unbound");
        return super.onUnbind(intent);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        List<CharSequence> texts = event.getText();
        CharSequence before = event.getBeforeText();
        CharSequence description = event.getContentDescription();
        int type = event.getEventType();
        boolean textEvent = type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY;
        boolean readable = (texts != null && !texts.isEmpty()) || before != null || description != null;
        // Without anything readable an event is only noise here (a focused Compose field on Android 9
        // sends a content-changed event every 500 ms for its cursor).
        if (!textEvent && !readable) return;
        String id = "?";
        try {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                id = String.valueOf(source.getViewIdResourceName());
                source.recycle();
            }
        } catch (RuntimeException e) {
            id = "!" + e.getClass().getSimpleName();
        }
        StringBuilder b = new StringBuilder("EVENT");
        b.append('\t').append(AccessibilityEvent.eventTypeToString(type));
        b.append('\t').append(event.getPackageName());
        b.append('\t').append(event.getClassName());
        b.append('\t').append(id);
        b.append('\t').append("password=").append(event.isPassword());
        b.append('\t').append("text=").append(LabFiles.oneLine(join(texts)));
        b.append('\t').append("before=").append(LabFiles.oneLine(before));
        b.append('\t').append("desc=").append(LabFiles.oneLine(description));
        // Where in the text: the change, the selection, or the stretch a cursor move went over.
        b.append('\t').append("from=").append(event.getFromIndex());
        b.append('\t').append("to=").append(event.getToIndex());
        b.append('\t').append("added=").append(event.getAddedCount());
        b.append('\t').append("removed=").append(event.getRemovedCount());
        b.append('\t').append("count=").append(event.getItemCount());
        b.append('\t').append("granularity=").append(event.getMovementGranularity());
        LabFiles.append(this, LabFiles.events(kind()), b.toString());
    }

    /**
     * What a screen reader does with a text field, done by this service: [action] on the node whose
     * view id is [id] (a resource name, or a Compose test tag that the app reports as its id), in
     * any window this service can reach. Returns the node as it is half a second later, on one line:
     *   found=B result=B password=B editable=B focused=B a11yfocused=B sel=START,END granularities=G
     *   actions=ID,ID,... text=TEXT
     * (the text last, with a line break written as \n). The actions:
     *   info              nothing, only the node
     *   focus, unfocus    ACTION_ACCESSIBILITY_FOCUS, ACTION_CLEAR_ACCESSIBILITY_FOCUS
     *   click             ACTION_CLICK (a screen reader's double tap: the field takes the input focus)
     *   prev, next        ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY, ACTION_NEXT_AT_MOVEMENT_GRANULARITY
     *                     by [granularity] (1 character, 2 word, ...), without extending the selection:
     *                     the cursor moves, as with a screen reader's "move by character"
     *   select            ACTION_SET_SELECTION from [start] to [end]: the cursor is put there
     *   settext           ACTION_SET_TEXT with [text], as a screen reader's paste or edit does
     */
    String act(String id, String action, int granularity, int start, int end, String text) {
        AccessibilityNodeInfo node = find(id);
        if (node == null) return "found=false";
        boolean result;
        Bundle args = new Bundle();
        switch (action) {
            case "info":
                result = true;
                break;
            case "focus":
                result = node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
                break;
            case "unfocus":
                result = node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS);
                break;
            case "click":
                result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                break;
            case "prev":
            case "next":
                args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, granularity);
                args.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, false);
                result = node.performAction(action.equals("prev")
                        ? AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
                        : AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, args);
                break;
            case "select":
                args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start);
                args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end);
                result = node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args);
                break;
            case "settext":
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                break;
            default:
                return "found=true result=false unknown-action=" + action;
        }
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        AccessibilityNodeInfo after = find(id);
        if (after == null) return "found=false result=" + result;
        StringBuilder actions = new StringBuilder();
        for (AccessibilityNodeInfo.AccessibilityAction a : after.getActionList()) {
            if (actions.length() > 0) actions.append(',');
            actions.append(a.getId());
        }
        return "found=true result=" + result
                + " password=" + after.isPassword()
                + " editable=" + after.isEditable()
                + " focused=" + after.isFocused()
                + " a11yfocused=" + after.isAccessibilityFocused()
                + " sel=" + after.getTextSelectionStart() + "," + after.getTextSelectionEnd()
                + " granularities=" + after.getMovementGranularities()
                + " actions=" + actions
                + " text=" + LabFiles.oneLine(after.getText());
    }

    /** The first node whose view id is [id] or ends with "/" + [id], in the windows this service reaches. */
    private AccessibilityNodeInfo find(String id) {
        List<AccessibilityWindowInfo> windows = getWindows();
        for (int i = windows.size() - 1; i >= -1; i--) {
            AccessibilityNodeInfo root;
            try {
                root = i >= 0 ? windows.get(i).getRoot() : getRootInActiveWindow();
            } catch (RuntimeException e) {
                continue;
            }
            AccessibilityNodeInfo found = root == null ? null : find(root, id, 0);
            if (found != null) return found;
        }
        return null;
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo node, String id, int depth) {
        String name = node.getViewIdResourceName();
        if (name != null && (name.equals(id) || name.endsWith("/" + id))) return node;
        if (depth > 60) return null;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child;
            try {
                child = node.getChild(i);
            } catch (RuntimeException e) {
                continue;
            }
            if (child == null) continue;
            AccessibilityNodeInfo found = find(child, id, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private static String join(List<CharSequence> texts) {
        if (texts == null) return "";
        StringBuilder b = new StringBuilder();
        for (CharSequence text : texts) {
            if (b.length() > 0) b.append(" || ");
            b.append(text);
        }
        return b.toString();
    }

    /**
     * Writes every node this service can reach into files/a11y-KIND-dump.txt and returns the
     * summary line "nodes=N windows=W".
     */
    String dump(String nonce) {
        StringBuilder out = new StringBuilder("DUMP " + nonce + "\n");
        int[] nodes = new int[1];
        int windowCount = 0;
        List<AccessibilityWindowInfo> windows = getWindows();
        for (int i = 0; i < windows.size(); i++) {
            AccessibilityWindowInfo window = windows.get(i);
            AccessibilityNodeInfo root = null;
            try {
                root = window.getRoot();
            } catch (RuntimeException e) {
                Log.w(TAG, "window " + i + ": " + e);
            }
            out.append("WINDOW\t").append(i);
            out.append('\t').append(typeName(window.getType()));
            out.append('\t').append("active=").append(window.isActive());
            out.append('\t').append("focused=").append(window.isFocused());
            out.append('\t').append(LabFiles.oneLine(window.getTitle()));
            out.append('\t').append(root == null ? "null" : String.valueOf(root.getPackageName()));
            out.append('\n');
            windowCount++;
            if (root != null) walk("NODE", root, 0, out, nodes);
        }
        if (windows.isEmpty()) {
            // Without the windows (a service that may not retrieve them), at least the active one.
            AccessibilityNodeInfo root = getRootInActiveWindow();
            out.append("WINDOW\t0\tACTIVE-ONLY\tactive=true\tfocused=?\t\t")
                    .append(root == null ? "null" : String.valueOf(root.getPackageName())).append('\n');
            windowCount++;
            if (root != null) walk("NODE", root, 0, out, nodes);
        }
        try {
            AccessibilityNodeInfo focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focus != null) line("FOCUS", focus, 0, out);
        } catch (RuntimeException e) {
            Log.w(TAG, "findFocus: " + e);
        }
        String summary = "nodes=" + nodes[0] + " windows=" + windowCount;
        out.append("END ").append(nonce).append(' ').append(summary).append('\n');
        LabFiles.replace(this, LabFiles.dump(kind()), out.toString());
        Log.i(TAG, kind() + " dump " + nonce + ": " + summary);
        return summary;
    }

    private void walk(String tag, AccessibilityNodeInfo node, int depth, StringBuilder out, int[] count) {
        line(tag, node, depth, out);
        count[0]++;
        if (depth > 60) return;
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
            } catch (RuntimeException e) {
                Log.w(TAG, "child " + i + ": " + e);
            }
            if (child == null) continue;
            walk(tag, child, depth + 1, out, count);
        }
    }

    private static void line(String tag, AccessibilityNodeInfo node, int depth, StringBuilder out) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        out.append(tag);
        out.append('\t').append(depth);
        out.append('\t').append(node.getPackageName());
        out.append('\t').append(node.getClassName());
        out.append('\t').append(node.getViewIdResourceName());
        out.append('\t').append("password=").append(node.isPassword());
        out.append('\t').append("editable=").append(node.isEditable());
        out.append('\t').append("focused=").append(node.isFocused());
        out.append('\t').append("clickable=").append(node.isClickable());
        out.append('\t').append("enabled=").append(node.isEnabled());
        out.append('\t').append("visible=").append(node.isVisibleToUser());
        out.append('\t').append("text=").append(LabFiles.oneLine(node.getText()));
        out.append('\t').append("desc=").append(LabFiles.oneLine(node.getContentDescription()));
        out.append('\t').append("hint=").append(LabFiles.oneLine(node.getHintText()));
        out.append('\t').append("state=");
        if (Build.VERSION.SDK_INT >= 30) out.append(LabFiles.oneLine(node.getStateDescription()));
        out.append('\t').append("error=").append(LabFiles.oneLine(node.getError()));
        out.append('\t').append("pane=");
        if (Build.VERSION.SDK_INT >= 28) out.append(LabFiles.oneLine(node.getPaneTitle()));
        out.append('\t').append("tooltip=");
        if (Build.VERSION.SDK_INT >= 28) out.append(LabFiles.oneLine(node.getTooltipText()));
        out.append('\t').append("bounds=").append(bounds.toShortString());
        out.append('\n');
    }

    private static String typeName(int type) {
        switch (type) {
            case AccessibilityWindowInfo.TYPE_APPLICATION: return "APPLICATION";
            case AccessibilityWindowInfo.TYPE_INPUT_METHOD: return "INPUT_METHOD";
            case AccessibilityWindowInfo.TYPE_SYSTEM: return "SYSTEM";
            case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY: return "ACCESSIBILITY_OVERLAY";
            case AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER: return "SPLIT_SCREEN_DIVIDER";
            default: return "TYPE_" + type;
        }
    }
}
