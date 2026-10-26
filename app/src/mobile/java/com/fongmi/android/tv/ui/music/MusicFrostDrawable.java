package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * 音乐首页暗磨砂件（2026-10-27 试点：液态玻璃撤下、换暗磨砂，只服务音乐首页一页）。
 *
 * 材料就三样，没有多余的：
 * 1) 糊底——身后真实内容的降采样缓冲按宿主窗口位置裁剪直贴（首页＝ MusicHomeBackgroundView
 *    的照片缓冲；缓冲是 1/4 尺寸、自带糊度）。原样直贴：不提饱和、不改对比、不折射、
 *    不色散——液态玻璃那套效果链（MusicGlassDrawable 的 AVBox 链）整段不在这里；
 * 2) 钉死的中间深灰半透平铺：色 #333333、浓度 .72，写死在本类——不从背景取色、
 *    不随背景变色（他今晚定罪液态玻璃「染成背景色」的反面：件走到哪都是这层灰，
 *    背景再亮再花也只透出糊影、染不上件面）；
 * 3) 边缘一线很淡的亮边定形（白 .25、1dp），外加宿主层浮影（MusicFrostWire 给）。
 * 取样未就绪走下限面（同灰深色满铺），任何底上都成形、首帧不闪。
 * tone 染色口保留（心情选中片/播放钮的状态色，色是件自己的、不从背景来）：
 * 在灰层之上再平铺一层指定色，与 MusicGlassDrawable 的 setToneColor 同形调用面。
 */
public class MusicFrostDrawable extends Drawable {

    // 钉死的中性深灰 + 浓度（本页全部磨砂件同值，写死、不许任何路径改它）
    private static final int FROST_COLOR = 0xFF333333;
    private static final float FROST_ALPHA = 0.72f;
    // 可见下限面（取样为空时满铺）：同灰、近不透明，保证成形
    private static final int FALLBACK_COLOR = 0xF2333333;
    // 边缘亮线：白 .25
    private static final int STROKE_COLOR = 0x40FFFFFF;
    private static final int STROKE_PRESSED_COLOR = 0x59FFFFFF;
    // 按压时面上加一层很轻的白（触感反馈，仅此而已）
    private static final int PRESS_COLOR = 0x14FFFFFF;

    private final float mRadiusPx;
    private final float mDensity;
    private final boolean mCircle;

    private final Paint mSamplePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int mToneColor = 0;
    private boolean mPressed = false;
    private int mAlpha = 255;

    private final RectF mTmp = new RectF();
    private final Path mClip = new Path();
    private final android.graphics.Matrix mSampleMatrix = new android.graphics.Matrix();
    private float mSrcFx, mSrcFy, mSrcFw = 1f, mSrcFh = 1f;
    private final int[] mHostLoc = new int[2];
    private final int[] mBgLoc = new int[2];

    public MusicFrostDrawable(Context context, float cornerRadiusDp) {
        mDensity = context.getResources().getDisplayMetrics().density;
        mRadiusPx = cornerRadiusDp * mDensity;
        mCircle = cornerRadiusDp >= 999f;
        mFillPaint.setColor(FROST_COLOR);
        mFillPaint.setAlpha(Math.round(FROST_ALPHA * 255));
        mFallbackPaint.setColor(FALLBACK_COLOR);
        mStrokePaint.setStyle(Paint.Style.STROKE);
        mStrokePaint.setStrokeWidth(1f * mDensity);
        mStrokePaint.setColor(STROKE_COLOR);
        mPressPaint.setColor(PRESS_COLOR);
    }

    public boolean isCircle() {
        return mCircle;
    }

    /** tone 染色（状态色用）：color=0 清除；在灰层之上平铺指定色、alpha 按入参。 */
    public void setToneColor(int color) {
        setToneColor(color, 0.16f);
    }

    public void setToneColor(int color, float alpha) {
        mToneColor = color;
        if (color != 0) {
            mTonePaint.setColor(color);
            mTonePaint.setAlpha(Math.round(alpha * 255));
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
            mStrokePaint.setColor(pressed ? STROKE_PRESSED_COLOR : STROKE_COLOR);
            invalidateSelf();
            return true;
        }
        return false;
    }

    private float radiusFor(Rect b) {
        return Math.min(mRadiusPx, Math.min(b.width(), b.height()) / 2f);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        mTmp.set(b);
        float r = radiusFor(b);
        mClip.reset();
        mClip.addRoundRect(mTmp, r, r, Path.Direction.CW);

        canvas.save();
        canvas.clipPath(mClip);
        Bitmap buf = sampleBuffer(b);
        if (buf != null) {
            // 糊底直贴：取样窗按浮点映射贴到 bounds，无任何颜色加工
            mSampleMatrix.reset();
            mSampleMatrix.postTranslate(-mSrcFx, -mSrcFy);
            mSampleMatrix.postScale(b.width() / mSrcFw, b.height() / mSrcFh);
            mSampleMatrix.postTranslate(b.left, b.top);
            canvas.drawBitmap(buf, mSampleMatrix, mSamplePaint);
        } else {
            canvas.drawRect(mTmp, mFallbackPaint);
        }
        canvas.drawRect(mTmp, mFillPaint);           // 钉死深灰（浓度写死）
        if (mToneColor != 0) canvas.drawRect(mTmp, mTonePaint);
        if (mPressed) canvas.drawRect(mTmp, mPressPaint);
        canvas.restore();

        // 边缘一线亮边定形：内缩半线宽整圈描
        float inset = mStrokePaint.getStrokeWidth() / 2f;
        mTmp.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
        canvas.drawRoundRect(mTmp, Math.max(0, r - inset), Math.max(0, r - inset), mStrokePaint);
    }

    /**
     * 取样窗计算（与 MusicGlassDrawable 同法）：优先首页照片背景（MusicHomeBackgroundView
     * 在本树里时裁它的照片缓冲）；不在时回落全域共享缓冲（MusicGlassBackgroundView）。
     * 锚点按背景 View 在窗口中的位置对位，宿主滚动时位置变、取样跟着变，不定格。
     * 缓冲不存在返回 null → 画下限面。
     */
    private Bitmap sampleBuffer(Rect b) {
        if (!(getCallback() instanceof View)) return null;
        View host = (View) getCallback();
        View root = host.getRootView();
        if (root == null) return null;

        MusicHomeBackgroundView photo = MusicHomeBackgroundView.findInTree(root);
        if (photo != null && photo.getWidth() > 0 && photo.getHeight() > 0
                && MusicHomeBackgroundView.hasBuffer()) {
            Bitmap buf = MusicHomeBackgroundView.getBuffer();
            if (buf != null && !buf.isRecycled()
                    && mapWindow(host, photo, b,
                            MusicHomeBackgroundView.bufferWidth(), MusicHomeBackgroundView.bufferHeight())) {
                return buf;
            }
        }
        MusicGlassBackgroundView bg = MusicGlassBackgroundView.findInTree(root);
        if (bg != null && bg.getWidth() > 0 && bg.getHeight() > 0
                && MusicGlassBackgroundView.hasBuffer()) {
            Bitmap buf = MusicGlassBackgroundView.getBuffer();
            if (buf != null && !buf.isRecycled()
                    && mapWindow(host, bg, b,
                            MusicGlassBackgroundView.bufferWidth(), MusicGlassBackgroundView.bufferHeight())) {
                return buf;
            }
        }
        return null;
    }

    /** 把宿主 bounds 按背景 View 的窗口位置映射进缓冲坐标（浮点窗），成功返回 true。 */
    private boolean mapWindow(View host, View bgView, Rect b, int bw, int bh) {
        if (bw <= 0 || bh <= 0) return false;
        host.getLocationInWindow(mHostLoc);
        bgView.getLocationInWindow(mBgLoc);
        float sx = (float) bw / bgView.getWidth();
        float sy = (float) bh / bgView.getHeight();
        float x0 = (mHostLoc[0] + b.left - mBgLoc[0]) * sx;
        float y0 = (mHostLoc[1] + b.top - mBgLoc[1]) * sy;
        float x1 = (mHostLoc[0] + b.right - mBgLoc[0]) * sx;
        float y1 = (mHostLoc[1] + b.bottom - mBgLoc[1]) * sy;
        x0 = Math.max(0f, Math.min(x0, bw - 1f));
        y0 = Math.max(0f, Math.min(y0, bh - 1f));
        x1 = Math.max(x0 + 0.5f, Math.min(x1, bw));
        y1 = Math.max(y0 + 0.5f, Math.min(y1, bh));
        mSrcFx = x0;
        mSrcFy = y0;
        mSrcFw = x1 - x0;
        mSrcFh = y1 - y0;
        return true;
    }

    @Override
    public void setAlpha(int alpha) {
        mAlpha = alpha;
        mFillPaint.setAlpha(Math.round(FROST_ALPHA * alpha));
        mFallbackPaint.setAlpha(Math.round(0xF2 * alpha / 255f));
        mSamplePaint.setAlpha(alpha);
        mStrokePaint.setAlpha(Math.round((mPressed ? 0x59 : 0x40) * alpha / 255f));
        mPressPaint.setAlpha(Math.round(0x14 * alpha / 255f));
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        mSamplePaint.setColorFilter(colorFilter);
        mFillPaint.setColorFilter(colorFilter);
        mFallbackPaint.setColorFilter(colorFilter);
        mStrokePaint.setColorFilter(colorFilter);
        mPressPaint.setColorFilter(colorFilter);
        mTonePaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
