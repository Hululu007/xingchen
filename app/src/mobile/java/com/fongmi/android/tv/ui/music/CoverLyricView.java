package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.player.lyrics.LyricsLine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 播放页内嵌歌词（批 2 逐字版）：版式照 §6.2 封面歌词口径——窗口前 2 后 6、目标可见 4 行；
 * 字号默认 22sp 粗体（700）、行高＝字号×4/3、行距 20dp、横向边距由布局 padding 给（28dp）、左对齐；
 * 副行（翻译）字号＝主行×0.62、字重 500、默认开。
 * 逐字渲染走共享内核 LyricWordEngine＋LyricLineDrawer（逐字上浮 -0.09em、长音光晕、X-13 零时长守卫）；
 * 本视图是 WordByWordText 绘制路径（LyricLineDrawer.setImmersive(false)，默认）：长音缩放只给非 CJK，
 * 与全屏页的沉浸页路径口径相反，归属见 LyricLineDrawer 类头与批 2 补正报告；
 * 非当前行压暗（深色面非当前 白 0.30，即 palette 口径）＋模糊：档位系数 off 0／light 0.45／medium 1.0（§6.1），
 * 绘制半径＝round(强度×2)/2，API≥31 走 RenderEffect（RenderNode 录行），API＜31 走 BlurMaskFilter 等效
 * （旧引擎应用侧同口径）；强度随距离的源头公式在未反编译的 AnchoredLyricEngine 本体内（§19 第 2 项未抠到），
 * 距离项沿旧引擎应用侧等价式，见 drawIdleMain 注，不冒充瓜子公式。
 * 歌词字带一点斜：SKEW=-0.12（定案，非瓜子值），由 LyricLineDrawer 统一挂画笔。
 * 点行＝回调行首时间（定案点歌词行快进）；超长行截断加省略号（D-11 承接）。
 * 播放中自驱帧循环：以宿主 setPosition 为锚点按真实时间推进显示位置，逐字动画逐帧走，暂停即停。
 */
public class CoverLyricView extends View {

    public interface OnLineClickListener {
        void onLineClick(int index, long positionMs);
    }

    private static final int WINDOW_BEFORE = 2;
    private static final int WINDOW_AFTER = 6;
    private static final float LINE_GAP_DP = 20f;
    private static final long BLUR_RETRY_COOLDOWN_MS = 30000L;

    private final LyricLineDrawer drawer = new LyricLineDrawer();
    private final List<LyricsLine> lines = new ArrayList<>();
    private final List<String> subs = new ArrayList<>();
    private final Map<Integer, RenderEffect> blurEffectCache = new HashMap<>();
    private long blurRetryAfterMs;
    private int currentIndex = -1;
    private float fontSizeSp = 22f;
    private int blurLevel = 2;
    private boolean showSub = true;
    private boolean alignCenter;
    private String emptyText = "";
    private OnLineClickListener lineClickListener;

    private boolean playing;
    private long anchorPosMs;
    private long anchorElapsedMs;
    private boolean framePosted;
    private final Choreographer choreographer = Choreographer.getInstance();
    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            framePosted = false;
            if (!playing || lines.isEmpty()) return;
            updateCurrentIndex(displayPositionMs());
            invalidate();
            postFrame();
        }
    };

    public CoverLyricView(Context context) {
        super(context);
        init();
    }

    public CoverLyricView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        // API≥31 走 RenderEffect（需硬件加速层）；API＜31 的 BlurMaskFilter 需要软件层才稳定生效（旧引擎同口径）。
        setLayerType(Build.VERSION.SDK_INT >= 31 ? LAYER_TYPE_HARDWARE : LAYER_TYPE_SOFTWARE, null);
        applyFontSize();
    }

    private void applyFontSize() {
        float density = getResources().getDisplayMetrics().scaledDensity;
        drawer.configure(fontSizeSp * density, 0f, getResources().getDisplayMetrics().density);
    }

    public void setFontSizeSp(float sp) {
        fontSizeSp = Math.max(14f, Math.min(32f, sp));
        applyFontSize();
        invalidate();
    }

    /** 模糊档位（§6.1：0 关 1 轻 2 中，系数由 LyricWordEngine.blurCoefficient 给）。 */
    public void setBlurLevel(int level) {
        blurLevel = Math.max(0, Math.min(2, level));
        invalidate();
    }

    public void setAdvancedFx(boolean advancedFx) {
        drawer.setAdvancedFx(advancedFx);
        invalidate();
    }

    public void setPalette(LyricWordEngine.LyricPalette palette) {
        drawer.setPalette(palette);
        invalidate();
    }

    public void setShowSub(boolean show) {
        showSub = show;
        invalidate();
    }

    /** 居中对齐（批 3 封面卡片展开态歌词，§3：对齐居中；默认左对齐为经典口径）。 */
    public void setAlignCenter(boolean center) {
        alignCenter = center;
        invalidate();
    }

    /** 当前行序号（宿主玻璃条单行歌词取当前行文本用）。 */
    public int currentIndex() {
        return currentIndex;
    }

    public void setEmptyText(String text) {
        emptyText = text == null ? "" : text;
        invalidate();
    }

    public void setOnLineClickListener(OnLineClickListener listener) {
        lineClickListener = listener;
    }

    public void setLines(List<LyricsLine> mains, List<String> subLines) {
        lines.clear();
        subs.clear();
        if (mains != null) lines.addAll(mains);
        if (subLines != null) subs.addAll(subLines);
        currentIndex = -1;
        anchorPosMs = 0;
        anchorElapsedMs = SystemClock.elapsedRealtime();
        invalidate();
        postFrame();
    }

    public void clearLines() {
        lines.clear();
        subs.clear();
        currentIndex = -1;
        invalidate();
    }

    public void setPlaying(boolean value) {
        if (playing == value) return;
        // 暂停／续播都以当前显示位置重新锚定，避免续播时位置跳一截。
        anchorPosMs = displayPositionMs();
        anchorElapsedMs = SystemClock.elapsedRealtime();
        playing = value;
        postFrame();
        invalidate();
    }

    /** 当前行随播放进度推进（position 已含歌词偏移，调用方加）；同时作自驱帧循环的锚点。 */
    public void setPosition(long positionMs) {
        anchorPosMs = positionMs;
        anchorElapsedMs = SystemClock.elapsedRealtime();
        updateCurrentIndex(positionMs);
        invalidate();
        postFrame();
    }

    private long displayPositionMs() {
        if (!playing) return anchorPosMs;
        return anchorPosMs + (SystemClock.elapsedRealtime() - anchorElapsedMs);
    }

    private void updateCurrentIndex(long positionMs) {
        int idx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).getTimeMs() <= positionMs) idx = i;
            else break;
        }
        currentIndex = idx;
    }

    private void postFrame() {
        if (framePosted || !playing || lines.isEmpty()) return;
        framePosted = true;
        choreographer.postFrameCallback(frameCallback);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (framePosted) {
            choreographer.removeFrameCallback(frameCallback);
            framePosted = false;
        }
        super.onDetachedFromWindow();
    }

    private float mainLineHeightPx() {
        return drawer.mainTextSizePx() * LyricWordEngine.LINE_HEIGHT_RATIO;
    }

    private float subLineHeightPx() {
        return drawer.subTextSizePx() * LyricWordEngine.LINE_HEIGHT_RATIO;
    }

    private float gapPx() {
        return LINE_GAP_DP * getResources().getDisplayMetrics().density;
    }

    private float slotHeightPx() {
        return mainLineHeightPx() + (showSub ? subLineHeightPx() : 0f) + gapPx();
    }

    private int windowStart() {
        if (currentIndex < 0) return 0;
        return Math.max(0, currentIndex - WINDOW_BEFORE);
    }

    private long lineDurationMs(int index) {
        LyricsLine line = lines.get(index);
        if (index + 1 < lines.size()) return lines.get(index + 1).getTimeMs() - line.getTimeMs();
        if (line.hasWords()) {
            long end = 0;
            for (int i = 0; i < line.getWords().size(); i++) {
                end = Math.max(end, line.getWords().get(i).getEndOffsetMs());
            }
            if (end > 0) return end;
        }
        return 4000L;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        if (width <= 0) return;
        float x = getPaddingLeft();
        if (lines.isEmpty()) {
            if (!emptyText.isEmpty()) {
                drawer.drawSub(canvas, emptyText, x, getPaddingTop() + subLineHeightPx(), width, false);
            }
            return;
        }
        long pos = displayPositionMs();
        float slot = slotHeightPx();
        float avail = getHeight() - getPaddingTop() - getPaddingBottom();
        int maxRows = Math.max(1, (int) Math.floor(avail / slot));
        int start = windowStart();
        int end = Math.min(lines.size(), Math.min(start + WINDOW_AFTER + WINDOW_BEFORE + 1, start + maxRows));
        float baseline = getPaddingTop() + drawer.mainTextSizePx();
        float blockTop = getPaddingTop();
        for (int i = start; i < end; i++) {
            int distance = currentIndex < 0 ? i : Math.abs(i - currentIndex);
            boolean current = i == currentIndex;
            LyricsLine line = lines.get(i);
            float rowX = x;
            if (alignCenter) {
                float textW = drawer.measureMain(line.getText());
                rowX = x + Math.max(0f, (width - Math.min(width, textW)) / 2f);
            }
            if (current) {
                drawer.drawCurrentMain(canvas, line, pos - line.getTimeMs(), lineDurationMs(i),
                        rowX, baseline, width);
            } else {
                drawIdleMain(canvas, line.getText(), rowX, baseline, blockTop, width, distance);
            }
            if (showSub) {
                String sub = i < subs.size() ? subs.get(i) : null;
                if (sub != null && !sub.isEmpty()) {
                    float subBaseline = baseline + subLineHeightPx() * 0.92f;
                    float subX = x;
                    if (alignCenter) {
                        float subW = drawer.measureSub(sub);
                        subX = x + Math.max(0f, (width - Math.min(width, subW)) / 2f);
                    }
                    drawer.drawSub(canvas, sub, subX, subBaseline, width, current);
                }
            }
            baseline += slot;
            blockTop += slot;
        }
    }

    /** 非当前行：压暗＋模糊（距当前 ≥1 行即上模糊——批 2 补正对齐审计 §4：录屏 +1 行已明显模糊、
     * 与 FullLyricView 的距离 >0 口径合；原 ≥2 门槛作废。半径走 §6.1 量化）。
     * 强度随距离的源头公式在未反编译的引擎本体内（§19 第 2 项未抠到）：此处沿旧引擎应用侧等价式
     * min(2＋距离×2.2, 12)×档位系数（px），再按 §6.1 量化到 0.5 步进，不冒充瓜子公式。 */
    private void drawIdleMain(Canvas canvas, String text, float x, float baseline, float blockTop,
                              int width, int distance) {
        float strength = Math.min(2f + distance * 2.2f, 12f) * LyricWordEngine.blurCoefficient(blurLevel);
        float radius = LyricWordEngine.quantizeBlurRadius(strength);
        boolean blurWanted = distance >= 1 && radius > 0f;
        if (blurWanted && Build.VERSION.SDK_INT >= 31) {
            if (SystemClock.elapsedRealtime() >= blurRetryAfterMs
                    && drawIdleWithEffect(canvas, text, x, baseline, blockTop, width, radius)) {
                return;
            }
            // 无可用效果时不挂模糊、只压暗直绘（旧引擎 GZ 应用侧同口径）。
            drawer.drawIdleMain(canvas, text, x, baseline, width, 255);
            return;
        }
        if (blurWanted) {
            // API＜31：BlurMaskFilter 等效（同半径、同量化）。
            android.text.TextPaint paint = drawer.mainPaint();
            paint.setColor(drawer.palette().inactive);
            paint.setAlpha(255);
            paint.setMaskFilter(new android.graphics.BlurMaskFilter(radius, android.graphics.BlurMaskFilter.Blur.NORMAL));
            paint.setShader(null);
            CharSequence draw = android.text.TextUtils.ellipsize(text, paint, width, android.text.TextUtils.TruncateAt.END);
            canvas.drawText(draw, 0, draw.length(), x, baseline, paint);
            paint.setMaskFilter(null);
            paint.setAlpha(255);
            return;
        }
        drawer.drawIdleMain(canvas, text, x, baseline, width, 255);
    }

    private RenderEffect blurEffectFor(float radius) {
        int e = Math.round(radius * 2f);
        RenderEffect effect = blurEffectCache.get(e);
        if (effect != null) return effect;
        float r = e / 2f;
        try {
            effect = RenderEffect.createBlurEffect(r, r, Shader.TileMode.DECAL);
            blurEffectCache.put(e, effect);
            return effect;
        } catch (Exception ex) {
            blurRetryAfterMs = SystemClock.elapsedRealtime() + BLUR_RETRY_COOLDOWN_MS;
            return null;
        }
    }

    /** API≥31 非当前行：整行录进 RenderNode、挂 RenderEffect 后绘出；失败置 30s 冷却并回退直绘。
     * 注意：不许调 node.discardDisplayList()——硬件加速下显示列表在 onDraw 返回后才被渲染线程消费，
     * 立即 discard 会让行「绘制成功却空白」（旧引擎实证教训）。 */
    private boolean drawIdleWithEffect(Canvas canvas, String text, float x, float baseline, float blockTop,
                                       int width, float radius) {
        RenderEffect effect = blurEffectFor(radius);
        if (effect == null) return false;
        int w = getWidth();
        if (w <= 0) return false;
        int h = Math.max(1, Math.round(mainLineHeightPx() + 4f * getResources().getDisplayMetrics().density));
        RenderNode node = new RenderNode("coverLyricIdleBlur");
        try {
            node.setPosition(0, 0, w, h);
            Canvas rec = node.beginRecording(w, h);
            android.text.TextPaint paint = new android.text.TextPaint(drawer.mainPaint());
            paint.setColor(drawer.palette().inactive);
            paint.setAlpha(255);
            paint.setMaskFilter(null);
            CharSequence draw = android.text.TextUtils.ellipsize(text, paint, width, android.text.TextUtils.TruncateAt.END);
            rec.drawText(draw, 0, draw.length(), x, baseline - blockTop, paint);
            node.endRecording();
            node.setRenderEffect(effect);
            int save = canvas.save();
            canvas.translate(0f, blockTop);
            canvas.drawRenderNode(node);
            canvas.restoreToCount(save);
            return true;
        } catch (Exception ex) {
            blurRetryAfterMs = SystemClock.elapsedRealtime() + BLUR_RETRY_COOLDOWN_MS;
            return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP && lineClickListener != null && !lines.isEmpty()) {
            float y = event.getY() - getPaddingTop();
            if (y >= 0) {
                int row = (int) Math.floor(y / slotHeightPx());
                int idx = windowStart() + row;
                if (idx >= 0 && idx < lines.size()) {
                    lineClickListener.onLineClick(idx, lines.get(idx).getTimeMs());
                    performClick();
                }
            }
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
