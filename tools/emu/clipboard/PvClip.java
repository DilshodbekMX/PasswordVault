import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.widget.Toast;

import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.TreeSet;

/**
 * tools/emu/clipboard: the clipboard as another app sees and uses it, for
 * tools/emu/clipboard/clear.sh.
 *
 * It runs on the device as the shell user (package com.android.shell) and talks to the clipboard
 * service directly, so that reading neither brings the app under test to the front nor pushes it to
 * the background. Android 7 to 9 let every app read the clipboard at any time; from Android 10 on
 * only the app with the focus may, and the shell, which holds READ_CLIPBOARD_IN_BACKGROUND for this
 * kind of test (`dumpsys package com.android.shell` on the API 37 image lists it as granted).
 * Writing is allowed to every app on every version.
 *
 *   CLASSPATH=/data/local/tmp/pv-clip.jar app_process /system/bin PvClip describe
 *   CLASSPATH=/data/local/tmp/pv-clip.jar app_process /system/bin PvClip get
 *   CLASSPATH=/data/local/tmp/pv-clip.jar app_process /system/bin PvClip set TEXT
 *   CLASSPATH=/data/local/tmp/pv-clip.jar app_process /system/bin PvClip clear
 *   CLASSPATH=/data/local/tmp/pv-clip.jar app_process /system/bin PvClip toast TEXT
 *
 * describe reads the clip's description only, as PasswordVault itself does, and prints one
 * "key=value" line per fact:
 *   now        the device's wall clock at the reading, System.currentTimeMillis()
 *   elapsed    its elapsed clock, SystemClock.elapsedRealtime()
 *   has        true or false: is there a clip at all
 *   label      the clip's label
 *   mime       its first MIME type
 *   sensitive  true or false: the description's extra android.content.extra.IS_SENSITIVE; no such
 *              line when the description has no such extra
 *   token      the description's extra io.github.dilshodbekmx.passwordvault.clipboard.TOKEN, the
 *              mark of PasswordVault's own copy; empty when there is none
 *   extras     the names of all the description's extras, sorted, comma-separated
 * get reads the clip itself and adds:
 *   items      the number of items
 *   text       the first item's text, with backslash, line feed and carriage return written as
 *              \\, \n and \r; empty when there is none
 * On Android 12 and later, reading another app's clip makes the system show "Shell pasted from your
 * clipboard" once per clip; describe does not.
 * set puts TEXT on the clipboard as a plain clip without extras, labelled "pv-clip", which is what a
 * copy in another app looks like to PasswordVault. clear empties the clipboard (an empty clip below
 * API 28, which has no clearPrimaryClip). toast shows TEXT as a short toast of the shell's and
 * returns about four seconds later: the control for a check that no toast was shown.
 *
 * Every command prints "PVCLIP OK" as its last line; "PVCLIP ERROR ..." there means it failed.
 *
 * The service's methods changed their parameters between Android 7 and 17 (a user id, an attribution
 * tag, a device id), so they are found by name and their arguments filled by type.
 */
public final class PvClip {
    private static final String PACKAGE = "com.android.shell";
    private static final String EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE";
    private static final String EXTRA_TOKEN = "io.github.dilshodbekmx.passwordvault.clipboard.TOKEN";
    private static final long TOAST_MS = 4000L;

    private PvClip() {
    }

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, false, "UTF-8");
        int status = 1;
        try {
            String command = args.length > 0 ? args[0] : "";
            if (command.equals("describe") && args.length == 1) {
                Object clipboard = clipboardService();
                boolean has = Boolean.TRUE.equals(call(clipboard, "hasPrimaryClip", null));
                clocks(out);
                out.println("has=" + has);
                if (has) describe((ClipDescription) call(clipboard, "getPrimaryClipDescription", null), out);
            } else if (command.equals("get") && args.length == 1) {
                ClipData clip = (ClipData) call(clipboardService(), "getPrimaryClip", null);
                clocks(out);
                out.println("has=" + (clip != null));
                if (clip != null) {
                    describe(clip.getDescription(), out);
                    out.println("items=" + clip.getItemCount());
                    CharSequence text = clip.getItemCount() > 0 ? clip.getItemAt(0).getText() : null;
                    out.println("text=" + (text == null ? "" : escape(text.toString())));
                }
            } else if (command.equals("set") && args.length == 2) {
                call(clipboardService(), "setPrimaryClip", ClipData.newPlainText("pv-clip", args[1]));
            } else if (command.equals("clear") && args.length == 1) {
                Object clipboard = clipboardService();
                if (find(clipboard, "clearPrimaryClip") != null) {
                    call(clipboard, "clearPrimaryClip", null);
                } else {
                    call(clipboard, "setPrimaryClip", ClipData.newPlainText("", ""));
                }
            } else if (command.equals("toast") && args.length == 2) {
                toast(args[1], out);
            } else {
                throw new IllegalArgumentException("usage: PvClip describe | get | set TEXT | clear | toast TEXT");
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

    private static void clocks(PrintStream out) {
        out.println("now=" + System.currentTimeMillis());
        out.println("elapsed=" + SystemClock.elapsedRealtime());
    }

    private static void describe(ClipDescription description, PrintStream out) {
        if (description == null) return;
        out.println("label=" + (description.getLabel() == null ? "" : escape(description.getLabel().toString())));
        out.println("mime=" + (description.getMimeTypeCount() > 0 ? description.getMimeType(0) : ""));
        PersistableBundle extras = description.getExtras();
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

    /** Calls the service's method name: clip for a ClipData parameter, the shell's package, no attribution tag, user 0, the default device. */
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

    /**
     * A toast of the shell's own. The process has no application, so the context is the system
     * context of a thread made for it, named as the shell's package, which is what the notification
     * service checks against the caller's uid. It does not return: the main looper cannot be made to
     * quit, so the process ends from inside it, with the last line this command owes.
     */
    private static void toast(final String text, final PrintStream out) throws Exception {
        Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        final Context shell = new ContextWrapper(system) {
            @Override
            public String getPackageName() {
                return PACKAGE;
            }

            @Override
            public String getOpPackageName() {
                return PACKAGE;
            }

            @Override
            public Context getApplicationContext() {
                return this;
            }
        };
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(shell, text, Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    end(out, "PVCLIP ERROR no toast: " + t, 1);
                }
            }
        });
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                end(out, "PVCLIP OK", 0);
            }
        }, TOAST_MS);
        Looper.loop();
    }

    private static void end(PrintStream out, String line, int status) {
        out.println(line);
        out.flush();
        System.exit(status);
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
