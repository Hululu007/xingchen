package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 流光 CD 进度条（照瓜子 CdSeekBar）：外层高 34、轨道容器 10、线宽 2，
 * 未播 ink×.10、已播 accent，时间 9sp muted×.74 在线下两侧。点按/拖动 seek。
 */
public class CdSeekBarView extends View {

    public interface Listener {
        void onSeek(long positionMs);
    }

    private final TextPaint timePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private long positionMs;
    private long durationMs;
    private boolean dragging;
    private float previewFraction = -1f;
    private Listener listener;
    public int inkColor = 0xFF282D36;
    public int accentColor = 0xFF282D36;
    public int mutedColor = 0xFF747D89;

    public CdSeekBarView(Context context) {
        this(context, null);
    }

    public CdSeekBarView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        timePaint.setTextSize(9f * density);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setProgress(long positionMs, long durationMs) {
        this.positionMs = positionMs;
        this.durationMs = durationMs;
        if (!dragging) invalidate();
    }

    private float fraction() {
        if (previewFraction >= 0) return previewFraction;
        if (durationMs <= 0) return 0f;
        return Math.max(0f, Math.min(1f, positionMs / (float) durationMs));
    }

    private long shown() {
        if (previewFraction >= 0 && durationMs > 0) return (long) (previewFraction * durationMs);
        return positionMs;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), Math.round(34f * density));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float lineY = 5f * density;
        float lineH = 2f * density;
        int track = (inkColor & 0x00FFFFFF) | (Math.round(255 * 0.10f) << 24);
        linePaint.setColor(track);
        rect.set(0, lineY, w, lineY + lineH);
        canvas.drawRoundRect(rect, lineH / 2f, lineH / 2f, linePaint);
        float fillW = w * fraction();
        if (fillW > 0) {
            linePaint.setColor(accentColor);
            rect.set(0, lineY, Math.max(fillW, lineH), lineY + lineH);
            canvas.drawRoundRect(rect, lineH / 2f, lineH / 2f, linePaint);
        }
        int timeColor = (mutedColor & 0x00FFFFFF) | (Math.round(255 * 0.74f) << 24);
        timePaint.setColor(timeColor);
        float base = 10f * density + 14f * density;
        timePaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(PlayerScrubberView.formatTime(shown()), 0, base, timePaint);
        timePaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(PlayerScrubberView.formatTime(durationMs), w, base, timePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                previewFraction = fracFor(event.getX());
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    previewFraction = fracFor(event.getX());
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging && listener != null && durationMs > 0) {
                    listener.onSeek((long) (previewFraction * durationMs));
                }
                dragging = false;
                previewFraction = -1f;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                previewFraction = -1f;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private float fracFor(float x) {
        return getWidth() > 0 ? Math.max(0f, Math.min(1f, x / getWidth())) : 0f;
    }
}
