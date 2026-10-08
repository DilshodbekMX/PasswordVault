package android.view.contentcapture;

import android.view.autofill.AutofillId;

/** Compile-time stand-in for the system API class of Android 10 (API 29); see ../../../../../build.gradle.kts. */
public final class ContentCaptureEvent {
    public static final int TYPE_VIEW_APPEARED = 1;
    public static final int TYPE_VIEW_DISAPPEARED = 2;
    public static final int TYPE_VIEW_TEXT_CHANGED = 3;
    public static final int TYPE_VIEW_TREE_APPEARING = 4;
    public static final int TYPE_VIEW_TREE_APPEARED = 5;
    public static final int TYPE_CONTEXT_UPDATED = 6;
    public static final int TYPE_SESSION_RESUMED = 7;
    public static final int TYPE_SESSION_PAUSED = 8;

    private ContentCaptureEvent() {
        throw new RuntimeException("Stub!");
    }

    public int getType() {
        throw new RuntimeException("Stub!");
    }

    public ViewNode getViewNode() {
        throw new RuntimeException("Stub!");
    }

    public AutofillId getId() {
        throw new RuntimeException("Stub!");
    }

    public CharSequence getText() {
        throw new RuntimeException("Stub!");
    }
}
