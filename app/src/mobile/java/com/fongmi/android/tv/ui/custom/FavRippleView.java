package com.fongmi.android.tv.ui.custom;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;

/**
 * 双击收藏涟漪（瓜子 CoverArt 口径，gz-full-parity-1025 §1／§15.8）：
 * 以双击点为心，三圆涟漪半径 0→1 归一推进、800ms、缓动 CubicBezier(0.2,0,0,1)；
 * 动画结束（或取消）自摘——从父容器 removeView，不留残 View。
 * 宿主用法：new 后 addView 到封面容器、调 startRipple(x, y)（坐标为本 View 局部坐标，
 * 宿主须让本 View 铺满封面容器，坐标即容器内坐标）。
 */
public class FavRippleView extends View {

    private static final long DURATION_MS = 800L;
    private static final int CIRCLE_COUNT = 3;
    /** 三圆起始相位错开（归一进度偏移），半径上限取对角线一半的倍数由 onDraw 按尺寸算。 */
    private static final float PHASE_STEP = 0.18f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator animator;
    private float centerX;
    private float centerY;
    private float progress;

    public FavRippleView(Context context) {
        super(context);
        paint.setStyle(Paint.Style.STROKE);
        setClickable(false);
        setFocusable(false);
    }

    /** 以 (x, y) 为心启动涟漪；重复调用先停旧动画再重开。 */
    public void startRipple(float x, float y) {
        centerX = x;
        centerY = y;
        if (animator != null) animator.cancel();
        final ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(DURATION_MS);
        anim.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        anim.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                detachSelf();
            }

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                detachSelf();
            }
        });
        animator = anim;
        anim.start();
    }

    private void detachSelf() {
        if (animator != null) {
            animator.removeAllUpdateListeners();
            animator.removeAllListeners();
            animator = null;
        }
        ViewGroup parent = (ViewGroup) getParent();
        if (parent != null) parent.removeView(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.removeAllUpdateListeners();
            animator.removeAllListeners();
            animator.cancel();
            animator = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (progress <= 0f) return;
        float maxR = (float) Math.hypot(Math.max(centerX, getWidth() - centerX),
                Math.max(centerY, getHeight() - centerY));
        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < CIRCLE_COUNT; i++) {
            float p = progress - i * PHASE_STEP;
            if (p <= 0f || p > 1f) continue;
            float radius = p * maxR;
            int alpha = (int) (0.55f * (1f - p) * 255f);
            paint.setColor(0xFFFFFF);
            paint.setAlpha(Math.max(0, Math.min(255, alpha)));
            paint.setStrokeWidth(2f * density * (1f - p * 0.5f));
            canvas.drawCircle(centerX, centerY, radius, paint);
        }
    }
}
