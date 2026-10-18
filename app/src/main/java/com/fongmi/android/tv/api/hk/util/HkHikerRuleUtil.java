package com.fongmi.android.tv.api.hk.util;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.hk.HkFavoriteStore;
import com.fongmi.android.tv.api.hk.HkParserKit;
import com.fongmi.android.tv.api.hk.HkRule;
import com.fongmi.android.tv.api.hk.HkRuleManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 移植自 8.83 {@code com.example.hikerview.service.http.HikerRuleUtil}（smali 1042 行）。
 * <p>
 * 8.83 原文：
 * <ul>
 *   <li>{@code getAssetsFileByHiker(url)}：{@code hiker://assets/} 开头时，按后缀返回内置 asset 文本
 *      （home.js / beautify.js / crypto-java.js / wasm.js / translate/ / localmanager.txt），
 *       非 hiker://assets/ 或空返回 ""。</li>
 *   <li>{@code getRulesByHiker(url)}：{@code hiker://home@<标题>} → 按标题查规则库（LitePal）返回规则 JSON；
 *       另有异步版本（listener 回调）。星辰用 {@link HkRuleManager#loadRuleJson(String)} 同款语义。</li>
 * </ul>
 */
public final class HkHikerRuleUtil {

    private HkHikerRuleUtil() {
    }

    /**
     * 8.83 HikerRuleUtil.getAssetsFileByHiker(String) 语义移植（smali 16-173）。
     *
     * @param url 形如 hiker://assets/home.js
     * @return asset 文本内容；非 hiker://assets/ 或空返回 ""
     */
    public static String getAssetsFileByHiker(String url) {
        if (TextUtils.isEmpty(url)) return "";
        final String prefix = "hiker://assets/";
        if (!url.startsWith(prefix)) return "";
        String p0 = url.replace(prefix, "");
        // 8.83 白名单：home.js / beautify.js / crypto-java.js / wasm.js / translate/ 前缀 / localmanager.txt
        boolean allowed = "home.js".equals(p0)
                || "beautify.js".equals(p0)
                || "crypto-java.js".equals(p0)
                || "wasm.js".equals(p0)
                || p0.startsWith("translate/")
                || "localmanager.txt".equals(p0);
        if (!allowed) return "";
        String code = loadAsset(p0);
        return code == null ? "" : code;
    }

    /**
     * 8.83 HikerRuleUtil.getRulesByHiker(String) 同步版语义移植（smali 174-590）。
     *
     * @param url 形如 hiker://home@&lt;规则标题&gt;
     * @return 规则 JSON；查不到或格式不对返回 ""
     */
    public static String getRulesByHiker(String url) {
        if (TextUtils.isEmpty(url) || !url.startsWith("hiker://")) return "";
        String p0 = url.replace("hiker://", "");
        if (p0.startsWith("home")) {
            // 8.83：split("@") 取 parts[1] 为标题查库；无标题返回全部规则（已排序）
            String[] parts = p0.split("@");
            if (parts.length > 1) {
                String json = HkRuleManager.get().loadRuleJson(parts[1]);
                return json == null ? "" : json;
            }
            return allRulesJson();
        }
        if (p0.startsWith("collection")) {
            // 对齐 8.83：返回收藏库记录 JSON（原版 home.js 收藏卡片用 picUrl/length）
            return HkFavoriteStore.toJson();
        }
        if (p0.startsWith("bookmark") || p0.startsWith("download") || p0.startsWith("history")) {
            // 8.83 返回对应库（书签/下载记录/历史）的记录 JSON；
            // 星辰无 hk 侧对应存储，等价于空库返回 []
            return "[]";
        }
        return "";
    }

    /**
     * 8.83 HikerRuleUtil.getRulesByHiker(String, OnCodeGetListener) 异步版语义移植（smali 591-848）。
     * 分支顺序与原文一致：empty → page → home → bookmark/download/collection/history → ""。
     */
    public static void getRulesByHiker(String url, HkCodeUtil.OnCodeGetListener listener) {
        try {
            if (TextUtils.isEmpty(url) || !url.startsWith("hiker://")) {
                listener.onFailure(404, url + "格式有误");
                return;
            }
            String p0 = url.replace("hiker://", "");
            if (p0.startsWith("empty")) {
                listener.onSuccess(HkParserKit.replaceOnce(p0, "empty", ""));
                return;
            }
            if (p0.startsWith("page")) {
                listener.onSuccess(p0);
                return;
            }
            listener.onSuccess(getRulesByHiker(url));
        } catch (Exception e) {
            listener.onFailure(500, "获取数据失败：" + e.getMessage());
        }
    }

    /** 全部已安装规则的原始 JSON 数组（对应 8.83 home 无标题时 findAll + sort + share 过滤序列化）。 */
    private static String allRulesJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        try {
            for (HkRule r : HkRuleManager.get().getRules()) {
                String json = HkRuleManager.get().loadRuleJson(r.getTitle());
                if (json == null) continue;
                if (!first) sb.append(",");
                sb.append(json);
                first = false;
            }
        } catch (Exception ignored) {
        }
        return sb.append("]").toString();
    }

    private static String loadAsset(String assetPath) {
        try {
            InputStream is = App.get().getAssets().open(assetPath);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            String code = bos.toString("UTF-8");
            return TextUtils.isEmpty(code) ? null : code;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
