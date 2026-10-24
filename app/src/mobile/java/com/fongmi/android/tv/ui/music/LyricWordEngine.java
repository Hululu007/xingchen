package com.fongmi.android.tv.ui.music;

import com.fongmi.android.tv.player.lyrics.LyricsWord;

/**
 * 逐字歌词引擎·共享内核（批 2）：内嵌歌词 CoverLyricView 与全屏歌词页 FullLyricView 共用。
 * 数值只认施工图 §6.1（瓜子 LyricsViewKt 常量区实读）：
 * 逐字上浮 WORD_LIFT_EM＝-0.09em、进度分母 max(1100ms, 词时长)；
 * 长音光晕：词时长 ≥1000ms 才算长音（非 CJK 另要求词长 2–&lt;8 字、CJK 直接算）、
 * EMPHASIZE_LETTER_FLOAT_EM＝-0.05、释放尾 400ms、光晕边距＝字号×0.12＋半径×3＋2、
 * COVER_GLOW_OVERFLOW＝96、缩放曲线 ×0.6（行末 ×1.6、上限 1.2）；
 * 光晕强度／半径系数照旧引擎已逐系数核对的 GZ emphasizeGlow 转写（BASE 0.6／PER_SEC 0.045／CAP 0.85，
 * 行末 ×1.1 封顶 0.95；半径 BASE 0.3／PER_SEC 0.012／CAP 0.5，行末 ×1.15 封顶 0.6）；
 * 副行字号＝主行×0.62、行高＝字号×4/3、字重 500；
 * 模糊三档系数 off 0／light 0.45／medium 1.0，绘制半径＝round(强度×2)/2（0.5 步进量化）。
 * 歌词字斜 SKEW＝-0.12 是定案值（非瓜子值，§18 冲突 4），内嵌与全屏两处都要。
 *
 * X-13 守卫：时长 ≤0 的词不许按 Math.max(1,…) 当 1ms 一闪「唱完」——零时长词在行界
 * （播放位置到达词起点）即时完成变色，但不触发上浮／光晕／缩放任何动画（见 wordState）。
 */
public final class LyricWordEngine {

    /** 歌词斜度（定案）：-0.12，两处歌词共用。 */
    public static final float SKEW = -0.12f;

    // —— 逐字上浮（§6.1） ——
    public static final float WORD_LIFT_EM = -0.09f;
    public static final long WORD_LIFT_MIN_DURATION_MS = 1100L;

    // —— 长音光晕（§6.1） ——
    public static final long LONG_WORD_MS = 1000L;
    public static final float EMPHASIZE_LETTER_FLOAT_EM = -0.05f;
    public static final long EMPH_RELEASE_TAIL_MS = 400L;
    public static final float COVER_GLOW_OVERFLOW = 96.0f;
    private static final float EMPH_GLOW_BASE = 0.6f;
    private static final float EMPH_GLOW_PER_SEC = 0.045f;
    private static final float EMPH_GLOW_CAP = 0.85f;
    private static final float EMPH_GLOW_LAST_MUL = 1.1f;
    private static final float EMPH_GLOW_LAST_CAP = 0.95f;
    private static final float EMPH_RADIUS_BASE = 0.3f;
    private static final float EMPH_RADIUS_PER_SEC = 0.012f;
    private static final float EMPH_RADIUS_CAP = 0.5f;
    private static final float EMPH_RADIUS_LAST_MUL = 1.15f;
    private static final float EMPH_RADIUS_LAST_CAP = 0.6f;

    // —— 副行（§6.1） ——
    public static final float SUB_RATIO = 0.62f;
    /** 行高倍率：主行／副行均为字号×4/3（封面与 CD／唱片架口径）；全屏页行高另走 ×1.3（§6.3）。 */
    public static final float LINE_HEIGHT_RATIO = 4f / 3f;

    private LyricWordEngine() {
    }

    /** 歌词四色口径：已唱／当前未唱／非当前／翻译。深色面回落与浅色面常量见 §6.1；有 palette 时用 palette 口径。 */
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

        /** 深色面回落（无 palette 时，§6.1）：已唱 0xFFEAEAEA、当前未唱 白 0.42、非当前 白 0.30、翻译 白 0.55。 */
        public static LyricPalette darkFallback() {
            return new LyricPalette(0xFFEAEAEA, 0x6BFFFFFF, 0x4DFFFFFF, 0x8CFFFFFF);
        }

        /** 浅色面常量（§6.1）：已唱 0xFFFF2D55、当前未唱 0xFF3C3C43、非当前 0xFF9A9AA0、翻译 0xFF8A8A90。 */
        public static LyricPalette lightFace() {
            return new LyricPalette(0xFFFF2D55, 0xFF3C3C43, 0xFF9A9AA0, 0xFF8A8A90);
        }

        /** palette 口径（照流光 CD PlayerLyricPalette(ink, ink×0.42, ink×0.38, ink×0.42) 的四值结构，由调用方给四色）。 */
        public static LyricPalette of(int sung, int activeUnsung, int inactive, int translation) {
            return new LyricPalette(sung, activeUnsung, inactive, translation);
        }
    }

    public static float clampF(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /** GZ isCJK 逐值转写（旧引擎同源）：平假名／CJK 统一表意／兼容表意区段。 */
    public static boolean isCJK(char c) {
        if (12352 <= c && c < 12544) return true;
        if (19968 <= c && c < 40960) return true;
        if (13312 <= c && c < 19904) return true;
        if (63744 > c || c >= 64256) return 12288 <= c && c < 12352;
        return true;
    }

    /** 整词是否全 CJK（空串／null 按非 CJK 计）。 */
    public static boolean isCjkWord(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            if (!isCJK(text.charAt(i))) return false;
        }
        return true;
    }

    /**
     * 长音判定（§6.1）：词时长 ≥1000ms 才算长音；CJK 词直接算，非 CJK 另要求词长 2–&lt;8 字。
     * X-13：时长 ≤0 的词永不算长音（不触发光晕）。
     */
    public static boolean isLongWord(LyricsWord word) {
        if (word == null) return false;
        long duration = word.getDurationMs();
        if (duration < LONG_WORD_MS) return false;
        String text = word.getText();
        if (isCjkWord(text)) return true;
        int len = text == null ? 0 : text.length();
        return len >= 2 && len < 8;
    }

    /** 单个词在 relative（相对行首 ms）时刻的渲染状态。 */
    public static final class WordState {
        /** 变色进度 0–1（已唱色覆盖比例）。 */
        public float colorProgress;
        /** 上浮进度 0–1（已 easeOut）；位移＝WORD_LIFT_EM×字号×此值。 */
        public float liftProgress;
        /** 光晕进度 0–1（已 empEasing）；非长音恒 0。 */
        public float glowProgress;
        /** 是否长音（光晕／字母浮动／缩放的触发门）。 */
        public boolean isLong;
        /** X-13：零时长词——即时完成、不许触发任何动画（lift／glow 恒 0）。 */
        public boolean instant;
    }

    /**
     * 词状态计算（X-13 守卫落点）：时长 ≤0 时不套 Math.max(1,…) 的假分母——
     * relative 到达词起点即 colorProgress＝1（行界即时完成），lift／glow 恒 0、不播动画。
     * 正常词：变色按词时长线性；上浮分母＝max(1100ms, 词时长)（§6.1）；
     * 光晕分母＝max(1000ms, 词时长)，行末词另加 400ms 释放尾。
     */
    public static WordState wordState(LyricsWord word, long relativeMs, boolean isLastWord, boolean advancedFx) {
        WordState state = new WordState();
        if (word == null) return state;
        long start = word.getStartOffsetMs();
        long duration = word.getDurationMs();
        if (duration <= 0) {
            // X-13：零时长词按行界即时完成，不许一闪而过地「唱完」，也不许触发上浮／光晕。
            state.instant = true;
            state.colorProgress = relativeMs >= start ? 1f : 0f;
            return state;
        }
        state.colorProgress = clampF((relativeMs - start) / (float) duration, 0f, 1f);
        if (!advancedFx) return state;
        state.liftProgress = easeOut(clampF((relativeMs - start)
                / (float) Math.max(WORD_LIFT_MIN_DURATION_MS, duration), 0f, 1f));
        state.isLong = isLongWord(word);
        if (state.isLong) {
            long denom = Math.max(LONG_WORD_MS, duration) + (isLastWord ? EMPH_RELEASE_TAIL_MS : 0L);
            state.glowProgress = empEasing(clampF((relativeMs - start) / (float) denom, 0f, 1f));
        }
        return state;
    }

    /** 长音光晕强度（GZ emphasizeGlow 转写）：s＝clamp(时长/1000, 1, 8)，先封顶、行末再乘再封顶，顺序不可换。 */
    public static float emphasizeGlowIntensity(long durationMs, boolean isLastWord) {
        float s = clampF(durationMs / 1000f, 1f, 8f);
        float intensity = Math.min((s - 1f) * EMPH_GLOW_PER_SEC + EMPH_GLOW_BASE, EMPH_GLOW_CAP);
        if (isLastWord) intensity = Math.min(intensity * EMPH_GLOW_LAST_MUL, EMPH_GLOW_LAST_CAP);
        return intensity;
    }

    /** 长音光晕半径 px（GZ emphasizeGlow 转写）：系数同上顺序，×字号 px。 */
    public static float emphasizeGlowRadius(long durationMs, boolean isLastWord, float emPx) {
        float s = clampF(durationMs / 1000f, 1f, 8f);
        float coef = Math.min((s - 1f) * EMPH_RADIUS_PER_SEC + EMPH_RADIUS_BASE, EMPH_RADIUS_CAP);
        if (isLastWord) coef = Math.min(coef * EMPH_RADIUS_LAST_MUL, EMPH_RADIUS_LAST_CAP);
        return coef * emPx;
    }

    /** 光晕边距（§6.1 emphasizeGlowMargin）：字号×0.12＋半径×3＋2。 */
    public static float emphasizeGlowMargin(float radiusPx, float emPx) {
        return emPx * 0.12f + radiusPx * 3f + 2f;
    }

    /**
     * 长音缩放量（GZ emphasizeScaleAmount 转写）：x＝时长/2000，x＞1 取 sqrt、否则取立方，×0.6；
     * 行末 ×1.6、上限 1.2。实际缩放＝1＋0.1×光晕进度×此量，且只对非 CJK 字（CJK 恒 1）。
     */
    public static float emphasizeScaleAmount(long durationMs, boolean isLastWord) {
        float x = durationMs / 2000f;
        float shaped = x > 1f ? (float) Math.sqrt(x) : x * x * x;
        float amount = shaped * 0.6f;
        if (isLastWord) amount *= 1.6f;
        return Math.min(amount, 1.2f);
    }

    /** 模糊档位系数（§6.1）：off 0／light 0.45／medium（含其余）1.0。 */
    public static float blurCoefficient(int level) {
        if (level == 0) return 0f;
        if (level == 1) return 0.45f;
        return 1.0f;
    }

    /** 绘制半径量化（§6.1 应用侧）：E＝round(强度×2)、半径＝E/2（0.5 步进）。 */
    public static float quantizeBlurRadius(float strength) {
        if (strength <= 0f) return 0f;
        int e = Math.round(strength * 2f);
        return e / 2f;
    }

    // —— 缓动（旧引擎同源转写：easeOut＝cubic-bezier(0,0,0.58,1)；empEasing 两段 bezier） ——

    public static float easeOut(float x) {
        return cubicBezier(0f, 0f, 0.58f, 1f, x);
    }

    public static float empEasing(float x) {
        float v = clampF(x, 0f, 1f);
        if (v < 0.5f) return cubicBezier(0.2f, 0.4f, 0.58f, 1f, v / 0.5f);
        return 1f - cubicBezier(0.3f, 0f, 0.58f, 1f, (v - 0.5f) / 0.5f);
    }

    private static float cubicBezier(float p1x, float p1y, float p2x, float p2y, float x) {
        float t = clampF(x, 0f, 1f);
        for (int i = 0; i < 8; i++) {
            float cx = bezier(p1x, p2x, t);
            float dx = cx - x;
            if (Math.abs(dx) < 0.001f) break;
            float slope = bezierSlope(p1x, p2x, t);
            if (Math.abs(slope) < 0.0001f) break;
            t -= dx / slope;
            t = clampF(t, 0f, 1f);
        }
        return bezier(p1y, p2y, t);
    }

    private static float bezier(float p1, float p2, float t) {
        float u = 1f - t;
        return 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t;
    }

    private static float bezierSlope(float p1, float p2, float t) {
        float u = 1f - t;
        return 3f * u * u * p1 + 6f * u * t * (p2 - p1) + 3f * t * t * (1f - p2);
    }
}
