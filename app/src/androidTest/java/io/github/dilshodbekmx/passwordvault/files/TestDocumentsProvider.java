package io.github.dilshodbekmx.passwordvault.files;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/**
 * A documents provider of the test APK, in the test APK's own process: a place behind the file
 * picker as Downloads or Google Drive is one, reached by the app only through a picker's grant.
 * Each document behaves as its id's prefix says ({@link TestDocuments}): a plain file, or one of the
 * providers that report success after writing 0 bytes, cut a file, change a byte, refuse to write,
 * refuse to delete or refuse to be read [V-S6]. The broken ones take the written bytes through a pipe
 * and store what they store when the writer has closed it.
 */
public final class TestDocumentsProvider extends DocumentsProvider {
    private static final String[] ROOT_COLUMNS = {
        Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_TITLE, Root.COLUMN_DOCUMENT_ID,
    };
    private static final String[] DOCUMENT_COLUMNS = {
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : ROOT_COLUMNS);
        MatrixCursor.RowBuilder row = cursor.newRow();
        row.add(Root.COLUMN_ROOT_ID, TestDocuments.ROOT_ID);
        row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_LOCAL_ONLY);
        row.add(Root.COLUMN_TITLE, "PV test documents");
        row.add(Root.COLUMN_DOCUMENT_ID, TestDocuments.ROOT_DOCUMENT);
        return cursor;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        boolean own = projection == null || TestDocuments.ALL_COLUMNS.equals(TestDocuments.behaviour(documentId));
        MatrixCursor cursor = new MatrixCursor(own ? DOCUMENT_COLUMNS : projection);
        addRow(cursor, documentId);
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        if (!TestDocuments.ROOT_DOCUMENT.equals(parentDocumentId)) throw new FileNotFoundException(parentDocumentId);
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        File[] files = TestDocuments.dir(getContext()).listFiles();
        if (files != null) {
            for (File file : files) {
                if (!file.getName().endsWith(".name")) addRow(cursor, file.getName());
            }
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        File file = existing(documentId);
        String behaviour = TestDocuments.behaviour(documentId);
        boolean writing = mode.indexOf('w') >= 0 || mode.indexOf('a') >= 0;
        if (!writing) {
            if (TestDocuments.NO_READ.equals(behaviour)) throw new FileNotFoundException("this document cannot be read");
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        switch (behaviour) {
            case TestDocuments.NO_WRITE:
                throw new FileNotFoundException("this place does not take writes");
            case TestDocuments.ZERO:
            case TestDocuments.CUT:
            case TestDocuments.FLIP:
                return brokenWriter(file, behaviour);
            default:
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
        }
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        existing(documentId);
        if (TestDocuments.NO_DELETE.equals(TestDocuments.behaviour(documentId))) {
            throw new UnsupportedOperationException("Delete not supported");
        }
        TestDocuments.delete(getContext(), documentId);
    }

    private File existing(String documentId) throws FileNotFoundException {
        File file;
        try {
            file = TestDocuments.file(getContext(), documentId);
        } catch (IllegalArgumentException e) {
            throw new FileNotFoundException(documentId);
        }
        if (!file.isFile()) throw new FileNotFoundException(documentId);
        return file;
    }

    private void addRow(MatrixCursor cursor, String documentId) throws FileNotFoundException {
        MatrixCursor.RowBuilder row = cursor.newRow();
        if (TestDocuments.ROOT_DOCUMENT.equals(documentId)) {
            row.add(Document.COLUMN_DOCUMENT_ID, documentId);
            row.add(Document.COLUMN_DISPLAY_NAME, "PV test documents");
            row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR);
            row.add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE);
            return;
        }
        File file = existing(documentId);
        int flags = Document.FLAG_SUPPORTS_WRITE;
        if (!TestDocuments.NO_DELETE.equals(TestDocuments.behaviour(documentId))) flags |= Document.FLAG_SUPPORTS_DELETE;
        row.add(Document.COLUMN_DOCUMENT_ID, documentId);
        row.add(Document.COLUMN_DISPLAY_NAME, TestDocuments.name(getContext(), documentId));
        row.add(Document.COLUMN_MIME_TYPE, "application/octet-stream");
        row.add(Document.COLUMN_FLAGS, flags);
        row.add(Document.COLUMN_SIZE, file.length());
        row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified());
    }

    /**
     * The writing end of a pipe whose bytes this provider stores in its own way once the writer has
     * closed it: none ({@link TestDocuments#ZERO}), the first half ({@link TestDocuments#CUT}), or all
     * of them with the middle one changed ({@link TestDocuments#FLIP}). The writer sees no error.
     */
    private ParcelFileDescriptor brokenWriter(final File file, final String behaviour) throws FileNotFoundException {
        final ParcelFileDescriptor[] pipe;
        try {
            TestDocuments.write(file, new byte[0]);
            pipe = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
        final ParcelFileDescriptor readEnd = pipe[0];
        new Thread(() -> {
            byte[] all;
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(readEnd)) {
                all = TestDocuments.readAll(in);
            } catch (IOException e) {
                return;
            }
            byte[] kept;
            switch (behaviour) {
                case TestDocuments.ZERO:
                    kept = new byte[0];
                    break;
                case TestDocuments.CUT:
                    kept = new byte[all.length / 2];
                    System.arraycopy(all, 0, kept, 0, kept.length);
                    break;
                default:
                    kept = all.clone();
                    if (kept.length > 0) kept[kept.length / 2] ^= 0x01;
                    break;
            }
            try {
                TestDocuments.write(file, kept);
            } catch (IOException ignored) {
                // The writer has gone; the document keeps what it had.
            }
        }, "test-documents-" + behaviour).start();
        return pipe[1];
    }
}
