import android.content.ClipData;
import android.content.ClipDescription;
import android.os.IBinder;
import android.os.PersistableBundle;

import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.TreeSet;

/**
 * tools/emu/clipboard: the primary clip as another app sees it, for tools/emu/clipboard/clear.sh.
 *
 * It runs on the device as the shell user (package com.android.shell) and talks to the clipboard
 * service directly, so that the app under test is neither brought to the front nor pushed to the
 * background by the reading. Android 7 to 9 let every app read the clipboard at any time; from
 * Android 10 on only the app with the focus may, and the shell, which holds
 * READ_CLIPBOARD_IN_BACKGROUND for exactly this kind of test (checked on the API 37 image: `dumpsys
 * package com.android.shell` lists it as granted). Writing is allowed to every app on every version.
 *
 *   CLASSPATH=/data/local/tmp/pv-clip.jar app_process /system/bin PvClip get
 *   CLASSPATH=/data/local/tmp/pv-clip.jar app_process /system/bin PvClip set TEXT
 *   CLASSPATH=/data/local/tmp/pv-clip.jar app_process /system/bin PvClip clear
 *
 * get prints one "key=value" line per fact and "PVCLIP OK" as its last line:
 *   has        true or false: is there a clip at all
 *   items      the number of items
 *   label      the clip's label
 *   mime       its first MIME type
 *   sensitive  true or false: the description's extra android.content.extra.IS_SENSITIVE; absent
 *              when the description has no such extra
 *   token      the description's extra io.github.dilshodbekmx.passwordvault.clipboard.TOKEN, the
 *              mark of PasswordVault's own copy; empty when there is none
 *   extras     the names of all the description's extras, sorted, comma-separated
 *   text       the first item's text, with backslash, line feed and carriage return written as
 *              \\, \n and \r; empty when there is none
 * set puts TEXT on the clipboard as a plain clip without extras, labelled "pv-clip", which is what a
 * copy in another app looks like to PasswordVault; clear empties the clipboard (an empty clip below
 * API 28, which has no clearPrimaryClip). Both print "PVCLIP OK" as their last line.
 * Anything else as the last line ("PVCLIP ERROR ...") means the call failed.
 *
 * The service's methods changed their parameters between Android 7 and 17 (a user id, an attribution
 * tag, a device id), so they are found by name and their arguments filled by type.
 */
public final class PvClip {
    private static final String PACKAGE = "com.android.shell";
    private static final String EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE";
    private static final String EXTRA_TOKEN = "io.github.dilshodbekmx.passwordvault.clipboard.TOKEN";

    private PvClip() {
    }

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, false, "UTF-8");
        int status = 1;
        try {
            Object clipboard = clipboardService();
            String command = args.length > 0 ? args[0] : "";
            if (command.equals("get") && args.length == 1) {
                get(clipboard, out);
            } else if (command.equals("set") && args.length == 2) {
                call(clipboard, "setPrimaryClip", ClipData.newPlainText("pv-clip", args[1]));
            } else if (command.equals("clear") && args.length == 1) {
                if (find(clipboard, "clearPrimaryClip") != null) {
                    call(clipboard, "clearPrimaryClip", null);
                } else {
                    call(clipboard, "setPrimaryClip", ClipData.newPlainText("", ""));
                }
            } else {
                throw new IllegalArgumentException("usage: PvClip get | set TEXT | clear");
            }
            out.println("PVCLIP OK");
            status = 0;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            out.println("PVCLIP ERROR " + cause.getClass().getName() + ": " + cause.getMessage());
        } catch (Exception e) {
            out.println("PVCLIP ERROR " + e.getClass().getName() + ": " + e.getMessage());
        }
        out.flush();
        System.exit(status);
    }

    private static void get(Object clipboard, PrintStream out) throws Exception {
        ClipData clip = (ClipData) call(clipboard, "getPrimaryClip", null);
        out.println("has=" + (clip != null));
        if (clip == null) return;
        out.println("items=" + clip.getItemCount());
        ClipDescription description = clip.getDescription();
        String label = description == null || description.getLabel() == null ? "" : description.getLabel().toString();
        out.println("label=" + escape(label));
        out.println("mime=" + (description != null && description.getMimeTypeCount() > 0 ? description.getMimeType(0) : ""));
        PersistableBundle extras = description == null ? null : description.getExtras();
        Set<String> names = new TreeSet<String>();
        if (extras != null) names.addAll(extras.keySet());
        if (names.contains(EXTRA_IS_SENSITIVE)) out.println("sensitive=" + extras.getBoolean(EXTRA_IS_SENSITIVE));
        String token = extras == null ? null : extras.getString(EXTRA_TOKEN);
        out.println("token=" + (token == null ? "" : escape(token)));
        StringBuilder all = new StringBuilder();
        for (String name : names) {
            if (all.length() > 0) all.append(',');
            all.append(name);
        }
        out.println("extras=" + all);
        CharSequence text = clip.getItemCount() > 0 ? clip.getItemAt(0).getText() : null;
        out.println("text=" + (text == null ? "" : escape(text.toString())));
    }

    private static Object clipboardService() throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "clipboard");
        if (binder == null) throw new IllegalStateException("no clipboard service");
        return Class.forName("android.content.IClipboard$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    private static Method find(Object clipboard, String name) {
        for (Method method : clipboard.getClass().getMethods()) {
            if (method.getName().equals(name)) return method;
        }
        return null;
    }

    /** Calls the service's method [name]: [clip] for a ClipData parameter, the shell's package, no attribution tag, user 0, the default device. */
    private static Object call(Object clipboard, String name, ClipData clip) throws Exception {
        Method method = find(clipboard, name);
        if (method == null) throw new NoSuchMethodException("IClipboard." + name);
        Class<?>[] types = method.getParameterTypes();
        Object[] values = new Object[types.length];
        int strings = 0;
        for (int i = 0; i < types.length; i++) {
            if (types[i] == ClipData.class) {
                values[i] = clip;
            } else if (types[i] == String.class) {
                values[i] = strings++ == 0 ? PACKAGE : null;
            } else if (types[i] == int.class) {
                values[i] = 0;
            } else {
                throw new IllegalStateException("IClipboard." + name + " takes a " + types[i].getName());
            }
        }
        return method.invoke(clipboard, values);
    }

    private static String escape(String text) {
        StringBuilder b = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') b.append("\\\\");
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else b.append(c);
        }
        return b.toString();
    }
}
