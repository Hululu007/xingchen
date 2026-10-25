package com.fongmi.android.tv.ui.music;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;

import com.fongmi.android.tv.utils.CoverColor;

/**
 * 封面一体背景（糊版）——重做批二从零重写，逐值照瓜子 CoverAmbientKt（对照表 §14／§15.5）。
 * 「一体」靠同源：垫底＝氛围色归一后的基准色本身、糊图＝封面本身、同色渐变罩——
 * 不靠擦封面本体（主题里的 tpCover 不许挂任何下沿渐隐）。
 *
 * 绘制自下而上：
 * 1. 垫底：满幅填 toneAdjust(ambientColor) 本身（CoverColor.toneAdjust 在 utils 包，直接调；
 *    注意 cdToneColor 是 CD 链的取色、别混）。
 * 2. 糊图层：封面缩 64×64、满幅 Crop 直绘（无任何缩放值），图层 alpha 0.4；
 *    API≥31 用 RenderEffect 24dp 只作用于本图层（经 RenderNode 隔离，垫底与罩不糊）；
 *    API&lt;31 不画糊图、渐变罩 alpha 统一 +0.2 补偿（§15.5 能力门口径的星辰落点，
 *    旧 FastBlur 近似已摘除）。
 * 3. 渐变罩：竖向四停点 0／0.4／0.78／1，色＝[V+0.05, 基准, 基准, V−0.04]
 *    （V 移位后钳 [0.1,0.68]），alpha 0.76／0.82／0.80／0.86（depth 0.8 口径）。
 * 换图／换色过渡 450ms crossfade（animateAmbient 口径）：旧图层淡出、新图层淡入、基准色补间。
 * 流体版不在本组件：走骨架 fluidAmbient（FluidCoverAmbientView），本组件只管糊版。
 * CD／唱片架主题不用本组件（自有背景），宿主 applySkeletonForTheme 时整层隐藏。
 */
public class CoverAmbientView extends View {

    /** 糊图图层 alpha（瓜子入参默认 0.4）。 */
    private static final float IMAGE_ALPHA = 0.4f;
    /** 取色／换图过渡时长（crossfade 450ms）。 */
    private static final long CROSSFADE_MS = 450L;
    /** 模糊半径 24dp（只作用糊图层）。 */
    private static final float BLUR_DP = 24f;
    /** API&lt;31 能力门回落：不画糊图，罩 alpha 统一补偿量（糊版 +0.2）。 */
    private static final float VEIL_BOOST_LEGACY = 0.2f;
    /** 渐变罩停点。 */
    private static final float[] VEIL_STOPS = {0f, 0.4f, 0.78f, 1f};
    /** 渐变罩各停点 alpha（depth 0.8 口径）。 */
    private static final float[] VEIL_ALPHAS = {0.76f, 0.82f, 0.80f, 0.86f};

    private final Paint fillPaint = new Paint();
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint veilPaint = new Paint();

    /** 当前基准色（已 toneAdjust）。 */
    private int baseColor = CoverColor.FALLBACK_COLOR;
    /** 上一份基准色（crossfade 起点）。 */
    private int prevBaseColor = baseColor;
    /** 当前糊图素材（64×64），可空。 */
    private Bitmap bitmap;
    /** 上一份糊图素材（crossfade 淡出层），过渡结束即弃。 */
    private Bitmap prevBitmap;
    /** crossfade 进度 0–1（1＝已收口在当前层）。 */
    private float fadeFraction = 1f;
    private ValueAnimator fadeAnimator;
    /** 糊图层隔离模糊节点（API≥31 才建）。 */
    private RenderNode blurNode;
    private boolean blurNodeDirty = true;

    public CoverAmbientView(Context context) {
        super(context);
    }

    public CoverAmbientView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** 当前基准色（已归一），供宿主给歌词页／流体层同步色调。 */
    public int getBaseColor() {
        return baseColor;
    }

    /**
     * 切歌时喂入：coverBitmap 为封面原图（内部缩 64×64 作糊图素材），
     * ambientColor 为封面取色（内部 toneAdjust 归一作基准色）。450ms crossfade 到新层。
     */
    public void setAmbient(Bitmap coverBitmap, int ambientColor) {
        int newBase = CoverColor.toneAdjust(ambientColor);
        Bitmap newBitmap = coverBitmap == null || coverBitmap.isRecycled() ? null : scaleTo64(coverBitmap);
        if (fadeAnimator != null) fadeAnimator.cancel();
        prevBitmap = bitmap;
        prevBaseColor = currentDisplayBase();
        bitmap = newBitmap;
        baseColor = newBase;
        blurNodeDirty = true;
        fadeFraction = 0f;
        fadeAnimator = ValueAnimator.ofFloat(0f, 1f);
        fadeAnimator.setDuration(CROSSFADE_MS);
        fadeAnimator.addUpdateListener(animation -> {
            fadeFraction = (float) animation.getAnimatedValue();
            blurNodeDirty = true;
            invalidate();
        });
        fadeAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                fadeFraction = 1f;
                prevBitmap = null;
                blurNodeDirty = true;
                invalidate();
            }
        });
        fadeAnimator.start();
        invalidate();
    }

    private int currentDisplayBase() {
        if (fadeFraction >= 1f) return baseColor;
        return (int) new ArgbEvaluator().evaluate(fadeFraction, prevBaseColor, baseColor);
    }

    private static Bitmap scaleTo64(Bitmap src) {
        try {
            if (src.getWidth() == 64 && src.getHeight() == 64) return src;
            return Bitmap.createScaledBitmap(src, 64, 64, true);
        } catch (Throwable ignored) {
            return src;
        }
    }

    /** V 移位：HSV 明度 ±shift 后钳 [0.1, 0.68]（瓜子 s() 口径）。 */
    private static int shiftValue(int color, float shift) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.max(0.1f, Math.min(0.68f, hsv[2] + shift));
        return Color.HSVToColor(hsv);
    }

    private static int withAlpha(int color, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        int displayBase = currentDisplayBase();
        // 1. 垫底：满幅填基准色本身
        fillPaint.setColor(displayBase);
        canvas.drawRect(0, 0, w, h, fillPaint);
        // 2. 糊图层（API<31 不画，靠罩 +0.2 补偿）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            drawImageLayer(canvas, w, h);
        }
        // 3. 渐变罩
        drawVeil(canvas, w, h, displayBase);
    }

    private void drawImageLayer(Canvas canvas, int w, int h) {
        if (bitmap == null && prevBitmap == null) return;
        if (blurNode == null) {
            blurNode = new RenderNode("coverAmbientBlur");
            blurNodeDirty = true;
        }
        if (blurNodeDirty || fadeFraction < 1f) {
            blurNode.setPosition(0, 0, w, h);
            Canvas rc = blurNode.beginRecording(w, h);
            if (prevBitmap != null && fadeFraction < 1f) {
                imagePaint.setAlpha(Math.round(255f * IMAGE_ALPHA * (1f - fadeFraction)));
                drawCrop(rc, prevBitmap, w, h);
            }
            if (bitmap != null) {
                imagePaint.setAlpha(Math.round(255f * IMAGE_ALPHA * fadeFraction));
                drawCrop(rc, bitmap, w, h);
            }
            blurNode.endRecording();
            float blurPx = BLUR_DP * getResources().getDisplayMetrics().density;
            blurNode.setRenderEffect(RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP));
            blurNodeDirty = false;
        }
        canvas.drawRenderNode(blurNode);
    }

    /** 满幅 Crop 直绘：等比放大到铺满、居中裁切，无任何额外缩放值。 */
    private void drawCrop(Canvas canvas, Bitmap bmp, int w, int h) {
        if (bmp == null || bmp.isRecycled()) return;
        float scale = Math.max((float) w / bmp.getWidth(), (float) h / bmp.getHeight());
        float dw = bmp.getWidth() * scale;
        float dh = bmp.getHeight() * scale;
        float left = (w - dw) / 2f;
        float top = (h - dh) / 2f;
        canvas.save();
        canvas.translate(left, top);
        canvas.scale(scale, scale);
        canvas.drawBitmap(bmp, 0, 0, imagePaint);
        canvas.restore();
    }

    private void drawVeil(Canvas canvas, int w, int h, int displayBase) {
        float boost = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? 0f : VEIL_BOOST_LEGACY;
        int[] colors = new int[4];
        colors[0] = withAlpha(shiftValue(displayBase, 0.05f), VEIL_ALPHAS[0] + boost);
        colors[1] = withAlpha(displayBase, VEIL_ALPHAS[1] + boost);
        colors[2] = withAlpha(displayBase, VEIL_ALPHAS[2] + boost);
        colors[3] = withAlpha(shiftValue(displayBase, -0.04f), VEIL_ALPHAS[3] + boost);
        veilPaint.setShader(new LinearGradient(0, 0, 0, h, colors, VEIL_STOPS, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, veilPaint);
        veilPaint.setShader(null);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (fadeAnimator != null) {
            fadeAnimator.cancel();
            fadeAnimator = null;
        }
        super.onDetachedFromWindow();
    }

    /** 宿主销毁收口：停掉换图过渡，迟到帧不再碰本层。 */
    public void release() {
        if (fadeAnimator != null) {
            fadeAnimator.cancel();
            fadeAnimator = null;
        }
        fadeFraction = 1f;
        prevBitmap = null;
    }
}
