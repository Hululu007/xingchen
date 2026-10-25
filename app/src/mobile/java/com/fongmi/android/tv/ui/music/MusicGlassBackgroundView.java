package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * 音乐全域统一背景（批 5 F1，2026-10-24 皮与背景总口径的源头实现）：
 * 凡套液态玻璃的音乐页（首页／搜索／列表族／插件／设置等非播放页），背景一律用这一份，
 * 各页不许再自编背景。后批复用方式：在页面根布局最底层放一个本 View（match_parent），
 * 内容层背景透明即可；播放页／全屏歌词页／五套播放页主题是例外，不用本 View。
 *
 * 构成＝近白底 #FAFAFC ＋ 粉／紫／薄荷极光柔光（第十稿定稿：近白底＋粉紫薄荷极光柔光）。
 * 定稿原件（music-home-final-draft.html／v10 出图稿）在本机 theme-redo 目录缺失，
 * 色值与光斑位置按第十稿定稿图（music-home-drafts 的 bold-v10 webp）目测取最接近值，
 * 并与树内既有 AuroraBackgroundView（瓜子 Aurora 实证色板：蜜桃／淡紫／薄荷）对齐微调；
 * 目测值清单见 batch5-f1 报告，后续若拿到定稿原件逐值复核只改本文件一处即全域生效。
 * 静态绘制、不做漂移动画（流畅优先；与 AuroraBackgroundView 同口径）。
 *
 * 共享糊底缓冲（2026-10-25 改令，真玻璃接法照海阔 HkDynamicBgView）：本 View 除全分辨率
 * 自绘外，还把同一场景以 1/4 降采样渲进一份进程共享缓冲，玻璃件（MusicGlassDrawable）
 * 按自身窗口位置从缓冲裁剪——1/4 采样天然成糊，玻璃透出的正是糊掉的本页底。
 * 音乐底是静态图、无海阔的 500ms 漂移帧，故缓冲只在背景尺寸变化时重绘；
 * 版本号供玻璃件判重裁，页面滚动时由页面调 noteScrollChanged() 顶一版双保险。
 */
public class MusicGlassBackgroundView extends View {

    private static final int BASE = 0xFFFAFAFC;

    private static final class Blob {
        final int color;
        final float a, b, diameter, alpha0, alpha1;

        Blob(int color, float a, float b, float diameter, float alpha0, float alpha1) {
            this.color = color;
            this.a = a;
            this.b = b;
            this.diameter = diameter;
            this.alpha0 = alpha0;
            this.alpha1 = alpha1;
        }
    }

    // 大光斑：中心公式与 AuroraBackgroundView 一致（cx=(W-r)*((a+1)/2)+r/2，半径=r/2）；
    // 色相换第十稿的粉／紫／薄荷：左上粉、右上紫、左下薄荷、右下粉。
    // 1.4.27 查修批加强：旧值色太浅（FFD9E8 级近白粉彩）＋中心透明度仅 0.50、中段 0.16，
    // 真机上整片极光几乎不可见、页面读作平白纸（截图实证）。改为色加深一档、中心 0.85、
    // 中段 0.38、直径加大，柔光必须一眼可见；仍是粉彩域、不压字（卡面文字区另有玻璃面承托）。
    // 2026-10-25 再压亮一档（用户真机截图亲口「背景有点太亮了」）：上一档在真机仍近乎漂白、
    // 粉紫几乎读不出。色停再加深／提饱和一档——粉 FFB9D9→FF9ECD、紫 C9B8FF→B79FFF、
    // 薄荷 A9E8D8→7FE3CB；透明度与直径、近白底 FAFAFC 均不动，仍是浅色粉彩域、不压成深色。
    private static final Blob[] LARGE = {
            new Blob(0xFFFF9ECD, -0.85f, -0.55f, 410f, 0.85f, 0.38f),
            new Blob(0xFFB79FFF, 0.85f, -0.35f, 390f, 0.85f, 0.38f),
            new Blob(0xFF7FE3CB, -0.55f, 0.75f, 370f, 0.80f, 0.36f),
            new Blob(0xFFFF9ECD, 0.60f, 0.85f, 350f, 0.78f, 0.34f),
    };

    private static final class Dot {
        final int color;
        final float x, y, radius, alpha0, alpha1;
        final boolean right;

        Dot(int color, float x, float y, float radius, float alpha0, float alpha1, boolean right) {
            this.color = color;
            this.x = x;
            this.y = y;
            this.radius = radius;
            this.alpha0 = alpha0;
            this.alpha1 = alpha1;
            this.right = right;
        }
    }

    // 小暖光：与 Aurora 小光斑同位，色保持蜜桃／金／粉（第十稿柔光里的暖点）。
    // 旧透明度 0.06–0.09 在真机等于没有，随大光斑一并加强到可见但不抢眼。
    private static final Dot[] SMALL = {
            new Dot(0xFFFF9A76, 110f, 70f, 190f, 0.22f, 0.08f, false),
            new Dot(0xFFFFD175, 230f, 50f, 150f, 0.19f, 0.07f, false),
            new Dot(0xFFF8C0D0, -140f, 40f, 120f, 0.16f, 0.05f, true),
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public MusicGlassBackgroundView(Context context) {
        super(context);
    }

    public MusicGlassBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    private static int withAlpha(int color, float alpha) {
        int a = Math.round(alpha * 255f);
        if (a < 0) a = 0;
        if (a > 255) a = 255;
        return (a << 24) | (color & 0x00FFFFFF);
    }

    private void drawBlob(Canvas canvas, float w, float h, float cx, float cy, float radius, int color, float alpha0, float alpha1) {
        if (radius <= 0f) return;
        RadialGradient g = new RadialGradient(cx, cy, radius,
                new int[]{withAlpha(color, alpha0), withAlpha(color, alpha1), withAlpha(color, 0f)},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        paint.setShader(g);
        canvas.drawRect(0f, 0f, w, h, paint);
        paint.setShader(null);
    }

    /** 整场绘制（底色＋大光斑＋小暖光）：全分辨率自绘与 1/4 缓冲共用同一份场景，参数只此一处。 */
    private void drawScene(Canvas canvas, float w, float h) {
        if (w <= 0f || h <= 0f) return;
        canvas.drawColor(BASE);
        float unit = w / 390f;
        for (Blob blob : LARGE) {
            float d = blob.diameter * unit;
            float cx = (w - d) * ((blob.a + 1f) / 2f) + d / 2f;
            float cy = (h - d) * ((blob.b + 1f) / 2f) + d / 2f;
            drawBlob(canvas, w, h, cx, cy, d / 2f, blob.color, blob.alpha0, blob.alpha1);
        }
        for (Dot dot : SMALL) {
            float cx = dot.right ? w + dot.x * unit : dot.x * unit;
            float cy = dot.y * unit;
            drawBlob(canvas, w, h, cx, cy, dot.radius * unit, dot.color, dot.alpha0, dot.alpha1);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawScene(canvas, getWidth(), getHeight());
    }

    // ---------- 共享糊底缓冲（进程单例态，口径照 HkDynamicBgView） ----------

    private static final Object BUFFER_LOCK = new Object();
    private static Bitmap sBuffer;              // 1/4 降采样缓冲
    private static int sBufferW, sBufferH;
    private static int sSrcW, sSrcH;            // 缓冲对应的背景尺寸，变了才重绘
    private static int sBufferVersion = 0;      // 重绘/滚动时 +1，玻璃件据此重裁

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) renderBuffer(w, h);
    }

    private void renderBuffer(int w, int h) {
        synchronized (BUFFER_LOCK) {
            if (sBuffer != null && !sBuffer.isRecycled() && sSrcW == w && sSrcH == h) return;
            int bw = Math.max(2, w / 4), bh = Math.max(2, h / 4);
            Bitmap old = sBuffer;
            sBuffer = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            sBufferW = bw;
            sBufferH = bh;
            sSrcW = w;
            sSrcH = h;
            drawScene(new Canvas(sBuffer), bw, bh);
            sBufferVersion++;
            if (old != null && old != sBuffer) old.recycle();
        }
    }

    /** 玻璃件用：缓冲是否就绪。 */
    public static boolean hasBuffer() {
        synchronized (BUFFER_LOCK) {
            return sBuffer != null && !sBuffer.isRecycled();
        }
    }

    /** 玻璃件用：当前缓冲（只读，不许改）。 */
    public static Bitmap getBuffer() {
        synchronized (BUFFER_LOCK) {
            return sBuffer;
        }
    }

    /** 玻璃件用：缓冲版本号，变了就重裁。 */
    public static int getBufferVersion() {
        synchronized (BUFFER_LOCK) {
            return sBufferVersion;
        }
    }

    public static int bufferWidth() {
        synchronized (BUFFER_LOCK) {
            return sBufferW;
        }
    }

    public static int bufferHeight() {
        synchronized (BUFFER_LOCK) {
            return sBufferH;
        }
    }

    /** 缓冲对应的背景 View 尺寸（源尺寸）：弹窗等独立窗口里的玻璃件按它换算裁剪坐标（锚点取 0,0）。 */
    public static int sourceWidth() {
        synchronized (BUFFER_LOCK) {
            return sSrcW;
        }
    }

    public static int sourceHeight() {
        synchronized (BUFFER_LOCK) {
            return sSrcH;
        }
    }

    /** 页面滚动时顶一版：玻璃件下次绘制按宿主新窗口位置重裁（静态底无漂移帧，靠它兜底）。 */
    public static void noteScrollChanged() {
        synchronized (BUFFER_LOCK) {
            sBufferVersion++;
        }
    }
}
