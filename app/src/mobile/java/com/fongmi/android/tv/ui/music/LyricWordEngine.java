package com.fongmi.android.tv.ui.music;

import android.graphics.Color;
import android.graphics.Paint;

import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.player.lyrics.LyricsWord;

import java.util.List;

/**
 * 逐字歌词引擎：常量与纯计算（无视图状态）。
 *
 * 数值口径（gz-full-parity-1025 §7.1 / §15.1 第 6 项，瓜子 LyricsViewKt 常量区与 smali 本体）：
 * 逐字上浮 −0.09em、分母 max(1100ms, 词时长)；长音门 词时长 ≥1000ms（非 CJK 另要求词长 2–&lt;8）；
 * 长音光晕浮 −0.05em、释放尾 400ms、边距＝字号×0.12＋半径×3＋2；
 * 缩放 ×0.6 基（正弦包络系数）、特定态峰值 ×1.6、上限钳 1.2；
 * 副行字号＝主行×0.62、字重 500；行高比 4/3（全屏面 1.3 由视图侧另给）。
 * 斜度 SKEW −0.12 是潇洒哥定案（瓜子引擎无斜度字面量），四个歌词面统一挂画笔。
 * 模糊：档位系数 off 0 / light 0.45 / medium 1.0；半径量化 round(强度×2)/2。
 * 距离衰减未定项（§15.7 卡点 1）：瓜子两开关的源码语义名未证，本实现只取一支并在此注明——
 * 下方支 rel≥0 → min(rel×2.5, 16.0)，上方默认支（入参假支）rel&lt;0 → min((−rel−1)×2.0＋2.5, 10.0)，
 * 末尾整体 ×档位系数。不冒充全式。
 * 弹簧刚度 amllPosYComposeStiffness 为 §15.1 第 6 项函数本体：
 * t＝(clamp(j,100,800)−100)/700，返回 ((pow(1−t, 0.2)×50)＋170)/0.9（450ms → 237.25）。
 */
public final class LyricWordEngine {

    /** 逐字上浮量（em）。 */
    public static final float WORD_LIFT_EM = -0.09f;
    /** 上浮进度分母下限（ms）。 */
    public static final long LIFT_MIN_DENOM_MS = 1100L;
    /** 长音门：词时长下限（ms）。 */
    public static final long LONG_WORD_MIN_MS = 1000L;
    /** 长音光晕浮量（em）。 */
    public static final float EMPHASIZE_FLOAT_EM = -0.05f;
    /** 长音光晕释放尾（ms）。 */
    public static final long RELEASE_TAIL_MS = 400L;
    /** 缩放包络基系数。 */
    public static final float SCALE_BASE = 0.6f;
    /** 缩放特定态峰值。 */
    public static final float SCALE_PEAK = 1.6f;
    /** 缩放上限。 */
    public static final float SCALE_MAX = 1.2f;
    /** 副行字号比（主行×0.62）。 */
    public static final float SUB_RATIO = 0.62f;
    /** 副行字重。 */
    public static final int SUB_WEIGHT = 500;
    /** 行高比 4/3（封面/CD/唱片架面；全屏面 1.3）。 */
    public static final float LINE_HEIGHT_RATIO = 4f / 3f;
    /** 字形斜度（他定案，四面统一）。 */
    public static final float SKEW = -0.12f;
    /** 模糊档位系数。 */
    public static final float BLUR_OFF = 0f;
    public static final float BLUR_LIGHT = 0.45f;
    public static final float BLUR_MEDIUM = 1.0f;
    /** 行 alpha 乘积低于此值不上模糊（§15.1 第 6 项消费端口径）。 */
    public static final float BLUR_ALPHA_GATE = 0.08f;

    private LyricWordEngine() {
    }

    /** 四色调色板：已唱 / 当前未唱 / 非当前 / 翻译副行。 */
    public static final class LyricPalette {
        public final int sung;
        public final int activeUnsung;
        public final int inactive;
        public final int translation;

        public LyricPalette(int sung, int activeUnsung, int inactive, int translation) {
            this.sung = sung;
            this.activeUnsung = activeUnsung;
            this.inactive = inactive;
            this.translation = translation;
        }

        public static LyricPalette of(int sung, int activeUnsung, int inactive, int translation) {
            return new LyricPalette(sung, activeUnsung, inactive, translation);
        }

        /** 深色面回落（§7.1）：已唱 0xFFEAEAEA、当前未唱 白0.42、非当前 白0.30、翻译 白0.55。 */
        public static LyricPalette darkFallback() {
            return new LyricPalette(0xFFEAEAEA, 0x6BFFFFFF, 0x4DFFFFFF, 0x8CFFFFFF);
        }

        /** 浅色面四值（§7.1）：已备，当前无浅色歌词面消费。 */
        public static LyricPalette lightFace() {
            return new LyricPalette(0xFFFF2D55, 0xFF3C3C43, 0xFF9A9AA0, 0xFF8A8A90);
        }
    }

    /** 单词在某时刻的演唱状态。 */
    public static final class WordState {
        public final boolean started;
        public final boolean finished;
        /** 0..1，词内进度（未开始 0、唱完 1）。 */
        public final float progress;

        public WordState(boolean started, boolean finished, float progress) {
            this.started = started;
            this.finished = finished;
            this.progress = progress;
        }
    }

    public static WordState wordState(long lineStartMs, LyricsWord word, long positionMs) {
        if (word == null) return new WordState(false, false, 0f);
        long start = lineStartMs + word.getStartOffsetMs();
        long duration = word.getDurationMs();
        if (positionMs < start) return new WordState(false, false, 0f);
        if (duration <= 0) return new WordState(true, true, 1f);
        if (positionMs >= start + duration) return new WordState(true, true, 1f);
        float progress = (positionMs - start) / (float) duration;
        return new WordState(true, false, clamp01(progress));
    }

    /** 长音判定：时长 ≥1000ms；CJK 词直接算，非 CJK 另要求词长 2–&lt;8 字。 */
    public static boolean isLongWord(LyricsWord word) {
        if (word == null || word.getDurationMs() < LONG_WORD_MIN_MS) return false;
        String text = word.getText();
        if (text == null || text.isEmpty()) return false;
        if (containsCjk(text)) return true;
        int length = text.codePointCount(0, text.length());
        return length >= 2 && length < 8;
    }

    private static boolean containsCjk(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF)
                    || (c >= 0x3040 && c <= 0x30FF) || (c >= 0xAC00 && c <= 0xD7AF)) return true;
        }
        return false;
    }

    /** 逐字上浮（em）：进度分母 max(1100ms, 词时长)。 */
    public static float wordLiftEm(LyricsWord word, long positionMs, long lineStartMs) {
        if (word == null) return 0f;
        WordState state = wordState(lineStartMs, word, positionMs);
        if (!state.started) return 0f;
        long start = lineStartMs + word.getStartOffsetMs();
        long denom = Math.max(LIFT_MIN_DENOM_MS, word.getDurationMs());
        float p = clamp01((positionMs - start) / (float) denom);
        return WORD_LIFT_EM * p;
    }

    /**
     * 长音光晕浮（em）：唱中随词进度到 −0.05em，唱完后 400ms 释放尾线性回 0。
     * 非长音恒 0。
     */
    public static float glowLiftEm(LyricsWord word, long positionMs, long lineStartMs) {
        if (!isLongWord(word)) return 0f;
        long start = lineStartMs + word.getStartOffsetMs();
        long end = start + word.getDurationMs();
        if (positionMs < start) return 0f;
        if (positionMs <= end) {
            float p = word.getDurationMs() <= 0 ? 1f : clamp01((positionMs - start) / (float) word.getDurationMs());
            return EMPHASIZE_FLOAT_EM * p;
        }
        long tail = positionMs - end;
        if (tail >= RELEASE_TAIL_MS) return 0f;
        return EMPHASIZE_FLOAT_EM * (1f - tail / (float) RELEASE_TAIL_MS);
    }

    /** 长音光晕是否点亮（含释放尾）。 */
    public static boolean glowActive(LyricsWord word, long positionMs, long lineStartMs) {
        if (!isLongWord(word)) return false;
        long start = lineStartMs + word.getStartOffsetMs();
        long end = start + word.getDurationMs();
        return positionMs >= start && positionMs < end + RELEASE_TAIL_MS;
    }

    /** 长音缩放：正弦包络 1＋0.6·sin(πp)，峰值名义 1.6、上限钳 1.2；非长音恒 1。 */
    public static float wordScale(LyricsWord word, long positionMs, long lineStartMs) {
        if (!isLongWord(word)) return 1f;
        WordState state = wordState(lineStartMs, word, positionMs);
        if (!state.started || state.finished) return 1f;
        float raw = 1f + SCALE_BASE * (float) Math.sin(Math.PI * state.progress);
        return Math.min(SCALE_MAX, raw);
    }

    /** 光晕边距（px）：字号×0.12＋半径×3＋2。 */
    public static float glowMarginPx(float fontSizePx, float glowRadiusPx) {
        return fontSizePx * 0.12f + glowRadiusPx * 3f + 2f;
    }

    /** AMLL 行位弹簧刚度函数本体（§15.1 第 6 项）：入参为当前行与上一行时长差（ms），缺失回落 450。 */
    public static float amllPosYComposeStiffness(long deltaMs) {
        long j = Math.max(100L, Math.min(800L, deltaMs));
        double t = (j - 100) / 700.0;
        return (float) (((Math.pow(1.0 - t, 0.2) * 50.0) + 170.0) / 0.9);
    }

    /** 模糊强度量化：round(强度×2)/2。 */
    public static float blurStrength(float rawStrength) {
        return Math.round(rawStrength * 2f) / 2f;
    }

    /** 档位（0/1/2）→模糊系数（0 / 0.45 / 1.0）。 */
    public static float blurCoefficient(int level) {
        if (level <= 0) return BLUR_OFF;
        if (level == 1) return BLUR_LIGHT;
        return BLUR_MEDIUM;
    }

    /**
     * 非当前行模糊距离强度（衰减支选取见类头未定项注明）：
     * rel＝行号−锚行号；rel＝0 恒 0；结果再由调用方乘档位系数并量化。
     */
    public static float distanceBlurStrength(int rel) {
        if (rel == 0) return 0f;
        if (rel > 0) return Math.min(rel * 2.5f, 16.0f);
        return Math.min((-rel - 1) * 2.0f + 2.5f, 10.0f);
    }

    /** 给画笔统一挂斜度（他定案 −0.12）。 */
    public static void applySkew(Paint paint) {
        if (paint != null) paint.setTextSkewX(SKEW);
    }

    /** 颜色乘 alpha 因子（0..1），返回新颜色。 */
    public static int withAlphaFactor(int color, float factor) {
        float f = clamp01(factor);
        int alpha = Math.round(Color.alpha(color) * f);
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    /** 当前行下标：最后一个 timeMs ≤ positionMs 的行；无行或全在将来时返回 -1。 */
    public static int indexAt(List<LyricsLine> lines, long positionMs) {
        if (lines == null || lines.isEmpty()) return -1;
        int index = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).getTimeMs() <= positionMs) index = i;
            else break;
        }
        return index;
    }

    public static float clamp01(float value) {
        if (value < 0f) return 0f;
        if (value > 1f) return 1f;
        return value;
    }
}
