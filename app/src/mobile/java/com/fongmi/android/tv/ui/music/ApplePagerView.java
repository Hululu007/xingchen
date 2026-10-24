package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

/**
 * Apple Music 三页横滑 pager（施工图 §2）：第 0 页歌曲信息／第 1 页播放／第 2 页歌词，
 * 进页默认停播放页（offset=1）。落页吸附照 GZ 实证（2026-10-25 深挖）：GZ 用 Compose
 * pager 库默认 fling——无 tween 时长，吸附动画＝spring(阻尼比 1.0 无回弹、刚度 400、
 * 可见阈值 1.0)，最多吸附 1 页（PagerSnapDistance.atMost(1)）；落页判定三阈值：
 * |速度|≥400dp/s 按速度方向定页；低速时位置分数 ≤0.5 回原页，且位移＜min(56dp, 页宽/2)
 * 不翻页。本类以半隐式欧拉逐帧积分该 spring（带松手初速），停稳条件 |Δx|＜1px。
 * 交叉淡化公式 alpha＝1−(3−2v)v²，v＝clamp(|页号−当前偏移|)（PlayerScreenKt pagerCrossfade 实读）。
 * 子页按页宽平铺，横向拖动跟手、松手吸附整页。
 * 起点落在 tag＝"noPagerSwipe" 的子视图（进度条／音量杆）内时不拦截该次手势，拖动优先给子视图。
 */
public class ApplePagerView extends FrameLayout {

    private static final String TAG_NO_SWIPE = "noPagerSwipe";

    private float offset = 1f;
    private float downX;
    private float downY;
    private float startOffset;
    private boolean dragging;
    private boolean blockedByChild;
    private long downTimeMs;
    private float lastX;
    private long lastMoveMs;
    private float velocityPxS;
    private final int touchSlop;
    // 吸附 spring 状态（刚度 400、阻尼比 1.0，位置以 px 计、逐帧半隐式欧拉积分）
    private boolean springActive;
    private float springX;
    private float springV;
    private float springTarget;
    private long springLastMs;
    private final android.view.Choreographer.FrameCallback springCallback = new android.view.Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!springActive) return;
            stepSpring();
            if (springActive) android.view.Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public ApplePagerView(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public ApplePagerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        int h = b - t;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
            child.layout(0, 0, w, h);
        }
        applyOffset();
    }

    private void applyOffset() {
        int w = getWidth();
        if (w <= 0) return;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.setTranslationX((i - offset) * w);
            float v = Math.max(0f, Math.min(1f, Math.abs(i - offset)));
            child.setAlpha(1f - (3f - 2f * v) * v * v);
            child.setVisibility(v >= 1f && i != Math.round(offset) ? View.INVISIBLE : View.VISIBLE);
        }
    }

    /** 无动画直落某页（装配时停播放页用）。 */
    public void setPageImmediate(int page) {
        cancelSpring();
        offset = clampPage(page);
        applyOffset();
    }

    private void cancelSpring() {
        springActive = false;
        android.view.Choreographer.getInstance().removeFrameCallback(springCallback);
    }

    /** spring 逐帧推进：a＝−k(x−T)−2√k·v（k=400、阻尼比 1.0）；|Δx|＜1px 且低速时精确落位停稳。 */
    private void stepSpring() {
        int w = getWidth();
        if (w <= 0) {
            springActive = false;
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        float dt = Math.min(0.05f, Math.max(0.001f, (now - springLastMs) / 1000f));
        springLastMs = now;
        float accel = -400f * (springX - springTarget) - 40f * springV;
        springV += accel * dt;
        springX += springV * dt;
        if (Math.abs(springX - springTarget) < 1f && Math.abs(springV) < 10f) {
            springX = springTarget;
            springActive = false;
        }
        offset = clampPage(springX / (float) w);
        applyOffset();
    }

    private float clampPage(float value) {
        return Math.max(0f, Math.min(getChildCount() - 1, value));
    }

    private boolean isInNoSwipeRegion(float x, float y) {
        return hitTagged(this, x, y);
    }

    private boolean hitTagged(View root, float x, float y) {
        if (!(root instanceof android.view.ViewGroup)) return false;
        android.view.ViewGroup group = (android.view.ViewGroup) root;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            float cx = x - child.getLeft() - child.getTranslationX();
            float cy = y - child.getTop() - child.getTranslationY();
            if (cx >= 0 && cy >= 0 && cx < child.getWidth() && cy < child.getHeight()) {
                if (TAG_NO_SWIPE.equals(child.getTag())) return true;
                if (hitTagged(child, cx, cy)) return true;
            }
        }
        return false;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                startOffset = offset;
                dragging = false;
                blockedByChild = isInNoSwipeRegion(ev.getX(), ev.getY());
                downTimeMs = android.os.SystemClock.uptimeMillis();
                lastX = ev.getX();
                lastMoveMs = downTimeMs;
                velocityPxS = 0f;
                cancelSpring();
                return false;
            case MotionEvent.ACTION_MOVE:
                if (blockedByChild || dragging) return dragging;
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                    dragging = true;
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int w = getWidth();
        if (w <= 0) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                startOffset = offset;
                dragging = true;
                downTimeMs = android.os.SystemClock.uptimeMillis();
                lastX = ev.getX();
                lastMoveMs = downTimeMs;
                velocityPxS = 0f;
                cancelSpring();
                return true;
            case MotionEvent.ACTION_MOVE: {
                long now = android.os.SystemClock.uptimeMillis();
                if (now > lastMoveMs) {
                    velocityPxS = (ev.getX() - lastX) / (now - lastMoveMs) * 1000f;
                }
                lastX = ev.getX();
                lastMoveMs = now;
                offset = clampPage(startOffset - (ev.getX() - downX) / (float) w);
                applyOffset();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                dragging = false;
                snapTo(decideTargetPage(ev));
                return true;
            }
            default:
                return super.onTouchEvent(ev);
        }
    }

    /**
     * 落页判定（GZ 三阈值实证）：以手势起始页为基准、最多动 1 页——
     * |速度|≥400dp/s 按速度方向定页（正＝向下一页）；否则位移≥min(56dp, 页宽/2)
     * 且位置分数＞0.5 才翻页，其余回起始页。
     */
    private int decideTargetPage(MotionEvent ev) {
        int w = getWidth();
        float density = getResources().getDisplayMetrics().density;
        int startPage = Math.round(startOffset);
        int target = startPage;
        float velPagesS = -velocityPxS / (float) w;
        if (Math.abs(velocityPxS) >= 400f * density) {
            target = startPage + (velPagesS > 0 ? 1 : -1);
        } else {
            float dispPx = Math.abs(ev.getX() - downX);
            float fraction = offset - startOffset;
            if (dispPx >= Math.min(56f * density, w / 2f) && Math.abs(fraction) > 0.5f) {
                target = startPage + (fraction > 0 ? 1 : -1);
            }
        }
        target = Math.max(startPage - 1, Math.min(startPage + 1, target));
        return Math.max(0, Math.min(getChildCount() - 1, target));
    }

    /** 起 spring 吸附：初速承接松手速度（位置速度＝−手指速度），目标＝整页 px。 */
    private void snapTo(int page) {
        cancelSpring();
        int w = getWidth();
        if (w <= 0) return;
        springX = offset * w;
        springV = -velocityPxS;
        springTarget = page * (float) w;
        springLastMs = android.os.SystemClock.uptimeMillis();
        springActive = true;
        android.view.Choreographer.getInstance().postFrameCallback(springCallback);
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelSpring();
        super.onDetachedFromWindow();
    }
}
