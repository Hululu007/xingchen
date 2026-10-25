package com.fongmi.android.tv.ui.music;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;

import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.player.lyrics.LyricsWord;

import java.util.ArrayList;
import java.util.List;

/**
 * 歌词行绘：画笔持有与单行绘制（主行/副行/当前行逐字/当前行整行分段）。
 *
 * 口径（gz-full-parity-1025 §7.1、§15.1）：
 * 字重映射 0→700 / 1→800 / 2→900（瓜子 lyricFontWeightOf，§13.1 纠正）；副行字重 500、字号＝主行×0.62。
 * 斜度 −0.12 统一挂画笔（他定案）。逐字上浮/长音光晕/缩放全走 LyricWordEngine，advancedFx 关则只分段着色。
 * 非逐字当前行：整行 activeUnsung 打底＋已唱段 sung 色覆盖（按时长比例定已唱宽度）。
 * 模糊：半径由视图侧算好（量化后）传入；消费门 SDK≥31 才上模糊、低版本只压暗（§15.1 第 6 项）。
 * 本类逐行模糊以 BlurMaskFilter 实现——RenderEffect 是视图级接口、无法逐行挂载，此为实现层近似，在此注明。
 */
public final class LyricLineDrawer {

    private final Paint mainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF clipRect = new RectF();

    private float fontSizePx = 0f;
    private float lineHeightRatio = LyricWordEngine.LINE_HEIGHT_RATIO;
    private float letterSpacingSp = 0f;
    private int lyricWeight = 0;
    private int textAlign = Paint.Align.LEFT.ordinal();
    private boolean advancedFx = true;
    private boolean immersive = false;
    private LyricWordEngine.LyricPalette palette = LyricWordEngine.LyricPalette.darkFallback();

    public LyricLineDrawer() {
        LyricWordEngine.applySkew(mainPaint);
        LyricWordEngine.applySkew(subPaint);
        mainPaint.setStyle(Paint.Style.FILL);
        subPaint.setStyle(Paint.Style.FILL);
    }

    /** 基础配置：字号（px）与对齐（Paint.Align.ordinal 口径：0 左 / 1 中 / 2 右）。 */
    public void configure(float fontSizePx, int textAlign) {
        this.fontSizePx = fontSizePx;
        this.textAlign = textAlign;
        applyTypefaces();
    }

    public void setLineHeightRatio(float ratio) {
        this.lineHeightRatio = ratio;
    }

    /** 字距（sp 的 px 换算由调用方做：全屏面 −0.2sp）。 */
    public void setLetterSpacingPx(float px) {
        this.letterSpacingSp = px;
        applyLetterSpacing();
    }

    public void setLyricWeight(int weight) {
        this.lyricWeight = Math.max(0, Math.min(2, weight));
        applyTypefaces();
    }

    public void setPalette(LyricWordEngine.LyricPalette palette) {
        if (palette != null) this.palette = palette;
    }

    public LyricWordEngine.LyricPalette palette() {
        return palette;
    }

    public void setAdvancedFx(boolean advancedFx) {
        this.advancedFx = advancedFx;
    }

    /** 沉浸（全屏）面标记：只影响字距默认值口径的记录，不改绘制结构。 */
    public void setImmersive(boolean immersive) {
        this.immersive = immersive;
    }

    public boolean isImmersive() {
        return immersive;
    }

    public float fontSizePx() {
        return fontSizePx;
    }

    public float lineHeightPx() {
        return fontSizePx * lineHeightRatio;
    }

    public float subFontSizePx() {
        return fontSizePx * LyricWordEngine.SUB_RATIO;
    }

    public float subLineHeightPx() {
        return subFontSizePx() * LyricWordEngine.LINE_HEIGHT_RATIO;
    }

    private void applyTypefaces() {
        int weightValue = lyricWeight == 2 ? 900 : lyricWeight == 1 ? 800 : 700;
        mainPaint.setTextSize(fontSizePx);
        mainPaint.setTypeface(weightTypeface(weightValue));
        subPaint.setTextSize(subFontSizePx());
        subPaint.setTypeface(weightTypeface(LyricWordEngine.SUB_WEIGHT));
        applyLetterSpacing();
    }

    private void applyLetterSpacing() {
        if (fontSizePx > 0f) {
            mainPaint.setLetterSpacing(letterSpacingSp / fontSizePx);
        }
    }

    private static Typeface weightTypeface(int weightValue) {
        if (Build.VERSION.SDK_INT >= 28) {
            return Typeface.create(Typeface.SANS_SERIF, weightValue, false);
        }
        return Typeface.create(Typeface.SANS_SERIF, weightValue >= 700 ? Typeface.BOLD : Typeface.NORMAL);
    }

    // ---------------- 测量 / 断行 ----------------

    public float measureMain(String text) {
        return text == null ? 0f : mainPaint.measureText(text);
    }

    public float measureSub(String text) {
        return text == null ? 0f : subPaint.measureText(text);
    }

    /** 主行按可用宽断行（逐字行不走此路，断行只服务整行文本）。 */
    public List<String> breakMain(String text, float maxWidth) {
        return breakWith(mainPaint, text, maxWidth);
    }

    public List<String> breakSub(String text, float maxWidth) {
        return breakWith(subPaint, text, maxWidth);
    }

    private static List<String> breakWith(Paint paint, String text, float maxWidth) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            out.add("");
            return out;
        }
        if (maxWidth <= 0f) {
            out.add(text);
            return out;
        }
        int start = 0;
        int length = text.length();
        while (start < length) {
            int count = paint.breakText(text, start, length, true, maxWidth, null);
            if (count <= 0) count = 1;
            out.add(text.substring(start, start + count));
            start += count;
        }
        if (out.isEmpty()) out.add("");
        return out;
    }

    /** 行左起点 x：按对齐与可用宽算（alignCenter 或 Paint.Align 口径）。 */
    public float lineStartX(float width, float availableWidth, float originX) {
        if (textAlign == Paint.Align.CENTER.ordinal()) return originX + Math.max(0f, (availableWidth - width) / 2f);
        if (textAlign == Paint.Align.RIGHT.ordinal()) return originX + Math.max(0f, availableWidth - width);
        return originX;
    }

    // ---------------- 绘制 ----------------

    private void setMainColor(int color, float alphaFactor, float blurRadiusPx) {
        mainPaint.setColor(LyricWordEngine.withAlphaFactor(color, alphaFactor));
        applyBlur(mainPaint, blurRadiusPx);
    }

    private void applyBlur(Paint paint, float blurRadiusPx) {
        // 消费门：SDK≥31 且量化半径为正才上模糊；低版本只压暗（§15.1 第 6 项）。
        if (Build.VERSION.SDK_INT >= 31 && blurRadiusPx > 0f) {
            paint.setMaskFilter(new BlurMaskFilter(blurRadiusPx, BlurMaskFilter.Blur.NORMAL));
        } else {
            paint.setMaskFilter(null);
        }
    }

    /** 非当前主行（可多行断行）：inactive 色，alphaFactor 为行 alpha 乘积，blurRadiusPx 为量化后半径。 */
    public void drawIdleMain(Canvas canvas, List<String> visualLines, float originX, float topY,
                             float availableWidth, float alphaFactor, float blurRadiusPx) {
        setMainColor(palette.inactive, alphaFactor, blurRadiusPx);
        float lineHeight = lineHeightPx();
        Paint.FontMetrics fm = mainPaint.getFontMetrics();
        for (int i = 0; i < visualLines.size(); i++) {
            String segment = visualLines.get(i);
            float x = lineStartX(measureMain(segment), availableWidth, originX);
            // baseline：行盒顶 + (行高 − 字高)/2 − ascent
            float baseline = topY + i * lineHeight + (lineHeight - (fm.descent - fm.ascent)) / 2f - fm.ascent;
            canvas.drawText(segment, x, baseline, mainPaint);
        }
        mainPaint.setMaskFilter(null);
    }

    /** 副行（翻译/罗马音整行）：translation 色、字重 500、字号主行×0.62。 */
    public void drawSub(Canvas canvas, List<String> visualLines, float originX, float topY,
                        float availableWidth, float alphaFactor, float blurRadiusPx) {
        subPaint.setColor(LyricWordEngine.withAlphaFactor(palette.translation, alphaFactor));
        applyBlur(subPaint, blurRadiusPx);
        float lineHeight = subLineHeightPx();
        Paint.FontMetrics fm = subPaint.getFontMetrics();
        for (int i = 0; i < visualLines.size(); i++) {
            String segment = visualLines.get(i);
            float x = lineStartX(measureSub(segment), availableWidth, originX);
            float baseline = topY + i * lineHeight + (lineHeight - (fm.descent - fm.ascent)) / 2f - fm.ascent;
            canvas.drawText(segment, x, baseline, subPaint);
        }
        subPaint.setMaskFilter(null);
    }

    /**
     * 当前行（有逐字）：逐词绘。未唱词 activeUnsung、唱完词 sung、唱中词按进度裁剪叠 sung。
     * advancedFx 开：逐字上浮 + 长音光晕（阴影层）+ 长音缩放；关：只分段着色。
     */
    public void drawCurrentWordRow(Canvas canvas, LyricsLine line, long positionMs,
                                   float originX, float topY, float availableWidth) {
        List<LyricsWord> words = line.getWords();
        float totalWidth = 0f;
        for (LyricsWord word : words) totalWidth += mainPaint.measureText(word.getText());
        float x = lineStartX(totalWidth, availableWidth, originX);
        float lineHeight = lineHeightPx();
        Paint.FontMetrics fm = mainPaint.getFontMetrics();
        float baseline = topY + (lineHeight - (fm.descent - fm.ascent)) / 2f - fm.ascent;
        long lineStart = line.getTimeMs();
        mainPaint.setMaskFilter(null);
        for (LyricsWord word : words) {
            String text = word.getText();
            float wordWidth = mainPaint.measureText(text);
            if (wordWidth <= 0f) continue;
            LyricWordEngine.WordState state = LyricWordEngine.wordState(lineStart, word, positionMs);
            float liftPx = 0f;
            float glowLiftPx = 0f;
            float scale = 1f;
            if (advancedFx) {
                liftPx = LyricWordEngine.wordLiftEm(word, positionMs, lineStart) * fontSizePx;
                glowLiftPx = LyricWordEngine.glowLiftEm(word, positionMs, lineStart) * fontSizePx;
                scale = LyricWordEngine.wordScale(word, positionMs, lineStart);
            }
            float drawY = baseline + liftPx + glowLiftPx;
            int save = canvas.save();
            if (scale != 1f) canvas.scale(scale, scale, x + wordWidth / 2f, baseline);
            boolean glow = advancedFx && LyricWordEngine.glowActive(word, positionMs, lineStart);
            if (glow) {
                float radius = Math.max(1f, fontSizePx * 0.12f);
                mainPaint.setShadowLayer(radius, 0f, 0f,
                        LyricWordEngine.withAlphaFactor(palette.sung, 0.55f));
            } else {
                mainPaint.clearShadowLayer();
            }
            if (!state.started) {
                mainPaint.setColor(palette.activeUnsung);
                canvas.drawText(text, x, drawY, mainPaint);
            } else if (state.finished) {
                mainPaint.setColor(palette.sung);
                canvas.drawText(text, x, drawY, mainPaint);
            } else {
                mainPaint.setColor(palette.activeUnsung);
                canvas.drawText(text, x, drawY, mainPaint);
                float sungWidth = wordWidth * state.progress;
                if (sungWidth > 0f) {
                    clipRect.set(x, topY - fontSizePx, x + sungWidth, topY + lineHeight + fontSizePx);
                    canvas.save();
                    canvas.clipRect(clipRect);
                    mainPaint.setColor(palette.sung);
                    canvas.drawText(text, x, drawY, mainPaint);
                    canvas.restore();
                }
            }
            mainPaint.clearShadowLayer();
            canvas.restoreToCount(save);
            x += wordWidth;
        }
    }

    /**
     * 当前行（无逐字）：整行 activeUnsung 打底 + 已唱段 sung 覆盖。
     * 已唱比例＝(positionMs − 行始) / lineDurationMs，钳 0..1。
     */
    public void drawCurrentPlain(Canvas canvas, LyricsLine line, long positionMs, long lineDurationMs,
                                 float originX, float topY, float availableWidth) {
        List<String> visualLines = breakMain(line.getText(), availableWidth);
        float fraction = lineDurationMs <= 0 ? 1f
                : LyricWordEngine.clamp01((positionMs - line.getTimeMs()) / (float) lineDurationMs);
        float lineHeight = lineHeightPx();
        Paint.FontMetrics fm = mainPaint.getFontMetrics();
        mainPaint.setMaskFilter(null);
        // 已唱宽度按整行总宽×比例逐行顺序消费（单行时即行宽×比例）。
        float sungBudget = totalWidth(visualLines) * fraction;
        for (int i = 0; i < visualLines.size(); i++) {
            String segment = visualLines.get(i);
            float width = measureMain(segment);
            float x = lineStartX(width, availableWidth, originX);
            float baseline = topY + i * lineHeight + (lineHeight - (fm.descent - fm.ascent)) / 2f - fm.ascent;
            mainPaint.setColor(palette.activeUnsung);
            canvas.drawText(segment, x, baseline, mainPaint);
            if (sungBudget > 0f && width > 0f) {
                float sungWidth = Math.min(width, sungBudget);
                clipRect.set(x, topY + i * lineHeight - fontSizePx, x + sungWidth,
                        topY + (i + 1) * lineHeight + fontSizePx);
                int save = canvas.save();
                canvas.clipRect(clipRect);
                mainPaint.setColor(palette.sung);
                canvas.drawText(segment, x, baseline, mainPaint);
                canvas.restoreToCount(save);
                sungBudget -= sungWidth;
            }
        }
    }

    private float totalWidth(List<String> visualLines) {
        float total = 0f;
        for (String s : visualLines) total += measureMain(s);
        return total;
    }

    /** 当前行分发：有逐字走逐字行、无逐字走整行分段。 */
    public void drawCurrentMain(Canvas canvas, LyricsLine line, long positionMs, long lineDurationMs,
                                float originX, float topY, float availableWidth) {
        if (line.hasWords()) {
            drawCurrentWordRow(canvas, line, positionMs, originX, topY, availableWidth);
        } else {
            drawCurrentPlain(canvas, line, positionMs, lineDurationMs, originX, topY, availableWidth);
        }
    }
}


/**
 * 歌词面共用骨架（包私有）：行块布局、弹簧跟随、手动浏览＋回锚、点行、逐字位置插值。
 *
 * 跟随口径（gz-full-parity-1025 §15.1 第 1/6/7 项）：AnchoredLyricEngine 同构——
 * 值已等目标不动；|值−目标|＞视高×1.5 → snapTo；其余按面选档弹簧 animateTo。
 * 回锚延时分面：封面面 3000ms、全屏面 5000ms（子类给值）。
 * 位置插值：宿主 setPosition 为锚点，播放中按真实时间推进，保证逐字平滑不依赖宿主调用频率。
 * 手势只有竖向滚动＋点行，无任何横向手势（§1 定死）。
 */
abstract class LyricSurfaceBase extends android.view.View {

    /** 副行挂载模式：每行都挂 / 只挂正唱行。 */
    enum SubMode {ALL_ROWS, CURRENT_ONLY}

    /** 弹簧选档模式：全屏 settle/seek 双档 / 封面路 cover 档。 */
    enum SpringKind {SETTLE_SEEK, COVER}

    protected final LyricLineDrawer drawer = new LyricLineDrawer();
    protected final LyricScrollSpring spring = LyricScrollSpring.settle();
    protected final LyricRowApproach blurApproach = new LyricRowApproach();

    protected java.util.List<LyricsLine> lines = java.util.Collections.emptyList();
    protected java.util.List<String> subLines = java.util.Collections.emptyList();
    protected boolean showSub = true;
    protected boolean playing = false;
    protected String emptyText = "";

    private OnLineClickListener lineClickListener;

    private long positionAnchorMs = 0L;
    private long positionAnchorTime = 0L;
    private long lastHostPositionMs = -1L;
    private boolean seekPending = false;
    private int currentIdx = -1;

    private boolean layoutDirty = true;
    private float[] blockTops = new float[0];
    private float[] blockHeights = new float[0];
    private java.util.List<java.util.List<String>> mainVisual = new java.util.ArrayList<>();
    private java.util.List<java.util.List<String>> subVisual = new java.util.ArrayList<>();
    private float totalHeight = 0f;

    private boolean browsing = false;
    private boolean reanchorPending = false;
    private float manualOffset = 0f;
    private float downX = 0f;
    private float downY = 0f;
    private float gestureStartManual = 0f;
    private boolean gestureDragged = false;

    private boolean loopRunning = false;
    private long lastFrameTimeNanos = 0L;

    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable reanchorRunnable = new Runnable() {
        @Override
        public void run() {
            reanchorPending = false;
            foldManualAndFollow();
        }
    };
    private final android.view.Choreographer.FrameCallback frameCallback = new android.view.Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            loopRunning = false;
            tick(frameTimeNanos);
        }
    };

    protected LyricSurfaceBase(android.content.Context context) {
        super(context);
    }

    protected LyricSurfaceBase(android.content.Context context, android.util.AttributeSet attrs) {
        super(context, attrs);
    }

    // ---- 子类口径 ----
    protected abstract float gapPx();

    protected abstract SubMode subMode();

    protected abstract SpringKind springKind();

    protected abstract long reanchorDelayMs();

    protected abstract boolean allowManualScroll();

    /** 锚行相对当前行的偏移（封面 −2、CD −1、全屏/唱片架 0）。 */
    protected abstract int anchorIndexOffset();

    /** 副行高缩放（全屏 anchorReveal 用），默认 1。 */
    protected float subHeightScale(int row, long nowMs) {
        return 1f;
    }

    /** 点空白是否消费（CD 窗不消费、交宿主收起），默认消费。 */
    protected boolean consumeTapMiss() {
        return true;
    }

    /** 只画窗口内的行（封面面 [锚−? ] 由子类给范围），默认全画。 */
    protected boolean rowInWindow(int row, int current) {
        return true;
    }

    protected float density() {
        return getResources().getDisplayMetrics().density;
    }

    protected float scaledDensity() {
        return getResources().getDisplayMetrics().scaledDensity;
    }

    // ---- 对外契约（四面通用） ----

    public interface OnLineClickListener {
        void onLineClick(int index, long positionMs);
    }

    public void setOnLineClickListener(OnLineClickListener listener) {
        this.lineClickListener = listener;
    }

    public void setLines(java.util.List<LyricsLine> mains, java.util.List<String> subs) {
        lines = mains == null ? java.util.Collections.emptyList() : new java.util.ArrayList<>(mains);
        subLines = subs == null ? java.util.Collections.emptyList() : new java.util.ArrayList<>(subs);
        // 切歌/换词：清上一首的滚动与浏览残留，复位到当前行（他定案：切歌清词、连切两首都有词）。
        handler.removeCallbacks(reanchorRunnable);
        reanchorPending = false;
        browsing = false;
        manualOffset = 0f;
        blurApproach.reset();
        layoutDirty = true;
        currentIdx = LyricWordEngine.indexAt(lines, drawPositionMs());
        requestLayout();
        invalidate();
        snapToAnchor();
        ensureLoop();
    }

    public void clearLines() {
        setLines(java.util.Collections.emptyList(), java.util.Collections.emptyList());
    }

    public void setPlaying(boolean playing) {
        if (this.playing == playing) return;
        // 以当前显示位置为锚重新起算，避免暂停/恢复跳变。
        positionAnchorMs = drawPositionMs();
        positionAnchorTime = android.os.SystemClock.uptimeMillis();
        this.playing = playing;
        ensureLoop();
        invalidate();
    }

    public void setPosition(long ms) {
        long previous = lastHostPositionMs;
        lastHostPositionMs = ms;
        positionAnchorMs = Math.max(0L, ms);
        positionAnchorTime = android.os.SystemClock.uptimeMillis();
        if (previous >= 0L && Math.abs(ms - previous) > 2500L) seekPending = true;
        int idx = LyricWordEngine.indexAt(lines, ms);
        if (idx != currentIdx) {
            currentIdx = idx;
            layoutDirty = true; // 副行只挂正唱行的面要重排
            if (!browsing && !reanchorPending) followCurrent();
        }
        ensureLoop();
        invalidate();
    }

    public void setLyricWeight(int weight) {
        drawer.setLyricWeight(weight);
        layoutDirty = true;
        invalidate();
    }

    public void setShowSub(boolean show) {
        if (this.showSub == show) return;
        this.showSub = show;
        layoutDirty = true;
        invalidate();
    }

    public void setEmptyText(String text) {
        this.emptyText = text == null ? "" : text;
        invalidate();
    }

    public int currentIndex() {
        return currentIdx;
    }

    protected long drawPositionMs() {
        if (!playing) return positionAnchorMs;
        long elapsed = android.os.SystemClock.uptimeMillis() - positionAnchorTime;
        if (elapsed < 0L) elapsed = 0L;
        return positionAnchorMs + elapsed;
    }

    // ---- 布局（行块） ----

    protected void markLayoutDirty() {
        layoutDirty = true;
        invalidate();
    }

    private void ensureLayout() {
        if (!layoutDirty) return;
        layoutDirty = false;
        int count = lines.size();
        blockTops = new float[count];
        blockHeights = new float[count];
        mainVisual = new java.util.ArrayList<>(count);
        subVisual = new java.util.ArrayList<>(count);
        float available = Math.max(0f, getWidth() - getPaddingLeft() - getPaddingRight());
        float y = 0f;
        long now = android.os.SystemClock.uptimeMillis();
        for (int i = 0; i < count; i++) {
            LyricsLine line = lines.get(i);
            java.util.List<String> mainLines;
            if (line.hasWords()) {
                mainLines = java.util.Collections.singletonList(line.getText());
            } else {
                mainLines = drawer.breakMain(line.getText(), available);
            }
            mainVisual.add(mainLines);
            float height = mainLines.size() * drawer.lineHeightPx();
            String sub = subText(i);
            java.util.List<String> subLinesVisual = java.util.Collections.emptyList();
            if (sub != null) {
                subLinesVisual = drawer.breakSub(sub, available);
                height += subLinesVisual.size() * drawer.subLineHeightPx() * subHeightScale(i, now);
            }
            subVisual.add(subLinesVisual);
            blockTops[i] = y;
            blockHeights[i] = height;
            y += height + gapPx();
        }
        totalHeight = count == 0 ? 0f : y - gapPx();
    }

    private String subText(int row) {
        if (!showSub) return null;
        if (subMode() == SubMode.CURRENT_ONLY && row != currentIdx) return null;
        if (row < 0 || row >= subLines.size()) return null;
        String sub = subLines.get(row);
        return sub == null || sub.isEmpty() ? null : sub;
    }

    protected float anchorTarget() {
        ensureLayout();
        if (lines.isEmpty() || blockTops.length == 0) return 0f;
        int anchorRow = currentIdx + anchorIndexOffset();
        if (anchorRow < 0) anchorRow = 0;
        if (anchorRow >= blockTops.length) anchorRow = blockTops.length - 1;
        return blockTops[anchorRow];
    }

    private void snapToAnchor() {
        spring.snapTo(anchorTarget());
    }

    private void followCurrent() {
        float target = anchorTarget();
        float viewH = getHeight();
        if (viewH > 0f && Math.abs(target - effectiveScroll()) > viewH * 1.5f) {
            spring.snapTo(target);
            return;
        }
        if (springKind() == SpringKind.COVER) {
            long delta = 450L;
            if (currentIdx > 0 && currentIdx < lines.size()) {
                delta = lines.get(currentIdx).getTimeMs() - lines.get(currentIdx - 1).getTimeMs();
                if (delta <= 0L) delta = 450L;
            }
            spring.retarget(LyricWordEngine.amllPosYComposeStiffness(delta) * 1.1f,
                    LyricScrollSpring.COVER_DAMPING, target);
        } else if (seekPending) {
            spring.retarget(LyricScrollSpring.SEEK_STIFFNESS, LyricScrollSpring.SEEK_DAMPING, target);
        } else {
            spring.retarget(LyricScrollSpring.SETTLE_STIFFNESS, LyricScrollSpring.SETTLE_DAMPING, target);
        }
        seekPending = false;
        ensureLoop();
    }

    protected float effectiveScroll() {
        return spring.value() + manualOffset;
    }

    // ---- 帧循环 ----

    protected void ensureLoop() {
        if (loopRunning) return;
        if (!playing && !spring.active() && !blurApproach.animating(android.os.SystemClock.uptimeMillis())) return;
        loopRunning = true;
        lastFrameTimeNanos = 0L;
        android.view.Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private void tick(long frameTimeNanos) {
        float dt = 0f;
        if (lastFrameTimeNanos != 0L) {
            dt = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f;
            if (dt > 0.05f) dt = 0.05f;
            if (dt < 0f) dt = 0f;
        }
        lastFrameTimeNanos = frameTimeNanos;
        if (dt > 0f) spring.step(dt);
        int idx = LyricWordEngine.indexAt(lines, drawPositionMs());
        if (idx != currentIdx) {
            currentIdx = idx;
            if (subMode() == SubMode.CURRENT_ONLY) layoutDirty = true;
            if (!browsing && !reanchorPending) followCurrent();
        }
        invalidate();
        long now = android.os.SystemClock.uptimeMillis();
        if (playing || spring.active() || blurApproach.animating(now) || revealAnimating(now)) {
            loopRunning = true;
            android.view.Choreographer.getInstance().postFrameCallback(frameCallback);
        }
    }

    /** 子类附加动画（如全屏副行展开）是否在动。 */
    protected boolean revealAnimating(long nowMs) {
        return false;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacks(reanchorRunnable);
        android.view.Choreographer.getInstance().removeFrameCallback(frameCallback);
        loopRunning = false;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutDirty = true;
        if (!browsing) snapToAnchor();
    }

    // ---- 手势：竖滚浏览 + 点行（无横向手势） ----

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        switch (event.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN: {
                if (reanchorPending) {
                    handler.removeCallbacks(reanchorRunnable);
                    reanchorPending = false;
                    foldManualAndFollow();
                }
                downX = event.getX();
                downY = event.getY();
                gestureStartManual = manualOffset;
                gestureDragged = false;
                return true;
            }
            case android.view.MotionEvent.ACTION_MOVE: {
                float dy = event.getY() - downY;
                float dx = event.getX() - downX;
                if (!gestureDragged && Math.abs(dy) > touchSlop() && Math.abs(dy) > Math.abs(dx)) {
                    gestureDragged = true;
                    if (allowManualScroll()) browsing = true;
                }
                if (gestureDragged && allowManualScroll()) {
                    manualOffset = clampManual(gestureStartManual - dy);
                    invalidate();
                }
                return true;
            }
            case android.view.MotionEvent.ACTION_UP: {
                float dy = event.getY() - downY;
                float dx = event.getX() - downX;
                boolean tap = !gestureDragged && Math.abs(dy) < touchSlop() * 2f && Math.abs(dx) < touchSlop() * 2f;
                if (tap) {
                    return handleTap(event.getY());
                }
                if (gestureDragged && allowManualScroll()) {
                    reanchorPending = true;
                    handler.removeCallbacks(reanchorRunnable);
                    handler.postDelayed(reanchorRunnable, reanchorDelayMs());
                }
                return true;
            }
            case android.view.MotionEvent.ACTION_CANCEL: {
                if (gestureDragged && allowManualScroll() && manualOffset != 0f) {
                    reanchorPending = true;
                    handler.removeCallbacks(reanchorRunnable);
                    handler.postDelayed(reanchorRunnable, reanchorDelayMs());
                }
                return true;
            }
            default:
                return super.onTouchEvent(event);
        }
    }

    private float touchSlop() {
        return android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
    }

    private float clampManual(float value) {
        float maxDown = Math.max(0f, totalHeight);
        if (value > maxDown) value = maxDown;
        float minUp = -Math.max(0f, totalHeight);
        if (value < minUp) value = minUp;
        return value;
    }

    private void foldManualAndFollow() {
        if (manualOffset != 0f) {
            float viewH = getHeight();
            if (viewH > 0f && Math.abs(manualOffset) > viewH * 1.5f) {
                manualOffset = 0f;
                spring.snapTo(anchorTarget());
            } else {
                spring.snapTo(effectiveScroll());
                manualOffset = 0f;
            }
        }
        browsing = false;
        followCurrent();
        invalidate();
    }

    private boolean handleTap(float y) {
        ensureLayout();
        float contentY = y - getPaddingTop() + effectiveScroll();
        for (int i = 0; i < blockTops.length; i++) {
            if (contentY >= blockTops[i] && contentY < blockTops[i] + blockHeights[i] + gapPx()) {
                if (lineClickListener != null) {
                    lineClickListener.onLineClick(i, lines.get(i).getTimeMs());
                }
                return true;
            }
        }
        return consumeTapMiss();
    }

    // ---- 绘制 ----

    @Override
    protected void onDraw(android.graphics.Canvas canvas) {
        super.onDraw(canvas);
        ensureLayout();
        float originX = getPaddingLeft();
        float available = Math.max(0f, getWidth() - getPaddingLeft() - getPaddingRight());
        if (lines.isEmpty()) {
            if (!emptyText.isEmpty()) {
                float top = Math.max(0f, (getHeight() - drawer.lineHeightPx()) / 2f);
                drawer.drawIdleMain(canvas, java.util.Collections.singletonList(emptyText),
                        originX, top, available, 1f, 0f);
            }
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        long position = drawPositionMs();
        float scroll = effectiveScroll();
        float topPad = getPaddingTop();
        int current = currentIdx;
        float blurCoefficient = blurCoefficient();
        for (int i = 0; i < lines.size(); i++) {
            if (!rowInWindow(i, current)) continue;
            float rowTop = topPad + blockTops[i] - scroll;
            float rowBottom = rowTop + blockHeights[i];
            if (rowBottom < -drawer.lineHeightPx() || rowTop > getHeight() + drawer.lineHeightPx()) continue;
            LyricsLine line = lines.get(i);
            if (i == current) {
                long duration = lineDurationMs(i);
                drawer.drawCurrentMain(canvas, line, position, duration, originX, rowTop, available);
                String sub = subText(i);
                if (sub != null) {
                    float subTop = rowTop + mainVisual.get(i).size() * drawer.lineHeightPx();
                    // 副行展开（全屏 anchorReveal）期间裁到行块底，不压下一行。
                    int save = canvas.save();
                    canvas.clipRect(0f, rowTop, getWidth(), rowTop + blockHeights[i]);
                    drawer.drawSub(canvas, subVisual.get(i), originX, subTop, available, 1f, 0f);
                    canvas.restoreToCount(save);
                }
            } else {
                int rel = i - Math.max(0, current);
                float strengthTarget = LyricWordEngine.distanceBlurStrength(rel);
                float animated = blurApproach.value(blurKey(i), strengthTarget, false, now);
                float radius = 0f;
                float rowAlpha = 1f;
                float alphaProduct = rowAlpha * (android.graphics.Color.alpha(drawer.palette().inactive) / 255f);
                if (alphaProduct >= LyricWordEngine.BLUR_ALPHA_GATE) {
                    radius = LyricWordEngine.blurStrength(animated * blurCoefficient);
                    if (radius < 0.5f) radius = 0f;
                }
                drawer.drawIdleMain(canvas, mainVisual.get(i), originX, rowTop, available, rowAlpha, radius);
                String sub = subText(i);
                if (sub != null) {
                    float subTop = rowTop + mainVisual.get(i).size() * drawer.lineHeightPx();
                    drawer.drawSub(canvas, subVisual.get(i), originX, subTop, available, rowAlpha, radius);
                }
            }
        }
        if (blurApproach.animating(now)) ensureLoop();
    }

    private int blurKey(int row) {
        return row * 31 + 7;
    }

    protected long lineDurationMs(int index) {
        if (index < 0 || index >= lines.size()) return 450L;
        if (index + 1 < lines.size()) {
            long delta = lines.get(index + 1).getTimeMs() - lines.get(index).getTimeMs();
            return delta > 0L ? delta : 450L;
        }
        return 5000L;
    }

    /** 模糊档位系数（子类由 setBlurLevel 写入）。 */
    protected float blurCoefficient() {
        return blurCoefficientValue;
    }

    private float blurCoefficientValue = LyricWordEngine.BLUR_MEDIUM;

    protected void setBlurCoefficient(float coefficient) {
        this.blurCoefficientValue = coefficient;
        invalidate();
    }
}
