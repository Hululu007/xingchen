package com.fongmi.android.tv.ui.music;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.core.graphics.ColorUtils;

import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.utils.CoverColor;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.ResUtil;

/**
 * 封面一体背景（重做第一步，规格 gz-full-parity-1025 §14.1＋§15.5）：
 * 瓜子 BlurredCoverAmbient——垫底满幅填基准色 j 本身；糊图＝封面 64×64 请求、
 * 满幅 Crop、blur 24dp、图层 alpha 0.4（无任何图层缩放）；四停点渐变罩
 * 0/0.4/0.78/1、色 [s(+0.05,j), j, j, s(−0.04,j)]（s＝V 移位钳 [0.1,0.68]）、
 * alpha 0.76/0.82/0.80/0.86；换图 crossfade 450ms。
 * 能力门 p（§15.5）：SDK≥31 且 RenderEffect.createBlurEffect 预检通过才画糊图；
 * 为假时不画图、罩 alpha 统一 +0.2。基准色归一走 CoverColor.toneAdjust（＝瓜子 d()）。
 * 本组件只负责背景层本身，不碰封面本体（封面无下沿渐隐，「一体」靠同源）。
 */
public class CoverAmbientView extends FrameLayout {

    private static final float IMAGE_ALPHA = 0.4f;
    private static final long CROSSFADE_MS = 450L;
    private static final float BLUR_DP = 24f;
    private static final float[] VEIL_STOPS = {0f, 0.4f, 0.78f, 1f};
    private static final float[] VEIL_ALPHAS = {0.76f, 0.82f, 0.80f, 0.86f};
    private static final float VEIL_BOOST_NO_BLUR = 0.2f;

    /** 能力门预检结论缓存（§15.5 的 m0 同位：进程内只检一次）。 */
    private static Boolean sBlurCapable;

    private final ImageView imageA;
    private final ImageView imageB;
    private final VeilView veil;
    private ImageView frontImage;
    private ImageView backImage;

    private String targetUrl;
    private int baseColor;
    private ValueAnimator crossfade;
    private CustomTarget<Bitmap> pendingTarget;

    public CoverAmbientView(Context context) {
        this(context, null);
    }

    public CoverAmbientView(Context context, AttributeSet attrs) {
        super(context, attrs);
        baseColor = CoverColor.toneAdjust(CoverColor.FALLBACK_COLOR);
        setBackgroundColor(baseColor);
        imageA = buildImageLayer();
        imageB = buildImageLayer();
        veil = new VeilView(context);
        addView(imageA);
        addView(imageB);
        addView(veil);
        frontImage = imageB;
        backImage = imageA;
        frontImage.setAlpha(0f);
        backImage.setAlpha(0f);
        veil.setBaseColor(baseColor, blurCapable());
    }

    private ImageView buildImageLayer() {
        ImageView v = new ImageView(getContext());
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        if (blurCapable()) {
            try {
                float r = ResUtil.dp2px((int) BLUR_DP);
                v.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP));
            } catch (Throwable ignored) {
                // 预检已过仍失败：本层退化为未糊图（64×64 放大本身已软），不阻断垫底与罩
            }
        }
        return v;
    }

    /** 能力门 p：SDK≥31 才真检 createBlurEffect(1,1,CLAMP) 不抛错（§15.5 本体）。 */
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

    /** 当前氛围基准色（已归一）。 */
    public int getBaseColor() {
        return baseColor;
    }

    /**
     * 换封面：新地址才动。取图 64×64（瓜子 hk3.d(64,64) 口径，ImgUtil 尺寸参为 dp，
     * 视觉等效）；图到后取色归一，图层与垫底色同窗 450ms crossfade。
     * 地址为空/取图失败：回落垫底（FALLBACK 归一色），不画糊图。
     */
    public void setCover(String url) {
        if (url != null && url.equals(targetUrl)) return;
        targetUrl = url;
        cancelPending();
        if (url == null || url.isEmpty()) {
            applyAtmosphere(null, CoverColor.toneAdjust(CoverColor.FALLBACK_COLOR));
            return;
        }
        final String wanted = url;
        pendingTarget = new CustomTarget<Bitmap>() {
            @Override
            public void onResourceReady(Bitmap resource, Transition<? super Bitmap> transition) {
                if (!wanted.equals(targetUrl)) return;
                int color = CoverColor.toneAdjust(CoverColor.dominantColor(resource));
                applyAtmosphere(resource, color);
            }

            @Override
            public void onLoadFailed(android.graphics.drawable.Drawable errorDrawable) {
                if (!wanted.equals(targetUrl)) return;
                applyAtmosphere(null, CoverColor.toneAdjust(CoverColor.FALLBACK_COLOR));
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

    private void applyAtmosphere(Bitmap bitmap, int newBase) {
        // 先收掉在跑的 crossfade（其结束回调按当时的层位清理），再换层，避免清掉新图
        if (crossfade != null) {
            crossfade.cancel();
            crossfade = null;
        }
        final int fromBase = baseColor;
        baseColor = newBase;
        // 新图先进后台层，crossfade 期间前台层渐隐、后台层渐显到 0.4（无糊时恒 0）
        backImage.setImageBitmap(bitmap);
        final ImageView in = backImage;
        final ImageView out = frontImage;
        frontImage = in;
        backImage = out;
        final float inTarget = blurCapable() && bitmap != null ? IMAGE_ALPHA : 0f;
        final float inStart = in.getAlpha();
        final float outStart = out.getAlpha();
        crossfade = ValueAnimator.ofFloat(0f, 1f);
        crossfade.setDuration(CROSSFADE_MS);
        crossfade.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            setBackgroundColor(ColorUtils.blendARGB(fromBase, newBase, t));
            veil.setBaseColor(ColorUtils.blendARGB(fromBase, newBase, t), blurCapable());
            in.setAlpha(inStart + (inTarget - inStart) * t);
            out.setAlpha(outStart * (1f - t));
        });
        crossfade.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                in.setAlpha(inTarget);
                out.setAlpha(0f);
                out.setImageBitmap(null);
                setBackgroundColor(newBase);
                veil.setBaseColor(newBase, blurCapable());
            }
        });
        crossfade.start();
    }

    /** V 移位（§14.1 的 s()）：HSV 的 V 加 delta 后钳 [0.1,0.68]，H/S 不动。 */
    static int shiftValue(int color, float delta) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.max(0.1f, Math.min(0.68f, hsv[2] + delta));
        return Color.HSVToColor(hsv);
    }

    /** 渐变罩层：竖向四停点，颜色随基准色走、alpha 按能力门分两套。 */
    private static class VeilView extends View {

        private final Paint paint = new Paint();
        private int base = Color.BLACK;
        private boolean blurOk = true;

        VeilView(Context context) {
            super(context);
            setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }

        void setBaseColor(int color, boolean blurCapable) {
            base = color;
            blurOk = blurCapable;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int h = getHeight();
            if (h <= 0) return;
            int[] raw = {shiftValue(base, 0.05f), base, base, shiftValue(base, -0.04f)};
            int[] colors = new int[4];
            for (int i = 0; i < 4; i++) {
                float a = VEIL_ALPHAS[i] + (blurOk ? 0f : VEIL_BOOST_NO_BLUR);
                colors[i] = ColorUtils.setAlphaComponent(raw[i], Math.round(Math.min(1f, a) * 255f));
            }
            paint.setShader(new LinearGradient(0f, 0f, 0f, h, colors, VEIL_STOPS, Shader.TileMode.CLAMP));
            canvas.drawRect(0f, 0f, getWidth(), h, paint);
            paint.setShader(null);
        }
    }
}
