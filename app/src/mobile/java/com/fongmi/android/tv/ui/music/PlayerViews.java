package com.fongmi.android.tv.ui.music;

import android.animation.ValueAnimator;
import android.graphics.Typeface;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;

public final class PlayerViews {

    private PlayerViews() {
    }

    public static float dp(View v, float value) {
        return value * v.getResources().getDisplayMetrics().density;
    }

    public static int dpI(View v, float value) {
        return Math.round(dp(v, value));
    }

    public static float sp(View v, float value) {
        return value * v.getResources().getDisplayMetrics().scaledDensity;
    }

    public static Typeface weight(int weight) {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                Typeface tf = Typeface.create(Typeface.DEFAULT, weight, false);
                if (tf != null) return tf;
            } catch (Throwable ignored) {
            }
        }
        return Typeface.create(Typeface.DEFAULT, weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
    }

    public static Typeface medium() {
        Typeface tf = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        return tf == null ? Typeface.create(Typeface.DEFAULT, Typeface.NORMAL) : tf;
    }

    public static void pressScale(View view, float pressedScale) {
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    animateScale(v, pressedScale);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    animateScale(v, 1f);
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    private static void animateScale(View v, float target) {
        float from = v.getScaleX();
        ValueAnimator anim = ValueAnimator.ofFloat(from, target);
        anim.setDuration(150L);
        if (Build.VERSION.SDK_INT >= 21) {
            anim.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        }
        anim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            v.setScaleX(f);
            v.setScaleY(f);
        });
        anim.start();
    }

    public static int withAlpha(int color, float alpha) {
        int a = Math.round(255f * Math.max(0f, Math.min(1f, alpha)));
        return (color & 0x00FFFFFF) | (a << 24);
    }

    public static int white(float alpha) {
        return withAlpha(0xFFFFFFFF, alpha);
    }
}
