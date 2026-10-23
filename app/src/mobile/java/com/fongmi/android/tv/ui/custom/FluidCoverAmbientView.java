package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * 流体背景视图（A4，照瓜子 ui/CoverAmbientKt.FluidCoverAmbient 转写为 View 层）。
 * 三色块取当前封面取色（播放页传入）；径向渐变停点 0→alpha 1、0.55→0.45、1→透明。
 * 漂移公式照报告抄：色块 0 缩放 (sin×0.3＋2.1)×(level×0.14＋1.0)、位移 X＝72dp×sin、
 * Y＝52dp×cos、旋转 sin×8°；色块 1 alpha＝钳 (level×0.15＋map(sin,0.3–0.78→0.58),0,0.9)、
 * 缩放 (sin×0.2＋1.7)×(level×0.25＋1.0)；色块 2 alpha＝钳 (level×0.12＋map(cos,0.3–0.78→0.52),0,0.9)、
 * 缩放 (cos×0.22＋1.9)×(level×0.25＋1.0)。
 * 两点星辰侧映射（报告未给确值，不冒充瓜子值）：① map() 的区间映射取线性钳制
 * （sin/cos 落在 0.3–0.78 外时钳到端点）；② 正弦驱动频率取慢漂移（周期约 14s），
 * 频率不在报告内；③ 色块落点取三分 spread（报告只给漂移量，未给落点）。
 * 律动未接通时 level＝0，流体仍按时间正弦漂移，不静止。
 */
public class FluidCoverAmbientView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] colors = {0xFF2E3A4E, 0xFF4A6FA5, 0xFF8E6FB8};
    private MusicRhythmSource rhythmSource;
    private long startMs;

    public FluidCoverAmbientView(Context context) {
        super(context);
        init();
    }

    public FluidCoverAmbientView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        startMs = SystemClock.uptimeMillis();
    }

    /** 三色块取色（播放页封面取色传入；未传入时用中性 fallback 三色占位） */
    public void setColors(int c0, int c1, int c2) {
        colors[0] = c0;
        colors[1] = c1;
        colors[2] = c2;
        invalidate();
    }

    /** 律动信号源（可空；空＝未接通，level 取 0） */
    public void setRhythmSource(MusicRhythmSource source) {
        rhythmSource = source;
    }

    private float level() {
        if (rhythmSource == null) return 0f;
        try {
            float v = rhythmSource.level();
            return Math.max(0f, Math.min(1f, v));
        } catch (Throwable ignored) {
            return 0f;
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 区间线性映射＋钳制（报告 map() 的星辰侧映射） */
    private static float mapRange(float v, float fromLo, float fromHi, float to) {
        if (fromHi <= fromLo) return 0f;
        return clamp((v - fromLo) / (fromHi - fromLo), 0f, 1f) * to;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        float t = (SystemClock.uptimeMillis() - startMs) / 1000f;
        float sin = (float) Math.sin(t * 0.45f);
        float cos = (float) Math.cos(t * 0.45f);
        float level = level();
        float base = Math.min(w, h) * 0.35f;
        // 色块 0：中心，带位移＋旋转
        float s0 = (sin * 0.3f + 2.1f) * (level * 0.14f + 1f);
        drawBlob(canvas, w / 2f + dp(72f) * sin, h / 2f + dp(52f) * cos,
                base * s0, colors[0], 1f, sin * 8f);
        // 色块 1：左上 spread，alpha 带 level 与 sin 映射
        float a1 = clamp(level * 0.15f + mapRange(sin, 0.3f, 0.78f, 0.58f), 0f, 0.9f);
        float s1 = (sin * 0.2f + 1.7f) * (level * 0.25f + 1f);
        drawBlob(canvas, w * 0.25f, h * 0.3f, base * s1, colors[1], a1, 0f);
        // 色块 2：右下 spread，alpha 带 level 与 cos 映射
        float a2 = clamp(level * 0.12f + mapRange(cos, 0.3f, 0.78f, 0.52f), 0f, 0.9f);
        float s2 = (cos * 0.22f + 1.9f) * (level * 0.25f + 1f);
        drawBlob(canvas, w * 0.75f, h * 0.7f, base * s2, colors[2], a2, 0f);
        if (isAttachedToWindow() && isShown()) postInvalidateOnAnimation();
    }

    private void drawBlob(Canvas canvas, float cx, float cy, float radius, int color, float alpha, float rotDeg) {
        if (radius <= 0 || alpha <= 0f) return;
        int a0 = Math.round(255 * alpha);
        int a1 = Math.round(255 * alpha * 0.45f);
        int[] stops = {
                (color & 0x00FFFFFF) | (a0 << 24),
                (color & 0x00FFFFFF) | (a1 << 24),
                (color & 0x00FFFFFF)};
        paint.setShader(new RadialGradient(cx, cy, radius, stops, new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));
        canvas.save();
        if (rotDeg != 0f) canvas.rotate(rotDeg, cx, cy);
        canvas.drawCircle(cx, cy, radius, paint);
        canvas.restore();
        paint.setShader(null);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startMs = SystemClock.uptimeMillis();
        invalidate();
    }
}
