package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 流光 CD 盘面（瓜子 LuminousCdPlayer 口径，gz-full-parity-1025 §5／§15.1 第 5 项）：
 * 盘径＝min(区宽, min(区高, 400dp))；封面按目标矩形绘制、占盘比 0.89285713、圆形裁剪。
 * 四路运动（CdMotion 真值）：spin 16°/秒×倍率（1＋0.65a＋0.3b）；flow＝12＋10a＋65b；
 * caustic＝28＋18a＋110b（初相位 42°）；undertow＝−(8＋4a＋18b)（初相位 214°），a/b 为律动双包络。
 * collapse 相位 ∈(0.01,0.99) 时四路速率全归 0；帧推进 dt 上钳 0.05s；
 * 循环门＝播放中 ∧ 系统动画开关开 ∧ 无前台面板，三者缺一即冻结（相位保留）。
 * 切歌扫光 600ms：扫程 mix×方向×90°、光强 max(0, sin(mixπ)×0.65)、
 * 入盘 alpha＝mix、入盘旋转 (1−mix)×方向×6°（direction：+1 下一首／−1 上一首，0 视为 +1）。
 * 光泽：播放起淡入 700ms、停淡出 450ms（sheenAlpha 时间推进）。
 * 表面明暗由宿主 setDarkSurface 切（AUTO 由宿主按系统态折算后给布尔）；取色由宿主 setCoverTone 喂
 * （o() 路单色，本类只用它染流光层，不自取色）。
 */
public class CdDiscView extends View {

    public static final float COVER_RATIO = 0.89285713f;
    private static final float SPIN_DEG_S = 16f;
    private static final long SWEEP_MS = 600L;
    private static final long SHEEN_IN_MS = 700L;
    private static final long SHEEN_OUT_MS = 450L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path clipPath = new Path();
    private final RectF coverRect = new RectF();

    private boolean darkSurface;
    private int coverTone = 0xFF8A93A5;
    private Bitmap coverBitmap;
    private Bitmap prevBitmap;
    private int sweepDirection = 1;
    private long sweepStartMs = -1L;
    private float sweepMix = 1f;

    private VisualizerRhythmSource rhythmSource;
    private boolean playing;
    private boolean foregroundPanelOpen;
    private float collapseFraction;

    private float spinDeg;
    private float flowDeg;
    private float causticDeg = 42f;
    private float undertowDeg = 214f;
    private float sheenAlpha;
    private long lastFrameMs;
    private boolean frameRunning;
    private final Choreographer.FrameCallback frameCallback = frameTimeNanos -> {
        if (!frameRunning) return;
        stepFrame();
        if (frameRunning) repostFrame();
    };

    private void repostFrame() {
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    public CdDiscView(Context context) {
        super(context);
    }

    public CdDiscView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public void setDarkSurface(boolean value) {
        darkSurface = value;
        invalidate();
    }

    public void setCoverTone(int tone) {
        coverTone = tone;
        invalidate();
    }

    /** 当前 spin 累计角（度，未取余），供宿主接缝核对。 */
    public float spinAngle() {
        return spinDeg;
    }

    /**
     * 换封面：direction 决定扫光方向（+1 下一首／−1 上一首／0 无扫光直换）。
     * 旧图保留作扫光期底层，新图以 alpha＝mix、旋转 (1−mix)×方向×6° 入盘，600ms 后旧图引用释放。
     */
    public void setCoverBitmap(@Nullable Bitmap bitmap, int direction) {
        if (bitmap == coverBitmap) return;
        prevBitmap = coverBitmap;
        coverBitmap = bitmap;
        if (direction == 0 || prevBitmap == null) {
            prevBitmap = null;
            sweepMix = 1f;
            sweepStartMs = -1L;
        } else {
            sweepDirection = direction > 0 ? 1 : -1;
            sweepMix = 0f;
            sweepStartMs = System.currentTimeMillis();
        }
        ensureFrameLoop();
        invalidate();
    }

    public void setRhythmSource(VisualizerRhythmSource source) {
        rhythmSource = source;
    }

    public void setPlaying(boolean value) {
        if (playing == value) return;
        playing = value;
        ensureFrameLoop();
        invalidate();
    }

    public void setForegroundPanelOpen(boolean open) {
        foregroundPanelOpen = open;
        ensureFrameLoop();
    }

    public void setCollapseFraction(float c) {
        collapseFraction = c < 0f ? 0f : Math.min(1f, c);
        invalidate();
    }

    private boolean systemAnimationsOn() {
        try {
            float scale = Settings.Global.getFloat(getContext().getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            return scale != 0f;
        } catch (Throwable t) {
            return true;
        }
    }

    private boolean motionGateOpen() {
        return playing && !foregroundPanelOpen && systemAnimationsOn();
    }

    private boolean sweepActive() {
        return sweepStartMs >= 0 && sweepMix < 1f;
    }

    private boolean sheenSettling() {
        return playing ? sheenAlpha < 1f : sheenAlpha > 0f;
    }

    private void ensureFrameLoop() {
        boolean need = motionGateOpen() || sweepActive() || sheenSettling();
        if (need && !frameRunning) {
            frameRunning = true;
            lastFrameMs = System.currentTimeMillis();
            Choreographer.getInstance().postFrameCallback(frameCallback);
        } else if (!need && frameRunning) {
            frameRunning = false;
            Choreographer.getInstance().removeFrameCallback(frameCallback);
        }
    }

    private void stepFrame() {
        long now = System.currentTimeMillis();
        float dt = Math.min(0.05f, Math.max(0.001f, (now - lastFrameMs) / 1000f));
        lastFrameMs = now;
        // 光泽淡入/淡出（不受运动门限制，跟播放态走）
        if (playing && sheenAlpha < 1f) {
            sheenAlpha = Math.min(1f, sheenAlpha + dt * 1000f / SHEEN_IN_MS);
        } else if (!playing && sheenAlpha > 0f) {
            sheenAlpha = Math.max(0f, sheenAlpha - dt * 1000f / SHEEN_OUT_MS);
        }
        // 扫光推进
        if (sweepActive()) {
            sweepMix = Math.min(1f, (now - sweepStartMs) / (float) SWEEP_MS);
            if (sweepMix >= 1f) {
                prevBitmap = null;
                sweepStartMs = -1L;
            }
        }
        // 四路运动：collapse 中段冻结；运动门关冻结
        boolean frozen = collapseFraction > 0.01f && collapseFraction < 0.99f;
        if (motionGateOpen() && !frozen) {
            float a = rhythmSource != null ? rhythmSource.beatA() : 0f;
            float b = rhythmSource != null ? rhythmSource.beatB() : 0f;
            spinDeg = wrap(spinDeg + SPIN_DEG_S * (1f + 0.65f * a + 0.3f * b) * dt);
            flowDeg = wrap(flowDeg + (12f + 10f * a + 65f * b) * dt);
            causticDeg = wrap(causticDeg + (28f + 18f * a + 110f * b) * dt);
            undertowDeg = wrap(undertowDeg - (8f + 4f * a + 18f * b) * dt);
        }
        invalidate();
        if (!motionGateOpen() && !sweepActive() && !sheenSettling()) {
            frameRunning = false;
            Choreographer.getInstance().removeFrameCallback(frameCallback);
        }
    }

    private static float wrap(float deg) {
        float r = deg % 360f;
        return r < 0f ? r + 360f : r;
    }

    @Override
    protected void onDetachedFromWindow() {
        frameRunning = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ensureFrameLoop();
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
        float disc = Math.min(w, Math.min(h, dp(400f)));
        float cx = w / 2f;
        float cy = h / 2f;
        float r = disc / 2f;
        // 盘体底（表面系）
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(darkSurface ? 0xFF1C1F26 : 0xFFE9E6DF);
        canvas.drawCircle(cx, cy, r, paint);
        // 沟槽环（静止层）
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1f));
        paint.setColor(darkSurface ? 0x14FFFFFF : 0x14000000);
        for (float f = 0.97f; f > COVER_RATIO; f -= 0.03f) {
            canvas.drawCircle(cx, cy, r * f, paint);
        }
        // 封面（目标矩形＋圆形裁剪，旋转 spin；扫光期旧图垫底、新图 alpha/旋转入盘）
        float coverR = r * COVER_RATIO;
        coverRect.set(cx - coverR, cy - coverR, cx + coverR, cy + coverR);
        clipPath.reset();
        clipPath.addCircle(cx, cy, coverR, Path.Direction.CW);
        if (prevBitmap != null && sweepActive()) {
            drawCover(canvas, prevBitmap, 1f, spinDeg);
        }
        if (coverBitmap != null) {
            if (sweepActive() && prevBitmap != null) {
                float mix = sweepMix;
                drawCover(canvas, coverBitmap, mix, spinDeg + (1f - mix) * sweepDirection * 6f);
            } else {
                drawCover(canvas, coverBitmap, 1f, spinDeg);
            }
        } else if (prevBitmap == null) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(coverTone);
            paint.setAlpha(90);
            canvas.drawCircle(cx, cy, coverR, paint);
            paint.setAlpha(255);
        }
        // 流光三路（相位层，alpha 乘光泽）：flow 慢环／caustic 亮斑／undertow 逆环
        if (sheenAlpha > 0f) {
            drawFlowLayer(canvas, cx, cy, coverR, flowDeg, 0.05f);
            drawFlowLayer(canvas, cx, cy, coverR, causticDeg, 0.10f);
            drawFlowLayer(canvas, cx, cy, coverR, undertowDeg, 0.04f);
        }
        // 切歌扫光：一条径向亮带，角度＝mix×方向×90°＋spin，强度 sin(mixπ)×0.65
        if (sweepActive()) {
            float intensity = Math.max(0f, (float) Math.sin(sweepMix * Math.PI) * 0.65f);
            if (intensity > 0f) {
                float angle = sweepMix * sweepDirection * 90f + spinDeg;
                canvas.save();
                canvas.clipPath(clipPath);
                canvas.translate(cx, cy);
                canvas.rotate(angle);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFFFFFF);
                paint.setAlpha(Math.round(intensity * 160f));
                canvas.drawRect(-dp(10f), -coverR, dp(10f), coverR, paint);
                canvas.restore();
            }
        }
        // 中心轴孔
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(darkSurface ? 0xFF14161C : 0xFFF4F2EE);
        canvas.drawCircle(cx, cy, r * 0.085f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1f));
        paint.setColor(darkSurface ? 0x2EFFFFFF : 0x1F000000);
        canvas.drawCircle(cx, cy, r * 0.085f, paint);
        // 外沿描边
        paint.setColor(darkSurface ? 0x24FFFFFF : 0x1A000000);
        canvas.drawCircle(cx, cy, r - dp(0.5f), paint);
    }

    private void drawCover(Canvas canvas, Bitmap bitmap, float alpha, float rotationDeg) {
        canvas.save();
        canvas.clipPath(clipPath);
        canvas.translate(coverRect.centerX(), coverRect.centerY());
        canvas.rotate(rotationDeg);
        canvas.translate(-coverRect.centerX(), -coverRect.centerY());
        bitmapPaint.setAlpha(Math.round(alpha * 255f));
        canvas.drawBitmap(bitmap, null, coverRect, bitmapPaint);
        bitmapPaint.setAlpha(255);
        canvas.restore();
    }

    /** 一路流光：以 coverTone 染色的径向亮斑，绕盘心按相位角旋转偏移。 */
    private void drawFlowLayer(Canvas canvas, float cx, float cy, float coverR,
                               float phaseDeg, float baseAlpha) {
        double rad = Math.toRadians(phaseDeg);
        float ox = (float) Math.cos(rad) * coverR * 0.45f;
        float oy = (float) Math.sin(rad) * coverR * 0.45f;
        int center = (coverTone & 0x00FFFFFF) | (Math.round(baseAlpha * sheenAlpha * 255f) << 24);
        int edge = coverTone & 0x00FFFFFF;
        RadialGradient gradient = new RadialGradient(cx + ox, cy + oy, coverR * 0.9f,
                center, edge, Shader.TileMode.CLAMP);
        canvas.save();
        canvas.clipPath(clipPath);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(gradient);
        canvas.drawCircle(cx, cy, coverR, paint);
        paint.setShader(null);
        canvas.restore();
    }
}
