package com.fongmi.android.tv.ui.music.view;

import android.animation.ArgbEvaluator;
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
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.PathInterpolator;

import androidx.annotation.Nullable;
import com.fongmi.android.tv.ui.music.MusicPlayerSettings;
import com.fongmi.android.tv.utils.CoverColor;

/**
 * 播放页外壳氛围层（瓜子 CoverAmbientKt 口径，2026-10-26 逐项亲验）：
 * 底层＝封面取色（wk1.a＝CoverColor.shellBaseColor，mk1 链 e 选色＋b 变换），
 * 切歌 450ms/bs4.a 动画过渡，回退 #FF1C1C1E。氛围层按「动态流体背景」开关二选一：
 * · 流体开（瓜子默认开）：FluidCoverAmbient——64×64 糊封面（模糊 18dp、alpha 1.0、
 *   四相位漂移变换）＋三团径向色洗（团色取 m 色＝d(o()) 与基色 s() 明度移位）
 *   ＋竖向渐变罩四档（停点 0/.4/.78/1，alpha 基数 .30/.38/.36/.48，
 *   模糊不可用时基数 +0.25 且糊层不画）。
 * · 流体关：BlurredCoverAmbient——64×64 糊封面（模糊 24dp、alpha 0.4、静止）
 *   ＋渐变罩四档（alpha .76/.82/.80/.86，模糊不可用时 +0.2 且糊层不画）。
 * s(Δ)＝HSL 明度移位（钳 [0.1,0.68]），与瓜子 CoverAmbientKt.s 同口径。
 * 流体量（节拍）已接通（2026-10-26 体检高危「假开关」修）：相位推进倍率＝
 * 流速系数×（节拍脉冲×0.3×节拍响应倍率＋1.0），节拍帧来自 MusicBeatState
 * （服务侧 MusicBeatAnalyzer 发布）；「音乐律动」关时倍率回 1.0、分析门同步关。
 * 模糊走 RenderNode＋RenderEffect（API 31+），与瓜子 BlurSupport 预检同门槛。
 * 底图来源（2026-10-27 改根）：本层不再独立取图——宿主把封面位图在到位回调里
 * 同点交入 setCoverBitmap，糊底与取色当场从同一张图派生（同源同裁切同点换帧）。
 */
public class CoverAmbientView extends View {

    private static final int FALLBACK = 0xFF1C1C1E;
    private static final PathInterpolator BS4_A = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final java.util.concurrent.Executor COLOR_EXEC =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint mBlobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap mBlurBitmap;
    private String mBoundKey = "";
    private Bitmap mDerivedFrom;
    private int mBaseColor = FALLBACK;
    private int mFromColor = FALLBACK;
    private int mToColor = FALLBACK;
    private ValueAnimator mColorAnim;
    private int mToneColor = FALLBACK;
    private boolean mHasTone;
    private Paint mShadePaint;
    private int mShadeW, mShadeH, mShadeBase;
    private boolean mShadeFluid, mShadeBlurOk;
    private RenderNode mBlurNode;
    private int mBlurNodeW, mBlurNodeH;
    private Bitmap mBlurNodeBitmap;
    private boolean mNodeFluid = true;

    // ===== 模式与流速（设置驱动，refreshSettings 读入）=====
    private boolean mFluidMode = true;
    private float mSpeedFactor = 1.0f;

    public CoverAmbientView(Context context) {
        super(context);
    }

    public CoverAmbientView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** 设置变化后由宿主调：重读流体开关与流速（瓜子工具面板「外观设置」同两项）。 */
    public void refreshSettings() {
        boolean fluid = MusicPlayerSettings.isFluidBg();
        mSpeedFactor = MusicPlayerSettings.fluidSpeedFactor();
        // 律动门同步（假开关接通的 UI 侧落点）：开关一变分析门即变，当前曲立即生效
        com.fongmi.android.tv.service.MusicBeatState.requestEnabled =
                fluid && MusicPlayerSettings.isFluidBeat();
        if (fluid != mFluidMode) {
            mFluidMode = fluid;
            mBlurNodeBitmap = null; // 糊层节点按新模式的模糊半径/alpha 重录
            if (fluid) startDriftIfNeeded();
            invalidate();
        }
    }

    /**
     * 封面本体派生（2026-10-27 改根，他令「封面背景一体盯死」）：氛围不再自带取图——
     * 宿主把锐封面那张位图在到位回调里同点交进来，糊底与取色全部从它当场派生：
     * 糊底＝同图居中裁方缩到 64×64（瓜子 64×64 取图同口径，裁的是与封面 centerCrop
     * 同一块中心方），取色源图＝同图缩 128×128 走 mk1 链（基色 wk1.a）与 o()+d() 链
     * （团色），基色 450ms 渐变过去。与封面同源、同裁切、同点换帧，不再有第二条
     * 加载抢先或迟到把两层撕开。cover 为 null＝本曲无封面或封面取图失败：清糊底、
     * 回落深底 FALLBACK（无封面口径不变）。key＝封面地址，只作同一性守卫：同 key
     * 且同一位图实例重入不重派生；同 key 换新实例（失败后重试成功）照常派生。
     */
    public void setCoverBitmap(@Nullable Bitmap cover, @Nullable String key) {
        String k = key == null ? "" : key;
        if (cover == null || cover.isRecycled()) {
            if (mBlurBitmap == null && mDerivedFrom == null && k.equals(mBoundKey)) return;
            mBoundKey = k;
            mDerivedFrom = null;
            mBlurBitmap = null;
            mHasTone = false;
            animateBaseTo(FALLBACK);
            invalidate();
            return;
        }
        if (k.equals(mBoundKey) && cover == mDerivedFrom) return;
        mBoundKey = k;
        mDerivedFrom = cover;
        try {
            mBlurBitmap = centerCropSquare64(cover);
        } catch (Throwable ignored) {
            mBlurBitmap = null;
        }
        mBlurNodeBitmap = null;
        try {
            // 取色源图 128×128（瓜子 c() 加载器同尺寸）：基色走 mk1 链、团色走 o()+d() 链
            Bitmap src128 = cover.getWidth() == 128 && cover.getHeight() == 128
                    ? cover : Bitmap.createScaledBitmap(cover, 128, 128, true);
            final String want = k;
            COLOR_EXEC.execute(() -> {
                int target = CoverColor.shellBaseColor(src128);
                int tone = CoverColor.toneAdjust(CoverColor.cdToneColor(src128));
                post(() -> {
                    if (!want.equals(mBoundKey)) return;
                    mToneColor = tone;
                    mHasTone = true;
                    animateBaseTo(target);
                    invalidate();
                });
            });
        } catch (Throwable ignored) {
        }
        invalidate();
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
        boolean blurOk = Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated();
        mPaint.setShader(null);
        mPaint.setColor(mBaseColor);
        mPaint.setAlpha(255);
        canvas.drawRect(0, 0, w, h, mPaint);
        if (blurOk && mBlurBitmap != null && !mBlurBitmap.isRecycled()) {
            drawBlurredViaNode(canvas, w, h);
        }
        if (mFluidMode) drawBlobs(canvas, w, h);
        if (mShadePaint == null || mShadeW != w || mShadeH != h || mShadeBase != mBaseColor
                || mShadeFluid != mFluidMode || mShadeBlurOk != blurOk) {
            mShadeW = w;
            mShadeH = h;
            mShadeBase = mBaseColor;
            mShadeFluid = mFluidMode;
            mShadeBlurOk = blurOk;
            int top = shiftLightness(mBaseColor, 0.05f);
            int bottom = shiftLightness(mBaseColor, -0.04f);
            int[] cols;
            if (mFluidMode) {
                // 流体罩四档：0→s(+.05) α(f+.30)；.4→基色 α(f+.38)；.78→基色 α(f+.36)；1→s(−.04) α(f+.48)，f＝模糊不可用 0.25、可用 0
                float f = blurOk ? 0f : 0.25f;
                cols = new int[]{
                        withAlpha(top, f + 0.30f),
                        withAlpha(mBaseColor, f + 0.38f),
                        withAlpha(mBaseColor, f + 0.36f),
                        withAlpha(bottom, f + 0.48f)};
            } else {
                // 静态罩四档：0→s(+.05) α(f+.76)；.4→基色 α(f+.82)；.78→基色 α(f+.80)；1→s(−.04) α(f+.86)，f＝模糊不可用 0.2、可用 0
                float f = blurOk ? 0f : 0.2f;
                cols = new int[]{
                        withAlpha(top, f + 0.76f),
                        withAlpha(mBaseColor, f + 0.82f),
                        withAlpha(mBaseColor, f + 0.80f),
                        withAlpha(bottom, f + 0.86f)};
            }
            mShadePaint = new Paint();
            mShadePaint.setShader(new LinearGradient(0, 0, 0, h,
                    cols, new float[]{0f, 0.4f, 0.78f, 1f}, Shader.TileMode.CLAMP));
        }
        canvas.drawRect(0, 0, w, h, mShadePaint);
    }

    // ===== 流体色团（瓜子 FluidBlob：径向渐变 0→色 α1／0.55→α.45／1→透明，中心＝画布中心、
    // 半径＝min(宽,高)/2，整层按 graphicsLayer 的 alpha/缩放/平移变换；流体量恒 0）=====
    private void drawBlobs(Canvas canvas, int w, int h) {
        int tone = mHasTone ? mToneColor : mBaseColor;
        // 团1：色＝m 色（缺→s(+.14, 基色)）；α=(sin(p1)×.28+.8)×.45；缩放＝sin(p1)×.16+1.5；
        //     平移＝(宽×(cos(p1)×.14−sin(p3)×.28), 高×cos(p1)×(−.32))
        drawBlob(canvas, w, h, mHasTone ? tone : shiftLightness(mBaseColor, 0.14f),
                clamp01((float) ((Math.sin(mPh1) * 0.28 + 0.8) * 0.45)),
                (float) (Math.sin(mPh1) * 0.16 + 1.5),
                (float) (Math.cos(mPh1) * 0.14 - Math.sin(mPh3) * 0.28),
                (float) (Math.cos(mPh1) * -0.32));
        // 团2：色＝s(+.16, 基色)；α=(sin(p4)×.3+.78)×.58；缩放＝sin(p4)×.2+1.7；
        //     平移＝(宽×(sin(p3)×.12+sin(p1)×.34), 高×cos(p2)×.28)
        drawBlob(canvas, w, h, shiftLightness(mBaseColor, 0.16f),
                clamp01((float) ((Math.sin(mPh4) * 0.3 + 0.78) * 0.58)),
                (float) (Math.sin(mPh4) * 0.2 + 1.7),
                (float) (Math.sin(mPh3) * 0.12 + Math.sin(mPh1) * 0.34),
                (float) (Math.cos(mPh2) * 0.28));
        // 团3：色＝s(+.06, m 色?:基色)；α=(cos(p4)×.3+.78)×.52；缩放＝cos(p4)×.22+1.9；
        //     平移＝(宽×cos(p2)×(−.32), 高×(cos(p1)×.1+sin(p3)×.3))
        drawBlob(canvas, w, h, shiftLightness(tone, 0.06f),
                clamp01((float) ((Math.cos(mPh4) * 0.3 + 0.78) * 0.52)),
                (float) (Math.cos(mPh4) * 0.22 + 1.9),
                (float) (Math.cos(mPh2) * -0.32),
                (float) (Math.cos(mPh1) * 0.1 + Math.sin(mPh3) * 0.3));
    }

    private void drawBlob(Canvas canvas, int w, int h, int color, float alpha, float scale, float txFrac, float tyFrac) {
        if (alpha <= 0.001f) return;
        float radius = Math.min(w, h) / 2f * scale;
        if (radius <= 0f) return;
        float cx = w / 2f + txFrac * w;
        float cy = h / 2f + tyFrac * h;
        int[] cols = new int[]{
                withAlpha(color, alpha),
                withAlpha(color, alpha * 0.45f),
                withAlpha(color, 0f)};
        mBlobPaint.setShader(new RadialGradient(cx, cy, radius, cols,
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, mBlobPaint);
        mBlobPaint.setShader(null);
    }

    /** 瓜子 s(Δ)：HSL 明度 +Δ，钳 [0.1, 0.68]（CoverAmbientKt.s）。 */
    private static int shiftLightness(int color, float delta) {
        float r = Color.red(color) / 255f;
        float g = Color.green(color) / 255f;
        float b = Color.blue(color) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float l = (max + min) / 2f;
        float hue = 0f, s = 0f;
        if (max != min) {
            float d = max - min;
            s = l > 0.5f ? d / (2f - max - min) : d / (max + min);
            if (max == r) hue = (g - b) / d + (g < b ? 6f : 0f);
            else if (max == g) hue = (b - r) / d + 2f;
            else hue = (r - g) / d + 4f;
            hue *= 60f;
        }
        l = Math.max(0.1f, Math.min(0.68f, l + delta));
        float c = (1f - Math.abs(2f * l - 1f)) * s;
        float x = c * (1f - Math.abs((hue / 60f) % 2f - 1f));
        float m = l - c / 2f;
        float rr, gg, bb;
        if (hue < 60f) { rr = c; gg = x; bb = 0f; }
        else if (hue < 120f) { rr = x; gg = c; bb = 0f; }
        else if (hue < 180f) { rr = 0f; gg = c; bb = x; }
        else if (hue < 240f) { rr = 0f; gg = x; bb = c; }
        else if (hue < 300f) { rr = x; gg = 0f; bb = c; }
        else { rr = c; gg = 0f; bb = x; }
        return Color.argb(Color.alpha(color),
                Math.round((rr + m) * 255f),
                Math.round((gg + m) * 255f),
                Math.round((bb + m) * 255f));
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static int withAlpha(int color, float alpha) {
        return Color.argb(Math.round(clamp01(alpha) * 255),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    // ===== 流体相位（瓜子 FluidCoverAmbient/ik1：四相位帧驱动，周期 19/27/34/13；
    // 速率＝流速系数（慢 0.6／标准 1.0／快速 2.5）×（节拍量×0.3＋流体量×0.65＋1.0），
    // 星辰无节拍/流体量输入→括号内恒 1.0。糊层变换：缩放＝sin(p4)×0.3＋2.1、
    // 平移X＝72dp×sin(p2)、平移Y＝52dp×cos(p3)、旋转＝sin(p1)×8°。
    // 播放推进、暂停冻结；只动糊层，不动上面的清晰封面。）=====
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
            if (!mAmbientPlaying || !mFluidMode || !isAttachedToWindow()) {
                mDriftRunning = false;
                mDriftLastNs = 0;
                return;
            }
            double dt = 0;
            if (mDriftLastNs != 0) dt = Math.min(0.1, (frameTimeNanos - mDriftLastNs) / 1e9);
            mDriftLastNs = frameTimeNanos;
            // 速率＝流速系数×（节拍量×0.3＋流体量×0.65＋1.0）：流体量星辰无独立输入取 0；
            // 节拍量＝分析器发布的 beatPulse（律动关/无帧时 0，倍率回 1.0 与旧行为一致）
            double beat = MusicPlayerSettings.isFluidBeat()
                    ? com.fongmi.android.tv.service.MusicBeatState.beatPulse
                    * MusicPlayerSettings.fluidBeatFactor() : 0.0;
            double rate = mSpeedFactor * (beat * 0.3 + 1.0) * dt;
            mPh1 += rate * 2 * Math.PI / 19.0;
            mPh2 += rate * 2 * Math.PI / 27.0;
            mPh3 += rate * 2 * Math.PI / 34.0;
            mPh4 += rate * 2 * Math.PI / 13.0;
            float density = getResources().getDisplayMetrics().density;
            mDriftScale = (float) (2.1 + 0.3 * Math.sin(mPh4));
            mDriftTx = 72f * density * (float) Math.sin(mPh2);
            mDriftTy = 52f * density * (float) Math.cos(mPh3);
            mDriftRot = 8f * (float) Math.sin(mPh1);
            invalidate();
            android.view.Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private void startDriftIfNeeded() {
        if (mAmbientPlaying && mFluidMode && !mDriftRunning && isAttachedToWindow()) {
            mDriftRunning = true;
            mDriftLastNs = 0;
            android.view.Choreographer.getInstance().postFrameCallback(mDriftCallback);
        }
    }

    public void setAmbientPlaying(boolean playing) {
        if (mAmbientPlaying == playing) return;
        mAmbientPlaying = playing;
        if (playing) startDriftIfNeeded();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshSettings();
        startDriftIfNeeded();
    }

    @Override
    protected void onDetachedFromWindow() {
        android.view.Choreographer.getInstance().removeFrameCallback(mDriftCallback);
        mDriftRunning = false;
        super.onDetachedFromWindow();
    }

    /** 糊封面层：小图铺满录入节点，整层挂图形模糊（流体 18dp／静态 24dp），节点 alpha 流体 1.0／静态 0.4。 */
    private void drawBlurredViaNode(Canvas canvas, int w, int h) {
        if (mBlurNode == null) mBlurNode = new RenderNode("coverAmbientBlur");
        // 切后台回来录制内容被系统丢弃（hasDisplayList=false）：位图/尺寸都没变、旧守卫不重录，
        // 糊层从此画空——他真机「切个后台再进来背景变没」的定因（2026-10-26）。丢了就重录。
        // 模式切换（模糊半径/alpha 变）同样重录。
        if (mBlurNodeBitmap != mBlurBitmap || mBlurNodeW != w || mBlurNodeH != h
                || mNodeFluid != mFluidMode || !mBlurNode.hasDisplayList()) {
            mBlurNodeBitmap = mBlurBitmap;
            mBlurNodeW = w;
            mBlurNodeH = h;
            mNodeFluid = mFluidMode;
            mBlurNode.setPosition(0, 0, w, h);
            Canvas rec = mBlurNode.beginRecording(w, h);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            // 糊图按瓜子 ContentScale.Crop 口径铺满整页：等比放大盖住整页、居中裁切——
            // 图的内容以封面自身的比例顺着页面延展（瓜子 CoverAmbientKt 两分支同为
            // AsyncImage＋Crop＋fillMaxSize，源码亲验）。旧法把 64×64 方图整张拉伸进
            // 竖长 rect：图被纵向压扁成一团无内容的雾，封面下沿处内容断掉，真机读作
            // 「封面像一张图贴在暗页上」（2026-10-26 他同屏对比图定罪：瓜子封面下方
            // 糊面留着原图的鬼影顺延，星辰只剩均匀暗雾）。
            int bw = mBlurBitmap.getWidth(), bh = mBlurBitmap.getHeight();
            float cropScale = Math.max(w / (float) bw, h / (float) bh);
            int sw = Math.round(w / cropScale), sh = Math.round(h / cropScale);
            int sx = Math.max(0, (bw - sw) / 2), sy = Math.max(0, (bh - sh) / 2);
            android.graphics.Rect src = new android.graphics.Rect(
                    sx, sy, Math.min(bw, sx + sw), Math.min(bh, sy + sh));
            rec.drawBitmap(mBlurBitmap, src, new android.graphics.RectF(0, 0, w, h), p);
            mBlurNode.endRecording();
            float r = (mFluidMode ? 18f : 24f) * getResources().getDisplayMetrics().density;
            mBlurNode.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP));
            mBlurNode.setAlpha(mFluidMode ? 1.0f : 0.4f);
        }
        // 重录会把节点变换清零：每帧按当前模式重贴——流体用相位变换，静态恒等（静止、无漂移）
        mBlurNode.setPivotX(w / 2f);
        mBlurNode.setPivotY(h / 2f);
        if (mFluidMode) {
            mBlurNode.setScaleX(mDriftScale);
            mBlurNode.setScaleY(mDriftScale);
            mBlurNode.setTranslationX(mDriftTx);
            mBlurNode.setTranslationY(mDriftTy);
            mBlurNode.setRotationZ(mDriftRot);
        } else {
            mBlurNode.setScaleX(1f);
            mBlurNode.setScaleY(1f);
            mBlurNode.setTranslationX(0f);
            mBlurNode.setTranslationY(0f);
            mBlurNode.setRotationZ(0f);
        }
        canvas.drawRenderNode(mBlurNode);
    }
}
