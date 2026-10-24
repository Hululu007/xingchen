package com.fongmi.android.tv.ui.music;

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

/**
 * Apple Music 三页横滑 pager（施工图 §2）：第 0 页歌曲信息／第 1 页播放／第 2 页歌词，
 * 进页默认停播放页（offset=1）。切页动效＝pager 默认 fling 近似（瓜子无时长字面值，
 * 落定动画 250ms 为近似、不冒充）＋交叉淡化公式 alpha＝1−(3−2v)v²，v＝clamp(|页号−当前偏移|)
 * （PlayerScreenKt pagerCrossfade 实读）。子页按页宽平铺，横向拖动跟手、松手吸附整页。
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
    private ValueAnimator snapAnimator;
    private final int touchSlop;

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
        if (snapAnimator != null) snapAnimator.cancel();
        offset = clampPage(page);
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
                if (snapAnimator != null) snapAnimator.cancel();
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
                if (snapAnimator != null) snapAnimator.cancel();
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
                int target = Math.round(offset);
                float velPages = -velocityPxS / (float) w;
                if (velPages > 0.5f) target = Math.min(getChildCount() - 1, (int) Math.floor(offset) + 1);
                else if (velPages < -0.5f) target = Math.max(0, (int) Math.ceil(offset) - 1);
                snapTo(target);
                return true;
            }
            default:
                return super.onTouchEvent(ev);
        }
    }

    private void snapTo(int page) {
        if (snapAnimator != null) snapAnimator.cancel();
        float from = offset;
        snapAnimator = ValueAnimator.ofFloat(from, page);
        snapAnimator.setDuration(250);
        snapAnimator.addUpdateListener(a -> {
            offset = (float) a.getAnimatedValue();
            applyOffset();
        });
        snapAnimator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (snapAnimator != null) snapAnimator.cancel();
        super.onDetachedFromWindow();
    }
}
