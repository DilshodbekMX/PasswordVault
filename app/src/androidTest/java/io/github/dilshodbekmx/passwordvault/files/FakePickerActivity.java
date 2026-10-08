package io.github.dilshodbekmx.passwordvault.files;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import java.io.IOException;

/**
 * A stand-in for the system file picker, in the test APK's own process: started for a result by the
 * app's activity, with the request code of the app's own launcher, it creates the document it was
 * given (as "Save as" does when the user taps Save; with the content it was given, for a document
 * to open) and stays open until the test sends
 * {@link TestDocuments#ACTION_FINISH_PICKER}. Then it finishes as the system picker does: with the
 * document and a read, write and persistable grant on it, or with no document. The system delivers
 * that result, and grants the permission, to the activity that started it, which the test may have
 * recreated meanwhile.
 */
public final class FakePickerActivity extends Activity {
    /** The picker shown now in this process; main thread. */
    static FakePickerActivity shown;

    private String documentId;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setText("PasswordVault test: fake file picker");
        setContentView(text);
        documentId = getIntent().getStringExtra(TestDocuments.EXTRA_ID);
        String name = getIntent().getStringExtra(TestDocuments.EXTRA_NAME);
        byte[] content = getIntent().getByteArrayExtra(TestDocuments.EXTRA_CONTENT);
        if (documentId != null && !TestDocuments.file(this, documentId).exists()) {
            try {
                TestDocuments.create(this, documentId, name, content == null ? new byte[0] : content);
            } catch (IOException e) {
                throw new IllegalStateException("the document could not be made", e);
            }
        }
        shown = this;
    }

    /** Ends the picker with [resultCode]: with the document for RESULT_OK, with none otherwise. */
    void finishWith(int resultCode) {
        if (resultCode == RESULT_OK && documentId != null) {
            Intent data = new Intent()
                    .setData(TestDocuments.uri(documentId))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            setResult(RESULT_OK, data);
        } else {
            setResult(RESULT_CANCELED);
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        if (shown == this) shown = null;
        super.onDestroy();
    }
}
