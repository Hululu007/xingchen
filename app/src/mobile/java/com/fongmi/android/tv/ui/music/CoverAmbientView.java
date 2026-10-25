package com.fongmi.android.tv.ui.music;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.palette.graphics.Palette;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.service.MusicBeatState;
import com.fongmi.android.tv.utils.ImgUtil;

public class CoverAmbientView extends View {

    public static final int FALLBACK_COLOR = 0xFF1C1C1E;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap coverBitmap;
    private String coverKey;
    private int baseColor = FALLBACK_COLOR;
    private int shownColor = FALLBACK_COLOR;
    private ValueAnimator colorAnimator;

    private boolean fluidEnabled = true;
    private boolean beatEnabled = true;
    private float speedFactor = 1f;
    private float beatFactor = 1f;

    private float phase1;
    private float phase2;
    private float phase3;
    private float phase4;
    private float fluidLevel;
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
        super(context);
        setWillNotDraw(false);
    }

    public void setFluidEnabled(boolean enabled) {
        fluidEnabled = enabled;
        updateRunning();
        invalidate();
    }

    public void setBeatEnabled(boolean enabled) {
        beatEnabled = enabled;
    }

    public void setSpeedFactors(float speed, float beat) {
        speedFactor = speed;
        beatFactor = beat;
    }

    public int getBaseColor() {
        return baseColor;
    }

    public void setCover(String url) {
        if (TextUtils.equals(url, coverKey)) return;
        coverKey = url;
        if (TextUtils.isEmpty(url)) {
            coverBitmap = null;
            animateBase(FALLBACK_COLOR);
            invalidate();
            return;
        }
        Glide.with(getContext()).asBitmap().load(ImgUtil.getUrl(url)).override(64, 64)
                .into(new CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull Bitmap resource,
                                                @Nullable Transition<? super Bitmap> transition) {
                        if (!TextUtils.equals(url, coverKey)) return;
                        coverBitmap = resource;
                        invalidate();
                    }

                    @Override
                    public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {
                    }

                    @Override
                    public void onLoadFailed(@Nullable android.graphics.drawable.Drawable errorDrawable) {
                        if (!TextUtils.equals(url, coverKey)) return;
                        coverBitmap = null;
                        invalidate();
                    }
                });
        Glide.with(getContext()).asBitmap().load(ImgUtil.getUrl(url)).override(128, 128)
                .into(new CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull Bitmap resource,
                                                @Nullable Transition<? super Bitmap> transition) {
                        if (!TextUtils.equals(url, coverKey)) return;
                        Palette.from(resource).maximumColorCount(24).generate(palette -> {
                            if (!TextUtils.equals(url, coverKey) || palette == null) return;
                            animateBase(extractAmbientColor(palette));
                        });
                    }

                    @Override
                    public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {
                    }

                    @Override
                    public void onLoadFailed(@Nullable android.graphics.drawable.Drawable errorDrawable) {
                        if (!TextUtils.equals(url, coverKey)) return;
                        animateBase(FALLBACK_COLOR);
                    }
                });
    }

    private static int hueBucket(float hue) {
        return (((int) ((hue + 15f) / 30f)) % 12 + 12) % 12;
    }

    private static int ambientTone(int rgb) {
        float[] hsv = new float[3];
        Color.colorToHSV(rgb, hsv);
        hsv[1] = hsv[1] < 0.06f ? 0f : Math.max(0.16f, Math.min(0.8f, hsv[1] * 1.05f + 0.02f));
        hsv[2] = Math.max(0.38f, Math.min(0.72f, hsv[2]));
        return Color.HSVToColor(hsv);
    }

    static int extractAmbientColor(Palette palette) {
        java.util.List<Palette.Swatch> all = palette.getSwatches();
        if (all.isEmpty()) {
            Palette.Swatch dom = palette.getDominantSwatch();
            return dom == null ? FALLBACK_COLOR : ambientTone(dom.getRgb());
        }
        java.util.List<Palette.Swatch> byLight = new java.util.ArrayList<>();
        for (Palette.Swatch s : all) {
            float l = s.getHsl()[2];
            if (l >= 0.05f && l <= 0.95f) byLight.add(s);
        }
        if (byLight.isEmpty()) {
            Palette.Swatch dom = palette.getDominantSwatch();
            return dom == null ? FALLBACK_COLOR : ambientTone(dom.getRgb());
        }
        int totalPop = 0;
        for (Palette.Swatch s : byLight) totalPop += s.getPopulation();
        java.util.List<Palette.Swatch> saturated = new java.util.ArrayList<>();
        int satPop = 0;
        for (Palette.Swatch s : byLight) {
            if (s.getHsl()[1] >= 0.06f) {
                saturated.add(s);
                satPop += s.getPopulation();
            }
        }
        Palette.Swatch picked = null;
        if (!saturated.isEmpty() && satPop >= totalPop * 0.12f) {
            java.util.Map<Integer, Float> buckets = new java.util.HashMap<>();
            for (Palette.Swatch s : saturated) {
                float[] hsl = s.getHsl();
                float w1 = Math.min(hsl[1], 0.6f) * 0.53f + 0.68f;
                if (w1 > 1f) w1 = 1f;
                float w2 = (hsl[0] < 12f || hsl[0] > 58f || hsl[1] < 0.1f || hsl[1] > 0.58f
                        || hsl[2] < 0.16f || hsl[2] > 0.84f) ? 0.78f : 1.0f;
                int bucket = hueBucket(hsl[0]);
                Float cur = buckets.get(bucket);
                buckets.put(bucket, (cur == null ? 0f : cur) + s.getPopulation() * w1 * w2);
            }
            int bestBucket = -1;
            float bestScore = -1f;
            for (java.util.Map.Entry<Integer, Float> e : buckets.entrySet()) {
                if (e.getValue() > bestScore) {
                    bestScore = e.getValue();
                    bestBucket = e.getKey();
                }
            }
            float bestPick = -1f;
            for (Palette.Swatch s : saturated) {
                float[] hsl = s.getHsl();
                if (hueBucket(hsl[0]) != bestBucket) continue;
                float vf = (hsl[2] >= 0.22f && hsl[2] <= 0.78f) ? 1.0f
                        : (hsl[2] >= 0.12f && hsl[2] <= 0.88f) ? 0.82f : 0.55f;
                float score = (Math.min(hsl[1], 1f) * 0.12f + 0.88f) * s.getPopulation() * vf;
                if (score > bestPick) {
                    bestPick = score;
                    picked = s;
                }
            }
        }
        if (picked == null) {
            int bestPop = -1;
            for (Palette.Swatch s : byLight) {
                if (s.getPopulation() > bestPop) {
                    bestPop = s.getPopulation();
                    picked = s;
                }
            }
        }
        if (picked == null) {
            Palette.Swatch dom = palette.getDominantSwatch();
            return dom == null ? FALLBACK_COLOR : ambientTone(dom.getRgb());
        }
        return ambientTone(picked.getRgb());
    }

    private void animateBase(int target) {
        baseColor = target;
        if (colorAnimator != null) colorAnimator.cancel();
        final int from = shownColor;
        colorAnimator = ValueAnimator.ofFloat(0f, 1f);
        colorAnimator.setDuration(450L);
        colorAnimator.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            shownColor = (int) new ArgbEvaluator().evaluate(f, from, target);
            invalidate();
        });
        colorAnimator.start();
    }

    private void updateRunning() {
        boolean need = fluidEnabled && isAttachedToWindow();
        if (need && !frameRunning) {
            frameRunning = true;
            lastFrameNs = 0;
            Choreographer.getInstance().postFrameCallback(frameCallback);
        } else if (!need) {
            frameRunning = false;
        }
    }

    private void stepFluid(long frameTimeNanos) {
        float dt;
        if (lastFrameNs == 0) dt = 0.016f;
        else dt = (frameTimeNanos - lastFrameNs) / 1_000_000_000f;
        lastFrameNs = frameTimeNanos;
        dt = Math.max(0f, Math.min(0.05f, dt));
        float beat = beatEnabled ? MusicBeatState.beatPulse * beatFactor : 0f;
        float targetLevel = beatEnabled ? MusicBeatState.energy : 0.35f;
        fluidLevel += (targetLevel - fluidLevel) * Math.min(1f, dt * 3f);
        float speed = Math.max(0.3f, Math.min(3f, speedFactor));
        float h = dt * speed * (beat * 0.3f + fluidLevel * 0.65f + 1.0f);
        float twoPi = 6.2831855f;
        phase1 = (phase1 + h * twoPi / 19f) % twoPi;
        phase2 = (phase2 + h * twoPi / 27f) % twoPi;
        phase3 = (phase3 + h * twoPi / 34f) % twoPi;
        phase4 = (phase4 + h * twoPi / 13f) % twoPi;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateRunning();
    }

    @Override
    protected void onDetachedFromWindow() {
        frameRunning = false;
        if (colorAnimator != null) colorAnimator.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        paint.setColor(shownColor);
        canvas.drawRect(0, 0, w, h, paint);
        if (coverBitmap != null) {
            if (fluidEnabled) drawFluidImage(canvas, w, h);
            else drawBlurredImage(canvas, w, h);
        }
        drawOverlay(canvas, w, h);
    }

    private void drawBlurredImage(Canvas canvas, int w, int h) {
        paint.setAlpha(Math.round(255f * 0.4f));
        canvas.drawBitmap(coverBitmap, null, new android.graphics.RectF(0, 0, w, h), paint);
        paint.setAlpha(255);
    }

    private void drawFluidImage(Canvas canvas, int w, int h) {
        float density = getResources().getDisplayMetrics().density;
        float zoom = (float) ((Math.sin(phase4) * 0.3f + 2.1f) * (fluidLevel * 0.14f + 1.0f));
        float tx = 72f * density * (float) Math.sin(phase2);
        float ty = 52f * density * (float) Math.cos(phase3);
        float rot = (float) Math.sin(phase1) * 8f;
        canvas.save();
        canvas.translate(w / 2f + tx, h / 2f + ty);
        canvas.rotate(rot);
        canvas.scale(zoom, zoom);
        float bw = w;
        float bh = h;
        canvas.drawBitmap(coverBitmap, null, new android.graphics.RectF(-bw / 2f, -bh / 2f, bw / 2f, bh / 2f), paint);
        canvas.restore();
        drawBlob(canvas, w, h,
                0.5f + (float) (Math.sin(phase3) * 0.12f + Math.sin(phase2) * 0.34f),
                0.5f + (float) (Math.cos(phase1) * 0.28f),
                shiftLightness(baseColor, 0.16f));
        drawBlob(canvas, w, h,
                0.5f + (float) (Math.cos(phase2) * -0.32f),
                0.5f + (float) (Math.cos(phase1) * 0.1f + Math.sin(phase3) * 0.3f),
                shiftLightness(baseColor, 0.06f));
    }

    private void drawBlob(Canvas canvas, int w, int h, float cxF, float cyF, int color) {
        float cx = w * cxF;
        float cy = h * cyF;
        float radius = Math.max(w, h) * 0.75f;
        int c = Color.argb(Math.round(255f * 0.5f), Color.red(color), Color.green(color), Color.blue(color));
        RadialGradient gradient = new RadialGradient(cx, cy, radius,
                c, Color.TRANSPARENT, Shader.TileMode.CLAMP);
        overlayPaint.setShader(gradient);
        canvas.drawRect(0, 0, w, h, overlayPaint);
        overlayPaint.setShader(null);
    }

    private void drawOverlay(Canvas canvas, int w, int h) {
        int top;
        int bottom;
        float[] stops;
        int[] colors;
        float boost = coverBitmap == null ? (fluidEnabled ? 0.25f : 0.20f) : 0f;
        if (fluidEnabled) {
            stops = new float[]{0f, 0.4f, 0.78f, 1f};
            colors = new int[]{
                    withAlpha(shiftLightness(baseColor, 0.05f), Math.min(1f, 0.30f + boost)),
                    withAlpha(baseColor, Math.min(1f, 0.38f + boost)),
                    withAlpha(baseColor, Math.min(1f, 0.36f + boost)),
                    withAlpha(shiftLightness(baseColor, -0.04f), Math.min(1f, 0.48f + boost))};
        } else {
            stops = new float[]{0f, 0.4f, 0.78f, 1f};
            colors = new int[]{
                    withAlpha(shiftLightness(baseColor, 0.05f), Math.min(1f, 0.76f + boost)),
                    withAlpha(baseColor, Math.min(1f, 0.82f + boost)),
                    withAlpha(baseColor, Math.min(1f, 0.80f + boost)),
                    withAlpha(shiftLightness(baseColor, -0.04f), Math.min(1f, 0.86f + boost))};
        }
        LinearGradient gradient = new LinearGradient(0, 0, 0, h, colors, stops, Shader.TileMode.CLAMP);
        overlayPaint.setShader(gradient);
        canvas.drawRect(0, 0, w, h, overlayPaint);
        overlayPaint.setShader(null);
    }

    private int withAlpha(int color, float alpha) {
        return Color.argb(Math.round(255f * alpha), Color.red(color), Color.green(color), Color.blue(color));
    }

    public static int shiftLightness(int color, float delta) {
        float[] hsl = new float[3];
        androidx.core.graphics.ColorUtils.colorToHSL(color, hsl);
        hsl[2] = Math.max(0f, Math.min(1f, hsl[2] + delta));
        return androidx.core.graphics.ColorUtils.HSLToColor(hsl);
    }
}
