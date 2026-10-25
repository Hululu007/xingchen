package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

/**
 * Apple Music 三页横向 pager（瓜子口径，gz-full-parity-1025 §3／§15.1 第 9 项 c）：
 * 三个子 View 即三页（布局顺序：信息／播放／歌词），进页默认页由宿主 setPageImmediate(1)。
 * 瓜子 pager 本体 fling 无 App 侧时长／缓动字面值（flingBehavior=null，框架默认物理）：
 * 本类以自绘近似实现——吸附至多 1 页、snap 弹簧阻尼 1.0／刚度 400（框架 snap 弹簧真值），
 * 速度门／分数门按框架默认口径（|速度| 优先、否则过半页吸附）。
 * 切页交叉淡化：alpha＝1−(3−2v)v²，v＝clamp(|页号−当前滚动位置|, 0, 1)。
 * 子 View 带 tag "noPagerSwipe" 的区域不触发翻页（其手势由子 View 自己消费，如进度／音量／歌词竖滚）；
 * 竖向手势本类不处理（不拦截）。
 */
public class ApplePagerView extends FrameLayout {

    private static final String TAG_NO_SWIPE = "noPagerSwipe";
    private static final double SNAP_STIFFNESS = 400d;
    private static final double SNAP_DAMPING_RATIO = 1.0d;
    private static final float FLING_DP_S = 400f;

    private final int touchSlop;
    /** 滚动位置（页单位，0..页数−1 的连续值）。 */
    private float scrollPos;
    private int pageCount;
    private float downX;
    private float downY;
    private float startPos;
    private int startPage;
    private boolean dragging;
    private boolean locked;
    private boolean downOnNoSwipe;
    private VelocityTracker velocityTracker;

    private float springVelocity;
    private float springTarget;
    private boolean springRunning;
    private long springLastMs;
    private final Choreographer.FrameCallback springCallback = frameTimeNanos -> {
        if (!springRunning) return;
        stepSpring();
        if (springRunning) repostSpring();
    };

    private void repostSpring() {
        Choreographer.getInstance().postFrameCallback(springCallback);
    }

    public ApplePagerView(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public ApplePagerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    /** 无动画直跳到指定页（宿主进页默认停播放页＝1）。 */
    public void setPageImmediate(int page) {
        stopSpring();
        int count = Math.max(1, getChildCount());
        scrollPos = Math.max(0, Math.min(count - 1, page));
        applyScroll();
    }

    private float density() {
        return getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int w = right - left;
        int h = bottom - top;
        pageCount = getChildCount();
        for (int i = 0; i < pageCount; i++) {
            View child = getChildAt(i);
            child.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
            child.layout(i * w, 0, i * w + w, h);
        }
        applyScroll();
    }

    /** 按 scrollPos 摆位＋交叉淡化：每页 translationX＝−scrollPos×宽，alpha 按距当前位距离。 */
    private void applyScroll() {
        int w = getWidth();
        if (w <= 0) return;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.setTranslationX(-scrollPos * w);
            float v = Math.max(0f, Math.min(1f, Math.abs(i - scrollPos)));
            float fade = (3f - 2f * v) * v * v;
            child.setAlpha(1f - fade);
            child.setVisibility(fade >= 1f ? View.INVISIBLE : View.VISIBLE);
        }
    }

    /** 在指定页内按坐标深查 noPagerSwipe 标记（命中即该区域手势归子 View）。 */
    private boolean hitNoSwipe(View view, float x, float y) {
        if (view == null || view.getVisibility() != View.VISIBLE) return false;
        if (TAG_NO_SWIPE.equals(view.getTag())) return true;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View child = group.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE) continue;
                float cx = x - child.getLeft() + group.getScrollX();
                float cy = y - child.getTop() + group.getScrollY();
                if (cx >= 0 && cx < child.getWidth() && cy >= 0 && cy < child.getHeight()) {
                    if (hitNoSwipe(child, cx, cy)) return true;
                }
            }
        }
        return false;
    }

    private boolean downHitsNoSwipe(float x, float y) {
        int page = Math.round(scrollPos);
        if (page < 0 || page >= getChildCount()) return false;
        View pageView = getChildAt(page);
        // 页内坐标：页布局在 i*w 处、整体 translationX −scrollPos*w，触点减页左界即页内坐标
        float localX = x - pageView.getLeft() - pageView.getTranslationX();
        float localY = y - pageView.getTop() - pageView.getTranslationY();
        if (localX < 0 || localX >= pageView.getWidth() || localY < 0 || localY >= pageView.getHeight()) {
            return false;
        }
        return hitNoSwipe(pageView, localX, localY);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                startPos = scrollPos;
                startPage = Math.round(scrollPos);
                dragging = false;
                locked = false;
                downOnNoSwipe = downHitsNoSwipe(ev.getX(), ev.getY());
                if (velocityTracker != null) velocityTracker.recycle();
                velocityTracker = VelocityTracker.obtain();
                velocityTracker.addMovement(ev);
                if (springRunning) stopSpring();
                return false;
            case MotionEvent.ACTION_MOVE: {
                if (velocityTracker != null) velocityTracker.addMovement(ev);
                if (downOnNoSwipe) return false;
                if (locked) return true;
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                    locked = true;
                    dragging = true;
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                locked = false;
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                startPos = scrollPos;
                startPage = Math.round(scrollPos);
                if (velocityTracker != null) velocityTracker.addMovement(ev);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (velocityTracker != null) velocityTracker.addMovement(ev);
                if (!dragging) {
                    float dx = ev.getX() - downX;
                    float dy = ev.getY() - downY;
                    if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) dragging = true;
                    else return true;
                }
                if (getWidth() > 0) {
                    float dx = ev.getX() - downX;
                    float pos = startPos - dx / getWidth();
                    int count = Math.max(1, getChildCount());
                    scrollPos = Math.max(0f, Math.min(count - 1, pos));
                    applyScroll();
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                float velDpS = 0f;
                if (velocityTracker != null) {
                    velocityTracker.addMovement(ev);
                    velocityTracker.computeCurrentVelocity(1000);
                    velDpS = velocityTracker.getXVelocity() / density();
                }
                recycleTracker();
                dragging = false;
                locked = false;
                snapTo(decideTarget(velDpS));
                performClick();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                recycleTracker();
                dragging = false;
                locked = false;
                snapTo(Math.round(scrollPos));
                return true;
            default:
                return super.onTouchEvent(ev);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    /** 吸附目标：速度优先（至多偏离起始页 1 页），否则就近页（同样钳在起始页 ±1）。 */
    private int decideTarget(float velDpS) {
        int count = Math.max(1, getChildCount());
        int target;
        if (velDpS <= -FLING_DP_S) target = startPage + 1;
        else if (velDpS >= FLING_DP_S) target = startPage - 1;
        else target = Math.round(scrollPos);
        target = Math.max(startPage - 1, Math.min(startPage + 1, target));
        return Math.max(0, Math.min(count - 1, target));
    }

    private void snapTo(int page) {
        springTarget = page;
        springVelocity = 0f;
        springLastMs = System.currentTimeMillis();
        if (!springRunning) {
            springRunning = true;
            Choreographer.getInstance().postFrameCallback(springCallback);
        }
    }

    private void stopSpring() {
        springRunning = false;
        Choreographer.getInstance().removeFrameCallback(springCallback);
    }

    private void stepSpring() {
        long now = System.currentTimeMillis();
        float dt = Math.min(0.05f, Math.max(0.001f, (now - springLastMs) / 1000f));
        springLastMs = now;
        double x = scrollPos - springTarget;
        double damping = 2d * SNAP_DAMPING_RATIO * Math.sqrt(SNAP_STIFFNESS);
        double accel = -SNAP_STIFFNESS * x - damping * springVelocity;
        springVelocity += (float) (accel * dt);
        float next = (float) (springTarget + x + springVelocity * dt);
        if (Math.abs(next - springTarget) < 0.001f && Math.abs(springVelocity) < 0.01f) {
            scrollPos = springTarget;
            springRunning = false;
        } else {
            int count = Math.max(1, getChildCount());
            scrollPos = Math.max(-0.05f, Math.min(count - 1 + 0.05f, next));
        }
        applyScroll();
    }

    private void recycleTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopSpring();
        recycleTracker();
        super.onDetachedFromWindow();
    }
}
