package com.fongmi.android.tv.api.hk;

import java.util.HashMap;
import java.util.Map;

/**
 * M3u8 广告清理工具（移植自 8.83）。
 *
 * <p>8.83 原文：{@code smali_classes2/com/example/hikerview/ui/video/util/M3u8ClearUtil.smali}
 * （2193 行）+ {@code model/M3u8ClearAdRule.smali}。
 *
 * <p>8.83 语义（JSEngine.proxyClearM3u8, JSEngine.smali:21401）：
 * <ol>
 *   <li>按规则 hosts 匹配 URL（hosts 为空则全匹配）；</li>
 *   <li>用 HttpRequestUtil.getResponseString(url, headers) 拉取 m3u8 内容；</li>
 *   <li>按 M3u8ClearAdRule 规则删除广告片段（#EXTINF 段）；</li>
 *   <li>返回清理后的内容；若为空则调用方返回原 URL。</li>
 * </ol>
 *
 * <p>当前状态：基础框架已建，完整 2193 行逻辑待搬运。
 * 现阶段实现：拉取 m3u8 内容并返回（不做广告片段删除），保证 API 可用不崩。
 * 完整版需搬运：规则匹配（containOrMatch）、片段删除、相对 URL 补全等。
 */
public class HkM3u8ClearUtil {

    private static final String TAG = "HkM3u8ClearUtil";

    /**
     * 获取清理后的 m3u8 内容（对应 8.83 M3u8ClearUtil.getProxyContent(String, Map)）。
     *
     * @param url     m3u8 地址
     * @param headers 请求头
     * @return 清理后的 m3u8 文本；失败返回 ""（调用方按 8.83 语义返回原 URL）
     */
    public static String getProxyContent(String url, Map<String, String> headers) {
        return getProxyContent(url, headers, null);
    }

    /**
     * 获取清理后的 m3u8 内容（对应 8.83 M3u8ClearUtil.getProxyContent(String, Map, List)）。
     *
     * @param url     m3u8 地址
     * @param headers 请求头
     * @param rules   广告规则列表（暂未使用，待完整移植）
     * @return 清理后的 m3u8 文本；失败返回 ""
     */
    public static String getProxyContent(String url, Map<String, String> headers, java.util.List<?> rules) {
        if (url == null || url.isEmpty()) return "";
        try {
            // 基础版：直接拉取内容返回（8.83 会按规则删除广告片段，待完整移植）
            String content = fetchContent(url, headers);
            if (content == null || content.isEmpty()) return "";
            if (!content.contains("#EXTM3U")) return ""; // 非 m3u8 内容，按 8.83 语义返回空
            return content;
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 拉取 URL 文本内容（对应 8.83 HttpRequestUtil.getResponseString）。
     */
    private static String fetchContent(String url, Map<String, String> headers) {
        java.net.HttpURLConnection conn = null;
        try {
            java.net.URL u = new java.net.URL(url);
            conn = (java.net.HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        conn.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return "";
            java.io.InputStream is = conn.getInputStream();
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            is.close();
            return baos.toString("UTF-8");
        } catch (Throwable e) {
            return "";
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }
}
