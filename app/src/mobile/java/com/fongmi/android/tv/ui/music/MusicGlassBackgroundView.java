package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 音乐全域统一背景（全音乐面一套，不许各页自编）。
 *
 * 现行口径（2026-10-25 深夜他终定，拼图 113238 中间那个播放器）：整页底＝封面重糊的氛围底——
 * 页面真实封面（继续播放／最近队列那批）拼成马赛克、以 1/10 尺寸渲再放大＝天然重模糊，
 * 上面压一层薄黑罩沉底；玻璃件压上去咬这层糊色显形。没有封面可喂时走深色兜底色场
 * （近黑底＋四团深色光），不许再回浅色极光（旧浅色口径同晚作废，他连否三版）。
 *
 * 启用范围：深底走 opt-in——只有前景已翻白的那一页调 useDarkAmbient(true)（当前仅音乐首页）。
 * 其余音乐页前景还是浅色设计（批三逐页从零时再翻），默认仍画旧浅色场，免得深底压死它们的深字。
 *
 * 氛围态进程共享：首页喂一次封面，各音乐页同一张底；换队列／换批封面才重渲。
 * 共享 1/4 缓冲机制不变：onSizeChanged 把同一场景渲进小图、版本号 +1，
 * MusicGlassDrawable 按宿主窗口位置裁剪作糊底（缓冲里装的就是这张糊底，玻璃咬得到）。
 */
public class MusicGlassBackgroundView extends View {

    // ---------- 浅色场（未启用深底的页，旧口径原样保留） ----------
    public static final int LIGHT_BASE_COLOR = 0xFFEAEAF0;
    public static final int[] LIGHT_SPOT_RGB = {0xEC81B5, 0x9776EC, 0x68CCA8, 0xEC81B5};
    public static final float[] LIGHT_SPOT_ALPHA = {0.84f, 0.84f, 0.80f, 0.76f};

    // ---------- 深色兜底色场（无封面时）：近黑底＋深宝石色光斑 ----------
    public static final int BASE_COLOR = 0xFF121218;
    public static final float[] SPOT_X = {0.14f, 0.86f, 0.16f, 0.88f};
    public static final float[] SPOT_Y = {0.10f, 0.30f, 0.84f, 0.90f};
    public static final float[] SPOT_DIAMETER_DP = {455f, 435f, 415f, 395f};
    public static final int[] SPOT_RGB = {0x7A2350, 0x3E2472, 0x14586B, 0x7A4A16};
    public static final float[] SPOT_ALPHA = {0.62f, 0.62f, 0.58f, 0.54f};
    public static final float[] SPOT_STOPS = {0f, 0.55f, 1f};
    public static final float[] SPOT_ALPHA_FACTORS = {1f, 0.45f, 0f};

    // 氛围底：马赛克渲染缩放（越小越糊）与黑罩 alpha
    private static final int AMBIENT_SCALE = 10;
    private static final int AMBIENT_VEIL_ALPHA = 0x3A;

    private static final int DOWNSAMPLE = 4;

    // ---------- 共享缓冲（进程单例态） ----------
    private static final Object LOCK = new Object();
    private static Bitmap sBuffer;
    private static int sBufferW, sBufferH;
    private static int sBufferVersion = 0;
    private static boolean sBufferDark;

    // ---------- 氛围封面（进程共享） ----------
    private static final List<Bitmap> sAmbient = new ArrayList<>();
    private static String sAmbientKey = "";
    private static int sAmbientVersion = 0;
    private static Bitmap sAmbientSmall;
    private static int sAmbientSmallW, sAmbientSmallH, sAmbientSmallVer = -1;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private int mLastW, mLastH;
    private boolean mDarkAmbient;

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

    /** 启用深底氛围（前景已翻白的页才调）；开启后立即按现尺寸重渲缓冲与画面。 */
    public void useDarkAmbient(boolean dark) {
        if (mDarkAmbient == dark) return;
        mDarkAmbient = dark;
        if (mLastW > 0 && mLastH > 0) renderBuffer(mLastW, mLastH);
        invalidate();
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

    /** 当前共享缓冲是不是深底页渲的（玻璃件据此选烟熏/浅色 tint）。 */
    public static boolean isBufferDark() {
        synchronized (LOCK) {
            return sBufferDark;
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

    // ---------- 氛围封面喂入 ----------

    /**
     * 喂入本页真实封面地址（最多取 6 张）：全部到位后拼成氛围底并重渲。
     * 同一批地址重复喂入直接跳过；单张失败不挡整批（失败计入到位）。
     */
    public void setAmbientCoverUrls(List<String> urls) {
        if (urls == null) return;
        List<String> clean = new ArrayList<>();
        for (String u : urls) {
            if (u != null && !u.trim().isEmpty() && !clean.contains(u)) clean.add(u);
            if (clean.size() >= 6) break;
        }
        if (clean.isEmpty()) return;
        String key = String.join("\n", clean);
        synchronized (LOCK) {
            if (key.equals(sAmbientKey)) return;
            sAmbientKey = key;
        }
        final List<Bitmap> got = new ArrayList<>();
        final int[] settled = {0};
        final int total = clean.size();
        for (String u : clean) {
            try {
                ImgUtil.load(u, 128, new CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(Bitmap resource) {
                        Bitmap hold = resource;
                        try {
                            Bitmap copy = resource.copy(
                                    resource.getConfig() != null ? resource.getConfig() : Bitmap.Config.ARGB_8888, false);
                            if (copy != null) hold = copy;
                        } catch (Throwable ignored) {
                        }
                        synchronized (got) {
                            got.add(hold);
                        }
                        settleAmbient(got, settled, total);
                    }

                    @Override
                    public void onLoadFailed(android.graphics.drawable.Drawable errorDrawable) {
                        settleAmbient(got, settled, total);
                    }
                });
            } catch (Throwable ignored) {
                settleAmbient(got, settled, total);
            }
        }
    }

    private void settleAmbient(List<Bitmap> got, int[] settled, int total) {
        boolean done;
        synchronized (got) {
            settled[0]++;
            done = settled[0] >= total;
        }
        if (!done) return;
        List<Bitmap> ready;
        synchronized (got) {
            ready = new ArrayList<>(got);
        }
        if (ready.isEmpty()) return;
        synchronized (LOCK) {
            sAmbient.clear();
            sAmbient.addAll(ready);
            sAmbientVersion++;
            sAmbientSmallVer = -1;
        }
        post(() -> {
            if (mLastW > 0 && mLastH > 0) renderBuffer(mLastW, mLastH);
            invalidate();
        });
    }

    // ---------- 绘制 ----------

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // 共享缓冲跟前景页走：回到本页时按本页口径重渲，免得玻璃采样到别页的底
        if (mLastW > 0 && mLastH > 0) renderBuffer(mLastW, mLastH);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            mLastW = w;
            mLastH = h;
            renderBuffer(w, h);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float density = getResources().getDisplayMetrics().density;
        drawScene(canvas, w, h, density, mDarkAmbient);
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
            drawScene(c, bw, bh, density, mDarkAmbient);
            sBufferDark = mDarkAmbient;
            sBufferVersion++;
            if (old != null && old != sBuffer) old.recycle();
        }
    }

    /**
     * 场景合成。深底页：有氛围封面＝马赛克小图放大（重模糊）＋黑罩，没有＝深色兜底色场；
     * 未启用深底的页画旧浅色场（底色＋四团浅光），数值与旧口径一致。
     * 缓冲与实绘共用本方法，两边看到的是同一张底。
     */
    static void drawScene(Canvas canvas, int w, int h, float density, boolean darkAmbient) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (darkAmbient) {
            Bitmap small = ambientSmall(w, h);
            if (small != null) {
                canvas.drawBitmap(small, null, new Rect(0, 0, w, h), p);
                p.setShader(null);
                p.setColor((AMBIENT_VEIL_ALPHA << 24));
                canvas.drawRect(0, 0, w, h, p);
                return;
            }
        }
        p.setColor(darkAmbient ? BASE_COLOR : LIGHT_BASE_COLOR);
        canvas.drawRect(0, 0, w, h, p);
        int[] rgbs = darkAmbient ? SPOT_RGB : LIGHT_SPOT_RGB;
        float[] alphas = darkAmbient ? SPOT_ALPHA : LIGHT_SPOT_ALPHA;
        for (int i = 0; i < SPOT_X.length; i++) {
            float cx = SPOT_X[i] * w;
            float cy = SPOT_Y[i] * h;
            float r = SPOT_DIAMETER_DP[i] * density / 2f;
            if (r <= 0) continue;
            int rgb = rgbs[i];
            int a0 = Math.round(255f * alphas[i]);
            int a1 = Math.round(255f * alphas[i] * SPOT_ALPHA_FACTORS[1]);
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

    /** 氛围马赛克小图（按目标尺寸缓存）：封面两列平铺、逐格居中裁满，1/10 尺寸渲＝放大即重模糊。 */
    private static Bitmap ambientSmall(int w, int h) {
        synchronized (LOCK) {
            if (sAmbient.isEmpty()) return null;
            if (sAmbientSmall != null && !sAmbientSmall.isRecycled()
                    && sAmbientSmallW == w && sAmbientSmallH == h && sAmbientSmallVer == sAmbientVersion) {
                return sAmbientSmall;
            }
            int sw = Math.max(2, w / AMBIENT_SCALE);
            int sh = Math.max(2, h / AMBIENT_SCALE);
            Bitmap small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(small);
            c.drawColor(0xFF121218);
            int n = sAmbient.size();
            int cols = n <= 1 ? 1 : 2;
            int rows = (n + cols - 1) / cols;
            int cellW = sw / cols;
            int cellH = sh / rows;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            for (int i = 0; i < n; i++) {
                Bitmap b = sAmbient.get(i);
                if (b == null || b.isRecycled()) continue;
                int cx = (i % cols) * cellW;
                int cy = (i / cols) * cellH;
                float scale = Math.max((float) cellW / b.getWidth(), (float) cellH / b.getHeight());
                int dw = Math.round(b.getWidth() * scale);
                int dh = Math.round(b.getHeight() * scale);
                int dx = cx + (cellW - dw) / 2;
                int dy = cy + (cellH - dh) / 2;
                c.drawBitmap(b, null, new Rect(dx, dy, dx + dw, dy + dh), p);
            }
            Bitmap old = sAmbientSmall;
            sAmbientSmall = small;
            sAmbientSmallW = w;
            sAmbientSmallH = h;
            sAmbientSmallVer = sAmbientVersion;
            if (old != null && old != small) old.recycle();
            return small;
        }
    }
}
