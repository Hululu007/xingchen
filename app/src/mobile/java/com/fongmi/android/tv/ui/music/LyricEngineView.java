package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.music.RichLyrics;
import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.player.lyrics.LyricsWord;

import java.util.ArrayList;
import java.util.List;

/**
 * 锚点歌词引擎（照瓜子 AnchoredLyricEngine 复刻）：当前行锚在视口 0.45 处，
 * 弹簧滚动（seek .833/100、settle 1.16/≈237.3），手滑后 5s 回锚、点行立即 seek＋回锚，
 * 逐字推进（上浮 −0.09em、长音 ≥1000ms 光晕＋放大 1.2、行末 +400ms 拖尾）。
 * 色板两套：沉浸（全屏歌词页）与经典页内，由宿主经 Palette 注入。
 * 模糊（2026-10-25 深夜 smali 定谳）：半径＝档位系数 × min(|距离|×2.5dp, 16dp)，
 * 瓜子组 B 主曲线；组 A（上限 5dp）与两覆写旗的选择子未定死，见 gz-smali-missing-dig.md。
 * 底部淡出（同晚定谳）：锚下跨度 80% 起、走 bottomFadeKeep 分段表、地板 0.45。
 */
public class LyricEngineView extends View {

    public static class Palette {
        public int sung;
        public int activeUnsung;
        public int inactive;
        public int translation;

        public static Palette fullscreen() {
            Palette p = new Palette();
            p.sung = 0xFFEAEAEA;
            p.activeUnsung = Color.argb(Math.round(255 * 0.42f), 255, 255, 255);
            p.inactive = 0xFF9A9AA0;
            p.translation = Color.argb(Math.round(255 * 0.55f), 255, 255, 255);
            return p;
        }

        public static Palette classic() {
            return fullscreen();
        }
    }

    public interface Listener {
        void onLineTap(int index, long timeMs);
    }

    private static final float ANCHOR = 0.45f;
    private static final long MANUAL_RETURN_MS = 5000;
    private static final float SKEW = -0.12f;

    private final Paint mainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    private List<RichLyrics.Line> lines = new ArrayList<>();
    private Palette palette = Palette.fullscreen();
    private float fontSizeSp = 24f;
    private float lineSpacingDp = 6f;
    private float padHDp = 28f;
    private boolean wordByWord = true;
    private boolean advancedFx = true;
    private boolean showTranslation = true;
    private boolean showRomanization = false;
    private int primary = 0;
    private int align = 0;
    private float blurLevel = 1.0f;
    private Listener listener;

    private long positionMs;
    private int currentIndex = -1;

    // 内容坐标：每行块顶 y（px）
    private float[] blockTops = new float[0];
    private float[] blockHeights = new float[0];
    private float contentHeight;

    // 滚动：offset 是内容上移量（px），目标由当前行锚定
    private float scrollOffset;
    private float springVelocity;
    private boolean springActive;
    private float springStiffness = 237.3f;
    private float springDamping = 1.16f;
    private long lastFrameTime;

    private boolean manualBrowsing;
    private long manualEndTime;
    private float touchStartY;
    private float touchStartOffset;
    private boolean touchMoved;
    private float touchDownX;
    private float touchDownY;

    public LyricEngineView(Context context) {
        this(context, null);
    }

    public LyricEngineView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        applyPaint();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setPalette(Palette palette) {
        this.palette = palette;
        invalidate();
    }

    public void configure(float fontSizeSp, float lineSpacingDp, float padHDp, boolean wordByWord,
                          boolean advancedFx, boolean showTranslation, boolean showRomanization,
                          int primary, int align, float blurLevel) {
        this.fontSizeSp = fontSizeSp;
        this.lineSpacingDp = lineSpacingDp;
        this.padHDp = padHDp;
        this.wordByWord = wordByWord;
        this.advancedFx = advancedFx;
        this.showTranslation = showTranslation;
        this.showRomanization = showRomanization;
        this.primary = primary;
        this.align = align;
        this.blurLevel = blurLevel;
        applyPaint();
        measureContent();
        invalidate();
    }

    private void applyPaint() {
        float mainPx = fontSizeSp * density * getResources().getConfiguration().fontScale;
        mainPaint.setTextSize(mainPx);
        mainPaint.setTextSkewX(SKEW);
        int weight = MusicPlayerSettings.getLyricWeight();
        Typeface base = Typeface.DEFAULT;
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            int w = weight == 2 ? 900 : weight == 1 ? 800 : 700;
            mainPaint.setTypeface(Typeface.create(base, w, false));
        } else {
            mainPaint.setTypeface(weight == 0 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT_BOLD);
        }
        subPaint.setTextSize(mainPx * 0.62f);
        subPaint.setTextSkewX(SKEW);
        subPaint.setTypeface(mainPaint.getTypeface());
    }

    public void setLines(List<RichLyrics.Line> newLines) {
        lines = newLines == null ? new ArrayList<>() : newLines;
        currentIndex = -1;
        scrollOffset = 0;
        springVelocity = 0;
        manualBrowsing = false;
        measureContent();
        invalidate();
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    /** 播放进度推进：算当前行，行变时起弹簧滚到锚点。 */
    public void setPosition(long positionMs) {
        this.positionMs = positionMs;
        int idx = indexAt(positionMs);
        if (idx != currentIndex) {
            currentIndex = idx;
            if (!manualBrowsing) startSpring(false);
        }
        invalidate();
    }

    /** 外部 seek（点行/进度条）后立即回锚，用 seek 弹簧。 */
    public void resyncToCurrent() {
        manualBrowsing = false;
        startSpring(true);
        invalidate();
    }

    private int indexAt(long pos) {
        int idx = -1;
        for (int i = 0; i < lines.size(); i++) {
            LyricsLine main = lines.get(i).main;
            if (main != null && main.getTimeMs() <= pos) idx = i;
            else break;
        }
        return idx;
    }

    private void measureContent() {
        int n = lines.size();
        blockTops = new float[n];
        blockHeights = new float[n];
        float lineHeight = fontSizeSp * density * (4f / 3f);
        float subHeight = lineHeight * 0.62f + dp(4);
        float y = 0;
        for (int i = 0; i < n; i++) {
            float h = lineHeight;
            if (subText(lines.get(i)) != null) h += subHeight;
            blockTops[i] = y;
            blockHeights[i] = h;
            y += h + dp(lineSpacingDp);
        }
        contentHeight = y;
    }

    private String mainText(RichLyrics.Line line) {
        if (line == null || line.main == null) return "";
        if (primary == 1 && line.translation != null && !line.translation.isEmpty()) return line.translation;
        if (primary == 2 && line.romanization != null && !line.romanization.isEmpty()) return line.romanization;
        String text = line.main.getText();
        return text == null ? "" : text;
    }

    @Nullable
    private String subText(RichLyrics.Line line) {
        if (line == null) return null;
        if (primary == 0) {
            if (showTranslation && line.translation != null && !line.translation.isEmpty()) return line.translation;
            if (showRomanization && line.romanization != null && !line.romanization.isEmpty()) return line.romanization;
        } else if (primary == 1) {
            String text = line.main == null ? null : line.main.getText();
            if (text != null && !text.isEmpty()) return text;
        } else {
            String text = line.main == null ? null : line.main.getText();
            if (text != null && !text.isEmpty()) return text;
        }
        return null;
    }

    private float anchorY() {
        return getHeight() * ANCHOR;
    }

    private float targetOffset() {
        if (currentIndex < 0 || currentIndex >= blockTops.length) return 0;
        float target = blockTops[currentIndex] - anchorY();
        float max = Math.max(0, contentHeight - getHeight() * 0.4f);
        return CoverColors.clamp(target, 0, Math.max(0, max));
    }

    private void startSpring(boolean seek) {
        springStiffness = seek ? 100f : 237.3f;
        springDamping = seek ? 0.833f : 1.16f;
        springActive = true;
        lastFrameTime = 0;
        postFrame();
    }

    private final Runnable frameRunnable = new Runnable() {
        @Override
        public void run() {
            if (!springActive) return;
            long now = android.os.SystemClock.uptimeMillis();
            if (lastFrameTime == 0) lastFrameTime = now;
            float dt = Math.min(0.05f, (now - lastFrameTime) / 1000f);
            lastFrameTime = now;
            float target = targetOffset();
            // 弹簧积分：a = -k(x-target) - 2ζ√k·v
            float accel = -springStiffness * (scrollOffset - target)
                    - 2f * springDamping * (float) Math.sqrt(springStiffness) * springVelocity;
            springVelocity += accel * dt;
            scrollOffset += springVelocity * dt;
            if (Math.abs(scrollOffset - target) < 0.5f && Math.abs(springVelocity) < 5f) {
                scrollOffset = target;
                springVelocity = 0;
                springActive = false;
            }
            invalidate();
            if (springActive) postFrame();
        }
    };

    private void postFrame() {
        postOnAnimation(frameRunnable);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = event.getX();
                touchDownY = event.getY();
                touchStartY = event.getY();
                touchStartOffset = scrollOffset;
                touchMoved = false;
                springActive = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dy = event.getY() - touchStartY;
                if (Math.abs(dy) > dp(4)) {
                    touchMoved = true;
                    manualBrowsing = true;
                    float max = Math.max(0, contentHeight - getHeight() * 0.4f);
                    scrollOffset = CoverColors.clamp(touchStartOffset - dy, -dp(48), max + dp(48));
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!touchMoved) {
                    int idx = hitLine(event.getY());
                    if (idx >= 0 && listener != null && lines.get(idx).main != null) {
                        listener.onLineTap(idx, lines.get(idx).main.getTimeMs());
                    }
                    manualBrowsing = false;
                    startSpring(true);
                } else {
                    manualEndTime = android.os.SystemClock.uptimeMillis() + MANUAL_RETURN_MS;
                    postDelayed(returnRunnable, MANUAL_RETURN_MS);
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private final Runnable returnRunnable = () -> {
        if (manualBrowsing) {
            manualBrowsing = false;
            startSpring(false);
        }
    };

    private int hitLine(float viewY) {
        float contentY = viewY + scrollOffset;
        for (int i = 0; i < blockTops.length; i++) {
            if (contentY >= blockTops[i] && contentY <= blockTops[i] + blockHeights[i]) return i;
        }
        return -1;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0 || lines.isEmpty()) return;
        float padH = dp(padHDp);
        int from = Math.max(0, currentIndex - 3);
        int to = Math.min(lines.size() - 1, currentIndex + 15);
        if (currentIndex < 0) {
            from = 0;
            to = Math.min(lines.size() - 1, 15);
        }
        for (int i = from; i <= to; i++) {
            float top = blockTops[i] - scrollOffset;
            if (top + blockHeights[i] < -dp(80) || top > h + dp(80)) continue;
            drawLine(canvas, i, top, w, padH);
        }
    }

    private void drawLine(Canvas canvas, int i, float top, int w, float padH) {
        RichLyrics.Line line = lines.get(i);
        boolean active = i == currentIndex;
        float viewH = Math.max(1, getHeight());
        float anchorY = viewH * ANCHOR;
        float centerY = top + blockHeights[i] / 2f;
        float fade = 1f;
        if (centerY > anchorY) {
            float span = viewH - anchorY;
            float start = span * 0.8f;
            float pos = centerY - anchorY;
            if (pos > start && span > start) fade = bottomFadeKeep((pos - start) / (span - start));
        }
        float lineHeight = fontSizeSp * density * (4f / 3f);
        float baseline = top + lineHeight * 0.78f;

        Paint paint = mainPaint;
        int prevColor = paint.getColor();
        android.graphics.MaskFilter prevMask = paint.getMaskFilter();
        if (!active && blurLevel > 0) {
            int dist = Math.abs(i - currentIndex);
            float radius = blurLevel * dp(Math.min(dist * 2.5f, 16f));
            if (radius > 0.3f) paint.setMaskFilter(new BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL));
        }
        String text = mainText(line);
        float x = alignX(paint, text, w, padH);
        if (active) {
            drawActiveLine(canvas, line, text, x, baseline);
        } else {
            int color = palette.inactive;
            paint.setColor(Color.argb(Math.round(Color.alpha(color) * fade), Color.red(color), Color.green(color), Color.blue(color)));
            canvas.drawText(text, x, baseline, paint);
        }
        paint.setColor(prevColor);
        paint.setMaskFilter(prevMask);

        String sub = subText(line);
        if (sub != null) {
            Paint sp = subPaint;
            int sc = palette.translation;
            float subBase = baseline + lineHeight * 0.62f + dp(4) - lineHeight * 0.22f;
            sp.setColor(active ? sc : Color.argb(Math.round(Color.alpha(sc) * fade * 0.8f), Color.red(sc), Color.green(sc), Color.blue(sc)));
            float sx = alignX(sp, sub, w, padH);
            canvas.drawText(sub, sx, subBase, sp);
        }
    }

    private float alignX(Paint paint, String text, int w, float padH) {
        float tw = paint.measureText(text);
        if (align == 1) return Math.max(padH, (w - tw) / 2f);
        if (align == 2) return w - padH - tw;
        return padH;
    }

    /** 当前行：逐字推进（或整行进度回落）。 */
    private void drawActiveLine(Canvas canvas, RichLyrics.Line line, String text, float x, float baseline) {
        LyricsLine main = line.main;
        if (main == null) return;
        long lineStart = main.getTimeMs();
        long lineEnd = lineEnd(main, i2(main));
        List<LyricsWord> words = main.getWords();
        Paint paint = mainPaint;
        if (!wordByWord || words == null || words.isEmpty()) {
            float frac = lineEnd > lineStart ? CoverColors.clamp((positionMs - lineStart) / (float) (lineEnd - lineStart), 0f, 1f) : 0f;
            float splitX = x + paint.measureText(text) * frac;
            paint.setColor(palette.activeUnsung);
            canvas.drawText(text, x, baseline, paint);
            canvas.save();
            canvas.clipRect(x - dp(2), baseline - paint.getTextSize() * 1.4f, splitX, baseline + paint.getTextSize() * 0.4f);
            paint.setColor(palette.sung);
            canvas.drawText(text, x, baseline, paint);
            canvas.restore();
            return;
        }
        float cursor = x;
        for (LyricsWord word : words) {
            String wt = word.getText();
            if (wt == null || wt.isEmpty()) continue;
            long ws = lineStart + word.getStartOffsetMs();
            long we = lineStart + word.getEndOffsetMs();
            float frac = we > ws ? CoverColors.clamp((positionMs - ws) / (float) (we - ws), 0f, 1f) : (positionMs >= we ? 1f : 0f);
            float wordW = paint.measureText(wt);
            boolean singing = positionMs >= ws && positionMs < we;
            boolean longWord = (we - ws) >= 1000 && advancedFx;
            float rise = advancedFx ? -0.09f * paint.getTextSize() * frac : 0f;
            paint.setColor(palette.activeUnsung);
            canvas.drawText(wt, cursor, baseline + rise, paint);
            if (frac > 0) {
                canvas.save();
                float glowPad = longWord && singing ? paint.getTextSize() * 0.3f : 0f;
                canvas.clipRect(cursor - glowPad, baseline + rise - paint.getTextSize() * 1.5f,
                        cursor + wordW * frac + glowPad, baseline + rise + paint.getTextSize() * 0.5f);
                paint.setColor(palette.sung);
                if (longWord && singing) {
                    paint.setShadowLayer(paint.getTextSize() * 0.35f, 0, 0, palette.sung);
                    float cx = cursor + wordW / 2f;
                    float cy = baseline + rise - paint.getTextSize() * 0.32f;
                    canvas.translate(cx, cy);
                    canvas.scale(1.2f, 1.2f);
                    canvas.translate(-cx, -cy);
                }
                canvas.drawText(wt, cursor, baseline + rise, paint);
                paint.clearShadowLayer();
                canvas.restore();
            }
            cursor += wordW;
        }
    }

    private int i2(LyricsLine main) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).main == main) return i;
        }
        return -1;
    }

    private long lineEnd(LyricsLine main, int index) {
        long start = main.getTimeMs();
        List<LyricsWord> words = main.getWords();
        if (words != null && !words.isEmpty()) {
            return start + words.get(words.size() - 1).getEndOffsetMs() + 400;
        }
        if (index >= 0 && index + 1 < lines.size() && lines.get(index + 1).main != null) {
            return lines.get(index + 1).main.getTimeMs() + 400;
        }
        return start + 4000;
    }

    /** bottomFadeKeep 分段（smoothstep 插值）：0→1 / .3→.85 / .55→.62 / .78→.48 / 1→.45。 */
    static float bottomFadeKeep(float f) {
        float[][] pts = {{0f, 1f}, {0.3f, 0.85f}, {0.55f, 0.62f}, {0.78f, 0.48f}, {1f, 0.45f}};
        if (f <= 0) return 1f;
        if (f >= 1) return 0.45f;
        for (int i = 1; i < pts.length; i++) {
            if (f <= pts[i][0]) {
                float t = (f - pts[i - 1][0]) / (pts[i][0] - pts[i - 1][0]);
                float s = t * t * (3 - 2 * t);
                return pts[i - 1][1] + (pts[i][1] - pts[i - 1][1]) * s;
            }
        }
        return 0.45f;
    }

    private float dp(float v) {
        return v * density;
    }
}
