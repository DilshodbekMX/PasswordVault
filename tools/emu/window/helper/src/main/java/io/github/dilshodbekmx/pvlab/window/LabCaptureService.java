package io.github.dilshodbekmx.pvlab.window;

import android.service.contentcapture.ContentCaptureService;
import android.util.Log;
import android.view.contentcapture.ContentCaptureContext;
import android.view.contentcapture.ContentCaptureEvent;
import android.view.contentcapture.ContentCaptureSessionId;
import android.view.contentcapture.ViewNode;

import java.util.HashSet;
import java.util.Set;

/**
 * A content capture service (Android 10, API 29 and newer) that writes down everything apps send it,
 * in the helper's files directory (files/capture.log), for tools/emu/window/capture.sh:
 *   CONNECTED
 *   SESSION TAB id TAB context (ContentCaptureContext's own text: the activity, the flags)
 *   EVENT TAB session TAB type TAB class=... TAB id=... (the view's resource id; a Compose node's
 *         test tag) TAB text=... TAB hint=... TAB desc=... TAB input=0x...
 *   DESTROYED TAB id
 * A text is written on one line (LabFiles.oneLine).
 *
 * It is a system API service: the device's content capture manager binds it only when the shell makes
 * it the temporary service ("cmd content_capture set temporary-service 0
 * io.github.dilshodbekmx.pvlab.window/.LabCaptureService MILLISECONDS"), and its calls into the system
 * API work only while hidden API checks are relaxed ("settings put global hidden_api_policy 1").
 * Connected, it allows content capture for PasswordVault and for this app (whose control screen is the
 * control of capture.sh); the apps' processes started afterwards then send their screens.
 */
public class LabCaptureService extends ContentCaptureService {
    private static final String TAG = "LabCapture";

    @Override
    public void onConnected() {
        Set<String> packages = new HashSet<>();
        packages.add("io.github.dilshodbekmx.passwordvault");
        packages.add(getPackageName());
        setContentCaptureWhitelist(packages, null);
        Log.i(TAG, "connected; allowed: " + packages);
        LabFiles.append(this, LabFiles.CAPTURE, "CONNECTED");
    }

    @Override
    public void onCreateContentCaptureSession(ContentCaptureContext context, ContentCaptureSessionId sessionId) {
        LabFiles.append(this, LabFiles.CAPTURE, "SESSION\t" + sessionId + "\t" + LabFiles.oneLine(String.valueOf(context)));
    }

    @Override
    public void onContentCaptureEvent(ContentCaptureSessionId sessionId, ContentCaptureEvent event) {
        StringBuilder b = new StringBuilder("EVENT");
        b.append('\t').append(sessionId);
        b.append('\t').append(typeName(event.getType()));
        ViewNode node = event.getViewNode();
        if (node != null) {
            b.append('\t').append("class=").append(node.getClassName());
            b.append('\t').append("id=").append(node.getIdEntry());
            b.append('\t').append("text=").append(LabFiles.oneLine(node.getText()));
            b.append('\t').append("hint=").append(LabFiles.oneLine(node.getHint()));
            b.append('\t').append("desc=").append(LabFiles.oneLine(node.getContentDescription()));
            b.append('\t').append("input=0x").append(Integer.toHexString(node.getInputType()));
        } else {
            b.append('\t').append("class=\tid=");
            CharSequence text = event.getText();
            b.append('\t').append("text=").append(LabFiles.oneLine(text));
        }
        LabFiles.append(this, LabFiles.CAPTURE, b.toString());
    }

    @Override
    public void onDestroyContentCaptureSession(ContentCaptureSessionId sessionId) {
        LabFiles.append(this, LabFiles.CAPTURE, "DESTROYED\t" + sessionId);
    }

    @Override
    public void onDisconnected() {
        Log.i(TAG, "disconnected");
    }

    private static String typeName(int type) {
        switch (type) {
            case ContentCaptureEvent.TYPE_VIEW_APPEARED: return "VIEW_APPEARED";
            case ContentCaptureEvent.TYPE_VIEW_DISAPPEARED: return "VIEW_DISAPPEARED";
            case ContentCaptureEvent.TYPE_VIEW_TEXT_CHANGED: return "VIEW_TEXT_CHANGED";
            case ContentCaptureEvent.TYPE_VIEW_TREE_APPEARING: return "VIEW_TREE_APPEARING";
            case ContentCaptureEvent.TYPE_VIEW_TREE_APPEARED: return "VIEW_TREE_APPEARED";
            case ContentCaptureEvent.TYPE_CONTEXT_UPDATED: return "CONTEXT_UPDATED";
            case ContentCaptureEvent.TYPE_SESSION_RESUMED: return "SESSION_RESUMED";
            case ContentCaptureEvent.TYPE_SESSION_PAUSED: return "SESSION_PAUSED";
            default: return "TYPE_" + type;
        }
    }
}
