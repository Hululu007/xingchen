package com.fongmi.android.tv.api.hk;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 海阔详情/子页短时缓存（5 分钟）。
 *
 * 原版 8.83 对应物：AutoCacheUtilKt.saveCache/getCache（file cache/pages/<md5>.json，
 * key = md5(￥￥￥ + 页面地址)，initAutoCacheMd5；无 TTL，靠 clearCache 限量）。
 * 星辰实现照原版的文件形态，口径按潇洒哥收紧：
 * key = md5(规则标题|￥￥￥地址)，目录按规则标题分（规则更新/删除时整目录失效）；
 * TTL 5 分钟（与详情预取槽对齐）；下拉刷新即作废单条；写失败/读损坏一律静默，不挡主流程。
 */
public final class HkDetailCache {

    /** 缓存有效期 5 分钟，与详情预取槽 TTL 对齐。 */
    public static final long TTL_MS = 5 * 60 * 1000L;

    private static final String DIR = "hk_detail_cache";
    private static final Gson GSON = new Gson();
    private static final Type ENTRY_TYPE = new TypeToken<Entry>() {
    }.getType();
    /**
     * items 数组的强类型读回口。
     * 2026-10-17 崩溃修法：Entry 里原来直接存 List<HkItem> 字段，R8 混淆后泛型签名被剥
     * （字段被收紧成裸 ArrayList），读回时元素全变成 LinkedTreeMap、绑定页强转崩。
     * 改成字符串中转后，这里是全链路唯一的泛型落点，用 TypeToken 匿名子类写法，
     * 本 App HkPageParser 等多处同款写法在 R8 下一直正常。
     */
    private static final Type ITEMS_TYPE = new TypeToken<List<HkItem>>() {
    }.getType();

    /** 命中结果：首屏条目 + 解析出的真实地址（新页 myUrl 用，与预取槽同口径）。 */
    public static final class Hit {
        public final List<HkItem> items;
        public final String parsedUrl;

        Hit(List<HkItem> items, String parsedUrl) {
            this.items = items;
            this.parsedUrl = parsedUrl == null ? "" : parsedUrl;
        }
    }

    /**
     * 落盘结构：写入时间 + 真实地址 + 条目的 JSON 字符串。
     * 2026-10-17 崩溃修法：原来直接存 List<HkItem> 字段，R8 剥签名后读回全变 LinkedTreeMap
     * 直接崩；现在条目以字符串中转，泛型落点只在 ITEMS_TYPE 一处，字符串字段本身无泛型可剥。
     * 旧版文件的 items 数组字段 Gson 读到这里时直接忽略（itemsJson 为 null），走删档自愈。
     */
    private static class Entry {
        long at;
        String parsedUrl;
        String itemsJson;
    }

    private HkDetailCache() {
    }

    /**
     * 缓存 key：md5(标题|￥￥￥地址)。
     * 原版 initAutoCacheMd5 同款：地址前拼 ￥￥￥（\uFFE5 三连）再 md5；
     * 星辰前面再加规则标题做命名空间（原版没有标题维度，规则更新/删除无法按规则失效）。
     */
    public static String keyOf(String ruleTitle, String pageUrl) {
        String u = pageUrl == null ? "" : pageUrl;
        return md5((ruleTitle == null ? "" : ruleTitle) + "|" + "￥￥￥" + u);
    }

    /**
     * 命中返回 Hit；缺失/过期/损坏返回 null（过期与损坏顺手删掉陈文件）。
     * 同步读，文件很小（详情首屏 JSON），调用方在 UI 线程调无妨。
     */
    public static synchronized Hit get(Context ctx, String ruleTitle, String pageUrl) {
        if (ctx == null || pageUrl == null || pageUrl.isEmpty()) return null;
        File f = fileOf(ctx, ruleTitle, keyOf(ruleTitle, pageUrl));
        try {
            if (!f.exists()) return null;
            long now = System.currentTimeMillis();
            if (now - f.lastModified() > TTL_MS) {
                f.delete();
                return null;
            }
            String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Entry e = GSON.fromJson(json, ENTRY_TYPE);
            if (e == null || e.itemsJson == null || e.itemsJson.isEmpty()) {
                // 旧版文件的 items 数组形态：itemsJson 为空，走删档自愈，永不把异形数据注入页面。
                f.delete();
                return null;
            }
            if (now - e.at > TTL_MS) {
                f.delete();
                return null;
            }
            // 强类型读回：元素若不是 HkItem（静默变质、fromJson 不抛异常），删档当未命中。
            List<HkItem> items = null;
            try {
                items = GSON.fromJson(e.itemsJson, ITEMS_TYPE);
            } catch (Exception ignored) {
            }
            if (items == null || items.isEmpty() || !allHkItems(items)) {
                f.delete();
                return null;
            }
            return new Hit(items, e.parsedUrl);
        } catch (Exception ex) {
            try {
                f.delete();
            } catch (Exception ignored) {
            }
            return null;
        }
    }

    /**
     * 异步写入（自起后台线程，不挡 UI；失败静默）。
     * 写完顺手清本规则目录下的过期文件，防止无限膨胀。
     */
    public static void put(Context ctx, String ruleTitle, String pageUrl,
                           List<HkItem> items, String parsedUrl) {
        if (ctx == null || pageUrl == null || pageUrl.isEmpty() || items == null || items.isEmpty()) return;
        final Context c = ctx.getApplicationContext();
        final String t = ruleTitle;
        final String u = pageUrl;
        final List<HkItem> copy = new ArrayList<>(items);
        final String pu = parsedUrl == null ? "" : parsedUrl;
        new Thread(() -> {
            try {
                synchronized (HkDetailCache.class) {
                    File f = fileOf(c, t, keyOf(t, u));
                    File dir = f.getParentFile();
                    if (dir != null && !dir.exists()) dir.mkdirs();
                    Entry e = new Entry();
                    e.at = System.currentTimeMillis();
                    e.parsedUrl = pu;
                    // toJson 用运行时真实类型序列化，安全；读回只走 ITEMS_TYPE 强类型口。
                    e.itemsJson = GSON.toJson(copy);
                    Files.write(f.toPath(), GSON.toJson(e).getBytes(StandardCharsets.UTF_8));
                    pruneLocked(c, t);
                }
            } catch (Exception ignored) {
            }
        }, "hk-detail-cache-put").start();
    }

    /** 该规则名下全部缓存失效（规则更新/删除时调用）。 */
    public static synchronized void invalidateRule(Context ctx, String ruleTitle) {
        if (ctx == null) return;
        try {
            deleteRecursively(dirOf(ctx, ruleTitle));
        } catch (Exception ignored) {
        }
    }

    /** 单条缓存失效（下拉刷新时调用）。 */
    public static synchronized void invalidateKey(Context ctx, String ruleTitle, String pageUrl) {
        if (ctx == null || pageUrl == null || pageUrl.isEmpty()) return;
        try {
            fileOf(ctx, ruleTitle, keyOf(ruleTitle, pageUrl)).delete();
        } catch (Exception ignored) {
        }
    }

    private static File dirOf(Context ctx, String ruleTitle) {
        File base = new File(ctx.getFilesDir(), DIR);
        return new File(base, md5(ruleTitle == null ? "" : ruleTitle));
    }

    private static File fileOf(Context ctx, String ruleTitle, String key) {
        return new File(dirOf(ctx, ruleTitle), key + ".json");
    }

    /** 类型自检：元素必须全是 HkItem，混进一个异形就整批判废（删档自愈）。 */
    private static boolean allHkItems(List<HkItem> items) {
        for (Object o : items) {
            if (!(o instanceof HkItem)) return false;
        }
        return true;
    }

    /** 只清本规则目录下的过期文件。 */
    private static void pruneLocked(Context ctx, String ruleTitle) {
        try {
            File[] fs = dirOf(ctx, ruleTitle).listFiles();
            if (fs == null) return;
            long now = System.currentTimeMillis();
            for (File f : fs) {
                if (f.isFile() && now - f.lastModified() > TTL_MS) f.delete();
            }
        } catch (Exception ignored) {
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.isDirectory() ? f.listFiles() : null;
        if (kids != null) for (File k : kids) deleteRecursively(k);
        f.delete();
    }

    private static String md5(String s) {
        try {
            MessageDigest d = MessageDigest.getInstance("MD5");
            byte[] b = d.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return "h" + Math.abs(s.hashCode());
        }
    }
}
