package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

/**
 * 页级竖滑切歌（瓜子 PlayerScreen 口径，gz-full-parity-1025 §1／§13.2／§15.3）：
 * 竖轴锁定 |dy|＞|dx|×1.5；状态栏死区（起始点落在状态栏高度内且下拉时不接管）；
 * 整页 translationY 跟手、钳在锚点 clamp(屏高dp×0.42, 240, 400)dp 内；
 * 过 36dp 出方向提示＋越门震动（haptic 类型 4、跌回门下重新 armed 可再震）；
 * 松手 |位移|≥36dp 按位移符号提交，或释放速度 ≥600dp/s 按速度符号提交；
 * 提交段 150ms＋CubicBezier(0.3,0,0.8,0.15) 滑到锚点后回调，再 spring(阻尼 0.9／刚度 1400) 回中；
 * 未提交 spring 回中。横向手势不拦截、点按透传。
 * 方向提示顶／底分置（§15.3）：上滑＝下一首、提示显在底部；下滑＝上一首、提示显在顶部；
 * 提示两行（标签 11sp 白 0.72＋歌名 15sp 白 0.92，样式由宿主布局给），本类只管显隐与数值：
 * alpha＝clamp((|位移|/锚距−0.06)/(36dp/锚距−0.06))、整体 translationY＝位移×0.1 视差。
 */
public class PlayerPageSwipeLayout extends FrameLayout {

    public interface OnPageSwipeListener {
        void onPageSwipe(boolean next);
    }

    public interface HintNameProvider {
        String nameFor(boolean next);
    }

    private static final float AXIS_DOMINANCE = 1.5f;
    private static final float TRIGGER_DP = 36f;
    private static final float FLING_DP_S = 600f;
    private static final long COMMIT_MS = 150L;
    private static final PathInterpolator COMMIT_INTERPOLATOR = new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);
    private static final double SPRING_STIFFNESS = 1400d;
    private static final double SPRING_DAMPING_RATIO = 0.9d;

    private final int touchSlop;
    private final int statusBarHeight;

    private OnPageSwipeListener swipeListener;
    private boolean swipeEnabled = true;

    private View hintRoot;
    private TextView hintDirView;
    private TextView hintNameView;
    private String hintNextLabel;
    private String hintPrevLabel;
    private HintNameProvider hintNameProvider;

    private float downX;
    private float downY;
    private boolean tracking;
    private boolean hapticArmed;
    private boolean commitPending;
    private VelocityTracker velocityTracker;

    private float springVelocity;
    private long springLastMs;
    private boolean springRunning;
    private final Choreographer.FrameCallback springCallback = frameTimeNanos -> {
        if (!springRunning) return;
        stepSpring();
        if (springRunning) repostSpring();
    };

    private void repostSpring() {
        Choreographer.getInstance().postFrameCallback(springCallback);
    }

    public PlayerPageSwipeLayout(Context context) {
        this(context, null);
    }

    public PlayerPageSwipeLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        statusBarHeight = resId > 0 ? getResources().getDimensionPixelSize(resId)
                : Math.round(24f * density());
    }

    public void setOnPageSwipeListener(OnPageSwipeListener listener) {
        swipeListener = listener;
    }

    /**
     * 挂方向提示：hintRoot 为提示容器（两行 dirView/nameView 在内），
     * 本类按方向把 hintRoot 的 FrameLayout gravity 切到顶部（上一首）／底部（下一首）。
     */
    public void setSwipeHintView(View hintRoot, TextView dirView, TextView nameView,
                                 String nextLabel, String prevLabel, HintNameProvider nameProvider) {
        this.hintRoot = hintRoot;
        this.hintDirView = dirView;
        this.hintNameView = nameView;
        this.hintNextLabel = nextLabel;
        this.hintPrevLabel = prevLabel;
        this.hintNameProvider = nameProvider;
    }

    /** 关：清 tracking、停弹簧、translationY 归 0、藏提示（在途提交动画一并取消）。 */
    public void setSwipeEnabled(boolean enabled) {
        swipeEnabled = enabled;
        if (!enabled) {
            tracking = false;
            commitPending = false;
            animate().cancel();
            stopSpring();
            setTranslationY(0f);
            hideHint();
            recycleTracker();
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

    private void hideHint() {
        if (hintRoot != null) {
            hintRoot.setVisibility(View.GONE);
            hintRoot.setAlpha(1f);
            hintRoot.setTranslationY(0f);
        }
    }

    private void recycleTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    private boolean inStatusDeadZone() {
        int[] loc = new int[2];
        getLocationInWindow(loc);
        return loc[1] + downY < statusBarHeight;
    }

    /** 竖轴锁定判定：过 slop、竖向占优、下拉时不在状态栏死区。 */
    private boolean tryLock(float dx, float dy) {
        if (Math.abs(dy) <= touchSlop) return false;
        if (Math.abs(dy) <= Math.abs(dx) * AXIS_DOMINANCE) return false;
        if (dy > 0 && inStatusDeadZone()) return false;
        tracking = true;
        if (!commitPending) {
            animate().cancel();
            stopSpring();
        }
        return true;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!swipeEnabled) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                tracking = false;
                hapticArmed = false;
                recycleTracker();
                velocityTracker = VelocityTracker.obtain();
                velocityTracker.addMovement(ev);
                return false;
            case MotionEvent.ACTION_MOVE:
                if (velocityTracker != null) velocityTracker.addMovement(ev);
                if (tracking) return true;
                return tryLock(ev.getX() - downX, ev.getY() - downY);
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
                tracking = false;
                hapticArmed = false;
                if (velocityTracker != null) velocityTracker.addMovement(ev);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (velocityTracker != null) velocityTracker.addMovement(ev);
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                if (!tracking && !tryLock(dx, dy)) return true;
                onDrag(dy);
                return true;
            }
            case MotionEvent.ACTION_UP: {
                tracking = false;
                hideHint();
                float dy = ev.getY() - downY;
                float velocityDpS = 0f;
                if (velocityTracker != null) {
                    velocityTracker.addMovement(ev);
                    velocityTracker.computeCurrentVelocity(1000);
                    velocityDpS = velocityTracker.getYVelocity() / density();
                }
                recycleTracker();
                if (Math.abs(dy) >= TRIGGER_DP * density()) {
                    commit(dy < 0);
                } else if (Math.abs(velocityDpS) >= FLING_DP_S) {
                    commit(velocityDpS < 0);
                } else {
                    springBack();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                tracking = false;
                hideHint();
                recycleTracker();
                springBack();
                return true;
            default:
                return super.onTouchEvent(ev);
        }
    }

    private void onDrag(float dy) {
        float anchor = anchorPx();
        setTranslationY(Math.max(-anchor, Math.min(anchor, dy)));
        float triggerPx = TRIGGER_DP * density();
        if (Math.abs(dy) > triggerPx) {
            if (!hapticArmed) {
                hapticArmed = true;
                try {
                    performHapticFeedback(4);
                } catch (Throwable ignored) {
                }
            }
            updateHint(dy, anchor);
        } else {
            hapticArmed = false;
            hideHint();
        }
    }

    private void updateHint(float dy, float anchorPx) {
        if (hintRoot == null) return;
        final boolean next = dy < 0;
        if (hintRoot.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) hintRoot.getLayoutParams();
            int gravity = (next ? Gravity.BOTTOM : Gravity.TOP) | Gravity.CENTER_HORIZONTAL;
            if (lp.gravity != gravity) {
                lp.gravity = gravity;
                hintRoot.setLayoutParams(lp);
            }
        }
        if (hintDirView != null) hintDirView.setText(next ? hintNextLabel : hintPrevLabel);
        if (hintNameView != null) {
            hintNameView.setText(hintNameProvider != null ? hintNameProvider.nameFor(next) : "");
        }
        hintRoot.setVisibility(View.VISIBLE);
        float ratio = Math.abs(dy) / anchorPx;
        float threshold = TRIGGER_DP * density() / anchorPx;
        float alpha = threshold > 0.06f ? (ratio - 0.06f) / (threshold - 0.06f) : 1f;
        hintRoot.setAlpha(Math.max(0f, Math.min(1f, alpha)));
        hintRoot.setTranslationY(dy * 0.1f);
    }

    private void commit(final boolean next) {
        if (commitPending) return;
        stopSpring();
        commitPending = true;
        float target = next ? -anchorPx() : anchorPx();
        animate().translationY(target)
                .setDuration(COMMIT_MS)
                .setInterpolator(COMMIT_INTERPOLATOR)
                .withEndAction(() -> {
                    commitPending = false;
                    if (isAttachedToWindow() && swipeListener != null) swipeListener.onPageSwipe(next);
                    springBack();
                })
                .start();
    }

    // ---------- 回中弹簧：阻尼比 0.9、刚度 1400（半隐式欧拉、dt 钳 0.05s） ----------

    private void springBack() {
        if (getTranslationY() == 0f) return;
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
        double x = getTranslationY();
        double damping = 2d * SPRING_DAMPING_RATIO * Math.sqrt(SPRING_STIFFNESS);
        double accel = -SPRING_STIFFNESS * x - damping * springVelocity;
        springVelocity += (float) (accel * dt);
        float next = (float) (x + springVelocity * dt);
        if (Math.abs(next) < 0.5f && Math.abs(springVelocity) < 8f) {
            setTranslationY(0f);
            springRunning = false;
            return;
        }
        setTranslationY(next);
    }

    @Override
    protected void onDetachedFromWindow() {
        // detach：取消在途提交动画＋停弹簧，避免迟到回调替用户切歌
        animate().cancel();
        commitPending = false;
        stopSpring();
        recycleTracker();
        super.onDetachedFromWindow();
    }
}
