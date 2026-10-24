package com.fongmi.android.tv.ui.music;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 流光 CD 盘面（批 3 D 路，照施工图 §4＋补遗四＋CdMotion/CdArtwork 深挖）：
 * 盘径由宿主在 onMeasure 给（min(区宽, min(区高, 400dp)) 在布局侧算，本视图正方形填充）；
 * 封面径＝盘径×0.89285713、圆形裁剪、随 spin 旋转；盘面材质走 a21 两套色表
 * （LIGHT sweep 8 档/hubSweep 10 档、DARK 同构，色值逐字抄 a21.java 静态表）；
 * 中心孔只有按盘径比例的圆环描边（无固定 dp 中心点，补遗四.3）。
 * 四路动画（CdMotion，速率公式深挖自 LuminousCdPlayerKt$rememberCdMotion$5$1 与
 * LuminousCdContent$lambda$20–23）：帧步进 dt 钳 0.05s；
 * spin＋＝boost×16°/s（boost＝1＋节拍.a×0.65＋节拍.b×0.3，歌词展开过渡中段 boost＝0）；
 * flow＋＝(12＋节拍.a×10＋节拍.b×65)°/s；caustic＋＝(28＋节拍.a×18＋节拍.b×110)°/s；
 * undertow＋＝−(8＋节拍.a×4＋节拍.b×18)°/s。初值 spin 0／flow 0／caustic 42／undertow 214。
 * 星辰侧无节拍包络源（dg0 的 a/b 无输入，与 FluidCoverAmbientView 律动未接通同口径），
 * a/b 恒 0 时四路退化为 boost 1、flow 12、caustic 28、undertow −8 °/s——公式在码、节拍项待源接通。
 * 仅 playing 且系统动画开时推进（GZ running 门）；spin 恢复（暂停→播放）boost 600ms 爬升
 * （任务口径 Motor 恢复 600ms：以 boost 爬升实现转速恢复，非 GZ 字面常量，见报告）。
 * 光泽 sheen：播放起 700ms 淡入／暂停 450ms 淡出（CD_SHEEN_FADE_IN/OUT）。
 * 切歌扫光（CdSongChange）：mix 600ms；扫程＝mix×方向×90°＋sweepBase（切歌瞬间 flow 角）、
 * 光强＝max(0, sin(mix×π)×0.65)；入盘封面 alpha＝mix、旋转＝(1−mix)×方向×6°。
 */
public class CdDiscView extends View {

    public static final float COVER_RATIO = 0.89285713f;

    // a21 材质色表（LIGHT＝a21.m／DARK＝a21.n，逐字抄色值）
    private static final int[] SWEEP_LIGHT = {
            0xFF9FAAB7, 0xFFF0F3F6, 0xFFB6C7D0, 0xFFE9E3D4, 0xFF929FAC, 0xFFF7F8FA, 0xFFD4CADB, 0xFF9FAAB7};
    private static final int[] HUB_LIGHT = {
            0xFF9FAAB7, 0xFFF0F3F6, 0xFFB6C7D0, 0xFFCFC6D8, 0xFFE9E3D4, 0xFF929FAC, 0xFFF7F8FA, 0xFFBACCD2, 0xFFD4CADB, 0xFF9FAAB7};
    private static final float[] HUB_STOPS = {0f, 0.15f, 0.27f, 0.31f, 0.38f, 0.5f, 0.67f, 0.79f, 0.84f, 1f};
    private static final int[] SWEEP_DARK = {
            0xFF2E3742, 0xFF8792A0, 0xFF3B4653, 0xFF4E493F, 0xFF262D37, 0xFF8C97A5, 0xFF46405A, 0xFF2E3742};
    private static final int[] HUB_DARK = {
            0xFF2E3742, 0xFF8792A0, 0xFF3B4653, 0xFF443C52, 0xFF4E493F, 0xFF262D37, 0xFF8C97A5, 0xFF3C4A55, 0xFF46405A, 0xFF2E3742};
    // a21 其余字段：c 中心色、k/l 两环色（LIGHT: c 白、k 白 0.65、l #6F7E8D；DARK: c #2A3038、k 白 0.30、l #1A1F26）
    private static final int HUB_CENTER_LIGHT = 0xFFFFFFFF;
    private static final int HUB_CENTER_DARK = 0xFF2A3038;
    private static final int RING_K_LIGHT = 0xA6FFFFFF;
    private static final int RING_K_DARK = 0x4DFFFFFF;
    private static final int RING_L_LIGHT = 0xFF6F7E8D;
    private static final int RING_L_DARK = 0xFF1A1F26;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF oval = new RectF();
    private final Path clipPath = new Path();

    private Bitmap cover;
    private Bitmap incomingCover;
    private boolean dark;
    /** 表面混色进度：0＝LIGHT 材质、1＝DARK 材质；切换时 500ms 补间（CD_SURFACE_SWITCH_SPEC）。 */
    private float surfaceMix;
    private ValueAnimator surfaceAnimator;
    private boolean playing;
    private float spin;
    private float flow;
    private float caustic = 42f;
    private float undertow = 214f;
    private float boostRamp = 1f;
    private long boostRampStartMs;
    private float sheenAlpha;
    private ValueAnimator sheenAnimator;
    private float sweepMix = 1f;
    private int sweepDir = 1;
    private float sweepBase;
    private ValueAnimator sweepAnimator;
    private int coverTone = 0xFF8E8E93;

    private boolean framePosted;
    private long lastFrameMs;
    private final Choreographer choreographer = Choreographer.getInstance();
    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            framePosted = false;
            stepFrame();
            if (playing && isShown()) postFrame();
        }
    };

    public CdDiscView(Context context) {
        super(context);
    }

    public CdDiscView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    /** 表面切换：材质两套色表逐色混色补间 500ms（GZ cdPaletteFor 的 mix 口径）。 */
    public void setDarkSurface(boolean value) {
        if (dark == value && (surfaceMix == (value ? 1f : 0f))) return;
        dark = value;
        if (surfaceAnimator != null) surfaceAnimator.cancel();
        surfaceAnimator = ValueAnimator.ofFloat(surfaceMix, value ? 1f : 0f);
        surfaceAnimator.setDuration(500);
        surfaceAnimator.addUpdateListener(a -> {
            surfaceMix = (float) a.getAnimatedValue();
            invalidate();
        });
        surfaceAnimator.start();
        invalidate();
    }

    private static int mixArgb(int from, int to, float f) {
        int a = Math.round(android.graphics.Color.alpha(from)
                + (android.graphics.Color.alpha(to) - android.graphics.Color.alpha(from)) * f);
        int r = Math.round(android.graphics.Color.red(from)
                + (android.graphics.Color.red(to) - android.graphics.Color.red(from)) * f);
        int g = Math.round(android.graphics.Color.green(from)
                + (android.graphics.Color.green(to) - android.graphics.Color.green(from)) * f);
        int b = Math.round(android.graphics.Color.blue(from)
                + (android.graphics.Color.blue(to) - android.graphics.Color.blue(from)) * f);
        return android.graphics.Color.argb(a, r, g, b);
    }

    private int[] mixedStops(int[] light, int[] darkStops) {
        if (surfaceMix <= 0f) return light;
        if (surfaceMix >= 1f) return darkStops;
        int[] out = new int[light.length];
        for (int i = 0; i < light.length; i++) out[i] = mixArgb(light[i], darkStops[i], surfaceMix);
        return out;
    }

    /** 封面取色（已 HSV 归一的 tone）：材质色向 tone 偏移用（a21.b 的 i0 公式近似，见 onDraw）。 */
    public void setCoverTone(int tone) {
        coverTone = tone;
        invalidate();
    }

    public float spinAngle() {
        return spin;
    }

    /**
     * 换封面：有旧图时走切歌扫光（mix 600ms、方向 dir）；无旧图直接落位不扫（换主题重进不误扫，X-12 口径）。
     */
    public void setCoverBitmap(@Nullable Bitmap bitmap, int direction) {
        if (bitmap == null) {
            cover = null;
            incomingCover = null;
            sweepMix = 1f;
            invalidate();
            return;
        }
        if (cover == null || cover == bitmap) {
            cover = bitmap;
            incomingCover = null;
            sweepMix = 1f;
            invalidate();
            return;
        }
        incomingCover = bitmap;
        sweepDir = direction < 0 ? -1 : 1;
        sweepBase = flow;
        if (sweepAnimator != null) sweepAnimator.cancel();
        sweepAnimator = ValueAnimator.ofFloat(0f, 1f);
        sweepAnimator.setDuration(600);
        sweepAnimator.addUpdateListener(a -> {
            sweepMix = (float) a.getAnimatedValue();
            invalidate();
        });
        sweepAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (incomingCover != null) {
                    cover = incomingCover;
                    incomingCover = null;
                }
                sweepMix = 1f;
                invalidate();
            }

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                if (incomingCover != null) {
                    cover = incomingCover;
                    incomingCover = null;
                }
                sweepMix = 1f;
            }
        });
        sweepAnimator.start();
        invalidate();
    }

    public void setPlaying(boolean value) {
        if (playing == value) return;
        playing = value;
        if (value) {
            boostRamp = 0f;
            boostRampStartMs = SystemClock.uptimeMillis();
            animateSheen(1f, 700);
            lastFrameMs = 0;
            postFrame();
        } else {
            animateSheen(0f, 450);
        }
        invalidate();
    }

    private void animateSheen(float to, long duration) {
        if (sheenAnimator != null) sheenAnimator.cancel();
        sheenAnimator = ValueAnimator.ofFloat(sheenAlpha, to);
        sheenAnimator.setDuration(duration);
        sheenAnimator.addUpdateListener(a -> {
            sheenAlpha = (float) a.getAnimatedValue();
            invalidate();
        });
        sheenAnimator.start();
    }

    private void postFrame() {
        if (framePosted) return;
        framePosted = true;
        choreographer.postFrameCallback(frameCallback);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (framePosted) {
            choreographer.removeFrameCallback(frameCallback);
            framePosted = false;
        }
        if (sweepAnimator != null) sweepAnimator.cancel();
        if (sheenAnimator != null) sheenAnimator.cancel();
        if (surfaceAnimator != null) surfaceAnimator.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE && playing) {
            lastFrameMs = 0;
            postFrame();
        }
    }

    /** 四路推进（CdMotion 帧式：dt 钳 0.05s；节拍 a/b 无源恒 0，公式保留项见类注）。 */
    private void stepFrame() {
        long now = SystemClock.uptimeMillis();
        if (lastFrameMs == 0) {
            lastFrameMs = now;
            return;
        }
        float dt = Math.min(0.05f, (now - lastFrameMs) / 1000f);
        lastFrameMs = now;
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        float beatA = 0f;
        float beatB = 0f;
        float boost = 1f + beatA * 0.65f + beatB * 0.3f;
        if (boostRamp < 1f) {
            boostRamp = Math.min(1f, (now - boostRampStartMs) / 600f);
        }
        spin = wrap(spin + boost * boostRamp * 16f * dt);
        flow = wrap(flow + (12f + beatA * 10f + beatB * 65f) * dt);
        caustic = wrap(caustic + (28f + beatA * 18f + beatB * 110f) * dt);
        undertow = wrap(undertow - (8f + beatA * 4f + beatB * 18f) * dt);
        invalidate();
    }

    private static float wrap(float deg) {
        float v = deg % 360f;
        return v < 0 ? v + 360f : v;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // 盘径＝min(区宽, min(区高, 400dp))（§4: f＝min(Box 宽, min(Box 高, 400))）
        float capPx = 400f * getResources().getDisplayMetrics().density;
        float size = Math.min(Math.min(getWidth(), getHeight()), capPx);
        if (size <= 0) return;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = size / 2f;
        int[] sweep = mixedStops(SWEEP_LIGHT, SWEEP_DARK);
        int[] hub = mixedStops(HUB_LIGHT, HUB_DARK);
        int hubCenter = mixArgb(HUB_CENTER_LIGHT, HUB_CENTER_DARK, surfaceMix);
        int ringK = mixArgb(RING_K_LIGHT, RING_K_DARK, surfaceMix);
        int ringL = mixArgb(RING_L_LIGHT, RING_L_DARK, surfaceMix);

        // 盘体：sweep 材质（8 档）随 flow 转
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new SweepGradient(cx, cy, sweep, null));
        canvas.save();
        canvas.rotate(flow, cx, cy);
        canvas.drawCircle(cx, cy, radius, paint);
        canvas.restore();
        // hubSweep 材质（10 档固定停点）随 caustic 转，半透叠在盘面
        paint.setShader(new SweepGradient(cx, cy, hub, HUB_STOPS));
        paint.setAlpha(110);
        canvas.save();
        canvas.rotate(caustic, cx, cy);
        canvas.drawCircle(cx, cy, radius, paint);
        canvas.restore();
        paint.setShader(null);
        paint.setAlpha(255);
        // undertow 暗涌：深色扇段随 undertow 转（a21.g/h 的暗层近似）
        paint.setColor(dark ? 0x1A04060A : 0x1A526170);
        canvas.save();
        canvas.rotate(undertow, cx, cy);
        canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, 0f, 110f, true, paint);
        canvas.restore();
        // sheen 光泽：白色扇段，alpha 随播放态淡入淡出
        if (sheenAlpha > 0.01f) {
            paint.setColor(0xFFFFFFFF);
            paint.setAlpha(Math.round(46f * sheenAlpha));
            canvas.save();
            canvas.rotate(flow * 0.5f, cx, cy);
            canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, -30f, 70f, true, paint);
            canvas.restore();
            paint.setAlpha(255);
        }

        float coverR = radius * COVER_RATIO;
        // 旧封面（扫光进行中作 base）
        drawCover(canvas, cover, cx, cy, coverR, spin, 1f);
        // 入盘封面：alpha＝mix、旋转＝spin＋(1−mix)×方向×6°
        if (incomingCover != null && sweepMix < 1f) {
            drawCover(canvas, incomingCover, cx, cy, coverR,
                    spin + (1f - sweepMix) * sweepDir * 6f, sweepMix);
            // 扫光扇：角度＝sweepBase＋mix×方向×90°、光强＝max(0, sin(mix×π)×0.65)
            float presence = Math.max(0f, (float) Math.sin(sweepMix * Math.PI) * 0.65f);
            if (presence > 0.01f) {
                paint.setColor(0xFFFFFFFF);
                paint.setAlpha(Math.round(255f * presence * 0.5f));
                float sweepAngle = sweepBase + sweepMix * sweepDir * 90f;
                canvas.save();
                canvas.rotate(sweepAngle, cx, cy);
                canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, -14f, 28f, true, paint);
                canvas.restore();
                paint.setAlpha(255);
            }
        }
        // CD 纹理：封面外圈的细同心环（盘面 grooves，以盘径比例画，无固定 dp）
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(dark ? 0x33FFFFFF : 0x22000000);
        float density = getResources().getDisplayMetrics().density;
        paint.setStrokeWidth(Math.max(1f, density * 0.7f));
        for (int i = 1; i <= 3; i++) {
            float r = coverR + (radius - coverR) * i / 4f;
            canvas.drawCircle(cx, cy, r, paint);
        }
        // 中心孔＋hub 圆环描边（a21.k/l 两环，半径按盘径比例）
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(hubCenter);
        canvas.drawCircle(cx, cy, radius * 0.055f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, density));
        paint.setColor(ringK);
        canvas.drawCircle(cx, cy, radius * 0.085f, paint);
        paint.setColor(ringL);
        canvas.drawCircle(cx, cy, radius * 0.115f, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawCover(Canvas canvas, @Nullable Bitmap bitmap, float cx, float cy, float r, float rotation, float alpha) {
        if (bitmap == null || bitmap.isRecycled()) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(coverTone);
            paint.setAlpha(Math.round(255f * alpha));
            canvas.drawCircle(cx, cy, r, paint);
            paint.setAlpha(255);
            return;
        }
        int save = canvas.save();
        clipPath.reset();
        clipPath.addCircle(cx, cy, r, Path.Direction.CW);
        canvas.clipPath(clipPath);
        canvas.rotate(rotation, cx, cy);
        bitmapPaint.setAlpha(Math.round(255f * alpha));
        float scale = Math.max((r * 2f) / bitmap.getWidth(), (r * 2f) / bitmap.getHeight());
        float dw = bitmap.getWidth() * scale;
        float dh = bitmap.getHeight() * scale;
        canvas.drawBitmap(bitmap, cx - dw / 2f, cy - dh / 2f, bitmapPaint);
        bitmapPaint.setAlpha(255);
        canvas.restoreToCount(save);
    }
}
