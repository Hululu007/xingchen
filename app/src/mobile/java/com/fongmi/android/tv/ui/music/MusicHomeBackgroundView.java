package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import com.fongmi.android.tv.R;

/**
 * 音乐首页专属背景（2026-10-27 他发海水照片亲定：首页底换这张图）：
 * 全屏铺满、居中裁切（centerCrop 语义），只服务音乐首页一页。
 *
 * 与全域 MusicGlassBackgroundView 的关系：本页不再挂那份固定暖色渐变，
 * 共享缓冲（MusicGlassBackgroundView 的静态缓冲）由其它音乐页各自的背景 View
 * 渲染维护，本类不碰它，其它页取底方式一字不变。
 *
 * 本类自维护一份首页域 1/4 降采样缓冲（同一张照片、同裁切），供首页暗磨砂件
 * （MusicFrostDrawable）按宿主窗口位置裁剪作糊底——件身后糊的就是这张照片本身。
 * 照片在构造时同步解码（1152×2048、解码上限 2048 边），首帧即有图，不许先进页再闪。
 */
public class MusicHomeBackgroundView extends View {

    private static final int DOWNSAMPLE = 4;
    private static final int DECODE_MAX_SIDE = 2048;
    private static final int FALLBACK_COLOR = 0xFF33424E;

    // ---------- 首页域共享缓冲（进程单例态，只本页与首页磨砂件读） ----------
    private static final Object LOCK = new Object();
    private static Bitmap sBuffer;
    private static int sBufferW, sBufferH;

    private Bitmap mPhoto;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix mMatrix = new Matrix();

    public MusicHomeBackgroundView(Context context) {
        super(context);
        init(context);
    }

    public MusicHomeBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        setWillNotDraw(false);
        mPhoto = decodePhoto(context);
    }

    /** 同步解码照片资源（drawable-nodpi，无密度缩放）；超过上限才采样，失败返回 null 走兜底色。 */
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

    // ---------- 磨砂件用：首页域缓冲访问（与 MusicGlassBackgroundView 同形契约、独立态） ----------
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

    /** 在视图树里按类型找本背景 View。找不到返回 null。 */
    public static MusicHomeBackgroundView findInTree(View root) {
        if (root == null) return null;
        if (root instanceof MusicHomeBackgroundView) return (MusicHomeBackgroundView) root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                MusicHomeBackgroundView v = findInTree(g.getChildAt(i));
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

    /** 居中裁切绘制：等比放大到铺满两边、溢出方向居中裁掉，与 ImageView centerCrop 同义。 */
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

    /** 把同一照片、同裁切以 1/4 尺寸渲进首页域缓冲，供磨砂件裁剪（1/4 自带糊度）。 */
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
            if (old != null && old != sBuffer) old.recycle();
        }
    }
}
