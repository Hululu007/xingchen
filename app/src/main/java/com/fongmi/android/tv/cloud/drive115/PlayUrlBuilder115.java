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
     * 档名清晰度打分（选转码最高档用）：从档名里抠分辨率数字（1080P/720P/4K 等），
     * 抠不到返回 -1。纯函数、无外部依赖，供离线断言直接抽取验证。
     */
    static int tierScore(String name) {
        if (name == null) return -1;
        String s = name.toUpperCase(java.util.Locale.US);
        if (s.contains("8K")) return 4320;
        if (s.contains("4K")) return 2160;
        if (s.contains("2K")) return 1440;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{3,4})\\s*P").matcher(s);
        int best = -1;
        while (m.find()) {
            try {
                best = Math.max(best, Integer.parseInt(m.group(1)));
            } catch (NumberFormatException ignored) {
            }
        }
        if (best > 0) return best;
        // 无 P 后缀的裸分辨率数字（如 2160/1080）也认
        java.util.regex.Matcher n = java.util.regex.Pattern.compile("(2160|1440|1080|720|576|480|360)").matcher(s);
        if (n.find()) {
            try {
                return Integer.parseInt(n.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    /**
     * 组装播放清单（潇洒哥定案口径）：总共只出两条——
     * 原画（/files/video 的 origin_file_url 优先，chrome/downurl 兜底，排第一）
     * ＋转码一条（接口多档里最高的那一档，其余档不列）。
     * 默认起播＝转码条（原画不作默认，流量与起播速度考虑）；只有原画时默认原画。
     * 什么都拿不到（无原画、无转码）走播放失败的既有报错路，不再回退「自动单条」。
     * m3u8 仅作 build() 内部取链手段保留，不以任何名字进本清单。
     */
    public PlayList buildPlayList(CloudFile file) throws Exception {
        if (file == null || TextUtils.isEmpty(file.getPickCode())) {
            throw new Exception("文件 pick_code 为空");
        }
        String pc = file.getPickCode();
        boolean forceRefresh = markPlay(pc);

        java.util.List<RawEntry> raw = new java.util.ArrayList<>();
        String originUrl = "";

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
        if (!TextUtils.isEmpty(origin)) {
            originUrl = origin;
            raw.add(new RawEntry("原画", origin, false));
        }

        // 转码只出一条：接口多档里最高的那一档（同址即原画的那档跳过；
        // 档名打不出分时按接口原序，第一档胜出）
        java.util.List<java.util.Map<String, String>> tiers = drive.videoTiers(pc);
        String bestUrl = "";
        String bestName = "";
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < tiers.size(); i++) {
            String tUrl = tiers.get(i).get("url");
            String tName = tiers.get(i).get("name");
            if (TextUtils.isEmpty(tUrl) || tUrl.equals(originUrl)) continue;
            int score = tierScore(tName);
            if (score > bestScore) {
                bestScore = score;
                bestUrl = tUrl;
                bestName = tName;
            }
        }
        int transIndex = -1;
        if (!TextUtils.isEmpty(bestUrl)) {
            String label = TextUtils.isEmpty(bestName) ? "转码" : ("转码 " + bestName);
            raw.add(new RawEntry(label, bestUrl, bestUrl.toLowerCase().contains(".m3u8")));
            transIndex = raw.size() - 1;
        }

        if (raw.isEmpty()) {
            // 原画、转码都拿不到：走播放失败的既有报错路（与 build 同文案）
            throw new Exception("取播放地址失败");
        }

        java.util.List<PlayEntry> entries = new java.util.ArrayList<>();
        int defaultIndex = 0;
        for (int i = 0; i < raw.size(); i++) {
            RawEntry re = raw.get(i);
            String proxyUrl = viaProxy(re.url, pc, re.isM3u8);
            if (TextUtils.isEmpty(proxyUrl)) throw new Exception("代理启动失败");
            entries.add(new PlayEntry(re.label, proxyUrl, re.isM3u8));
            if (i == transIndex) defaultIndex = entries.size() - 1;
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
