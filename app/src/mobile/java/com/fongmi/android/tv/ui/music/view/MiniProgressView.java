package com.fongmi.android.tv.ui.music.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 播放页迷你进度条（星辰自有件，瓜子没有；2026-10-21 他定案、2026-10-25 点名恢复）：
 * 平时只有一根短条——52dp 宽、视觉条 3dp 高、轨道白 20%、填充白 45%，居中；
 * 点它展开完整进度栈，闲置 3 秒自动缩回（暂停时保持展开，逻辑在 Activity）。
 */
public class MiniProgressView extends View {

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final float mDensity;
    private long mPosition, mDuration;

    public MiniProgressView(Context context) {
        this(context, null);
    }

    public MiniProgressView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = context.getResources().getDisplayMetrics().density;
    }

    public void setProgress(long position, long duration) {
        mPosition = position;
        mDuration = duration;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // 点击热区高 24dp，视觉条只有 3dp 居中
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (int) (24 * mDensity));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float barW = 52 * mDensity;
        float barH = 3 * mDensity;
        float l = (w - barW) / 2f;
        float t = (h - barH) / 2f;
        mPaint.setColor(0x33FFFFFF); // 轨道白 20%
        mRect.set(l, t, l + barW, t + barH);
        canvas.drawRoundRect(mRect, barH / 2f, barH / 2f, mPaint);
        float f = mDuration > 0 ? Math.max(0f, Math.min(1f, (float) mPosition / (float) mDuration)) : 0f;
        if (f > 0) {
            mPaint.setColor(0x73FFFFFF); // 填充白 45%
            mRect.set(l, t, l + barW * f, t + barH);
            canvas.drawRoundRect(mRect, barH / 2f, barH / 2f, mPaint);
        }
    }
}
