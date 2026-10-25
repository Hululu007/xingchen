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
 *    改走下限面：深烟熏渐变下限层 {0xFF4A4A4E, 0xFF333336, 0xFF262628} 照样满铺，
 *    其上烟熏罩、顶部流光、底部影、亮边描边一层不少，任何底上都成形。
 * 2) 小尺寸圆形键（圆角 999）只留一层轻接触影（4dp / 黑 .28），
 *    不叠底部内软影——56dp 圆键叠双层影在右下成灰污团（回归清单第 5 条）。
 *
 * 液态玻璃四项 finishing（用户定：边缘亮边、顶部流光、悬浮分层、按压发亮）：
 * - 边缘亮边：1dp 基础环（纵向 {顶 .30, 中 .20, 底 .14}）＋顶部镜面亮线 1.5dp 白 .85
 *   （只走顶边与两顶角、近实心硬线——苹果液态玻璃定版口径：顶约两倍于侧、侧 .20、底 .14）；
 * - 斜向流光：135° 对角 {白 .45 → .28 处 .08 → .58 处 0}（src-over 近似定版的 screen 叠加）；
 * - 悬浮分层：底部内软影 18dp / 黑 .30＋底沿接触影 4dp / 黑 .28（背景件画不出一界之外的
 *   投影，以内影近似 CSS 0 8px 32px rgba(0,0,0,.25–.32) 的 alpha 量级；小圆键例外只留接触影）；
 * - 按压发亮：state_pressed 时整面加 0x2EFFFFFF 白光、亮边/亮线同步提亮，松开即回。
 *
 * 显形轮定版（2026-10-25 他给样张「这种才是液态玻璃」＋主会话查苹果液态玻璃 CSS 拆解参数
 * ＋样张逐区实测色号三方对齐，裁定面栈三层、逐层报值）：
 *   1) 糊底：共享缓冲采样过 ColorMatrix——饱和 ×1.75（定版区间 1.70–1.80，液态与毛玻璃
 *      的分水岭）＋亮度 ×1.10（+10%）；
 *   2) 烟熏填充：真深色填充 #26282C（近黑微冷）alpha {.80,.84,.88}——CSS 白 10% tint 在
 *      星辰浅底上合不出样张的 #2B2B2B 家族，靠这一层合成后落进该家族；
 *   3) 白 tint 收尾：白色 alpha .10 平铺（定版 rgba(255,255,255,0.10)；
 *      旧白罩 {.58,.34,.46} 厚涂＝他定性的「毛玻璃」，已废）。
 * 选中族件由 MusicGlassWire 换件时挂 #305F67 @ .55 tone（烟熏之上、tint 之下）成带色玻璃。
 * 件内前景由 MusicGlassWire 换件时同步翻白系（主字 #F1F4F5／副字白 .60／图标 #F1F3F5，
 * 样张实测口径；副字 alpha 由样张 .45 起算、经 WCAG 合成口径逐级提到 .60 才全过 3:1 门，
 * 见 w2-glass.md 第八节）。硬口径第 2 条随之更新：小圆键接触影为 4dp / 黑 .28 单层。
 */
public class MusicGlassDrawable extends Drawable {

    // 面栈第 2 层·烟熏填充：#26282C（近黑微冷）真深色填充，alpha {.80,.84,.88}，停点 {0,.55,1}
    private static final int[] SMOKE = {0xCC26282C, 0xD626282C, 0xE026282C};
    // 面栈第 3 层·白 tint 收尾：白色 alpha .10（0x1A）平铺（定版 rgba(255,255,255,0.10)）
    private static final int[] OVERLAY = {0x1AFFFFFF, 0x1AFFFFFF, 0x1AFFFFFF};
    private static final float[] OVERLAY_STOPS = {0f, 0.55f, 1f};
    // 可见下限层（取样失败时满铺）：深烟熏渐变，与正脸同家族、无采样也成深件
    private static final int[] FALLBACK = {0xFF4A4A4E, 0xFF333336, 0xFF262628};
    // 135° 斜向流光：白 {.45 @0, .08 @.28, 0 @.58}（定版口径；src-over 近似 screen）
    private static final int[] SHEEN = {0x73FFFFFF, 0x14FFFFFF, 0x00FFFFFF, 0x00FFFFFF};
    private static final float[] SHEEN_STOPS = {0f, 0.28f, 0.58f, 1f};
    // 面栈第 1 层·采样糊底处理：提饱和 1.75＋提亮 ×1.10（定版口径；外部 setColorFilter 非空时以外部为准）
    private static final float TINT_SATURATION = 1.75f;
    private static final float TINT_BRIGHTEN = 1.10f;
    private static final ColorFilter TINT_FILTER = buildTintFilter();

    private static ColorFilter buildTintFilter() {
        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
        cm.setSaturation(TINT_SATURATION);
        float b = TINT_BRIGHTEN;
        cm.postConcat(new android.graphics.ColorMatrix(new float[]{
                b, 0, 0, 0, 0,
                0, b, 0, 0, 0,
                0, 0, b, 0, 0,
                0, 0, 0, 1, 0}));
        return new android.graphics.ColorMatrixColorFilter(cm);
    }
    // 基础环：1dp 纵向渐变 {顶 .30, 中 .20, 底 .14}（定版边框白 18–30% 区间；顶实线由镜面亮线承担）
    private static final int[] STROKE = {0x4DFFFFFF, 0x33FFFFFF, 0x24FFFFFF};
    private static final int[] STROKE_PRESSED = {0x66FFFFFF, 0x40FFFFFF, 0x2EFFFFFF};
    private static final float[] STROKE_STOPS = {0f, 0.55f, 1f};
    // 顶部镜面亮线：白 .85（按压 .95）、1.5dp，只走顶边＋两顶角（draw 里按带高裁剪放行）
    private static final int SPEC_COLOR = 0xD9FFFFFF;
    private static final int SPEC_COLOR_PRESSED = 0xF2FFFFFF;
    private static final float SPEC_WIDTH_DP = 1.5f;

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
    private final Paint mSmokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mOverlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mContactPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSpecPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int mToneColor = 0; // 0=无染色；非 0 时平铺一层（烟熏之上、tint 之下，alpha 由 setToneColor 定）

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
        mSpecPaint.setStyle(Paint.Style.STROKE);
        mSpecPaint.setStrokeWidth(SPEC_WIDTH_DP * mDensity);
        mSpecPaint.setColor(SPEC_COLOR);
        mPressPaint.setColor(0x2EFFFFFF);
        mBitmapPaint.setColorFilter(TINT_FILTER);
    }

    public boolean isCircle() {
        return mCircle;
    }

    /** tone 染色（心情卡卡底用）：color=0 清除染色恢复原样；非 0 时平铺一层、alpha 0.16。 */
    public void setToneColor(int color) {
        setToneColor(color, 0.16f);
    }

    /** tone 染色（带 alpha）：选中族带色玻璃用更足的 alpha 让颜色读得出（样张选中青口径）。 */
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
            mSpecPaint.setColor(pressed ? SPEC_COLOR_PRESSED : SPEC_COLOR);
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
        mSmokePaint.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                SMOKE, OVERLAY_STOPS, Shader.TileMode.CLAMP));
        mOverlayPaint.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                OVERLAY, OVERLAY_STOPS, Shader.TileMode.CLAMP));
        mFallbackPaint.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                FALLBACK, OVERLAY_STOPS, Shader.TileMode.CLAMP));
        // 135° 斜向流光：左上→右下对角（定版停点 {.45@0, .08@.28, 0@.58}）
        mSheenPaint.setShader(new LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom,
                SHEEN, SHEEN_STOPS, Shader.TileMode.CLAMP));
        // 底部内软影：黑 .30 向上羽化 18dp（小圆键不设、不画）
        float sh = 18f * mDensity;
        mShadowPaint.setShader(new LinearGradient(0, bounds.bottom - sh, 0, bounds.bottom,
                new int[]{0x00000000, 0x4D000000}, null, Shader.TileMode.CLAMP));
        // 底沿接触影：4dp / 黑 .28，全件共用一层
        float ch = 4f * mDensity;
        mContactPaint.setShader(new LinearGradient(0, bounds.bottom - ch, 0, bounds.bottom,
                new int[]{0x00000000, 0x47000000}, null, Shader.TileMode.CLAMP));
        rebuildStrokeShader(bounds);
    }

    private void rebuildStrokeShader(Rect b) {
        if (b.isEmpty()) return;
        // 基础环：纵向渐变 {顶 .30, 中 .20, 底 .14}（按压切高亮组）
        mStrokePaint.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                mPressed ? STROKE_PRESSED : STROKE, STROKE_STOPS, Shader.TileMode.CLAMP));
    }

    /** 按给定内缩画整圈圆角环（基础环与顶部镜面亮线共用同一路径口径）。 */
    private void drawRing(Canvas canvas, Rect b, Paint paint, float inset) {
        mTmp.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
        float r = Math.max(0, mRadiusPx - inset);
        if (mTopOnly) {
            Path p = new Path();
            p.addRoundRect(mTmp, new float[]{r, r, r, r, 0, 0, 0, 0}, Path.Direction.CW);
            canvas.drawPath(p, paint);
        } else {
            canvas.drawRoundRect(mTmp, r, r, paint);
        }
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
        canvas.drawRect(mTmp, mSmokePaint);            // 烟熏填充（真深色面，面栈第 2 层）
        if (mToneColor != 0) canvas.drawRect(mTmp, mTonePaint); // tone 染色（tint 之下）
        canvas.drawRect(mTmp, mOverlayPaint);          // 白 tint .10 收尾（面栈第 3 层）
        canvas.drawRect(mTmp, mSheenPaint);            // 135° 斜向流光
        if (!mCircle) {
            canvas.drawRect(mTmp, mShadowPaint);       // 底部内软影（大件分层）
        }
        canvas.drawRect(mTmp, mContactPaint);          // 唯一一层接触影（小圆键只有它）
        if (mPressed) canvas.drawRect(mTmp, mPressPaint); // 按压发亮
        canvas.restore();

        // 基础环：界内缩半宽画，避免被圆角裁掉半边
        drawRing(canvas, b, mStrokePaint, mStrokePaint.getStrokeWidth() / 2f);
        // 顶部镜面亮线：同路径只放行顶部带（带高＝圆角＋线宽、封顶件高 45%），
        // 顶边与两顶角成近实心亮线、向两侧自然收口（圆键件同理只亮顶弧）
        float band = Math.min(mRadiusPx + mSpecPaint.getStrokeWidth(), b.height() * 0.45f);
        canvas.save();
        canvas.clipRect(b.left, b.top, b.right, b.top + band);
        drawRing(canvas, b, mSpecPaint, mSpecPaint.getStrokeWidth() / 2f);
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
        mSmokePaint.setAlpha(alpha);
        mOverlayPaint.setAlpha(alpha);
        mSheenPaint.setAlpha(alpha);
        mShadowPaint.setAlpha(alpha);
        mContactPaint.setAlpha(alpha);
        mStrokePaint.setAlpha(alpha);
        mSpecPaint.setAlpha(alpha);
        mPressPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        // 糊底画笔：外部滤镜非空以外部为准，清空时回定版 tint（饱和 1.75＋提亮 1.10）
        mBitmapPaint.setColorFilter(colorFilter != null ? colorFilter : TINT_FILTER);
        mFallbackPaint.setColorFilter(colorFilter);
        mSmokePaint.setColorFilter(colorFilter);
        mOverlayPaint.setColorFilter(colorFilter);
        mSheenPaint.setColorFilter(colorFilter);
        mShadowPaint.setColorFilter(colorFilter);
        mContactPaint.setColorFilter(colorFilter);
        mStrokePaint.setColorFilter(colorFilter);
        mSpecPaint.setColorFilter(colorFilter);
        mPressPaint.setColorFilter(colorFilter);
        mTonePaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
