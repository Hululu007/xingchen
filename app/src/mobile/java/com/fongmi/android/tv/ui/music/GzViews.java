package com.fongmi.android.tv.ui.music;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

/** 播放页共用小件：尺寸换算、瓜子按压缩放（默认 .96、150ms、bs4.a 缓动）、文本/图标钮工厂。 */
public final class GzViews {

    private GzViews() {
    }

    public static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    public static int dpi(Context c, float v) {
        return Math.round(dp(c, v));
    }

    /** 瓜子 MotionKt.clickable 默认按压：缩放 .96、150ms；个别件传自己的值（.9/.88/.85 等）。 */
    public static void pressScale(View view, float scale) {
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    animateScale(v, scale);
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

    public static void animateScale(View v, float to) {
        ValueAnimator anim = ValueAnimator.ofFloat(v.getScaleX(), to);
        anim.setDuration(150);
        anim.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        anim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            v.setScaleX(f);
            v.setScaleY(f);
        });
        anim.start();
    }

    public static TextView text(Context c, String s, float sp, int weight, int color) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            tv.setTypeface(Typeface.create(Typeface.DEFAULT, weight, false));
        } else {
            tv.setTypeface(weight >= 700 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        tv.setIncludeFontPadding(false);
        return tv;
    }

    public static ImageView icon(Context c, int res, float sizeDp, float alpha) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(res);
        iv.setAlpha(alpha);
        int s = dpi(c, sizeDp);
        iv.setLayoutParams(new android.view.ViewGroup.LayoutParams(s, s));
        return iv;
    }
}
