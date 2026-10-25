package com.fongmi.android.tv.ui.music;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;

import androidx.annotation.Nullable;

public class PlayerScrubberView extends View {

    public interface Listener {
        void onScrubPreview(long positionMs);
        void onSeekDragEnd(long positionMs);
        void onSeekTap(long positionMs);
        void onQualityChipTap();
        void onLyricChipTap();
    }

    private final float density;
    private final TextPaint timePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint chipPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bottomPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private long positionMs;
    private long durationMs;
    private boolean scrubbing;
    private float previewFraction = -1f;
    private float blend;
    private ValueAnimator blendAnim;
    private Listener listener;

    private String qualityLabel = "";
    private String lyricLabel = "";
    private boolean qualityEnabled = true;
    private boolean lyricEnabled = true;
    private final RectF qualityChipRect = new RectF();
    private final RectF lyricChipRect = new RectF();
    private boolean downOnChip;

    public float trackHeightDp = 4f;
    public float fillIdle = 0.42f;
    public float fillDrag = 0.78f;
    public float idleIdle = 0.14f;
    public float idleDrag = 0.22f;
    public boolean showTopRow = true;
    public boolean showBottomRow = true;
    public boolean lightInk = false;

    public PlayerScrubberView(Context context) {
        this(context, null);
    }

    public PlayerScrubberView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        timePaint.setColor(0xFFFFFFFF);
        timePaint.setTextSize(11f * density);
        timePaint.setLetterSpacing(0.2f / 11f);
        timePaint.setTypeface(android.graphics.Typeface.MONOSPACE);
        chipPaint.setColor(0xFFFFFFFF);
        chipPaint.setTextSize(11f * density);
        chipPaint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 700));
        bottomPaint.setColor(0xFFFFFFFF);
        bottomPaint.setTextSize(12f * density);
    }

    private float dp(float v) {
        return v * density;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setProgress(long positionMs, long durationMs) {
        this.positionMs = positionMs;
        this.durationMs = durationMs;
        if (!scrubbing) invalidate();
    }

    public void setChips(String qualityLabel, boolean qualityEnabled, String lyricLabel, boolean lyricEnabled) {
        this.qualityLabel = qualityLabel == null ? "" : qualityLabel;
        this.qualityEnabled = qualityEnabled;
        this.lyricLabel = lyricLabel == null ? "" : lyricLabel;
        this.lyricEnabled = lyricEnabled;
        invalidate();
    }

    public boolean isScrubbing() {
        return scrubbing;
    }

    private float fraction() {
        if (previewFraction >= 0f) return previewFraction;
        if (durationMs <= 0) return 0f;
        return Math.max(0f, Math.min(1f, positionMs / (float) durationMs));
    }

    private long displayPosition() {
        if (previewFraction >= 0f && durationMs > 0) return (long) (previewFraction * durationMs);
        return positionMs;
    }

    private void setBlendTarget(float target) {
        if (blendAnim != null) blendAnim.cancel();
        blendAnim = ValueAnimator.ofFloat(blend, target);
        blendAnim.setDuration(150);
        blendAnim.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        blendAnim.addUpdateListener(a -> {
            blend = (float) a.getAnimatedValue();
            invalidate();
        });
        blendAnim.start();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = (int) dp((showTopRow ? 28f : 0f) + 18f + (showBottomRow ? 20f : 0f));
        setMeasuredDimension(w, h);
    }

    public static String formatTime(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return h + ":" + two(m) + ":" + two(s);
        return m + ":" + two(s);
    }

    private static String two(long v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    private int ink(float alpha) {
        int a = Math.round(255f * Math.max(0f, Math.min(1f, alpha)));
        return lightInk ? (a << 24) : ((a << 24) | 0x00FFFFFF);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float topH = showTopRow ? dp(28f) : 0f;
        long shown = displayPosition();
        if (showTopRow) {
            float alpha = 0.72f * blend;
            timePaint.setColor(ink(alpha));
            Paint.FontMetrics fm = timePaint.getFontMetrics();
            float base = topH / 2f - (fm.ascent + fm.descent) / 2f;
            timePaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(formatTime(shown), 0, base, timePaint);
            timePaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(formatTime(durationMs), w, base, timePaint);
            drawChips(canvas, topH);
        }
        float canvasTop = topH;
        float canvasH = dp(18f);
        float trackH = dp(trackHeightDp + blend);
        float cy = canvasTop + canvasH / 2f;
        float idleA = idleIdle + (idleDrag - idleIdle) * blend;
        float fillA = fillIdle + (fillDrag - fillIdle) * blend;
        trackPaint.setShader(null);
        trackPaint.setColor(ink(idleA));
        rect.set(0, cy - trackH / 2f, w, cy + trackH / 2f);
        canvas.drawRoundRect(rect, trackH / 2f, trackH / 2f, trackPaint);
        float frac = fraction();
        float fillW = frac * w;
        if (fillW > 0f) {
            if (fillW < trackH) fillW = trackH;
            int c1 = ink(fillA * 0.72f);
            int c2 = ink(fillA);
            trackPaint.setShader(new LinearGradient(0, 0, Math.max(1f, fillW), 0, c1, c2, Shader.TileMode.CLAMP));
            rect.set(0, cy - trackH / 2f, fillW, cy + trackH / 2f);
            canvas.drawRoundRect(rect, trackH / 2f, trackH / 2f, trackPaint);
            trackPaint.setShader(null);
        }
        if (showBottomRow) {
            float alpha = 0.65f;
            bottomPaint.setColor(ink(alpha));
            Paint.FontMetrics fm = bottomPaint.getFontMetrics();
            float base = canvasTop + canvasH + dp(14f) - (fm.ascent + fm.descent) / 2f;
            bottomPaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(formatTime(shown), 0, base, bottomPaint);
            bottomPaint.setTextAlign(Paint.Align.RIGHT);
            long remain = durationMs - shown;
            canvas.drawText("-" + formatTime(Math.max(0, remain)), w, base, bottomPaint);
        }
    }

    private void drawChips(Canvas canvas, float topH) {
        boolean hasQ = !qualityLabel.isEmpty();
        boolean hasL = !lyricLabel.isEmpty();
        qualityChipRect.setEmpty();
        lyricChipRect.setEmpty();
        if (!hasQ && !hasL) return;
        float scale = scrubbing ? 0.28f : 0.55f;
        float padH = dp(8f) * scale + dp(4f);
        float chipH = dp(20f);
        float qw = hasQ ? chipPaint.measureText(qualityLabel) + padH * 2 : 0;
        float lw = hasL ? chipPaint.measureText(lyricLabel) + padH * 2 : 0;
        float gap = dp(8f);
        float total = qw + (hasQ && hasL ? gap : 0) + lw;
        float x = (getWidth() - total) / 2f;
        float y = (topH - chipH) / 2f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (hasQ) {
            qualityChipRect.set(x, y, x + qw, y + chipH);
            drawChip(canvas, p, qualityChipRect, qualityLabel, qualityEnabled, scale);
            x += qw + gap;
        }
        if (hasL) {
            lyricChipRect.set(x, y, x + lw, y + chipH);
            drawChip(canvas, p, lyricChipRect, lyricLabel, lyricEnabled, scale);
        }
    }

    private void drawChip(Canvas canvas, Paint p, RectF r, String label, boolean enabled, float scale) {
        p.setShader(null);
        p.setColor(ink(0.14f));
        canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, p);
        chipPaint.setColor(ink((enabled ? 0.72f : 0.35f) * (0.45f + 0.55f * scale / 0.55f)));
        chipPaint.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = chipPaint.getFontMetrics();
        float base = r.centerY() - (fm.ascent + fm.descent) / 2f;
        canvas.save();
        canvas.translate(r.centerX(), r.centerY());
        float s = 0.6f + 0.4f * (scale / 0.55f);
        canvas.scale(s, s);
        canvas.drawText(label, 0, base - r.centerY(), chipPaint);
        canvas.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downOnChip = false;
                if (showTopRow && !qualityChipRect.isEmpty() && qualityChipRect.contains(x, event.getY())) {
                    downOnChip = true;
                    return true;
                }
                if (showTopRow && !lyricChipRect.isEmpty() && lyricChipRect.contains(x, event.getY())) {
                    downOnChip = true;
                    return true;
                }
                scrubbing = true;
                setBlendTarget(1f);
                previewFraction = fractionFor(x);
                if (listener != null) listener.onScrubPreview((long) (previewFraction * durationMs));
                getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (scrubbing) {
                    previewFraction = fractionFor(x);
                    if (listener != null) listener.onScrubPreview((long) (previewFraction * durationMs));
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (downOnChip) {
                    if (listener != null) {
                        if (!qualityChipRect.isEmpty() && qualityChipRect.contains(x, event.getY())) listener.onQualityChipTap();
                        else if (!lyricChipRect.isEmpty() && lyricChipRect.contains(x, event.getY())) listener.onLyricChipTap();
                    }
                    downOnChip = false;
                    return true;
                }
                if (scrubbing) {
                    long target = (long) (previewFraction * durationMs);
                    boolean wasDrag = Math.abs(previewFraction - (durationMs > 0 ? positionMs / (float) durationMs : 0f)) > 0.002f;
                    scrubbing = false;
                    setBlendTarget(0f);
                    if (listener != null) {
                        if (wasDrag) listener.onSeekDragEnd(target);
                        else listener.onSeekTap(target);
                    }
                    previewFraction = -1f;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (scrubbing) {
                    scrubbing = false;
                    setBlendTarget(0f);
                    previewFraction = -1f;
                    invalidate();
                }
                downOnChip = false;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private float fractionFor(float x) {
        float w = getWidth();
        if (w <= 0) return 0f;
        return Math.max(0f, Math.min(1f, x / w));
    }
}
