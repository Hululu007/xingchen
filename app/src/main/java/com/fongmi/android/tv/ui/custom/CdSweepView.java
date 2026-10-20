package com.fongmi.android.tv.ui.custom;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.PathInterpolator;

/**
 * 流光 CD 切歌扫光（GZ CdSongChange 转写）：切歌时一道扇形光泽扫过盘面——
 * mix 0→1、tween(600, bs4.d 近似曲线)，扫程 = 方向×90°、光强 = max(0, sin(mix×π)×0.65)；
 * 完成时把扫过角度并入 base、mix 归零（下次从新 base 起扫）。平时不绘制、零开销。
 */
public class CdSweepView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float mix;
    private float baseDeg;
    private int dir = 1;
    private ValueAnimator animator;

    public CdSweepView(Context context) {
        super(context);
    }

    public CdSweepView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** 触发一次扫光（direction：&gt;0 顺时针、&lt;=0 逆时针；GZ 口径 direction=skipDirection≤0?−1:1） */
    public void sweep(int direction) {
        dir = direction <= 0 ? -1 : 1;
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(600);
        // bs4.d 为路径缓动（类 Emphasized），以同族贝塞尔近似
        animator.setInterpolator(new PathInterpolator(0.3f, 0f, 0.1f, 1f));
        animator.addUpdateListener(a -> {
            mix = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                baseDeg = wrap(baseDeg + dir * 90f);
                mix = 0f;
                invalidate();
            }
        });
        animator.start();
    }

    private static float wrap(float deg) {
        float r = deg % 360f;
        return r < 0 ? r + 360f : r;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mix <= 0f || mix >= 1f) return;
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        float cx = w / 2f;
        float cy = h / 2f;
        float radius = Math.min(w, h) / 2f;
        float presence = (float) Math.max(0d, Math.sin(mix * Math.PI) * 0.65d);
        int peak = Math.min(255, Math.round(255f * presence));
        int white = (peak << 24) | 0x00FFFFFF;
        // 扇形光：约 50° 宽的柔边楔形（透明→白→透明），随 mix 旋转扫过
        SweepGradient gradient = new SweepGradient(cx, cy,
                new int[]{0x00000000, 0x00000000, white, 0x00000000, 0x00000000},
                new float[]{0f, 0.36f, 0.43f, 0.5f, 1f});
        paint.setShader(gradient);
        canvas.save();
        canvas.rotate(baseDeg + dir * 90f * mix, cx, cy);
        canvas.drawCircle(cx, cy, radius, paint);
        canvas.restore();
        paint.setShader(null);
    }
}
