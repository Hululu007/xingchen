package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Apple 主题音量行（施工图 §2：只有滑杆一行）：行高由布局给 32dp、轨高 6dp、
 * 底轨白 0.14、填充渐变白 0.42→0.60（瓜子 AppleMusicVolumeSlider 绘制 lambda 实读）。
 * 音量值由宿主接系统媒体音量（AudioManager STREAM_MUSIC），本视图只管 fraction 与拖动回调。
 */
public class MusicVolumeSliderView extends View {

    public interface OnVolumeListener {
        void onVolumeChanged(float fraction);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float fraction;
    private boolean dragging;
    private OnVolumeListener listener;

    public MusicVolumeSliderView(Context context) {
        super(context);
    }

    public MusicVolumeSliderView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public void setOnVolumeListener(OnVolumeListener listener) {
        this.listener = listener;
    }

    public void setFraction(float value) {
        if (dragging) return;
        fraction = Math.max(0f, Math.min(1f, value));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        float density = getResources().getDisplayMetrics().density;
        float trackPx = 6f * density;
        float top = (h - trackPx) / 2f;
        float radius = trackPx / 2f;
        paint.setShader(null);
        paint.setColor(0xFFFFFFFF);
        paint.setAlpha(Math.round(255f * 0.14f));
        rect.set(0, top, w, top + trackPx);
        canvas.drawRoundRect(rect, radius, radius, paint);
        float filled = w * Math.max(0f, Math.min(1f, fraction));
        if (filled > 0) {
            paint.setAlpha(255);
            paint.setShader(new LinearGradient(0, 0, w, 0,
                    0x6BFFFFFF, 0x99FFFFFF, Shader.TileMode.CLAMP));
            rect.set(0, top, filled, top + trackPx);
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setShader(null);
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
                fraction = fractionFor(event.getX());
                if (listener != null) listener.onVolumeChanged(fraction);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    fraction = fractionFor(event.getX());
                    if (listener != null) listener.onVolumeChanged(fraction);
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
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
