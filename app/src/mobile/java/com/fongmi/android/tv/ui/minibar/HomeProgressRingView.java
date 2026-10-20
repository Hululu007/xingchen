package com.fongmi.android.tv.ui.minibar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 音乐首页迷你条专用：封面外圈进度环（浅绿点线感的实线环，跟随播放进度）。
 * 只被 ListenMiniBar 的音乐分支（buildMusicBar）使用，听书条不引用。
 */
public class HomeProgressRingView extends View {

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();
    private float fraction;

    public HomeProgressRingView(Context context) {
        super(context);
        init();
    }

    public HomeProgressRingView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        float stroke = 2.5f * getResources().getDisplayMetrics().density;
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(stroke);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setColor(0xFFE3EFE7);
        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeWidth(stroke);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        progressPaint.setColor(0xFF45C08A);
    }

    /** 进度 0..1；无有效时长时传 0 只画轨道。 */
    public void setFraction(float f) {
        float v = f < 0 ? 0 : Math.min(f, 1f);
        if (Math.abs(v - fraction) < 0.001f) return;
        fraction = v;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float inset = Math.max(trackPaint.getStrokeWidth(), progressPaint.getStrokeWidth()) / 2f + 1f;
        arcRect.set(inset, inset, getWidth() - inset, getHeight() - inset);
        canvas.drawArc(arcRect, 0, 360, false, trackPaint);
        if (fraction > 0) canvas.drawArc(arcRect, -90, 360f * fraction, false, progressPaint);
    }
}
