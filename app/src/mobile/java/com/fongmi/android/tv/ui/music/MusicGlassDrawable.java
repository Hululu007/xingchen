package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;

/**
 * 音乐液态玻璃件（2026-10-25 晚按潇洒哥令「抄他的液态玻璃」换 AVBox 的配方：
 * XiaochangXu/AVBox 用的 Kyant backdrop 库，本类照其效果链在 View 体系复刻）。
 * 糊底机制沿用自家采样：从 MusicGlassBackgroundView 的 1/4 共享缓冲按宿主位置裁剪。
 *
 * AVBox 效果链（FloatingNavBar 的 drawBackdrop 口径 + LiquidGlassConfig 默认值）：
 * 1) colorControls：saturation 1.5、brightness (0.5−t)·0.12、contrast 1−(0.5−t)·0.25，
 *    t=translucency 取 0.35（AVBox 默认 0.5；星辰字全白、底更亮，收一档保证对比），
 *    矩阵照 backdrop 的 colorControlsColorFilter 逐项（亮度权重 .213/.715/.072、
 *    偏移 t=(0.5−c·0.5+b)·255）；2026-10-26 抗卡改：矩阵由 MusicGlassBackgroundView
 *    一次性烤进处理缓冲，本类裁处理缓冲、不再每件每帧过一遍滤镜（外部滤镜另设时例外）；
 * 2) blur：采样缓冲 1/4 缩放自带糊度（AVBox blur 5dp 的等效量级，不再另叠）；
 * 3) lens 折射：AGSL 圆角矩形折射着色器（backdrop 的 RoundedRectRefraction +
 *    色散版逐字移植），refractionHeight/Amount = AVBox distortion 默认 30dp
 *    （几何上限取件小边一半），depthEffect 关、chromaticAberration 开（AVBox 默认开）；
 *    走 android.graphics.RuntimeShader（API 33+），低版本/编译失败回落直通采样；
 * 4) 容器 tint：AVBox containerColor = surfaceContainer @ (0.4 × containerAlphaScale)，
 *    containerAlphaScale = 2−2t = 1.3 → alpha .52，色取深色面 #1E2023 平铺
 *    （星辰音乐页字全白令，取 AVBox 深色主题的面色家族）；
 * 5) Highlight.Default：白 .5、45°、falloff 1、宽 0.5dp、Plus 叠加——AGSL
 *    DefaultHighlight 着色器逐字移植描边；无 RuntimeShader 时回落旧环线近似；
 * 6) 阴影：backdrop 的 Shadow 是件外投影，背景 Drawable 画不出界外，沿用内影近似
 *    （底部内软影 18dp/黑 .30＋接触影 4dp/黑 .28，CSS 0 8px 32px rgba(0,0,0,.25–.32) 量级）。
 * 选中族 tone 染色（MusicGlassWire 挂 #305F67 @ .55）与前景翻白口径不变。
 * 取样为空/未就绪时走下限面（深色渐变满铺），任何底上都成形。
 */
public class MusicGlassDrawable extends Drawable {

    // 容器 tint：#1E2023 @ .40 平铺（2026-10-26 他点「玻璃一直是那个颜色」后由 .52 收一档：
    // tint 只压底保证白字，颜色主体让位给取样——取样变它就变，不许 tint 一家独大定格成色）
    private static final int TINT_COLOR = 0x661E2023;
    // 可见下限层（取样失败时满铺）：与统一暖底同家族的暖棕渐变（首帧与暖底同色系，不闪深灰）
    private static final int[] FALLBACK = {0xFF8A6844, 0xFF6E4E30, 0xFF4B3926};
    private static final float[] FALLBACK_STOPS = {0f, 0.55f, 1f};
    // AVBox lens 默认：distortion 30dp（高与量同值）
    private static final float REFRACTION_DP = 30f;

    private static final String SDF_AGSL =
            "float radiusAt(float2 coord, float4 radii) {\n" +
            "    if (coord.x >= 0.0) {\n" +
            "        if (coord.y <= 0.0) return radii.y;\n" +
            "        else return radii.z;\n" +
            "    } else {\n" +
            "        if (coord.y <= 0.0) return radii.x;\n" +
            "        else return radii.w;\n" +
            "    }\n" +
            "}\n" +
            "float sdRoundedRect(float2 coord, float2 halfSize, float radius) {\n" +
            "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n" +
            "    float outside = length(max(cornerCoord, 0.0)) - radius;\n" +
            "    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);\n" +
            "    return outside + inside;\n" +
            "}\n" +
            "float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {\n" +
            "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n" +
            "    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {\n" +
            "        return sign(coord) * normalize(max(cornerCoord, 0.0));\n" +
            "    } else {\n" +
            "        float gradX = step(cornerCoord.y, cornerCoord.x);\n" +
            "        return sign(coord) * float2(gradX, 1.0 - gradX);\n" +
            "    }\n" +
            "}\n";

    // backdrop RoundedRectRefractionWithDispersion 逐字移植（depthEffect 恒 0 时与无色散版同形）
    private static final String REFRACTION_AGSL =
            "uniform shader content;\n" +
            "uniform float2 size;\n" +
            "uniform float2 offset;\n" +
            "uniform float4 cornerRadii;\n" +
            "uniform float refractionHeight;\n" +
            "uniform float refractionAmount;\n" +
            "uniform float depthEffect;\n" +
            "uniform float chromaticAberration;\n" +
            SDF_AGSL +
            "float circleMap(float x) { return 1.0 - sqrt(1.0 - x * x); }\n" +
            "half4 main(float2 coord) {\n" +
            "    float2 halfSize = size * 0.5;\n" +
            "    float2 centeredCoord = (coord + offset) - halfSize;\n" +
            "    float radius = radiusAt(coord, cornerRadii);\n" +
            "    float sd = sdRoundedRect(centeredCoord, halfSize, radius);\n" +
            "    if (-sd >= refractionHeight) { return content.eval(coord); }\n" +
            "    sd = min(sd, 0.0);\n" +
            "    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;\n" +
            "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n" +
            "    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius)" +
            "        + depthEffect * normalize(centeredCoord));\n" +
            "    float2 refractedCoord = coord + d * grad;\n" +
            "    float dispersionIntensity = chromaticAberration *" +
            "        ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));\n" +
            "    float2 dispersedCoord = d * grad * dispersionIntensity;\n" +
            "    half4 color = half4(0.0);\n" +
            "    half4 red = content.eval(refractedCoord + dispersedCoord);\n" +
            "    color.r += red.r / 3.5;\n" +
            "    color.a += red.a / 7.0;\n" +
            "    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));\n" +
            "    color.r += orange.r / 3.5;\n" +
            "    color.g += orange.g / 7.0;\n" +
            "    color.a += orange.a / 7.0;\n" +
            "    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));\n" +
            "    color.r += yellow.r / 3.5;\n" +
            "    color.g += yellow.g / 3.5;\n" +
            "    color.a += yellow.a / 7.0;\n" +
            "    half4 green = content.eval(refractedCoord);\n" +
            "    color.g += green.g / 3.5;\n" +
            "    color.a += green.a / 7.0;\n" +
            "    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));\n" +
            "    color.g += cyan.g / 3.5;\n" +
            "    color.b += cyan.b / 3.0;\n" +
            "    color.a += cyan.a / 7.0;\n" +
            "    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));\n" +
            "    color.b += blue.b / 3.0;\n" +
            "    color.a += blue.a / 7.0;\n" +
            "    half4 purple = content.eval(refractedCoord - dispersedCoord);\n" +
            "    color.r += purple.r / 7.0;\n" +
            "    color.b += purple.b / 3.0;\n" +
            "    color.a += purple.a / 7.0;\n" +
            "    return color;\n" +
            "}";

    // backdrop DefaultHighlight 逐字移植（color 由 setColorUniform 喂入，alpha 在画笔侧 .5）
    private static final String HIGHLIGHT_AGSL =
            "uniform float2 size;\n" +
            "uniform float4 cornerRadii;\n" +
            "layout(color) uniform half4 color;\n" +
            "uniform float angle;\n" +
            "uniform float falloff;\n" +
            SDF_AGSL +
            "half4 main(float2 coord) {\n" +
            "    float2 halfSize = size * 0.5;\n" +
            "    float2 centeredCoord = coord - halfSize;\n" +
            "    float radius = radiusAt(coord, cornerRadii);\n" +
            "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n" +
            "    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);\n" +
            "    float2 normal = float2(cos(angle), sin(angle));\n" +
            "    float d = dot(grad, normal);\n" +
            "    float intensity = pow(abs(d), falloff);\n" +
            "    return color * intensity;\n" +
            "}";

    private static Boolean sShaderOk;

    private static boolean shaderSupported() {
        if (sShaderOk != null) return sShaderOk;
        boolean ok = false;
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                RuntimeShader probe = new RuntimeShader(REFRACTION_AGSL);
                ok = probe != null;
            } catch (Throwable ignored) {
                ok = false;
            }
        }
        sShaderOk = ok;
        return ok;
    }

    // 无着色器时的回落环线（2026-10-26 显形轮提亮：样张口径＝整圈连续亮边、顶边最亮勾住整颗；
    // 旧值 {顶 .30, 中 .20, 底 .14} 在暖底上读不出，提为 {顶 .55, 中 .34, 底 .22}＋顶镜面线 .85）
    private static final int[] STROKE = {0x8CFFFFFF, 0x57FFFFFF, 0x38FFFFFF};
    private static final int[] STROKE_PRESSED = {0xB3FFFFFF, 0x75FFFFFF, 0x4DFFFFFF};
    private static final float[] STROKE_STOPS = {0f, 0.55f, 1f};
    private static final int SPEC_COLOR = 0xD9FFFFFF;
    private static final int SPEC_COLOR_PRESSED = 0xF2FFFFFF;
    private static final float SPEC_WIDTH_DP = 1.5f;

    private static volatile int sEpoch = 0;

    /** 列表滚动等宿主位置变化场景调用：令全部玻璃件下一帧重裁糊底。 */
    public static void noteScrollChanged() {
        sEpoch++;
    }

    // 滚动进行中标记（接线层滚动钩子喂）：滚动中暂停折射/高光两道全幅着色器——
    // 那是每帧最贵的部分；取样重裁照常实时跟随（他 00:17 令），停下钩子复位后下一帧恢复。
    private static volatile boolean sScrollActive = false;

    public static void noteScrollActive(boolean active) {
        sScrollActive = active;
    }

    private final float mRadiusPx;
    private final float mDensity;
    private final boolean mTopOnly;
    private final boolean mCircle;

    private final Paint mBitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint mProcessedPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint mLensPaint = new Paint();
    private final Paint mFallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mContactPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSpecPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private RuntimeShader mLensShader;
    private RuntimeShader mHighlightShader;
    private boolean mLensDead;
    private boolean mHighlightDead;

    private Bitmap mProcessed;
    private int mProcessedW, mProcessedH;
    private ColorFilter mExternalFilter;

    private int mToneColor = 0;

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
    private boolean mProcessedDirty = true;
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
        mTintPaint.setColor(TINT_COLOR);
        // 取样画笔不再挂 colorControls：已由背景缓冲预烤（见类头注 1）；外部滤镜经 setColorFilter 另设
        mHighlightPaint.setStyle(Paint.Style.STROKE);
        mHighlightPaint.setStrokeWidth(0.75f * mDensity);
        mHighlightPaint.setAlpha(204);
        mHighlightPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
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
        mProcessedDirty = true;
        mFallbackPaint.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                FALLBACK, FALLBACK_STOPS, Shader.TileMode.CLAMP));
        float sh = 18f * mDensity;
        mShadowPaint.setShader(new LinearGradient(0, bounds.bottom - sh, 0, bounds.bottom,
                new int[]{0x00000000, 0x4D000000}, null, Shader.TileMode.CLAMP));
        float ch = 4f * mDensity;
        mContactPaint.setShader(new LinearGradient(0, bounds.bottom - ch, 0, bounds.bottom,
                new int[]{0x00000000, 0x47000000}, null, Shader.TileMode.CLAMP));
        // 顶部流光带（样张「顶部流光」：一道自顶边向下衰减的镜面光，面状、不是线）：
        // 高度取件高 30%、封顶 28dp，白 .18 → 0
        float sheenH = Math.min(bounds.height() * 0.30f, 28f * mDensity);
        if (sheenH > 0) {
            mSheenPaint.setShader(new LinearGradient(0, bounds.top, 0, bounds.top + sheenH,
                    new int[]{0x2EFFFFFF, 0x00000000}, null, Shader.TileMode.CLAMP));
        }
        rebuildStrokeShader(bounds);
    }

    private void rebuildStrokeShader(Rect b) {
        if (b.isEmpty()) return;
        mStrokePaint.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                mPressed ? STROKE_PRESSED : STROKE, STROKE_STOPS, Shader.TileMode.CLAMP));
    }

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

    private float[] cornerRadii(Rect b) {
        float r = Math.min(mRadiusPx, Math.min(b.width(), b.height()) / 2f);
        if (mTopOnly) return new float[]{r, r, 0f, 0f};
        return new float[]{r, r, r, r};
    }

    /** 把采样区渲成本件尺寸的过程图（colorControls 已烙入），供折射着色器取样/或直通绘制。 */
    private void ensureProcessed(Rect b, Bitmap buf) {
        int w = b.width(), h = b.height();
        if (w <= 0 || h <= 0 || buf == null) return;
        if (!mProcessedDirty && mProcessed != null && !mProcessed.isRecycled()
                && mProcessedW == w && mProcessedH == h) return;
        if (mProcessed == null || mProcessedW != w || mProcessedH != h) {
            if (mProcessed != null) mProcessed.recycle();
            mProcessed = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            mProcessedW = w;
            mProcessedH = h;
        }
        Canvas c = new Canvas(mProcessed);
        mDst.set(0, 0, w, h);
        c.drawBitmap(buf, mSrc, mDst, mBitmapPaint);
        mProcessedDirty = false;
    }

    private boolean drawLensBackdrop(Canvas canvas, Rect b) {
        if (mLensDead || !shaderSupported() || mProcessed == null) return false;
        try {
            if (mLensShader == null) mLensShader = new RuntimeShader(REFRACTION_AGSL);
            BitmapShader input = new BitmapShader(mProcessed, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            mLensShader.setInputShader("content", input);
            mLensShader.setFloatUniform("size", (float) b.width(), (float) b.height());
            mLensShader.setFloatUniform("offset", 0f, 0f);
            mLensShader.setFloatUniform("cornerRadii", cornerRadii(b));
            float cap = Math.min(b.width(), b.height()) / 2f;
            float refr = Math.min(REFRACTION_DP * mDensity, cap);
            mLensShader.setFloatUniform("refractionHeight", refr);
            mLensShader.setFloatUniform("refractionAmount", -refr);
            mLensShader.setFloatUniform("depthEffect", 0f);
            mLensShader.setFloatUniform("chromaticAberration", 1f);
            mLensPaint.setShader(mLensShader);
            canvas.save();
            canvas.translate(-b.left, -b.top);
            canvas.drawRect(b, mLensPaint);
            canvas.restore();
            mLensPaint.setShader(null);
            return true;
        } catch (Throwable t) {
            mLensDead = true;
            mLensPaint.setShader(null);
            return false;
        }
    }

    private boolean drawHighlightShader(Canvas canvas, Rect b) {
        if (mHighlightDead || !shaderSupported()) return false;
        try {
            if (mHighlightShader == null) mHighlightShader = new RuntimeShader(HIGHLIGHT_AGSL);
            mHighlightShader.setFloatUniform("size", (float) b.width(), (float) b.height());
            mHighlightShader.setFloatUniform("cornerRadii", cornerRadii(b));
            mHighlightShader.setColorUniform("color", 0xFFFFFFFF);
            mHighlightShader.setFloatUniform("angle", (float) (45.0 * Math.PI / 180.0));
            mHighlightShader.setFloatUniform("falloff", 1f);
            mHighlightPaint.setShader(mHighlightShader);
            canvas.save();
            canvas.translate(-b.left, -b.top);
            drawRing(canvas, b, mHighlightPaint, mHighlightPaint.getStrokeWidth() / 2f);
            canvas.restore();
            mHighlightPaint.setShader(null);
            return true;
        } catch (Throwable t) {
            // 只关高光自己：旧实现与折射共用 mLensDead，高光一次构造失败把折射一起杀掉、
            // 只剩最弱回落环——「玻璃不明显」的一条暗因，拆开互不连坐
            mHighlightDead = true;
            mHighlightPaint.setShader(null);
            return false;
        }
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        mTmp.set(b);

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
            mProcessedDirty = true;
        }

        canvas.save();
        canvas.clipPath(buildClip(mTmp));
        boolean lensDrawn = false;
        if (mLastOk) {
            // 取样源：无外部滤镜时用已烤 colorControls 的处理缓冲（免每帧滤镜整图重渲）；
            // 外部滤镜在场时回原图＋滤镜（与旧行为一致：外部滤镜取代 colorControls）
            Bitmap buf = null;
            if (mExternalFilter == null) buf = MusicGlassBackgroundView.getProcessedBuffer();
            if (buf == null) buf = MusicGlassBackgroundView.getBuffer();
            if (buf != null && !buf.isRecycled()) {
                ensureProcessed(b, buf);
                // 滚动中不跑折射着色器（全幅 AGSL 是每帧最贵的一道），停下自动恢复
                if (!sScrollActive) lensDrawn = drawLensBackdrop(canvas, b);
                if (!lensDrawn && mProcessed != null) {
                    canvas.drawBitmap(mProcessed, b.left, b.top, mProcessedPaint);
                }
            } else {
                canvas.drawRect(mTmp, mFallbackPaint);
            }
        } else {
            canvas.drawRect(mTmp, mFallbackPaint);
        }
        canvas.drawRect(mTmp, mTintPaint);             // 容器 tint（AVBox 面色 @ .52）
        if (mToneColor != 0) canvas.drawRect(mTmp, mTonePaint);
        canvas.drawRect(mTmp, mSheenPaint);            // 顶部流光带（着色器按件高封顶 28dp，余下为 0）
        if (!mCircle) {
            canvas.drawRect(mTmp, mShadowPaint);       // 底部内软影（大件分层）
        }
        canvas.drawRect(mTmp, mContactPaint);          // 接触影（小圆键只有它）
        if (mPressed) canvas.drawRect(mTmp, mPressPaint);
        canvas.restore();

        // Highlight.Default 着色器描边（Plus 叠加）；无着色器/滚动中回落环线。
        // 顶镜面线两条路都画（样张顶边最亮、近白勾边）：裁在顶带内只露环的顶部一段
        if (sScrollActive || !drawHighlightShader(canvas, b)) {
            drawRing(canvas, b, mStrokePaint, mStrokePaint.getStrokeWidth() / 2f);
        }
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
        mProcessedPaint.setAlpha(alpha);
        mFallbackPaint.setAlpha(alpha);
        mTintPaint.setAlpha(alpha);
        mShadowPaint.setAlpha(alpha);
        mContactPaint.setAlpha(alpha);
        mStrokePaint.setAlpha(alpha);
        mSpecPaint.setAlpha(alpha);
        mPressPaint.setAlpha(alpha);
        mSheenPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        // 糊底画笔：外部滤镜非空以外部为准（取样回原图）；清空时用预烤处理缓冲（colorControls 在缓冲里）
        mExternalFilter = colorFilter;
        mBitmapPaint.setColorFilter(colorFilter);
        mProcessedDirty = true;
        mFallbackPaint.setColorFilter(colorFilter);
        mTintPaint.setColorFilter(colorFilter);
        mShadowPaint.setColorFilter(colorFilter);
        mContactPaint.setColorFilter(colorFilter);
        mStrokePaint.setColorFilter(colorFilter);
        mSpecPaint.setColorFilter(colorFilter);
        mPressPaint.setColorFilter(colorFilter);
        mTonePaint.setColorFilter(colorFilter);
        mSheenPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
