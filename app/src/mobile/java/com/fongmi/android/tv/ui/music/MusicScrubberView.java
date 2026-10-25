package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 播放进度条（瓜子 PlaybackScrubber 口径，gz-full-parity-1025 §2／§3）：
 * 无拇指；轨高静止／拖动两档，拖动时 150ms tween 在两档间过渡，已播 alpha 同步过渡。
 * 默认变体（经典／封面卡展开态）：轨 4dp／拖 5dp、已播 alpha 0.42／0.78。
 * Apple 变体：轨 6dp／拖 7dp、已播 alpha 0.58／0.90（底轨 alpha 0.14／拖动 0.22 由本类同口径处理）。
 * 契约三段：onScrubStart（按下命中）→ onScrubPreview(fraction)（拖动预览，宿主只刷时间不提交）
 * → onScrubEnd(fraction)（抬手提交 seek）。点按（按下即抬手、未移动）同样走 Start→End 一次提交。
 * setFraction 只更新显示进度，不回调（宿主播放进度回喂用）。
 */
public class MusicScrubberView extends View {

    public interface OnScrubListener {
        void onScrubStart();

        void onScrubPreview(float fraction);

        void onScrubEnd(float fraction);
    }

    private static final long TRACK_TWEEN_MS = 150L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float idleTrackDp = 4f;
    private float dragTrackDp = 5f;
    private float idlePlayedAlpha = 0.42f;
    private float dragPlayedAlpha = 0.78f;
    private float idleTrackAlpha = 0.14f;
    private float dragTrackAlpha = 0.22f;

    private float fraction;
    private boolean dragging;
    /** 轨高 tween：0＝静止档、1＝拖动档，按真实时间推进。 */
    private float dragBlend;
    private long dragBlendStartMs;
    private boolean dragBlendTarget;

    private OnScrubListener listener;

    public MusicScrubberView(Context context) {
        super(context);
    }

    public MusicScrubberView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public void setOnScrubListener(OnScrubListener listener) {
        this.listener = listener;
    }

    /** 变体口径：静止轨高／拖动轨高（dp）、静止已播 alpha／拖动已播 alpha。 */
    public void setVariant(float idleTrackDp, float dragTrackDp, float idlePlayedAlpha, float dragPlayedAlpha) {
        this.idleTrackDp = idleTrackDp;
        this.dragTrackDp = dragTrackDp;
        this.idlePlayedAlpha = idlePlayedAlpha;
        this.dragPlayedAlpha = dragPlayedAlpha;
        invalidate();
    }

    public boolean isDragging() {
        return dragging;
    }

    public void setFraction(float value) {
        fraction = clamp01(value);
        if (!dragging) invalidate();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(1f, v);
    }

    private void setDragBlendTarget(boolean target) {
        if (dragBlendTarget == target) return;
        // 从当前 blend 值续接 tween，避免中途反向跳变
        dragBlend = currentDragBlend();
        dragBlendTarget = target;
        dragBlendStartMs = System.currentTimeMillis();
        postInvalidateOnAnimation();
    }

    private float currentDragBlend() {
        float from = dragBlend;
        float to = dragBlendTarget ? 1f : 0f;
        if (from == to) return to;
        long elapsed = System.currentTimeMillis() - dragBlendStartMs;
        float t = Math.min(1f, elapsed / (float) TRACK_TWEEN_MS);
        return from + (to - from) * t;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float blend = currentDragBlend();
        if (blend != (dragBlendTarget ? 1f : 0f)) postInvalidateOnAnimation();
        float density = getResources().getDisplayMetrics().density;
        float trackH = (idleTrackDp + (dragTrackDp - idleTrackDp) * blend) * density;
        float playedAlpha = idlePlayedAlpha + (dragPlayedAlpha - idlePlayedAlpha) * blend;
        float trackAlpha = idleTrackAlpha + (dragTrackAlpha - idleTrackAlpha) * blend;
        float w = getWidth();
        float cy = getHeight() / 2f;
        float top = cy - trackH / 2f;
        float radius = trackH / 2f;
        // 底轨：白×trackAlpha
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFF);
        paint.setAlpha(Math.round(trackAlpha * 255f));
        canvas.drawRoundRect(0f, top, w, top + trackH, radius, radius, paint);
        // 已播：白×playedAlpha
        float playedW = w * fraction;
        if (playedW > 0f) {
            paint.setAlpha(Math.round(playedAlpha * 255f));
            canvas.drawRoundRect(0f, top, playedW, top + trackH, radius, radius, paint);
            // 已播段右端收角（圆角只在整轨两端，中间切口画方角补齐左半圆右侧）
            if (playedW > radius && playedW < w) {
                canvas.drawRect(playedW - radius, top, playedW, top + trackH, paint);
            }
        }
    }

    private float fractionForX(float x) {
        if (getWidth() <= 0) return 0f;
        return clamp01(x / getWidth());
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                dragging = true;
                setDragBlendTarget(true);
                fraction = fractionForX(event.getX());
                invalidate();
                if (listener != null) listener.onScrubStart();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return false;
                fraction = fractionForX(event.getX());
                invalidate();
                if (listener != null) listener.onScrubPreview(fraction);
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragging) return false;
                dragging = false;
                setDragBlendTarget(false);
                fraction = fractionForX(event.getX());
                invalidate();
                if (listener != null) listener.onScrubEnd(fraction);
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) return false;
                dragging = false;
                setDragBlendTarget(false);
                invalidate();
                if (listener != null) listener.onScrubEnd(fraction);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
