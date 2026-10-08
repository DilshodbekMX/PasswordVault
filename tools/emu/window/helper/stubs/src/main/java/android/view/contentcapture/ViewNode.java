package android.view.contentcapture;

import android.view.autofill.AutofillId;

/**
 * Compile-time stand-in for the system API class of Android 10 (API 29); see
 * ../../../../../build.gradle.kts. On the device it extends android.app.assist.AssistStructure.ViewNode,
 * whose public methods these are; the calls resolve there.
 */
public final class ViewNode {
    private ViewNode() {
        throw new RuntimeException("Stub!");
    }

    public AutofillId getAutofillId() {
        throw new RuntimeException("Stub!");
    }

    public String getClassName() {
        throw new RuntimeException("Stub!");
    }

    public String getIdEntry() {
        throw new RuntimeException("Stub!");
    }

    public CharSequence getText() {
        throw new RuntimeException("Stub!");
    }

    public String getHint() {
        throw new RuntimeException("Stub!");
    }

    public CharSequence getContentDescription() {
        throw new RuntimeException("Stub!");
    }

    public int getInputType() {
        throw new RuntimeException("Stub!");
    }
}
