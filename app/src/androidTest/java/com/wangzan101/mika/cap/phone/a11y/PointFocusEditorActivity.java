package com.wangzan101.mika.cap.phone.a11y;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.MotionEvent;
import android.widget.EditText;
import android.widget.LinearLayout;

/** A consumed touch can leave input focus in another visible editor. */
public class PointFocusEditorActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        EditText target = new TouchConsumingEditor(this);
        target.setText("Target original");
        target.setLongClickable(true);
        root.addView(target);
        EditText focused = new EditText(this);
        focused.setText("Focused original");
        root.addView(focused);
        setContentView(root);
        focused.requestFocus();
    }

    private static final class TouchConsumingEditor extends EditText {
        TouchConsumingEditor(Context context) {
            super(context);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            return true;
        }
    }
}
