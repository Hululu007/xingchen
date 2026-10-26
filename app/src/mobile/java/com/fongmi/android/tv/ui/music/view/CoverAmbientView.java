package com.fongmi.android.tv.ui.music.view;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.utils.CoverColor;
import com.fongmi.android.tv.utils.ImgUtil;
import com.bumptech.glide.request.transition.Transition;

/**
 * 播放页外壳氛围层（瓜子 BlurredCoverAmbient 口径）：封面取色打底（切歌 450ms/bs4.a 渐变，
 * 回退 #FF1C1C1E；取色＝瓜子 mk1 链：128×128 源图经 e 选色＋b 变换，见 CoverColor.shellBaseColor）
 * ＋糊封面层（取图 64×64 居中裁切、铺满后图形模糊 24dp，alpha 基数 0.8，
 * 渐变罩明度自上 +0.05 线性到下 −0.04）。模糊走 RenderNode＋RenderEffect（API 31+），
 * 低版本回落为未加模糊步的铺图。流体背景开时由 Activity 换 FluidCoverView 叠加，本类只管糊封面态。
 */
public class CoverAmbientView extends View {

    private static final int FALLBACK = 0xFF1C1C1E;
    private static final PathInterpolator BS4_A = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final java.util.concurrent.Executor COLOR_EXEC =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private Bitmap mBlurBitmap;
    private String mBoundUrl;
    private int mBaseColor = FALLBACK;
    private int mFromColor = FALLBACK;
    private int mToColor = FALLBACK;
    private ValueAnimator mColorAnim;
    private Paint mFluidShadePaint;
    private int mShadeW, mShadeH;
    private int mShadeBase;
    private RenderNode mBlurNode;
    private int mBlurNodeW, mBlurNodeH;
    private Bitmap mBlurNodeBitmap;

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
                    mBlurBitmap = centerCropSquare64(resource);
                } catch (Throwable ignored) {
                    mBlurBitmap = null;
                }
                mBlurNodeBitmap = null;
                try {
                    // 外壳底色走瓜子 mk1 链（CoverColor.shellBaseColor：e 选色＋b 变换），取色源图 128×128（瓜子加载器同尺寸）
                    Bitmap src128 = resource.getWidth() == 128 && resource.getHeight() == 128
                            ? resource : Bitmap.createScaledBitmap(resource, 128, 128, true);
                    COLOR_EXEC.execute(() -> {
                        int target = CoverColor.shellBaseColor(src128);
                        post(() -> {
                            if (want.equals(mBoundUrl)) animateBaseTo(target);
                        });
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

    /** 64×64 居中裁切（瓜子 Coil size(64,64)＋Fill 的等价位图）：等比放大到盖住 64×64 再裁中心。 */
    private static Bitmap centerCropSquare64(Bitmap src) {
        int sw = src.getWidth(), sh = src.getHeight();
        if (sw <= 0 || sh <= 0) return null;
        float scale = Math.max(64f / sw, 64f / sh);
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate((64f - sw * scale) / 2f, (64f - sh * scale) / 2f);
        Bitmap out = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(src, m, p);
        return out;
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
            if (Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated()) {
                drawBlurredViaNode(canvas, w, h);
            } else {
                mPaint.setAlpha(Math.round(255 * 0.8f));
                android.graphics.RectF dst = new android.graphics.RectF(0, 0, w, h);
                canvas.save();
                canvas.translate(mDriftTx, mDriftTy);
                canvas.scale(mDriftScale, mDriftScale, w / 2f, h / 2f);
                canvas.rotate(mDriftRot, w / 2f, h / 2f);
                canvas.drawBitmap(mBlurBitmap, null, dst, mPaint);
                canvas.restore();
                mPaint.setAlpha(255);
            }
        }
        if (mFluidShadePaint == null || mShadeW != w || mShadeH != h || mShadeBase != mBaseColor) {
            mShadeW = w;
            mShadeH = h;
            mShadeBase = mBaseColor;
            // 流体渐变罩四档（dig 流体节，硬件加速 f＝0）：0→明度+0.05 色 α.30；0.4→基色 α.38；
            // 0.78→基色 α.36；1→明度−0.04 色 α.48。此前用的是静态糊图版的两道薄罩，流体版不用。
            int[] cols = new int[]{
                    withAlpha(blend(mBaseColor, 0xFFFFFFFF, 0.05f), 0.30f),
                    withAlpha(mBaseColor, 0.38f),
                    withAlpha(mBaseColor, 0.36f),
                    withAlpha(blend(mBaseColor, 0xFF000000, 0.04f), 0.48f)};
            mFluidShadePaint = new Paint();
            mFluidShadePaint.setShader(new LinearGradient(0, 0, 0, h,
                    cols, new float[]{0f, 0.4f, 0.78f, 1f}, Shader.TileMode.CLAMP));
        }
        canvas.drawRect(0, 0, w, h, mFluidShadePaint);
    }

    private static int blend(int color, int target, float ratio) {
        int r = Math.round(android.graphics.Color.red(color) + (android.graphics.Color.red(target) - android.graphics.Color.red(color)) * ratio);
        int g = Math.round(android.graphics.Color.green(color) + (android.graphics.Color.green(target) - android.graphics.Color.green(color)) * ratio);
        int b = Math.round(android.graphics.Color.blue(color) + (android.graphics.Color.blue(target) - android.graphics.Color.blue(color)) * ratio);
        return android.graphics.Color.rgb(r, g, b);
    }

    private static int withAlpha(int color, float alpha) {
        return android.graphics.Color.argb(Math.round(alpha * 255),
                android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color));
    }

    // ===== 流体氛围（瓜子 FluidCoverAmbient 真值，gz-smali-missing-dig 流体节，昨晚已抠在案）=====
    // 四相位帧驱动：速率＝速度系数（标准 1.0）×（节拍量×0.3＋流体量×0.65＋1.0），星辰无节拍/流体量
    // 输入（＝瓜子的音乐律动关）→倍率 1.0；相位增量＝速率×2π/周期，周期 19/27/34/13。
    // 变换：缩放＝sin(φ4)×0.3＋2.1；平移X＝72dp·sin(φ2)；平移Y＝52dp·cos(φ3)；旋转＝sin(φ1)×8°。
    // 播放推进、暂停冻结；只动糊层，不动上面的清晰封面。
    // （订正：今早一版曾写自编近似值并误称公式未抠到——公式一直在案，是落码时没翻案。）
    private boolean mAmbientPlaying;
    private boolean mDriftRunning;
    private double mPh1, mPh2, mPh3, mPh4;
    private long mDriftLastNs;
    private float mDriftScale = 2.1f;
    private float mDriftTx;
    private float mDriftTy;
    private float mDriftRot;
    private final android.view.Choreographer.FrameCallback mDriftCallback = new android.view.Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!mAmbientPlaying || !isAttachedToWindow()) {
                mDriftRunning = false;
                mDriftLastNs = 0;
                return;
            }
            double dt = 0;
            if (mDriftLastNs != 0) dt = Math.min(0.1, (frameTimeNanos - mDriftLastNs) / 1e9);
            mDriftLastNs = frameTimeNanos;
            mPh1 += dt * 2 * Math.PI / 19.0;
            mPh2 += dt * 2 * Math.PI / 27.0;
            mPh3 += dt * 2 * Math.PI / 34.0;
            mPh4 += dt * 2 * Math.PI / 13.0;
            float density = getResources().getDisplayMetrics().density;
            mDriftScale = (float) (2.1 + 0.3 * Math.sin(mPh4));
            mDriftTx = 72f * density * (float) Math.sin(mPh2);
            mDriftTy = 52f * density * (float) Math.cos(mPh3);
            mDriftRot = 8f * (float) Math.sin(mPh1);
            if (mBlurNode != null) {
                int w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
                mBlurNode.setPivotX(w / 2f);
                mBlurNode.setPivotY(h / 2f);
                mBlurNode.setScaleX(mDriftScale);
                mBlurNode.setScaleY(mDriftScale);
                mBlurNode.setTranslationX(mDriftTx);
                mBlurNode.setTranslationY(mDriftTy);
                mBlurNode.setRotationZ(mDriftRot); // RenderNode 只有 setRotationZ（无 setRotation，首编实证）
            }
            invalidate();
            android.view.Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public void setAmbientPlaying(boolean playing) {
        if (mAmbientPlaying == playing) return;
        mAmbientPlaying = playing;
        if (playing && !mDriftRunning && isAttachedToWindow()) {
            mDriftRunning = true;
            mDriftLastNs = 0;
            android.view.Choreographer.getInstance().postFrameCallback(mDriftCallback);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mAmbientPlaying && !mDriftRunning) {
            mDriftRunning = true;
            mDriftLastNs = 0;
            android.view.Choreographer.getInstance().postFrameCallback(mDriftCallback);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        android.view.Choreographer.getInstance().removeFrameCallback(mDriftCallback);
        mDriftRunning = false;
        super.onDetachedFromWindow();
    }

    /** 糊封面层：小图铺满录入节点，整层挂 24dp 图形模糊（RenderEffect），alpha 基数 0.8 走节点。 */
    private void drawBlurredViaNode(Canvas canvas, int w, int h) {
        if (mBlurNode == null) mBlurNode = new RenderNode("coverAmbientBlur");
        // 切后台回来录制内容被系统丢弃（hasDisplayList=false）：位图/尺寸都没变、旧守卫不重录，
        // 糊层从此画空——他真机「切个后台再进来背景变没」的定因（2026-10-26）。丢了就重录。
        if (mBlurNodeBitmap != mBlurBitmap || mBlurNodeW != w || mBlurNodeH != h
                || !mBlurNode.hasDisplayList()) {
            mBlurNodeBitmap = mBlurBitmap;
            mBlurNodeW = w;
            mBlurNodeH = h;
            mBlurNode.setPosition(0, 0, w, h);
            Canvas rec = mBlurNode.beginRecording(w, h);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            rec.drawBitmap(mBlurBitmap, null, new android.graphics.RectF(0, 0, w, h), p);
            mBlurNode.endRecording();
            float r = 18f * getResources().getDisplayMetrics().density; // 流体版模糊修饰 18.0（dig 流体节）
            mBlurNode.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP));
            mBlurNode.setAlpha(0.8f);
        }
        // 重录会把节点变换清零：每帧按当前流体相位重贴，保证暂停冻结/切歌后构图不跳
        mBlurNode.setPivotX(w / 2f);
        mBlurNode.setPivotY(h / 2f);
        mBlurNode.setScaleX(mDriftScale);
        mBlurNode.setScaleY(mDriftScale);
        mBlurNode.setTranslationX(mDriftTx);
        mBlurNode.setTranslationY(mDriftTy);
        mBlurNode.setRotationZ(mDriftRot);
        canvas.drawRenderNode(mBlurNode);
    }
}
