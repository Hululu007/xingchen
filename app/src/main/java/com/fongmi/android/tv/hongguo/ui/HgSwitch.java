package com.fongmi.android.tv.hongguo.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

/**
 * 定稿形态的自绘开关（46×27，关＝rgba(27,27,31,.14)、开＝主题红
 * #E63A2E，白色圆钮 21dp 带影）：抽屉与各面板共用一件，替掉系统
 * 素 Switch（他点名「按钮全都做错了」，开关也在内）。
 */
public class HgSwitch extends FrameLayout {

    public interface OnToggle {
        void onToggle(boolean on);
    }

    private boolean checked;
    private final View knob;
    private OnToggle listener;

    public HgSwitch(Context context) {
        super(context);
        knob = new View(context);
        GradientDrawable knobBg = new GradientDrawable();
        knobBg.setShape(GradientDrawable.OVAL);
        knobBg.setColor(0xFFFFFFFF);
        knob.setBackground(knobBg);
        knob.setElevation(dp(2));
        FrameLayout.LayoutParams klp = new FrameLayout.LayoutParams(dp(21), dp(21));
        klp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        klp.leftMargin = dp(3);
        addView(knob, klp);
        setOnClickListener(v -> setChecked(!checked, true));
        applyTrack(false);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void applyTrack(boolean animate) {
        GradientDrawable track = new GradientDrawable();
        track.setColor(checked ? 0xFFE63A2E : 0x241B1B1F);
        track.setCornerRadius(dp(13.5f));
        setBackground(track);
        float target = checked ? dp(22) : dp(3);
        if (animate) {
            knob.animate().x(target).setDuration(250).start();
        } else {
            knob.setX(target);
        }
    }

    public boolean isChecked() {
        return checked;
    }

    public void setChecked(boolean on, boolean notify) {
        if (checked == on) {
            applyTrack(false);
            return;
        }
        checked = on;
        applyTrack(true);
        if (notify && listener != null) listener.onToggle(on);
    }

    public void setOnToggleListener(OnToggle l) {
        listener = l;
    }
}
