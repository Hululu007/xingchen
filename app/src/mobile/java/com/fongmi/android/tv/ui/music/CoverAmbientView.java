package com.fongmi.android.tv.ui.music;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.core.graphics.ColorUtils;

import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.service.MusicBeatState;
import com.fongmi.android.tv.utils.CoverColor;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.ResUtil;

/**
 * 封面一体背景（2026-10-25 主会话按瓜子 CoverAmbientKt 源码逐项复刻，真值见
 * theme-redo/gz-full-parity-1025.md §16）。瓜子播放页背景两套，PlayerScreen 按
 * playerFluidBgEnabled 分发、该开关默认 TRUE——默认是「流体」：
 * 流体：垫底 tone 实色 → 糊封面（64×64、blur 18dp）被放大 (sin Z·0.3＋2.1)、
 * 平移 72dp·sin p／52dp·cos X、旋转 8°·sin Y、alpha 0.58＋0.14 sin Z 呼吸 →
 * 三颗径向光斑（调色板色／s(+0.16,tone)／s(+0.06,调色板)，各自缩放/alpha/
 * 游走公式见 §16.6）→ 轻罩 {0.30,0.38,0.36,0.48}（无图 +0.25）。
 * 相位由节拍包络驱动：rate = dt·速度·(1＋0.65·envA＋0.3·envB)，四相位除数
 * Z=13／p=19／X=27／Y=34；envA/envB 是分析器 BeatFrame 经指数包络（τ .25／
 * .09/.32）的平滑值，分析器本体见 MusicBeatAnalyzer（cg0 移植）。
 * 静态（流体关）：糊图满幅 alpha 0.4、blur 24dp、罩 {0.76,0.82,0.80,0.86}。
 * tone = d(主色)，palette = d(o(图))（CoverColor.cdToneColor 即 o 的移植）。
 * 换歌 crossfade 450ms。动画只在已附着＋可见＋流体开时跑帧回调，不可见即停。
 */
public class CoverAmbientView extends FrameLayout {

    private static final long CROSSFADE_MS = 450L;
    private static final float STATIC_IMAGE_ALPHA = 0.4f;
    private static final float STATIC_BLUR_DP = 24f;
    private static final float FLUID_BLUR_DP = 18f;
    private static final float[] VEIL_STOPS = {0f, 0.4f, 0.78f, 1f};
    private static final float[] VEIL_ALPHAS_FLUID = {0.30f, 0.38f, 0.36f, 0.48f};
    private static final float[] VEIL_ALPHAS_STATIC = {0.76f, 0.82f, 0.80f, 0.86f};
    private static final float VEIL_BOOST_FLUID_NO_IMG = 0.25f;
    private static final float VEIL_BOOST_STATIC_NO_IMG = 0.2f;

    private static Boolean sBlurCapable;

    private final ImageView imageA;
    private final ImageView imageB;
    private final BlobView blobs;
    private final VeilView veil;
    private ImageView frontImage;
    private ImageView backImage;

    private String targetUrl;
    private int baseColor;
    private int paletteColor;
    private boolean hasImage;
    private boolean fluid = true;
    private boolean beatEnabled = true;
    private float phaseSpeed = 1.0f;
    private float beatScale = 1.0f;

    private ValueAnimator crossfade;
    private CustomTarget<Bitmap> pendingTarget;

    private boolean animRunning;
    private long lastFrameNs;
    private double phaseP, phaseX, phaseY, phaseZ;
    private float envA, envB;
    private int fadeFromTone, fadeToTone, fadeFromPalette, fadeToPalette;
    private float fadeT = 1f;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!animRunning) return;
            stepFrame(frameTimeNanos);
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public CoverAmbientView(Context context) {
        this(context, null);
    }

    public CoverAmbientView(Context context, AttributeSet attrs) {
        super(context, attrs);
        baseColor = CoverColor.toneAdjust(CoverColor.FALLBACK_COLOR);
        paletteColor = 0;
        setBackgroundColor(baseColor);
        imageA = buildImageLayer();
        imageB = buildImageLayer();
        blobs = new BlobView(context);
        veil = new VeilView(context);
        addView(imageA);
        addView(imageB);
        addView(blobs);
        addView(veil);
        frontImage = imageB;
        backImage = imageA;
        frontImage.setAlpha(0f);
        backImage.setAlpha(0f);
        veil.setState(baseColor, true, false);
        blobs.setColors(blobColors(baseColor, 0));
    }

    private ImageView buildImageLayer() {
        ImageView v = new ImageView(getContext());
        v.setScaleType(ImageView.ScaleType.MATRIX);
        v.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        applyBlur(v, fluid);
        return v;
    }

    private void applyBlur(ImageView v, boolean fluidMode) {
        if (!blurCapable()) {
            try {
                v.setRenderEffect(null);
            } catch (Throwable ignored) {
            }
            return;
        }
        try {
            float r = ResUtil.dp2px((int) (fluidMode ? FLUID_BLUR_DP : STATIC_BLUR_DP));
            v.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP));
        } catch (Throwable ignored) {
            // 预检已过仍失败：退化为未糊图（64×64 放大本身已软），不阻断其余层
        }
    }

    /** 能力门 p：SDK≥31 才真检 createBlurEffect 不抛错（进程内只检一次）。 */
    public static boolean blurCapable() {
        if (sBlurCapable != null) return sBlurCapable;
        boolean ok = false;
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                ok = RenderEffect.createBlurEffect(1f, 1f, Shader.TileMode.CLAMP) != null;
            } catch (Throwable ignored) {
                ok = false;
            }
        }
        sBlurCapable = ok;
        return ok;
    }

    public int getBaseColor() {
        return baseColor;
    }

    /** 流体开关（瓜子 playerFluidBgEnabled，默认开）。关时回静态糊图口径。 */
    public void setFluidEnabled(boolean enabled) {
        if (fluid == enabled) return;
        fluid = enabled;
        applyBlur(imageA, fluid);
        applyBlur(imageB, fluid);
        if (!fluid) {
            resetImageMatrix(frontImage);
            resetImageMatrix(backImage);
            frontImage.setAlpha(hasImage && blurCapable() ? STATIC_IMAGE_ALPHA : 0f);
            backImage.setAlpha(0f);
        } else {
            frontImage.setAlpha(hasImage && blurCapable() ? fluidImageAlpha() : 0f);
        }
        veil.setState(currentTone(), fluid, hasImage && blurCapable());
        syncAnim();
    }

    public boolean isFluidEnabled() {
        return fluid;
    }

    /** 节拍开关（瓜子 playerFluidBeatEnabled，默认开）：关时帧恒静默、包络归零。 */
    public void setBeatEnabled(boolean enabled) {
        beatEnabled = enabled;
    }

    /** 速度两倍率：相位速度（0.6/1.0/2.5）与节拍响应（0.7/1.0/1.6）。 */
    public void setSpeedFactors(float phase, float beat) {
        phaseSpeed = phase;
        beatScale = beat;
    }

    /**
     * 换封面：新地址才动。取图 64×64；图到后取 tone 与 palette，
     * 图层、垫底、光斑色同窗 450ms crossfade。空/失败回落垫底、不画糊图。
     */
    public void setCover(String url) {
        if (url != null && url.equals(targetUrl)) return;
        targetUrl = url;
        cancelPending();
        if (url == null || url.isEmpty()) {
            applyAtmosphere(null, CoverColor.toneAdjust(CoverColor.FALLBACK_COLOR), 0);
            return;
        }
        final String wanted = url;
        pendingTarget = new CustomTarget<Bitmap>() {
            @Override
            public void onResourceReady(Bitmap resource, Transition<? super Bitmap> transition) {
                if (!wanted.equals(targetUrl)) return;
                int tone = CoverColor.toneAdjust(CoverColor.dominantColor(resource));
                int palette = CoverColor.toneAdjust(CoverColor.cdToneColor(resource));
                applyAtmosphere(resource, tone, palette);
            }

            @Override
            public void onLoadFailed(android.graphics.drawable.Drawable errorDrawable) {
                if (!wanted.equals(targetUrl)) return;
                applyAtmosphere(null, CoverColor.toneAdjust(CoverColor.FALLBACK_COLOR), 0);
            }

            @Override
            public void onLoadCleared(android.graphics.drawable.Drawable placeholder) {
            }
        };
        ImgUtil.load(url, 64, pendingTarget);
    }

    private void cancelPending() {
        if (crossfade != null) {
            crossfade.cancel();
            crossfade = null;
        }
    }

    private int currentTone() {
        return fadeT < 1f ? ColorUtils.blendARGB(fadeFromTone, fadeToTone, fadeT) : baseColor;
    }

    private int currentPalette() {
        return fadeT < 1f ? ColorUtils.blendARGB(fadeFromPalette, fadeToPalette, fadeT) : paletteColor;
    }

    /** 三颗光斑的色：[调色板原色（无则 s(+0.14,tone)）, s(+0.16,tone), s(+0.06,调色板|tone)]。 */
    private static int[] blobColors(int tone, int palette) {
        int c1 = palette != 0 ? palette : shiftValue(tone, 0.14f);
        int c2 = shiftValue(tone, 0.16f);
        int c3 = shiftValue(palette != 0 ? palette : tone, 0.06f);
        return new int[]{c1, c2, c3};
    }

    private void applyAtmosphere(Bitmap bitmap, int newTone, int newPalette) {
        if (crossfade != null) {
            crossfade.cancel();
            crossfade = null;
        }
        fadeFromTone = currentTone();
        fadeToTone = newTone;
        fadeFromPalette = currentPalette();
        fadeToPalette = newPalette;
        fadeT = 0f;
        baseColor = newTone;
        paletteColor = newPalette;
        hasImage = bitmap != null;

        backImage.setImageBitmap(bitmap);
        final ImageView in = backImage;
        final ImageView out = frontImage;
        frontImage = in;
        backImage = out;
        final boolean imgOk = hasImage && blurCapable();
        final float inTarget = imgOk ? (fluid ? fluidImageAlpha() : STATIC_IMAGE_ALPHA) : 0f;
        final float inStart = in.getAlpha();
        final float outStart = out.getAlpha();
        crossfade = ValueAnimator.ofFloat(0f, 1f);
        crossfade.setDuration(CROSSFADE_MS);
        crossfade.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            fadeT = t;
            int toneNow = ColorUtils.blendARGB(fadeFromTone, fadeToTone, t);
            int palNow = ColorUtils.blendARGB(fadeFromPalette, fadeToPalette, t);
            setBackgroundColor(toneNow);
            veil.setState(toneNow, fluid, imgOk);
            blobs.setColors(blobColors(toneNow, palNow));
            in.setAlpha(inStart + (inTarget - inStart) * t);
            out.setAlpha(outStart * (1f - t));
            if (!fluid) resetImageMatrix(in);
        });
        crossfade.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                fadeT = 1f;
                in.setAlpha(inTarget);
                out.setAlpha(0f);
                out.setImageBitmap(null);
                setBackgroundColor(newTone);
                veil.setState(newTone, fluid, imgOk);
                blobs.setColors(blobColors(newTone, newPalette));
            }
        });
        crossfade.start();
        syncAnim();
    }

    // ---------- 流体动画（§16.3/16.4/16.5/16.6 逐项） ----------

    private void syncAnim() {
        boolean want = fluid && isAttachedToWindow() && getVisibility() == View.VISIBLE;
        if (want && !animRunning) {
            animRunning = true;
            lastFrameNs = 0;
            Choreographer.getInstance().postFrameCallback(frameCallback);
        } else if (!want) {
            animRunning = false;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        syncAnim();
    }

    @Override
    protected void onDetachedFromWindow() {
        animRunning = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        syncAnim();
    }

    private float fluidImageAlpha() {
        return clampF(0.58f + 0.14f * (float) Math.sin(phaseZ) + 0.12f * envB, 0.3f, 0.95f);
    }

    private void stepFrame(long frameNs) {
        if (lastFrameNs == 0) {
            lastFrameNs = frameNs;
            return;
        }
        double dtRaw = Math.min(0.1, (frameNs - lastFrameNs) / 1e9);
        lastFrameNs = frameNs;
        // 包络（dg0.a）：dt 钳 [0.0005,0.05]、beatScale 钳 [0,2]
        float dt = (float) Math.max(0.0005, Math.min(0.05, dtRaw));
        float scale = Math.max(0f, Math.min(2f, beatScale));
        float bass = beatEnabled ? MusicBeatState.bass : 0f;
        float pulse = beatEnabled ? MusicBeatState.beatPulse : 0f;
        float targetA = clampF(bass * scale, 0f, 1f);
        envA += (targetA - envA) * (1f - (float) Math.exp(-dt / 0.25f));
        float targetB = clampF(pulse * scale, 0f, 1f);
        float tauB = targetB > envB ? 0.09f : 0.32f;
        envB += (targetB - envB) * (1f - (float) Math.exp(-dt / tauB));
        // 相位（ik1）：rate = dt·速度钳[0.3,3]·(1＋0.65 envA＋0.3 envB)，f3 = rate·2π
        double speed = Math.max(0.3, Math.min(3.0, phaseSpeed));
        double f3 = dtRaw * speed * (1.0 + 0.65 * envA + 0.3 * envB) * 2 * Math.PI;
        phaseP = wrap(phaseP + f3 / 19.0);
        phaseX = wrap(phaseX + f3 / 27.0);
        phaseY = wrap(phaseY + f3 / 34.0);
        phaseZ = wrap(phaseZ + f3 / 13.0);

        layoutFluidImage(frontImage);
        layoutFluidImage(backImage);
        if (fadeT >= 1f && hasImage && blurCapable()) frontImage.setAlpha(fluidImageAlpha());
        blobs.setPhase(phaseP, phaseX, phaseY, phaseZ, envB);
    }

    private static double wrap(double v) {
        double twoPi = 2 * Math.PI;
        v %= twoPi;
        return v < 0 ? v + twoPi : v;
    }

    private static float clampF(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private final Matrix imgMatrix = new Matrix();

    private void layoutFluidImage(ImageView v) {
        if (v == null || v.getDrawable() == null) return;
        int vw = getWidth(), vh = getHeight();
        int iw = v.getDrawable().getIntrinsicWidth();
        int ih = v.getDrawable().getIntrinsicHeight();
        if (vw <= 0 || vh <= 0 || iw <= 0 || ih <= 0) return;
        float base = Math.max((float) vw / iw, (float) vh / ih);
        float mult = (float) ((Math.sin(phaseZ) * 0.3 + 2.1) * (envB * 0.14 + 1.0));
        float s = base * mult;
        float density = getResources().getDisplayMetrics().density;
        float tx = (float) (72.0 * density * Math.sin(phaseP));
        float ty = (float) (52.0 * density * Math.cos(phaseX));
        float rot = (float) (8.0 * Math.sin(phaseY));
        imgMatrix.reset();
        imgMatrix.postScale(s, s);
        imgMatrix.postTranslate((vw - iw * s) / 2f + tx, (vh - ih * s) / 2f + ty);
        imgMatrix.postRotate(rot, vw / 2f, vh / 2f);
        v.setImageMatrix(imgMatrix);
    }

    private void resetImageMatrix(ImageView v) {
        if (v == null || v.getDrawable() == null) return;
        int vw = getWidth(), vh = getHeight();
        int iw = v.getDrawable().getIntrinsicWidth();
        int ih = v.getDrawable().getIntrinsicHeight();
        if (vw <= 0 || vh <= 0 || iw <= 0 || ih <= 0) return;
        float base = Math.max((float) vw / iw, (float) vh / ih);
        imgMatrix.reset();
        imgMatrix.postScale(base, base);
        imgMatrix.postTranslate((vw - iw * base) / 2f, (vh - ih * base) / 2f);
        v.setImageMatrix(imgMatrix);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (fluid) {
            layoutFluidImage(frontImage);
            layoutFluidImage(backImage);
        } else {
            resetImageMatrix(frontImage);
            resetImageMatrix(backImage);
        }
    }

    /** V 移位（瓜子 s()）：HSV 的 V 加 delta 后钳 [0.1,0.68]，H/S 不动。 */
    static int shiftValue(int color, float delta) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.max(0.1f, Math.min(0.68f, hsv[2] + delta));
        return Color.HSVToColor(hsv);
    }

    /** 光斑层：三颗径向渐变软圆（停点 0→色、0.55→色×0.45、1→透明），变换逐项照 §16.6。 */
    private static class BlobView extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int[] colors = {0, 0, 0};
        private double p, x, y, z;
        private float envB;

        BlobView(Context context) {
            super(context);
            setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }

        void setColors(int[] c) {
            colors = c;
            invalidate();
        }

        void setPhase(double p, double x, double y, double z, float envB) {
            this.p = p;
            this.x = x;
            this.y = y;
            this.z = z;
            this.envB = envB;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float corner = (float) (0.5 * Math.sqrt((double) w * w + (double) h * h));
            float beatScale = 1f + 0.25f * envB;
            // 斑一：scale (sinZ·0.2＋1.7)·beatScale；alpha 0.4524＋0.174 sinZ＋0.15 envB；tx W(0.12 sinY＋0.34 sinP)；ty H·0.28 cosX
            drawBlob(canvas, colors[0],
                    w * 0.5f + (float) (w * (0.12 * Math.sin(y) + 0.34 * Math.sin(p))),
                    h * 0.5f + (float) (h * 0.28 * Math.cos(x)),
                    corner * (float) ((Math.sin(z) * 0.2 + 1.7) * beatScale),
                    clampAlpha(0.4524f + 0.174f * (float) Math.sin(z) + 0.15f * envB));
            // 斑二：scale (cosZ·0.22＋1.9)·beatScale；alpha 0.4056＋0.156 cosZ＋0.12 envB；tx W·(−0.32 cosX)；ty H(0.10 cosP＋0.30 sinY)
            drawBlob(canvas, colors[1],
                    w * 0.5f + (float) (w * -0.32 * Math.cos(x)),
                    h * 0.5f + (float) (h * (0.10 * Math.cos(p) + 0.30 * Math.sin(y))),
                    corner * (float) ((Math.cos(z) * 0.22 + 1.9) * beatScale),
                    clampAlpha(0.4056f + 0.156f * (float) Math.cos(z) + 0.12f * envB));
            // 斑三：scale (sinP·0.16＋1.5)·beatScale；alpha 0.36＋0.126 sinP＋0.10 envB；tx W(0.14 cosP −0.28 sinY)；ty H·(−0.32 cosP)
            drawBlob(canvas, colors[2],
                    w * 0.5f + (float) (w * (0.14 * Math.cos(p) - 0.28 * Math.sin(y))),
                    h * 0.5f + (float) (h * -0.32 * Math.cos(p)),
                    corner * (float) ((Math.sin(p) * 0.16 + 1.5) * beatScale),
                    clampAlpha(0.36f + 0.126f * (float) Math.sin(p) + 0.10f * envB));
        }

        private float clampAlpha(float a) {
            return Math.max(0f, Math.min(0.9f, a));
        }

        private void drawBlob(Canvas canvas, int color, float cx, float cy, float radius, float alpha) {
            if (color == 0 || radius <= 0 || alpha <= 0.01f) return;
            int cFull = ColorUtils.setAlphaComponent(color, Math.round(alpha * 255f));
            int cMid = ColorUtils.setAlphaComponent(color, Math.round(alpha * 0.45f * 255f));
            int cEdge = ColorUtils.setAlphaComponent(color, 0);
            paint.setShader(new RadialGradient(cx, cy, radius,
                    new int[]{cFull, cMid, cEdge}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setShader(null);
        }
    }

    /** 渐变罩层：颜色随基准色走、alpha 按流体/静态两套（无图时各自加厚）。 */
    private static class VeilView extends View {

        private final Paint paint = new Paint();
        private int base = Color.BLACK;
        private boolean fluidMode = true;
        private boolean imageOk;

        VeilView(Context context) {
            super(context);
            setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }

        void setState(int color, boolean fluidMode, boolean imageOk) {
            base = color;
            this.fluidMode = fluidMode;
            this.imageOk = imageOk;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int h = getHeight();
            if (h <= 0) return;
            float[] alphas = fluidMode ? VEIL_ALPHAS_FLUID : VEIL_ALPHAS_STATIC;
            float boost = imageOk ? 0f : (fluidMode ? VEIL_BOOST_FLUID_NO_IMG : VEIL_BOOST_STATIC_NO_IMG);
            int[] raw = {shiftValue(base, 0.05f), base, base, shiftValue(base, -0.04f)};
            int[] colors = new int[4];
            for (int i = 0; i < 4; i++) {
                float a = alphas[i] + boost;
                colors[i] = ColorUtils.setAlphaComponent(raw[i], Math.round(Math.min(1f, a) * 255f));
            }
            paint.setShader(new LinearGradient(0f, 0f, 0f, h, colors, VEIL_STOPS, Shader.TileMode.CLAMP));
            canvas.drawRect(0f, 0f, getWidth(), h, paint);
            paint.setShader(null);
        }
    }
}
