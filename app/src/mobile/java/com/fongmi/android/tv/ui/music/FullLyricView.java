package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Scroller;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.player.lyrics.LyricsWord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 全屏歌词列表（批 2）：与内嵌 CoverLyricView 共用 LyricWordEngine＋LyricLineDrawer 内核。
 * 版式照 §6.3／§6.1 全屏口径：字号默认 24sp（可调 16–40）、字重 700、左对齐、字距 -0.2sp、
 * 行高＝字号×1.3、横向垫 28dp（布局 padding 给）、行距 6dp、顶渐隐 40dp；
 * 副行随行（字号×0.62、行高＝副行字号×4/3、字重 500）；当前行锚定在渐隐区下方（top 档口径），
 * 沉浸滚动跟随；手指拖动浏览，松手 5 秒回锚（旧引擎 APPLE_BROWSE_REANCHOR_DELAY 同值）；
 * 点行＝seek（定案）；非当前行模糊＋压暗（档位系数与半径量化同内嵌，模糊强度↔距离公式未抠到、
 * 沿旧引擎应用侧等价式不动；压暗口径经批 2 补正改向录屏平台，见 drawIdleRow 注）；
 * 字斜 SKEW=-0.12 定案（LyricLineDrawer 统一挂）。
 * 本视图是沉浸页绘制路径（LiveWordLiftLine，LyricLineDrawer.setImmersive(true) 在 init 置位）：
 * 长音缩放只给单字 CJK＋逐字错峰＋正弦字母浮，与内嵌的 WordByWordText 路径口径相反，归属见补正报告。
 * 长行按词／字宽折行（不截断——全屏是沉浸通读，折行口径照录屏实帧当前行折行形态；
 * 内嵌窗口的 D-11 截断省略只在 CoverLyricView）。
 */
public class FullLyricView extends View {

    public interface OnLineClickListener {
        void onLineClick(int index, long positionMs);
    }

    private static final float LINE_GAP_DP = 6f;
    private static final float TOP_FADE_DP = 40f;
    private static final float LINE_HEIGHT_RATIO_FULL = 1.3f;
    private static final long REANCHOR_MS = 5000L;
    private static final long BLUR_RETRY_COOLDOWN_MS = 30000L;

    private final LyricLineDrawer drawer = new LyricLineDrawer();
    private final List<LyricsLine> lines = new ArrayList<>();
    private final List<String> subs = new ArrayList<>();
    private final List<LineLayout> layouts = new ArrayList<>();
    private final Map<Integer, RenderEffect> blurEffectCache = new HashMap<>();
    private long blurRetryAfterMs;
    private final Scroller scroller;
    private final GestureDetector gestureDetector;

    private int currentIndex = -1;
    private float fontSizeSp = 24f;
    private int blurLevel = 2;
    private int textAlign;
    private boolean showSub = true;
    private String emptyText = "";
    private int fadeColor = 0;
    private OnLineClickListener lineClickListener;

    private float scrollOffset;
    private float contentHeight;
    private int layoutWidth = -1;
    private boolean browsing;
    private long lastBrowseMs;

    private boolean playing;
    private long anchorPosMs;
    private long anchorElapsedMs;
    private boolean framePosted;
    /** 逐行模糊趋近（GZ lyricRowBlur 规格，见 drawIdleRow）。 */
    private final LyricRowApproach blurApproach = new LyricRowApproach();
    private final Choreographer choreographer = Choreographer.getInstance();
    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            framePosted = false;
            if (lines.isEmpty()) return;
            stepFrame();
            invalidate();
            if (playing || scroller.computeScrollOffset() || browsing || scrollAnimating()
                    || blurApproach.hasRunning(SystemClock.elapsedRealtime())) postFrame();
        }
    };

    private static class LineLayout {
        float top;
        float height;
        /** 主行视觉行：有词行＝词子集列表；无词行＝文本片段列表（wordRows 与 textRows 二选一）。 */
        List<List<LyricsWord>> wordRows;
        List<String> textRows;
        LyricsWord lastWord;
    }

    public FullLyricView(Context context) {
        super(context);
        scroller = new Scroller(context);
        gestureDetector = new GestureDetector(context, new GestureListener());
        init();
    }

    public FullLyricView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        scroller = new Scroller(context);
        gestureDetector = new GestureDetector(context, new GestureListener());
        init();
    }

    private void init() {
        setLayerType(Build.VERSION.SDK_INT >= 31 ? LAYER_TYPE_HARDWARE : LAYER_TYPE_SOFTWARE, null);
        // 沉浸页路径（LiveWordLiftLine）：长音缩放／扇形／字母浮走逐字错峰口径，与内嵌 WordByWordText 路径分工。
        drawer.setImmersive(true);
        applyFontSize();
    }

    private void applyFontSize() {
        float scaled = getResources().getDisplayMetrics().scaledDensity;
        // 全屏字距 -0.2sp（§6.3）：Paint 字距以 em 计，除以字号 sp 换算（旧引擎同式）。
        drawer.configure(fontSizeSp * scaled, -0.2f / fontSizeSp, getResources().getDisplayMetrics().density);
        layoutWidth = -1;
    }

    public void setFontSizeSp(float sp) {
        fontSizeSp = Math.max(16f, Math.min(40f, sp));
        applyFontSize();
        invalidate();
    }

    public void setBlurLevel(int level) {
        blurLevel = Math.max(0, Math.min(2, level));
        invalidate();
    }

    /** 文本对齐（批 7 补线：键 music_lyric_align 0 左／1 中／2 右，此前只进了工具面板预览）。
     * 对齐改变折行后视觉行宽即变，须重算布局（字重经 setLyricWeight 同样影响度量，一并走 layoutWidth 重置）。 */
    public void setTextAlign(int align) {
        textAlign = (align == 1 || align == 2) ? align : 0;
        layoutWidth = -1;
        invalidate();
    }

    /** 主歌词字重（批 7 补线：键 music_lyric_weight 0 标准／1 加粗／2 特粗）。 */
    public void setLyricWeight(int weight) {
        drawer.setLyricWeight(weight);
        layoutWidth = -1;
        invalidate();
    }

    /** 视觉行起点 X：按对齐与实测行宽算；超宽（单行宽≥可用宽）钳到左起点不溢出。 */
    private float rowStartX(float x, float width, float textW) {
        if (textAlign == 1) return x + Math.max(0f, (width - Math.min(width, textW)) / 2f);
        if (textAlign == 2) return x + Math.max(0f, width - Math.min(width, textW));
        return x;
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
        layoutWidth = -1;
        invalidate();
    }

    public void setEmptyText(String text) {
        emptyText = text == null ? "" : text;
        invalidate();
    }

    /** 顶渐隐颜色（页面取色底；0＝不画渐隐罩）。 */
    public void setFadeColor(int color) {
        fadeColor = color;
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
        blurApproach.clear();
        currentIndex = -1;
        scrollOffset = 0f;
        browsing = false;
        layoutWidth = -1;
        anchorPosMs = 0;
        anchorElapsedMs = SystemClock.elapsedRealtime();
        invalidate();
        postFrame();
    }

    public void clearLines() {
        lines.clear();
        subs.clear();
        layouts.clear();
        blurApproach.clear();
        contentHeight = 0f;
        currentIndex = -1;
        invalidate();
    }

    public void setPlaying(boolean value) {
        if (playing == value) return;
        anchorPosMs = displayPositionMs();
        anchorElapsedMs = SystemClock.elapsedRealtime();
        playing = value;
        postFrame();
        invalidate();
    }

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

    private boolean scrollAnimating() {
        if (browsing || currentIndex < 0 || currentIndex >= layouts.size()) return false;
        float target = targetScroll();
        return Math.abs(target - scrollOffset) > 0.5f;
    }

    private void stepFrame() {
        updateCurrentIndex(displayPositionMs());
        if (scroller.computeScrollOffset()) {
            scrollOffset = scroller.getCurrY();
            return;
        }
        if (browsing) {
            if (SystemClock.elapsedRealtime() - lastBrowseMs > REANCHOR_MS) browsing = false;
            return;
        }
        if (currentIndex >= 0 && currentIndex < layouts.size()) {
            float target = targetScroll();
            float diff = target - scrollOffset;
            if (Math.abs(diff) > 0.5f) {
                scrollOffset += diff * 0.18f;
            } else {
                scrollOffset = target;
            }
        }
    }

    private float targetScroll() {
        if (currentIndex < 0 || currentIndex >= layouts.size()) return 0f;
        float fade = TOP_FADE_DP * getResources().getDisplayMetrics().density;
        return clampScroll(layouts.get(currentIndex).top - fade);
    }

    private float clampScroll(float value) {
        float max = Math.max(0f, contentHeight - getHeight());
        return Math.max(0f, Math.min(max, value));
    }

    private void postFrame() {
        if (framePosted || lines.isEmpty()) return;
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

    // ===== 布局（按词／字宽折行，宽度变或数据变重算） =====

    private float mainRowHeightPx() {
        return drawer.mainTextSizePx() * LINE_HEIGHT_RATIO_FULL;
    }

    private float subRowHeightPx() {
        return drawer.subTextSizePx() * LyricWordEngine.LINE_HEIGHT_RATIO;
    }

    private float gapPx() {
        return LINE_GAP_DP * getResources().getDisplayMetrics().density;
    }

    private void ensureLayout() {
        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        if (width <= 0 || width == layoutWidth) return;
        layoutWidth = width;
        layouts.clear();
        float y = 0f;
        for (int i = 0; i < lines.size(); i++) {
            LyricsLine line = lines.get(i);
            LineLayout layout = new LineLayout();
            layout.top = y;
            layout.lastWord = LyricLineDrawer.lastNonEmptyWord(line.getWords());
            if (line.hasWords()) {
                layout.wordRows = wrapWords(line.getWords(), width);
                layout.height = layout.wordRows.size() * mainRowHeightPx();
            } else {
                layout.textRows = wrapText(line.getText(), width);
                layout.height = layout.textRows.size() * mainRowHeightPx();
            }
            if (showSub) {
                String sub = i < subs.size() ? subs.get(i) : null;
                if (sub != null && !sub.isEmpty()) layout.height += subRowHeightPx();
            }
            layouts.add(layout);
            y += layout.height + gapPx();
        }
        contentHeight = layouts.isEmpty() ? 0f : y - gapPx();
        scrollOffset = clampScroll(scrollOffset);
    }

    private List<List<LyricsWord>> wrapWords(List<LyricsWord> words, float width) {
        List<List<LyricsWord>> rows = new ArrayList<>();
        List<LyricsWord> row = new ArrayList<>();
        float rowWidth = 0f;
        for (LyricsWord word : words) {
            if (word.getText().isEmpty()) continue;
            float w = drawer.measureMain(word.getText());
            if (!row.isEmpty() && rowWidth + w > width) {
                rows.add(row);
                row = new ArrayList<>();
                rowWidth = 0f;
            }
            row.add(word);
            rowWidth += w;
        }
        if (!row.isEmpty() || rows.isEmpty()) rows.add(row);
        return rows;
    }

    private List<String> wrapText(String text, float width) {
        List<String> rows = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            rows.add("");
            return rows;
        }
        int start = 0;
        while (start < text.length()) {
            int n = drawer.breakMain(text.substring(start), width);
            if (n <= 0) n = 1;
            if (start + n >= text.length()) {
                rows.add(text.substring(start));
                break;
            }
            // 折行点尽量落在空格处（英文词不腰斩；找不到空格才硬折，照实帧当前行折行形态）。
            int chunkEnd = start + n;
            int space = text.lastIndexOf(' ', chunkEnd - 1);
            if (space > start) chunkEnd = space + 1;
            rows.add(text.substring(start, chunkEnd));
            start = chunkEnd;
            while (start < text.length() && text.charAt(start) == ' ') start++;
        }
        if (rows.isEmpty()) rows.add(text);
        return rows;
    }

    private long lineDurationMs(int index) {
        LyricsLine line = lines.get(index);
        if (index + 1 < lines.size()) return lines.get(index + 1).getTimeMs() - line.getTimeMs();
        if (line.hasWords()) {
            long end = 0;
            for (LyricsWord w : line.getWords()) end = Math.max(end, w.getEndOffsetMs());
            if (end > 0) return end;
        }
        return 4000L;
    }

    // ===== 绘制 =====

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        if (width <= 0) return;
        float x = getPaddingLeft();
        if (lines.isEmpty()) {
            if (!emptyText.isEmpty()) {
                drawer.drawSub(canvas, emptyText, x, getPaddingTop() + subRowHeightPx(), width, false);
            }
            return;
        }
        ensureLayout();
        long pos = displayPositionMs();
        float top0 = getPaddingTop();
        for (int i = 0; i < layouts.size(); i++) {
            LineLayout layout = layouts.get(i);
            float lineTop = top0 + layout.top - scrollOffset;
            if (lineTop + layout.height < top0 || lineTop > getHeight()) continue;
            LyricsLine line = lines.get(i);
            boolean current = i == currentIndex;
            int distance = currentIndex < 0 ? i + 1 : i - currentIndex;
            float baseline = lineTop + drawer.mainTextSizePx();
            if (layout.wordRows != null) {
                for (List<LyricsWord> row : layout.wordRows) {
                    float rowTextW = 0f;
                    for (LyricsWord w : row) rowTextW += drawer.measureMain(w.getText());
                    float rowX = rowStartX(x, width, rowTextW);
                    if (current) {
                        drawer.drawCurrentWordRow(canvas, row, layout.lastWord, pos - line.getTimeMs(),
                                rowX, baseline, width, line.isBG());
                    } else {
                        drawIdleRow(canvas, i, joinWords(row), rowX, baseline, lineTop, width, distance);
                    }
                    baseline += mainRowHeightPx();
                }
            } else {
                for (String rowText : layout.textRows) {
                    float rowX = rowStartX(x, width, drawer.measureMain(rowText));
                    if (current) {
                        drawer.drawCurrentPlain(canvas, rowText, pos - line.getTimeMs(), lineDurationMs(i),
                                rowX, baseline, width);
                    } else {
                        drawIdleRow(canvas, i, rowText, rowX, baseline, lineTop, width, distance);
                    }
                    baseline += mainRowHeightPx();
                }
            }
            if (showSub) {
                String sub = i < subs.size() ? subs.get(i) : null;
                if (sub != null && !sub.isEmpty()) {
                    // 副行紧跟主行块下方：副行基线＝行块底 - 副行高 + 副行高×0.92（与内嵌同式）
                    float subBaseline = lineTop + layout.height - subRowHeightPx() + subRowHeightPx() * 0.92f;
                    drawer.drawSub(canvas, sub, rowStartX(x, width, drawer.measureSub(sub)), subBaseline, width, current);
                }
            }
        }
        drawTopFade(canvas);
    }

    private String joinWords(List<LyricsWord> row) {
        StringBuilder sb = new StringBuilder();
        for (LyricsWord w : row) sb.append(w.getText());
        return sb.toString();
    }

    /** 顶渐隐 40dp（§6.3）：取色底自上而下由实到透，压在歌词区顶部（背景为取色底时与内容 alpha 渐隐等价呈现）。 */
    private void drawTopFade(Canvas canvas) {
        if (fadeColor == 0) return;
        float fadePx = TOP_FADE_DP * getResources().getDisplayMetrics().density;
        Paint paint = new Paint();
        int opaque = fadeColor | 0xFF000000;
        int transparent = fadeColor & 0x00FFFFFF;
        paint.setShader(new LinearGradient(0, 0, 0, fadePx,
                new int[]{opaque, transparent}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, getWidth(), fadePx, paint);
        paint.setShader(null);
    }

    /**
     * 非当前行：压暗＋模糊。压暗经批 2 补正改向录屏口径（审计 §4）：原 max(0.35, 0.85-距离×0.16)
     * 在 +3 行合成 ≈0.11、比录屏同距离（≈0.27）暗一倍多，作废。新式：以 palette 非当前 0.30 为基
     * （录屏相对不透明度平台 0.2–0.3），行 alpha 不再随距离线性压暗、只走顶／底渐隐趋近——
     * 顶渐隐 smoothstep (3-2t)t²（审计 §4 源码注记 :457–477）、底渐隐 bottomFadeKeep 分段曲线
     * （源码实读转写，见下）。注：行 alpha 的源头状态函数未抠到（在未反编译的引擎本体内），
     * 本式按录屏不透明度比对齐、不冒充源码公式；模糊强度↔距离公式已 smali 深挖抠实（D1），走源头式。
     * palette 非当前色（白 0.30）自带 alpha、与画笔 alpha 相乘落地，故行 alpha 只给渐隐系数，
     * 不再乘 0.30——原式 255×0.30×渐隐再乘色内 0.30，合成约 0.09，闲行近乎不可见（全屏页「像没做」断点，
     * 1.4.27 查修批纠正）；纠正后合成＝0.30×顶渐隐×底渐隐，与批 2 补正的录屏平台值一致。
     * 2026-10-25 深挖补记：源头状态函数已抠实——本页（Apple 列表面）跑模式 A，闲行行 alpha＝0.72 平台
     * （不透明 ColorInactive），但 GZ 另有整页 lyricDimAlpha 因子（主题宿主注入、数值未抠到）与之相乘，
     * 乘积与本页现行 0.30 录屏平台相合；只知其一不知其二，不动水平值（见深挖报告第 5 项）。
     */
    /** 与当前行同时间组（同 timeMs，如合唱/重叠行）——GZ 趋近时长门「当前组高亮」的星辰映射。 */
    private boolean sameTimeGroup(int row) {
        if (row < 0 || currentIndex < 0 || row >= lines.size() || currentIndex >= lines.size()) return false;
        return lines.get(row).getTimeMs() == lines.get(currentIndex).getTimeMs();
    }

    private void drawIdleRow(Canvas canvas, int row, String text, float x, float baseline, float blockTop,
                             int width, int distance) {
        float fadePx = TOP_FADE_DP * getResources().getDisplayMetrics().density;
        float relTop = blockTop - getPaddingTop();
        float topT = LyricWordEngine.clampF(fadePx > 0f ? relTop / fadePx : 1f, 0f, 1f);
        float topKeep = (3f - 2f * topT) * topT * topT;
        float relY = LyricWordEngine.clampF(relTop / Math.max(1f, (float) getHeight()), 0f, 1f);
        float bottomKeep = bottomFadeKeep(relY, 0.45f);
        int alpha = Math.round(255f * topKeep * bottomKeep);
        // 模糊强度走源头式（smali 深挖 D1，distance 为带符号距离：本行－当前行）；旧等价式作废。
        // 趋近动画 2026-10-25 深挖落码（GZ lyricRowBlur）：逐行 animateFloatAsState——目标变化时
        // 以当前显示值起整段重跑 tween（活动门任一开 200ms、否则 400ms，CubicBezier(0.2,0,0,1)）。
        // 门的星辰映射：browsing（本视图浏览态）或与当前行同时间组；GZ 另两门（S1 状态、snap 旗）
        // 源名未恢复、未映射，见深挖报告。趋近作用在量化前的强度值上，半径仍走 §6.1 量化。
        float targetStrength = LyricWordEngine.blurStrength(distance) * LyricWordEngine.blurCoefficient(blurLevel);
        boolean gate = browsing || sameTimeGroup(row);
        float strength = blurApproach.approach(row, targetStrength, gate ? 200 : 400,
                SystemClock.elapsedRealtime());
        float radius = LyricWordEngine.quantizeBlurRadius(strength);
        boolean blurWanted = radius > 0f && distance != 0;
        if (blurWanted && Build.VERSION.SDK_INT >= 31) {
            if (SystemClock.elapsedRealtime() >= blurRetryAfterMs
                    && drawIdleWithEffect(canvas, text, x, baseline, blockTop, width, radius, alpha)) {
                return;
            }
            drawer.drawIdleMain(canvas, text, x, baseline, width, alpha);
            return;
        }
        if (blurWanted) {
            android.text.TextPaint paint = drawer.mainPaint();
            paint.setColor(drawer.palette().inactive);
            paint.setAlpha(alpha);
            paint.setMaskFilter(new android.graphics.BlurMaskFilter(radius, android.graphics.BlurMaskFilter.Blur.NORMAL));
            paint.setShader(null);
            CharSequence draw = android.text.TextUtils.ellipsize(text, paint, width, android.text.TextUtils.TruncateAt.END);
            canvas.drawText(draw, 0, draw.length(), x, baseline, paint);
            paint.setMaskFilter(null);
            paint.setAlpha(255);
            return;
        }
        drawer.drawIdleMain(canvas, text, x, baseline, width, alpha);
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

    /**
     * 底渐隐保留曲线（源码 bottomFadeKeep 实读转写）：停点 {0, 0.3, 0.55, 0.78, 1} →
     * 保留 {1, 0.85, 0.62, 0.48, endKeep}，段内以 smoothstep (3-2t)t² 插值；endKeep 默认 0.45（源码 default）。
     */
    private static float bottomFadeKeep(float f, float endKeep) {
        float[] stops = {0f, 0.3f, 0.55f, 0.78f, 1f};
        float[] keeps = {1f, 0.85f, 0.62f, 0.48f, endKeep};
        float v = LyricWordEngine.clampF(f, 0f, 1f);
        for (int i = 0; i < 4; i++) {
            if (v <= stops[i + 1]) {
                float t = (v - stops[i]) / (stops[i + 1] - stops[i]);
                float smooth = (3f - 2f * t) * t * t;
                return keeps[i] + (keeps[i + 1] - keeps[i]) * smooth;
            }
        }
        return keeps[4];
    }

    /** API≥31 非当前行 RenderNode 录行（不许 discardDisplayList，旧引擎实证教训，同 CoverLyricView）。 */
    private boolean drawIdleWithEffect(Canvas canvas, String text, float x, float baseline, float blockTop,
                                       int width, float radius, int alpha) {
        RenderEffect effect = blurEffectFor(radius);
        if (effect == null) return false;
        int w = getWidth();
        if (w <= 0) return false;
        int h = Math.max(1, Math.round(mainRowHeightPx() + 4f * getResources().getDisplayMetrics().density));
        RenderNode node = new RenderNode("fullLyricIdleBlur");
        try {
            node.setPosition(0, 0, w, h);
            Canvas rec = node.beginRecording(w, h);
            android.text.TextPaint paint = new android.text.TextPaint(drawer.mainPaint());
            paint.setColor(drawer.palette().inactive);
            paint.setAlpha(alpha);
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

    // ===== 触摸：拖动浏览＋点行 seek =====

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) {
            scroller.forceFinished(true);
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            browsing = true;
            lastBrowseMs = SystemClock.elapsedRealtime();
            scrollOffset = clampScroll(scrollOffset + distanceY);
            invalidate();
            return true;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            browsing = true;
            lastBrowseMs = SystemClock.elapsedRealtime();
            float max = Math.max(0f, contentHeight - getHeight());
            scroller.fling(0, Math.round(scrollOffset), 0, Math.round(-velocityY),
                    0, 0, 0, Math.round(max));
            postFrame();
            return true;
        }

        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            if (lineClickListener == null || layouts.isEmpty()) return false;
            float contentY = e.getY() - getPaddingTop() + scrollOffset;
            for (int i = 0; i < layouts.size(); i++) {
                LineLayout layout = layouts.get(i);
                if (contentY >= layout.top && contentY < layout.top + layout.height) {
                    lineClickListener.onLineClick(i, lines.get(i).getTimeMs());
                    performClick();
                    return true;
                }
            }
            return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean handled = gestureDetector.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            lastBrowseMs = SystemClock.elapsedRealtime();
            postFrame();
        }
        return handled;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
