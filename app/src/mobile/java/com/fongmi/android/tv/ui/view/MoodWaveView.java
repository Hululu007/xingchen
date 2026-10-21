package com.fongmi.android.tv.ui.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * 心情电台卡右侧波形条（GZ MoodRadioPanel 的波形区：宽 0.44 比例、高 92dp、
 * 播放振幅 1.0、暂停 0.35；波形色／条数／间距 GZ 未抠到，星辰侧自定：
 * 20 根条、tone 色 0.7 alpha、条间 3dp、条宽自适应；播放时相位动画，暂停时静态低幅）。
 */
public class MoodWaveView extends View {

    private static final int BARS = 20;
    private static final int GAP_DP = 3;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] heights = new float[BARS];
    private float amplitude = 0.35f;
    private float phase;
    private int tone = 0xFFD88445;
    private ValueAnimator animator;

    public MoodWaveView(Context context) {
        super(context);
        init();
    }

    public MoodWaveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xB3D88445);
        for (int i = 0; i < BARS; i++) heights[i] = 0.4f + 0.6f * (float) Math.abs(Math.sin(i * 0.9));
    }

    public void setTone(int tone) {
        this.tone = tone;
        paint.setColor((tone & 0x00FFFFFF) | 0xB3000000);
        invalidate();
    }

    /** playing=true：振幅 1.0＋相位动画；false：振幅 0.35 静态 */
    public void setPlaying(boolean playing) {
        amplitude = playing ? 1.0f : 0.35f;
        if (playing) {
            if (animator == null) {
                animator = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
                animator.setDuration(900);
                animator.setRepeatCount(ValueAnimator.INFINITE);
                animator.addUpdateListener(a -> {
                    phase = (float) a.getAnimatedValue();
                    invalidate();
                });
            }
            if (!animator.isRunning()) animator.start();
        } else if (animator != null) {
            animator.cancel();
            invalidate();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) animator.cancel();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float gap = GAP_DP * getResources().getDisplayMetrics().density;
        float barW = (w - gap * (BARS - 1)) / BARS;
        if (barW <= 0) return;
        for (int i = 0; i < BARS; i++) {
            float wave = 0.55f + 0.45f * (float) Math.sin(phase + i * 0.7);
            float bh = h * heights[i] * amplitude * wave;
            if (bh < 2) bh = 2;
            float left = i * (barW + gap);
            float top = (h - bh) / 2f;
            canvas.drawRoundRect(left, top, left + barW, top + bh, barW / 2f, barW / 2f, paint);
        }
    }
}
