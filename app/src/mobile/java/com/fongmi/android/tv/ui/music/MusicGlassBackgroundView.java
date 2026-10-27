package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import com.fongmi.android.tv.R;

/**
 * 音乐全域统一背景（除音乐首页外的全部音乐页共用，全音乐面一套，不许各页自编）。
 *
 * 2026-10-27 推广（他亲令「把音乐首页这个背景跟磨砂玻璃应用到音乐其他页面」）：
 * 本 View 改渲与音乐首页同一张海水照片（R.drawable.home_bg_sea_photo，首页
 * MusicHomeBackgroundView 同源同图）——全屏铺满、居中裁切（centerCrop 语义），
 * 构造时同步解码、首帧即有图，不许先进页再闪。此前的固定暖色渐变＋四团柔光场景
 * （GRAD/SPOT 那套）整段退场。
 *
 * 同时维护进程共享 1/4 降采样缓冲：onSizeChanged 时把同一照片、同裁切渲进小图，
 * 再过一道与首页同口径的缩微糊化（整体缩到 1/8 再双线性放大回来、两趟——照片的
 * 波纹/闪点只剩大块明暗与色影），供 MusicFrostDrawable 按宿主窗口位置裁剪作糊底
 * （其取样回落路读的就是本缓冲）。处理缓冲（colorControls 预烤）保留旧契约，
 * 内容同步换成照片缓冲。
 *
 * BASE_COLOR 保留：它仍是窗口底三处同值（@color/music_page_base、ThemeManager
 * 字面量、本常数）的代表色口径，本单不动窗口底（与首页试点出货口径一致）。
 */
public class MusicGlassBackgroundView extends View {

    /** 窗口底代表色（与 @color/music_page_base、ThemeManager 字面量三处同值，改色须三处同改）。 */
    public static final int BASE_COLOR = 0xFF6E4E30;

    private static final int DOWNSAMPLE = 4;
    private static final int DECODE_MAX_SIDE = 2048;
    private static final int FALLBACK_COLOR = 0xFF33424E;

    // ---------- 共享缓冲（进程单例态） ----------
    private static final Object LOCK = new Object();
    private static Bitmap sBuffer;
    private static Bitmap sProcessedBuffer;
    private static int sBufferW, sBufferH;
    private static int sBufferVersion = 0;

    private Bitmap mPhoto;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix mMatrix = new Matrix();

    public MusicGlassBackgroundView(Context context) {
        super(context);
        init(context);
    }

    public MusicGlassBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        setWillNotDraw(false);
        mPhoto = decodePhoto(context);
    }

    /** 同步解码照片资源（与 MusicHomeBackgroundView 同口径：drawable-nodpi、解码上限 2048 边），失败返回 null 走兜底色。 */
    private static Bitmap decodePhoto(Context context) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeResource(context.getResources(), R.drawable.home_bg_sea_photo, bounds);
            int sample = 1;
            int maxSide = Math.max(bounds.outWidth, bounds.outHeight);
            while (maxSide / (sample * 2) >= DECODE_MAX_SIDE) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeResource(context.getResources(), R.drawable.home_bg_sea_photo, opts);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---------- 磨砂件用：共享缓冲访问 ----------
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

    /**
     * 已烤入 colorControls 的场景缓冲（与原图同尺寸、同版本）：旧玻璃件裁这张免逐帧
     * 过颜色矩阵。推广后内容同步为照片缓冲，契约保留给仍读它的旧件。
     */
    public static Bitmap getProcessedBuffer() {
        synchronized (LOCK) {
            return sProcessedBuffer;
        }
    }

    private static final android.graphics.ColorFilter CC_FILTER = buildCcFilter();

    private static android.graphics.ColorFilter buildCcFilter() {
        float sat = 1.5f, bri = 0.018f, con = 0.9625f;
        float invSat = 1f - sat;
        float r = 0.213f * invSat, g = 0.715f * invSat, b = 0.072f * invSat;
        float t = (0.5f - con * 0.5f + bri) * 255f;
        float cr = con * r, cg = con * g, cb = con * b, cs = con * sat;
        return new android.graphics.ColorMatrixColorFilter(new android.graphics.ColorMatrix(new float[]{
                cr + cs, cg, cb, 0, t,
                cr, cg + cs, cb, 0, t,
                cr, cg, cb + cs, 0, t,
                0, 0, 0, 1, 0}));
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
        drawPhoto(canvas, w, h);
    }

    /** 居中裁切绘制：等比放大到铺满两边、溢出方向居中裁掉，与首页 MusicHomeBackgroundView 同义。 */
    private void drawPhoto(Canvas canvas, int w, int h) {
        if (mPhoto == null || mPhoto.isRecycled()) {
            canvas.drawColor(FALLBACK_COLOR);
            return;
        }
        float scale = Math.max((float) w / mPhoto.getWidth(), (float) h / mPhoto.getHeight());
        float dx = (w - mPhoto.getWidth() * scale) / 2f;
        float dy = (h - mPhoto.getHeight() * scale) / 2f;
        mMatrix.reset();
        mMatrix.postScale(scale, scale);
        mMatrix.postTranslate(dx, dy);
        canvas.drawBitmap(mPhoto, mMatrix, mPaint);
    }

    /** 把同一照片、同裁切以 1/4 尺寸渲进共享缓冲，供磨砂件裁剪；渲完再糊化加厚（与首页同道）。 */
    private void renderBuffer(int w, int h) {
        if (mPhoto == null) return;
        int bw = Math.max(2, w / DOWNSAMPLE);
        int bh = Math.max(2, h / DOWNSAMPLE);
        synchronized (LOCK) {
            Bitmap old = sBuffer;
            sBuffer = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            sBufferW = bw;
            sBufferH = bh;
            Canvas c = new Canvas(sBuffer);
            c.scale(1f / DOWNSAMPLE, 1f / DOWNSAMPLE);
            drawPhoto(c, w, h);
            blurBuffer(sBuffer);
            // colorControls 一次性烤进处理缓冲（缓冲只在尺寸变化时重渲，烤一次全件共用）
            Bitmap oldP = sProcessedBuffer;
            sProcessedBuffer = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas pc = new Canvas(sProcessedBuffer);
            Paint pp = new Paint(Paint.FILTER_BITMAP_FLAG);
            pp.setColorFilter(CC_FILTER);
            pc.drawBitmap(sBuffer, 0, 0, pp);
            if (oldP != null && oldP != sProcessedBuffer) oldP.recycle();
            sBufferVersion++;
            if (old != null && old != sBuffer) old.recycle();
        }
    }

    /**
     * 糊化加厚（与首页 MusicHomeBackgroundView.blurBuffer 同口径同参数）：缓冲整体缩到
     * 1/8（即全屏 1/32）再以双线性放大画回、连做两趟——每个缓冲像素平均掉原图约
     * 32×32 邻域，波纹/闪点这类高频细节整体灭掉，只剩缓慢变化的大块明暗与色影。
     * 只在尺寸变化时跑一次、图又小，开销可忽略。
     */
    private static final int BLUR_SHRINK = 8;
    private static final int BLUR_PASSES = 2;

    private static void blurBuffer(Bitmap buf) {
        int w = buf.getWidth(), h = buf.getHeight();
        int tw = Math.max(1, w / BLUR_SHRINK), th = Math.max(1, h / BLUR_SHRINK);
        if (tw >= w || th >= h) return;
        Bitmap tiny = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        Rect src = new Rect(0, 0, w, h);
        RectF dstFull = new RectF(0, 0, w, h);
        Canvas bufCanvas = new Canvas(buf);
        for (int i = 0; i < BLUR_PASSES; i++) {
            Canvas tinyCanvas = new Canvas(tiny);
            tinyCanvas.drawBitmap(buf, src, new RectF(0, 0, tw, th), p);
            bufCanvas.drawBitmap(tiny, new Rect(0, 0, tw, th), dstFull, p);
        }
        tiny.recycle();
    }
}
