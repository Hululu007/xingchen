package com.fongmi.android.tv.ui.music.view;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.palette.graphics.Palette;

import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.utils.ImgUtil;
import com.bumptech.glide.request.transition.Transition;

/**
 * 播放页外壳氛围层（瓜子外壳口径）：封面 dominant 色打底（切歌 450ms/bs4.a 渐变，回退 #FF1C1C1E）
 * ＋糊封面层（取图缩到 64 宽、放大回铺自带图形糊度，渐变罩上 +0.05／下 −0.04 提/压明度，alpha 基数 0.8）。
 * 流体背景开时由 Activity 换 FluidCoverView 叠加，本类只管糊封面态。
 */
public class CoverAmbientView extends View {

    private static final int FALLBACK = 0xFF1C1C1E;
    private static final PathInterpolator BS4_A = new PathInterpolator(0.2f, 0f, 0f, 1f);

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private Bitmap mBlurBitmap;
    private String mBoundUrl;
    private int mBaseColor = FALLBACK;
    private int mFromColor = FALLBACK;
    private int mToColor = FALLBACK;
    private ValueAnimator mColorAnim;
    private Paint mShadePaint;
    private int mShadeW, mShadeH;

    public CoverAmbientView(Context context) {
        super(context);
    }

    public CoverAmbientView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** 切歌换封面：取小图作糊底，同时算 dominant 色并 450ms 渐变过去。url 空＝回落纯色。 */
    public void setCover(String url) {
        if (url == null) url = "";
        if (url.equals(mBoundUrl)) return;
        mBoundUrl = url;
        if (url.isEmpty()) {
            mBlurBitmap = null;
            animateBaseTo(FALLBACK);
            invalidate();
            return;
        }
        final String want = url;
        ImgUtil.load(url, 160, new CustomTarget<Bitmap>() {
            @Override
            public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                if (!want.equals(mBoundUrl)) return;
                try {
                    int w = 64;
                    int h = Math.max(1, (int) (resource.getHeight() * (64f / Math.max(1, resource.getWidth()))));
                    mBlurBitmap = Bitmap.createScaledBitmap(resource, w, h, true);
                } catch (Throwable ignored) {
                    mBlurBitmap = null;
                }
                try {
                    Palette.from(resource).generate(p -> {
                        if (p == null || !want.equals(mBoundUrl)) return;
                        int c = p.getDominantColor(FALLBACK);
                        animateBaseTo(c);
                    });
                } catch (Throwable ignored) {
                }
                invalidate();
            }

            @Override
            public void onLoadFailed(@Nullable android.graphics.drawable.Drawable errorDrawable) {
                if (want.equals(mBoundUrl)) {
                    mBlurBitmap = null;
                    animateBaseTo(FALLBACK);
                    invalidate();
                }
            }

            @Override
            public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {
            }
        });
    }

    public int getBaseColor() {
        return mBaseColor;
    }

    private void animateBaseTo(int target) {
        if (mColorAnim != null) mColorAnim.cancel();
        mFromColor = mBaseColor;
        mToColor = target;
        mColorAnim = ValueAnimator.ofObject(new ArgbEvaluator(), mFromColor, mToColor);
        mColorAnim.setDuration(450);
        mColorAnim.setInterpolator(BS4_A);
        mColorAnim.addUpdateListener(a -> {
            mBaseColor = (int) a.getAnimatedValue();
            invalidate();
        });
        mColorAnim.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        mPaint.setShader(null);
        mPaint.setColor(mBaseColor);
        mPaint.setAlpha(255);
        canvas.drawRect(0, 0, w, h, mPaint);
        if (mBlurBitmap != null && !mBlurBitmap.isRecycled()) {
            mPaint.setAlpha(Math.round(255 * 0.8f));
            android.graphics.RectF dst = new android.graphics.RectF(0, 0, w, h);
            canvas.drawBitmap(mBlurBitmap, null, dst, mPaint);
            mPaint.setAlpha(255);
        }
        if (mShadePaint == null || mShadeW != w || mShadeH != h) {
            mShadeW = w;
            mShadeH = h;
            mShadePaint = new Paint();
            // 渐变罩：顶部 +0.05 提亮、底部 −0.04 压暗（以黑/白低 alpha 近似明度偏移）
            mShadePaint.setShader(new LinearGradient(0, 0, 0, h,
                    new int[]{0x0DFFFFFF, 0x00000000, 0x0A000000},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        }
        canvas.drawRect(0, 0, w, h, mShadePaint);
    }
}
