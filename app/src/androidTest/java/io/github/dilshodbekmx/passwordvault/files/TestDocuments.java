package io.github.dilshodbekmx.passwordvault.files;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The documents of the test APK's own documents provider ({@link TestDocumentsProvider}), which
 * stands in for a real place behind the system file picker: files in the test APK's own storage,
 * in its own process, reached by the app only through the Storage Access Framework and the
 * permissions a picker grants.
 *
 * <p>Java on purpose: these classes run in the test APK's process, where the app's classes are not
 * loaded, and the test APK carries no Kotlin library (the build leaves out every library the app
 * already has).
 *
 * <p>A document id is {@code <behaviour>-<anything>}; the behaviour is how the provider treats it.
 */
final class TestDocuments {
    private TestDocuments() {}

    /** The provider's authority (the test APK's own: the app's id with ".test"). */
    static final String AUTHORITY = "io.github.dilshodbekmx.passwordvault.test.documents";

    /** The app the documents are granted to. */
    static final String APP_PACKAGE = "io.github.dilshodbekmx.passwordvault";

    static final String ROOT_ID = "pvtest";
    static final String ROOT_DOCUMENT = "root";

    /** A file, as most providers hand one out. */
    static final String PLAIN = "plain";
    /** Takes every byte written and stores none, and reports success [V-S6]. */
    static final String ZERO = "zero";
    /** Stores only the first half of what is written. */
    static final String CUT = "cut";
    /** Stores every byte written, one of them changed. */
    static final String FLIP = "flip";
    /** Refuses to open the document for writing. */
    static final String NO_WRITE = "nowrite";
    /** Does not allow the document to be deleted. */
    static final String NO_DELETE = "nodelete";
    /** Cannot open the document for reading. */
    static final String NO_READ = "noread";
    /** Refuses the mode "wt" as a bad mode, and with "w" writes over the old content without cutting it. */
    static final String W_ONLY = "wonly";
    /** Answers a query with all its columns, in its own order, whatever columns were asked for. */
    static final String ALL_COLUMNS = "allcolumns";

    /** The receiver's commands; see {@link TestDocumentsReceiver}. */
    static final String ACTION_CREATE = "io.github.dilshodbekmx.passwordvault.test.documents.CREATE";
    static final String ACTION_BYTES = "io.github.dilshodbekmx.passwordvault.test.documents.BYTES";
    static final String ACTION_FINISH_PICKER = "io.github.dilshodbekmx.passwordvault.test.documents.FINISH_PICKER";

    static final String EXTRA_ID = "id";
    static final String EXTRA_NAME = "name";
    static final String EXTRA_CONTENT = "content";
    static final String EXTRA_RESULT = "result";
    static final String EXTRA_BYTES = "bytes";

    static String behaviour(String id) {
        int dash = id.indexOf('-');
        return dash < 0 ? id : id.substring(0, dash);
    }

    static Uri uri(String id) {
        return DocumentsContract.buildDocumentUri(AUTHORITY, id);
    }

    static File dir(Context context) {
        File dir = new File(context.getFilesDir(), "documents");
        dir.mkdirs();
        return dir;
    }

    static File file(Context context, String id) {
        if (id.isEmpty() || id.contains("/") || id.endsWith(".name") || id.equals(ROOT_DOCUMENT)) {
            throw new IllegalArgumentException("not a document id: " + id);
        }
        return new File(dir(context), id);
    }

    static String name(Context context, String id) {
        File names = new File(dir(context), id + ".name");
        try {
            return new String(read(names), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return id;
        }
    }

    /** Creates (or replaces) the document [id] with [content], named [name]. */
    static void create(Context context, String id, String name, byte[] content) throws IOException {
        write(file(context, id), content);
        write(new File(dir(context), id + ".name"), (name == null ? id : name).getBytes(StandardCharsets.UTF_8));
    }

    static void delete(Context context, String id) {
        file(context, id).delete();
        new File(dir(context), id + ".name").delete();
    }

    static byte[] read(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return readAll(in);
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int n;
        while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    static void write(File file, byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
    }
}
