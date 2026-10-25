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
 * 黑胶唱片架进度条（照瓜子 ShelfSeekBar）：固定高 72dp＝滑杆行 48＋时间行 24；
 * 轨/填充皆 2dp、轨色 Ink α.12、填充 Ink；时间 10sp（瓜子参数位疑值，标注）。
 */
public class ShelfSeekBarView extends View {

    public interface Listener {
        void onSeek(long positionMs);
    }

    public static final int INK = 0xFF252522;
    public static final int MUTED = 0xFF86857E;

    private final TextPaint timePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private long positionMs;
    private long durationMs;
    private boolean dragging;
    private float previewFraction = -1f;
    private Listener listener;

    public ShelfSeekBarView(Context context) {
        this(context, null);
    }

    public ShelfSeekBarView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        timePaint.setTextSize(10f * density);
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
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), Math.round(72f * density));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float lineH = 2f * density;
        float lineY = 24f * density - lineH / 2f;
        int track = (INK & 0x00FFFFFF) | (Math.round(255 * 0.12f) << 24);
        linePaint.setColor(track);
        rect.set(0, lineY, w, lineY + lineH);
        canvas.drawRoundRect(rect, lineH / 2f, lineH / 2f, linePaint);
        float fillW = w * fraction();
        if (fillW > 0) {
            linePaint.setColor(INK);
            rect.set(0, lineY, Math.max(fillW, lineH), lineY + lineH);
            canvas.drawRoundRect(rect, lineH / 2f, lineH / 2f, linePaint);
        }
        timePaint.setColor(MUTED);
        float base = 48f * density + 14f * density;
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
