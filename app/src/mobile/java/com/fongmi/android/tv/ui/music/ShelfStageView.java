package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.Nullable;

/**
 * 黑胶唱片架舞台（瓜子 RecordShelfPlayer 口径，gz-full-parity-1025 §6）：
 * 几何布局期定死（只由屏宽算、切歌不重算）：舞台宽 stage＝clamp(屏宽dp×0.66, 180, 300)、
 * 舞台高＝stage−23；f2＝min(屏宽×0.5, stage−36)、f3＝f2×0.95（袖边长 f＝f3）、c＝屏宽×0.59（槽距）。
 * 槽位变换：非当前槽缩放 (f2×0.78)/f3、原点 (0.5,1)；位移＝(1−v)×f×0.24×缩放＋c×pos（dp，v＝接近度）。
 * 袖与盘：袖外 Box 当前槽（静止时）1.48×f／非当前 f；盘 0.97×封套、阴影 5dp、
 * 露出 translationX＝−(density×f)×0.51×(1−v)、旋转 (1−v)×(−70°)；
 * 中心图 0.22×盘、圆点 0.065／0.025×盘；架板 1.1×封套／高 6dp／偏移 (−0.05×封套, 封套−2dp)。
 * 封面圆角：袖 1.5dp＋本体 2.0dp 双层；**封面按目标矩形铺满袖套**（1.4.28 点名缺陷：
 * 旧写法 (Bitmap,x,y) 重载忽略缩放、封面只剩左上角一块——本类一律 drawBitmap(bitmap, null, dst, paint)）。
 * 盘旋转 30°/秒、dt 钳 0.05s、系统动画关时不转。
 * 横滑：轴锁 |dx|＞|dy|×1.5、触发 36dp、甩速 600dp/s（VelocityTracker 释放速度）；
 * 提交先自转槽位再回调宿主（Listener.onSwipeCommit）；点当前槽（槽 0、无动画时）回调 onDiscTap。
 * setCovers 签名（旧契约逐字）：setCovers(prev, cur, next, hasPrevTrack, hasNextTrack, currentTitle)——
 * 三槽位图一次喂入＋两端存在位＋当前标题（占位/无封面口径用）。
 */
public class ShelfStageView extends View {

    public interface Listener {
        void onSwipeCommit(boolean next);

        void onDiscTap();
    }

    public static final int SHELF_INK = 0xFF252522;
    private static final int SHELF_MUTED = 0xFF86857E;
    private static final float AXIS_DOMINANCE = 1.5f;
    private static final float TRIGGER_DP = 36f;
    private static final float FLING_DP_S = 600f;
    private static final double SLOT_SPRING_K = 1400d;
    private static final double SLOT_SPRING_DAMPING = 0.9d;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path clipPath = new Path();
    private final RectF rect = new RectF();
    private final RectF coverDst = new RectF();
    private final Choreographer choreographer = Choreographer.getInstance();

    private Listener listener;
    private Bitmap coverPrev;
    private Bitmap coverCur;
    private Bitmap coverNext;
    private boolean hasPrev;
    private boolean hasNext;
    private String titleCur = "";
    private String keyPrev;
    private String keyCur;
    private String keyNext;
    private int shiftedDir;
    private String shiftedKey;

    private float stageDp;
    private float stageHeightDp;
    private float sleeveDp;
    private float centerRefDp;
    private float nonCurrentScale = 0.821f;

    private float slotOffset;
    private boolean playing;
    private float recordRotation;
    private long lastFrameMs;
    private boolean framePosted;
    private final Choreographer.FrameCallback frameCallback = frameTimeNanos -> {
        framePosted = false;
        stepRotation();
        if (playing) postFrame();
    };

    private float downX;
    private float downY;
    private boolean dragging;
    private boolean animating;
    private int animGen;
    private int touchSlop;
    private VelocityTracker velocityTracker;

    public ShelfStageView(Context context) {
        super(context);
        init(context);
    }

    public ShelfStageView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** 宿主每次装配时告知三槽曲目键，供切歌连续性对账。 */
    public void noteSlotKeys(@Nullable String prevKey, @Nullable String curKey, @Nullable String nextKey) {
        keyPrev = prevKey;
        keyCur = curKey;
        keyNext = nextKey;
    }

    /**
     * 宿主切歌装配时先问：本视图刚自转过槽位、且转到的正是 incomingKey 时返回方向
     * （+1 下一首／−1 上一首），宿主据此对账免清屏；否则 0。一次性消费。
     */
    public int consumeJustShifted(@Nullable String incomingKey) {
        if (shiftedDir != 0 && incomingKey != null && incomingKey.equals(shiftedKey)) {
            int dir = shiftedDir;
            shiftedDir = 0;
            shiftedKey = null;
            return dir;
        }
        return 0;
    }

    /** 程序化切歌且目标正是相邻槽：先滑到相邻位，落位后自转槽位并回调 onSettled。 */
    public void advanceAnimated(boolean next, @Nullable Runnable onSettled) {
        if (dragging || animating) {
            animGen++;
            animating = false;
            dragging = false;
            settleIntoNeighbor(next);
            invalidate();
            if (onSettled != null) onSettled.run();
            return;
        }
        animateSlotTo(next ? -1f : 1f, () -> {
            settleIntoNeighbor(next);
            invalidate();
            if (onSettled != null) onSettled.run();
        });
    }

    /** 槽位自转：位图／存在位／键整体移一槽，偏移归零，记对账账目。 */
    private void settleIntoNeighbor(boolean next) {
        if (next) {
            coverPrev = coverCur;
            coverCur = coverNext;
            coverNext = null;
            hasPrev = true;
            hasNext = false;
            keyPrev = keyCur;
            keyCur = keyNext;
            keyNext = null;
        } else {
            coverNext = coverCur;
            coverCur = coverPrev;
            coverPrev = null;
            hasNext = true;
            hasPrev = false;
            keyNext = keyCur;
            keyCur = keyPrev;
            keyPrev = null;
        }
        slotOffset = 0f;
        shiftedKey = keyCur;
        shiftedDir = next ? 1 : -1;
    }

    public void setCovers(@Nullable Bitmap prev, @Nullable Bitmap cur, @Nullable Bitmap next,
                          boolean hasPrevTrack, boolean hasNextTrack, String currentTitle) {
        coverPrev = prev;
        coverCur = cur;
        coverNext = next;
        hasPrev = hasPrevTrack;
        hasNext = hasNextTrack;
        titleCur = currentTitle == null ? "" : currentTitle;
        invalidate();
    }

    public void setPlaying(boolean value) {
        if (playing == value) return;
        playing = value;
        lastFrameMs = 0;
        if (value) postFrame();
        invalidate();
    }

    public boolean isAnimating() {
        return animating || dragging;
    }

    /** 宿主切歌后复位：偏移归零不走动画、自转对账作废。 */
    public void resetOffset() {
        animGen++;
        animating = false;
        dragging = false;
        slotOffset = 0f;
        shiftedDir = 0;
        shiftedKey = null;
        invalidate();
    }

    public float slotOffset() {
        return slotOffset;
    }

    private void postFrame() {
        if (framePosted) return;
        framePosted = true;
        choreographer.postFrameCallback(frameCallback);
    }

    private void stepRotation() {
        long now = android.os.SystemClock.uptimeMillis();
        if (lastFrameMs == 0) {
            lastFrameMs = now;
            return;
        }
        float dt = Math.min(0.05f, (now - lastFrameMs) / 1000f);
        lastFrameMs = now;
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return;
        recordRotation = (recordRotation + dt * 30f) % 360f;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (framePosted) {
            choreographer.removeFrameCallback(frameCallback);
            framePosted = false;
        }
        animGen++;
        animating = false;
        recycleTracker();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float density = getResources().getDisplayMetrics().density;
        float widthDp = getResources().getDisplayMetrics().widthPixels / density;
        stageDp = Math.max(180f, Math.min(300f, widthDp * 0.66f));
        stageHeightDp = stageDp - 23f;
        float f2 = Math.min(widthDp * 0.5f, stageDp - 36f);
        sleeveDp = f2 * 0.95f;
        centerRefDp = widthDp * 0.59f;
        nonCurrentScale = (f2 * 0.78f) / sleeveDp;
    }

    /** 舞台高（dp）＝stage−23：宿主布局按此定死高度。 */
    public float stageHeightDp() {
        if (stageHeightDp <= 0) {
            float density = getResources().getDisplayMetrics().density;
            float widthDp = getResources().getDisplayMetrics().widthPixels / density;
            float stage = Math.max(180f, Math.min(300f, widthDp * 0.66f));
            return stage - 23f;
        }
        return stageHeightDp;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    // ================= 绘制 =================

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (sleeveDp <= 0) return;
        float centerX = getWidth() / 2f;
        float sleevePx = dp(sleeveDp);
        // 舞台顶光条 12dp：白→#E5E4E0
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, 0, 0, dp(12f),
                new int[]{0xFFFFFFFF, 0xFFE5E4E0}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, getWidth(), dp(12f), paint);
        paint.setShader(null);
        // 先侧槽后当前槽（当前压顶）
        drawSlot(canvas, -1, coverPrev, hasPrev, centerX, sleevePx);
        drawSlot(canvas, 1, coverNext, hasNext, centerX, sleevePx);
        drawSlot(canvas, 0, coverCur, true, centerX, sleevePx);
    }

    private void drawSlot(Canvas canvas, int slot, @Nullable Bitmap cover, boolean exists,
                          float centerX, float sleevePx) {
        if (!exists && slot != 0) return;
        float pos = slot + slotOffset;
        if (Math.abs(pos) > 1.6f) return;
        float v = Math.max(0f, Math.min(1f, 1f - Math.abs(pos)));
        float scale = nonCurrentScale + (1f - nonCurrentScale) * v;
        float txDp = (1f - v) * sleeveDp * 0.24f * scale + centerRefDp * pos;
        float slotCenterX = centerX + dp(txDp);
        float sleeveLeft = slotCenterX - sleevePx / 2f;
        float sleeveTop = 0f;
        float sleeveBottom = sleeveTop + sleevePx;

        canvas.save();
        canvas.translate(slotCenterX, sleeveBottom);
        canvas.scale(scale, scale);
        canvas.translate(-slotCenterX, -sleeveBottom);

        // 架板：1.1×封套／高 6dp／偏移 (−0.05×封套, 封套−2dp)，三层半透明黑槽影
        float boardLeft = slotCenterX - sleevePx / 2f + dp(-0.05f * sleeveDp);
        float boardTop = sleeveTop + sleevePx - dp(2f);
        float boardW = sleevePx * 1.1f;
        float boardH = dp(6f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x0E000000);
        canvas.drawRect(boardLeft, boardTop, boardLeft + boardW, boardTop + boardH, paint);
        paint.setColor(0x1A000000);
        canvas.drawRect(boardLeft + boardW * 0.06f, boardTop + boardH * 0.20f,
                boardLeft + boardW * 0.94f, boardTop + boardH * 0.74f, paint);
        paint.setColor(0x2B000000);
        canvas.drawRect(boardLeft + boardW * 0.12f, boardTop + boardH * 0.34f,
                boardLeft + boardW * 0.88f, boardTop + boardH * 0.58f, paint);

        // 圆盘只在接近当前槽时露出（reveal＝v）
        if (v > 0.001f) drawRecord(canvas, slotCenterX, sleeveTop, sleevePx, v, cover, slot);

        // 袖层：渐变 #AAA79F→#77746D→#47453F、圆角 1.5dp、偏移 (1.4dp, 0)
        float layerLeft = sleeveLeft + dp(1.4f);
        paint.setShader(new LinearGradient(0, sleeveTop, 0, sleeveBottom,
                new int[]{0xFFAAA79F, 0xFF77746D, 0xFF47453F}, null, Shader.TileMode.CLAMP));
        rect.set(layerLeft, sleeveTop, layerLeft + sleevePx, sleeveBottom);
        canvas.drawRoundRect(rect, dp(1.5f), dp(1.5f), paint);
        paint.setShader(null);

        // 封面本体：目标矩形铺满袖套（圆角 2.0dp 裁剪）；无封面画音符占位（0.18×f、ShelfMuted）
        if (cover != null && !cover.isRecycled()) {
            int save = canvas.save();
            clipPath.reset();
            rect.set(sleeveLeft, sleeveTop, sleeveLeft + sleevePx, sleeveBottom);
            clipPath.addRoundRect(rect, dp(2f), dp(2f), Path.Direction.CW);
            canvas.clipPath(clipPath);
            float sc = Math.max(sleevePx / cover.getWidth(), sleevePx / cover.getHeight());
            float dw = cover.getWidth() * sc;
            float dh = cover.getHeight() * sc;
            coverDst.set(sleeveLeft + (sleevePx - dw) / 2f, sleeveTop + (sleevePx - dh) / 2f,
                    sleeveLeft + (sleevePx + dw) / 2f, sleeveTop + (sleevePx + dh) / 2f);
            canvas.drawBitmap(cover, null, coverDst, bitmapPaint);
            canvas.restoreToCount(save);
        } else {
            drawNotePlaceholder(canvas, sleeveLeft, sleeveTop, sleevePx);
        }
        // 袖面高光：#D7D5CF 低 alpha 覆顶 36%
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x2ED7D5CF);
        rect.set(sleeveLeft, sleeveTop, sleeveLeft + sleevePx, sleeveTop + sleevePx * 0.36f);
        canvas.drawRoundRect(rect, dp(2f), dp(2f), paint);
        canvas.restore();
    }

    /** 无封面占位：居中音符（椭圆头＋竖杆＋旗），边长 0.18×f、ShelfMuted 色（形态近似，见回执）。 */
    private void drawNotePlaceholder(Canvas canvas, float left, float top, float sleevePx) {
        float s = sleevePx * 0.18f;
        float cx = left + (sleevePx - s) / 2f;
        float cy = top + (sleevePx - s) / 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(SHELF_MUTED);
        float headRx = s * 0.30f;
        float headRy = s * 0.22f;
        float headCx = cx + headRx;
        float headCy = cy + s - headRy;
        canvas.drawOval(new RectF(headCx - headRx, headCy - headRy, headCx + headRx, headCy + headRy), paint);
        float stemW = Math.max(1f, s * 0.07f);
        canvas.drawRect(headCx + headRx - stemW, cy + s * 0.08f, headCx + headRx, headCy, paint);
        canvas.drawRect(headCx + headRx - stemW, cy + s * 0.08f,
                headCx + headRx + s * 0.26f, cy + s * 0.24f, paint);
    }

    private void drawRecord(Canvas canvas, float slotCenterX, float sleeveTop, float sleevePx,
                            float reveal, @Nullable Bitmap cover, int slot) {
        float discPx = sleevePx * 0.97f;
        boolean atRestCurrent = slot == 0 && Math.abs(slotOffset) < 0.001f;
        float outerWDp = atRestCurrent ? sleeveDp * 1.48f : sleeveDp;
        float outerRight = slotCenterX - sleevePx / 2f + dp(outerWDp);
        float discCx = outerRight - discPx / 2f;
        float discCy = sleeveTop + sleevePx / 2f;
        discCx += -dp(sleeveDp) * 0.51f * (1f - reveal);
        float rotation = recordRotation + (1f - reveal) * -70f;
        // 阴影 5dp
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x33000000);
        canvas.drawCircle(discCx, discCy + dp(5f), discPx / 2f, paint);
        canvas.save();
        canvas.rotate(rotation, discCx, discCy);
        paint.setColor(0xFF17171A);
        canvas.drawCircle(discCx, discCy, discPx / 2f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, dp(0.7f)));
        paint.setColor(0x2AFFFFFF);
        for (int i = 1; i <= 4; i++) {
            canvas.drawCircle(discCx, discCy, discPx / 2f * (0.55f + i * 0.1f), paint);
        }
        paint.setStyle(Paint.Style.FILL);
        // 中心图 0.22×盘
        float centerR = discPx * 0.22f / 2f;
        if (cover != null && !cover.isRecycled()) {
            int save = canvas.save();
            clipPath.reset();
            clipPath.addCircle(discCx, discCy, centerR, Path.Direction.CW);
            canvas.clipPath(clipPath);
            float sc = Math.max(centerR * 2f / cover.getWidth(), centerR * 2f / cover.getHeight());
            float dw = cover.getWidth() * sc;
            float dh = cover.getHeight() * sc;
            coverDst.set(discCx - dw / 2f, discCy - dh / 2f, discCx + dw / 2f, discCy + dh / 2f);
            canvas.drawBitmap(cover, null, coverDst, bitmapPaint);
            canvas.restoreToCount(save);
        } else {
            paint.setColor(0xFFD4D0C5);
            canvas.drawCircle(discCx, discCy, centerR, paint);
        }
        // 圆点 0.065×盘／中心点 0.025×盘
        paint.setColor(0xFFD4D0C5);
        canvas.drawCircle(discCx, discCy, discPx * 0.065f / 2f, paint);
        paint.setColor(0xFF080808);
        canvas.drawCircle(discCx, discCy, discPx * 0.025f / 2f, paint);
        canvas.restore();
    }

    // ================= 横滑／点按 =================

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                dragging = false;
                if (animating) {
                    animGen++;
                    animating = false;
                }
                recycleTracker();
                velocityTracker = VelocityTracker.obtain();
                velocityTracker.addMovement(ev);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (velocityTracker != null) velocityTracker.addMovement(ev);
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                if (!dragging) {
                    if (Math.abs(dx) <= touchSlop) return true;
                    if (Math.abs(dx) <= Math.abs(dy) * AXIS_DOMINANCE) return true;
                    dragging = true;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                float frac = dx / dp(centerRefDp);
                if (frac > 0 && !hasPrev) frac = 0f;
                if (frac < 0 && !hasNext) frac = 0f;
                slotOffset = Math.max(-1f, Math.min(1f, frac));
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (velocityTracker != null) velocityTracker.addMovement(ev);
                float dx = ev.getX() - downX;
                float velDpS = 0f;
                if (velocityTracker != null) {
                    velocityTracker.computeCurrentVelocity(1000);
                    velDpS = velocityTracker.getXVelocity() / dp(1f);
                }
                recycleTracker();
                if (!dragging) {
                    if (!animating && hitCurrentSleeve(ev.getX(), ev.getY()) && listener != null) {
                        listener.onDiscTap();
                    }
                    performClick();
                    return true;
                }
                dragging = false;
                boolean next = dx < 0;
                boolean commit = Math.abs(dx) >= TRIGGER_DP * dp(1f) || Math.abs(velDpS) >= FLING_DP_S;
                if (commit && next && !hasNext) commit = false;
                if (commit && !next && !hasPrev) commit = false;
                if (commit) {
                    final boolean commitNext = next;
                    animateSlotTo(commitNext ? -1f : 1f, () -> {
                        settleIntoNeighbor(commitNext);
                        invalidate();
                        if (listener != null) listener.onSwipeCommit(commitNext);
                    });
                } else {
                    animateSlotTo(0f, null);
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                recycleTracker();
                if (dragging) {
                    dragging = false;
                    animateSlotTo(0f, null);
                }
                return true;
            default:
                return super.onTouchEvent(ev);
        }
    }

    private boolean hitCurrentSleeve(float x, float y) {
        if (sleeveDp <= 0) return false;
        float sleevePx = dp(sleeveDp);
        float centerX = getWidth() / 2f;
        return x >= centerX - sleevePx * 0.74f && x <= centerX + sleevePx * 0.74f
                && y >= 0 && y <= sleevePx;
    }

    /** 槽位动画：弹簧（阻尼 0.9／刚度 1400）趋向 target，收敛后回调（代次守卫防叠加）。 */
    private void animateSlotTo(float target, @Nullable Runnable onDone) {
        animating = true;
        final int gen = ++animGen;
        final float[] velocity = {0f};
        final long[] lastMs = {android.os.SystemClock.uptimeMillis()};
        final Choreographer.FrameCallback[] cb = new Choreographer.FrameCallback[1];
        cb[0] = frameTimeNanos -> {
            if (gen != animGen) return;
            long now = android.os.SystemClock.uptimeMillis();
            float dt = Math.min(0.05f, Math.max(0.001f, (now - lastMs[0]) / 1000f));
            lastMs[0] = now;
            double damp = 2d * SLOT_SPRING_DAMPING * Math.sqrt(SLOT_SPRING_K);
            velocity[0] += (float) ((SLOT_SPRING_K * (target - slotOffset) - damp * velocity[0]) * dt);
            slotOffset += velocity[0] * dt;
            invalidate();
            if (Math.abs(slotOffset - target) < 0.005f && Math.abs(velocity[0]) < 0.05f) {
                slotOffset = target;
                animating = false;
                invalidate();
                if (onDone != null) onDone.run();
            } else {
                choreographer.postFrameCallback(cb[0]);
            }
        };
        choreographer.postFrameCallback(cb[0]);
    }

    private void recycleTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    /** 颜色工具（宿主 palette 共用口径）：base 向 to 混 mix。 */
    public static int mixColor(int base, float mix, int to) {
        float f = Math.max(0f, Math.min(1f, mix));
        int r = Math.round(Color.red(base) + (Color.red(to) - Color.red(base)) * f);
        int g = Math.round(Color.green(base) + (Color.green(to) - Color.green(base)) * f);
        int b = Math.round(Color.blue(base) + (Color.blue(to) - Color.blue(base)) * f);
        return Color.rgb(r, g, b);
    }
}
