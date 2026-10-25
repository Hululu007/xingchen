package com.fongmi.android.tv.ui.music;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.fongmi.android.tv.service.MusicBeatState;

/**
 * 封面一体背景（照瓜子 BlurredCoverAmbient／FluidCoverAmbient 复刻，定谳见 gz-player-logic-dig 与取色记录）。
 * 层序：动画底色（450ms 变色）→ 模糊封面图（64×64、crossfade 450、alpha .4）→ 四档竖向渐变罩。
 * 流体开时图层改 18dp 模糊＋相位变换，上叠三团色团再罩渐变（罩 alpha 换流体系数）。
 * BlurSupport 不可用（API＜31）时不画图层，渐变 alpha 按瓜子补偿（模糊 +.2／流体 +.25）。
 */
public class CoverAmbientView extends FrameLayout {

    private static final long COLOR_ANIM_MS = 450;

    private final ImageView coverView;
    private final BlobsView blobsView;
    private final GradientView gradientView;
    private final Paint fillPaint = new Paint();

    private String coverUrl;
    private int baseColor = CoverColors.fallback();
    private int targetColor = CoverColors.fallback();
    private ValueAnimator colorAnimator;
    private boolean fluid;
    private boolean beat;
    private float speedFactor = 1f;

    // 流体相位（弧度）与 dg0 双路平滑量
    private final float[] phases = new float[4];
    private float envA;
    private float envB;
    private long lastFrameNs;
    private boolean frameRunning;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!frameRunning) return;
            stepFluid(frameTimeNanos);
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public CoverAmbientView(Context context) {
        this(context, null);
    }

    public CoverAmbientView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        coverView = new ImageView(context);
        coverView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(coverView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        blobsView = new BlobsView(context);
        addView(blobsView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        gradientView = new GradientView(context);
        addView(gradientView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        applyLayerMode();
    }

    /** 封面与取色结果一起下发：url 变了才换图，底色 450ms 动画到新色。 */
    public void setCover(String url, int color) {
        boolean urlChanged = url == null ? coverUrl != null : !url.equals(coverUrl);
        coverUrl = url;
        if (urlChanged) {
            if (url == null || url.isEmpty() || !CoverColors.blurSupported()) {
                coverView.setImageDrawable(null);
                coverView.setVisibility(INVISIBLE);
            } else {
                coverView.setVisibility(VISIBLE);
                Glide.with(getContext()).load(url).override(64, 64)
                        .transition(DrawableTransitionOptions.withCrossFade(450))
                        .into(coverView);
            }
        }
        if (color != targetColor) {
            targetColor = color;
            animateColorTo(color);
        }
        gradientView.invalidate();
        blobsView.invalidate();
    }

    public void setFluid(boolean fluid, boolean beat, float speedFactor) {
        this.fluid = fluid;
        this.beat = beat;
        this.speedFactor = speedFactor;
        applyLayerMode();
        updateFrameLoop();
        gradientView.invalidate();
    }

    private void applyLayerMode() {
        if (Build.VERSION.SDK_INT >= 31 && CoverColors.blurSupported()) {
            float radius = dp(fluid ? 18f : 24f);
            try {
                coverView.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                        radius, radius, Shader.TileMode.CLAMP));
            } catch (Throwable ignored) {
                coverView.setRenderEffect(null);
            }
        }
        coverView.setAlpha(fluid ? 1f : 0.4f);
        blobsView.setVisibility(fluid ? VISIBLE : GONE);
        if (!fluid) resetImageTransform();
    }

    private void resetImageTransform() {
        coverView.setScaleX(1f);
        coverView.setScaleY(1f);
        coverView.setTranslationX(0f);
        coverView.setTranslationY(0f);
        coverView.setRotation(0f);
    }

    private void animateColorTo(final int to) {
        if (colorAnimator != null) colorAnimator.cancel();
        final int from = baseColor;
        colorAnimator = ValueAnimator.ofFloat(0f, 1f);
        colorAnimator.setDuration(COLOR_ANIM_MS);
        colorAnimator.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        colorAnimator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            baseColor = blend(from, to, t);
            invalidate();
            gradientView.invalidate();
            blobsView.invalidate();
        });
        colorAnimator.start();
    }

    static int blend(int from, int to, float t) {
        int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * t);
        int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * t);
        int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t);
        return Color.rgb(r, g, b);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        fillPaint.setColor(baseColor);
        canvas.drawRect(0, 0, getWidth(), getHeight(), fillPaint);
        super.onDraw(canvas);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateFrameLoop();
    }

    @Override
    protected void onDetachedFromWindow() {
        frameRunning = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        super.onDetachedFromWindow();
    }

    private void updateFrameLoop() {
        boolean want = fluid && beat && isAttachedToWindow();
        if (want && !frameRunning) {
            frameRunning = true;
            lastFrameNs = 0;
            Choreographer.getInstance().postFrameCallback(frameCallback);
        } else if (!want) {
            frameRunning = false;
        }
    }

    /** ik1＋dg0：相位基数 19/27/34/13；速率＝dt×速度×(慢路×.65＋流体量×.3＋1)；能量慢路 τ.25、流体量上升 τ.09/回落 τ.32。 */
    private void stepFluid(long nowNs) {
        if (lastFrameNs == 0) {
            lastFrameNs = nowNs;
            return;
        }
        float dt = (nowNs - lastFrameNs) / 1.0e9f;
        lastFrameNs = nowNs;
        float dtC = CoverColors.clamp(dt, 0.0005f, 0.05f);
        float gain = CoverColors.clamp(MusicPlayerSettings.fluidBeatFactor(), 0f, 2f);
        float targetA = CoverColors.clamp(MusicBeatState.energy * gain, 0f, 1f);
        float targetB = CoverColors.clamp(MusicBeatState.bass * gain, 0f, 1f);
        envA += (targetA - envA) * (1f - (float) Math.exp(-dtC / 0.25f));
        float tauB = targetB > envB ? 0.09f : 0.32f;
        envB += (targetB - envB) * (1f - (float) Math.exp(-dtC / tauB));
        float speed = CoverColors.clamp(speedFactor, 0.3f, 3.0f);
        float h = dt * speed * (envB * 0.3f + envA * 0.65f + 1f);
        float twoPi = (float) (Math.PI * 2);
        phases[0] = (phases[0] + h * twoPi / 19f) % twoPi;
        phases[1] = (phases[1] + h * twoPi / 27f) % twoPi;
        phases[2] = (phases[2] + h * twoPi / 34f) % twoPi;
        phases[3] = (phases[3] + h * twoPi / 13f) % twoPi;
        applyFluidTransforms();
        blobsView.invalidate();
    }

    /** dk1 case 0：底图缩放/平移/旋转。 */
    private void applyFluidTransforms() {
        float density = getResources().getDisplayMetrics().density;
        float scale = (float) ((Math.sin(phases[3]) * 0.3 + 2.1) * (envB * 0.14 + 1f));
        coverView.setScaleX(scale);
        coverView.setScaleY(scale);
        coverView.setTranslationX(density * 72f * (float) Math.sin(phases[0]));
        coverView.setTranslationY(density * 52f * (float) Math.cos(phases[1]));
        coverView.setRotation((float) (Math.sin(phases[2]) * 8.0));
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /** 三团色团：径向渐变（0→α1／.55→α.45／1→透明）＋各自 graphicsLayer 等价变换。 */
    private class BlobsView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        BlobsView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (!fluid) return;
            int w = getWidth();
            int h = getHeight();
            if (w == 0 || h == 0) return;
            Integer single = CoverColors.single(coverUrl);
            int base = baseColor;
            int c1 = single != null ? single : CoverColors.shiftLight(0.14f, base);
            int c2 = CoverColors.shiftLight(0.16f, base);
            int c3 = CoverColors.shiftLight(0.06f, single != null ? single : base);
            float p1 = phases[0], p2 = phases[1], p3 = phases[2], p4 = phases[3];
            drawBlob(canvas, w, h, c1,
                    CoverColors.clamp(envB * 0.1f + affine((float) Math.sin(p1), 0.28f, 0.8f, 0.45f), 0f, 0.9f),
                    (float) ((Math.sin(p1) * 0.16 + 1.5) * (envB * 0.25 + 1f)),
                    w * (float) (Math.cos(p1) * 0.14 - Math.sin(p3) * 0.28),
                    h * (float) (Math.cos(p1) * -0.32));
            drawBlob(canvas, w, h, c2,
                    CoverColors.clamp(envB * 0.15f + affine((float) Math.sin(p4), 0.3f, 0.78f, 0.58f), 0f, 0.9f),
                    (float) ((Math.sin(p4) * 0.2 + 1.7) * (envB * 0.25 + 1f)),
                    w * (float) (Math.sin(p3) * 0.12 + Math.sin(p1) * 0.34),
                    h * (float) (Math.cos(p2) * 0.28));
            drawBlob(canvas, w, h, c3,
                    CoverColors.clamp(envB * 0.12f + affine((float) Math.cos(p4), 0.3f, 0.78f, 0.52f), 0f, 0.9f),
                    (float) ((Math.cos(p4) * 0.22 + 1.9) * (envB * 0.25 + 1f)),
                    w * (float) (Math.cos(p2) * -0.32),
                    h * (float) (Math.cos(p1) * 0.1 + Math.sin(p3) * 0.3));
        }

        private float affine(float x, float a, float b, float c) {
            return (x * a + b) * c;
        }

        private void drawBlob(Canvas canvas, int w, int h, int color, float alpha, float scale, float tx, float ty) {
            float cx = w / 2f + tx;
            float cy = h / 2f + ty;
            float radius = Math.min(w, h) / 2f * scale;
            if (radius <= 0) return;
            int[] colors = new int[]{
                    withAlpha(color, alpha),
                    withAlpha(color, alpha * 0.45f),
                    withAlpha(color, 0f)};
            paint.setShader(new RadialGradient(cx, cy, radius, colors, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
        }

        private int withAlpha(int color, float alpha) {
            return Color.argb(Math.round(255 * CoverColors.clamp(alpha, 0f, 1f)),
                    Color.red(color), Color.green(color), Color.blue(color));
        }
    }

    /** 四档竖向渐变罩：模糊版基数 .8 组、流体版 .30/.38/.36/.48 组；BlurSupport 不可用时 +f11 补偿。 */
    private class GradientView extends View {
        private final Paint paint = new Paint();

        GradientView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w == 0 || h == 0) return;
            boolean p = CoverColors.blurSupported();
            int top = CoverColors.shiftLight(0.05f, baseColor);
            int bottom = CoverColors.shiftLight(-0.04f, baseColor);
            int[] colors;
            if (fluid) {
                float f12 = p ? 0f : 0.25f;
                colors = new int[]{
                        withAlpha(top, f12 + 0.30f),
                        withAlpha(baseColor, f12 + 0.38f),
                        withAlpha(baseColor, f12 + 0.36f),
                        withAlpha(bottom, f12 + 0.48f)};
            } else {
                float f11 = p ? 0f : 0.2f;
                float base = 0.8f;
                colors = new int[]{
                        withAlpha(top, base - 0.04f + f11),
                        withAlpha(baseColor, base + 0.02f + f11),
                        withAlpha(baseColor, base + f11),
                        withAlpha(bottom, base + 0.06f + f11)};
            }
            paint.setShader(new LinearGradient(0, 0, 0, h, colors,
                    new float[]{0f, 0.4f, 0.78f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
        }

        private int withAlpha(int color, float alpha) {
            return Color.argb(Math.round(255 * CoverColors.clamp(alpha, 0f, 1f)),
                    Color.red(color), Color.green(color), Color.blue(color));
        }
    }
}
