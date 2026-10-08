package io.github.dilshodbekmx.passwordvault.files;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.io.File;
import java.io.IOException;

/**
 * The test's way into the test APK's process, by ordered broadcasts from the app's process (see
 * {@code DocumentsRig}); each answers through the broadcast's result.
 *
 * <ul>
 *   <li>{@link TestDocuments#ACTION_CREATE}: creates the document {@code id} with {@code content} and
 *       {@code name}, and grants the app read, write and persistable permission on it, as a picker's
 *       result does; the result data is the document's address.
 *   <li>{@link TestDocuments#ACTION_BYTES}: the stored bytes of {@code id} in the result extras, or
 *       RESULT_CANCELED when there is no such document.
 *   <li>{@link TestDocuments#ACTION_FINISH_PICKER}: ends the {@link FakePickerActivity} shown, with
 *       the result code {@code result}; RESULT_CANCELED when none is shown.
 * </ul>
 */
public final class TestDocumentsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        String id = intent.getStringExtra(TestDocuments.EXTRA_ID);
        if (TestDocuments.ACTION_CREATE.equals(action)) {
            byte[] content = intent.getByteArrayExtra(TestDocuments.EXTRA_CONTENT);
            try {
                TestDocuments.create(context, id, intent.getStringExtra(TestDocuments.EXTRA_NAME), content == null ? new byte[0] : content);
            } catch (IOException e) {
                setResultCode(Activity.RESULT_CANCELED);
                return;
            }
            Uri uri = TestDocuments.uri(id);
            context.grantUriPermission(
                    TestDocuments.APP_PACKAGE,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            setResultCode(Activity.RESULT_OK);
            setResultData(uri.toString());
        } else if (TestDocuments.ACTION_BYTES.equals(action)) {
            File file = TestDocuments.file(context, id);
            if (!file.isFile()) {
                setResultCode(Activity.RESULT_CANCELED);
                return;
            }
            try {
                Bundle extras = new Bundle();
                extras.putByteArray(TestDocuments.EXTRA_BYTES, TestDocuments.read(file));
                setResultExtras(extras);
                setResultCode(Activity.RESULT_OK);
            } catch (IOException e) {
                setResultCode(Activity.RESULT_CANCELED);
            }
        } else if (TestDocuments.ACTION_FINISH_PICKER.equals(action)) {
            FakePickerActivity picker = FakePickerActivity.shown;
            if (picker == null) {
                setResultCode(Activity.RESULT_CANCELED);
                return;
            }
            picker.finishWith(intent.getIntExtra(TestDocuments.EXTRA_RESULT, Activity.RESULT_OK));
            setResultCode(Activity.RESULT_OK);
        }
    }
}
