package io.github.dilshodbekmx.pvlab.window;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Parcel;
import android.os.PersistableBundle;
import android.os.Process;
import android.util.Base64;
import android.util.Log;
import android.util.SparseArray;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Reads the saved-instance state of the app under test, by the method of the window lab's
 * Experiment 5 (e5_state.py), whose lab app logged every leaf of the Bundle in onSaveInstanceState.
 * PasswordVault has no such log, so this instrumentation runs in the app's process instead:
 *
 *   adb shell am instrument io.github.dilshodbekmx.pvlab.window/.StateProbe
 *   adb shell am start -n io.github.dilshodbekmx.passwordvault/.MainActivity
 *
 * It starts nothing and changes nothing: the app runs as it always does, and the activity is started
 * the way the launcher starts it. Android calls [callActivityOnSaveInstanceState] whenever it takes
 * an activity's state (a rotation, Home); after the app has written its Bundle, the complete one, with
 * the androidx saved-state registry where Compose keeps its saved state, is written to logcat (tag
 * PvWinState):
 *
 *   SAVE seq pid=P activity=CLASS leaves=N bytes=LEN   (LEN -1: the Bundle could not be flattened)
 *   L64 seq i CHUNK    Base64 of the leaves, one "path = value" per line, in UTF-8
 *   B64 seq i CHUNK    Base64 of the Bundle as a Parcel's bytes, which also holds what is inside its Parcelables
 *   END seq pid=P
 *
 * state.sh looks for the typed texts in both, the bytes in UTF-16 and in UTF-8.
 */
public class StateProbe extends Instrumentation {
    private static final String TAG = "PvWinState";
    private static final int CHUNK = 3000;

    private int seq;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        Log.i(TAG, "READY pid=" + Process.myPid() + " target=" + getTargetContext().getPackageName());
    }

    @Override
    public void callActivityOnSaveInstanceState(Activity activity, Bundle outState) {
        super.callActivityOnSaveInstanceState(activity, outState);
        report(activity, outState);
    }

    @Override
    public void callActivityOnSaveInstanceState(Activity activity, Bundle outState, PersistableBundle outPersistentState) {
        super.callActivityOnSaveInstanceState(activity, outState, outPersistentState);
        report(activity, outState);
    }

    private synchronized void report(Activity activity, Bundle state) {
        int n = ++seq;
        try {
            StringBuilder leaves = new StringBuilder();
            int[] count = new int[1];
            leavesOf("outState", state, leaves, count, 0);
            byte[] bytes = bytesOf(state);
            String pid = "pid=" + Process.myPid();
            Log.i(TAG, "SAVE " + n + " " + pid + " activity=" + activity.getClass().getName()
                    + " leaves=" + count[0] + " bytes=" + (bytes == null ? -1 : bytes.length));
            chunks("L64", n, Base64.encodeToString(leaves.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
            if (bytes != null) chunks("B64", n, Base64.encodeToString(bytes, Base64.NO_WRAP));
            Log.i(TAG, "END " + n + " " + pid);
        } catch (Throwable t) {
            // The probe must never end the app it watches.
            Log.e(TAG, "FAILED " + n + " " + t);
        }
    }

    private static void chunks(String kind, int n, String text) {
        int i = 0;
        for (int at = 0; at < text.length(); at += CHUNK) {
            Log.i(TAG, kind + " " + n + " " + (i++) + " " + text.substring(at, Math.min(text.length(), at + CHUNK)));
        }
    }

    /** Every leaf of a saved-state value as "path = value", as the lab's dumpValue logged them. */
    @SuppressWarnings("deprecation")
    private static void leavesOf(String path, Object value, StringBuilder out, int[] count, int depth) {
        if (depth > 40) {
            leaf(path, "(too deep)", out, count);
        } else if (value instanceof Bundle) {
            Bundle bundle = (Bundle) value;
            for (String key : bundle.keySet()) {
                Object item;
                try {
                    item = bundle.get(key);
                } catch (RuntimeException e) {
                    item = "(unreadable: " + e.getClass().getName() + ")";
                }
                leavesOf(path + "/" + key, item, out, count, depth + 1);
            }
        } else if (value instanceof List) {
            List<?> list = (List<?>) value;
            for (int i = 0; i < list.size(); i++) leavesOf(path + "[" + i + "]", list.get(i), out, count, depth + 1);
        } else if (value instanceof Object[]) {
            Object[] array = (Object[]) value;
            for (int i = 0; i < array.length; i++) leavesOf(path + "[" + i + "]", array[i], out, count, depth + 1);
        } else if (value instanceof SparseArray) {
            SparseArray<?> array = (SparseArray<?>) value;
            for (int i = 0; i < array.size(); i++) {
                leavesOf(path + "{" + array.keyAt(i) + "}", array.valueAt(i), out, count, depth + 1);
            }
        } else if (value instanceof char[]) {
            leaf(path, new String((char[]) value), out, count);
        } else if (value instanceof byte[]) {
            leaf(path, "bytes:" + new String((byte[]) value, StandardCharsets.ISO_8859_1), out, count);
        } else if (value instanceof int[]) {
            leaf(path, java.util.Arrays.toString((int[]) value), out, count);
        } else if (value instanceof long[]) {
            leaf(path, java.util.Arrays.toString((long[]) value), out, count);
        } else {
            String text;
            try {
                text = String.valueOf(value);
            } catch (RuntimeException e) {
                text = "(no text: " + e.getClass().getName() + ")";
            }
            leaf(path, (value == null ? "" : value.getClass().getName() + ": ") + text, out, count);
        }
    }

    private static void leaf(String path, String text, StringBuilder out, int[] count) {
        out.append(path).append(" = ").append(LabFiles.oneLine(text)).append('\n');
        count[0]++;
    }

    /** The Bundle as a Parcel's bytes, or null when it holds something that cannot be flattened. */
    private static byte[] bytesOf(Bundle bundle) {
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeBundle(bundle);
            return parcel.marshall();
        } catch (RuntimeException e) {
            Log.w(TAG, "the Bundle cannot be flattened: " + e);
            return null;
        } finally {
            parcel.recycle();
        }
    }
}
