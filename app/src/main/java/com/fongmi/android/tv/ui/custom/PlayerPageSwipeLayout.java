package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 播放页整页竖滑切歌容器（GZ coverAxisLock 模型转写）：
 * 竖轴锁定（|dy|＞|dx|×1.5）＋状态栏死区（起点在状态栏高度内且向下滑不触发），
 * 整页跟手（translationY 钳在锚点内）、过 36dp 出方向提示＋震动一次、
 * 松手过 36dp 或甩动 600dp/s 提交切歌、否则 250ms 回中。
 * 横向手势（歌词页滑入滑出、进度拖动、架列横滑）一律不拦截，点按原样透传子控件。
 * 只挂播放页 themeContainer（五主题通用）；全屏歌词页是独立兄弟层，不受影响。
 */
public class PlayerPageSwipeLayout extends FrameLayout {

    public interface OnPageSwipeListener {
        void onPageSwipe(boolean next);
    }

    private OnPageSwipeListener swipeListener;
    private TextView hintView;
    private String hintNextText;
    private String hintPrevText;

    private float downX;
    private float downY;
    private long downTimeMs;
    private long lastMoveTimeMs;
    private float lastMoveY;
    private boolean tracking;
    private boolean hapticFired;
    private boolean commitPending;
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

    public void setSwipeHintView(TextView hint, String nextText, String prevText) {
        hintView = hint;
        hintNextText = nextText;
        hintPrevText = prevText;
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
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                downTimeMs = SystemClock.elapsedRealtime();
                lastMoveTimeMs = downTimeMs;
                lastMoveY = downY;
                tracking = false;
                hapticFired = false;
                return false;
            case MotionEvent.ACTION_MOVE:
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
                if (!commitPending) animate().cancel();
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
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                downTimeMs = SystemClock.elapsedRealtime();
                lastMoveTimeMs = downTimeMs;
                lastMoveY = downY;
                tracking = false;
                hapticFired = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
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
                    if (!commitPending) animate().cancel();
                }
                float dy = ev.getY() - downY;
                lastMoveY = ev.getY();
                lastMoveTimeMs = SystemClock.elapsedRealtime();
                float anchorPx = anchorPx();
                setTranslationY(Math.max(-anchorPx, Math.min(anchorPx, dy)));
                if (Math.abs(dy) > 36 * density()) {
                    if (!hapticFired) {
                        hapticFired = true;
                        try {
                            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                        } catch (Throwable ignored) {
                        }
                    }
                    if (hintView != null) {
                        hintView.setText(dy < 0 ? hintNextText : hintPrevText);
                        hintView.setVisibility(View.VISIBLE);
                    }
                } else if (hintView != null) {
                    hintView.setVisibility(View.GONE);
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                tracking = false;
                if (hintView != null) hintView.setVisibility(View.GONE);
                float dy = ev.getY() - downY;
                float dtMs = Math.max(1f, (float) (lastMoveTimeMs - downTimeMs));
                float velocityDpS = Math.abs(lastMoveY - downY) / dtMs * 1000f / density();
                if (Math.abs(dy) >= 36 * density() || velocityDpS >= 600f) {
                    commit(dy < 0);
                } else {
                    springBack();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                tracking = false;
                if (hintView != null) hintView.setVisibility(View.GONE);
                springBack();
                return true;
            default:
                return super.onTouchEvent(ev);
        }
    }

    private void commit(boolean next) {
        if (commitPending) return;
        commitPending = true;
        float target = next ? -anchorPx() : anchorPx();
        animate().translationY(target).setDuration(150).withEndAction(() -> {
            setTranslationY(0f);
            commitPending = false;
            if (swipeListener != null) swipeListener.onPageSwipe(next);
        }).start();
    }

    private void springBack() {
        animate().translationY(0f).setDuration(250).start();
    }
}
