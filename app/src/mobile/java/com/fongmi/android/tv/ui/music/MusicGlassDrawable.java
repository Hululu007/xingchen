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
 * 音乐侧液态玻璃件（全 App 一套）：糊底机制照海阔 HkGlassDrawable 同源（从 MusicGlassBackgroundView
 * 的 1/4 共享缓冲按宿主窗口位置裁剪，天然透底模糊），罩层与内影用海阔现行值，
 * finishing 按用户点名的液态玻璃四项补齐（不是毛玻璃）：
 * 1) 边缘亮边：1dp 对角渐变描边（左上近全白→侧边收暗→底部回亮，明暗渐变，不是单色细线）；
 * 2) 顶部流光：自顶向下 .26→.08→0 的镜面反光带；
 * 3) 悬浮分层：底部 14dp 内软影（rgba(80,90,120,.20)，海阔值）＋底沿 3dp 接触影（.12），
 *    面与底分离（背景 Drawable 画不出一界之外的投影，靠内影＋亮边对比立分层）；
 * 4) 按压发亮：stateful，pressed 时整面加 .18 白光＋亮边切高亮渐变（宿主可点才有此态）。
 * 另有可选染色层（setTintColors）：宿主色相以横向渐变罩在糊底与白罩之间，默认无、无染色时行为与原先一致。
 * 裁剪失败兜底透明（透出页底，不刷白）。圆角两种：四角同圆／仅顶角（贴底面板用，底角不出缺口）。
 * 与海阔的机制差异（参数不变）：缓冲源为音乐静态底——除版本号外另以宿主窗口位置作重裁守卫，
 * 页面滚动监听再调 MusicGlassBackgroundView.noteScrollChanged() 顶版双保险；背景定位按类型找
 * MusicGlassBackgroundView（音乐各页 id 不一）；弹窗是独立窗口、树里找不到背景 View 时，
 * 回落到静态缓冲本体、锚点取 (0,0) 按源尺寸换算（全屏窗口同原点，糊底内容即当时音乐页色场）。
 */
public class MusicGlassDrawable extends Drawable {

    private final float mRadius;          // px
    private final float mDensity;
    private final boolean mTopOnly;

    private final Paint mBitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint mTintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mOverlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mContactPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Rect mSrc = new Rect();
    private final RectF mDst = new RectF();
    private final RectF mTmp = new RectF();
    private final Path mClip = new Path();
    private final Path mRim = new Path();
    private final float[] mRadii = new float[8];
    private final int[] mHostLoc = new int[2];
    private final int[] mBgLoc = new int[2];
    private final int[] mLocCheck = new int[2];

    private Shader mRimShader;
    private Shader mRimShaderPressed;
    private boolean mPressed;

    // 可选染色层（默认无）：横向多停点渐变，画在糊底之上、白罩之下——宿主的色相身份
    // （如首页心情卡的心情三色）以染色活在玻璃里，不许再拿实色填充盖掉卡底。
    // 强度 TINT_STRENGTH 为星辰侧自定：色相要读得出、糊底要透得过，待真机判定。
    private static final float TINT_STRENGTH = 0.62f;
    private int[] mTintColors;
    private Shader mTintShader;
    private final Rect mTintBounds = new Rect();
    private int mAlpha = 255;

    /** 设置／清除染色（colors 为横向渐变停点色，null 或少于 2 色＝无染色）。 */
    public void setTintColors(int[] colors) {
        mTintColors = colors != null && colors.length >= 2 ? colors.clone() : null;
        rebuildTintShader();
        invalidateSelf();
    }

    private void rebuildTintShader() {
        if (mTintColors == null || mTintBounds.isEmpty()) {
            mTintShader = null;
        } else {
            mTintShader = new LinearGradient(mTintBounds.left, 0, mTintBounds.right, 0,
                    mTintColors, null, Shader.TileMode.CLAMP);
        }
        mTintPaint.setShader(mTintShader);
        mTintPaint.setAlpha(Math.round(mAlpha * TINT_STRENGTH));
    }

    private int mLastVersion = -1;
    private boolean mLastOk = false;
    private int mLastLocX = Integer.MIN_VALUE;
    private int mLastLocY = Integer.MIN_VALUE;

    // 白罩（与 HkGlassDrawable 同值）：.58→.34→.46，停点 0/.55/1
    private static final int[] OVERLAY_WHITE = {0x94FFFFFF, 0x57FFFFFF, 0x75FFFFFF};
    private final float[] mOverlayStops = {0f, 0.55f, 1f};
    // 顶部流光带：.26→.08→0（镜面反光，只在上部）
    private static final int[] SHEEN = {0x42FFFFFF, 0x14FFFFFF, 0x00FFFFFF};
    private final float[] mSheenStops = {0f, 0.42f, 0.75f};
    // 边缘亮边（对角明暗渐变）：常量态／按压高亮态
    private static final int[] RIM = {0xE6FFFFFF, 0x66FFFFFF, 0x40FFFFFF, 0x8CFFFFFF};
    private static final int[] RIM_PRESSED = {0xFFFFFFFF, 0x99FFFFFF, 0x73FFFFFF, 0xBFFFFFFF};
    private final float[] mRimStops = {0f, 0.35f, 0.7f, 1f};

    public MusicGlassDrawable(Context context, float cornerRadiusDp) {
        this(context, cornerRadiusDp, false);
    }

    public MusicGlassDrawable(Context context, float cornerRadiusDp, boolean topOnly) {
        mDensity = context.getResources().getDisplayMetrics().density;
        mRadius = cornerRadiusDp * mDensity;
        mTopOnly = topOnly;
        mFallbackPaint.setColor(0x00000000); // 兜底透明：裁剪失败时透出页底，不整片刷白
        mStrokePaint.setStyle(Paint.Style.STROKE);
        mStrokePaint.setStrokeWidth(1f * mDensity);
        mGlowPaint.setColor(0x2EFFFFFF); // 按压发亮：整面 +.18 白光
        float r = mRadius;
        // addRoundRect 圆角序：左上/右上/右下/左下（各 x,y）；仅顶角模式下两角归零（贴底不留缺口）
        mRadii[0] = r; mRadii[1] = r; mRadii[2] = r; mRadii[3] = r;
        mRadii[4] = topOnly ? 0 : r; mRadii[5] = topOnly ? 0 : r;
        mRadii[6] = topOnly ? 0 : r; mRadii[7] = topOnly ? 0 : r;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        mLastVersion = -1; // 界变了就重裁
        mLastLocX = Integer.MIN_VALUE;
        mTintBounds.set(bounds);
        rebuildTintShader();
        mOverlayPaint.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                OVERLAY_WHITE, mOverlayStops, Shader.TileMode.CLAMP));
        mSheenPaint.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                SHEEN, mSheenStops, Shader.TileMode.CLAMP));
        // 底部内软影：rgba(80,90,120,.20) 向上羽化 14dp（与海阔同值）；底沿接触影 3dp/.12 加重分层
        float sh = 14f * mDensity;
        mShadowPaint.setShader(new LinearGradient(0, bounds.bottom - sh, 0, bounds.bottom,
                new int[]{0x00000000, 0x33505A78}, null, Shader.TileMode.CLAMP));
        float ch = 3f * mDensity;
        mContactPaint.setShader(new LinearGradient(0, bounds.bottom - ch, 0, bounds.bottom,
                new int[]{0x00000000, 0x1F505A78}, null, Shader.TileMode.CLAMP));
        // 亮边对角渐变（左上→右下）：常态／按压态各一份
        mRimShader = new LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom,
                RIM, mRimStops, Shader.TileMode.CLAMP);
        mRimShaderPressed = new LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom,
                RIM_PRESSED, mRimStops, Shader.TileMode.CLAMP);
        mStrokePaint.setShader(mPressed ? mRimShaderPressed : mRimShader);
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    protected boolean onStateChange(int[] stateSet) {
        boolean pressed = false;
        for (int s : stateSet) if (s == android.R.attr.state_pressed) { pressed = true; break; }
        if (pressed == mPressed) return false;
        mPressed = pressed;
        mStrokePaint.setShader(mPressed ? mRimShaderPressed : mRimShader);
        invalidateSelf();
        return true;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        mTmp.set(b);
        mClip.reset();
        mClip.addRoundRect(mTmp, mRadii, Path.Direction.CW);

        // 裁剪：版本号变化、上次失败、或宿主窗口位置变了（滚动）时重算
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
        // 染色（宿主色相）→ 罩 → 流光 → 按压光 → 内软影＋接触影（面与底的分层）
        if (mTintShader != null) canvas.drawRect(mTmp, mTintPaint);
        canvas.drawRect(mTmp, mOverlayPaint);
        canvas.drawRect(mTmp, mSheenPaint);
        if (mPressed) canvas.drawRect(mTmp, mGlowPaint);
        canvas.drawRect(mTmp, mShadowPaint);
        canvas.drawRect(mTmp, mContactPaint);
        canvas.restore();
        // 边缘亮边（clip 外画，避免被切掉半个描边）
        float half = mStrokePaint.getStrokeWidth() / 2f;
        mTmp.set(b.left + half, b.top + half, b.right - half, b.bottom - half);
        float[] rr = new float[8];
        for (int i = 0; i < 8; i++) rr[i] = Math.max(0, mRadii[i] - half);
        mRim.reset();
        mRim.addRoundRect(mTmp, rr, Path.Direction.CW);
        canvas.drawPath(mRim, mStrokePaint);
    }

    /** 按宿主在窗口中的位置，把界映射到缓冲坐标。失败返回 false（走兜底）。 */
    private boolean computeCrop(Rect b) {
        if (!(getCallback() instanceof View)) return false;
        if (!MusicGlassBackgroundView.hasBuffer()) return false;
        View host = (View) getCallback();
        host.getLocationInWindow(mHostLoc);
        float sx, sy;
        int anchorX, anchorY;
        View root = host.getRootView();
        MusicGlassBackgroundView bg = root == null ? null : findGlassBg(root);
        if (bg != null && bg.getWidth() > 0 && bg.getHeight() > 0) {
            // 同窗口：锚点＝背景 View 的窗口位置（与海阔固定 id 找法口径等价，音乐按类型找）
            bg.getLocationInWindow(mBgLoc);
            anchorX = mBgLoc[0];
            anchorY = mBgLoc[1];
            sx = (float) MusicGlassBackgroundView.bufferWidth() / bg.getWidth();
            sy = (float) MusicGlassBackgroundView.bufferHeight() / bg.getHeight();
        } else {
            // 独立窗口（弹窗面板）：回落静态缓冲本体，两全屏窗口同原点，锚点 (0,0)、按源尺寸换算
            int sw = MusicGlassBackgroundView.sourceWidth(), sh = MusicGlassBackgroundView.sourceHeight();
            if (sw <= 0 || sh <= 0) return false;
            anchorX = 0;
            anchorY = 0;
            sx = (float) MusicGlassBackgroundView.bufferWidth() / sw;
            sy = (float) MusicGlassBackgroundView.bufferHeight() / sh;
        }
        int bw = MusicGlassBackgroundView.bufferWidth(), bh = MusicGlassBackgroundView.bufferHeight();
        int x0 = (int) ((mHostLoc[0] + b.left - anchorX) * sx);
        int y0 = (int) ((mHostLoc[1] + b.top - anchorY) * sy);
        int x1 = (int) ((mHostLoc[0] + b.right - anchorX) * sx);
        int y1 = (int) ((mHostLoc[1] + b.bottom - anchorY) * sy);
        x0 = Math.max(0, Math.min(x0, bw - 1));
        y0 = Math.max(0, Math.min(y0, bh - 1));
        x1 = Math.max(x0 + 1, Math.min(x1, bw));
        y1 = Math.max(y0 + 1, Math.min(y1, bh));
        mSrc.set(x0, y0, x1, y1);
        return true;
    }

    /** 音乐各页背景 id 不一，按类型在根视图树里找背景 View。 */
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
        mAlpha = alpha;
        mBitmapPaint.setAlpha(alpha);
        mTintPaint.setAlpha(Math.round(alpha * TINT_STRENGTH));
        mOverlayPaint.setAlpha(alpha);
        mSheenPaint.setAlpha(alpha);
        mStrokePaint.setAlpha(alpha);
        mShadowPaint.setAlpha(alpha);
        mContactPaint.setAlpha(alpha);
        mGlowPaint.setAlpha(alpha);
        mFallbackPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        mBitmapPaint.setColorFilter(colorFilter);
        mTintPaint.setColorFilter(colorFilter);
        mOverlayPaint.setColorFilter(colorFilter);
        mSheenPaint.setColorFilter(colorFilter);
        mStrokePaint.setColorFilter(colorFilter);
        mShadowPaint.setColorFilter(colorFilter);
        mContactPaint.setColorFilter(colorFilter);
        mGlowPaint.setColorFilter(colorFilter);
        mFallbackPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
