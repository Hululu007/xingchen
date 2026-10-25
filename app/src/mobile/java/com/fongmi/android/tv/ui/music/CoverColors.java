package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Shader;
import android.os.Build;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.palette.graphics.Palette;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 封面取色全链（照瓜子 CoverAmbientKt 逐项复刻，定谳见 gz-smali-missing-dig.md 深夜节）。
 * 单色链：128×128 位图 → o() 选色 → d() 后处理 → cache-d（m(url) 读）。
 * 三色链：e() 主提取经 b() 压暗得 ambient、f() 顶部带得 statusBar、顶部 26% 带亮度得 topIsLight → cache-e（n(url) 读）。
 * 位图加载走 Glide override(128,128)，等价瓜子 Coil 的 128 尺寸请求。
 */
public final class CoverColors {

    /** 瓜子 wk1 = CoverExtractedColors(ambient, statusBar, topIsLight)。 */
    public static final class Extracted {
        public final int ambient;
        public final int statusBar;
        public final boolean topIsLight;

        Extracted(int ambient, int statusBar, boolean topIsLight) {
            this.ambient = ambient;
            this.statusBar = statusBar;
            this.topIsLight = topIsLight;
        }
    }

    public interface Callback {
        void onColors(@Nullable Integer single, @Nullable Extracted extracted);
    }

    private static final int FALLBACK = 0xFF1C1C1E;
    private static final LruCache<String, Integer> CACHE_SINGLE = new LruCache<>(64);
    private static final LruCache<String, Extracted> CACHE_EXTRACTED = new LruCache<>(64);
    private static Boolean blurSupport;

    private CoverColors() {
    }

    public static int fallback() {
        return FALLBACK;
    }

    @Nullable
    public static Integer single(String url) {
        if (url == null || url.isEmpty()) return null;
        return CACHE_SINGLE.get(url);
    }

    @Nullable
    public static Extracted extracted(String url) {
        if (url == null || url.isEmpty()) return null;
        return CACHE_EXTRACTED.get(url);
    }

    /** il0.l0.p()「BlurSupport」：API 31+ 试建 RenderEffect 成功才算可用；结果缓存。他机 Android 16 恒 true。 */
    public static boolean blurSupported() {
        if (blurSupport != null) return blurSupport;
        boolean ok = false;
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                android.graphics.RenderEffect.createBlurEffect(1f, 1f, Shader.TileMode.CLAMP);
                ok = true;
            } catch (Throwable ignored) {
            }
        }
        blurSupport = ok;
        return ok;
    }

    public static void load(Context context, final String url, final Callback callback) {
        if (url == null || url.isEmpty()) {
            callback.onColors(null, null);
            return;
        }
        Integer s = CACHE_SINGLE.get(url);
        Extracted e = CACHE_EXTRACTED.get(url);
        if (s != null && e != null) {
            callback.onColors(s, e);
            return;
        }
        Glide.with(context.getApplicationContext()).asBitmap().load(url).override(128, 128)
                .into(new CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                        Bitmap bmp = resource.getConfig() == Bitmap.Config.HARDWARE
                                ? resource.copy(Bitmap.Config.ARGB_8888, false) : resource;
                        Extracted ext = buildExtracted(bmp);
                        Integer single = buildSingle(bmp);
                        if (single == null && ext != null) single = singleFromOctave(bmp);
                        if (single != null) CACHE_SINGLE.put(url, single);
                        if (ext != null) CACHE_EXTRACTED.put(url, ext);
                        callback.onColors(single, ext);
                    }

                    @Override
                    public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {
                    }

                    @Override
                    public void onLoadFailed(@Nullable android.graphics.drawable.Drawable errorDrawable) {
                        callback.onColors(CACHE_SINGLE.get(url), CACHE_EXTRACTED.get(url));
                    }
                });
    }

    // ===== 单色链：d(o()) =====

    @Nullable
    static Integer buildSingle(Bitmap bmp) {
        Palette.Swatch sw = selectOctave(bmp);
        if (sw == null) return null;
        return d(sw.getRgb());
    }

    @Nullable
    static Integer singleFromOctave(Bitmap bmp) {
        Palette.Swatch sw = selectOctave(bmp);
        return sw == null ? null : d(sw.getRgb());
    }

    /** o()：24 色无过滤 → 明度 [.05,.95] 子集 L1 → sat≥.06 子集 L2（人口≥L1×.12）走色相桶选色；不成回 L1 最大人口；再空回 dominant。 */
    @Nullable
    static Palette.Swatch selectOctave(Bitmap bmp) {
        Palette palette = Palette.from(bmp).maximumColorCount(24).clearFilters().generate();
        List<Palette.Swatch> all = palette.getSwatches();
        if (all.isEmpty()) return null;
        List<Palette.Swatch> l1 = new ArrayList<>();
        for (Palette.Swatch sw : all) {
            float v = sw.getHsl()[2];
            if (v >= 0.05f && v <= 0.95f) l1.add(sw);
        }
        if (!l1.isEmpty()) {
            int pop1 = 0;
            for (Palette.Swatch sw : l1) pop1 += sw.getPopulation();
            if (pop1 < 1) pop1 = 1;
            List<Palette.Swatch> l2 = new ArrayList<>();
            int pop2 = 0;
            for (Palette.Swatch sw : l1) {
                if (sw.getHsl()[1] >= 0.06f) {
                    l2.add(sw);
                    pop2 += sw.getPopulation();
                }
            }
            if (!l2.isEmpty() && pop2 >= pop1 * 0.12f) {
                Map<Integer, Float> buckets = new HashMap<>();
                for (Palette.Swatch sw : l2) {
                    float[] hsl = sw.getHsl();
                    float w1 = Math.min(hsl[1], 0.6f) * 0.53f + 0.68f;
                    if (w1 > 1f) w1 = 1f;
                    boolean out = hsl[0] < 12f || hsl[0] > 58f || hsl[1] < 0.1f || hsl[1] > 0.58f
                            || hsl[2] < 0.16f || hsl[2] > 0.84f;
                    float w2 = out ? 1.0f : 0.78f;
                    int bucket = hueBucket(hsl[0]);
                    Float cur = buckets.get(bucket);
                    buckets.put(bucket, (cur == null ? 0f : cur) + sw.getPopulation() * w1 * w2);
                }
                Integer bestBucket = null;
                float bestScore = 0;
                for (Map.Entry<Integer, Float> en : buckets.entrySet()) {
                    if (bestBucket == null || en.getValue() > bestScore) {
                        bestBucket = en.getKey();
                        bestScore = en.getValue();
                    }
                }
                if (bestBucket != null) {
                    Palette.Swatch best = null;
                    float bestSel = 0;
                    for (Palette.Swatch sw : l2) {
                        if (hueBucket(sw.getHsl()[0]) != bestBucket) continue;
                        float[] hsl = sw.getHsl();
                        float lightFactor = (hsl[2] >= 0.22f && hsl[2] <= 0.78f) ? 1f
                                : (hsl[2] >= 0.12f && hsl[2] <= 0.88f) ? 0.82f : 0.55f;
                        float sel = (Math.min(hsl[1], 1f) * 0.12f + 0.88f) * sw.getPopulation() * lightFactor;
                        if (best == null || sel > bestSel) {
                            best = sw;
                            bestSel = sel;
                        }
                    }
                    if (best != null) return best;
                }
            }
            Palette.Swatch max = null;
            for (Palette.Swatch sw : l1) {
                if (max == null || sw.getPopulation() > max.getPopulation()) max = sw;
            }
            if (max != null) return max;
        }
        return palette.getDominantSwatch();
    }

    static int hueBucket(float hue) {
        return (((int) ((hue + 15f) / 30f)) % 12 + 12) % 12;
    }

    /** d()：sat<.06→0，余 clamp(sat×1.05+.02,.16,.8)；明度（HSV value）clamp [.38,.72]。 */
    public static int d(int rgb) {
        float[] hsv = new float[3];
        Color.colorToHSV(rgb, hsv);
        hsv[1] = hsv[1] < 0.06f ? 0f : clamp(hsv[1] * 1.05f + 0.02f, 0.16f, 0.8f);
        hsv[2] = clamp(hsv[2], 0.38f, 0.72f);
        return Color.HSVToColor(hsv);
    }

    /** s(Δ，色)：HSV 明度 +Δ，钳 [.1,.68]。 */
    public static int shiftLight(float delta, int rgb) {
        float[] hsv = new float[3];
        Color.colorToHSV(rgb, hsv);
        hsv[2] = clamp(hsv[2] + delta, 0.1f, 0.68f);
        return Color.HSVToColor(hsv);
    }

    /** r（色， 亮版？)：HSV 钳位——亮版 sat∈[.38,.88]／val∈[.62,.92]；暗版 sat∈[.48,.9]／val∈[.36,.56]。 */
    public static int toneClamp(int rgb, boolean light) {
        float[] hsv = new float[3];
        Color.colorToHSV(rgb, hsv);
        if (light) {
            hsv[1] = clamp(hsv[1], 0.38f, 0.88f);
            hsv[2] = clamp(hsv[2], 0.62f, 0.92f);
        } else {
            hsv[1] = clamp(hsv[1], 0.48f, 0.9f);
            hsv[2] = clamp(hsv[2], 0.36f, 0.56f);
        }
        return Color.HSVToColor(hsv);
    }

    // ===== 三色链：mk1 =====

    @Nullable
    static Extracted buildExtracted(Bitmap bmp) {
        Palette.Swatch main = selectExtractedMain(bmp);
        if (main == null) return null;
        int base = main.getRgb();
        float[] hsv = new float[3];
        Color.colorToHSV(base, hsv);
        Integer alt = null;
        if (hsv[1] < 0.1f) {
            Palette.Swatch o = selectOctave(bmp);
            if (o != null) {
                float[] altHsv = new float[3];
                Color.colorToHSV(o.getRgb(), altHsv);
                if (altHsv[1] >= 0.15f) alt = o.getRgb();
            }
        }
        int ambient = b(base, alt);
        Palette.Swatch top = selectTopBand(bmp);
        int statusBar = top != null ? top.getRgb() : base;
        boolean topLight = topBandIsLight(bmp);
        return new Extracted(ambient, statusBar, topLight);
    }

    /** b（色， alt)：低饱和借 alt 色相；sat 分段重映射；明度 × 因子（≥.75→.40／≥.55→.48／≥.35→.58／余 .78）钳 [.18,.5]。因子链方向经 smali 亲验。 */
    public static int b(int rgb, @Nullable Integer alt) {
        float[] hsv = new float[3];
        Color.colorToHSV(rgb, hsv);
        float hue = hsv[0];
        float sat = hsv[1];
        float val = hsv[2];
        boolean borrowed = false;
        if (sat < 0.1f && alt != null) {
            float[] altHsv = new float[3];
            Color.colorToHSV(alt, altHsv);
            if (altHsv[1] >= 0.15f) {
                hue = altHsv[0];
                borrowed = true;
            }
        }
        float newSat;
        if (!borrowed) {
            newSat = clamp(sat * 1.32f + 0.1f, 0.28f, 0.9f);
        } else if (sat < 0.08f) {
            newSat = clamp(sat, 0f, 0.16f);
        } else if (sat < 0.18f) {
            newSat = clamp(sat * 1.35f + 0.05f, 0.12f, 0.45f);
        } else {
            newSat = clamp(sat * 1.32f + 0.05f, 0.22f, 0.9f);
        }
        float factor = val >= 0.75f ? 0.4f : val >= 0.55f ? 0.48f : val >= 0.35f ? 0.58f : 0.78f;
        float newVal = clamp(val * factor, 0.18f, 0.5f);
        return Color.HSVToColor(new float[]{hue, newSat, newVal});
    }

    /** e()：全图（权重 1.0）＋下半区 y∈[.45h,h]（权重 1.35）双 Palette 按 rgb 合并 → 明度 [.06,.92]、sat≥.1 → 色相桶（肤色区 ×.3 惩罚）→ 桶内按 t() 选。 */
    @Nullable
    static Palette.Swatch selectExtractedMain(Bitmap bmp) {
        int w = Math.max(1, bmp.getWidth());
        int h = Math.max(1, bmp.getHeight());
        Palette full = Palette.from(bmp).maximumColorCount(24).clearFilters().generate();
        int bandTop = Math.min(h - 1, Math.max(0, (int) (h * 0.45f)));
        Palette bottom = Palette.from(bmp, new android.graphics.Rect(0, bandTop, w, h))
                .maximumColorCount(24).clearFilters().generate();
        Map<Integer, float[]> merged = new HashMap<>();
        Map<Integer, Palette.Swatch> byRgb = new HashMap<>();
        mergeInto(merged, byRgb, full, 1.0f);
        mergeInto(merged, byRgb, bottom, 1.35f);
        List<Palette.Swatch> candidates = new ArrayList<>();
        for (Map.Entry<Integer, float[]> en : merged.entrySet()) {
            Palette.Swatch sw = byRgb.get(en.getKey());
            float[] hsl = sw.getHsl();
            if (hsl[2] >= 0.06f && hsl[2] <= 0.92f && hsl[1] >= 0.1f) candidates.add(sw);
        }
        if (candidates.isEmpty()) return full.getDominantSwatch();
        Map<Integer, Float> buckets = new HashMap<>();
        for (Palette.Swatch sw : candidates) {
            float[] hsl = sw.getHsl();
            float weight = merged.get(sw.getRgb())[0];
            float score = weight * (hsl[1] >= 0.12f ? 1f : 0.6f)
                    * ((hsl[0] >= 5f && hsl[0] <= 50f && hsl[1] >= 0.12f && hsl[1] <= 0.72f
                    && hsl[2] >= 0.25f && hsl[2] <= 0.82f) ? 0.3f : 1.0f);
            int bucket = hueBucket(hsl[0]);
            Float cur = buckets.get(bucket);
            buckets.put(bucket, (cur == null ? 0f : cur) + score);
        }
        Integer bestBucket = null;
        float bestScore = 0;
        for (Map.Entry<Integer, Float> en : buckets.entrySet()) {
            if (bestBucket == null || en.getValue() > bestScore) {
                bestBucket = en.getKey();
                bestScore = en.getValue();
            }
        }
        if (bestBucket == null) return full.getDominantSwatch();
        Palette.Swatch best = null;
        float bestT = 0;
        for (Palette.Swatch sw : candidates) {
            if (hueBucket(sw.getHsl()[0]) != bestBucket) continue;
            float t = scoreT(sw, merged.get(sw.getRgb())[0]);
            if (best == null || t > bestT) {
                best = sw;
                bestT = t;
            }
        }
        return best != null ? best : full.getDominantSwatch();
    }

    private static void mergeInto(Map<Integer, float[]> merged, Map<Integer, Palette.Swatch> byRgb,
                                  Palette palette, float weightFactor) {
        for (Palette.Swatch sw : palette.getSwatches()) {
            float[] cur = merged.get(sw.getRgb());
            float add = sw.getPopulation() * weightFactor;
            if (cur == null) {
                merged.put(sw.getRgb(), new float[]{add});
                byRgb.put(sw.getRgb(), sw);
            } else {
                cur[0] += add;
            }
        }
    }

    /** t()：(log(pop+1)×.15+1)×合并权重 × sat 因子 × 明度因子（HSL）。 */
    static float scoreT(Palette.Swatch sw, float mergedWeight) {
        float[] hsl = sw.getHsl();
        float satFactor = (hsl[1] >= 0.12f && hsl[1] <= 0.55f) ? 1.25f
                : (hsl[1] > 0.55f && hsl[1] <= 0.85f) ? 1.1f : 0.9f;
        float lightFactor;
        if (hsl[2] >= 0.25f && hsl[2] <= 0.72f) lightFactor = 1.2f;
        else if (hsl[2] < 0.15f || hsl[2] > 0.85f) lightFactor = 0.55f;
        else lightFactor = 1.0f;
        return (float) ((Math.log(sw.getPopulation() + 1.0) * 0.15 + 1.0) * mergedWeight) * satFactor * lightFactor;
    }

    /** f()：顶部 28% 带 16 色 → dominant → vibrant → muted → 最大人口（瓜子目标键序 p48.e/h 按此等价，dominant 存在时不触发）。 */
    @Nullable
    static Palette.Swatch selectTopBand(Bitmap bmp) {
        int w = Math.max(1, bmp.getWidth());
        int h = Math.max(1, bmp.getHeight());
        int bandH = Math.max(1, Math.min(h, (int) (h * 0.28f)));
        Palette palette = Palette.from(bmp, new android.graphics.Rect(0, 0, w, bandH))
                .maximumColorCount(16).clearFilters().generate();
        Palette.Swatch sw = palette.getDominantSwatch();
        if (sw != null) return sw;
        sw = palette.getVibrantSwatch();
        if (sw != null) return sw;
        sw = palette.getMutedSwatch();
        if (sw != null) return sw;
        Palette.Swatch max = null;
        for (Palette.Swatch s : palette.getSwatches()) {
            if (max == null || s.getPopulation() > max.getPopulation()) max = s;
        }
        return max;
    }

    /** topIsLight：顶部 26% 带网格采样（x 步 宽/40、y 步 带高/12）Rec709 亮度均值 <.55。 */
    static boolean topBandIsLight(Bitmap bmp) {
        int w = Math.max(1, bmp.getWidth());
        int h = Math.max(1, bmp.getHeight());
        int bandH = Math.max(1, Math.min(h, (int) (h * 0.26f)));
        int stepX = Math.max(1, w / 40);
        int stepY = Math.max(1, bandH / 12);
        double sum = 0;
        int count = 0;
        for (int y = 0; y < bandH; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int px = bmp.getPixel(x, y);
                sum += 0.2126 * (Color.red(px) / 255.0) + 0.7152 * (Color.green(px) / 255.0) + 0.0722 * (Color.blue(px) / 255.0);
                count++;
            }
        }
        float avg = count == 0 ? 0.2f : (float) (sum / count);
        return avg < 0.55f;
    }

    static float clamp(float v, float min, float max) {
        return v < min ? min : Math.min(v, max);
    }
}
