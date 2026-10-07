package io.github.dilshodbekmx.pvlab.window;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Build;
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
 *  - on the command "a11y-dump" ([LabCommandReceiver]), every node of every window it can reach
 *    (files/a11y-KIND-dump.txt):
 *      DUMP nonce
 *      WINDOW TAB index TAB type TAB active=B TAB focused=B TAB title TAB root-package
 *      NODE TAB depth TAB package TAB class TAB id TAB password=B TAB editable=B TAB focused=B
 *           TAB text=... TAB desc=... TAB hint=... TAB state=... TAB error=... TAB pane=... TAB tooltip=...
 *      FOCUS TAB ... (the node that has the input focus, asked for directly; the fields of NODE)
 *      END nonce nodes=N windows=W
 * A text is written on one line: a line break in it as \n, a tab as \t.
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
                || type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED;
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
        LabFiles.append(this, LabFiles.events(kind()), b.toString());
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
