package com.fongmi.android.tv.ui.music;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextPaint;
import android.text.TextUtils;

import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.player.lyrics.LyricsWord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 歌词行绘制器（批 2＋批 2 补正，与 LyricWordEngine 共用内核）：内嵌 CoverLyricView 与全屏 FullLyricView 共用。
 * 补正口径（审计 batch2-wordfx-audit.md §5 七条，公式只认瓜子 LyricsViewKt 源码原式）：
 * 1）扫边羽化宽＝max(9dp, 字号px)（2026-10-25 smali 深挖抠实原值，见 LyricWordEngine.sweepFeatherPx；
 *    此前 ≈0.6em 录屏反推作废）、
 *    渐变＝同色 alpha 斜坡（已唱色不变、只变 alpha，FadeBrushCache 语义），不再向 TRANSPARENT（透明黑）渐变；
 * 2）粒度逐字化：词先切 lift 单元（CJK 逐字切时窗、非 CJK 字素簇共用整词时窗），扫边与上浮逐单元推进；
 *    扫边罩整行一张、相邻单元以字框中点为界无缝 tiling，不再按词 clipRect 裁剪；
 * 3）逐单元扫速分母平滑 max(时长, min(280ms, 时长+120ms))（LyricWordEngine.sweepDurationMs）；
 * 4）长音三路分离：整词光晕进度 empEasing／逐字错峰进度／字母浮正弦驼峰＋驻留项（引擎三函数）；
 * 5）缩放与展开按绘制路径分工（见 immersive 字段）：沉浸页（FullLyricView）只给单字 CJK 缩放＋扇形 dx；
 *    内嵌（CoverLyricView）走 WordByWordText 路径，缩放与扇形只给非 CJK、进度走整词一路；
 *    两路都做 computeEmphShifts 整行字位重排（源码 :10840；WordByWord 的段位移补偿同式）；
 * 6）光晕形态＝挖空字芯的边缘光：extractAlpha＋BlurMaskFilter 取字形 alpha、DST_OUT 切掉字形本体、
 *    只剩字外一圈光（createGlyphEdgeGlow :10914），按边距扩图、alpha＝整词进度×强度绘入；原实心辉光废止。
 * 无逐字词时整行提亮回落、超长行截断加省略号（D-11）维持原口径（回落行也走新羽化与同色 alpha 斜坡）。
 * 字斜 SKEW＝-0.12（定案）由本类统一挂到所有歌词画笔。
 */
public class LyricLineDrawer {

    private final TextPaint mainPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint subPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowBitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Map<String, Bitmap> glowCache = new HashMap<>();
    private static final int GLOW_CACHE_MAX = 48;

    private LyricWordEngine.LyricPalette palette = LyricWordEngine.LyricPalette.darkFallback();
    private boolean advancedFx = true;
    private float density = 1f;
    /**
     * 绘制路径归属：true＝沉浸页路径（LiveWordLiftLine，FullLyricView 用）；
     * false＝WordByWordText 路径（CoverLyricView 内嵌用）。两路的长音缩放／扇形／字母浮口径相反，见类头第 5 条。
     */
    private boolean immersive;

    public LyricLineDrawer() {
        mainPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        mainPaint.setTextSkewX(LyricWordEngine.SKEW);
        subPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        subPaint.setTextSkewX(LyricWordEngine.SKEW);
    }

    /** 字号（px）与字距（em，Paint 口径）：主行字重 700、副行＝主行×0.62／字重 500（§6.1）。字号变则光晕缓存作废。 */
    public void configure(float mainSizePx, float letterSpacingEm, float density) {
        this.density = Math.max(1f, density);
        mainPaint.setTextSize(mainSizePx);
        mainPaint.setLetterSpacing(letterSpacingEm);
        subPaint.setTextSize(mainSizePx * LyricWordEngine.SUB_RATIO);
        subPaint.setLetterSpacing(letterSpacingEm);
        clearGlowCache();
    }

    /**
     * 主歌词字重（批 7 补线：键 music_lyric_weight 0 标准／1 加粗／2 特粗，此前只进了工具面板预览）。
     * 映射照面板预览 applyPreview 的既有先例：0→400（低版 DEFAULT）、1→700（DEFAULT_BOLD）、
     * 2→900（低版 DEFAULT_BOLD＋fakeBold 近似）。只挂主行画笔，副行 §6.1 字重 500 不动。
     * 光晕位图键不含字重，换字重必须作废缓存，否则光晕仍按旧字重出图。
     */
    public void setLyricWeight(int weight) {
        Typeface base;
        boolean fake = false;
        if (weight >= 2) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                base = Typeface.create(Typeface.DEFAULT, 900);
            } else {
                base = Typeface.DEFAULT_BOLD;
                fake = true;
            }
        } else if (weight == 1) {
            base = Typeface.DEFAULT_BOLD;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            base = Typeface.create(Typeface.DEFAULT, 400);
        } else {
            base = Typeface.DEFAULT;
        }
        mainPaint.setTypeface(base);
        mainPaint.setFakeBoldText(fake);
        clearGlowCache();
    }

    public void setPalette(LyricWordEngine.LyricPalette palette) {
        if (palette != null && palette != this.palette) {
            this.palette = palette;
            clearGlowCache();
        }
    }

    public LyricWordEngine.LyricPalette palette() {
        return palette;
    }

    public void setAdvancedFx(boolean advancedFx) {
        this.advancedFx = advancedFx;
    }

    /** 绘制路径：true＝沉浸页（FullLyricView 在 init 里置 true）；false＝WordByWordText（内嵌默认）。 */
    public void setImmersive(boolean immersive) {
        this.immersive = immersive;
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
     * 当前行主行：有逐字词走逐单元路径；无词走整行提亮回落（lineDurationMs＝本行时长，
     * ≤0 时按 X-13 同理：已过行界即整行已唱、不播动画——整行回落本就没有逐字动画）。
     */
    public void drawCurrentMain(Canvas canvas, LyricsLine line, long relativeMs, long lineDurationMs,
                                  float x, float baseline, float maxWidth) {
        if (line == null) return;
        if (line.hasWords()) {
            drawWordLine(canvas, line.getWords(), lastNonEmptyWord(line.getWords()), relativeMs, x, baseline, maxWidth,
                    line.isBG());
        } else {
            drawWholeLine(canvas, line.getText(), relativeMs, lineDurationMs, x, baseline, maxWidth);
        }
    }

    /** 全屏页分行绘制：当前行的一个视觉行（词子集）；lineLastWord 为整行最后一个非空词（光晕释放尾判定用）；
     * lineBg＝整行是否伴唱行（沉浸页字母浮 ×2 系数开关，smali 深挖抠实＝LyricLine.isBG）。 */
    public void drawCurrentWordRow(Canvas canvas, List<LyricsWord> rowWords, LyricsWord lineLastWord,
                                   long relativeMs, float x, float baseline, float maxWidth, boolean lineBg) {
        drawWordLine(canvas, rowWords, lineLastWord, relativeMs, x, baseline, maxWidth, lineBg);
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
            float em = mainPaint.getTextSize();
            float feather = LyricWordEngine.sweepFeatherPx(em, density);
            float edgeX = x + (width + feather) * progress;
            drawSungSegment(canvas, drawText, x, baseline, edgeX, feather, x, x + width);
        }
        mainPaint.setAlpha(255);
    }

    /** 一个待绘单元的全部几何／状态（先全行算齐，再统一绘——扫边罩与重排都需要整行视界）。 */
    private static class UnitDraw {
        LyricsWord word;
        LyricWordEngine.LyricUnit unit;
        boolean isLastWord;
        float x;
        float width;
        float drawY;
        float scale = 1f;
        float fanDx;
        float shift;
        float sweepProgress;
        float sweepEdgeX;
        float glowAlpha;
        float unitGlowRadius;
    }

    private void drawWordLine(Canvas canvas, List<LyricsWord> words, LyricsWord lineLastWord, long relativeMs,
                              float x, float baseline, float maxWidth, boolean lineBg) {
        if (words == null || words.isEmpty()) return;
        float em = mainPaint.getTextSize();
        float feather = LyricWordEngine.sweepFeatherPx(em, density);
        List<UnitDraw> draws = new ArrayList<>();
        float cx = x;
        boolean truncated = false;
        for (int wi = 0; wi < words.size(); wi++) {
            LyricsWord word = words.get(wi);
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
                truncated = true;
                break;
            }
            boolean isLast = word == lineLastWord;
            float[] charWidths = new float[text.length()];
            for (int ci = 0; ci < text.length(); ci++) {
                charWidths[ci] = mainPaint.measureText(text, ci, ci + 1);
            }
            List<LyricWordEngine.LyricUnit> units = LyricWordEngine.buildUnits(word, charWidths);
            float amount = LyricWordEngine.isLongWord(word)
                    ? LyricWordEngine.emphasizeScaleAmount(word.getDurationMs(), isLast) : 0f;
            float ux = cx;
            for (LyricWordEngine.LyricUnit unit : units) {
                float unitWidth = wordWidth * unit.widthFraction;
                LyricWordEngine.UnitState st = LyricWordEngine.unitState(word, unit, relativeMs, isLast, advancedFx);
                UnitDraw d = new UnitDraw();
                d.word = word;
                d.unit = unit;
                d.isLastWord = isLast;
                d.x = ux;
                d.width = unitWidth;
                float dy = LyricWordEngine.WORD_LIFT_EM * em * st.liftProgress;
                if (st.isLong && advancedFx) {
                    if (immersive) {
                        // 沉浸页路径（LiveWordLiftLine :7121–7147）：逐字错峰一路驱动缩放／扇形／驻留，
                        // 字母浮走正弦驼峰一路；缩放只给单字 CJK。
                        if (st.unitProgress > 0.001f) {
                            if (unit.singleCjkChar) {
                                d.scale = 1f + LyricWordEngine.EMPHASIZE_SCALE_COEF * st.unitProgress * amount;
                                d.fanDx = (unit.emphCount / 2f - unit.emphOrdinal)
                                        * (-st.unitProgress) * LyricWordEngine.EMPHASIZE_FAN_EM * amount * em;
                            }
                            dy += LyricWordEngine.EMPHASIZE_DWELL_EM * em * amount * st.unitProgress;
                        }
                        // ×2 系数（z2）开关 2026-10-25 smali 深挖抠实：当前行是伴唱行
                        // （LyricLine.isBG，TTML x-bg）时字母浮 ×2.0，否则 ×1.0。
                        dy += st.unitFloat * LyricWordEngine.EMPHASIZE_LETTER_FLOAT_EM * em * (lineBg ? 2.0f : 1.0f);
                    } else {
                        // WordByWordText 路径（:10366–10377）：整词 empEasing 一路驱动缩放／扇形／驻留，
                        // 缩放与扇形只给非 CJK；此路径无正弦字母浮（dy 只有上浮＋驻留，源码实读）。
                        if (st.glowProgress > 0.001f) {
                            if (!unit.firstCharCjk) {
                                d.scale = 1f + LyricWordEngine.EMPHASIZE_SCALE_COEF * st.glowProgress * amount;
                                d.fanDx = (unit.emphCount / 2f - unit.emphOrdinal)
                                        * (-st.glowProgress) * LyricWordEngine.EMPHASIZE_FAN_EM * amount * em;
                            }
                            dy += LyricWordEngine.EMPHASIZE_DWELL_EM * em * amount * st.glowProgress;
                        }
                    }
                    if (immersive) {
                        // 沉浸页光晕 alpha 时间曲线（2026-10-25 smali 深挖抠实）：
                        // alpha＝强度 glowV × 逐字错峰进度 unitProgress（empEasing 驼峰）× 上浮进度 liftProgress，
                        // ≤0.001 不绘。此前用整词一路 glowProgress 代替逐字一路，是内嵌路的口径、沉浸路用错了一路。
                        // （行级 alpha 因子：星辰当前行恒满 alpha，与瓜子行状态在本页同值，不另乘。）
                        if (st.unitProgress > 0.001f && st.liftProgress > 0f) {
                            d.unitGlowRadius = LyricWordEngine.emphasizeGlowRadius(word.getDurationMs(), isLast, em);
                            d.glowAlpha = st.unitProgress * st.liftProgress
                                    * LyricWordEngine.emphasizeGlowIntensity(word.getDurationMs(), isLast);
                        }
                    } else if (st.glowProgress > 0.01f) {
                        // 内嵌 WordByWord 路：alpha＝整词进度 emphasizeProgressTime × 强度，门槛 0.01（smali 旁证同值）。
                        d.unitGlowRadius = LyricWordEngine.emphasizeGlowRadius(word.getDurationMs(), isLast, em);
                        d.glowAlpha = st.glowProgress
                                * LyricWordEngine.emphasizeGlowIntensity(word.getDurationMs(), isLast);
                    }
                }
                d.drawY = baseline + dy;
                d.sweepProgress = st.sweepProgress;
                // 扫边前锋：CJK 词按字框逐字算；非 CJK 词各字素簇共用整词时窗、前锋按整词框算
                // （drawLiveSweepMask 非单字 CJK 分支按词框推进，:11180 一带；字素簇单元的
                // sweepProgress 因共用整词时窗与词级进度同值）。
                if (isCjkText(text)) {
                    d.sweepEdgeX = ux + (unitWidth + feather) * st.sweepProgress;
                } else {
                    d.sweepEdgeX = cx + (wordWidth + feather) * st.sweepProgress;
                }
                draws.add(d);
                ux += unitWidth;
            }
            cx += wordWidth;
        }
        if (draws.isEmpty()) return;
        computeEmphShifts(draws);
        float rowRight = cx;
        // —— 光晕（挖空边缘光，绘在字形之下，字芯保持干净） ——
        for (UnitDraw d : draws) {
            // 绘制门槛：沉浸路 0.001、内嵌路 0.01（smali 深挖两路原值不同，不许混）。
            if (d.glowAlpha > (immersive ? 0.001f : 0.01f) && d.unitGlowRadius > 0f) {
                if (immersive) {
                    drawUnitGlow(canvas, d, em);
                }
            }
        }
        if (!immersive) {
            drawWordGlows(canvas, draws, em);
        }
        // —— 未唱色打底（逐单元，带各自上浮／缩放／扇形／重排变换） ——
        mainPaint.setMaskFilter(null);
        mainPaint.setShader(null);
        mainPaint.setColor(palette.activeUnsung);
        mainPaint.setAlpha(255);
        for (UnitDraw d : draws) {
            int save = beginUnitTransform(canvas, d);
            canvas.drawText(d.unit.text, d.x, d.drawY, mainPaint);
            if (save >= 0) canvas.restoreToCount(save);
        }
        // —— 已唱色扫边罩（整行一张：逐单元同色 alpha 斜坡、以字框中点为界无缝 tiling） ——
        for (int i = 0; i < draws.size(); i++) {
            UnitDraw d = draws.get(i);
            if (d.sweepProgress <= 0f) continue;
            float visLeft = visLeft(d);
            float visRight = visRight(d);
            float tileL = i == 0 ? x - feather : (visRight(draws.get(i - 1)) + visLeft) / 2f;
            float tileR = i == draws.size() - 1 ? rowRight + feather : (visRight + visLeft(draws.get(i + 1))) / 2f;
            if (truncated) tileR = Math.min(tileR, x + maxWidth);
            // 前锋换算到单元本地坐标（与绘制变换同系）：行坐标 → 经 shift／fan／缩放逆变换。
            float edgeLocal = toLocalX(d, d.sweepEdgeX);
            float featherLocal = d.scale == 1f ? feather : feather / d.scale;
            int save = beginUnitTransform(canvas, d);
            drawSungSegment(canvas, d.unit.text, d.x, d.drawY, edgeLocal, featherLocal,
                    toLocalX(d, tileL), toLocalX(d, tileR));
            if (save >= 0) canvas.restoreToCount(save);
        }
        mainPaint.setAlpha(255);
        mainPaint.setShader(null);
    }

    private static boolean isCjkText(String text) {
        return LyricWordEngine.isCjkWord(text);
    }

    /**
     * 整行字位重排（源码 computeEmphShifts :10840 实读）：总增宽 F＝Σ(scale-1)×字宽；
     * 每单元 shift＝(已累增宽＋本单元增宽/2)－F/2；F≤0 时全行 shift＝0。
     */
    private void computeEmphShifts(List<UnitDraw> draws) {
        float total = 0f;
        for (UnitDraw d : draws) {
            if (d.width > 0f) total += (d.scale - 1f) * d.width;
        }
        if (total <= 0f) {
            for (UnitDraw d : draws) d.shift = 0f;
            return;
        }
        float acc = 0f;
        for (UnitDraw d : draws) {
            if (d.width <= 0f) {
                d.shift = 0f;
                continue;
            }
            float extra = (d.scale - 1f) * d.width;
            d.shift = (acc + extra / 2f) - total / 2f;
            acc += extra;
        }
    }

    private float visLeft(UnitDraw d) {
        float extra = (d.scale - 1f) * d.width;
        return d.x + d.fanDx + d.shift - extra / 2f;
    }

    private float visRight(UnitDraw d) {
        float extra = (d.scale - 1f) * d.width;
        return d.x + d.width + d.fanDx + d.shift + extra / 2f;
    }

    /** 单元绘制变换：先平移 (fanDx＋shift)，再绕单元中心缩放（与 GZ 绘制 lambda 的 translate＋scale 同序）。无变换返回 -1。 */
    private int beginUnitTransform(Canvas canvas, UnitDraw d) {
        float dx = d.fanDx + d.shift;
        if (dx == 0f && d.scale == 1f) return -1;
        int save = canvas.save();
        canvas.translate(dx, 0f);
        if (d.scale != 1f) {
            float centerX = d.x + d.width / 2f;
            canvas.translate(centerX, d.drawY);
            canvas.scale(d.scale, d.scale);
            canvas.translate(-centerX, -d.drawY);
        }
        return save;
    }

    /** 行坐标 → 单元本地坐标（beginUnitTransform 的逆变换，供扫边罩的渐变与裁剪定位）。 */
    private float toLocalX(UnitDraw d, float rowX) {
        float dx = d.fanDx + d.shift;
        if (d.scale == 1f) return rowX - dx;
        float centerX = d.x + d.width / 2f;
        return centerX + (rowX - dx - centerX) / d.scale;
    }

    /**
     * 已唱色扫边段：以同色 alpha 斜坡叠已唱色（FadeBrushCache 语义——颜色不变、只变 alpha：
     * 渐变两端都是已唱色本身，一端 alpha 满、一端 alpha 0，禁止用 Color.TRANSPARENT 这种透明黑，
     * 否则中段 RGB 被拉向黑发暗）。clip 由调用方按 tiling 边界给（本地坐标）。
     * 前锋 edgeX 处 alpha 落 0、edgeX-feather 处 alpha 满，edgeX 以左全满（CLAMP）。
     */
    private void drawSungSegment(Canvas canvas, String text, float drawX, float drawY,
                                 float edgeX, float feather, float clipL, float clipR) {
        if (clipR <= clipL) return;
        Paint.FontMetrics fm = mainPaint.getFontMetrics();
        float em = mainPaint.getTextSize();
        int saveClip = canvas.save();
        canvas.clipRect(clipL, drawY + fm.top - em * 0.1f, clipR, drawY + fm.bottom + em * 0.1f);
        int sung = palette.sung;
        int sungClear = sung & 0x00FFFFFF;
        mainPaint.setColor(sung);
        mainPaint.setAlpha(255);
        if (feather > 0f) {
            mainPaint.setShader(new LinearGradient(edgeX - feather, 0, edgeX, 0,
                    new int[]{sung, sungClear}, null, Shader.TileMode.CLAMP));
        } else {
            mainPaint.setShader(null);
        }
        canvas.drawText(text, drawX, drawY, mainPaint);
        mainPaint.setShader(null);
        canvas.restoreToCount(saveClip);
    }

    // —— 光晕：挖空字芯的边缘光（createGlyphEdgeGlow :10914） ——

    private void clearGlowCache() {
        for (Bitmap b : glowCache.values()) {
            if (b != null && !b.isRecycled()) b.recycle();
        }
        glowCache.clear();
    }

    /**
     * 边缘光位图（照源码式）：源字形位图 extractAlpha（BlurMaskFilter 半径）得模糊 alpha，
     * 以已唱色着色绘入扩图，再 DST_OUT 把字形本体切掉——只剩字外一圈光、字芯干净。
     * 边距 margin＝ceil(emphasizeGlowMargin)（源码 Live 路径取整口径）；源图预衬 pad＝ceil(max(3×半径, 字号×0.2))
     * （源码 Live 路径光晕源图衬边 :6823 一带），防模糊在源图边缘被截。
     * 位图按 (文本|半径|边距|衬边|已唱色|字号) 缓存，上限回收防内存涨；失败返回 null（调用方跳过光晕、不许崩）。
     */
    private Bitmap glyphEdgeGlow(String text, float radiusPx, float em) {
        int margin = (int) Math.ceil(LyricWordEngine.emphasizeGlowMargin(radiusPx, em));
        int pad = (int) Math.ceil(Math.max(3f * radiusPx, em * 0.2f));
        String key = text + '|' + Math.round(radiusPx * 2f) + '|' + margin + '|' + pad
                + '|' + palette.sung + '|' + Math.round(em);
        Bitmap cached = glowCache.get(key);
        if (cached != null && !cached.isRecycled()) return cached;
        Bitmap src = null;
        Bitmap alpha = null;
        try {
            Paint.FontMetrics fm = mainPaint.getFontMetrics();
            float textW = mainPaint.measureText(text);
            int srcW = Math.max(1, (int) Math.ceil(textW) + pad * 2);
            int srcH = Math.max(1, (int) Math.ceil(fm.bottom - fm.top) + pad * 2);
            src = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888);
            Canvas srcCanvas = new Canvas(src);
            TextPaint tp = new TextPaint(mainPaint);
            tp.setColor(palette.sung);
            tp.setAlpha(255);
            tp.setMaskFilter(null);
            tp.setShader(null);
            srcCanvas.drawText(text, pad, pad - fm.top, tp);

            int outW = srcW + margin * 2;
            int outH = srcH + margin * 2;
            if (outW <= 0 || outH <= 0) {
                src.recycle();
                return null;
            }
            Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
            Paint blurPaint = new Paint();
            blurPaint.setMaskFilter(new BlurMaskFilter(radiusPx, BlurMaskFilter.Blur.NORMAL));
            int[] offset = new int[2];
            alpha = src.extractAlpha(blurPaint, offset);
            Canvas outCanvas = new Canvas(out);
            Paint tint = new Paint(Paint.FILTER_BITMAP_FLAG);
            tint.setColorFilter(new PorterDuffColorFilter(palette.sung, PorterDuff.Mode.SRC_IN));
            outCanvas.drawBitmap(alpha, offset[0] + margin, offset[1] + margin, tint);
            Paint cut = new Paint();
            cut.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
            outCanvas.drawBitmap(src, margin, margin, cut);
            if (glowCache.size() >= GLOW_CACHE_MAX) clearGlowCache();
            glowCache.put(key, out);
            return out;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        } finally {
            if (alpha != null && !alpha.isRecycled()) alpha.recycle();
            if (src != null && !src.isRecycled()) src.recycle();
        }
    }

    /** 沉浸页路径：逐单元光晕（位图随单元变换一起缩放／平移，与 GZ 逐 lift 项绘光晕图同粒度）。 */
    private void drawUnitGlow(Canvas canvas, UnitDraw d, float em) {
        Bitmap glow = glyphEdgeGlow(d.unit.text, d.unitGlowRadius, em);
        if (glow == null) return;
        int margin = (int) Math.ceil(LyricWordEngine.emphasizeGlowMargin(d.unitGlowRadius, em));
        int pad = (int) Math.ceil(Math.max(3f * d.unitGlowRadius, em * 0.2f));
        Paint.FontMetrics fm = mainPaint.getFontMetrics();
        int save = beginUnitTransform(canvas, d);
        glowBitmapPaint.setAlpha(Math.round(255f * Math.min(1f, d.glowAlpha)));
        canvas.drawBitmap(glow, d.x - margin - pad, d.drawY + fm.top - margin - pad, glowBitmapPaint);
        glowBitmapPaint.setAlpha(255);
        if (save >= 0) canvas.restoreToCount(save);
    }

    /**
     * WordByWordText 路径：整词光晕一张（源码按强调区间整词出图、绘入 alpha＝整词进度×强度，
     * 即 d.glowAlpha 的来源一路）；位置落在词框左缘、随词首单元的变换（上浮／重排取词首单元口径近似，
     * 位图缩放映射 v＝clamp(展开宽/原宽, 0.5, 2) 未逐项搬——见交付报告拿不准单列）。
     */
    private void drawWordGlows(Canvas canvas, List<UnitDraw> draws, float em) {
        int i = 0;
        while (i < draws.size()) {
            UnitDraw first = draws.get(i);
            int j = i;
            while (j < draws.size() && draws.get(j).word == first.word) j++;
            if (first.glowAlpha > 0.01f && first.unitGlowRadius > 0f) {
                Bitmap glow = glyphEdgeGlow(first.word.getText(), first.unitGlowRadius, em);
                if (glow != null) {
                    int margin = (int) Math.ceil(LyricWordEngine.emphasizeGlowMargin(first.unitGlowRadius, em));
                    int pad = (int) Math.ceil(Math.max(3f * first.unitGlowRadius, em * 0.2f));
                    Paint.FontMetrics fm = mainPaint.getFontMetrics();
                    int save = beginUnitTransform(canvas, first);
                    glowBitmapPaint.setAlpha(Math.round(255f * Math.min(1f, first.glowAlpha)));
                    canvas.drawBitmap(glow, first.x - margin - pad,
                            first.drawY + fm.top - margin - pad, glowBitmapPaint);
                    glowBitmapPaint.setAlpha(255);
                    if (save >= 0) canvas.restoreToCount(save);
                }
            }
            i = j;
        }
    }
}
