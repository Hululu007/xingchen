package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;

/**
 * 流光 CD 盘面（照瓜子 CdArtwork＋a21＝CdDiscMaterial 复刻）：
 * 盘面扫光 8 档闭环、盘芯 hubSweep 10 档固定 stop [0,.15,.27,.31,.38,.5,.67,.79,.84,1]，
 * 每档先经 a() 向盘芯 tint 染色；盘芯 tint＝b(封面色)＝lerp(材料.c→封面色, min((1−亮度)×e＋d,.92))。
 * 封面方图居中（盘面 ×0.89285713、圆角 尺寸×.05），播放时整盘 16°/s 旋转（dt 钳 .05）。
 * 数值出处：gz-smali-missing-dig a21 全表＋三浮点定谳。
 */
public class CdDiscView extends View {

    public static final class Material {
        public final int[] face;
        public final int[] hub;
        public final int base;
        public final float d;
        public final float e;
        public final float f;
        public final int sheen;
        public final int hiSoft;
        public final int hiMid;
        public final int edge;
        public final int hiStrong;
        public final int ring;

        Material(int[] face, int[] hub, int base, float d, float e, float f,
                 int g, int h, int i, int j, int k, int l) {
            this.face = face;
            this.hub = hub;
            this.base = base;
            this.d = d;
            this.e = e;
            this.f = f;
            this.sheen = g;
            this.hiSoft = h;
            this.hiMid = i;
            this.edge = j;
            this.hiStrong = k;
            this.ring = l;
        }
    }

    private static int C(String hex) {
        return Color.parseColor(hex);
    }

    private static int withAlpha(int color, float alpha) {
        return Color.argb(Math.round(255 * alpha), Color.red(color), Color.green(color), Color.blue(color));
    }

    public static Material pearl() {
        int[] a = {C("#9FAAB7"), C("#F0F3F6"), C("#B6C7D0"), C("#E9E3D4"),
                C("#929FAC"), C("#F7F8FA"), C("#D4CADB"), C("#9FAAB7")};
        int[] b = {a[0], a[1], a[2], a[3], C("#CFC6D8"), a[4], a[5], a[6], C("#BACCD2"), a[7]};
        return new Material(a, b, 0xFFFFFFFF, 0.55f, 0.25f, 0.55f,
                withAlpha(C("#526170"), 0.1f), withAlpha(0xFFFFFFFF, 0.16f),
                withAlpha(0xFFFFFFFF, 0.5f), withAlpha(0xFF000000, 0.28f),
                withAlpha(0xFFFFFFFF, 0.65f), C("#6F7E8D"));
    }

    public static Material space() {
        int[] a = {C("#2E3742"), C("#8792A0"), C("#3B4653"), C("#4E493F"),
                C("#262D37"), C("#8C97A5"), C("#46405A"), C("#2E3742")};
        int[] b = {a[0], a[1], a[2], a[3], C("#443C52"), a[4], a[5], a[6], C("#3C4A55"), a[7]};
        return new Material(a, b, C("#2A3038"), 0.5f, 0f, 0.34f,
                withAlpha(C("#04060A"), 0.42f), withAlpha(0xFFFFFFFF, 0.1f),
                withAlpha(0xFFFFFFFF, 0.22f), withAlpha(0xFF000000, 0.55f),
                withAlpha(0xFFFFFFFF, 0.3f), C("#1A1F26"));
    }

    private static final float[] HUB_STOPS = {0f, 0.15f, 0.27f, 0.31f, 0.38f, 0.5f, 0.67f, 0.79f, 0.84f, 1f};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private Material material = pearl();
    private int coverColor = 0xFF888888;
    private Bitmap coverBitmap;
    private String coverUrl;
    private float angle;
    private boolean playing;
    private long lastFrameNs;
    private boolean frameRunning;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!frameRunning) return;
            if (lastFrameNs > 0) {
                float dt = Math.min(0.05f, (frameTimeNanos - lastFrameNs) / 1e9f);
                if (playing) {
                    angle = (angle + 16f * dt) % 360f;
                    invalidate();
                }
            }
            lastFrameNs = frameTimeNanos;
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public CdDiscView(Context context) {
        this(context, null);
    }

    public CdDiscView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public void setMaterial(Material material) {
        this.material = material;
        invalidate();
    }

    public void setCover(String url, int color) {
        coverColor = color;
        if (url == null ? coverUrl == null : url.equals(coverUrl)) {
            invalidate();
            return;
        }
        coverUrl = url;
        coverBitmap = null;
        if (url != null && !url.isEmpty()) {
            Glide.with(getContext()).asBitmap().load(url).override(512, 512)
                    .into(new CustomTarget<Bitmap>() {
                        @Override
                        public void onResourceReady(@androidx.annotation.NonNull Bitmap resource,
                                                    @Nullable Transition<? super Bitmap> transition) {
                            if (url.equals(coverUrl)) {
                                coverBitmap = resource;
                                invalidate();
                            }
                        }

                        @Override
                        public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {
                        }
                    });
        }
        invalidate();
    }

    public void setPlaying(boolean playing) {
        this.playing = playing;
        updateLoop();
    }

    private void updateLoop() {
        boolean should = playing && isAttachedToWindow();
        if (should && !frameRunning) {
            frameRunning = true;
            lastFrameNs = 0;
            Choreographer.getInstance().postFrameCallback(frameCallback);
        } else if (!should) {
            frameRunning = false;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateLoop();
    }

    @Override
    protected void onDetachedFromWindow() {
        frameRunning = false;
        super.onDetachedFromWindow();
    }

    static float lightness(int color) {
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        return (max + min) / 2f / 255f;
    }

    static int lerp(int from, int to, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return Color.argb(
                Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t),
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * t),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * t),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t));
    }

    /** b(封面色)：盘芯 tint。 */
    private int hubTint() {
        float t = Math.min((1f - lightness(coverColor)) * material.e + material.d, 0.92f);
        return lerp(material.base, coverColor, t);
    }

    /** a(色, tint)：每档向 tint 混。 */
    private int shade(int color, int tint) {
        float t = (1f - lightness(color) * 0.65f) * material.f;
        return lerp(color, tint, t);
    }

    private int[] shaded(int[] colors, int tint) {
        int[] out = new int[colors.length];
        for (int i = 0; i < colors.length; i++) out[i] = shade(colors[i], tint);
        return out;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float size = Math.min(getWidth(), getHeight());
        if (size <= 0) return;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = size / 2f;
        int tint = hubTint();

        canvas.save();
        canvas.rotate(angle, cx, cy);

        paint.setShader(new SweepGradient(cx, cy, shaded(material.face, tint), null));
        canvas.drawCircle(cx, cy, r, paint);
        paint.setShader(null);

        float coverSize = size * 0.89285713f;
        float corner = coverSize * 0.05f;
        rect.set(cx - coverSize / 2f, cy - coverSize / 2f, cx + coverSize / 2f, cy + coverSize / 2f);
        if (coverBitmap != null) {
            canvas.save();
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(rect, corner, corner, android.graphics.Path.Direction.CW);
            canvas.clipPath(clip);
            canvas.drawBitmap(coverBitmap, null, rect, paint);
            canvas.restore();
        } else {
            paint.setColor(lerp(material.base, coverColor, 0.35f));
            canvas.drawRoundRect(rect, corner, corner, paint);
        }

        float hubR = size * 0.13f;
        paint.setShader(new SweepGradient(cx, cy, shaded(material.hub, tint), HUB_STOPS));
        canvas.drawCircle(cx, cy, hubR, paint);
        paint.setShader(null);
        paint.setColor(material.ring);
        canvas.drawCircle(cx, cy, hubR * 0.32f, paint);

        canvas.restore();

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, size * 0.004f));
        paint.setColor(material.edge);
        canvas.drawCircle(cx, cy, r - paint.getStrokeWidth() / 2f, paint);
        paint.setStyle(Paint.Style.FILL);

        paint.setShader(new RadialGradient(cx - r * 0.35f, cy - r * 0.4f, r * 1.1f,
                material.hiSoft, 0x00000000, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, r, paint);
        paint.setShader(null);
    }
}
