package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Apple 主题音量滑杆（瓜子口径，gz-full-parity-1025 §3）：
 * 行高 32dp（布局给）、轨 6dp、底轨白 0.14、填充横向渐变白 0.42→0.60、无拇指。
 * onVolume(fraction)：按下／拖动／抬手每次位置变化都回调（宿主即时写音量），抬手不另发结束事件。
 * setFraction 只回显，不回调。
 */
public class MusicVolumeSliderView extends View {

    public interface OnVolumeListener {
        void onVolume(float fraction);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float fraction;
    private boolean dragging;
    private OnVolumeListener listener;
    private LinearGradient fillGradient;
    private float gradientWidth = -1f;

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
        fraction = value < 0f ? 0f : Math.min(1f, value);
        if (!dragging) invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float trackH = 6f * density;
        float w = getWidth();
        float cy = getHeight() / 2f;
        float top = cy - trackH / 2f;
        float radius = trackH / 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(0xFFFFFF);
        paint.setAlpha(Math.round(0.14f * 255f));
        canvas.drawRoundRect(0f, top, w, top + trackH, radius, radius, paint);
        float fillW = w * fraction;
        if (fillW > 0f) {
            if (fillGradient == null || gradientWidth != w) {
                gradientWidth = w;
                fillGradient = new LinearGradient(0f, 0f, w, 0f,
                        0x6BFFFFFF, 0x99FFFFFF, Shader.TileMode.CLAMP);
            }
            paint.setAlpha(255);
            paint.setShader(fillGradient);
            canvas.drawRoundRect(0f, top, fillW, top + trackH, radius, radius, paint);
            if (fillW > radius && fillW < w) {
                canvas.drawRect(fillW - radius, top, fillW, top + trackH, paint);
            }
            paint.setShader(null);
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
                if (listener != null) listener.onVolume(fraction);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return false;
                fraction = fractionForX(event.getX());
                invalidate();
                if (listener != null) listener.onVolume(fraction);
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragging) return false;
                dragging = false;
                fraction = fractionForX(event.getX());
                invalidate();
                if (listener != null) listener.onVolume(fraction);
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
