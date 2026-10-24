package com.fongmi.android.tv.ui.custom;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;

/**
 * 双击收藏涟漪层（2026-10-25 深挖 GZ favRipple 全公式落码，代旧自造居中椭圆）：
 * 进度 p 由 tween(800ms, CubicBezier(0.2,0,0,1)) 驱动（先置 0 再趋 1）；中心＝双击点
 * （x<0 时退回画布中心）；D＝画布对角线长、f＝1−p；0<p<1 才画，三圆均为白色：
 * 圆 1 填充 alpha 0.16·f、半径 0.62·D·p；圆 2 描边 alpha 0.45·f、半径同上、
 * 描边宽 (4dp·f＋2dp)px；圆 3 描边 v＝clamp((p−0.22)/0.78,0,1)、v>0 才画、
 * alpha (1−v)·0.3、半径 0.45·D·v、描边宽 2dp。动画结束自移除。
 */
public class FavRippleView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float centerX = -1f;
    private float centerY = -1f;
    private float progress;
    private ValueAnimator animator;

    public FavRippleView(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** 在 (x, y)（本视图坐标）起涟漪；x<0 表示以画布中心为心。 */
    public void startRipple(float x, float y) {
        centerX = x;
        centerY = y;
        progress = 0f;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(800);
        animator.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                removeSelf();
            }

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                removeSelf();
            }
        });
        animator.start();
    }

    private void removeSelf() {
        if (getParent() instanceof ViewGroup) {
            try {
                ((ViewGroup) getParent()).removeView(this);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float p = progress;
        if (p <= 0f || p >= 1f) return;
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float cx = centerX < 0 ? w / 2f : centerX;
        float cy = centerY < 0 ? h / 2f : centerY;
        float diag = (float) Math.hypot(w, h);
        float f = 1f - p;
        float density = getResources().getDisplayMetrics().density;
        // 圆 1：填充
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        paint.setAlpha(Math.round(255f * 0.16f * f));
        canvas.drawCircle(cx, cy, 0.62f * diag * p, paint);
        // 圆 2：描边（同半径）
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth((4f * f + 2f) * density);
        paint.setAlpha(Math.round(255f * 0.45f * f));
        canvas.drawCircle(cx, cy, 0.62f * diag * p, paint);
        // 圆 3：延迟起涨的描边
        float v = Math.max(0f, Math.min(1f, (p - 0.22f) / 0.78f));
        if (v > 0f) {
            paint.setStrokeWidth(2f * density);
            paint.setAlpha(Math.round(255f * (1f - v) * 0.3f));
            canvas.drawCircle(cx, cy, 0.45f * diag * v, paint);
        }
        paint.setAlpha(255);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }
}
