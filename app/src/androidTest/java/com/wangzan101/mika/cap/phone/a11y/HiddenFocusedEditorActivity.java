package com.wangzan101.mika.cap.phone.a11y;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.LinearLayout;
import android.widget.TextView;

/** An accessibility provider can keep reporting focus after an editor collapses. */
public class HiddenFocusedEditorActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        TextView writes = new TextView(this);
        writes.setText("Writes: 0");
        root.addView(writes);
        Editor editor = new Editor(this, writes, getIntent().getBooleanExtra("collapse_after_write", false));
        root.addView(editor, new LinearLayout.LayoutParams(-1, 120));
        setContentView(root);
        editor.requestFocus();
    }

    private static final class Editor extends View {
        private static final int FIELD = 1;
        private final TextView writes;
        private boolean collapsed;
        private String text = "Original draft";
        private int writeCount;

        Editor(Context context, TextView writes, boolean collapseAfterWrite) {
            super(context);
            this.writes = writes;
            collapsed = !collapseAfterWrite;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            setFocusableInTouchMode(true);
            setContentDescription("Editor surface");
            setOnClickListener(view -> requestFocus());
        }

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return new AccessibilityNodeProvider() {
                @Override public AccessibilityNodeInfo createAccessibilityNodeInfo(int id) {
                    if (id != HOST_VIEW_ID && id != FIELD) return null;
                    AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
                    if (id == HOST_VIEW_ID) {
                        onInitializeAccessibilityNodeInfo(info);
                        info.setSource(Editor.this);
                        info.addChild(Editor.this, FIELD);
                        return info;
                    }
                    int width = collapsed ? 0 : getWidth();
                    int[] location = new int[2];
                    getLocationOnScreen(location);
                    info.setSource(Editor.this, FIELD);
                    info.setParent(Editor.this);
                    info.setPackageName(getContext().getPackageName());
                    info.setClassName("android.widget.EditText");
                    info.setEditable(true);
                    info.setEnabled(true);
                    info.setFocusable(true);
                    info.setFocused(true);
                    info.setVisibleToUser(true);
                    info.setText(text);
                    info.setBoundsInParent(new Rect(0, 0, width, 100));
                    info.setBoundsInScreen(new Rect(location[0], location[1], location[0] + width, location[1] + 100));
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT);
                    return info;
                }

                @Override public AccessibilityNodeInfo findFocus(int focus) {
                    return focus == AccessibilityNodeInfo.FOCUS_INPUT ? createAccessibilityNodeInfo(FIELD) : null;
                }

                @Override public boolean performAction(int id, int action, Bundle arguments) {
                    if (id != FIELD || action != AccessibilityNodeInfo.ACTION_SET_TEXT) return false;
                    CharSequence value = arguments.getCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE);
                    text = value == null ? "" : value.toString();
                    collapsed = true;
                    writes.setText("Writes: " + ++writeCount);
                    return true;
                }
            };
        }
    }
}
