package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.view.View;

import com.fongmi.android.tv.music.RichLyrics;
import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.player.lyrics.LyricsWord;

import java.util.List;

public class GzLyricLineView extends View {

    public static class Palette {
        public int sung;
        public int activeUnsung;
        public int inactive;
        public int translation;

        public Palette(int sung, int activeUnsung, int inactive, int translation) {
            this.sung = sung;
            this.activeUnsung = activeUnsung;
            this.inactive = inactive;
            this.translation = translation;
        }
    }

    private final TextPaint mainPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint subPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint glowPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private RichLyrics.Line line;
    private Palette palette;
    private float fontSizePx;
    private float lineHeightMul = 4f / 3f;
    private float letterSpacingPx;
    private int align = 0;
    private boolean current;
    private boolean past;
    private boolean advancedFx = true;
    private boolean showTranslation = true;
    private boolean showRomanization;
    private int primary;
    private float sungChars = -1f;
    private long smoothMs;
    private long[] charStart;
    private long[] charEnd;
    private String mainText = "";
    private String sub1 = "";
    private String sub2 = "";
    private boolean bgLine;

    public GzLyricLineView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    public void bind(RichLyrics.Line line, Palette palette, float fontSizePx, float lineHeightMul,
                     float letterSpacingPx, int align, boolean showTranslation, boolean showRomanization,
                     int primary, boolean advancedFx) {
        this.line = line;
        this.palette = palette;
        this.fontSizePx = fontSizePx;
        this.lineHeightMul = lineHeightMul;
        this.letterSpacingPx = letterSpacingPx;
        this.align = align;
        this.showTranslation = showTranslation;
        this.showRomanization = showRomanization;
        this.primary = primary;
        this.advancedFx = advancedFx;
        resolveTexts();
        buildCharTimings();
        applyPaints();
        requestLayout();
        invalidate();
    }

    private void resolveTexts() {
        mainText = "";
        sub1 = "";
        sub2 = "";
        bgLine = false;
        if (line == null || line.main == null) return;
        LyricsLine m = line.main;
        bgLine = m.isBG();
        String original = m.getText();
        String trans = line.translation == null ? "" : line.translation;
        String roma = line.romanization == null ? "" : line.romanization;
        if (primary == 1 && !trans.isEmpty()) {
            mainText = trans;
            if (showTranslation) sub1 = original;
            if (showRomanization && !roma.isEmpty()) sub2 = roma;
        } else if (primary == 2 && !roma.isEmpty()) {
            mainText = roma;
            sub1 = original;
            if (showTranslation && !trans.isEmpty()) sub2 = trans;
        } else {
            mainText = original;
            if (showTranslation && !trans.isEmpty()) sub1 = trans;
            if (showRomanization && !roma.isEmpty()) sub2 = roma;
        }
    }

    private void buildCharTimings() {
        int n = mainText.length();
        charStart = new long[n];
        charEnd = new long[n];
        if (line == null || line.main == null) return;
        long lineStart = line.main.getTimeMs();
        List<LyricsWord> words = line.main.getWords();
        if (words == null || words.isEmpty() || primary != 0) return;
        int idx = 0;
        for (LyricsWord w : words) {
            String wt = w.getText();
            if (wt == null || wt.isEmpty()) continue;
            int pos = mainText.indexOf(wt, idx);
            if (pos < 0) pos = idx;
            int len = wt.length();
            long ws = lineStart + w.getStartOffsetMs();
            long dur = w.getDurationMs();
            for (int i = 0; i < len && pos + i < n; i++) {
                charStart[pos + i] = ws + dur * i / Math.max(1, len);
                charEnd[pos + i] = ws + dur * (i + 1) / Math.max(1, len);
            }
            idx = Math.min(n, pos + len);
        }
    }

    private void applyPaints() {
        Typeface tf = Typeface.create(Typeface.DEFAULT, weightStyle());
        mainPaint.setTypeface(tf);
        mainPaint.setTextSize(fontSizePx * (bgLine ? 0.7f : 1f));
        subPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        subPaint.setTextSize(fontSizePx * 0.62f);
        glowPaint.setTypeface(tf);
        glowPaint.setTextSize(mainPaint.getTextSize());
    }

    private int weightStyle() {
        return Typeface.BOLD;
    }

    public void setWeight(int style) {
        Typeface tf = Typeface.create(Typeface.DEFAULT, style);
        mainPaint.setTypeface(tf);
        glowPaint.setTypeface(tf);
        invalidate();
    }

    public void setCurrentState(boolean current, boolean past, float sungChars, long smoothMs) {
        this.current = current;
        this.past = past;
        this.sungChars = sungChars;
        this.smoothMs = smoothMs;
        invalidate();
    }

    public boolean isCurrent() {
        return current;
    }

    public int contentHeight() {
        float main = fontSizePx * (bgLine ? 0.7f : 1f) * lineHeightMul;
        float h = main;
        if (!sub1.isEmpty()) h += dp(4) + fontSizePx * 0.62f * lineHeightMul;
        if (!sub2.isEmpty()) h += dp(4) + fontSizePx * 0.62f * lineHeightMul;
        return Math.max(1, Math.round(h));
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private float charX(int index, float startX) {
        float x = startX;
        for (int i = 0; i < index && i < mainText.length(); i++) {
            x += mainPaint.measureText(mainText, i, i + 1) + letterSpacingPx;
        }
        return x;
    }

    private float textWidth(String s, TextPaint p) {
        if (s == null || s.isEmpty()) return 0;
        return p.measureText(s) + letterSpacingPx * Math.max(0, s.length() - 1);
    }

    private float startXFor(String s, TextPaint p) {
        float w = textWidth(s, p);
        float avail = getWidth();
        if (align == 1) return Math.max(0, (avail - w) / 2f);
        if (align == 2) return Math.max(0, avail - w);
        return 0;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (palette == null || mainText == null) return;
        float mainSize = fontSizePx * (bgLine ? 0.7f : 1f);
        float baseline = mainSize * 1.02f;
        drawMain(canvas, baseline);
        float y = mainSize * lineHeightMul;
        if (!sub1.isEmpty()) {
            y += dp(4);
            drawSub(canvas, sub1, y + fontSizePx * 0.62f * 1.02f);
            y += fontSizePx * 0.62f * lineHeightMul;
        }
        if (!sub2.isEmpty()) {
            y += dp(4);
            drawSub(canvas, sub2, y + fontSizePx * 0.62f * 1.02f);
        }
    }

    private void drawSub(Canvas canvas, String s, float baseline) {
        subPaint.setColor(palette.translation);
        float x = startXFor(s, subPaint);
        canvas.drawText(s, x, baseline, subPaint);
    }

    private void drawMain(Canvas canvas, float baseline) {
        float startX = startXFor(mainText, mainPaint);
        if (!current) {
            mainPaint.setColor(past ? palette.inactive : palette.inactive);
            drawLifted(canvas, startX, baseline, false);
            return;
        }
        mainPaint.setColor(palette.activeUnsung);
        drawLifted(canvas, startX, baseline, false);
        if (sungChars <= 0) return;
        float clipRight;
        int full = (int) Math.floor(sungChars);
        if (full >= mainText.length()) {
            clipRight = getWidth();
        } else {
            float fx = charX(full, startX);
            float cw = mainPaint.measureText(mainText, full, full + 1);
            clipRight = fx + cw * (sungChars - full);
        }
        int save = canvas.save();
        canvas.clipRect(0, 0, clipRight, getHeight());
        mainPaint.setColor(palette.sung);
        drawLifted(canvas, startX, baseline, true);
        canvas.restoreToCount(save);
    }

    private void drawLifted(Canvas canvas, float startX, float baseline, boolean sungPass) {
        if (!advancedFx || charStart == null || charStart.length != mainText.length()) {
            canvas.drawText(mainText, startX, baseline, mainPaint);
            return;
        }
        float x = startX;
        long lineStart = line == null || line.main == null ? 0 : line.main.getTimeMs();
        for (int i = 0; i < mainText.length(); i++) {
            String ch = mainText.substring(i, i + 1);
            float cw = mainPaint.measureText(ch);
            float lift = 0f;
            long cs = charStart[i];
            long ce = charEnd[i];
            if (ce > cs && smoothMs >= cs) {
                float p = (smoothMs - cs) / (float) Math.max(1100L, ce - cs);
                p = Math.max(0f, Math.min(1f, p));
                float e = 1f - (1f - p) * (1f - p) * (1f - p);
                lift = e * -0.09f * fontSizePx;
            }
            float emph = emphasisFloat(i, lineStart);
            float dy = lift + emph;
            if (sungPass) drawGlow(canvas, ch, x, baseline + dy, i, lineStart);
            canvas.drawText(ch, x, baseline + dy, mainPaint);
            x += cw + letterSpacingPx;
        }
    }

    private float emphasisFloat(int charIndex, long lineStart) {
        if (!advancedFx || line == null || line.main == null) return 0f;
        List<LyricsWord> words = line.main.getWords();
        if (words == null || words.isEmpty()) return 0f;
        int idx = 0;
        for (LyricsWord w : words) {
            String wt = w.getText();
            if (wt == null || wt.isEmpty()) continue;
            int pos = mainText.indexOf(wt, idx);
            if (pos < 0) pos = idx;
            int len = wt.length();
            if (charIndex >= pos && charIndex < pos + len && w.getDurationMs() >= 1000L) {
                int inWord = charIndex - pos;
                float stagger = (w.getDurationMs() / 2.5f) * (inWord / (float) Math.max(1, len));
                float t = (smoothMs - (lineStart + w.getStartOffsetMs()) - (stagger - 400f))
                        / (w.getDurationMs() * 1.4f);
                t = Math.max(0f, Math.min(1f, t));
                float s = (float) Math.sin(t * Math.PI);
                if (s < 0) s = 0;
                return s * -0.05f * fontSizePx;
            }
            idx = Math.min(mainText.length(), pos + len);
        }
        return 0f;
    }

    private void drawGlow(Canvas canvas, String ch, float x, float baseline, int charIndex, long lineStart) {
        if (!advancedFx || line == null || line.main == null) return;
        List<LyricsWord> words = line.main.getWords();
        if (words == null || words.isEmpty()) return;
        int idx = 0;
        for (LyricsWord w : words) {
            String wt = w.getText();
            if (wt == null || wt.isEmpty()) continue;
            int pos = mainText.indexOf(wt, idx);
            if (pos < 0) pos = idx;
            int len = wt.length();
            if (charIndex >= pos && charIndex < pos + len) {
                long dur = w.getDurationMs();
                if (dur < 1000L) return;
                if (!isCjk(wt) && (len < 2 || len >= 8)) return;
                boolean lineEnd = pos + len >= mainText.length();
                float sec = Math.max(1f, Math.min(8f, dur / 1000f));
                float alphaCoef = Math.min(lineEnd ? 0.95f : 0.85f,
                        (0.6f + 0.045f * (sec - 1f)) * (lineEnd ? 1.1f : 1f));
                float blurCoef = Math.min(lineEnd ? 0.6f : 0.5f,
                        (0.3f + 0.012f * (sec - 1f)) * (lineEnd ? 1.15f : 1f));
                long denom = Math.max(1000L, dur) + (lineEnd ? 400L : 0L);
                float p = (smoothMs - (lineStart + w.getStartOffsetMs())) / (float) denom;
                p = Math.max(0f, Math.min(1f, p));
                if (p <= 0.01f) return;
                float e = 1f - (1f - p) * (1f - p) * (1f - p);
                glowPaint.setColor(palette.sung);
                glowPaint.setAlpha(Math.round(255f * alphaCoef * e));
                float radius = Math.max(1f, blurCoef * fontSizePx * 0.5f);
                glowPaint.setMaskFilter(new BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL));
                canvas.drawText(ch, x, baseline, glowPaint);
                glowPaint.setMaskFilter(null);
                return;
            }
            idx = Math.min(mainText.length(), pos + len);
        }
    }

    private boolean isCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return true;
        }
        return false;
    }

    public Rect measureLineBounds() {
        return new Rect(0, 0, getWidth(), contentHeight());
    }
}
