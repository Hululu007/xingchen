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
import android.view.ViewGroup;

/**
 * 音乐侧真玻璃件（2026-10-25 改令：做法与取值照海阔 HkGlassDrawable 同源移植，不另立音乐估值）：
 * 背景＝从 MusicGlassBackgroundView 的共享缓冲按自身在窗口中的位置裁剪（缓冲已是 1/4 降采样，
 * 天然透底模糊），再叠三层：白渐变罩（.58→.34→.46）＋底部内软影＋1dp 白描边高光（75%），
 * 与 HkGlassDrawable 现行值逐值一致；裁剪失败兜底透明（透出页底，不刷白）。
 * 与海阔的差异只有机制接法两处（参数不变）：
 * 1) 缓冲源：海阔缓冲带漂移、每 500ms 顶一版版本号促玻璃件重裁；音乐底是静态图、缓冲只在
 *    尺寸变化时重绘，故本件另加守卫——宿主窗口位置变了（滚动/布局）也重裁，页面滚动监听
 *    另调 MusicGlassBackgroundView.noteScrollChanged() 顶版本号双保险。
 * 2) 背景定位：海阔按固定 id（hk_dynamic_bg）找背景 View；音乐各页背景 id 不一
 *    （首页 homeGlassBg／其余页 musicGlassBg），故按类型在根视图树里找 MusicGlassBackgroundView。
 */
public class MusicGlassDrawable extends Drawable {

    private final float mRadius;          // px
    private final float mDensity;

    private final Paint mBitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint mOverlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Rect mSrc = new Rect();
    private final RectF mDst = new RectF();
    private final RectF mTmp = new RectF();
    private final Path mClip = new Path();
    private final int[] mHostLoc = new int[2];
    private final int[] mBgLoc = new int[2];
    private final int[] mLocCheck = new int[2];

    private int mLastVersion = -1;
    private boolean mLastOk = false;
    private int mLastLocX = Integer.MIN_VALUE;
    private int mLastLocY = Integer.MIN_VALUE;

    // 白玻璃（与 HkGlassDrawable 同值）：白渐变罩 .58→.34→.46 ＋ 1dp 白描边（75%）
    private static final int[] OVERLAY_WHITE = {0x94FFFFFF, 0x57FFFFFF, 0x75FFFFFF};
    private final float[] mOverlayStops = {0f, 0.55f, 1f};

    public MusicGlassDrawable(Context context, float cornerRadiusDp) {
        mDensity = context.getResources().getDisplayMetrics().density;
        mRadius = cornerRadiusDp * mDensity;
        mFallbackPaint.setColor(0x00000000); // 兜底透明：裁剪失败时透出页底，不整片刷白
        mStrokePaint.setStyle(Paint.Style.STROKE);
        mStrokePaint.setStrokeWidth(1f * mDensity);
        mStrokePaint.setColor(0xBFFFFFFF);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        mLastVersion = -1; // 界变了就重裁
        mLastLocX = Integer.MIN_VALUE;
        // 罩色 shader（界相关，缓存）：白玻璃 .58→.34→.46
        mOverlayPaint.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                OVERLAY_WHITE, mOverlayStops, Shader.TileMode.CLAMP));
        // 底部内软影：rgba(80,90,120,.20) 向上羽化 14dp（与海阔同值）
        float sh = 14f * mDensity;
        mShadowPaint.setShader(new LinearGradient(0, bounds.bottom - sh, 0, bounds.bottom,
                new int[]{0x00000000, 0x33505A78}, null, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        mTmp.set(b);
        mClip.reset();
        mClip.addRoundRect(mTmp, mRadius, mRadius, Path.Direction.CW);

        // 1) 裁剪缓冲：版本号变化、上次失败、或宿主窗口位置变了（滚动）时重算
        boolean moved = false;
        if (getCallback() instanceof View) {
            View host = (View) getCallback();
            host.getLocationInWindow(mLocCheck);
            int x = mLocCheck[0] + b.left, y = mLocCheck[1] + b.top;
            moved = (x != mLastLocX || y != mLastLocY);
            mLastLocX = x;
            mLastLocY = y;
        }
        int ver = MusicGlassBackgroundView.getBufferVersion();
        if (ver != mLastVersion || moved || !mLastOk) {
            mLastOk = computeCrop(b);
            mLastVersion = ver;
        }

        canvas.save();
        canvas.clipPath(mClip);
        if (mLastOk) {
            Bitmap buf = MusicGlassBackgroundView.getBuffer();
            if (buf != null && !buf.isRecycled()) {
                mDst.set(b);
                canvas.drawBitmap(buf, mSrc, mDst, mBitmapPaint);
            } else {
                canvas.drawRect(mTmp, mFallbackPaint);
            }
        } else {
            canvas.drawRect(mTmp, mFallbackPaint); // 兜底：透明透底
        }
        // 2) 白渐变罩 3) 底部内软影
        canvas.drawRect(mTmp, mOverlayPaint);
        canvas.drawRect(mTmp, mShadowPaint);
        canvas.restore();
        // 4) 边缘高光（clip 外画，避免被切掉半个描边）
        float half = mStrokePaint.getStrokeWidth() / 2f;
        mTmp.set(b.left + half, b.top + half, b.right - half, b.bottom - half);
        canvas.drawRoundRect(mTmp, Math.max(0, mRadius - half), Math.max(0, mRadius - half), mStrokePaint);
    }

    /** 按宿主在窗口中的位置，把界映射到缓冲坐标。失败返回 false（走兜底）。 */
    private boolean computeCrop(Rect b) {
        if (!(getCallback() instanceof View)) return false;
        View host = (View) getCallback();
        View root = host.getRootView();
        if (root == null) return false;
        MusicGlassBackgroundView bg = findGlassBg(root);
        if (bg == null || !MusicGlassBackgroundView.hasBuffer()) return false;
        int bgW = bg.getWidth(), bgH = bg.getHeight();
        if (bgW <= 0 || bgH <= 0) return false;
        host.getLocationInWindow(mHostLoc);
        bg.getLocationInWindow(mBgLoc);
        float sx = (float) MusicGlassBackgroundView.bufferWidth() / bgW;
        float sy = (float) MusicGlassBackgroundView.bufferHeight() / bgH;
        int x0 = (int) ((mHostLoc[0] + b.left - mBgLoc[0]) * sx);
        int y0 = (int) ((mHostLoc[1] + b.top - mBgLoc[1]) * sy);
        int x1 = (int) ((mHostLoc[0] + b.right - mBgLoc[0]) * sx);
        int y1 = (int) ((mHostLoc[1] + b.bottom - mBgLoc[1]) * sy);
        // clamp 进缓冲界
        int bw = MusicGlassBackgroundView.bufferWidth(), bh = MusicGlassBackgroundView.bufferHeight();
        x0 = Math.max(0, Math.min(x0, bw - 1));
        y0 = Math.max(0, Math.min(y0, bh - 1));
        x1 = Math.max(x0 + 1, Math.min(x1, bw));
        y1 = Math.max(y0 + 1, Math.min(y1, bh));
        mSrc.set(x0, y0, x1, y1);
        return true;
    }

    /** 音乐各页背景 id 不一，按类型在根视图树里找背景 View（海阔是固定 id，口径等价）。 */
    private static MusicGlassBackgroundView findGlassBg(View v) {
        if (v instanceof MusicGlassBackgroundView) return (MusicGlassBackgroundView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                MusicGlassBackgroundView r = findGlassBg(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    @Override
    public void setAlpha(int alpha) {
        mBitmapPaint.setAlpha(alpha);
        mOverlayPaint.setAlpha(alpha);
        mStrokePaint.setAlpha(alpha);
        mShadowPaint.setAlpha(alpha);
        mFallbackPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        mBitmapPaint.setColorFilter(colorFilter);
        mOverlayPaint.setColorFilter(colorFilter);
        mStrokePaint.setColorFilter(colorFilter);
        mShadowPaint.setColorFilter(colorFilter);
        mFallbackPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
