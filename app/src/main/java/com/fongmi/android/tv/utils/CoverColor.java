package com.fongmi.android.tv.utils;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * 用途：从专辑封面提取主色与氛围底色，供音乐播放页整页取色（纯静态工具，无状态、无缓存）。
 */
public final class CoverColor {

    // ===== 常量区（数值出处：GZ 反编译 CoverAmbientKt 自研取色算法实证，2026-10-21） =====
    /** 兜底色：空图／全透明图时返回（GZ CoverAmbientKt 默认色 0xFF1C1C1E 原值）。 */
    public static final int FALLBACK_COLOR = 0xFF1C1C1E;
    /** 调用方应先把图缩到 ≤96px；方法内再兜底缩放到的最长边。 */
    private static final int MAX_EDGE = 96;
    /** 自研量化器档数：每通道量化到 24 档后聚合直方图（GZ 口径）。 */
    private static final int QUANT_LEVELS = 24;
    /** 下半区域起始比例：画面下方 55%（即 y ≥ 高度 × 0.45 起）。 */
    private static final float LOWER_START_RATIO = 0.45f;
    /** 下半区域采样权重倍率（GZ 口径 ×1.35）。 */
    private static final double LOWER_WEIGHT = 1.35d;
    /** HSV 过滤（GZ 主路函数 e，CoverAmbientKt:1152/1162）：明度 V∈[0.06,0.92]、饱和度 S≥0.1（HSV 口径）。 */
    private static final float MIN_VALUE = 0.06f;
    private static final float MAX_VALUE = 0.92f;
    private static final float MIN_SATURATION = 0.10f;
    /** 打分（GZ 主路）：S≥0.12 时饱和系数 1.0，否则 0.6。 */
    private static final float SATURATION_FULL_THRESHOLD = 0.12f;
    /** 暖色相降权（GZ 主路）：色相 5–50° 且 S∈[0.12,0.72] 且 V∈[0.25,0.82] 时打分 ×0.3。 */
    private static final float WARM_HUE_MIN = 5f;
    private static final float WARM_HUE_MAX = 50f;
    private static final float WARM_SAT_MIN = 0.12f;
    private static final float WARM_SAT_MAX = 0.72f;
    private static final float WARM_VAL_MIN = 0.25f;
    private static final float WARM_VAL_MAX = 0.82f;
    private static final double WARM_PENALTY = 0.3d;
    /** 低于此 alpha 的像素视为透明、跳过采样。 */
    private static final int MIN_ALPHA = 128;
    /** ambientBackground 压暗口径：主色向黑色混合的比例（GZ 口径 0.55）。 */
    private static final float AMBIENT_DARKEN_MIX = 0.55f;
    /** isBright 的相对亮度阈值（0–1）。 */
    private static final float BRIGHT_LUMINANCE = 0.62f;

    private CoverColor() {
    }

    /**
     * 提取封面主色（GZ 主路函数 e 口径）：24 档量化扫全图（下半 55% 区域权重 ×1.35），
     * 对每个色桶取均值色做 HSV 过滤（V∈[0.06,0.92]、S≥0.1）后按 GZ 主路打分
     * 「计数 ×(S≥0.12?1:0.6) ×暖色相降权」计分，按色相分桶（12 桶）取最高桶内最高分。
     * 同步方法，请在后台线程调用；入参为小图（≤96px），方法内再兜底缩放。
     */
    public static int dominantColor(Bitmap bmp) {
        Bitmap small = prepare(bmp);
        if (small == null) return FALLBACK_COLOR;
        try {
            return pickDominant(small);
        } finally {
            if (small != bmp) small.recycle();
        }
    }

    /** 返回氛围底色：主色向黑色混 0.55 的压暗版，供整页底色。 */
    public static int ambientBackground(Bitmap bmp) {
        int dominant = dominantColor(bmp);
        return mixToBlack(dominant, AMBIENT_DARKEN_MIX);
    }

    /** 判断颜色是否偏亮（相对亮度口径）。 */
    public static boolean isBright(int color) {
        float r = Color.red(color) / 255f;
        float g = Color.green(color) / 255f;
        float b = Color.blue(color) / 255f;
        float luminance = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        return luminance >= BRIGHT_LUMINANCE;
    }

    private static Bitmap prepare(Bitmap bmp) {
        if (bmp == null || bmp.isRecycled()) return null;
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if (w <= 0 || h <= 0) return null;
        int max = Math.max(w, h);
        if (max <= MAX_EDGE) return bmp;
        float scale = MAX_EDGE / (float) max;
        int nw = Math.max(1, Math.round(w * scale));
        int nh = Math.max(1, Math.round(h * scale));
        try {
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true);
            return scaled == null ? bmp : scaled;
        } catch (RuntimeException e) {
            return bmp;
        }
    }

    private static int pickDominant(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);
        int lowerStartY = Math.round(h * LOWER_START_RATIO);

        // 量化直方图：key = (qr << 10) | (qg << 5) | qb，每通道 24 档映射到 0–31 的 5bit 槽位存桶。
        // 每桶累计加权票数与加权 RGB 和，最后取均值色参与打分。
        java.util.HashMap<Integer, Bucket> buckets = new java.util.HashMap<>();
        for (int y = 0; y < h; y++) {
            double weight = y >= lowerStartY ? LOWER_WEIGHT : 1.0d;
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int pixel = pixels[row + x];
                if (Color.alpha(pixel) < MIN_ALPHA) continue;
                int r = Color.red(pixel);
                int g = Color.green(pixel);
                int b = Color.blue(pixel);
                int key = (quantKey(r) << 10) | (quantKey(g) << 5) | quantKey(b);
                Bucket bucket = buckets.get(key);
                if (bucket == null) {
                    bucket = new Bucket();
                    buckets.put(key, bucket);
                }
                bucket.weight += weight;
                bucket.rSum += r * weight;
                bucket.gSum += g * weight;
                bucket.bSum += b * weight;
            }
        }
        if (buckets.isEmpty()) return FALLBACK_COLOR;

        int fallbackColor = FALLBACK_COLOR;
        double fallbackVotes = -1;
        float[] hsv = new float[3];
        // GZ 主路：先按单色打分，再按色相分桶汇总，取最高桶内最高分（CoverAmbientKt 函数 e/l）。
        double[] hueBucketTotals = new double[12];
        int[] hueBucketBestColor = new int[12];
        double[] hueBucketBestScore = new double[12];
        for (int i = 0; i < 12; i++) hueBucketBestScore[i] = -1;
        boolean anyCandidate = false;
        for (Bucket bucket : buckets.values()) {
            if (bucket.weight <= 0) continue;
            int r = clamp255(Math.round((float) (bucket.rSum / bucket.weight)));
            int g = clamp255(Math.round((float) (bucket.gSum / bucket.weight)));
            int b = clamp255(Math.round((float) (bucket.bSum / bucket.weight)));
            int color = Color.rgb(r, g, b);
            if (bucket.weight > fallbackVotes) {
                fallbackVotes = bucket.weight;
                fallbackColor = color;
            }
            Color.colorToHSV(color, hsv);
            float hue = hsv[0];
            float saturation = hsv[1];
            float value = hsv[2];
            if (value < MIN_VALUE || value > MAX_VALUE) continue;
            if (saturation < MIN_SATURATION) continue;
            // GZ 主路打分：计数 ×(S≥0.12?1:0.6) ×暖色相降权（暖色相且 S/V 落区间时 ×0.3）。
            double score = bucket.weight * (saturation >= SATURATION_FULL_THRESHOLD ? 1.0d : 0.6d);
            boolean warm = hue >= WARM_HUE_MIN && hue <= WARM_HUE_MAX
                    && saturation >= WARM_SAT_MIN && saturation <= WARM_SAT_MAX
                    && value >= WARM_VAL_MIN && value <= WARM_VAL_MAX;
            if (warm) score *= WARM_PENALTY;
            int hueBucket = hueBucketIndex(hue);
            hueBucketTotals[hueBucket] += score;
            anyCandidate = true;
            if (score > hueBucketBestScore[hueBucket]) {
                hueBucketBestScore[hueBucket] = score;
                hueBucketBestColor[hueBucket] = color;
            }
        }
        if (anyCandidate) {
            int bestBucket = -1;
            double bestTotal = -1;
            for (int i = 0; i < 12; i++) {
                if (hueBucketBestScore[i] < 0) continue;
                if (hueBucketTotals[i] > bestTotal) {
                    bestTotal = hueBucketTotals[i];
                    bestBucket = i;
                }
            }
            if (bestBucket >= 0) return hueBucketBestColor[bestBucket];
        }
        // 全部色桶都被 HSV 过滤掉时，退回票数最高的均值色而不是兜底灰（图本身有内容）。
        return fallbackColor;
    }

    /** GZ 色相分桶（CoverAmbientKt 函数 l）：((hue＋15)/30) 取整对 12 取模，每桶 30°、偏移 15°。 */
    private static int hueBucketIndex(float hueDegrees) {
        return (((int) ((hueDegrees + 15f) / 30f)) % 12 + 12) % 12;
    }

    /**
     * 主色可用化调整（GZ 函数 d，CoverAmbientKt:1101-1108，HSV 口径）：
     * S＜0.06 时 S 清零，否则 S＝clamp(S×1.05＋0.02, 0.16, 0.8)；V＝clamp(V, 0.38, 0.72)。色相与 alpha 不动。
     */
    public static int toneAdjust(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        if (hsv[1] < 0.06f) hsv[1] = 0f;
        else hsv[1] = clampFloat(hsv[1] * 1.05f + 0.02f, 0.16f, 0.8f);
        hsv[2] = clampFloat(hsv[2], 0.38f, 0.72f);
        return Color.HSVToColor(Color.alpha(color), hsv);
    }

    private static float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int quantKey(int channel) {
        int level = channel * QUANT_LEVELS / 256; // 0..23
        return level * 31 / (QUANT_LEVELS - 1); // 映射到 5bit 桶位 0..31，避免相邻档撞桶
    }

    private static int mixToBlack(int color, float mix) {
        float keep = 1f - mix;
        int r = clamp255(Math.round(Color.red(color) * keep));
        int g = clamp255(Math.round(Color.green(color) * keep));
        int b = clamp255(Math.round(Color.blue(color) * keep));
        return Color.rgb(r, g, b);
    }

    private static int clamp255(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static final class Bucket {
        double weight;
        double rSum;
        double gSum;
        double bSum;
    }
}
