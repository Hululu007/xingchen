package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.HapticFeedbackConstants;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 播放页整页竖滑切歌容器（GZ coverAxisLock 模型转写）：
 * 竖轴锁定（|dy|＞|dx|×1.5）＋状态栏死区（起点在状态栏高度内且向下滑不触发），
 * 整页跟手（translationY 钳在锚点内）、过 36dp 出方向提示＋越门震动（armed 可重震）、
 * 松手过 36dp 或甩动 600dp/s 提交切歌、否则弹簧回中
 * （施工图 §1 SpringForce：阻尼比 0.9、刚度 1400——批 3 D 路换真弹簧，手写积分不引新依赖；
 * 跟手拖动段行为不变、只换松手回弹段）。
 * 横向手势（歌词页滑入滑出、进度拖动、架列横滑）一律不拦截，点按原样透传子控件。
 * 只挂播放页 themeContainer（五主题通用）；全屏歌词页是独立兄弟层，不受影响。
 */
public class PlayerPageSwipeLayout extends FrameLayout {

    public interface OnPageSwipeListener {
        void onPageSwipe(boolean next);
    }

    /** 方向提示的相邻歌名供给（由宿主按队列现查，next=true 取下一首）。 */
    public interface HintNameProvider {
        String nameFor(boolean next);
    }

    private OnPageSwipeListener swipeListener;
    private View hintView;
    private TextView hintDirView;
    private TextView hintNameView;
    private HintNameProvider hintNameProvider;
    private String hintNextText;
    private String hintPrevText;
    /** 默认开（经典口径）；Apple 主题关——其歌词页要竖滚、播放页是横滑 pager，竖滑切歌会抢手势（批 3）。 */
    private boolean swipeEnabled = true;

    private float downX;
    private float downY;
    private long downTimeMs;
    private long lastMoveTimeMs;
    private float lastMoveY;
    private boolean tracking;
    /** 越门震动 armed：越过 36dp 震一次、跌回门下重新 armed（GZ PlayerScreen$19$1 口径，haptic 类型 4）。 */
    private boolean hapticArmed;
    private boolean commitPending;
    private VelocityTracker velocityTracker;
    /** 提交段缓动 bs4.f＝(0.3,0,0.8,0.15)（SwipeExitSpec 150ms）。 */
    private static final PathInterpolator COMMIT_INTERPOLATOR = new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);
    private int touchSlop;
    private int statusBarHeight;

    public PlayerPageSwipeLayout(Context context) {
        super(context);
        init(context);
    }

    public PlayerPageSwipeLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        int resId = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) statusBarHeight = context.getResources().getDimensionPixelSize(resId);
    }

    public void setOnPageSwipeListener(OnPageSwipeListener listener) {
        swipeListener = listener;
    }

    public void setSwipeEnabled(boolean enabled) {
        swipeEnabled = enabled;
        if (!enabled) {
            tracking = false;
            stopSpring();
            setTranslationY(0f);
            hideHint();
        }
    }

    public void setSwipeHintView(View hint, TextView dirView, TextView nameView,
                                 String nextText, String prevText, HintNameProvider nameProvider) {
        hintView = hint;
        hintDirView = dirView;
        hintNameView = nameView;
        hintNextText = nextText;
        hintPrevText = prevText;
        hintNameProvider = nameProvider;
    }

    private void hideHint() {
        if (hintView != null) {
            hintView.setVisibility(View.GONE);
            hintView.setAlpha(1f);
            hintView.setTranslationY(0f);
        }
    }

    private float density() {
        return getResources().getDisplayMetrics().density;
    }

    private float anchorPx() {
        float heightDp = getResources().getDisplayMetrics().heightPixels / density();
        float anchorDp = Math.max(240f, Math.min(400f, heightDp * 0.42f));
        return anchorDp * density();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!swipeEnabled) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                downTimeMs = SystemClock.elapsedRealtime();
                lastMoveTimeMs = downTimeMs;
                lastMoveY = downY;
                tracking = false;
                hapticArmed = false;
                if (velocityTracker != null) velocityTracker.recycle();
                velocityTracker = VelocityTracker.obtain();
                velocityTracker.addMovement(ev);
                return false;
            case MotionEvent.ACTION_MOVE:
                if (velocityTracker != null) velocityTracker.addMovement(ev);
                if (tracking) return true;
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                if (Math.abs(dy) <= touchSlop) return false;
                if (Math.abs(dy) <= Math.abs(dx) * 1.5f) return false;
                if (dy > 0) {
                    int[] loc = new int[2];
                    getLocationInWindow(loc);
                    if (loc[1] + downY < statusBarHeight) return false;
                }
                tracking = true;
                if (!commitPending) {
                    animate().cancel();
                    stopSpring();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                tracking = false;
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!swipeEnabled) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                downTimeMs = SystemClock.elapsedRealtime();
                lastMoveTimeMs = downTimeMs;
                lastMoveY = downY;
                tracking = false;
                hapticArmed = false;
                if (velocityTracker != null) velocityTracker.addMovement(ev);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (velocityTracker != null) velocityTracker.addMovement(ev);
                if (!tracking) {
                    float dx = ev.getX() - downX;
                    float dy = ev.getY() - downY;
                    if (Math.abs(dy) <= touchSlop) return true;
                    if (Math.abs(dy) <= Math.abs(dx) * 1.5f) return true;
                    if (dy > 0) {
                        int[] loc = new int[2];
                        getLocationInWindow(loc);
                        if (loc[1] + downY < statusBarHeight) return true;
                    }
                    tracking = true;
                    if (!commitPending) {
                        animate().cancel();
                        stopSpring();
                    }
                }
                float dy = ev.getY() - downY;
                lastMoveY = ev.getY();
                lastMoveTimeMs = SystemClock.elapsedRealtime();
                float anchorPx = anchorPx();
                setTranslationY(Math.max(-anchorPx, Math.min(anchorPx, dy)));
                if (Math.abs(dy) > 36 * density()) {
                    if (!hapticArmed) {
                        hapticArmed = true;
                        try {
                            performHapticFeedback(4);
                        } catch (Throwable ignored) {
                        }
                    }
                    if (hintView != null) {
                        boolean next = dy < 0;
                        // 提示分置（GZ SwipeDirectionHint 两实例真值，smali 深挖 §15.3）：下一首在底部、
                        // 上一首在顶部——两实例各带自己的对齐，不是同一块居中提示换文案。
                        if (hintView.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) hintView.getLayoutParams();
                            int g = (next ? Gravity.BOTTOM : Gravity.TOP) | Gravity.CENTER_HORIZONTAL;
                            if (lp.gravity != g) {
                                lp.gravity = g;
                                hintView.setLayoutParams(lp);
                            }
                        }
                        if (hintDirView != null) hintDirView.setText(next ? hintNextText : hintPrevText);
                        if (hintNameView != null) {
                            hintNameView.setText(hintNameProvider != null ? hintNameProvider.nameFor(next) : "");
                        }
                        hintView.setVisibility(View.VISIBLE);
                        // 渐显（GZ 口径）：alpha＝clamp((|位移|/锚距−0.06)/(36dp/锚距−0.06))，锚距 6% 起显、触发门满显；
                        // 整体随位移×0.1 视差（PlayerScreenKt.java:25391-25410）。
                        float ratio = Math.abs(dy) / anchorPx;
                        float threshold = 36f * density() / anchorPx;
                        float a = threshold > 0.06f ? (ratio - 0.06f) / (threshold - 0.06f) : 1f;
                        hintView.setAlpha(Math.max(0f, Math.min(1f, a)));
                        hintView.setTranslationY(dy * 0.1f);
                    }
                } else {
                    hapticArmed = false;
                    hideHint();
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                tracking = false;
                hideHint();
                float dy = ev.getY() - downY;
                // 松手判定（GZ coverAxisLock$2 口径）：位移≥36dp 按位移符号提交；
                // 位移不足时只看释放速度（VelocityTracker 释放值），≥600dp/s 按速度符号提交。
                float velocityDpS = 0f;
                if (velocityTracker != null) {
                    velocityTracker.addMovement(ev);
                    velocityTracker.computeCurrentVelocity(1000);
                    velocityDpS = velocityTracker.getYVelocity() / density();
                }
                if (Math.abs(dy) >= 36 * density()) {
                    commit(dy < 0);
                } else if (Math.abs(velocityDpS) >= 600f) {
                    commit(velocityDpS < 0);
                } else {
                    springBack();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                tracking = false;
                hideHint();
                springBack();
                return true;
            default:
                return super.onTouchEvent(ev);
        }
    }

    private void commit(boolean next) {
        if (commitPending) return;
        stopSpring();
        commitPending = true;
        float target = next ? -anchorPx() : anchorPx();
        // 提交段（GZ 口径）：150ms＋缓动 (0.3,0,0.8,0.15) 滑到锚点→回调切歌→spring(0.9,1400) 回中，
        // 不再瞬时归零（PlayerScreenKt.java:460；bs4.java:21；coverAxisLock$2.java:290-340）。
        animate().translationY(target).setDuration(150).setInterpolator(COMMIT_INTERPOLATOR).withEndAction(() -> {
            commitPending = false;
            // 页面已销毁/已 detach 时不许替用户切歌（松手后立刻退页的迟到回调）
            if (isAttachedToWindow() && swipeListener != null) swipeListener.onPageSwipe(next);
            springBack();
        }).start();
    }

    // ================= 回弹弹簧（§1：阻尼比 0.9、刚度 1400） =================

    private static final double SPRING_STIFFNESS = 1400d;
    private static final double SPRING_DAMPING_RATIO = 0.9d;
    private float springVelocity;
    private long springLastMs;
    private boolean springRunning;
    private final Choreographer.FrameCallback springCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!springRunning) return;
            stepSpring();
            if (springRunning) Choreographer.getInstance().postFrameCallback(this);
        }
    };

    /** 松手回弹：自当前 translationY 以弹簧积分回 0（半隐式欧拉，dt 钳 0.05s）。 */
    private void springBack() {
        animate().cancel();
        if (getTranslationY() == 0f) return;
        springVelocity = 0f;
        springLastMs = SystemClock.uptimeMillis();
        if (!springRunning) {
            springRunning = true;
            Choreographer.getInstance().postFrameCallback(springCallback);
        }
    }

    private void stepSpring() {
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.05f, Math.max(0.001f, (now - springLastMs) / 1000f));
        springLastMs = now;
        double damping = 2d * SPRING_DAMPING_RATIO * Math.sqrt(SPRING_STIFFNESS);
        double x = getTranslationY();
        springVelocity += (float) ((SPRING_STIFFNESS * (0d - x) - damping * springVelocity) * dt);
        double next = x + springVelocity * dt;
        if (Math.abs(next) < 0.5d && Math.abs(springVelocity) < 10d) {
            setTranslationY(0f);
            springRunning = false;
            return;
        }
        setTranslationY((float) next);
    }

    private void stopSpring() {
        if (springRunning) {
            springRunning = false;
            Choreographer.getInstance().removeFrameCallback(springCallback);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        // 提交动画也要随 detach 取消，只停弹簧挡不住 150ms 内的迟到提交
        animate().cancel();
        stopSpring();
        super.onDetachedFromWindow();
    }
}
