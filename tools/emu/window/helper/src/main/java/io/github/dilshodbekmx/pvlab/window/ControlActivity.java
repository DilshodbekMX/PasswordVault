package io.github.dilshodbekmx.pvlab.window;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The control of every check of tools/emu/window: a screen with no protection at all, made of
 * platform views. A text field (control_text), a password field (control_password) and a button
 * (control_button) that counts its taps (control_status shows "clicks=N").
 *
 * What the app under test must prevent has to happen here, or a clean result would mean nothing: the
 * autofill service gets a fill request and, after Save, the typed text; the keyboard gets no
 * IME_FLAG_NO_PERSONALIZED_LEARNING; a tap through another app's overlay arrives; an accessibility
 * service reads the typed text; the saved-instance state holds the typed text.
 *
 * Started with the string extra "clip", it puts that text on the clipboard.
 */
public class ControlActivity extends Activity {
    private static final String TAG = "PvWinControl";

    /** Taps on the button, and touches that arrived through another window, since the process started. */
    static volatile int clicks;
    static volatile int obscuredTouches;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        column.setPadding(pad, 3 * pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("PV window lab: control");
        title.setTextSize(20f);
        column.addView(title, wide());

        EditText text = new EditText(this);
        text.setId(R.id.control_text);
        text.setHint("control text");
        text.setInputType(InputType.TYPE_CLASS_TEXT);
        column.addView(text, wide());

        EditText password = new EditText(this);
        password.setId(R.id.control_password);
        password.setHint("control password");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        column.addView(password, wide());

        TextView status = new TextView(this);
        status.setId(R.id.control_status);
        status.setText("clicks=" + clicks);
        status.setTextSize(18f);

        Button button = new Button(this);
        button.setId(R.id.control_button);
        button.setText("CONTROL");
        button.setOnClickListener(v -> {
            clicks++;
            status.setText("clicks=" + clicks);
            Log.i(TAG, "CLICKED clicks=" + clicks);
        });
        column.addView(button, wide());
        column.addView(status, wide());
        setContentView(column);

        String clip = getIntent().getStringExtra("clip");
        if (clip != null) {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("pv-window-lab", clip));
        }
        Log.i(TAG, "onCreate restored=" + (savedInstanceState != null));
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            boolean obscured = (event.getFlags() & MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0;
            if (obscured) obscuredTouches++;
            Log.i(TAG, "touch DOWN flags=0x" + Integer.toHexString(event.getFlags()) + " obscured=" + obscured);
        }
        return super.dispatchTouchEvent(event);
    }

    private static LinearLayout.LayoutParams wide() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
