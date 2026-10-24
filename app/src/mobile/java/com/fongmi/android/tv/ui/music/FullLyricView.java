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
 * 点行＝seek（定案）；非当前行模糊＋压暗（档位系数与半径量化同内嵌，距离项沿旧引擎应用侧等价式，
 * 源头公式 §19 第 2 项未抠到）；字斜 SKEW=-0.12 定案（LyricLineDrawer 统一挂）。
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
    private final Choreographer choreographer = Choreographer.getInstance();
    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            framePosted = false;
            if (lines.isEmpty()) return;
            stepFrame();
            invalidate();
            if (playing || scroller.computeScrollOffset() || browsing || scrollAnimating()) postFrame();
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
            int distance = currentIndex < 0 ? i : Math.abs(i - currentIndex);
            float baseline = lineTop + drawer.mainTextSizePx();
            if (layout.wordRows != null) {
                for (List<LyricsWord> row : layout.wordRows) {
                    if (current) {
                        drawer.drawCurrentWordRow(canvas, row, layout.lastWord, pos - line.getTimeMs(),
                                x, baseline, width);
                    } else {
                        drawIdleRow(canvas, joinWords(row), x, baseline, lineTop, width, distance);
                    }
                    baseline += mainRowHeightPx();
                }
            } else {
                for (String rowText : layout.textRows) {
                    if (current) {
                        drawer.drawCurrentPlain(canvas, rowText, pos - line.getTimeMs(), lineDurationMs(i),
                                x, baseline, width);
                    } else {
                        drawIdleRow(canvas, rowText, x, baseline, lineTop, width, distance);
                    }
                    baseline += mainRowHeightPx();
                }
            }
            if (showSub) {
                String sub = i < subs.size() ? subs.get(i) : null;
                if (sub != null && !sub.isEmpty()) {
                    // 副行紧跟主行块下方：副行基线＝行块底 - 副行高 + 副行高×0.92（与内嵌同式）
                    float subBaseline = lineTop + layout.height - subRowHeightPx() + subRowHeightPx() * 0.92f;
                    drawer.drawSub(canvas, sub, x, subBaseline, width, current);
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

    /** 非当前行：压暗（距离衰减沿旧引擎应用侧等价式 max(0.35, 0.85-距离×0.16)，源头公式未抠到）＋模糊。 */
    private void drawIdleRow(Canvas canvas, String text, float x, float baseline, float blockTop,
                             int width, int distance) {
        int alpha = Math.round(255f * Math.max(0.35f, 0.85f - distance * 0.16f));
        float strength = Math.min(2f + distance * 2.2f, 12f) * LyricWordEngine.blurCoefficient(blurLevel);
        float radius = LyricWordEngine.quantizeBlurRadius(strength);
        boolean blurWanted = radius > 0f && distance > 0;
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
