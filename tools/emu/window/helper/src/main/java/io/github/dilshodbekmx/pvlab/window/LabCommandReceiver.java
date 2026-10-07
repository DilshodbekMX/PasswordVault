package io.github.dilshodbekmx.pvlab.window;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * The scripts' commands:
 *
 *   adb shell am broadcast -n io.github.dilshodbekmx.pvlab.window/.LabCommandReceiver --es cmd NAME ...
 *
 * `am broadcast` prints "result=1, data=..." when the command was done and "result=2" with the reason
 * when it was not. The commands:
 *
 *   overlay-add --ei x X --ei y Y --ei w W --ei h H [--ef alpha A] [--ez touchable B]
 *       Draws a TYPE_APPLICATION_OVERLAY window from this app, another uid than the app under test,
 *       so that the app sees an untrusted overlay (the window lab's OverlayReceiver.kt). Without
 *       "touchable" it has FLAG_NOT_TOUCHABLE: taps pass through to the window below, the classic
 *       tapjacking set-up. Android 12 and newer themselves block taps through an overlay whose alpha
 *       is above 0.8, so the default alpha is 0.5, which reaches the app's own filter.
 *       Needs "appops set io.github.dilshodbekmx.pvlab.window SYSTEM_ALERT_WINDOW allow".
 *   overlay-remove
 *   a11y-status
 *       Which of the two accessibility services are connected: "plain=true tool=false".
 *   a11y-dump --es kind plain|tool --es nonce N
 *       The service KIND writes every node it can reach into files/a11y-KIND-dump.txt
 *       ([LabA11yService.dump]); data: "nodes=N windows=W".
 *   clip --es text TEXT
 *       Puts TEXT on the clipboard, so that a text toolbar has "Paste" to show.
 *   control
 *       The control screen's taps: "clicks=N obscured=M" ([ControlActivity]).
 */
public class LabCommandReceiver extends BroadcastReceiver {
    private static final String TAG = "PvWinLab";
    private static final int DONE = 1;
    private static final int FAILED = 2;

    private static View overlay;

    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        String cmd = String.valueOf(intent.getStringExtra("cmd"));
        switch (cmd) {
            case "overlay-add":
                addOverlay(app, intent);
                break;
            case "overlay-remove":
                answer(DONE, removeOverlay(app) ? "removed" : "none");
                break;
            case "a11y-status":
                answer(DONE, "plain=" + LabA11yService.RUNNING.containsKey("plain")
                        + " tool=" + LabA11yService.RUNNING.containsKey("tool"));
                break;
            case "a11y-dump":
                dump(intent);
                break;
            case "clip":
                ClipboardManager clipboard = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("pv-window-lab", String.valueOf(intent.getStringExtra("text"))));
                answer(DONE, "set");
                break;
            case "control":
                answer(DONE, "clicks=" + ControlActivity.clicks + " obscured=" + ControlActivity.obscuredTouches);
                break;
            default:
                answer(FAILED, "unknown command '" + cmd + "'");
        }
    }

    private void answer(int code, String text) {
        Log.i(TAG, "command: " + text);
        setResultCode(code);
        setResultData(text);
    }

    private void dump(Intent intent) {
        String kind = String.valueOf(intent.getStringExtra("kind"));
        String nonce = String.valueOf(intent.getStringExtra("nonce"));
        LabA11yService service = LabA11yService.RUNNING.get(kind);
        if (service == null) {
            answer(FAILED, "the accessibility service '" + kind + "' is not connected");
            return;
        }
        // The node tree is read through the accessibility connection, off the main thread.
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                pending.setResultData(service.dump(nonce));
                pending.setResultCode(DONE);
            } catch (RuntimeException e) {
                Log.e(TAG, "a11y-dump", e);
                pending.setResultData("failed: " + e.getClass().getName());
                pending.setResultCode(FAILED);
            } finally {
                pending.finish();
            }
        }, "a11y-dump").start();
    }

    private void addOverlay(Context app, Intent intent) {
        removeOverlay(app);
        if (!Settings.canDrawOverlays(app)) {
            answer(FAILED, "no overlay permission: appops set " + app.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
            return;
        }
        boolean touchable = intent.getBooleanExtra("touchable", false);
        float alpha = intent.getFloatExtra("alpha", 0.5f);
        TextView view = new TextView(app);
        view.setText("OVERLAY (pv window lab)");
        view.setTextSize(18f);
        view.setTextColor(Color.WHITE);
        view.setBackgroundColor(Color.argb(255, 220, 0, 0));
        view.setGravity(Gravity.CENTER);
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (!touchable) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                intent.getIntExtra("w", 600),
                intent.getIntExtra("h", 250),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = intent.getIntExtra("x", 100);
        lp.y = intent.getIntExtra("y", 900);
        lp.alpha = alpha;
        lp.setTitle("PvWinOverlay");
        WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        try {
            wm.addView(view, lp);
        } catch (RuntimeException e) {
            Log.e(TAG, "overlay-add", e);
            answer(FAILED, "addView failed: " + e.getClass().getName());
            return;
        }
        overlay = view;
        answer(DONE, "added x=" + lp.x + " y=" + lp.y + " w=" + lp.width + " h=" + lp.height + " alpha=" + alpha
                + " touchable=" + touchable);
    }

    private static boolean removeOverlay(Context app) {
        View view = overlay;
        if (view == null) return false;
        overlay = null;
        WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        try {
            wm.removeView(view);
        } catch (RuntimeException e) {
            Log.w(TAG, "overlay-remove: " + e);
        }
        return true;
    }
}
