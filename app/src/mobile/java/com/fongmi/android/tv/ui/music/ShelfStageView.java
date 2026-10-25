package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.Nullable;

/**
 * 黑胶唱片架舞台（批 3 D 路，照施工图 §5＋补遗五＋旧 ShelfStageGeometry 抠值存档）：
 * 固定 3 槽（−1 上一首／0 当前／+1 下一首），不是整条队列横拉。
 * 几何（约束宽＝屏宽 dp）：stage＝clamp(宽×0.66, 180, 300)；舞台高＝stage−23；
 * f2＝min(宽×0.5, stage−36)；封套 f3＝f2×0.95；槽距 c＝宽×0.59；
 * 非当前缩放 f4＝(f2×0.78)/f3≈0.821；变换原点 (0.5, 1)；
 * 槽位移＝(1−v)×f3×0.24×缩放＋c×slot（dp，v＝clamp(1−|slot|,0,1)，slot 为连续位）。
 * X-14 落法：舞台宽高在 onSizeChanged 按屏宽公式定死（不随切歌/内容变化），
 * 切歌只换三槽位图与位移动画代次，绝不重绑根布局、不改根宽——避开旧账两因。
 * 袖：外 Box 当前槽 1.48×f3／非当前 f3；袖层圆角 1.5dp＋封面本体圆角 2dp；
 * 架板宽 1.1×f3、高 6dp、偏移 (−0.05×f3, f3−2dp)；填充为三层半透明黑槽影（2026-10-25 深挖抠实）；袖内无封面占位＝居中音符矢量 0.18×f、ShelfMuted（同批抠实）。
 * 圆盘：直径 0.97×封套、圆形、阴影 5dp（以盘下暗圆近似）；滑动露出：
 * translationX＝−f×0.51×(1−reveal)、旋转＝盘自转＋(1−reveal)×(−70°)，
 * reveal 取槽位接近度 clamp(1−|slot|,0,1)（静止当前槽 reveal＝1 全探出；侧槽无盘，与 GZ 侧槽不绘盘一致）。
 * 盘自转 30°/s（dt 钳 0.05s），仅播放时推进、唱完/暂停停。
 * 中心图 0.22×盘（封面缩图圆）、圆点 0.065×盘 #D4D0C5 与 0.025×盘 #080808（ShelfRecord 抠值）。
 * 手势：横滑翻页——轴锁 |dx|＞|dy|×1.5（与经典竖滑同口径换横轴，页级竖滑 dominance 1.5）、
 * 触发 36dp、甩速 600dp/s；拖动中三槽连续位移、松手提交/回弹走 Choreographer 弹簧 (阻尼 0.9/刚度 1400，
 * 与页级回弹同参，见 PlayerPageSwipeLayout.springBack)。
 * 点唱片收藏：点按落在当前槽袖内、无滑动动画进行中才触发（§5/补遗五.10），由宿主执行收藏切换。
 * 切歌连续性（2026-10-25）：提交落位后先自转槽位（settleIntoNeighbor：位图/存在位/键整体
 * 前移一槽、偏移归零，落位帧与自转后逐像素同位）再通知宿主——宿主装配时 consumeJustShifted
 * 对上账即免清屏，只补最外侧新槽封面；程序化切歌（按钮/自动）切到相邻槽时走 advanceAnimated
 * 同路（先滑到相邻位、落位自转、回调回喂）。旧路「切歌即清屏＋三槽封面逐张弹入」是跳变源头，废。
 */
public class ShelfStageView extends View {

    public interface Listener {
        /** 横滑提交切歌（next=true 下一首）。 */
        void onSwipeCommit(boolean next);

        /** 点当前槽唱片（收藏切换，宿主执行）。 */
        void onDiscTap();
    }

    public static final int SHELF_INK = 0xFF252522;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private final RectF coverDst = new RectF();
    private final Path clipPath = new Path();
    private final Matrix noteMatrix = new Matrix();
    private final Path notePath = new Path();

    /** Rounded.MusicNote 矢量（24×24；GZ tf2.w() 构建路径逐笔解码，相对曲线已换算为绝对坐标）。 */
    private static final Path NOTE_PATH = buildNotePath();

    private static Path buildNotePath() {
        Path p = new Path();
        p.moveTo(12f, 5f);
        p.lineTo(12f, 13.55f);
        p.cubicTo(11.06f, 13.01f, 9.9f, 12.8f, 8.67f, 13.23f);
        p.cubicTo(7.33f, 13.71f, 6.3f, 14.9f, 6.06f, 16.3f);
        p.cubicTo(5.6f, 19.04f, 7.92f, 21.38f, 10.65f, 20.95f);
        p.cubicTo(12.61f, 20.64f, 14f, 18.84f, 14f, 16.85f);
        p.lineTo(14f, 7f);
        p.lineTo(16f, 7f);
        p.cubicTo(17.1f, 7f, 18f, 6.1f, 18f, 5f);
        p.cubicTo(18f, 3.9f, 17.1f, 3f, 16f, 3f);
        p.lineTo(14f, 3f);
        p.cubicTo(12.9f, 3f, 12f, 3.9f, 12f, 5f);
        p.close();
        return p;
    }

    private Bitmap coverPrev;
    private Bitmap coverCur;
    private Bitmap coverNext;
    private String titleCur = "";
    private boolean hasPrev;
    private boolean hasNext;
    // 三槽曲目键（宿主 noteSlotKeys 喂入）＋自转对账：自转后 shiftedKey=新当前键、shiftedDir=方向
    private String keyPrev;
    private String keyCur;
    private String keyNext;
    private String shiftedKey;
    private int shiftedDir;

    // 几何（dp，onSizeChanged 定死）
    private float stageDp;
    private float stageHeightDp;
    private float sleeveDp;
    private float centerRefDp;
    private float nonCurrentScale = 0.821f;

    private float slotOffset; // 连续槽位偏移：0 静止，拖动/动画中 ∈[-1,1]
    private boolean playing;
    private float recordRotation;
    private long lastFrameMs;
    private boolean framePosted;
    private final Choreographer choreographer = Choreographer.getInstance();
    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            framePosted = false;
            stepRotation();
            if (playing && isShown()) postFrame();
        }
    };

    // 横滑手势
    private float downX;
    private float downY;
    private long downTimeMs;
    private float lastMoveX;
    private long lastMoveTimeMs;
    private boolean dragging;
    private boolean animating;
    private int animGen;
    private Listener listener;
    private int touchSlop;

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

    /** 宿主每次装配时告知三槽曲目键（MusicQueue.trackKey），供切歌连续性对账。 */
    public void noteSlotKeys(@Nullable String prevKey, @Nullable String curKey, @Nullable String nextKey) {
        keyPrev = prevKey;
        keyCur = curKey;
        keyNext = nextKey;
    }

    /**
     * 宿主切歌装配时先问：本视图刚自转过槽位、且转到的正是 incomingKey 时返回方向
     * （+1 下一首／-1 上一首），宿主据此自转自己的字段并免清屏装配；否则返回 0。一次性消费。
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

    /**
     * 程序化切歌（按钮／自动下一首）且目标正是相邻槽时：槽位先滑到相邻位，落位后自转
     * 槽位并回调 onSettled（宿主在回调里按已自转字段回喂封面，补最外侧新槽）。
     * 正在拖动／动画中则直接自转落位，不叠动画。
     */
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

    /**
     * 槽位自转（落位瞬间调用）：位图／存在位／键整体移一槽，新当前槽＝原相邻槽、偏移归零。
     * 落位时相邻槽正居中、自转后仍居中，原当前槽退到相邻位——画面逐像素连续。
     * 最外侧新槽暂无图（存在位先置假），等宿主装配回喂补齐。
     */
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

    /** 三槽位图一次喂入（切歌只换内容、不动布局——X-14）。 */
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
        animGen++;
        animating = false;
        super.onDetachedFromWindow();
    }

    private void stepRotation() {
        long now = SystemClock.uptimeMillis();
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
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // X-14：舞台几何布局期定死——只由屏宽公式算，与曲目/内容无关，切歌不重算根宽。
        float density = getResources().getDisplayMetrics().density;
        float widthDp = getResources().getDisplayMetrics().widthPixels / density;
        stageDp = Math.max(180f, Math.min(300f, widthDp * 0.66f));
        stageHeightDp = stageDp - 23f;
        float f2 = Math.min(widthDp * 0.5f, stageDp - 36f);
        sleeveDp = f2 * 0.95f;
        centerRefDp = widthDp * 0.59f;
        nonCurrentScale = (f2 * 0.78f) / sleeveDp;
    }

    /** 舞台高（dp）：宿主布局按此给定死高度。 */
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

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (sleeveDp <= 0) return;
        float density = getResources().getDisplayMetrics().density;
        float centerX = getWidth() / 2f;
        float sleevePx = sleeveDp * density;
        float stageTop = 0f;
        // 舞台顶光条：高 12dp、白→#E5E4E0（RecordShelfStage 抠值）
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, 0, 0, dp(12f),
                new int[]{0xFFFFFFFF, 0xFFE5E4E0}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, getWidth(), dp(12f), paint);
        paint.setShader(null);
        // 槽序：先画侧槽、后画当前槽（当前压顶）
        drawSlot(canvas, -1, coverPrev, hasPrev, centerX, stageTop, sleevePx);
        drawSlot(canvas, 1, coverNext, hasNext, centerX, stageTop, sleevePx);
        drawSlot(canvas, 0, coverCur, true, centerX, stageTop, sleevePx);
    }

    private void drawSlot(Canvas canvas, int slot, @Nullable Bitmap cover, boolean exists,
                          float centerX, float stageTop, float sleevePx) {
        if (!exists && slot != 0) return;
        float pos = slot + slotOffset;
        if (Math.abs(pos) > 1.6f) return;
        float v = Math.max(0f, Math.min(1f, 1f - Math.abs(pos)));
        float scale = nonCurrentScale + (1f - nonCurrentScale) * v;
        float txDp = (1f - v) * sleeveDp * 0.24f * scale + centerRefDp * pos;
        float slotCenterX = centerX + txDp * getResources().getDisplayMetrics().density;
        float outerWDp = (slot == 0 && Math.abs(slotOffset) < 0.001f) ? sleeveDp * 1.48f : sleeveDp;
        // 袖外 Box：宽 outerW、 高 f3；变换原点 (0.5,1)——以袖底边中点为原点缩放
        float sleeveLeft = slotCenterX - sleevePx / 2f;
        float sleeveTop = stageTop; // 袖顶贴舞台顶（槽纵偏移 −12dp 是舞台内对齐口径，已并入舞台高 stage−23 的关系，不在槽内再移）
        float sleeveBottom = sleeveTop + sleevePx;

        canvas.save();
        // 原点 (0.5,1)：绕袖底边中心缩放
        canvas.translate(slotCenterX, sleeveBottom);
        canvas.scale(scale, scale);
        canvas.translate(-slotCenterX, -sleeveBottom);

        // 架板：宽 1.1×f3、高 6dp、偏移 (−0.05×f3, f3−2dp)；填充不是纯色——GZ 实证为三层
        // 半透明黑槽影叠画（全幅 α0.055／内缩 α0.10／芯 α0.17、无圆角），ShelfInk 只是墨色不作板色。
        paint.setStyle(Paint.Style.FILL);
        float boardLeft = slotCenterX - sleevePx / 2f + dp(-0.05f * sleeveDp);
        float boardTop = sleeveTop + sleevePx - dp(2f);
        float boardW = sleevePx * 1.1f;
        float boardH = dp(6f);
        paint.setColor(0x0E000000);
        canvas.drawRect(boardLeft, boardTop, boardLeft + boardW, boardTop + boardH, paint);
        paint.setColor(0x1A000000);
        canvas.drawRect(boardLeft + boardW * 0.06f, boardTop + boardH * 0.20f,
                boardLeft + boardW * 0.94f, boardTop + boardH * 0.74f, paint);
        paint.setColor(0x2B000000);
        canvas.drawRect(boardLeft + boardW * 0.12f, boardTop + boardH * 0.34f,
                boardLeft + boardW * 0.88f, boardTop + boardH * 0.58f, paint);

        // 圆盘（仅接近当前槽时绘：reveal＝v；侧槽静止 v＝0 不绘，与 GZ 一致）
        if (v > 0.001f) {
            drawRecord(canvas, slotCenterX, sleeveTop, sleevePx, v, cover);
        }

        // 袖层：宽 f3、高 f3、圆角 1.5dp，渐变 #AAA79F→#77746D→#47453F（袖内抠值），偏移 (1.4dp, 0)
        float sleeveLayerLeft = sleeveLeft + dp(1.4f);
        paint.setShader(new LinearGradient(0, sleeveTop, 0, sleeveBottom,
                new int[]{0xFFAAA79F, 0xFF77746D, 0xFF47453F}, null, Shader.TileMode.CLAMP));
        rect.set(sleeveLayerLeft, sleeveTop, sleeveLayerLeft + sleevePx, sleeveBottom);
        canvas.drawRoundRect(rect, dp(1.5f), dp(1.5f), paint);
        paint.setShader(null);
        // 封面本体：方形、圆角 2dp；无封面时素色＋歌名首字（仅当前槽有标题可用）
        if (cover != null && !cover.isRecycled()) {
            int save = canvas.save();
            clipPath.reset();
            rect.set(sleeveLeft, sleeveTop, sleeveLeft + sleevePx, sleeveBottom);
            clipPath.addRoundRect(rect, dp(2f), dp(2f), Path.Direction.CW);
            canvas.clipPath(clipPath);
            float sc = Math.max(sleevePx / cover.getWidth(), sleevePx / cover.getHeight());
            float dw = cover.getWidth() * sc;
            float dh = cover.getHeight() * sc;
            // 必须走目标矩形重载：(Bitmap, x, y) 重载按原图尺寸画、sc/dw/dh 全被忽略——
            // 旧写法封面只剩袖套左上角一小块、其余露出袖面灰渐变（2026-10-25 真机图 113185 实证）。
            coverDst.set(sleeveLeft + (sleevePx - dw) / 2f, sleeveTop + (sleevePx - dh) / 2f,
                    sleeveLeft + (sleevePx + dw) / 2f, sleeveTop + (sleevePx + dh) / 2f);
            canvas.drawBitmap(cover, null, coverDst, bitmapPaint);
            canvas.restoreToCount(save);
        } else {
            // 无封面占位（GZ 实证）：袖面居中音符矢量 Rounded.MusicNote、边长 0.18×f、tint ShelfMuted，
            // 画序在封面之下（有封面被盖住、无封面才露出）；星辰旧版素色块＋歌名首字作废。
            float noteSize = sleevePx * 0.18f;
            noteMatrix.setScale(noteSize / 24f, noteSize / 24f);
            noteMatrix.postTranslate(sleeveLeft + (sleevePx - noteSize) / 2f,
                    sleeveTop + (sleevePx - noteSize) / 2f);
            notePath.set(NOTE_PATH);
            notePath.transform(noteMatrix);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF86857E);
            canvas.drawPath(notePath, paint);
        }
        // 袖面高光：#D7D5CF 斜光（sleeveEdgeLighting 色）低 alpha 覆顶
        paint.setColor(0x2ED7D5CF);
        rect.set(sleeveLeft, sleeveTop, sleeveLeft + sleevePx, sleeveTop + sleevePx * 0.36f);
        canvas.drawRoundRect(rect, dp(2f), dp(2f), paint);
        canvas.restore();
    }

    /** 圆盘本体：直径 0.97×封套、右对齐（Alignment(1,0) CenterEnd）、露出位移/旋转照 §5。 */
    private void drawRecord(Canvas canvas, float slotCenterX, float sleeveTop, float sleevePx, float reveal, @Nullable Bitmap cover) {
        float density = getResources().getDisplayMetrics().density;
        float discPx = sleevePx * 0.97f;
        // 袖外 Box 右沿：当前槽 1.48×f3 时盘贴右沿；盘左缘＝袖左＋1.48f3−0.97f3（静止当前槽）
        float outerRight = slotCenterX - sleevePx / 2f + sleevePx * 1.48f;
        float discCx = outerRight - discPx / 2f;
        float discCy = sleeveTop + sleevePx / 2f;
        float tx = -(sleeveDp * density) * 0.51f * (1f - reveal);
        discCx += tx;
        float rotation = recordRotation + (1f - reveal) * -70f;
        // 阴影 5dp：盘下暗圆偏移
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x33000000);
        canvas.drawCircle(discCx, discCy + dp(5f), discPx / 2f, paint);
        canvas.save();
        canvas.rotate(rotation, discCx, discCy);
        // 盘体：近黑＋沟槽同心环（ShelfRecord 径向渐变 alpha 档 0.035/0.11/0.025/0.025/0.2/0.035 近似为环线）
        paint.setColor(0xFF17171A);
        canvas.drawCircle(discCx, discCy, discPx / 2f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, density * 0.7f));
        paint.setColor(0x2AFFFFFF);
        for (int i = 1; i <= 4; i++) {
            canvas.drawCircle(discCx, discCy, discPx / 2f * (0.55f + i * 0.1f), paint);
        }
        paint.setStyle(Paint.Style.FILL);
        // 中心图 0.22×盘：封面缩图圆
        float centerR = discPx * 0.22f / 2f;
        if (cover != null && !cover.isRecycled()) {
            int save = canvas.save();
            clipPath.reset();
            clipPath.addCircle(discCx, discCy, centerR, Path.Direction.CW);
            canvas.clipPath(clipPath);
            float sc = Math.max(centerR * 2f / cover.getWidth(), centerR * 2f / cover.getHeight());
            // 同 drawSlot：(x, y) 重载忽略缩放，中心图会按原图尺寸溢出圆裁、只露局部放大块
            float dw = cover.getWidth() * sc;
            float dh = cover.getHeight() * sc;
            coverDst.set(discCx - dw / 2f, discCy - dh / 2f, discCx + dw / 2f, discCy + dh / 2f);
            canvas.drawBitmap(cover, null, coverDst, bitmapPaint);
            canvas.restoreToCount(save);
        } else {
            paint.setColor(0xFFD4D0C5);
            canvas.drawCircle(discCx, discCy, centerR, paint);
        }
        // 圆点：0.065×盘 #D4D0C5 环点以小圆环近似、0.025×盘 #080808 中心点
        paint.setColor(0xFFD4D0C5);
        canvas.drawCircle(discCx, discCy, discPx * 0.065f / 2f, paint);
        paint.setColor(0xFF080808);
        canvas.drawCircle(discCx, discCy, discPx * 0.025f / 2f, paint);
        canvas.restore();
    }

    // ================= 横滑翻页 =================

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                downTimeMs = SystemClock.elapsedRealtime();
                lastMoveX = downX;
                lastMoveTimeMs = downTimeMs;
                dragging = false;
                if (animating) {
                    animGen++;
                    animating = false;
                }
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                if (!dragging) {
                    if (Math.abs(dx) <= touchSlop) return true;
                    if (Math.abs(dx) <= Math.abs(dy) * 1.5f) return true;
                    dragging = true;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                lastMoveX = ev.getX();
                lastMoveTimeMs = SystemClock.elapsedRealtime();
                float density = getResources().getDisplayMetrics().density;
                float frac = dx / (centerRefDp * density);
                // 无相邻曲时该方向锁死不位移
                if (frac > 0 && !hasPrev) frac = 0f;
                if (frac < 0 && !hasNext) frac = 0f;
                slotOffset = Math.max(-1f, Math.min(1f, frac));
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                if (!dragging) {
                    // 点按：落在当前槽袖内＝点唱片收藏（仅无动画时）
                    if (!animating && hitCurrentSleeve(ev.getX(), ev.getY()) && listener != null) {
                        listener.onDiscTap();
                    }
                    performClick();
                    return true;
                }
                dragging = false;
                float density = getResources().getDisplayMetrics().density;
                float dtMs = Math.max(1f, (float) (lastMoveTimeMs - downTimeMs));
                float velocityDpS = Math.abs(lastMoveX - downX) / dtMs * 1000f / density;
                boolean commit = Math.abs(dx) >= 36f * density || velocityDpS >= 600f;
                boolean next = dx < 0;
                if (commit && next && !hasNext) commit = false;
                if (commit && !next && !hasPrev) commit = false;
                if (commit) {
                    animateSlotTo(next ? -1f : 1f, () -> {
                        // 先自转槽位再通知宿主切歌：宿主装配 consumeJustShifted 对上账、
                        // 免清屏续帧（旧序先切歌后归零、宿主清屏重装＝落位帧被整段丢弃）。
                        settleIntoNeighbor(next);
                        invalidate();
                        if (listener != null) listener.onSwipeCommit(next);
                    });
                } else {
                    animateSlotTo(0f, null);
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
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
        float density = getResources().getDisplayMetrics().density;
        float sleevePx = sleeveDp * density;
        float centerX = getWidth() / 2f;
        return x >= centerX - sleevePx * 0.74f && x <= centerX + sleevePx * 0.74f
                && y >= 0 && y <= sleevePx;
    }

    /** 槽位动画：弹簧积分（阻尼 0.9／刚度 1400，与页级回弹同参）趋向 target，收敛后回调。 */
    private void animateSlotTo(float target, Runnable onDone) {
        animating = true;
        final int gen = ++animGen;
        final float[] velocity = {0f};
        final long[] lastMs = {SystemClock.uptimeMillis()};
        final Choreographer.FrameCallback[] cb = new Choreographer.FrameCallback[1];
        cb[0] = new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                if (gen != animGen) return;
                long now = SystemClock.uptimeMillis();
                float dt = Math.min(0.05f, Math.max(0.001f, (now - lastMs[0]) / 1000f));
                lastMs[0] = now;
                double k = 1400d;
                double damp = 2d * 0.9d * Math.sqrt(k);
                velocity[0] += (float) ((k * (target - slotOffset) - damp * velocity[0]) * dt);
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
            }
        };
        choreographer.postFrameCallback(cb[0]);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    /** 供宿主在切歌后复位（位图已换，偏移归零不走动画）；自转对账一并作废，防陈账误配后来的同曲装配。 */
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

    // 颜色工具（宿主算 palette 时共用口径）：base 向 ink 混 mix
    public static int mixColor(int base, float mix, int to) {
        float f = Math.max(0f, Math.min(1f, mix));
        int r = Math.round(Color.red(base) + (Color.red(to) - Color.red(base)) * f);
        int g = Math.round(Color.green(base) + (Color.green(to) - Color.green(base)) * f);
        int b = Math.round(Color.blue(base) + (Color.blue(to) - Color.blue(base)) * f);
        return Color.rgb(r, g, b);
    }
}
