package com.wangzan101.mika.cap.phone.a11y;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A custom editor which exposes no editable node or input connection and ignores paste shortcuts. */
public class KeyOnlyEditorTestActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setFocusableInTouchMode(true);
        setContentView(content);
        addEditor(content, "Title");
        addEditor(content, "Description");
        content.requestFocus();
    }

    private void addEditor(LinearLayout content, String label) {
        TextView editor = new TextView(this) {
            private final StringBuilder value = new StringBuilder();

            @Override public boolean onKeyDown(int code, KeyEvent event) {
                if (event.isCtrlPressed()) return true;
                int ch = event.getUnicodeChar();
                if (ch < ' ' || ch > '~') return super.onKeyDown(code, event);
                value.append((char) ch);
                setText(label + ": " + value);
                return true;
            }
        };
        editor.setText(label);
        editor.setTextSize(24);
        editor.setPadding(24, 32, 24, 32);
        editor.setFocusableInTouchMode(true);
        editor.setOnFocusChangeListener((view, focused) -> {
            if (focused) view.setPadding(24, 48, 24, 32);
        });
        editor.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) view.requestFocus();
            return false;
        });
        editor.setOnClickListener(view -> view.requestFocus());
        content.addView(editor, new LinearLayout.LayoutParams(-1, -2));
    }
}
