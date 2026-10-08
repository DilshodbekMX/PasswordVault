package io.github.dilshodbekmx.pvlab.window;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * The helper's records, kept as files in its own files directory so that they outlive its process
 * (the system unbinds an idle autofill service and may end the process): the scripts read them with
 * "run-as io.github.dilshodbekmx.pvlab.window cat files/NAME" and empty them with "rm".
 */
final class LabFiles {
    static final String AUTOFILL = "autofill.log";
    static final String CAPTURE = "capture.log";

    private static final Object LOCK = new Object();

    private LabFiles() {
    }

    /** The events file of the accessibility service [kind] ("plain" or "tool"). */
    static String events(String kind) {
        return "a11y-" + kind + "-events.log";
    }

    /** The file of the last node dump of the accessibility service [kind]. */
    static String dump(String kind) {
        return "a11y-" + kind + "-dump.txt";
    }

    static void append(Context context, String name, String line) {
        write(context, name, line + "\n", true);
    }

    static void replace(Context context, String name, String text) {
        write(context, name, text, false);
    }

    private static void write(Context context, String name, String text, boolean append) {
        synchronized (LOCK) {
            File file = new File(context.getFilesDir(), name);
            try (Writer out = new OutputStreamWriter(new FileOutputStream(file, append), StandardCharsets.UTF_8)) {
                out.write(text);
            } catch (IOException e) {
                Log.e("PvWinLab", "cannot write " + file, e);
            }
        }
    }

    /** One line: line breaks and the field separator of the records are written as visible escapes. */
    static String oneLine(CharSequence text) {
        if (text == null) return "";
        StringBuilder b = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c == '\t') b.append("\\t");
            else b.append(c);
        }
        return b.toString();
    }
}
