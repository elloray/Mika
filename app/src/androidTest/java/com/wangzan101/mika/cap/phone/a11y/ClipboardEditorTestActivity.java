package com.wangzan101.mika.cap.phone.a11y;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Two app-drawn editors without an IME or an accessible text-entry action. */
public class ClipboardEditorTestActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setFocusableInTouchMode(true);
        setContentView(content);

        TextView description = addEditor(content, "Description", false);
        Button paste = new Button(this);
        paste.setId(android.R.id.paste);
        paste.setText(Resources.getSystem().getString(android.R.string.paste));
        paste.setVisibility(View.GONE);
        content.addView(paste);
        paste.setOnClickListener(view -> {
            paste.setVisibility(View.GONE);
            description.postDelayed(() -> readClip(description, "Description"), 300);
        });
        Runnable showPaste = () -> paste.setVisibility(View.VISIBLE);
        final int[] holds = {0};
        description.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP &&
                    event.getEventTime() - event.getDownTime() >= ViewConfiguration.getLongPressTimeout() &&
                    ++holds[0] == 2) {
                // Ignore the first hold, then expose Paste asynchronously after the second.
                description.postDelayed(showPaste, 1_750);
            }
            return false;
        });
        addEditor(content, "Shortcut", true);
        content.requestFocus();
    }

    private TextView addEditor(LinearLayout content, String label, boolean usesShortcut) {
        TextView editor = new TextView(this) {
            private boolean controlDown;

            @Override public boolean onKeyDown(int code, KeyEvent event) {
                if (code == KeyEvent.KEYCODE_CTRL_LEFT) {
                    controlDown = true;
                    return true;
                }
                if (!event.isCtrlPressed()) return super.onKeyDown(code, event);
                if (usesShortcut && controlDown && code == KeyEvent.KEYCODE_A) {
                    setBackgroundColor(Color.BLACK);
                    setTextColor(Color.WHITE);
                } else if (usesShortcut && controlDown && code == KeyEvent.KEYCODE_V) {
                    postDelayed(() -> readClip(this, label), 300);
                }
                return true;
            }

            @Override public boolean onKeyUp(int code, KeyEvent event) {
                if (code != KeyEvent.KEYCODE_CTRL_LEFT) return super.onKeyUp(code, event);
                controlDown = false;
                return true;
            }
        };
        editor.setText(label);
        editor.setTextSize(24);
        editor.setPadding(24, 32, 24, 32);
        editor.setMinHeight((int) (96 * getResources().getDisplayMetrics().density));
        editor.setFocusableInTouchMode(true);
        editor.setOnClickListener(view -> {
            editor.requestFocus();
            if (editor.getText().toString().equals(label)) editor.setText(label + " focused");
        });
        content.addView(editor, new LinearLayout.LayoutParams(-1, -2));
        return editor;
    }

    private void readClip(TextView editor, String label) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData clip = clipboard.getPrimaryClip();
        CharSequence text = clip == null ? "(empty clipboard)" : clip.getItemAt(0).coerceToText(this);
        editor.setText(label + ": " + text);
    }
}
