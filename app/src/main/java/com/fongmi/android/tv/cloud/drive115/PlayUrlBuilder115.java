package com.fongmi.android.tv.cloud.drive115;

import android.text.TextUtils;
import android.util.Log;

import com.fongmi.android.tv.cloud.CloudFile;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.OkHttpClient;

/**
 * 115 播放地址构造器（对照 PY _player_content + _play_candidates + _play_result）
 *
 * 核心原则：播放器永远不直连 115，所有地址都包成�机代理地址。
 * 返回的 playUrl 为空字符串（对照 PY {"parse":0,"playUrl":"","url":p}）。
 */
public class PlayUrlBuilder115 {

    private static final String TAG = "PlayUrl115";
    private static final long URL_CACHE_MS = 4 * 60 * 1000; // 直链缓存 4 分钟
    private static final long REPEAT_FORCE_MS = 90 * 1000; // 90秒内重复点同一文件强制刷新

    private final Drive115 drive;
    private final OkHttpClient client;
    private final CookieJar115 cookieJar;

    /** pickcode -> {time, url} 直链缓存 */
    private final Map<String, CacheEntry> urlCache = new ConcurrentHashMap<>();
    /** 上次播放的 pickcode 和时间（90秒内重复强制刷新） */
    private String lastPc = "";
    private long lastPlayTime = 0;

    private static class CacheEntry {
        final long time;
        final String url;

        CacheEntry(long time, String url) {
            this.time = time;
            this.url = url;
        }
    }

    public PlayUrlBuilder115(Drive115 drive, OkHttpClient client, CookieJar115 cookieJar) {
        this.drive = drive;
        this.client = client;
        this.cookieJar = cookieJar;
    }

    public static class PlayRequest {
        /** 代理地址（播放器用的） */
        public final String proxyUrl;
        /** 原始直链（调试用） */
        public final String rawUrl;
        /** 是否是 m3u8 */
        public final boolean isM3u8;

        PlayRequest(String proxyUrl, String rawUrl, boolean isM3u8) {
            this.proxyUrl = proxyUrl;
            this.rawUrl = rawUrl;
            this.isM3u8 = isM3u8;
        }
    }

    /**
     * 构造播放地址（对照 PY _player_content）
     *
     * @param file 视频文件
     * @param mode "org"=原画优先, "trans"=转码优先, 其他=自动（转码优先，起播快）
     * @return PlayRequest，proxyUrl 给播放器
     */
    public PlayRequest build(CloudFile file, String mode) throws Exception {
        if (file == null || TextUtils.isEmpty(file.getPickCode())) {
            throw new Exception("文件 pick_code 为空");
        }
        String pc = file.getPickCode();

        // 90秒内重复点同一文件 → 强制刷新（对照 PY _play_last）
        boolean forceRefresh = pc.equals(lastPc)
                && (System.currentTimeMillis() - lastPlayTime) < REPEAT_FORCE_MS;
        lastPc = pc;
        lastPlayTime = System.currentTimeMillis();

        String url;
        boolean isM3u8;

        if ("org".equals(mode)) {
            // 原画：缓存那条 → 现取一条新的（治签名过期）→ 转码 → 网盘自动画质
            url = getDownUrlCached(pc, forceRefresh);
            isM3u8 = false;
            if (TextUtils.isEmpty(url)) {
                url = drive.m3u8Url(pc);
                isM3u8 = true;
            }
        } else {
            // 默认/自动：m3u8 优先（起播快、省带宽）；m3u8 失败时兜底原画直链（对照 PY 候选链 m3u8 → 原画）
            url = null;
            isM3u8 = false;
            String m3u8 = null;
            try {
                m3u8 = drive.m3u8Url(pc);
            } catch (Exception e) {
                Log.d(TAG, "m3u8 地址构造失败: " + e.getMessage());
            }
            if (!TextUtils.isEmpty(m3u8) && probeM3u8(m3u8)) {
                url = m3u8;
                isM3u8 = true;
            }
            if (TextUtils.isEmpty(url)) {
                Log.d(TAG, "m3u8 不可用，兜底原画直链");
                url = getDownUrlCached(pc, forceRefresh);
                isM3u8 = false;
            }
        }

        if (TextUtils.isEmpty(url)) {
            throw new Exception("取播放地址失败");
        }

        // 包成代理地址（对照 PY _via_proxy）
        String proxyUrl = viaProxy(url, pc);
        if (TextUtils.isEmpty(proxyUrl)) {
            throw new Exception("代理启动失败");
        }

        Log.d(TAG, "播放地址已包代理: " + (isM3u8 ? "m3u8" : "原画"));
        return new PlayRequest(proxyUrl, url, isM3u8);
    }

    /**
     * 轻量探查 m3u8 接口：上游是否返回可用的 video_url
     * 失败（异常/空/state=false）返回 false，上层兜底原画直链
     */
    private boolean probeM3u8(String m3u8Url) {
        try {
            okhttp3.OkHttpClient probeClient = client.newBuilder()
                    .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                    .build();
            okhttp3.Request.Builder rb = new okhttp3.Request.Builder().url(m3u8Url)
                    .header("User-Agent", "Mozilla/5.0 115Browser/27.0.5.7")
                    .header("Referer", "https://115.com/");
            String cookie = cookieJar.merged(drive.getCookie());
            if (!TextUtils.isEmpty(cookie)) {
                rb.header("Cookie", cookie);
            }
            try (okhttp3.Response resp = probeClient.newCall(rb.build()).execute()) {
                okhttp3.ResponseBody body = resp.body();
                String text = body != null ? body.string() : "";
                String trim = text.trim();
                if (trim.startsWith("{")) {
                    org.json.JSONObject jo = new org.json.JSONObject(trim);
                    boolean ok = !TextUtils.isEmpty(jo.optString("video_url", ""));
                    Log.d(TAG, "m3u8 探查: " + (ok ? "可用" : "无 video_url"));
                    return ok;
                }
                boolean ok = trim.startsWith("#EXTM3U");
                Log.d(TAG, "m3u8 探查: " + (ok ? "直接返回清单" : "未知内容"));
                return ok;
            }
        } catch (Exception e) {
            Log.d(TAG, "m3u8 探查异常: " + e.getMessage());
            return false;
        }
    }

    /**
     * 取原画直链（带 4 分钟缓存，对照 PY _down_cached）
     */
    private String getDownUrlCached(String pc, boolean force) throws Exception {
        if (!force) {
            CacheEntry e = urlCache.get(pc);
            if (e != null && (System.currentTimeMillis() - e.time) < URL_CACHE_MS
                    && !TextUtils.isEmpty(e.url)) {
                Log.d(TAG, "直链缓存命中");
                return e.url;
            }
        }
        Log.d(TAG, "现取直链" + (force ? "(强制刷新)" : ""));
        String url = drive.downUrl(pc);
        if (!TextUtils.isEmpty(url)) {
            urlCache.put(pc, new CacheEntry(System.currentTimeMillis(), url));
        }
        return url;
    }

    /**
     * 包成代理地址（对照 PY _via_proxy）
     */
    private String viaProxy(String url, String pc) {
        Proxy115 proxy = Proxy115.getInstance(client, cookieJar);
        int port = proxy.startProxy();
        if (port <= 0) return "";

        // 请求头：115 认 UA
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 115Browser/27.0.5.7");
        headers.put("Referer", "https://115.com/");
        // 带上合并后的 cookie（登录 cookie + 罐内）
        String cookie = cookieJar.merged(drive.getCookie());
        if (!TextUtils.isEmpty(cookie)) {
            headers.put("Cookie", cookie);
        }

        String token = proxy.put(headers);
        if (!TextUtils.isEmpty(pc)) {
            proxy.bindTarget(token, url, pc);
            // 设置 403 重铸回调
            proxy.setRemintCallback(pickcode -> {
                // 对照 PY _remint_org：现取一条新链
                String newUrl = drive.downUrl(pickcode);
                String newCookie = cookieJar.merged(drive.getCookie());
                // 更新缓存
                urlCache.put(pickcode, new CacheEntry(System.currentTimeMillis(), newUrl));
                return new String[]{newUrl, newCookie};
            });
        }
        return "http://127.0.0.1:" + port + "/v?t=" + token + "&u=" + Proxy115.b64e(url);
    }

    /** 清理缓存 */
    public void clearCache() {
        urlCache.clear();
    }
}
