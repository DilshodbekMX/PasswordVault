import android.app.UiAutomation;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.PrintStream;
import java.lang.reflect.Method;

/**
 * tools/emu/uidump: prints the nodes of the active window, one per line, as tools/emu/adb.sh's
 * pv_ui_nodes gives them: text|content-desc|resource-id|clickable|enabled|x|y (x, y: the centre).
 *
 * It does what `uiautomator dump` does (the same UiAutomation of the shell, through the platform's
 * own UiAutomationShellWrapper in /system/framework/uiautomator.jar, and only nodes visible to the
 * user, with their bounds cut to the window), except that it does not wait for the window to be
 * idle first. `uiautomator dump` waits up to 10 s for one second without an accessibility event and
 * gives up with "ERROR: could not get idle state."; on Android 9 a focused Compose text field sends
 * TYPE_WINDOW_CONTENT_CHANGED every 500 ms (its blinking cursor), so no dump succeeds while a field
 * has the focus. Run on the device by the shell user:
 *
 *   CLASSPATH=/data/local/tmp/pv-uidump.jar:/system/framework/uiautomator.jar \
 *       app_process /system/bin PvUiDump
 *
 * Prints "PVUIDUMP OK <count>" as its last line; anything else means the dump failed.
 */
public final class PvUiDump {
    private PvUiDump() {
    }

    public static void main(String[] args) throws Exception {
        Class<?> wrapperClass = Class.forName("com.android.uiautomator.core.UiAutomationShellWrapper");
        Object wrapper = wrapperClass.getDeclaredConstructor().newInstance();
        Method connect = wrapperClass.getMethod("connect");
        Method disconnect = wrapperClass.getMethod("disconnect");
        Method get = wrapperClass.getMethod("getUiAutomation");
        PrintStream out = new PrintStream(System.out, false, "UTF-8");
        int status = 1;
        connect.invoke(wrapper);
        try {
            try {
                wrapperClass.getMethod("setCompressedLayoutHierarchy", boolean.class).invoke(wrapper, false);
            } catch (ReflectiveOperationException e) {
                // An older or newer wrapper without it: the default is the full hierarchy.
            }
            UiAutomation automation = (UiAutomation) get.invoke(wrapper);
            AccessibilityNodeInfo root = null;
            for (int i = 0; i < 20 && root == null; i++) {
                root = automation.getRootInActiveWindow();
                if (root == null) Thread.sleep(100);
            }
            if (root == null) {
                out.println("PVUIDUMP ERROR no root node in the active window");
            } else {
                Rect window = new Rect();
                root.getBoundsInScreen(window);
                int[] count = new int[1];
                dump(root, window, out, count);
                out.println("PVUIDUMP OK " + count[0]);
                status = 0;
            }
        } finally {
            out.flush();
            disconnect.invoke(wrapper);
        }
        System.exit(status);
    }

    private static void dump(AccessibilityNodeInfo node, Rect window, PrintStream out, int[] count) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (!bounds.intersect(window)) bounds.setEmpty();
        String id = node.getViewIdResourceName();
        if (id == null) id = "";
        int slash = id.lastIndexOf('/');
        if (slash >= 0) id = id.substring(slash + 1);
        out.println(clean(node.getText()) + "|" + clean(node.getContentDescription()) + "|" + id + "|"
                + node.isClickable() + "|" + node.isEnabled() + "|" + bounds.centerX() + "|" + bounds.centerY());
        count[0]++;
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            if (child.isVisibleToUser()) dump(child, window, out, count);
            child.recycle();
        }
    }

    private static String clean(CharSequence text) {
        if (text == null) return "";
        StringBuilder b = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') b.append(' ');
            else if (c == '|') b.append('/');
            else b.append(c);
        }
        return b.toString();
    }
}
