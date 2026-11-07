package com.wangzan101.mika.cap.phone.a11y;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.TextView;

/** 浮层手势真机回归的整屏点按探针;只在测试包里,点按次数通过无障碍文字读出。 */
public class GestureTapTestActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setText("taps:0");
        text.setTextSize(32f);
        text.setGravity(Gravity.CENTER);
        text.setClickable(true);
        text.setOnClickListener(v -> {
            int taps = Integer.parseInt(text.getText().toString().substring("taps:".length()));
            text.setText("taps:" + (taps + 1));
        });
        setContentView(text, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }
}
