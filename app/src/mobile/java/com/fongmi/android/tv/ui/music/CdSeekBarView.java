package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * CD／唱片架共用细轨进度（瓜子 CdSeekBar 口径，gz-full-parity-1025 §5／§6）：
 * 无拇指；CD 面容器 10dp／轨 2dp、唱片架面轨 2dp——同一类两面共用，
 * 轨高按视图高钳制（min(2dp, 高/2)）绘制，靠布局高度区分两面形态。
 * OnSeekListener.onSeek(fraction) 语义：按下、拖动、抬手三时点都回调当前 fraction，
 * 宿主按 fraction 即时提交 seek（瓜子 CdSeekBar 拖动即提交口径）；isDragging() 供宿主区分拖动中回喂。
 * setColors(track, fill)：宿主按主题表面（CD palette／唱片架语气色）刷底轨与填充色。
 * setFraction 只回显不回调。
 */
public class CdSeekBarView extends View {

    public interface OnSeekListener {
        void onSeek(float fraction);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int trackColor = 0x1A000000;
    private int fillColor = 0xFF252522;
    private float fraction;
    private boolean dragging;
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
        fraction = value < 0f ? 0f : Math.min(1f, value);
        if (!dragging) invalidate();
    }

    public float fraction() {
        return fraction;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float trackH = Math.min(2f * density, getHeight() / 2f);
        if (trackH <= 0f) return;
        float w = getWidth();
        float cy = getHeight() / 2f;
        float top = cy - trackH / 2f;
        float radius = trackH / 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(trackColor);
        canvas.drawRoundRect(0f, top, w, top + trackH, radius, radius, paint);
        float fillW = w * fraction;
        if (fillW > 0f) {
            paint.setColor(fillColor);
            canvas.drawRoundRect(0f, top, fillW, top + trackH, radius, radius, paint);
            if (fillW > radius && fillW < w) {
                canvas.drawRect(fillW - radius, top, fillW, top + trackH, paint);
            }
        }
    }

    private float fractionForX(float x) {
        if (getWidth() <= 0) return 0f;
        float f = x / getWidth();
        return f < 0f ? 0f : Math.min(1f, f);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                dragging = true;
                fraction = fractionForX(event.getX());
                invalidate();
                if (listener != null) listener.onSeek(fraction);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return false;
                fraction = fractionForX(event.getX());
                invalidate();
                if (listener != null) listener.onSeek(fraction);
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragging) return false;
                dragging = false;
                fraction = fractionForX(event.getX());
                invalidate();
                if (listener != null) listener.onSeek(fraction);
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
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
