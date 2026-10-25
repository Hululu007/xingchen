package com.fongmi.android.tv.ui.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * 心情电台卡波形条（从零重写，GZ MoodRadioPanel 波形区口径）：
 * 20 根竖条，条高由正弦相位决定；播放时相位推进、振幅趋向 1.0，暂停时相位停住、振幅趋向 0.35。
 * 颜色由宿主按当前心情 tone 喂 setWaveColor。条数 20 为星辰侧自定（GZ 条数未抠到，见对照表）。
 */
public class MoodWaveView extends View {

    private static final int BAR_COUNT = 20;
    private static final float AMP_PLAYING = 1.0f;
    private static final float AMP_PAUSED = 0.35f;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator ampAnimator;
    private float amplitude = AMP_PAUSED;
    private float phase;
    private boolean playing;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!playing) return;
            phase += 0.55f;
            invalidate();
            postDelayed(this, 120);
        }
    };

    public MoodWaveView(Context context) {
        super(context);
        init();
    }

    public MoodWaveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        barPaint.setColor(0xFF8E8E93);
        barPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setWaveColor(int color) {
        barPaint.setColor(color);
        invalidate();
    }

    /** 播放态：true 时振幅趋向 1.0 并推进相位；false 时振幅趋向 0.35、相位停住（不归零）。 */
    public void setPlaying(boolean value) {
        if (playing == value) return;
        playing = value;
        animateAmplitudeTo(value ? AMP_PLAYING : AMP_PAUSED);
        removeCallbacks(ticker);
        if (value && isAttachedToWindow()) post(ticker);
    }

    public boolean isPlaying() {
        return playing;
    }

    private void animateAmplitudeTo(float target) {
        if (ampAnimator != null) ampAnimator.cancel();
        ampAnimator = ValueAnimator.ofFloat(amplitude, target);
        ampAnimator.setDuration(300);
        ampAnimator.addUpdateListener(a -> {
            amplitude = (float) a.getAnimatedValue();
            invalidate();
        });
        ampAnimator.start();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (playing) {
            removeCallbacks(ticker);
            post(ticker);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(ticker);
        if (ampAnimator != null) ampAnimator.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float density = getResources().getDisplayMetrics().density;
        float barWidth = 3f * density;
        barPaint.setStrokeWidth(barWidth);
        float slot = (float) w / BAR_COUNT;
        float cy = h / 2f;
        float maxHalf = h / 2f - 2f * density;
        for (int i = 0; i < BAR_COUNT; i++) {
            double s = Math.sin(i * 0.85 + phase) * 0.5 + Math.sin(i * 0.37 - phase * 0.6) * 0.5;
            float ratio = 0.25f + 0.75f * (float) Math.abs(s);
            float half = Math.max(2f * density, maxHalf * ratio * amplitude);
            float x = slot * i + slot / 2f;
            canvas.drawLine(x, cy - half, x, cy + half, barPaint);
        }
    }
}
