package com.fongmi.android.tv.emby;

import android.net.Uri;
import android.text.TextUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Emby 媒体请求的鉴权头补发（play-dead-diagnosis.md P0）。
 * 详情链（选集直接播/换画质/410 续播/AI 换集）走 PUSH 裸 URL，不进 EmbySpider.playerContent，
 * Result 里没有头 → 播放器媒体请求 0 自定义头 → 影桥前置 302→410。
 * 这里按直链 URL 的 host 找回归属的已登录服，用 EmbyApi.authHeader() 现成拼法补头，
 * 不另拼一套；找不到归属服返回 null（PUSH 的其它用途如磁力/txt 不受影响）。
 */
public final class EmbyMediaHeader {

    private EmbyMediaHeader() {}

    public static Map<String, String> forUrl(String url) {
        try {
            if (TextUtils.isEmpty(url) || !url.startsWith("http")) return null;
            String host = hostOf(url);
            if (host.isEmpty()) return null;
            for (EmbyStore.EmbyServer s : EmbyStore.servers()) {
                if (s == null || !s.isLogin() || TextUtils.isEmpty(s.token)) continue;
                if (host.equalsIgnoreCase(hostOf(s.baseUrl))) return headerOf(s);
                if (s.altUrls != null) {
                    for (String alt : s.altUrls) {
                        if (!TextUtils.isEmpty(alt) && host.equalsIgnoreCase(hostOf(alt))) return headerOf(s);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Map<String, String> headerOf(EmbyStore.EmbyServer s) {
        String h = new EmbyApi(s.baseUrl, s.token, s.userId, s.id).authHeader();
        Map<String, String> m = new LinkedHashMap<>();
        m.put("X-Emby-Authorization", h);
        return m;
    }

    /** host:port（端口不同算不同服，避免 8096/8920 串）。 */
    private static String hostOf(String u) {
        try {
            Uri uri = Uri.parse(u);
            String host = uri.getHost();
            if (host == null) return "";
            int port = uri.getPort();
            return port > 0 ? host.toLowerCase() + ":" + port : host.toLowerCase();
        } catch (Throwable t) {
            return "";
        }
    }
}
