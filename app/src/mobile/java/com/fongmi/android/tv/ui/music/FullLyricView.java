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
 * 全屏歌词页的歌词列表（2026-10-25 从零重写，旧实现整删）。
 *
 * 形态与数值只认瓜子本体两类依据：瓜子 2.1.8 反编译抠值（施工图 §6.1/§6.3 与深挖记录）
 * 与他的瓜子真机录屏、截图实帧。本视图口径：
 * - 左对齐沉浸滚动，横向垫 28dp 由父页给；字号默认 24sp（设置可调 16–40）、字重 700、
 *   行高＝字号×1.3、字距 −0.2sp、行距（块距）6dp——均为 LyricsViewKt 常量区实读值。
 * - 锚位＝顶部：正唱行顶对齐歌词区顶部（瓜子歌词锚定设置默认 "top"；实帧里正唱行恒为
 *   歌名行下方第一行、已唱行不出现在锚上方，与此合）。已唱行滑出区顶后由逐行顶渐隐吞掉。
 * - 副行（翻译/音译）只挂正唱行（实帧：非当前行无副行）；副行字号＝主行×0.62、字重 500、
 *   行高＝副行字号×4/3。正唱行因此比闲行高出一个副行位，布局随正唱行换位重排，
 *   位移交给跟随滚动吸收；正唱行副行高走 anchorReveal 展开（250ms、CubicBezier(0.2,0,0,1)，
 *   2026-10-25 smali 深挖 §15.1 第 1 项；GZ 本体 target 为行高＋0.6×样本高，星辰映射为副行高展开）。
 * - 非当前行压暗＋随距离模糊。压暗＝0.30 平台（深色面 palette 非当前档）×顶渐隐
 *   smoothstep（40dp 渐隐高）×底渐隐 bottomFadeKeep（LyricsViewKt:10618 逐字转写、
 *   endKeep 默认 0.45）；模糊强度＝LyricWordEngine.blurStrength（smali 深挖源头式）
 *   ×档位系数（off 0／light 0.45／medium 1.0），半径按 round(强度×2)/2 量化，
 *   逐行强度走 LyricRowApproach 趋近（瓜子 lyricRowBlur 规格：门开 200ms／关 400ms、
 *   CubicBezier(0.2,0,0,1)）。
 * - 正唱行逐字跟唱（逐字上浮＋长音光晕）走共用 LyricLineDrawer／LyricWordEngine
 *   （与播放页内嵌歌词共用同一引擎，非本页专属代码）；字斜由引擎统一挂 SKEW 定案值。
 * - 手指拖动浏览、甩动惯性、点行 seek（回调交父页）。浏览停手回锚延时 5000ms、跟随滚动
 *   走引擎弹簧（settle ζ1.16/k237.25、seek ζ0.833/k100、|偏移|＞视高×1.5 snap），
 *   均 2026-10-25 smali 深挖 §15.1 第 1/6 项抠实（旧 τ90ms 阻尼趋近作废）。
 */
public class FullLyricView extends View {

    public interface OnLineClickListener {
        void onLineClick(int index, long positionMs);
    }

    /** 行距（块与块之间）：APPLE_LYRIC_LINE_GAP＝6dp（LyricsViewKt:310）。 */
    private static final float LINE_GAP_DP = 6f;
    /** 顶渐隐高：APPLE_TOP_FADE_HEIGHT＝40dp（LyricsViewKt:311）。 */
    private static final float TOP_FADE_DP = 40f;
    /** 全屏行高比：字号×1.3（LyricsViewKt:1325 lyricTextStyle）。 */
    private static final float LINE_HEIGHT_RATIO = 1.3f;
    /** 非当前行压暗平台：深色面 palette 非当前档白 0.30（LyricsViewKt 深色回落值）。 */
    private static final float IDLE_ALPHA_BASE = 0.30f;
    /** 浏览停手后回锚延时：全屏/Apple 面 5000ms（瓜子字段 APPLE_BROWSE_REANCHOR_DELAY＝0x1388，
     * 2026-10-25 smali 深挖 §15.1 第 1 项证实；封面面为 3000ms、不属本页）。 */
    private static final long REANCHOR_MS = 5000L;
    private static final long BLUR_RETRY_COOLDOWN_MS = 30000L;
    /** anchorReveal 规格（smali 深挖 §15.1 第 1 项）：TweenSpec 250ms、CubicBezier(0.2,0,0,1)。 */
    private static final android.view.animation.PathInterpolator REVEAL_INTERP =
            new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final long REVEAL_MS = 250L;

    private final LyricLineDrawer drawer = new LyricLineDrawer();
    private final LyricRowApproach blurApproach = new LyricRowApproach();
    private final List<LyricsLine> lines = new ArrayList<>();
    private final List<String> subs = new ArrayList<>();
    private final List<Block> blocks = new ArrayList<>();
    private final Map<Integer, RenderEffect> blurEffects = new HashMap<>();
    private final Scroller scroller;
    private final GestureDetector gestures;
    private final Choreographer choreographer = Choreographer.getInstance();

    private float fontSizeSp = 24f;
    private int blurLevel = 2;
    private int textAlign;
    private boolean showSub = true;
    private String emptyText = "";
    private int fadeColor;
    private OnLineClickListener lineClickListener;

    private int currentIndex = -1;
    private float scrollOffset;
    private float contentHeight;
    private int layoutWidth = -1;
    /** 跟随滚动弹簧（瓜子引擎 SpringSpec 等价，见 LyricScrollSpring）：settle 档 ζ1.16/k237.25、
     * seek 档 ζ0.833/k100；浏览/甩动直写 scrollOffset 时同步 snap 对齐弹簧值。 */
    private final LyricScrollSpring followSpring = new LyricScrollSpring();
    private boolean seekSpringMode;
    /** anchorReveal：正唱行换位且带副行时，副行高按 250ms 贝塞尔展开（见 ensureLayout）。 */
    private int revealForIndex = -1;
    private long revealStartMs;

    private boolean playing;
    private long anchorPosMs;
    private long anchorElapsedMs;
    private boolean browsing;
    private long lastBrowseMs;
    private long blurRetryAfterMs;
    private boolean framePosted;
    private long lastFrameMs;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            framePosted = false;
            if (lines.isEmpty()) return;
            stepFrame();
            invalidate();
            if (playing || scroller.computeScrollOffset() || browsing || followAnimating()
                    || revealRunning()
                    || blurApproach.hasRunning(SystemClock.elapsedRealtime())) postFrame();
        }
    };

    /** 一行歌词的布局块：主行视觉行（词行／文本行二选一）＋可选副行（仅正唱行）。 */
    private static class Block {
        float top;
        float mainHeight;
        float subHeight;
        List<List<LyricsWord>> wordRows;
        List<String> textRows;
        LyricsWord lastWord;

        float height() {
            return mainHeight + subHeight;
        }
    }

    public FullLyricView(Context context) {
        super(context);
        scroller = new Scroller(context);
        gestures = new GestureDetector(context, new GestureListener());
        init();
    }

    public FullLyricView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        scroller = new Scroller(context);
        gestures = new GestureDetector(context, new GestureListener());
        init();
    }

    private void init() {
        setLayerType(Build.VERSION.SDK_INT >= 31 ? LAYER_TYPE_HARDWARE : LAYER_TYPE_SOFTWARE, null);
        drawer.setImmersive(true);
        applyFontSize();
    }

    private void applyFontSize() {
        float scaledDensity = getResources().getDisplayMetrics().scaledDensity;
        // 字距 −0.2sp（LyricsViewKt:1325）：Paint 字距以 em 计，按字号换算。
        drawer.configure(fontSizeSp * scaledDensity, -0.2f / fontSizeSp,
                getResources().getDisplayMetrics().density);
        layoutWidth = -1;
    }

    // ===== 外部喂入（父页契约） =====

    public void setFontSizeSp(float sp) {
        fontSizeSp = Math.max(16f, Math.min(40f, sp));
        applyFontSize();
        invalidate();
    }

    public void setBlurLevel(int level) {
        blurLevel = Math.max(0, Math.min(2, level));
        invalidate();
    }

    public void setTextAlign(int align) {
        textAlign = (align == 1 || align == 2) ? align : 0;
        layoutWidth = -1;
        invalidate();
    }

    public void setLyricWeight(int weight) {
        drawer.setLyricWeight(weight);
        layoutWidth = -1;
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
        layoutWidth = -1;
        invalidate();
    }

    public void setEmptyText(String text) {
        emptyText = text == null ? "" : text;
        invalidate();
    }

    /** 顶渐隐颜色（页面取色底；0＝不画区顶渐隐罩）。 */
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
        followSpring.snapTo(0f);
        seekSpringMode = false;
        revealForIndex = -1;
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
        blocks.clear();
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
        // 与当前显示位置差 >1.2s 视为 seek 跳变：本轮跟随滚动走 seek 弹簧档（引擎三路选档的星辰映射）。
        if (Math.abs(positionMs - displayPositionMs()) > 1200L) seekSpringMode = true;
        anchorPosMs = positionMs;
        anchorElapsedMs = SystemClock.elapsedRealtime();
        updateCurrentIndex(positionMs);
        invalidate();
        postFrame();
    }

    // ===== 播放位置与正唱行 =====

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
        if (idx != currentIndex) {
            currentIndex = idx;
            // 副行只挂正唱行：正唱行换位即块高变，布局随之重排（位移由跟随滚动吸收）。
            layoutWidth = -1;
            // anchorReveal 起点：新正唱行带副行时，副行高走 250ms 展开（GZ 本体 target＝
            // 行高＋0.6×样本高；星辰结构映射为本行副行高的展开系数，见 ensureLayout）。
            if (idx >= 0 && subOf(idx) != null) {
                revealForIndex = idx;
                revealStartMs = SystemClock.elapsedRealtime();
            } else {
                revealForIndex = -1;
            }
        }
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

    // ===== 布局 =====

    private float mainRowHeightPx() {
        return drawer.mainTextSizePx() * LINE_HEIGHT_RATIO;
    }

    private float subRowHeightPx() {
        return drawer.subTextSizePx() * LyricWordEngine.LINE_HEIGHT_RATIO;
    }

    private float gapPx() {
        return LINE_GAP_DP * getResources().getDisplayMetrics().density;
    }

    private String subOf(int index) {
        if (!showSub || index < 0 || index >= subs.size()) return null;
        String sub = subs.get(index);
        return sub == null || sub.isEmpty() ? null : sub;
    }

    private void ensureLayout() {
        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        if (width <= 0 || width == layoutWidth) return;
        layoutWidth = width;
        blocks.clear();
        float y = 0f;
        for (int i = 0; i < lines.size(); i++) {
            LyricsLine line = lines.get(i);
            Block block = new Block();
            block.top = y;
            block.lastWord = LyricLineDrawer.lastNonEmptyWord(line.getWords());
            if (line.hasWords()) {
                block.wordRows = wrapWords(line.getWords(), width);
                block.mainHeight = block.wordRows.size() * mainRowHeightPx();
            } else {
                block.textRows = wrapText(line.getText(), width);
                block.mainHeight = block.textRows.size() * mainRowHeightPx();
            }
            // 副行只占正唱行的块高（实帧口径），闲行不预留副行位。
            // anchorReveal：正唱行副行高按 250ms 贝塞尔从 0 展开到全高（展开期间 stepFrame 逐帧重排）。
            if (i == currentIndex && subOf(i) != null) {
                float factor = 1f;
                if (i == revealForIndex) {
                    long elapsed = SystemClock.elapsedRealtime() - revealStartMs;
                    factor = elapsed >= REVEAL_MS ? 1f
                            : REVEAL_INTERP.getInterpolation(elapsed / (float) REVEAL_MS);
                }
                block.subHeight = subRowHeightPx() * factor;
            }
            blocks.add(block);
            y += block.height() + gapPx();
        }
        contentHeight = blocks.isEmpty() ? 0f : y - gapPx();
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
            // 折点尽量落在空格处（英文词不腰斩，照实帧折行形态），无空格才硬折。
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

    // ===== 滚动：顶部锚位＋跟随＋浏览 =====

    private float targetScroll() {
        if (currentIndex < 0 || currentIndex >= blocks.size()) return 0f;
        return clampScroll(blocks.get(currentIndex).top);
    }

    private float clampScroll(float value) {
        float max = Math.max(0f, contentHeight - getHeight());
        return Math.max(0f, Math.min(max, value));
    }

    private boolean followAnimating() {
        if (browsing || currentIndex < 0 || currentIndex >= blocks.size()) return false;
        return followSpring.active(targetScroll());
    }

    private boolean revealRunning() {
        return revealForIndex >= 0
                && SystemClock.elapsedRealtime() - revealStartMs < REVEAL_MS;
    }

    private void stepFrame() {
        long now = SystemClock.elapsedRealtime();
        float dtMs = lastFrameMs == 0 ? 16f : Math.min(50f, now - lastFrameMs);
        lastFrameMs = now;
        updateCurrentIndex(displayPositionMs());
        if (revealRunning()) layoutWidth = -1;
        ensureLayout();
        if (scroller.computeScrollOffset()) {
            scrollOffset = scroller.getCurrY();
            followSpring.snapTo(scrollOffset);
            return;
        }
        if (browsing) {
            followSpring.snapTo(scrollOffset);
            if (now - lastBrowseMs > REANCHOR_MS) {
                browsing = false;
                // 回锚 snap 判据（引擎原式）：手动偏移｜值−目标｜＞视高×1.5 直接落位、否则弹簧回锚。
                if (currentIndex >= 0 && currentIndex < blocks.size()
                        && Math.abs(scrollOffset - targetScroll()) > getHeight() * 1.5f) {
                    scrollOffset = targetScroll();
                    followSpring.snapTo(scrollOffset);
                }
            }
            return;
        }
        if (currentIndex >= 0 && currentIndex < blocks.size()) {
            float target = targetScroll();
            if (getHeight() > 0 && Math.abs(target - followSpring.value) > getHeight() * 1.5f) {
                followSpring.snapTo(target);
                seekSpringMode = false;
            } else {
                // 选档（引擎 posSpringSpec）：seek 跳变→seek 档（ζ0.833/k100）；
                // 常规跟随/回锚→settle 档（ζ1.16、k＝amllPosYComposeStiffness(450)＝237.25）。
                float stiffness = seekSpringMode ? 100f : LyricWordEngine.amllPosYComposeStiffness(450L);
                float damping = seekSpringMode ? 0.833f : 1.16f;
                if (!followSpring.step(dtMs / 1000f, target, stiffness, damping)) seekSpringMode = false;
            }
            scrollOffset = followSpring.value;
        }
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
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            float blockTop = top0 + block.top - scrollOffset;
            if (blockTop + block.height() < top0 || blockTop > getHeight()) continue;
            LyricsLine line = lines.get(i);
            boolean current = i == currentIndex;
            int distance = currentIndex < 0 ? i + 1 : i - currentIndex;
            float baseline = blockTop + drawer.mainTextSizePx();
            if (block.wordRows != null) {
                for (List<LyricsWord> row : block.wordRows) {
                    float rowX = rowStartX(x, width, rowWidth(row));
                    if (current) {
                        drawer.drawCurrentWordRow(canvas, row, block.lastWord, pos - line.getTimeMs(),
                                rowX, baseline, width, line.isBG());
                    } else {
                        drawIdleRow(canvas, i, joinWords(row), rowX, baseline, blockTop, width, distance);
                    }
                    baseline += mainRowHeightPx();
                }
            } else {
                for (String rowText : block.textRows) {
                    float rowX = rowStartX(x, width, drawer.measureMain(rowText));
                    if (current) {
                        drawer.drawCurrentPlain(canvas, rowText, pos - line.getTimeMs(), lineDurationMs(i),
                                rowX, baseline, width);
                    } else {
                        drawIdleRow(canvas, i, rowText, rowX, baseline, blockTop, width, distance);
                    }
                    baseline += mainRowHeightPx();
                }
            }
            if (current && block.subHeight > 0f) {
                String sub = subOf(i);
                if (sub != null) {
                    float subBaseline = blockTop + block.height() - block.subHeight
                            + block.subHeight * 0.92f;
                    drawer.drawSub(canvas, sub, rowStartX(x, width, drawer.measureSub(sub)),
                            subBaseline, width, true);
                }
            }
        }
        drawTopFade(canvas);
    }

    private float rowWidth(List<LyricsWord> row) {
        float w = 0f;
        for (LyricsWord word : row) w += drawer.measureMain(word.getText());
        return w;
    }

    private float rowStartX(float x, float width, float textW) {
        if (textAlign == 1) return x + Math.max(0f, (width - Math.min(width, textW)) / 2f);
        if (textAlign == 2) return x + Math.max(0f, width - Math.min(width, textW));
        return x;
    }

    private String joinWords(List<LyricsWord> row) {
        StringBuilder sb = new StringBuilder();
        for (LyricsWord w : row) sb.append(w.getText());
        return sb.toString();
    }

    /** 区顶渐隐罩 40dp（瓜子常量）：取色底由实到透，压在歌词区顶部。 */
    private void drawTopFade(Canvas canvas) {
        if (fadeColor == 0) return;
        float fadePx = TOP_FADE_DP * getResources().getDisplayMetrics().density;
        Paint paint = new Paint();
        int opaque = fadeColor | 0xFF000000;
        int transparent = fadeColor & 0x00FFFFFF;
        paint.setShader(new android.graphics.LinearGradient(0, 0, 0, fadePx,
                new int[]{opaque, transparent}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, getWidth(), fadePx, paint);
        paint.setShader(null);
    }

    /**
     * 非当前行：压暗＋模糊。
     * 压暗＝0.30 平台×顶渐隐×底渐隐：顶渐隐按行在本区内的相对位置走 smoothstep (3-2t)t²
     * （40dp 渐隐高内由 0 到 1）；底渐隐走 bottomFadeKeep（源头曲线逐字转写）。
     * 模糊：强度＝blurStrength(带符号距离)×档位系数，LyricRowApproach 按瓜子门控时长趋近
     * （浏览中或与正唱行同时间组 200ms、其余 400ms），半径量化后 API≥31 走 RenderNode
     * 挂 RenderEffect、低版本走 BlurMaskFilter 兜底。
     */
    private void drawIdleRow(Canvas canvas, int row, String text, float x, float baseline,
                             float blockTop, int width, int distance) {
        float fadePx = TOP_FADE_DP * getResources().getDisplayMetrics().density;
        float relTop = blockTop - getPaddingTop();
        float topT = LyricWordEngine.clampF(fadePx > 0f ? relTop / fadePx : 1f, 0f, 1f);
        float topKeep = (3f - 2f * topT) * topT * topT;
        float relY = LyricWordEngine.clampF(relTop / Math.max(1f, (float) getHeight()), 0f, 1f);
        float bottomKeep = bottomFadeKeep(relY, 0.45f);
        int alpha = Math.round(255f * IDLE_ALPHA_BASE * topKeep * bottomKeep);
        float targetStrength = LyricWordEngine.blurStrength(distance)
                * LyricWordEngine.blurCoefficient(blurLevel);
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
            paint.setMaskFilter(new android.graphics.BlurMaskFilter(radius,
                    android.graphics.BlurMaskFilter.Blur.NORMAL));
            paint.setShader(null);
            CharSequence draw = android.text.TextUtils.ellipsize(text, paint, width,
                    android.text.TextUtils.TruncateAt.END);
            canvas.drawText(draw, 0, draw.length(), x, baseline, paint);
            paint.setMaskFilter(null);
            paint.setAlpha(255);
            return;
        }
        drawer.drawIdleMain(canvas, text, x, baseline, width, alpha);
    }

    private boolean sameTimeGroup(int row) {
        if (row < 0 || currentIndex < 0 || row >= lines.size() || currentIndex >= lines.size()) {
            return false;
        }
        return lines.get(row).getTimeMs() == lines.get(currentIndex).getTimeMs();
    }

    /**
     * 底渐隐保留曲线（LyricsViewKt:10618 逐字转写）：停点 {0, 0.3, 0.55, 0.78, 1} →
     * 保留 {1, 0.85, 0.62, 0.48, endKeep}，段内 smoothstep 插值；endKeep 默认 0.45。
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

    /** API≥31 非当前行模糊：RenderNode 录行后挂 RenderEffect（不 discardDisplayList）。 */
    private boolean drawIdleWithEffect(Canvas canvas, String text, float x, float baseline,
                                       float blockTop, int width, float radius, int alpha) {
        RenderEffect effect = blurEffectFor(radius);
        if (effect == null) return false;
        int w = getWidth();
        if (w <= 0) return false;
        int h = Math.max(1, Math.round(mainRowHeightPx()
                + 4f * getResources().getDisplayMetrics().density));
        RenderNode node = new RenderNode("lyricPageIdleBlur");
        try {
            node.setPosition(0, 0, w, h);
            Canvas rec = node.beginRecording(w, h);
            android.text.TextPaint paint = new android.text.TextPaint(drawer.mainPaint());
            paint.setColor(drawer.palette().inactive);
            paint.setAlpha(alpha);
            paint.setMaskFilter(null);
            CharSequence draw = android.text.TextUtils.ellipsize(text, paint, width,
                    android.text.TextUtils.TruncateAt.END);
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

    private RenderEffect blurEffectFor(float radius) {
        int e = Math.round(radius * 2f);
        RenderEffect effect = blurEffects.get(e);
        if (effect != null) return effect;
        float r = e / 2f;
        try {
            effect = RenderEffect.createBlurEffect(r, r, Shader.TileMode.DECAL);
            blurEffects.put(e, effect);
            return effect;
        } catch (Exception ex) {
            blurRetryAfterMs = SystemClock.elapsedRealtime() + BLUR_RETRY_COOLDOWN_MS;
            return null;
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
            if (lineClickListener == null || blocks.isEmpty()) return false;
            float contentY = e.getY() - getPaddingTop() + scrollOffset;
            for (int i = 0; i < blocks.size(); i++) {
                Block block = blocks.get(i);
                if (contentY >= block.top && contentY < block.top + block.height()) {
                    browsing = false;
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
        boolean handled = gestures.onTouchEvent(event);
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
