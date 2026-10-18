package com.fongmi.android.tv.clash;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * 按源定向策略表：决定某个来源的流量走节点（本地混合口）还是直连。
 * <p>
 * 用户已拍板：默认全部直连；逐源单独开「走节点」。
 * <p>
 * key 分三类：
 * <ul>
 * <li>{@link #KIND_PY}：PY 点播源，按源标识（开工时与壳子侧确认标识口径，见 README 未决问题）；
 * <li>{@link #KIND_JS}：JS 点播源，按源标识；
 * <li>{@link #KIND_HK}：海阔小程序，按规则名；
 * <li>{@link #KIND_EMBY}：Emby 服务器，按服 id（EmbyStore 镜像一份，本表是框架决策用的唯一真相）。
 * <li>{@link #KIND_BOOK}：书源（Legado 书源仓库），按 bookSourceUrl；阅读取数（正文/图片/音频/缓存下载）用。
 * </ul>
 * 决策统一走 {@link #shouldProxy}：总开关（ClashSettings）关 → 一律直连；
 * 总开关开 → 查本表。
 */
public final class SourceProxyPolicy {

    public static final String KIND_VOD = "vod";
    public static final String KIND_PY = "py";
    public static final String KIND_JS = "js";
    public static final String KIND_HK = "hk";
    public static final String KIND_EMBY = "emby";
    /** 书源（Legado 书源仓库）：id=bookSourceUrl，与阅读打点一致。 */
    public static final String KIND_BOOK = "book";

    private static final String PREFS = "clash_source_policy";

    private SourceProxyPolicy() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(String kind, String id) {
        return kind + ":" + (id == null ? "" : id);
    }

    /** 某源是否开了「走节点」（默认关 = 直连）。 */
    public static boolean isEnabled(Context context, String kind, String id) {
        return prefs(context).getBoolean(key(kind, id), false);
    }

    /** 设置某源走节点 / 直连。 */
    public static void setEnabled(Context context, String kind, String id, boolean on) {
        prefs(context).edit().putBoolean(key(kind, id), on).apply();
    }

    /**
     * 壳子网络层决策入口：这个来源的请求要不要走本地节点口。
     * Emby：单服开了走节点即生效，不再要求节点总开关先开（用户口径：开了就生效）。
     * 其它源：维持原语义，总开关关 → 一律直连。
     */
    public static boolean shouldProxy(Context context, String kind, String id) {
        if (KIND_EMBY.equals(kind)) return isEnabled(context, kind, id);
        if (!ClashSettings.isEnabled(context)) return false;
        return isEnabled(context, kind, id);
    }

    /** 某 kind 下是否有任一源开了走节点（节点按需拉起、门控 enabled() 用）。 */
    public static boolean anyEnabled(Context context, String kind) {
        try {
            String prefix = kind + ":";
            java.util.Map<String, ?> all = prefs(context).getAll();
            if (all == null) return false;
            for (java.util.Map.Entry<String, ?> e : all.entrySet()) {
                if (e.getKey() != null && e.getKey().startsWith(prefix) && Boolean.TRUE.equals(e.getValue()))
                    return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 列出已开走节点的源（设置页展示用）。 */
    public static List<String> listEnabled(Context context) {
        List<String> out = new ArrayList<>();
        for (String k : prefs(context).getAll().keySet()) {
            Object v = prefs(context).getAll().get(k);
            if (Boolean.TRUE.equals(v)) out.add(k);
        }
        return out;
    }

    /** 清空全表（恢复默认全部直连）。 */
    public static void clearAll(Context context) {
        prefs(context).edit().clear().apply();
    }
}
