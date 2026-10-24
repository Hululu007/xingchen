package com.fongmi.android.tv.ui.music;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;

import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.player.lyrics.LyricsWord;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 歌词行绘制器（批 2，与 LyricWordEngine 共用内核）：内嵌 CoverLyricView 与全屏 FullLyricView 共用同一套
 * 逐词绘制——逐字上浮、长音光晕（BlurMaskFilter 等价呈边缘辉光，光晕边距按 §6.1 公式算出留档，
 * Canvas 文字模糊不裁剪、无需扩图，旧引擎同注）、已唱色扫边（带宽 max(9dp, 字号) 的渐变羽化，旧引擎同式）、
 * 无逐字词时整行提亮回落、超长行截断加省略号（D-11）。
 * 字斜 SKEW＝-0.12（定案）由本类统一挂到所有歌词画笔。
 */
public class LyricLineDrawer {

    private final TextPaint mainPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint subPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint glowPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Map<Float, BlurMaskFilter> blurCache = new HashMap<>();

    private LyricWordEngine.LyricPalette palette = LyricWordEngine.LyricPalette.darkFallback();
    private boolean advancedFx = true;
    private float density = 1f;

    public LyricLineDrawer() {
        mainPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        mainPaint.setTextSkewX(LyricWordEngine.SKEW);
        subPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        subPaint.setTextSkewX(LyricWordEngine.SKEW);
        glowPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        glowPaint.setTextSkewX(LyricWordEngine.SKEW);
    }

    /** 字号（px）与字距（em，Paint 口径）：主行字重 700、副行＝主行×0.62／字重 500（§6.1）。 */
    public void configure(float mainSizePx, float letterSpacingEm, float density) {
        this.density = Math.max(1f, density);
        mainPaint.setTextSize(mainSizePx);
        mainPaint.setLetterSpacing(letterSpacingEm);
        glowPaint.setTextSize(mainSizePx);
        glowPaint.setLetterSpacing(letterSpacingEm);
        subPaint.setTextSize(mainSizePx * LyricWordEngine.SUB_RATIO);
        subPaint.setLetterSpacing(letterSpacingEm);
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

    public float mainTextSizePx() {
        return mainPaint.getTextSize();
    }

    public float subTextSizePx() {
        return subPaint.getTextSize();
    }

    public TextPaint mainPaint() {
        return mainPaint;
    }

    public TextPaint subPaint() {
        return subPaint;
    }

    private BlurMaskFilter blurFilter(float radius) {
        BlurMaskFilter filter = blurCache.get(radius);
        if (filter == null) {
            filter = new BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL);
            blurCache.put(radius, filter);
        }
        return filter;
    }

    /** 非当前行主行：纯文本（截断加省略号），颜色走 palette.inactive，alpha 由调用方按距离／渐隐给（0–255 乘算）。 */
    public void drawIdleMain(Canvas canvas, String text, float x, float baseline, float maxWidth, int alpha) {
        if (text == null || text.isEmpty()) return;
        mainPaint.setColor(palette.inactive);
        mainPaint.setAlpha(alpha);
        mainPaint.setMaskFilter(null);
        mainPaint.setShader(null);
        CharSequence draw = TextUtils.ellipsize(text, mainPaint, maxWidth, TextUtils.TruncateAt.END);
        canvas.drawText(draw, 0, draw.length(), x, baseline, mainPaint);
        mainPaint.setAlpha(255);
    }

    /** 副行（翻译／罗马音）：纯文本截断；当前行满 alpha、非当前行压暗（×0.6，与旧口径 140→84 等价）。 */
    public void drawSub(Canvas canvas, String sub, float x, float baseline, float maxWidth, boolean current) {
        if (sub == null || sub.isEmpty()) return;
        subPaint.setColor(palette.translation);
        subPaint.setAlpha(current ? 255 : 153);
        subPaint.setMaskFilter(null);
        subPaint.setShader(null);
        CharSequence draw = TextUtils.ellipsize(sub, subPaint, maxWidth, TextUtils.TruncateAt.END);
        canvas.drawText(draw, 0, draw.length(), x, baseline, subPaint);
        subPaint.setAlpha(255);
    }

    /**
     * 当前行主行：有逐字词走逐词路径；无词走整行提亮回落（lineDurationMs＝本行时长，
     * ≤0 时按 X-13 同理：已过行界即整行已唱、不播动画——整行回落本就没有逐字动画）。
     */
    public void drawCurrentMain(Canvas canvas, LyricsLine line, long relativeMs, long lineDurationMs,
                                  float x, float baseline, float maxWidth) {
        if (line == null) return;
        if (line.hasWords()) {
            drawWordLine(canvas, line.getWords(), lastNonEmptyWord(line.getWords()), relativeMs, x, baseline, maxWidth);
        } else {
            drawWholeLine(canvas, line.getText(), relativeMs, lineDurationMs, x, baseline, maxWidth);
        }
    }

    /** 全屏页分行绘制：当前行的一个视觉行（词子集）；lineLastWord 为整行最后一个非空词（光晕释放尾判定用）。 */
    public void drawCurrentWordRow(Canvas canvas, List<LyricsWord> rowWords, LyricsWord lineLastWord,
                                   long relativeMs, float x, float baseline, float maxWidth) {
        drawWordLine(canvas, rowWords, lineLastWord, relativeMs, x, baseline, maxWidth);
    }

    /** 全屏页分行绘制：无词当前行的一个视觉行（整行提亮回落的片段版，进度按整行算）。 */
    public void drawCurrentPlain(Canvas canvas, String text, long relativeMs, long lineDurationMs,
                                 float x, float baseline, float maxWidth) {
        drawWholeLine(canvas, text, relativeMs, lineDurationMs, x, baseline, maxWidth);
    }

    public float measureMain(String text) {
        return text == null ? 0f : mainPaint.measureText(text);
    }

    public float measureSub(String text) {
        return text == null ? 0f : subPaint.measureText(text);
    }

    public int breakMain(String text, float maxWidth) {
        if (text == null || text.isEmpty()) return 0;
        return mainPaint.breakText(text, true, maxWidth, null);
    }

    public static LyricsWord lastNonEmptyWord(List<LyricsWord> words) {
        if (words == null) return null;
        for (int i = words.size() - 1; i >= 0; i--) {
            if (!words.get(i).getText().isEmpty()) return words.get(i);
        }
        return null;
    }

    private void drawWholeLine(Canvas canvas, String text, long relativeMs, long lineDurationMs,
                               float x, float baseline, float maxWidth) {
        if (text == null || text.isEmpty()) return;
        CharSequence draw = TextUtils.ellipsize(text, mainPaint, maxWidth, TextUtils.TruncateAt.END);
        String drawText = draw.toString();
        float width = mainPaint.measureText(drawText);
        float progress;
        if (lineDurationMs <= 0) {
            progress = relativeMs >= 0 ? 1f : 0f;
        } else {
            progress = LyricWordEngine.clampF(relativeMs / (float) lineDurationMs, 0f, 1f);
        }
        mainPaint.setMaskFilter(null);
        mainPaint.setShader(null);
        mainPaint.setColor(palette.activeUnsung);
        mainPaint.setAlpha(255);
        canvas.drawText(drawText, x, baseline, mainPaint);
        if (progress > 0f && width > 0f) {
            drawSungOverlay(canvas, drawText, x, baseline, width, progress, mainPaint, palette.sung);
        }
        mainPaint.setAlpha(255);
    }

    private void drawWordLine(Canvas canvas, List<LyricsWord> words, LyricsWord lineLastWord, long relativeMs,
                              float x, float baseline, float maxWidth) {
        float em = mainPaint.getTextSize();
        float cx = x;
        for (int i = 0; i < words.size(); i++) {
            LyricsWord word = words.get(i);
            String text = word.getText();
            if (text.isEmpty()) continue;
            float wordWidth = mainPaint.measureText(text);
            if (cx - x + wordWidth > maxWidth) {
                // D-11：超长行截断加省略号——剩余预算放得下省略号就画省略号收尾，不许溢出裁切。
                String ellipsis = "…";
                float ellWidth = mainPaint.measureText(ellipsis);
                if (cx - x + ellWidth <= maxWidth) {
                    mainPaint.setColor(palette.activeUnsung);
                    mainPaint.setAlpha(255);
                    mainPaint.setMaskFilter(null);
                    mainPaint.setShader(null);
                    canvas.drawText(ellipsis, cx, baseline, mainPaint);
                }
                break;
            }
            boolean isLast = word == lineLastWord;
            LyricWordEngine.WordState state = LyricWordEngine.wordState(word, relativeMs, isLast, advancedFx);
            float drawY = baseline + LyricWordEngine.WORD_LIFT_EM * em * state.liftProgress;
            float scale = 1f;
            if (state.isLong && state.glowProgress > 0f) {
                drawY += LyricWordEngine.EMPHASIZE_LETTER_FLOAT_EM * em * state.glowProgress;
                if (!LyricWordEngine.isCjkWord(text)) {
                    scale = 1f + 0.1f * state.glowProgress
                            * LyricWordEngine.emphasizeScaleAmount(word.getDurationMs(), isLast);
                }
            }
            int save = -1;
            if (scale != 1f) {
                save = canvas.save();
                float centerX = cx + wordWidth / 2f;
                canvas.translate(centerX, drawY);
                canvas.scale(scale, scale);
                canvas.translate(-centerX, -drawY);
            }
            if (state.isLong && state.glowProgress > 0.01f) {
                float radius = LyricWordEngine.emphasizeGlowRadius(word.getDurationMs(), isLast, em);
                // 光晕边距按 §6.1 公式算出（字号×0.12＋半径×3＋2）：GZ 用于光晕位图外扩，
                // Canvas 文字模糊不裁剪、无需扩图，此处算出留档，绘制用 BlurMaskFilter 等价呈边缘辉光。
                float glowMargin = LyricWordEngine.emphasizeGlowMargin(radius, em);
                if (radius > 0f && glowMargin > 0f) {
                    glowPaint.setColor(palette.sung);
                    glowPaint.setAlpha(Math.round(255f
                            * LyricWordEngine.emphasizeGlowIntensity(word.getDurationMs(), isLast)
                            * state.glowProgress));
                    glowPaint.setMaskFilter(blurFilter(radius));
                    glowPaint.setShader(null);
                    canvas.drawText(text, cx, drawY, glowPaint);
                    glowPaint.setMaskFilter(null);
                    glowPaint.setAlpha(255);
                }
            }
            mainPaint.setMaskFilter(null);
            mainPaint.setShader(null);
            mainPaint.setColor(palette.activeUnsung);
            mainPaint.setAlpha(255);
            canvas.drawText(text, cx, drawY, mainPaint);
            if (state.colorProgress > 0f) {
                drawSungOverlay(canvas, text, cx, drawY, wordWidth, state.colorProgress, mainPaint, palette.sung);
            }
            mainPaint.setAlpha(255);
            if (save >= 0) canvas.restoreToCount(save);
            cx += wordWidth;
        }
    }

    /** 已唱色扫边：先整段画未唱色（调用方已画），再按进度以羽化渐变裁剪叠已唱色（旧引擎同式）。 */
    private void drawSungOverlay(Canvas canvas, String text, float x, float drawY, float width,
                                 float progress, TextPaint paint, int sungColor) {
        float em = paint.getTextSize();
        if (progress >= 1f) {
            paint.setColor(sungColor);
            paint.setAlpha(255);
            paint.setShader(null);
            canvas.drawText(text, x, drawY, paint);
            return;
        }
        float feather = Math.max(9f * density, em);
        float edgeX = x + (width + feather) * progress;
        Paint.FontMetrics fm = paint.getFontMetrics();
        int saveClip = canvas.save();
        canvas.clipRect(x, drawY + fm.top - em * 0.1f,
                Math.min(edgeX, x + width), drawY + fm.bottom + em * 0.1f);
        paint.setColor(sungColor);
        paint.setAlpha(255);
        paint.setShader(new LinearGradient(edgeX - feather, 0, edgeX, 0,
                new int[]{sungColor, Color.TRANSPARENT}, null, Shader.TileMode.CLAMP));
        canvas.drawText(text, x, drawY, paint);
        paint.setShader(null);
        canvas.restoreToCount(saveClip);
    }
}
