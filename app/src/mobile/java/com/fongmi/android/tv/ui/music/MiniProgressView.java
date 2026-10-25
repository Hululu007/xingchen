package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 播放页迷你进度条（星辰自有交互，瓜子无此组件）：52×24dp 热区、视觉条 3dp 居中，
 * 轨道白 .20、填充白 .45。与主题底部进度栈互斥，由宿主接线（点它展开、闲置 3s 缩回）。
 */
public class MiniProgressView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private float fraction;

    public MiniProgressView(Context context) {
        this(context, null);
    }

    public MiniProgressView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
    }

    public void setProgress(long positionMs, long durationMs) {
        if (durationMs <= 0) return;
        fraction = Math.max(0f, Math.min(1f, positionMs / (float) durationMs));
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(Math.round(52f * density), Math.round(24f * density));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = 52f * density;
        float h = 3f * density;
        float y = (getHeight() - h) / 2f;
        float x = (getWidth() - w) / 2f;
        paint.setColor(0x33FFFFFF);
        rect.set(x, y, x + w, y + h);
        canvas.drawRoundRect(rect, h / 2f, h / 2f, paint);
        float fillW = w * fraction;
        if (fillW > 0) {
            paint.setColor(0x73FFFFFF);
            rect.set(x, y, x + Math.max(fillW, h), y + h);
            canvas.drawRoundRect(rect, h / 2f, h / 2f, paint);
        }
    }
}
