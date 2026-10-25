package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * 流体背景视图（照瓜子 CoverAmbientKt.FluidCoverAmbient，2026-10-25 smali 深挖 §15.5 全式重写；
 * 旧版的单正弦驱动＋三分落点＋map() 映射均为星辰侧自编、已废）：
 * 驱动＝帧积分相位（无 tween）：Δ＝dt×钳速[0.3,3]×beatFactor×2π，beatFactor＝1＋0.65a＋0.3b；
 * φ1/φ2/φ3/φ4 分别 += Δ/19、Δ/27、Δ/34、Δ/13，各对 2π 取余。
 * 包络 a/b 读律动源双路（dg0 时间常数在源内：a τ0.25、b 升 0.09/降 0.32），
 * 本视图按速度档乘增益 f8（slow 0.7／normal 1.0／fast 1.6，钳 [0,2]）——f8 是增益不是速度，
 * 速度本体 slow 0.6／normal 1.0／fast 2.5 由 setSpeed 下发。
 * 构成（自下而上）：基准色 j 满幅 → 糊底图层（64×64 软图满幅 Crop 上采样即糊，
 * 变换 scale＝(sinφ4×0.3＋2.1)×(1＋b×0.14)、tx＝72dp·sinφ1、ty＝52dp·cosφ2、rot＝sinφ3×8°；
 * GZ 另挂 18dp 模糊，星辰以 64×64 上采样的天然软度承接）→ 三团色块（色＝j 的 HSV 明度移位
 * ＋0.14／＋0.16／＋0.06；各自 alpha/scale/translation 公式见 onDraw，alpha 钳 [0,0.9]）。
 * 色块以径向渐变圆承接 GZ 的全幅图层变换（圆心＝视图中心＋translation，半径＝基半径×scale）。
 */
public class FluidCoverAmbientView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF backdropDst = new RectF();
    private int baseColor = 0xFF2E3A4E;
    private Bitmap backdropBitmap;
    private MusicRhythmSource rhythmSource;
    /** 漂移速度（GZ fluidSpeed 本体：slow 0.6／normal 1.0／fast 2.5；由播放页从设置键下发）。 */
    private float speedFactor = 1.0f;
    /** 律动增益 f8（按速度档：slow 0.7／normal 1.0／fast 1.6）。 */
    private float rhythmGain = 1.0f;
    private double phase1;
    private double phase2;
    private double phase3;
    private double phase4;
    private long lastDrawMs;

    public FluidCoverAmbientView(Context context) {
        super(context);
    }

    public FluidCoverAmbientView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** 基准色 j（封面取色归一色）：满幅垫底＋三团按明度移位派生。 */
    public void setBaseColor(int color) {
        baseColor = color;
        invalidate();
    }

    /** 糊底图层素材：64×64 软图（与经典糊底同源），可空（空时只剩基准色＋三团）。 */
    public void setBackdropBitmap(Bitmap bitmap) {
        backdropBitmap = bitmap;
        invalidate();
    }

    /** 律动信号源（可空；空＝a/b 恒 0、beatFactor 恒 1，流体按基础速度漂移）。 */
    public void setRhythmSource(MusicRhythmSource source) {
        rhythmSource = source;
    }

    /** 漂移速度下发（GZ fluidSpeed 三档 0.6/1.0/2.5）；同时按档定增益 f8（0.7/1.0/1.6）。≤0 视为 1.0。 */
    public void setSpeed(float factor) {
        speedFactor = factor > 0f ? factor : 1.0f;
        rhythmGain = speedFactor <= 0.75f ? 0.7f : speedFactor >= 2f ? 1.6f : 1.0f;
    }

    public float speed() {
        return speedFactor;
    }

    private float beatA() {
        if (rhythmSource == null) return 0f;
        try {
            return Math.max(0f, rhythmSource.beatA());
        } catch (Throwable ignored) {
            return 0f;
        }
    }

    private float beatB() {
        if (rhythmSource == null) return 0f;
        try {
            return Math.max(0f, rhythmSource.beatB());
        } catch (Throwable ignored) {
            return 0f;
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** HSV 明度移位（GZ 色变体 s(Δ)：V＋Δ、钳 [0,1]，色相/饱和不动）。 */
    private static int shiftValue(int color, float dv) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = clamp(hsv[2] + dv, 0f, 1f);
        return Color.HSVToColor(hsv);
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
        long now = SystemClock.uptimeMillis();
        float dt = lastDrawMs == 0 ? 0f : Math.min(0.05f, (now - lastDrawMs) / 1000f);
        lastDrawMs = now;
        float a = clamp(beatA() * rhythmGain, 0f, 2f);
        float b = clamp(beatB() * rhythmGain, 0f, 2f);
        float beatFactor = 1f + 0.65f * a + 0.3f * b;
        double delta = dt * clamp(speedFactor, 0.3f, 3f) * beatFactor * 2.0 * Math.PI;
        phase1 = (phase1 + delta / 19.0) % (2.0 * Math.PI);
        phase2 = (phase2 + delta / 27.0) % (2.0 * Math.PI);
        phase3 = (phase3 + delta / 34.0) % (2.0 * Math.PI);
        phase4 = (phase4 + delta / 13.0) % (2.0 * Math.PI);
        float s1 = (float) Math.sin(phase1);
        float c1 = (float) Math.cos(phase1);
        float s2 = (float) Math.sin(phase2);
        float c2 = (float) Math.cos(phase2);
        float s3 = (float) Math.sin(phase3);
        float s4 = (float) Math.sin(phase4);
        float c4 = (float) Math.cos(phase4);
        float cx = w / 2f;
        float cy = h / 2f;

        // 基准色垫底（组件外满幅填 j 的星辰侧承接：本视图自填）
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(baseColor);
        canvas.drawRect(0, 0, w, h, paint);

        // 糊底图层：满幅 Crop＋相位变换（scale/translation/rotation 照 §15.5 附带式）
        Bitmap backdrop = backdropBitmap;
        if (backdrop != null && !backdrop.isRecycled()) {
            float scale = (s4 * 0.3f + 2.1f) * (1f + b * 0.14f);
            canvas.save();
            canvas.translate(cx + dp(72f) * s1, cy + dp(52f) * c2);
            canvas.rotate(s3 * 8f);
            canvas.scale(scale, scale);
            float coverScale = Math.max(w / backdrop.getWidth(), h / backdrop.getHeight());
            float dw = backdrop.getWidth() * coverScale;
            float dh = backdrop.getHeight() * coverScale;
            backdropDst.set(-dw / 2f, -dh / 2f, dw / 2f, dh / 2f);
            canvas.drawBitmap(backdrop, null, backdropDst, bitmapPaint);
            canvas.restore();
        }

        float baseR = Math.max(w, h) * 0.55f;
        // 团 1（色 V＋0.14）
        drawBlob(canvas,
                cx + w * (s1 * 0.34f + s3 * 0.12f),
                cy + h * (c2 * 0.28f),
                baseR * (s4 * 0.2f + 1.7f) * (1f + b * 0.25f),
                shiftValue(baseColor, 0.14f),
                clamp((s4 * 0.3f + 0.78f) * 0.58f + b * 0.15f, 0f, 0.9f));
        // 团 2（色 V＋0.16）
        drawBlob(canvas,
                cx + w * (c2 * -0.32f),
                cy + h * (s3 * 0.3f + c1 * 0.1f),
                baseR * (c4 * 0.22f + 1.9f) * (1f + b * 0.25f),
                shiftValue(baseColor, 0.16f),
                clamp((c4 * 0.3f + 0.78f) * 0.52f + b * 0.12f, 0f, 0.9f));
        // 团 3（色 V＋0.06）
        drawBlob(canvas,
                cx + w * (s3 * -0.28f + c1 * 0.14f),
                cy + h * (c1 * -0.32f),
                baseR * (s1 * 0.16f + 1.5f) * (1f + b * 0.25f),
                shiftValue(baseColor, 0.06f),
                clamp((s1 * 0.28f + 0.8f) * 0.45f + b * 0.1f, 0f, 0.9f));
        if (isAttachedToWindow() && isShown()) postInvalidateOnAnimation();
    }

    private void drawBlob(Canvas canvas, float cx, float cy, float radius, int color, float alpha) {
        if (radius <= 0 || alpha <= 0f) return;
        int a0 = Math.round(255 * alpha);
        int a1 = Math.round(255 * alpha * 0.45f);
        int[] stops = {
                (color & 0x00FFFFFF) | (a0 << 24),
                (color & 0x00FFFFFF) | (a1 << 24),
                (color & 0x00FFFFFF)};
        paint.setShader(new RadialGradient(cx, cy, radius, stops, new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setShader(null);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        lastDrawMs = 0;
        invalidate();
    }
}
