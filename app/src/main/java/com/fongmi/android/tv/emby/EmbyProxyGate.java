package com.fongmi.android.tv.emby;

import android.content.Context;
import android.text.TextUtils;

import com.fongmi.android.tv.clash.ClashSettings;
import com.fongmi.android.tv.clash.MihomoManager;
import com.fongmi.android.tv.clash.SourceProxyPolicy;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Emby 按 host 认领走节点归属。
 * <p>
 * 背景：Emby 播放流（Exo 取流线程）、海报图（Glide 线程）没有线程打点，
 * 只开单服「走节点」时选路认不出它们，只能直连。选择器按请求 host 反查
 * EmbyStore 里开了走节点的服，命中即归属该服。
 * <p>
 * 线程安全：认领表是不可变快照，volatile 发布；EmbyStore 写操作后失效重建。
 */
public final class EmbyProxyGate {

    private EmbyProxyGate() {
    }

    /** host（小写）→ serverId，只收「走节点开」的服。 */
    private static volatile Map<String, String> CLAIM_CACHE = null;

    /** EmbyStore 写操作后调用，认领表下次重建。 */
    public static void invalidate() {
        CLAIM_CACHE = null;
    }

    private static Map<String, String> claimMap() {
        Map<String, String> cached = CLAIM_CACHE;
        if (cached != null) return cached;
        Map<String, String> map = new HashMap<>();
        try {
            for (EmbyStore.EmbyServer s : EmbyStore.servers()) {
                if (s == null || TextUtils.isEmpty(s.id) || !s.proxyEnabled) continue;
                putHost(map, s.baseUrl, s.id);
                if (s.altUrls != null) for (String alt : s.altUrls) putHost(map, alt, s.id);
            }
        } catch (Throwable ignored) {
        }
        Map<String, String> frozen = Collections.unmodifiableMap(map);
        CLAIM_CACHE = frozen;
        return frozen;
    }

    private static void putHost(Map<String, String> map, String url, String serverId) {
        String host = hostOfUrl(url);
        if (!host.isEmpty() && !map.containsKey(host)) map.put(host, serverId);
    }

    /** URL 取 host：小写、无端口；取不到返回 ""。 */
    public static String hostOfUrl(String url) {
        try {
            if (TextUtils.isEmpty(url)) return "";
            String host = new URI(url.trim()).getHost();
            return host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** host 命中开了走节点的服 → serverId；无则 null。 */
    public static String serverIdForHost(String host) {
        if (TextUtils.isEmpty(host)) return null;
        return claimMap().get(host.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * SourceGate.HostClaim 实现用：host 命中且本服应走节点 → "emby|serverId"。
     * 内核没跑时按需拉起（Emby 口径：开了就生效，不要求总开关先开）。
     * select() 是热路径：host 不命中时只读一次缓存表，不碰内核、不读盘。
     */
    public static String claim(String host, Context app) {
        try {
            String serverId = serverIdForHost(host);
            if (serverId == null) return null;
            if (app != null && !MihomoManager.get().isRunning()) {
                if (!MihomoManager.get().ensureRunning(app)) return null;
            }
            return SourceProxyPolicy.KIND_EMBY + "|" + serverId;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * MPV 用：host 命中的服应走节点 → 本地混合口 URL（与 gate 同一口）；
     * 否则 null。**不做内核拉起**：拉起是 EmbyApi（接口线程）的事，
     * 播放器 load 路径可能在主线程，不能阻塞等；正常流程点播前必经过
     * EmbySpider.playerContent（后台线程，已拉起），内核已在跑。
     */
    public static String proxyUrlForHost(String host, Context app) {
        try {
            if (app == null || TextUtils.isEmpty(host)) return null;
            if (serverIdForHost(host) == null) return null;
            if (!MihomoManager.get().isRunning()) return null;
            return ClashSettings.localProxyUrl(app.getApplicationContext());
        } catch (Throwable ignored) {
            return null;
        }
    }
}
