package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * 音乐全域统一背景（W2 材料层从零重写，全音乐面一套，不许各页自编）。
 *
 * 场景＝近白底＋四团径向柔光斑，静态（无漂移帧）。
 * 亮度口径（1.4.29 回归清单第 4 条销账）：以 1.4.28 为基准全场真降一档——
 * 近白底与光斑一起压，不许只改光斑色停：
 *   底色 #FAFAFC → #F1F1F4；
 *   粉 #FFB9D9 → #F09FC6、紫 #C9B8FF → #AE97F2、薄荷 #A9E8D8 → #8AD6BE；
 *   光斑中心 alpha .85/.85/.80/.78 → .80/.80/.75/.72，直径与位置不动（过渡连续、无中段斑驳）。
 * 全场平均亮度改前/改后数字由 JVM harness 复刻本类合成数学算出，写进 w2-glass.md 对照表。
 *
 * 同时维护进程共享 1/4 降采样缓冲（同源参照海阔 HkDynamicBgView 的缓冲机制）：
 * onSizeChanged 时把同一场景渲进小图、版本号 +1，MusicGlassDrawable 按宿主窗口位置裁剪作糊底。
 */
public class MusicGlassBackgroundView extends View {

    // ---------- 场景常数（harness 逐值镜像，改这里必须同步改 harness） ----------
    public static final int BASE_COLOR = 0xFFF1F1F4;
    // 光斑：中心位置（占宽/高比例）、直径 dp、RGB、中心 alpha
    public static final float[] SPOT_X = {0.14f, 0.86f, 0.16f, 0.88f};
    public static final float[] SPOT_Y = {0.10f, 0.30f, 0.84f, 0.90f};
    public static final float[] SPOT_DIAMETER_DP = {410f, 390f, 370f, 350f};
    public static final int[] SPOT_RGB = {0xF09FC6, 0xAE97F2, 0x8AD6BE, 0xF09FC6};
    public static final float[] SPOT_ALPHA = {0.80f, 0.80f, 0.75f, 0.72f};
    // 径向衰减停点与 alpha 系数：中心→中段平滑羽化→边缘归零（连续过渡）
    public static final float[] SPOT_STOPS = {0f, 0.55f, 1f};
    public static final float[] SPOT_ALPHA_FACTORS = {1f, 0.45f, 0f};

    private static final int DOWNSAMPLE = 4;

    // ---------- 共享缓冲（进程单例态） ----------
    private static final Object LOCK = new Object();
    private static Bitmap sBuffer;
    private static int sBufferW, sBufferH;
    private static int sBufferVersion = 0;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    public MusicGlassBackgroundView(Context context) {
        super(context);
        init();
    }

    public MusicGlassBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setWillNotDraw(false);
    }

    // ---------- 玻璃件用：共享缓冲访问 ----------
    public static boolean hasBuffer() {
        synchronized (LOCK) {
            return sBuffer != null && !sBuffer.isRecycled();
        }
    }

    public static Bitmap getBuffer() {
        synchronized (LOCK) {
            return sBuffer;
        }
    }

    public static int getBufferVersion() {
        synchronized (LOCK) {
            return sBufferVersion;
        }
    }

    public static int bufferWidth() {
        synchronized (LOCK) {
            return sBufferW;
        }
    }

    public static int bufferHeight() {
        synchronized (LOCK) {
            return sBufferH;
        }
    }

    /** 在视图树里按类型找背景 View（音乐各页 id 不一，不按 id 找）。找不到返回 null。 */
    public static MusicGlassBackgroundView findInTree(View root) {
        if (root == null) return null;
        if (root instanceof MusicGlassBackgroundView) return (MusicGlassBackgroundView) root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                MusicGlassBackgroundView v = findInTree(g.getChildAt(i));
                if (v != null) return v;
            }
        }
        return null;
    }

    // ---------- 绘制 ----------
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) renderBuffer(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float density = getResources().getDisplayMetrics().density;
        drawScene(canvas, w, h, density);
    }

    /** 把同一场景以 1/4 尺寸渲进共享缓冲，供玻璃件裁剪。 */
    private void renderBuffer(int w, int h) {
        int bw = Math.max(2, w / DOWNSAMPLE);
        int bh = Math.max(2, h / DOWNSAMPLE);
        synchronized (LOCK) {
            Bitmap old = sBuffer;
            sBuffer = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            sBufferW = bw;
            sBufferH = bh;
            float density = getResources().getDisplayMetrics().density / DOWNSAMPLE;
            Canvas c = new Canvas(sBuffer);
            drawScene(c, bw, bh, density);
            sBufferVersion++;
            if (old != null && old != sBuffer) old.recycle();
        }
    }

    /**
     * 场景合成（底色满铺＋四团径向光斑逐团 src-over 叠加）。
     * harness 按同一公式复刻：光斑内任一点的 alpha＝中心 alpha × 停点分段线性插值系数。
     */
    static void drawScene(Canvas canvas, int w, int h, float density) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(BASE_COLOR);
        canvas.drawRect(0, 0, w, h, p);
        for (int i = 0; i < SPOT_X.length; i++) {
            float cx = SPOT_X[i] * w;
            float cy = SPOT_Y[i] * h;
            float r = SPOT_DIAMETER_DP[i] * density / 2f;
            if (r <= 0) continue;
            int rgb = SPOT_RGB[i];
            int a0 = Math.round(255f * SPOT_ALPHA[i]);
            int a1 = Math.round(255f * SPOT_ALPHA[i] * SPOT_ALPHA_FACTORS[1]);
            int[] colors = {
                    (a0 << 24) | rgb,
                    (a1 << 24) | rgb,
                    rgb & 0x00FFFFFF
            };
            p.setShader(new RadialGradient(cx, cy, r, colors, SPOT_STOPS, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r, p);
        }
        p.setShader(null);
    }
}
