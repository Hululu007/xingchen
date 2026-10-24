package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 流光 CD／黑胶唱片架共用细轨进度（批 3 D 路）：轨线 2dp、无可见拇指（§4 拇指 0dp 占位；
 * §5 轨高 2dp）。颜色由主题注入：CD＝底轨 ink 系、填充 accent（封面 tone 向 ink 混 0.52）；
 * 唱片架＝底轨 ShelfInk×0.12、填充语气色混 ShelfInk 0.65。容器高由布局给（CD 10dp／架同式）。
 * 触摸：按下/拖动报 fraction、松手提交（与 MusicScrubberView 同口径，但轨厚不随拖动变——§4 无拖动态字面值）。
 */
public class CdSeekBarView extends View {

    public interface OnSeekListener {
        void onSeeking(float fraction);

        void onSeekCommit(float fraction);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float fraction;
    private boolean dragging;
    private int trackColor = 0x33000000;
    private int fillColor = 0xFF282D36;
    private OnSeekListener listener;

    public CdSeekBarView(Context context) {
        super(context);
    }

    public CdSeekBarView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public void setOnSeekListener(OnSeekListener listener) {
        this.listener = listener;
    }

    public void setColors(int track, int fill) {
        trackColor = track;
        fillColor = fill;
        invalidate();
    }

    public boolean isDragging() {
        return dragging;
    }

    public void setFraction(float value) {
        if (dragging) return;
        fraction = Math.max(0f, Math.min(1f, value));
        invalidate();
    }

    public float fraction() {
        return fraction;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        float density = getResources().getDisplayMetrics().density;
        float trackPx = 2f * density;
        float top = (h - trackPx) / 2f;
        float radius = trackPx / 2f;
        paint.setColor(trackColor);
        rect.set(0, top, w, top + trackPx);
        canvas.drawRoundRect(rect, radius, radius, paint);
        float filled = w * Math.max(0f, Math.min(1f, fraction));
        if (filled > 0) {
            paint.setColor(fillColor);
            rect.set(0, top, filled, top + trackPx);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }
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
                fraction = fractionFor(event.getX());
                if (listener != null) listener.onSeeking(fraction);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    fraction = fractionFor(event.getX());
                    if (listener != null) listener.onSeeking(fraction);
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging && listener != null) listener.onSeekCommit(fractionFor(event.getX()));
                dragging = false;
                invalidate();
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
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
}
