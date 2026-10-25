package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/** 系统音量滑杆（Apple 主题音量行与工具面板 SystemVolumeCard 共用）：白轨 .14、填充白 .5、圆头。 */
public class VolumeSliderView extends View {

    public interface Listener {
        void onVolume(float fraction);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private float fraction;
    private Listener listener;
    public int trackColor = 0x24FFFFFF;
    public int fillColor = 0x80FFFFFF;
    public float barHeightDp = 4f;

    public VolumeSliderView(Context context) {
        this(context, null);
    }

    public VolumeSliderView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setFraction(float fraction) {
        this.fraction = Math.max(0f, Math.min(1f, fraction));
        invalidate();
    }

    public float getFraction() {
        return fraction;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float h = barHeightDp * density;
        float cy = getHeight() / 2f;
        paint.setColor(trackColor);
        rect.set(0, cy - h / 2f, getWidth(), cy + h / 2f);
        canvas.drawRoundRect(rect, h / 2f, h / 2f, paint);
        float fillW = getWidth() * fraction;
        if (fillW > 0) {
            paint.setColor(fillColor);
            rect.set(0, cy - h / 2f, Math.max(fillW, h), cy + h / 2f);
            canvas.drawRoundRect(rect, h / 2f, h / 2f, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                float f = getWidth() > 0 ? Math.max(0f, Math.min(1f, event.getX() / getWidth())) : 0f;
                fraction = f;
                invalidate();
                if (listener != null) listener.onVolume(f);
                return true;
            case MotionEvent.ACTION_UP:
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }
}
