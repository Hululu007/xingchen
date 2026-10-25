package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * 音乐液态玻璃件（W2 材料层从零重写，全 App 一套）。
 * 糊底机制与海阔 HkGlassDrawable 同源：从 MusicGlassBackgroundView 的 1/4 共享缓冲
 * 按宿主在窗口中的位置裁剪，天然透底模糊；零 RenderEffect。
 *
 * 硬口径（1.4.29 回归清单第 1/5 条销账）：
 * 1) 自带不依赖取样的可见下限——取样为空/未就绪/裁剪失败时，不走透明兜底，
 *    改走下限面：半透明白渐变下限层 {0xD9FFFFFF, 0xB3FFFFFF, 0xCCFFFFFF} 照样满铺，
 *    其上白罩、顶部流光、底部影、亮边描边一层不少，任何底上都成形。
 * 2) 小尺寸圆形键（圆角 999）只留一层轻接触影（3dp / 0x1F505A78），
 *    不叠底部内软影——56dp 圆键叠双层影在右下成灰污团（回归清单第 5 条）。
 *
 * 液态玻璃四项 finishing（用户定：边缘亮边、顶部流光、悬浮分层、按压发亮）：
 * - 边缘亮边：1dp 对角渐变描边 {E6,66,40,8C} 停点 {0,.35,.7,1}，左上最亮、底部回亮；
 * - 顶部流光：纵向 {.26,.08,0} 停点 {0,.42,.75}，只活在上部；
 * - 悬浮分层：底部内软影 14dp / rgba(80,90,120,.20)＋底沿接触影 3dp / .12 合成
 *   （背景件画不出一界之外的投影，分层由糊底透深＋亮边＋内影合成；小圆键例外只留接触影）；
 * - 按压发亮：state_pressed 时整面加 0x2EFFFFFF 白光、亮边切高亮渐变，松开即回。
 */
public class MusicGlassDrawable extends Drawable {

    // 白罩（与海阔逐值一致）：.58→.34→.46，停点 {0,.55,1}
    private static final int[] OVERLAY = {0x94FFFFFF, 0x57FFFFFF, 0x75FFFFFF};
    private static final float[] OVERLAY_STOPS = {0f, 0.55f, 1f};
    // 可见下限层（取样失败时满铺）：半透明白渐变，任何底上可辨
    private static final int[] FALLBACK = {0xD9FFFFFF, 0xB3FFFFFF, 0xCCFFFFFF};
    // 顶部流光
    private static final int[] SHEEN = {0x42FFFFFF, 0x14FFFFFF, 0x00FFFFFF};
    private static final float[] SHEEN_STOPS = {0f, 0.42f, 0.75f};
    // 边缘亮边（常态／按压高亮）
    private static final int[] STROKE = {0xE6FFFFFF, 0x66FFFFFF, 0x40FFFFFF, 0x8CFFFFFF};
    private static final int[] STROKE_PRESSED = {0xFFFFFFFF, 0x99FFFFFF, 0x73FFFFFF, 0xBFFFFFFF};
    private static final float[] STROKE_STOPS = {0f, 0.35f, 0.7f, 1f};

    private static volatile int sEpoch = 0; // 滚动/位置变化时 +1，强制全件重裁

    /** 列表滚动等宿主位置变化场景调用：令全部玻璃件下一帧重裁糊底。 */
    public static void noteScrollChanged() {
        sEpoch++;
    }

    private final float mRadiusPx;
    private final float mDensity;
    private final boolean mTopOnly;   // 面板贴底：仅顶角圆、底角归零
    private final boolean mCircle;    // 圆角 999 的小圆键：一层轻接触影口径

    private final Paint mBitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint mFallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mOverlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mContactPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int mToneColor = 0; // 0=无染色；非 0 时以 alpha 0.16 平铺一层（白罩之上、流光之下）

    private final Rect mSrc = new Rect();
    private final RectF mDst = new RectF();
    private final RectF mTmp = new RectF();
    private final Path mClip = new Path();
    private final int[] mHostLoc = new int[2];
    private final int[] mBgLoc = new int[2];

    private int mLastVersion = -1;
    private int mLastEpoch = -1;
    private int mLastHostX = Integer.MIN_VALUE, mLastHostY = Integer.MIN_VALUE;
    private boolean mLastOk = false;
    private boolean mPressed = false;

    public MusicGlassDrawable(Context context, float cornerRadiusDp) {
        this(context, cornerRadiusDp, false);
    }

    /** topOnly=true：仅顶两角圆（面板贴底用），底角归零无缺口。 */
    public MusicGlassDrawable(Context context, float cornerRadiusDp, boolean topOnly) {
        mDensity = context.getResources().getDisplayMetrics().density;
        mRadiusPx = cornerRadiusDp * mDensity;
        mTopOnly = topOnly;
        mCircle = cornerRadiusDp >= 999f;
        mStrokePaint.setStyle(Paint.Style.STROKE);
        mStrokePaint.setStrokeWidth(1f * mDensity);
        mPressPaint.setColor(0x2EFFFFFF);
    }

    public boolean isCircle() {
        return mCircle;
    }

    /** tone 染色（心情卡卡底用）：color=0 清除染色恢复原样；非 0 时平铺一层、alpha 0.16。 */
    public void setToneColor(int color) {
        mToneColor = color;
        if (color != 0) {
            mTonePaint.setColor(color);
            mTonePaint.setAlpha(Math.round(0.16f * 255));
        }
        invalidateSelf();
    }

    public int getToneColor() {
        return mToneColor;
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    protected boolean onStateChange(int[] state) {
        boolean pressed = false;
        for (int s : state) if (s == android.R.attr.state_pressed) pressed = true;
        if (pressed != mPressed) {
            mPressed = pressed;
            rebuildStrokeShader(getBounds());
            invalidateSelf();
            return true;
        }
        return false;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        mLastVersion = -1;
        mOverlayPaint.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                OVERLAY, OVERLAY_STOPS, Shader.TileMode.CLAMP));
        mFallbackPaint.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                FALLBACK, OVERLAY_STOPS, Shader.TileMode.CLAMP));
        mSheenPaint.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                SHEEN, SHEEN_STOPS, Shader.TileMode.CLAMP));
        // 底部内软影：rgba(80,90,120,.20) 向上羽化 14dp（小圆键不设、不画）
        float sh = 14f * mDensity;
        mShadowPaint.setShader(new LinearGradient(0, bounds.bottom - sh, 0, bounds.bottom,
                new int[]{0x00000000, 0x33505A78}, null, Shader.TileMode.CLAMP));
        // 底沿接触影：3dp / 0x1F505A78（.12），全件共用一层
        float ch = 3f * mDensity;
        mContactPaint.setShader(new LinearGradient(0, bounds.bottom - ch, 0, bounds.bottom,
                new int[]{0x00000000, 0x1F505A78}, null, Shader.TileMode.CLAMP));
        rebuildStrokeShader(bounds);
    }

    private void rebuildStrokeShader(Rect b) {
        if (b.isEmpty()) return;
        mStrokePaint.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                mPressed ? STROKE_PRESSED : STROKE, STROKE_STOPS, Shader.TileMode.CLAMP));
    }

    private Path buildClip(RectF f) {
        mClip.reset();
        if (mTopOnly) {
            float r = mRadiusPx;
            mClip.addRoundRect(f, new float[]{r, r, r, r, 0, 0, 0, 0}, Path.Direction.CW);
        } else {
            mClip.addRoundRect(f, mRadiusPx, mRadiusPx, Path.Direction.CW);
        }
        return mClip;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        mTmp.set(b);

        // 宿主窗口位置变了（滚动/布局位移）也要重裁，不能只认缓冲版本号
        boolean needCrop = false;
        if (getCallback() instanceof View) {
            View host = (View) getCallback();
            host.getLocationInWindow(mHostLoc);
            if (mHostLoc[0] != mLastHostX || mHostLoc[1] != mLastHostY) {
                mLastHostX = mHostLoc[0];
                mLastHostY = mHostLoc[1];
                needCrop = true;
            }
        }
        int ver = MusicGlassBackgroundView.getBufferVersion();
        if (ver != mLastVersion || sEpoch != mLastEpoch || needCrop) {
            mLastOk = computeCrop(b);
            mLastVersion = ver;
            mLastEpoch = sEpoch;
        }

        canvas.save();
        canvas.clipPath(buildClip(mTmp));
        if (mLastOk) {
            Bitmap buf = MusicGlassBackgroundView.getBuffer();
            if (buf != null && !buf.isRecycled()) {
                mDst.set(b);
                canvas.drawBitmap(buf, mSrc, mDst, mBitmapPaint);
            } else {
                canvas.drawRect(mTmp, mFallbackPaint);
            }
        } else {
            // 可见下限：取样为空/未就绪时下限层满铺成形（回归清单第 1 条）
            canvas.drawRect(mTmp, mFallbackPaint);
        }
        canvas.drawRect(mTmp, mOverlayPaint);          // 白罩
        if (mToneColor != 0) canvas.drawRect(mTmp, mTonePaint); // tone 染色（流光/亮边之下）
        canvas.drawRect(mTmp, mSheenPaint);            // 顶部流光
        if (!mCircle) {
            canvas.drawRect(mTmp, mShadowPaint);       // 底部内软影（大件分层）
        }
        canvas.drawRect(mTmp, mContactPaint);          // 唯一一层接触影（小圆键只有它）
        if (mPressed) canvas.drawRect(mTmp, mPressPaint); // 按压发亮
        canvas.restore();

        // 亮边：界内缩半宽画，避免被圆角裁掉半边
        float half = mStrokePaint.getStrokeWidth() / 2f;
        mTmp.set(b.left + half, b.top + half, b.right - half, b.bottom - half);
        float r = Math.max(0, mRadiusPx - half);
        canvas.save();
        if (mTopOnly) {
            Path p = new Path();
            p.addRoundRect(mTmp, new float[]{r, r, r, r, 0, 0, 0, 0}, Path.Direction.CW);
            canvas.drawPath(p, mStrokePaint);
        } else {
            canvas.drawRoundRect(mTmp, r, r, mStrokePaint);
        }
        canvas.restore();
    }

    /**
     * 按宿主在窗口中的位置把界映射到缓冲坐标。
     * 页面内：树里按类型找 MusicGlassBackgroundView 对位裁剪；
     * 独立窗口（弹窗）：树里没有背景 View 时走静态回落——直接用共享缓冲本体、
     * 锚点 (0,0) 按源尺寸换算（活动窗与弹窗窗同为全屏同原点）。
     * 缓冲不存在返回 false → 画可见下限层（不是透明）。
     */
    private boolean computeCrop(Rect b) {
        if (!MusicGlassBackgroundView.hasBuffer()) return false;
        int bw = MusicGlassBackgroundView.bufferWidth();
        int bh = MusicGlassBackgroundView.bufferHeight();
        if (bw <= 0 || bh <= 0) return false;

        if (getCallback() instanceof View) {
            View host = (View) getCallback();
            View root = host.getRootView();
            MusicGlassBackgroundView bg = root != null ? MusicGlassBackgroundView.findInTree(root) : null;
            if (bg != null && bg.getWidth() > 0 && bg.getHeight() > 0) {
                host.getLocationInWindow(mHostLoc);
                bg.getLocationInWindow(mBgLoc);
                float sx = (float) bw / bg.getWidth();
                float sy = (float) bh / bg.getHeight();
                return setSrc(
                        (int) ((mHostLoc[0] + b.left - mBgLoc[0]) * sx),
                        (int) ((mHostLoc[1] + b.top - mBgLoc[1]) * sy),
                        (int) ((mHostLoc[0] + b.right - mBgLoc[0]) * sx),
                        (int) ((mHostLoc[1] + b.bottom - mBgLoc[1]) * sy), bw, bh);
            }
            // 静态回落：按宿主窗口坐标对全屏缓冲换算
            android.util.DisplayMetrics dm = host.getResources().getDisplayMetrics();
            if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                host.getLocationInWindow(mHostLoc);
                float sx = (float) bw / dm.widthPixels;
                float sy = (float) bh / dm.heightPixels;
                return setSrc(
                        (int) ((mHostLoc[0] + b.left) * sx),
                        (int) ((mHostLoc[1] + b.top) * sy),
                        (int) ((mHostLoc[0] + b.right) * sx),
                        (int) ((mHostLoc[1] + b.bottom) * sy), bw, bh);
            }
        }
        return false;
    }

    private boolean setSrc(int x0, int y0, int x1, int y1, int bw, int bh) {
        x0 = Math.max(0, Math.min(x0, bw - 1));
        y0 = Math.max(0, Math.min(y0, bh - 1));
        x1 = Math.max(x0 + 1, Math.min(x1, bw));
        y1 = Math.max(y0 + 1, Math.min(y1, bh));
        mSrc.set(x0, y0, x1, y1);
        return true;
    }

    @Override
    public void setAlpha(int alpha) {
        mBitmapPaint.setAlpha(alpha);
        mFallbackPaint.setAlpha(alpha);
        mOverlayPaint.setAlpha(alpha);
        mSheenPaint.setAlpha(alpha);
        mShadowPaint.setAlpha(alpha);
        mContactPaint.setAlpha(alpha);
        mStrokePaint.setAlpha(alpha);
        mPressPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        mBitmapPaint.setColorFilter(colorFilter);
        mFallbackPaint.setColorFilter(colorFilter);
        mOverlayPaint.setColorFilter(colorFilter);
        mSheenPaint.setColorFilter(colorFilter);
        mShadowPaint.setColorFilter(colorFilter);
        mContactPaint.setColorFilter(colorFilter);
        mStrokePaint.setColorFilter(colorFilter);
        mPressPaint.setColorFilter(colorFilter);
        mTonePaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
