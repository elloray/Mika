package com.wangzan101.mika.cap.phone.display;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/** 用真实的 Activity 方向请求重现横屏游戏;不依赖任何第三方应用。 */
public class RotationTestActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (getDisplay() == null || getDisplay().getDisplayId() == Display.DEFAULT_DISPLAY) {
            finish();
            return;
        }
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        show(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        show(intent);
    }

    private void show(Intent intent) {
        setRequestedOrientation(intent.getIntExtra("orientation", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
        setContentView(new View(this) {
            private final Paint paint = new Paint();
            private boolean tapped = false;

            @Override protected void onDraw(Canvas canvas) {
                float w = getWidth(), h = getHeight();
                paint.setColor(Color.RED);
                canvas.drawRect(0, 0, w / 2, h / 2, paint);
                paint.setColor(Color.GREEN);
                canvas.drawRect(w / 2, 0, w, h / 2, paint);
                paint.setColor(Color.BLUE);
                canvas.drawRect(0, h / 2, w / 2, h, paint);
                paint.setColor(tapped ? Color.CYAN : Color.YELLOW);
                canvas.drawRect(w / 2, h / 2, w, h, paint);
            }

            @Override public boolean onTouchEvent(MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_UP
                        && event.getX() > getWidth() / 2f && event.getY() > getHeight() / 2f) {
                    tapped = true;
                    invalidate();
                    performClick();
                }
                return true;
            }

            @Override public boolean performClick() { super.performClick(); return true; }
        });
    }
}
