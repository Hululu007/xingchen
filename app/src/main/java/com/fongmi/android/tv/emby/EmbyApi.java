package com.fongmi.android.tv.emby;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.clash.ClashSettings;
import com.fongmi.android.tv.clash.SourceProxyPolicy;
import com.github.catvod.net.GlobalProxy;
import com.github.catvod.net.SourceGate;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.ProxySelector;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Emby Server 原生 API 客户端（OkHttp，与 Drive115 同网络层口径）。
 * 所有方法都是同步阻塞调用，调用方必须在子线程里用。
 * 密码只出现在 login() 的请求体里，不打日志不落盘。
 * 端点与参数按 plan §2 + Yamby 实证拼，PlaybackInfo/进度上报按本机真服实测（VERIFY.md）对齐。
 */
public class EmbyApi {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int TIMEOUT = 15;

    private final OkHttpClient client;
    private final String base;
    private final String token;
    private final String userId;
    /** 所属服务器 id（走节点打点用；"" = 未归属）。 */
    private final String serverId;

    public EmbyApi(String baseUrl, String token, String userId) {
        this(baseUrl, token, userId, "");
    }

    public EmbyApi(String baseUrl, String token, String userId, String serverId) {
        base = baseUrl == null ? "" : baseUrl;
        this.token = token == null ? "" : token;
        this.userId = userId == null ? "" : userId;
        this.serverId = serverId == null ? "" : serverId;
        client = newClient();
    }

    /** 框架管到的唯一入口：按 catvod OkHttp.java:264 照抄的选择器挂载。 */
    private static OkHttpClient newClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT, TimeUnit.SECONDS)
                .proxySelector(SourceGate.wrapSelector(GlobalProxy.wrapSelector(ProxySelector.getDefault())))
                .build();
    }

    /** 每台安装唯一的 DeviceId（EmbyStore 持久化），不写死。 */
    private static String deviceId() {
        try {
            return EmbyStore.deviceId();
        } catch (Throwable t) {
            return "xingchen-android";
        }
    }

    /** 包 versionName（取不到回退 "1.3.28"）。 */
    private static String appVersion() {
        try {
            String v = App.get().getPackageManager()
                    .getPackageInfo(App.get().getPackageName(), 0).versionName;
            return TextUtils.isEmpty(v) ? "1.3.28" : v;
        } catch (Throwable t) {
            return "1.3.28";
        }
    }

    /** Client 如实申报 "XingChen"，不冒充官方客户端；鉴权头两处（实例/static 登录）都走这里。 */
    private static String buildAuthHeader(String token, String version, String deviceId) {
        String h = "MediaBrowser Client=\"XingChen\", Device=\"Android\", DeviceId=\"" + deviceId
                + "\", Version=\"" + version + "\"";
        if (!TextUtils.isEmpty(token)) h += ", Token=\"" + token + "\"";
        return h;
    }

    public String authHeader() {
        return buildAuthHeader(token, appVersion(), deviceId());
    }

    /** ---- 结果包装 ---- */

    /** 自动决策后的播放目标（直链优先）。 */
    public static class PlayTarget {
        public String url = "";
        public String name = "";
        public String playSessionId = "";
        public final List<Subtitle> subtitles = new ArrayList<>();
        public final List<TrackInfo> audioTracks = new ArrayList<>();
    }

    public static class Subtitle {
        public String title = "";
        public String url = "";
        public boolean external = false;
    }

    /** 音轨 / 字幕轨（播放中切换用，来自 PlaybackInfo.MediaStreams）。 */
    public static class TrackInfo {
        public String name = "";
        public String language = "";
        public int index = 0;
        public boolean isDefault = false;
        public boolean external = false;
        public String url = "";
    }

    /** 手动切画质/强制转码的候选项。 */
    public static class PlayOption {
        public String label = "";
        public String url = "";
        public boolean direct = false;
        public long bitrate = 0;
        /** 同次 PlaybackInfo 响应的会话 id（会话一致性：一次调用即够）。 */
        public String sessionId = "";
    }

    /** 一次 PlaybackInfo 的全部产出：画质候选 + 自动决策源的字幕/轨道 + 会话 id。 */
    public static class PlaybackCandidates {
        public String playSessionId = "";
        public final List<PlayOption> options = new ArrayList<>();
        public final List<Subtitle> subtitles = new ArrayList<>();
        public final List<TrackInfo> audioTracks = new ArrayList<>();
    }

    public static class ItemPage {
        public final List<EmbyItem> items = new ArrayList<>();
        public int total = 0;
    }

    public static class ServerInfo {
        public String serverName = "";
        public String version = "";
        public String id = "";
    }

    public static class LoginResult {
        public String token = "";
        public String userId = "";
        public String userName = "";
    }

    // ---------- 基础 ----------

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }

    private Request.Builder rb(String path) {
        String url = base + path + (path.contains("?") ? "&" : "?") + "api_key=" + token;
        return new Request.Builder().url(url).header("X-Emby-Authorization", authHeader());
    }

    /**
     * 走节点打点：全部走节点开（总开关开且全局模式）→ 不打点 → 无归属路径 → 走节点；
     * 否则按服开关打点（默认直连）；节点总开关关 → 框架层全直连。
     */
    private void beginMark() {
        try {
            boolean global = ClashSettings.isGlobalMode(App.get()) && ClashSettings.isEnabled(App.get());
            if (!global) SourceGate.mark(SourceProxyPolicy.KIND_EMBY, serverId);
        } catch (Throwable ignored) {
        }
    }

    private String get(String path) throws Exception {
        beginMark();
        try {
            Request req = rb(path).get().build();
            try (Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null)
                    throw new Exception("http " + resp.code());
                return resp.body().string();
            }
        } finally {
            SourceGate.clear();
        }
    }

    private void delete(String path) throws Exception {
        beginMark();
        try {
            Request req = rb(path).delete().build();
            try (Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful()) throw new Exception("http " + resp.code());
            }
        } finally {
            SourceGate.clear();
        }
    }

    private String post(String path, String body) throws Exception {
        beginMark();
        try {
            Request req = rb(path).post(RequestBody.create(body, JSON)).build();
            try (Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null)
                    throw new Exception("http " + resp.code());
                return resp.body().string();
            }
        } finally {
            SourceGate.clear();
        }
    }

    // ---------- 连服检测（免登录） ----------

    public static ServerInfo ping(String baseUrl) throws Exception {
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        while (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        // 只挂选择器、不打点：免登录探测不参与按服定向
        OkHttpClient c = new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT, TimeUnit.SECONDS)
                .proxySelector(SourceGate.wrapSelector(GlobalProxy.wrapSelector(ProxySelector.getDefault())))
                .build();
        Request req = new Request.Builder().url(baseUrl + "/System/Info/Public").get().build();
        try (Response resp = c.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null)
                throw new Exception("连不上服务器（http " + resp.code() + "）");
            JSONObject o = new JSONObject(resp.body().string());
            ServerInfo s = new ServerInfo();
            s.serverName = o.optString("ServerName", "");
            s.version = o.optString("Version", "");
            s.id = o.optString("Id", "");
            return s;
        }
    }

    // ---------- 登录（拿 token；密码只进这一次请求体） ----------

    public static LoginResult login(String baseUrl, String username, String password) throws Exception {
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        while (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        // 只挂选择器、不打点：登录请求不参与按服定向
        OkHttpClient c = new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT, TimeUnit.SECONDS)
                .proxySelector(SourceGate.wrapSelector(GlobalProxy.wrapSelector(ProxySelector.getDefault())))
                .build();
        JSONObject body = new JSONObject();
        body.put("Username", username);
        body.put("Pw", password == null ? "" : password);
        Request req = new Request.Builder()
                .url(baseUrl + "/Users/AuthenticateByName")
                .header("X-Emby-Authorization", buildAuthHeader("", appVersion(), deviceId()))
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        try (Response resp = c.newCall(req).execute()) {
            if (resp.code() == 401 || resp.code() == 403)
                throw new Exception("用户名或密码不对");
            if (!resp.isSuccessful() || resp.body() == null)
                throw new Exception("登录失败（http " + resp.code() + "）");
            JSONObject o = new JSONObject(resp.body().string());
            LoginResult r = new LoginResult();
            r.token = o.optString("AccessToken", "");
            JSONObject u = o.optJSONObject("User");
            if (u != null) {
                r.userId = u.optString("Id", "");
                r.userName = u.optString("Name", username);
            }
            if (TextUtils.isEmpty(r.token)) throw new Exception("服务器没返回 token");
            return r;
        }
    }

    // ---------- 浏览 ----------

    private static final String FIELDS = "PrimaryImageAspectRatio,Overview,UserData,RunTimeTicks,IndexNumber,ParentId,"
            + "Genres,People,ProductionYear,CommunityRating,CriticRating,SeriesId,SeasonId";

    /** 媒体库（一级：电影库 / 电视剧库 / 音乐等） */
    public List<EmbyItem> views() throws Exception {
        return EmbyItem.listFrom(new JSONObject(get("/Users/" + userId + "/Views")));
    }

    /** 单条目详情。 */
    public EmbyItem getItem(String itemId) throws Exception {
        return EmbyItem.fromJson(new JSONObject(get("/Users/" + userId + "/Items/" + itemId + "?Fields=" + FIELDS)));
    }

    /** 某库下的电影/剧集（服务端分页）。 */
    public ItemPage items(String parentId, int start, int limit) throws Exception {
        return items(parentId, start, limit, null);
    }

    /** 某库下的电影/剧集（服务端分页），genre 为空=不过滤类型（筛选栏用）。 */
    public ItemPage items(String parentId, int start, int limit, String genre) throws Exception {
        String path = "/Users/" + userId + "/Items"
                + "?ParentId=" + parentId
                + "&IncludeItemTypes=Movie,Series"
                + "&Recursive=true&Fields=" + FIELDS
                + (genre == null || genre.isEmpty() ? "" : "&Genres=" + enc(genre))
                + "&SortBy=SortName&SortOrder=Ascending"
                + "&StartIndex=" + start + "&Limit=" + limit;
        JSONObject root = new JSONObject(get(path));
        ItemPage p = new ItemPage();
        p.items.addAll(EmbyItem.listFrom(root));
        p.total = root.optInt("TotalRecordCount", p.items.size());
        return p;
    }

    /** 某剧的季 */
    public List<EmbyItem> seasons(String seriesId) throws Exception {
        String path = "/Shows/" + seriesId + "/Seasons"
                + "?UserId=" + userId + "&Fields=" + FIELDS;
        return EmbyItem.listFrom(new JSONObject(get(path)));
    }

    /** 某季的集 */
    public List<EmbyItem> episodes(String seriesId, String seasonId) throws Exception {
        String path = "/Shows/" + seriesId + "/Episodes"
                + "?SeasonId=" + seasonId + "&UserId=" + userId + "&Fields=" + FIELDS;
        return EmbyItem.listFrom(new JSONObject(get(path)));
    }

    /** 继续观看（专属首页首 rail）。 */
    public List<EmbyItem> resume() throws Exception {
        return EmbyItem.listFrom(new JSONObject(get("/Users/" + userId + "/Items/Resume")));
    }

    /** 最新入库。 */
    public List<EmbyItem> latest() throws Exception {
        return EmbyItem.listFrom(new JSONObject(get("/Users/" + userId + "/Items/Latest")));
    }

    /** 接着看下一集（Yamby 端点；部分老服路径不同，失败时回退）。 */
    public List<EmbyItem> nextUp() throws Exception {
        try {
            return EmbyItem.listFrom(new JSONObject(get("/emby/Shows/NextUp?UserId=" + userId)));
        } catch (Exception e) {
            return EmbyItem.listFrom(new JSONObject(get("/Shows/NextUp?UserId=" + userId)));
        }
    }

    /** 全库搜索（单服）。 */
    public ItemPage search(String term, int start, int limit) throws Exception {
        String path = "/Users/" + userId + "/Items"
                + "?SearchTerm=" + enc(term)
                + "&Recursive=true&IncludeItemTypes=Movie,Series,Episode"
                + "&Fields=" + FIELDS
                + "&StartIndex=" + start + "&Limit=" + limit;
        JSONObject root = new JSONObject(get(path));
        ItemPage p = new ItemPage();
        p.items.addAll(EmbyItem.listFrom(root));
        p.total = root.optInt("TotalRecordCount", p.items.size());
        return p;
    }

    /** 同类推荐。无元数据时服务端可能 500，失败直接当无推荐（不抛错）。 */
    public List<EmbyItem> similar(String itemId) {
        try {
            return EmbyItem.listFrom(new JSONObject(get("/Items/" + itemId + "/Similar?UserId=" + userId + "&Fields=" + FIELDS)));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** 类型列表（筛选栏用）。 */
    public List<String> genres() throws Exception {
        List<String> list = new ArrayList<>();
        JSONArray arr = new JSONObject(get("/Genres?UserId=" + userId)).optJSONArray("Items");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            String n = arr.optJSONObject(i).optString("Name", "");
            if (!n.isEmpty()) list.add(n);
        }
        return list;
    }

    /** 演员列表。 */
    public List<EmbyItem> persons() throws Exception {
        return EmbyItem.listFrom(new JSONObject(get("/Persons?UserId=" + userId)));
    }

    /** 某演员的作品。 */
    public List<EmbyItem> personItems(String personId) throws Exception {
        String path = "/Users/" + userId + "/Items"
                + "?PersonIds=" + personId + "&Recursive=true"
                + "&IncludeItemTypes=Movie,Series,Episode&Fields=" + FIELDS;
        return EmbyItem.listFrom(new JSONObject(get(path)));
    }

    /** 收藏列表。 */
    public List<EmbyItem> favoriteItems() throws Exception {
        String path = "/Users/" + userId + "/Items"
                + "?Filters=IsFavorite&Recursive=true"
                + "&IncludeItemTypes=Movie,Series,Episode&Fields=" + FIELDS;
        return EmbyItem.listFrom(new JSONObject(get(path)));
    }

    /** 海报地址（小图：maxWidth + tag 精准缓存失效 + quality=90）。 */
    public String imageUrl(String itemId, String imageTag, int width) {
        if (TextUtils.isEmpty(itemId)) return "";
        if (width <= 0) width = 400;
        String url = base + "/Items/" + itemId + "/Images/Primary?maxWidth=" + width
                + "&quality=90&api_key=" + token;
        if (!TextUtils.isEmpty(imageTag)) url += "&tag=" + enc(imageTag);
        return url;
    }

    /** 背景大横图（详情页用）。 */
    public String backdropUrl(String itemId, int width) {
        if (TextUtils.isEmpty(itemId)) return "";
        if (width <= 0) width = 1280;
        return base + "/Items/" + itemId + "/Images/Backdrop?maxWidth=" + width
                + "&quality=90&api_key=" + token;
    }

    // ---------- 标记 / 收藏 ----------

    public void markPlayed(String itemId) throws Exception {
        post("/Users/" + userId + "/PlayedItems/" + itemId, "{}");
    }

    public void unmarkPlayed(String itemId) throws Exception {
        delete("/Users/" + userId + "/PlayedItems/" + itemId);
    }

    public void favorite(String itemId) throws Exception {
        post("/Users/" + userId + "/FavoriteItems/" + itemId, "{}");
    }

    public void unfavorite(String itemId) throws Exception {
        delete("/Users/" + userId + "/FavoriteItems/" + itemId);
    }

    // ---------- 播放 ----------

    /**
     * 直放声明的设备档案：mp4/m4v + h264 + aac 直放。
     * 实测：不带它 DirectStreamUrl 为 null；带上返回真实直链。
     */
    private JSONObject deviceProfile() {
        try {
            JSONObject dp = new JSONObject();
            dp.put("Name", "XingChen Android");
            dp.put("MaxStreamingBitrate", 120_000_000);
            dp.put("MaxStaticBitrate", 100_000_000);
            JSONArray direct = new JSONArray();
            JSONObject v = new JSONObject();
            v.put("Container", "mp4,m4v");
            v.put("Type", "Video");
            v.put("VideoCodec", "h264");
            v.put("AudioCodec", "aac");
            direct.put(v);
            JSONObject a = new JSONObject();
            a.put("Container", "mp3");
            a.put("Type", "Audio");
            direct.put(a);
            dp.put("DirectPlayProfiles", direct);
            JSONArray trans = new JSONArray();
            JSONObject t = new JSONObject();
            t.put("Container", "ts");
            t.put("Type", "Video");
            t.put("VideoCodec", "h264");
            t.put("AudioCodec", "aac");
            t.put("Protocol", "hls");
            t.put("Context", "Streaming");
            t.put("MaxAudioChannels", "2");
            t.put("MinSegments", 1);
            t.put("SegmentLength", 10);
            t.put("BreakOnNonKeyFrames", true);
            trans.put(t);
            dp.put("TranscodingProfiles", trans);
            dp.put("ContainerProfiles", new JSONArray());
            dp.put("CodecProfiles", new JSONArray());
            dp.put("ResponseProfiles", new JSONArray());
            JSONArray subs = new JSONArray();
            for (String f : new String[]{"srt", "vtt", "ass", "ssa"}) {
                JSONObject s = new JSONObject();
                s.put("Format", f);
                s.put("Method", "External");
                subs.put(s);
            }
            dp.put("SubtitleProfiles", subs);
            return dp;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private JSONObject playbackInfo(String itemId) throws Exception {
        JSONObject body = new JSONObject();
        body.put("UserId", userId);
        body.put("DeviceId", deviceId());
        body.put("MaxStreamingBitrate", 120_000_000);
        body.put("StartTimeTicks", 0);
        body.put("DeviceProfile", deviceProfile());
        return new JSONObject(post("/Items/" + itemId + "/PlaybackInfo?UserId=" + userId, body.toString()));
    }

    private static String absolute(String base, String token, String url) {
        if (TextUtils.isEmpty(url)) return "";
        String u = url.startsWith("http") ? url : base + url;
        if (!u.contains("api_key=")) u += (u.contains("?") ? "&" : "?") + "api_key=" + token;
        return u;
    }

    private String transcodeUrl(String itemId, String msId, JSONObject ms) {
        String t = ms.optString("TranscodingUrl", "");
        if (!TextUtils.isEmpty(t)) return absolute(base, token, t);
        return base + "/Videos/" + itemId + "/master.m3u8?MediaSourceId=" + enc(msId) + "&api_key=" + token;
    }

    /** 拿播放地址：直链优先，服务器不支持直链时给转码 m3u8。 */
    public PlayTarget playback(String itemId, String itemName) throws Exception {
        JSONObject info = playbackInfo(itemId);
        JSONArray sources = info.optJSONArray("MediaSources");
        if (sources == null || sources.length() == 0) throw new Exception("服务器没给播放源");
        PlayTarget t = new PlayTarget();
        t.name = itemName == null ? "" : itemName;
        t.playSessionId = info.optString("PlaySessionId", "");
        for (int i = 0; i < sources.length(); i++) {
            JSONObject ms = sources.optJSONObject(i);
            if (ms == null) continue;
            String direct = ms.optString("DirectStreamUrl", "");
            if (!TextUtils.isEmpty(direct) && ms.optBoolean("SupportsDirectPlay", false)) {
                collectTracks(t, ms, itemId);
                t.url = absolute(base, token, direct);
                return t;
            }
        }
        JSONObject ms = sources.getJSONObject(0);
        collectTracks(t, ms, itemId);
        t.url = absolute(base, token, transcodeUrl(itemId, ms.optString("Id", ""), ms));
        if (TextUtils.isEmpty(t.url)) throw new Exception("没拿到播放地址");
        return t;
    }

    /**
     * 全部 MediaSources 候选项 + 会话 id + 自动决策源的字幕/轨道，一次 PlaybackInfo 调用。
     * 播前选画质（EmbyNavigator）、强制转码分支（EmbySpider）都走这里，不再另调 playback() 取会话。
     */
    public PlaybackCandidates playbackCandidates(String itemId, String itemName) throws Exception {
        JSONObject info = playbackInfo(itemId);
        JSONArray sources = info.optJSONArray("MediaSources");
        if (sources == null || sources.length() == 0) throw new Exception("服务器没给播放源");
        PlaybackCandidates c = new PlaybackCandidates();
        c.playSessionId = info.optString("PlaySessionId", "");
        for (int i = 0; i < sources.length(); i++) {
            JSONObject ms = sources.optJSONObject(i);
            if (ms == null) continue;
            String msId = ms.optString("Id", "");
            String direct = ms.optString("DirectStreamUrl", "");
            boolean canDirect = !TextUtils.isEmpty(direct) && ms.optBoolean("SupportsDirectPlay", false);
            long bitrate = ms.optLong("Bitrate", 0);
            PlayOption o = new PlayOption();
            o.bitrate = bitrate;
            o.direct = canDirect;
            o.sessionId = c.playSessionId;
            String q = qualityLabel(ms);
            if (canDirect) {
                o.label = "直链" + (q.isEmpty() ? "" : " · " + q) + bitrateText(bitrate);
                o.url = absolute(base, token, direct);
            } else {
                o.label = "转码" + (q.isEmpty() ? "" : " · " + q) + bitrateText(bitrate);
                o.url = absolute(base, token, transcodeUrl(itemId, msId, ms));
            }
            if (!TextUtils.isEmpty(o.url)) c.options.add(o);
        }
        if (c.options.isEmpty()) throw new Exception("没拿到播放地址");
        // 字幕/轨道取自自动决策源（与 playback() 的选择逻辑一致）
        collectTracksInto(c, sources, itemId);
        return c;
    }

    /** 画质候选项列表（EmbySpider 强制转码分支 / EmbyQualityDialog 用），一次调用。 */
    public List<PlayOption> playbackOptions(String itemId, String itemName) throws Exception {
        return playbackCandidates(itemId, itemName).options;
    }

    private void collectTracksInto(PlaybackCandidates c, JSONArray sources, String itemId) {
        if (sources == null || sources.length() == 0) return;
        JSONObject pick = null;
        for (int i = 0; i < sources.length(); i++) {
            JSONObject ms = sources.optJSONObject(i);
            if (ms == null) continue;
            if (pick == null) pick = ms;
            String direct = ms.optString("DirectStreamUrl", "");
            if (!TextUtils.isEmpty(direct) && ms.optBoolean("SupportsDirectPlay", false)) {
                pick = ms;
                break;
            }
        }
        if (pick == null) return;
        PlayTarget tmp = new PlayTarget();
        collectTracks(tmp, pick, itemId);
        c.subtitles.addAll(tmp.subtitles);
        c.audioTracks.addAll(tmp.audioTracks);
    }

    private static String qualityLabel(JSONObject ms) {
        JSONArray streams = ms.optJSONArray("MediaStreams");
        if (streams != null) for (int i = 0; i < streams.length(); i++) {
            JSONObject s = streams.optJSONObject(i);
            if (s != null && "Video".equals(s.optString("Type", ""))) {
                int w = s.optInt("Width", 0), h = s.optInt("Height", 0);
                if (w > 0 && h > 0) return w + "x" + h;
            }
        }
        return "";
    }

    private static String bitrateText(long bitrate) {
        if (bitrate <= 0) return "";
        if (bitrate >= 1_000_000) return " · " + String.format("%.1f Mbps", bitrate / 1_000_000.0);
        return " · " + (bitrate / 1000) + " Kbps";
    }

    private void collectTracks(PlayTarget t, JSONObject ms, String itemId) {
        JSONArray streams = ms.optJSONArray("MediaStreams");
        if (streams == null) return;
        for (int i = 0; i < streams.length(); i++) {
            JSONObject s = streams.optJSONObject(i);
            if (s == null) continue;
            String type = s.optString("Type", "");
            if ("Audio".equals(type)) {
                TrackInfo a = new TrackInfo();
                a.name = s.optString("DisplayTitle", s.optString("Language", "音轨"));
                a.language = s.optString("Language", "");
                a.index = s.optInt("Index", 0);
                a.isDefault = s.optBoolean("IsDefault", false);
                t.audioTracks.add(a);
            } else if ("Subtitle".equals(type)) {
                String delivery = s.optString("DeliveryMethod", "");
                TrackInfo sub = new TrackInfo();
                sub.name = s.optString("DisplayTitle", "字幕");
                sub.language = s.optString("Language", "");
                sub.index = s.optInt("Index", 0);
                sub.isDefault = s.optBoolean("IsDefault", false);
                sub.external = "External".equals(delivery) || "Hls".equals(delivery);
                sub.url = absolute(base, token, s.optString("DeliveryUrl", ""));
                t.audioTracks.add(sub);
                if (sub.external && !TextUtils.isEmpty(sub.url)) {
                    Subtitle st = new Subtitle();
                    st.title = sub.name;
                    st.url = sub.url;
                    st.external = true;
                    t.subtitles.add(st);
                }
            }
        }
    }

    // ---------- 续播进度（实测语义：Playing 开会话 → Progress 定时 → Stopped 落盘） ----------

    /** 播放开始调一次：建立播放会话。 */
    public void reportPlaying(String itemId, String playSessionId) {
        try {
            JSONObject body = new JSONObject();
            body.put("ItemId", itemId);
            body.put("PlaySessionId", playSessionId == null ? "" : playSessionId);
            post("/Sessions/Playing", body.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 播放中定时上报：只更新会话状态，不落盘续播点。 */
    public void reportProgress(String itemId, String playSessionId, long positionMs, boolean paused) {
        try {
            JSONObject body = new JSONObject();
            body.put("ItemId", itemId);
            body.put("PlaySessionId", playSessionId == null ? "" : playSessionId);
            body.put("PositionTicks", positionMs * 10_000L);
            body.put("IsPaused", paused);
            body.put("IsMuted", false);
            post("/Sessions/Playing/Progress", body.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 播完 / 退出调：服务端据此写 Played 与落盘续播点。 */
    public void reportStopped(String itemId, String playSessionId, long positionMs) {
        try {
            JSONObject body = new JSONObject();
            body.put("ItemId", itemId);
            body.put("PlaySessionId", playSessionId == null ? "" : playSessionId);
            body.put("PositionTicks", positionMs * 10_000L);
            body.put("IsPaused", false);
            post("/Sessions/Playing/Stopped", body.toString());
        } catch (Throwable ignored) {
        }
    }

}
