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
        boolean forceRefresh = markPlay(pc);

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

        // 包成代理地址（对照 PY _via_proxy）；isM3u8 登记进代理，供播放器初始格式判定
        String proxyUrl = viaProxy(url, pc, isM3u8);
        if (TextUtils.isEmpty(proxyUrl)) {
            throw new Exception("代理启动失败");
        }

        Log.d(TAG, "播放地址已包代理: " + (isM3u8 ? "m3u8" : "原画"));
        return new PlayRequest(proxyUrl, url, isM3u8);
    }

    /** 播放记账：90 秒内重复点同一文件返回 true（强制刷新），对照 PY _play_last */
    private boolean markPlay(String pc) {
        boolean force = pc.equals(lastPc)
                && (System.currentTimeMillis() - lastPlayTime) < REPEAT_FORCE_MS;
        lastPc = pc;
        lastPlayTime = System.currentTimeMillis();
        return force;
    }

    /** 播放清单一条：画质档名 + 已包代理的播放地址 */
    public static class PlayEntry {
        public final String label;
        public final String proxyUrl;
        public final boolean isM3u8;

        PlayEntry(String label, String proxyUrl, boolean isM3u8) {
            this.label = label;
            this.proxyUrl = proxyUrl;
            this.isM3u8 = isM3u8;
        }
    }

    /** 播放清单：entries 是选集（画质档），defaultIndex 是默认起播档 */
    public static class PlayList {
        public final java.util.List<PlayEntry> entries;
        public final int defaultIndex;

        PlayList(java.util.List<PlayEntry> entries, int defaultIndex) {
            this.entries = entries;
            this.defaultIndex = defaultIndex;
        }
    }

    private static class RawEntry {
        final String label;
        final String url;
        final boolean isM3u8;

        RawEntry(String label, String url, boolean isM3u8) {
            this.label = label;
            this.url = url;
            this.isM3u8 = isM3u8;
        }
    }

    /**
     * 组装播放清单（对照 PY _file_detail 的选集构成）：
     * 原画（/files/video 的 origin_file_url 优先，chrome/downurl 兜底）
     * → 转码多档（最多 6 档）→ 网盘自动画质（m3u8 探活才入列）。
     * 默认起播档＝自动画质（起播快、与既有 auto 口径一致），没有则第一档。
     * 全部候选都拿不到时降级走 build(auto) 单条，与旧 getPlayUrl 同口径。
     */
    public PlayList buildPlayList(CloudFile file) throws Exception {
        if (file == null || TextUtils.isEmpty(file.getPickCode())) {
            throw new Exception("文件 pick_code 为空");
        }
        String pc = file.getPickCode();
        boolean forceRefresh = markPlay(pc);

        java.util.List<RawEntry> raw = new java.util.ArrayList<>();
        java.util.Set<String> seenUrl = new java.util.HashSet<>();
        java.util.Set<String> seenLabel = new java.util.HashSet<>();

        // 原画：origin_file_url 现拿优先，downurl 老链兜底
        String origin = "";
        try {
            origin = drive.videoOriginUrl(pc);
        } catch (Exception ignored) {
        }
        if (TextUtils.isEmpty(origin)) {
            try {
                origin = getDownUrlCached(pc, forceRefresh);
            } catch (Exception e) {
                Log.d(TAG, "原画取链失败: " + e.getMessage());
            }
        }
        if (!TextUtils.isEmpty(origin) && seenUrl.add(origin)) {
            raw.add(new RawEntry("原画", origin, false));
            seenLabel.add("原画");
        }

        // 转码多档（对照 PY _file_detail：最多 6 档，同名跳过）
        java.util.List<java.util.Map<String, String>> tiers = drive.videoTiers(pc);
        for (int i = 0; i < tiers.size() && i < 6; i++) {
            String tUrl = tiers.get(i).get("url");
            String tName = tiers.get(i).get("name");
            if (TextUtils.isEmpty(tUrl) || !seenUrl.add(tUrl)) continue;
            String label = TextUtils.isEmpty(tName) ? ("转码 " + (i + 1)) : ("转码 " + tName);
            if (!seenLabel.add(label)) continue;
            raw.add(new RawEntry(label, tUrl, tUrl.toLowerCase().contains(".m3u8")));
        }

        // 网盘自动画质（HLS）：探活通过才入列（对照 PY m3u8 候选）
        int autoIndex = -1;
        try {
            String m3u8 = drive.m3u8Url(pc);
            if (!TextUtils.isEmpty(m3u8) && !seenUrl.contains(m3u8) && probeM3u8(m3u8)) {
                raw.add(new RawEntry("自动画质", m3u8, true));
                seenUrl.add(m3u8);
                autoIndex = raw.size() - 1;
            }
        } catch (Exception e) {
            Log.d(TAG, "自动画质探查失败: " + e.getMessage());
        }

        if (raw.isEmpty()) {
            // 候选全灭：降级旧 auto 单条（m3u8 探活→原画直链的既有链）
            PlayRequest r = build(file, "auto");
            java.util.List<PlayEntry> single = new java.util.ArrayList<>();
            single.add(new PlayEntry("自动画质", r.proxyUrl, r.isM3u8));
            return new PlayList(single, 0);
        }

        java.util.List<PlayEntry> entries = new java.util.ArrayList<>();
        int defaultIndex = 0;
        for (int i = 0; i < raw.size(); i++) {
            RawEntry re = raw.get(i);
            String proxyUrl = viaProxy(re.url, pc, re.isM3u8);
            if (TextUtils.isEmpty(proxyUrl)) throw new Exception("代理启动失败");
            entries.add(new PlayEntry(re.label, proxyUrl, re.isM3u8));
            if (i == autoIndex) defaultIndex = entries.size() - 1;
        }
        Log.d(TAG, "播放清单已组装: " + entries.size() + " 档, 默认第 " + (defaultIndex + 1) + " 档");
        return new PlayList(entries, defaultIndex);
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
    private String viaProxy(String url, String pc, boolean isM3u8) {
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
        // 登记 m3u8 标记：播放器拿到代理地址时首轮即按清单解析（走 Result.format 既有链）
        proxy.markM3u8(token, isM3u8);
        // 照插件样板：m3u8 路不绑目标（u= 按次生效，变体/分片各取各的）；
        // 只有原画直链路才绑目标（403 重铸用）
        if (!isM3u8 && !TextUtils.isEmpty(pc)) {
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
