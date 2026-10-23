package com.xingchen.tv.theme;
import com.fongmi.android.tv.R;

import android.app.Activity;
import android.app.Application;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ThemeManager {
    // 壁纸缓存只存「图源」（解码后的位图 / 纯色值），不存 Drawable 本体：
    // 每个窗口贴图时按图源新建一份 Drawable，避免全 App 共用同一个
    // Drawable 实例时跨窗口 detach/回调互相污染（一处 detach 满屏黑）。
    private static android.graphics.Bitmap sCachedBitmap = null;
    private static Integer sCachedColor = null;
    private static String sCachedKey = "";
    // 上一张成功贴出的壁纸图源：真图拿不到时兜底用，永不落透明黑。
    private static android.graphics.Bitmap sLastBitmap = null;
    private static Integer sLastColor = null;
    // 连上一张都没有时的深色占位（深蓝黑，与阅读底色同族）。
    private static final int PLACEHOLDER_COLOR = 0xFF0E1622;

    private static ThemeManager instance;
    private XingChenTheme theme;
    private static final String TAG = "XC_WALLPAPER";
    private static void flog(String msg) {
        Log.d(TAG, msg);
        try {
            File dir = new File("/sdcard/XingChen");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "wallpaper.log");
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
            String line = sdf.format(new Date()) + " " + msg + "\n";
            FileWriter w = new FileWriter(f, true);
            w.write(line);
            w.close();
        } catch (IOException e) {
            Log.e(TAG, "flog failed", e);
        }
    }

    private ThemeManager() {}

    public static synchronized ThemeManager get() {
        if (instance == null) instance = new ThemeManager();
        return instance;
    }

    public static void init(Application app) {
        get().theme = XingChenTheme.load(app);
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityResumed(Activity a) { currentActivity = a; get().apply(a); }
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }

    public XingChenTheme getTheme() {
        return theme;
    }

    public void refresh(android.content.Context ctx) {
        theme = XingChenTheme.load(ctx);
    }

    public void setWallpaper(android.content.Context ctx, String type, String value) {
        theme.wallpaperType = type;
        theme.wallpaperValue = value;
        theme.save(ctx);
        // Invalidate wallpaper cache: local/url reuse fixed filenames, so the
        // cache key (type:value) would otherwise match the stale drawable.
        sCachedKey = "";
        sCachedBitmap = null;
        sCachedColor = null;
        applyAll();
    }

    private void applyAll() {
        try {
            if (currentActivity != null) apply(currentActivity);
        } catch (Exception e) { flog("ERROR applyAll failed: " + e); }
    }
    private static android.app.Activity currentActivity;

    public void setTheme(XingChenTheme theme) {
        this.theme = theme;
    }

    public void apply(Activity activity) {
        // 第一贴（同步）：命中缓存或有上一张图时先把窗口底贴上，不等 post。
        // 以前只靠 post 的异步一贴，那次失败被吞掉首页就持久黑；
        // 同步这步只贴已解码的缓存图，绝不在此新开大图解码（89MB 教训）。
        try {
            applyCachedNow(activity);
        } catch (Exception e) {
            Log.e(TAG, "sync wallpaper apply failed", e);
            flog("ERROR sync apply failed: " + e);
        }
        // 第二贴（异步）：全量校验/解码，在其后覆盖同步贴的结果。
        try {
            activity.getWindow().getDecorView().post(new Runnable() {
                @Override public void run() {
                    doApply(activity);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "async wallpaper apply failed", e);
            flog("ERROR apply failed: " + e);
        }
    }

    private boolean isHkPage(Activity activity) {
        String actName = activity.getClass().getName();
        return actName.endsWith(".ui.hk.HkListActivity")
                || actName.endsWith(".ui.hk.HkSearchActivity")
                || actName.endsWith(".ui.activity.HkPageActivity")
                || actName.endsWith(".ui.activity.HkRuleSearchActivity");
    }

    // 同步贴：只用已有图源（当前缓存优先，其次上一张），不解码。
    private void applyCachedNow(Activity activity) {
        if (activity == null || isHkPage(activity)) return;
        if (theme == null) theme = XingChenTheme.load(activity);
        android.graphics.drawable.Drawable dw = null;
        if (currentCacheKey().equals(sCachedKey)) dw = freshDrawableFromCache(activity);
        if (dw == null) dw = freshDrawableFromLast(activity);
        if (dw != null) {
            activity.getWindow().setBackgroundDrawable(dw);
            flog("applyCachedNow: sync-pasted for " + activity.getClass().getSimpleName());
        }
    }

    // 按当前缓存图源新建一份本窗口独立的 Drawable（位图本体可共享，状态不共享）。
    private android.graphics.drawable.Drawable freshDrawableFromCache(Activity activity) {
        if (sCachedColor != null) return new android.graphics.drawable.ColorDrawable(sCachedColor);
        if (sCachedBitmap != null && !sCachedBitmap.isRecycled()) {
            android.graphics.drawable.BitmapDrawable bd = new android.graphics.drawable.BitmapDrawable(activity.getResources(), sCachedBitmap);
            bd.setTintList(null);
            return bd;
        }
        return null;
    }

    private android.graphics.drawable.Drawable freshDrawableFromLast(Activity activity) {
        if (sLastColor != null) return new android.graphics.drawable.ColorDrawable(sLastColor);
        if (sLastBitmap != null && !sLastBitmap.isRecycled()) {
            android.graphics.drawable.BitmapDrawable bd = new android.graphics.drawable.BitmapDrawable(activity.getResources(), sLastBitmap);
            bd.setTintList(null);
            return bd;
        }
        return null;
    }

    // 兜底：上一张也没有就落深色占位，绝不落透明（透明＝黑）。
    private android.graphics.drawable.Drawable fallbackDrawable(Activity activity) {
        android.graphics.drawable.Drawable dw = freshDrawableFromLast(activity);
        if (dw != null) return dw;
        return new android.graphics.drawable.ColorDrawable(PLACEHOLDER_COLOR);
    }

    private String currentCacheKey() {
        String type = theme.wallpaperType;
        String value = theme.wallpaperValue;
        boolean blur = theme.wallpaperBlur;
        String cacheKey = type + ":" + value + (blur ? ":blur" : "");
        // Local/url wallpapers reuse fixed filenames; include lastModified so
        // picking a new image with the same path busts the cache.
        if ((XingChenTheme.WP_LOCAL.equals(type) || XingChenTheme.WP_URL.equals(type)) && value != null) {
            try {
                java.io.File f = new java.io.File(value);
                if (f.exists()) cacheKey += ":" + f.lastModified();
            } catch (Exception e) {}
        }
        return cacheKey;
    }

    private void doApply(Activity activity) {
        doApply(activity, false);
    }

    private void doApply(Activity activity, boolean isRetry) {
        try {
            flog("doApply: activity=" + activity.getClass().getSimpleName());
            // 海阔浅色页（列表页/搜索页/聚合页/规则搜索页）不跟主 App 主题走：不铺星辰壁纸，
            // 窗口底用动态背景顶色兜底；状态栏各页自己管（透明＋深色图标），这里不再强制不透明，
            // 否则会盖住 decor 注入的动态背景，状态栏区又和页面断成两截
            if (isHkPage(activity)) {
                try {
                    activity.getWindow().setBackgroundDrawable(
                            new android.graphics.drawable.ColorDrawable(activity.getColor(com.fongmi.android.tv.R.color.hk_status_bar)));
                } catch (Exception ignored) {}
                return;
            }
            if (theme == null) theme = XingChenTheme.load(activity);
            android.graphics.drawable.Drawable dw = obtainWallpaper(activity);
            boolean real = dw != null;
            // 真图拿不到也绝不落透明黑：上一张优先，没有就深色占位。
            if (!real) dw = fallbackDrawable(activity);
            flog("doApply: setting window background, real=" + real);
            try {
                activity.getWindow().setBackgroundDrawable(dw);
            } catch (Exception e) {
                Log.e(TAG, "setting window background failed", e);
                flog("ERROR setting window bg: " + e);
            }
            if (!real && !isRetry) {
                // 已落兜底，再约一次延迟重试；下一次 onActivityResumed 也会再走 apply。
                try {
                    activity.getWindow().getDecorView().postDelayed(new Runnable() {
                        @Override public void run() {
                            if (!activity.isFinishing() && !activity.isDestroyed()) doApply(activity, true);
                        }
                    }, 1500);
                    flog("doApply: wallpaper missing, retry scheduled");
                } catch (Exception e) {
                    Log.e(TAG, "schedule wallpaper retry failed", e);
                    flog("ERROR schedule retry: " + e);
                }
            }
            try {
                android.view.View decor = activity.getWindow().getDecorView();
                flog("DIAG DecorView: class=" + decor.getClass().getSimpleName() + ", bg=" + bgDesc(decor.getBackground()));
                if (decor instanceof android.view.ViewGroup) {
                    android.view.ViewGroup dg = (android.view.ViewGroup) decor;
                    for (int i = 0; i < dg.getChildCount() && i < 3; i++) {
                        android.view.View child = dg.getChildAt(i);
                        flog("DIAG Decor child " + i + ": class=" + child.getClass().getSimpleName() + ", bg=" + bgDesc(child.getBackground()));
                        if (child instanceof android.view.ViewGroup && ((android.view.ViewGroup) child).getChildCount() > 0) {
                            android.view.View grandchild = ((android.view.ViewGroup) child).getChildAt(0);
                            flog("DIAG   grandchild: class=" + grandchild.getClass().getSimpleName() + ", bg=" + bgDesc(grandchild.getBackground()));
                        }
                    }
                }
            } catch (Exception e) {
                flog("DIAG error: " + e);
            }
            // Clear opaque ColorDrawable cover views sitting directly in the DecorView
            // (e.g. a solid beige View that hides the window wallpaper). Only plain
            // Views are touched: ViewGroups (content) and ImageViews (wallpaper
            // layer) are left alone.
            try {
                android.view.View decor2 = activity.getWindow().getDecorView();
                if (decor2 instanceof android.view.ViewGroup) {
                    android.view.ViewGroup dg2 = (android.view.ViewGroup) decor2;
                    for (int i = 0; i < dg2.getChildCount(); i++) {
                        android.view.View child = dg2.getChildAt(i);
                        if (child == null) continue;
                        if (child instanceof android.view.ViewGroup) continue;
                        if (child instanceof android.widget.ImageView) continue;
                        if ("xc_wallpaper".equals(child.getTag())) continue;
                        android.graphics.drawable.Drawable bg = child.getBackground();
                        if (bg instanceof android.graphics.drawable.ColorDrawable) {
                            int color = ((android.graphics.drawable.ColorDrawable) bg).getColor();
                            if (android.graphics.Color.alpha(color) == 255) {
                                child.setBackground(null);
                                flog("Cleared opaque cover view: child " + i + " (" + child.getClass().getSimpleName() + ")");
                            }
                        }
                    }
                }
            } catch (Exception e) {
                flog("ERROR clearing cover views: " + e);
            }
        } catch (Exception e) { flog("ERROR doApply failed: " + e); }
    }

    // 拿到真壁纸时返回一份本窗口独立的 Drawable，并把图源存进缓存/上一张；
    // 拿不到（解码失败等）返回 null，由 doApply 落兜底并约重试。
    private android.graphics.drawable.Drawable obtainWallpaper(Activity activity) {
        try {
            String type = theme.wallpaperType;
            String value = theme.wallpaperValue;
            boolean blur = theme.wallpaperBlur;
            String cacheKey = currentCacheKey();
            // Check cache first - avoids re-decoding on every page switch (black flash fix)
            if (cacheKey.equals(sCachedKey)) {
                android.graphics.drawable.Drawable hit = freshDrawableFromCache(activity);
                if (hit != null) {
                    flog("obtainWallpaper: cache HIT for " + cacheKey);
                    return hit;
                }
            }
            flog("obtainWallpaper: cache MISS for " + cacheKey + ", decoding...");
            flog("obtainWallpaper: type=" + type + ", value=" + value);
            if (XingChenTheme.WP_COLOR.equals(type)) {
                try {
                    int color = android.graphics.Color.parseColor(value);
                    cacheColor(cacheKey, color);
                    return new android.graphics.drawable.ColorDrawable(color);
                } catch (Exception e) {
                    flog("ERROR color parse: " + e);
                    return null;
                }
            } else if (XingChenTheme.WP_LOCAL.equals(type) || XingChenTheme.WP_URL.equals(type)) {
                try {
                    android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(value);
                    if (bm != null) {
                        flog("obtainWallpaper: file bitmap loaded, " + bm.getWidth() + "x" + bm.getHeight());
                        if (blur) bm = WallpaperBlur.blurredWallpaper(bm);
                        cacheBitmap(cacheKey, bm);
                        return freshDrawableFromCache(activity);
                    } else {
                        flog("obtainWallpaper: file decode failed, fallback to builtin");
                    }
                } catch (Exception e) {
                    flog("ERROR file decode: " + e);
                }
            }
            boolean preBlurred = blur && "shanjian".equals(value);
            int id = getBuiltinRes(activity, value, blur);
            flog("obtainWallpaper: builtin id=" + id);
            if (id != 0) {
                try {
                    // Decode with sampling to avoid 89MB full-size bitmap (black flash fix)
                    android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                    opts.inJustDecodeBounds = true;
                    android.graphics.BitmapFactory.decodeResource(activity.getResources(), id, opts);
                    int screenW = activity.getResources().getDisplayMetrics().widthPixels;
                    int screenH = activity.getResources().getDisplayMetrics().heightPixels;
                    int sample = 1;
                    while ((opts.outWidth / sample) > screenW * 2 || (opts.outHeight / sample) > screenH * 2) {
                        sample *= 2;
                    }
                    opts.inJustDecodeBounds = false;
                    opts.inSampleSize = sample;
                    flog("obtainWallpaper: decoding with sample=" + sample + " for " + opts.outWidth + "x" + opts.outHeight);
                    android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeResource(activity.getResources(), id, opts);
                    if (bm != null) {
                        if (blur && !preBlurred) bm = WallpaperBlur.blurredWallpaper(bm);
                        flog("obtainWallpaper: builtin bitmap decoded, " + bm.getWidth() + "x" + bm.getHeight() + ", caching");
                        cacheBitmap(cacheKey, bm);
                        return freshDrawableFromCache(activity);
                    }
                } catch (Exception e) {
                    flog("ERROR builtin decode: " + e);
                }
                android.graphics.drawable.Drawable fallback = activity.getResources().getDrawable(id, null);
                if (fallback != null) {
                    android.graphics.Bitmap fbm = drawableToBitmap(activity, fallback);
                    if (fbm != null) {
                        if (blur && !preBlurred) fbm = WallpaperBlur.blurredWallpaper(fbm);
                        cacheBitmap(cacheKey, fbm);
                        return freshDrawableFromCache(activity);
                    }
                    // 转不成位图就直接用这一份（不进缓存），也好过落黑。
                    return fallback;
                }
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "obtainWallpaper failed", e);
            flog("ERROR obtainWallpaper: " + e);
            return null;
        }
    }

    private void cacheBitmap(String key, android.graphics.Bitmap bm) {
        sCachedBitmap = bm;
        sCachedColor = null;
        sCachedKey = key;
        sLastBitmap = bm;
        sLastColor = null;
    }

    private void cacheColor(String key, int color) {
        sCachedColor = color;
        sCachedBitmap = null;
        sCachedKey = key;
        sLastColor = color;
        sLastBitmap = null;
    }

    private android.graphics.Bitmap drawableToBitmap(Activity activity, android.graphics.drawable.Drawable d) {
        try {
            int w = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : activity.getResources().getDisplayMetrics().widthPixels;
            int h = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : activity.getResources().getDisplayMetrics().heightPixels;
            android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(bm);
            d.setBounds(0, 0, w, h);
            d.draw(c);
            return bm;
        } catch (Exception e) {
            flog("ERROR drawableToBitmap: " + e);
            return null;
        }
    }

    private int getBuiltinRes(android.content.Context ctx, String name, boolean blur) {
        try {
            if ("shanjian".equals(name)) {
                // Blur OFF -> clear original; Blur ON -> pre-blurred soft version (moderate).
                // Runtime blur is skipped for the pre-blurred resource (no double blur).
                return blur ? R.drawable.poster_shanjian_blur_soft : R.drawable.poster_shanjian;
            }
            return ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
        } catch (Exception e) {
            flog("ERROR getBuiltinRes failed for name: " + e);
            return 0;
        }
    }

    private void makeTransparent(ViewGroup root) {
        try {
            for (int i = 0; i < root.getChildCount(); i++) {
                View child = root.getChildAt(i);
                if ("xc_wallpaper".equals(child.getTag())) continue;
                String cls = child.getClass().getName();
                if (cls.contains("BottomNavigationView")) continue;
                if (child instanceof ViewGroup) {
                    ViewGroup vg = (ViewGroup) child;
                    try {
                        Drawable bg = vg.getBackground();
                        if (bg instanceof android.graphics.drawable.ColorDrawable) {
                            vg.setBackgroundColor(0x00000000);
                            flog("makeTransparent: cleared root bg in " + cls);
                        }
                    } catch (Exception e) {}
                    // Only go one level deep, do not touch nested cards
                }
            }
        } catch (Exception e) { flog("ERROR makeTransparent failed: " + e); }
    }

    private String bgDesc(android.graphics.drawable.Drawable bg) {
        if (bg == null) return "null";
        String cls = bg.getClass().getSimpleName();
        if (bg instanceof android.graphics.drawable.ColorDrawable) {
            int c = ((android.graphics.drawable.ColorDrawable) bg).getColor();
            return cls + "(color=#" + Integer.toHexString(c) + ")";
        }
        return cls;
    }
}