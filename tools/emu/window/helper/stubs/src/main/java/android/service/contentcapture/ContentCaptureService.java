package android.service.contentcapture;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.view.contentcapture.ContentCaptureContext;
import android.view.contentcapture.ContentCaptureEvent;
import android.view.contentcapture.ContentCaptureSessionId;

import java.util.Set;

/** Compile-time stand-in for the system API class of Android 10 (API 29); see ../../../../../build.gradle.kts. */
public abstract class ContentCaptureService extends Service {
    public static final String SERVICE_INTERFACE = "android.service.contentcapture.ContentCaptureService";

    public ContentCaptureService() {
        throw new RuntimeException("Stub!");
    }

    @Override
    public final IBinder onBind(Intent intent) {
        throw new RuntimeException("Stub!");
    }

    public final void setContentCaptureWhitelist(Set<String> packages, Set<ComponentName> activities) {
        throw new RuntimeException("Stub!");
    }

    public void onConnected() {
        throw new RuntimeException("Stub!");
    }

    public void onCreateContentCaptureSession(ContentCaptureContext context, ContentCaptureSessionId sessionId) {
        throw new RuntimeException("Stub!");
    }

    public void onContentCaptureEvent(ContentCaptureSessionId sessionId, ContentCaptureEvent event) {
        throw new RuntimeException("Stub!");
    }

    public void onDestroyContentCaptureSession(ContentCaptureSessionId sessionId) {
        throw new RuntimeException("Stub!");
    }

    public void onDisconnected() {
        throw new RuntimeException("Stub!");
    }
}
