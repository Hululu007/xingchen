package com.fongmi.android.tv.ui.music;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 播放进度条（§1 PlaybackScrubber）：容器高由布局给 18dp；轨道静止 4dp／拖动 5dp
 * （150ms tween）、无拇指、自绘底轨＋已播条。底轨／填充透明度照瓜子 Scrubber
 * 默认口径：闲置底 0.14／拖动 0.22、填充 0.42／激活 0.78。
 */
public class MusicScrubberView extends View {

    public interface OnScrubListener {
        /** 拖动中（fraction 0..1，未提交）。 */
        void onScrubbing(float fraction);

        /** 松手提交（fraction 0..1）。 */
        void onScrubCommit(float fraction);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float fraction;
    private boolean dragging;
    private float trackDp = 4f;
    private ValueAnimator trackAnimator;
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

    public boolean isDragging() {
        return dragging;
    }

    public void setFraction(float value) {
        if (dragging) return;
        fraction = Math.max(0f, Math.min(1f, value));
        invalidate();
    }

    private void animateTrack(float toDp) {
        if (trackAnimator != null) trackAnimator.cancel();
        trackAnimator = ValueAnimator.ofFloat(trackDp, toDp);
        trackAnimator.setDuration(150);
        trackAnimator.addUpdateListener(a -> {
            trackDp = (float) a.getAnimatedValue();
            invalidate();
        });
        trackAnimator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        float density = getResources().getDisplayMetrics().density;
        float trackPx = trackDp * density;
        float top = (h - trackPx) / 2f;
        float radius = trackPx / 2f;
        // 底轨
        paint.setColor(0xFFFFFFFF);
        paint.setAlpha(Math.round(255f * (dragging ? 0.22f : 0.14f)));
        rect.set(0, top, w, top + trackPx);
        canvas.drawRoundRect(rect, radius, radius, paint);
        // 已播条
        float filled = w * Math.max(0f, Math.min(1f, fraction));
        if (filled > 0) {
            paint.setAlpha(Math.round(255f * (dragging ? 0.78f : 0.42f)));
            rect.set(0, top, filled, top + trackPx);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }
        paint.setAlpha(255);
    }

    private float fractionFor(float x) {
        if (getWidth() <= 0) return 0f;
        return Math.max(0f, Math.min(1f, x / (float) getWidth()));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                animateTrack(5f);
                fraction = fractionFor(event.getX());
                if (listener != null) listener.onScrubbing(fraction);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    fraction = fractionFor(event.getX());
                    if (listener != null) listener.onScrubbing(fraction);
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    fraction = fractionFor(event.getX());
                    if (listener != null) listener.onScrubCommit(fraction);
                }
                dragging = false;
                animateTrack(4f);
                invalidate();
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                animateTrack(4f);
                invalidate();
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

    @Override
    protected void onDetachedFromWindow() {
        if (trackAnimator != null) trackAnimator.cancel();
        super.onDetachedFromWindow();
    }
}
