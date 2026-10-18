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
        /** 视频宽（MediaStreams 里 Video 流；0=未知）。 */
        public int width = 0;
        /** 视频高（MediaStreams 里 Video 流；0=未知）。 */
        public int height = 0;
        /** 同次 PlaybackInfo 里 MediaSource 的 Id（410 重取时按源匹配）。 */
        public String mediaSourceId = "";
        /** 视频编码（MediaStreams 里 Video 流 Codec；""=未知）。 */
        public String videoCodec = "";

        /**
         * 详情页画质行标签：只用源数据实际字段拼，不编造。
         * 例：「直链 · 4K 2160p · 25.0 Mbps · H264」「转码 · 1080p · 8.5 Mbps」。
         * forceTranscode=true 时（转码线路行）全部标转码。
         */
        public String qualityRowLabel(boolean forceTranscode) {
            StringBuilder sb = new StringBuilder(forceTranscode || !direct ? "转码" : "直链");
            String q;
            if (height >= 2160 || width >= 3800) q = "4K " + (height > 0 ? height + "p" : width + "x" + height);
            else if (height >= 1440) q = "1440p";
            else if (height >= 1080) q = "1080p";
            else if (height >= 720) q = "720p";
            else if (height > 0) q = height + "p";
            else if (width > 0) q = width + "x" + height;
            else q = "未知";
            sb.append(" · ").append(q);
            sb.append(EmbyApi.bitrateText(bitrate));
            if (!TextUtils.isEmpty(videoCodec)) sb.append(" · ").append(videoCodec.toUpperCase());
            return sb.toString();
        }
    }

    /**
     * 默认直链：分辨率最高优先，同分辨率比码率。
     * 宽高未知时退化为纯比码率；都比不出时取第一条。
     */
    public static PlayOption pickDefaultDirect(List<PlayOption> direct) {
        PlayOption best = null;
        if (direct == null) return null;
        for (PlayOption o : direct) {
            if (o == null || TextUtils.isEmpty(o.url)) continue;
            if (best == null) {
                best = o;
                continue;
            }
            long a = (long) o.width * o.height;
            long b = (long) best.width * best.height;
            if (a != b ? a > b : o.bitrate > best.bitrate) best = o;
        }
        return best;
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

    /**
     * 列表响应双形状兼容：多数端点回 {Items:[...]} 包装，
     * 但 Resume / Latest 直接回裸数组 [...]——按包装解析它们永远是空（真机实证）。
     */
    private static List<EmbyItem> parseList(String body) throws Exception {
        String b = body == null ? "" : body.trim();
        if (b.startsWith("[")) return EmbyItem.listFrom(new JSONArray(b));
        return EmbyItem.listFrom(new JSONObject(b));
    }

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
     * 打点前先做节点按需拉起：本服开了「走节点」就静默把内核拉起来，
     * 不再要求节点总开关先开（用户口径）。
     */
    private void beginMark() {
        try {
            ensureNodeForServer(serverId);
            boolean global = ClashSettings.isGlobalMode(App.get()) && ClashSettings.isEnabled(App.get());
            if (!global) SourceGate.mark(SourceProxyPolicy.KIND_EMBY, serverId);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 任一 Emby 服开了「走节点」：发请求前静默把 mihomo 内核拉起来，
     * 不再要求节点总开关先开。返回 true = 内核在跑（或本服本来就直连）；
     * false = 拉起失败，调用方给中文提示。必须在后台线程调用（同步阻塞）。
     */
    public static boolean ensureNodeForServer(String serverId) {
        try {
            android.content.Context app = App.get();
            if (app == null || TextUtils.isEmpty(serverId)) return true;
            if (!com.fongmi.android.tv.clash.SourceProxyPolicy.isEnabled(
                    app, com.fongmi.android.tv.clash.SourceProxyPolicy.KIND_EMBY, serverId)) return true;
            return com.fongmi.android.tv.clash.MihomoManager.get().ensureRunning(app);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 401 = token 失效/被服务端踢掉：抛带重登引导的中文异常，调用方直接展示。 */
    private static void throwIfAuthExpired(int code) throws Exception {
        if (code == 401) throw new Exception("登录已失效，请去设置-个性功能重新登录");
    }

    /** 最近一次请求的回码（播放失败诊断用）。 */
    private int lastHttpCode;

    private String get(String path) throws Exception {
        beginMark();
        try {
            Request req = rb(path).get().build();
            try (Response resp = client.newCall(req).execute()) {
                lastHttpCode = resp.code();
                throwIfAuthExpired(resp.code());
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
                throwIfAuthExpired(resp.code());
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
                lastHttpCode = resp.code();
                throwIfAuthExpired(resp.code());
                if (!resp.isSuccessful() || resp.body() == null)
                    throw new Exception("http " + resp.code());
                return resp.body().string();
            }
        } finally {
            SourceGate.clear();
        }
    }

    // ---------- 地址识别（短地址补全） ----------

    /** 探测命中后的登录成果：真正通的地址 + 服务器信息 + 登录结果。 */
    public static class ResolvedLogin {
        public String baseUrl = "";
        public ServerInfo info;
        public LoginResult login;
    }

    /**
     * 把用户填的地址展开成候选列表（按优先级排）：
     * 公益服常只给短地址（一个域名或 IP），http/https 前缀和端口都可以没写，
     * 还有不少服挂在 /emby 子路径下（面板反代常见，根路径探测是 404）。
     * 带前缀 → 原样优先，没写端口再补一份 :8096；
     * 没前缀 → https、http 依次排，没写端口再各补一份 :8096。
     * 每个候选若没带路径，紧跟一份补 /emby 的。
     * 之后逐个 ping，通的算数——壳子自己认，不让用户拼地址。
     */
    public static List<String> candidates(String raw) {
        List<String> bases = new ArrayList<>();
        String u = raw == null ? "" : raw.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.isEmpty()) return bases;
        String lower = u.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            bases.add(u);
            if (!hasPort(u)) bases.add(withPort(u, 8096));
        } else {
            bases.add("https://" + u);
            bases.add("http://" + u);
            if (!hasPort(u)) {
                bases.add("https://" + withPort(u, 8096));
                bases.add("http://" + withPort(u, 8096));
            }
        }
        List<String> out = new ArrayList<>();
        for (String b : bases) {
            out.add(b);
            if (!hasPath(b)) out.add(b + "/emby");
        }
        return out;
    }

    private static boolean hasPath(String u) {
        String s = u;
        int p = s.indexOf("://");
        if (p >= 0) s = s.substring(p + 3);
        return s.indexOf('/') >= 0;
    }

    private static String authorityOf(String u) {
        String s = u;
        int p = s.indexOf("://");
        if (p >= 0) s = s.substring(p + 3);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int at = s.lastIndexOf('@');
        if (at >= 0) s = s.substring(at + 1);
        return s;
    }

    private static boolean hasPort(String u) {
        String a = authorityOf(u);
        int first = a.indexOf(':');
        if (first < 0) return false;
        if (a.indexOf(':', first + 1) >= 0) return true; // IPv6 字面量，不补
        return a.substring(first + 1).matches("\\d+");
    }

    private static String withPort(String u, int port) {
        String prefix = "";
        String rest = u;
        int p = u.indexOf("://");
        if (p >= 0) {
            prefix = u.substring(0, p + 3);
            rest = u.substring(p + 3);
        }
        int slash = rest.indexOf('/');
        String auth = slash >= 0 ? rest.substring(0, slash) : rest;
        String path = slash >= 0 ? rest.substring(slash) : "";
        return prefix + auth + ":" + port + path;
    }

    /**
     * 登录/探测打点：与实例 beginMark 同口径——「全部走节点」模式下不打点（框架按全局决策），
     * 否则按服打点。登录前服务器还没入库也照打：id 先定、策略表先挂（见 EmbyServerActivity），
     * 这样只通节点的服在登录这一步就能走节点，不会卡死在第一步。
     */
    private static void markFor(String serverId) {
        try {
            // 登录/探测也是"真正用节点"的时机：本服开了走节点就静默拉起内核
            ensureNodeForServer(serverId);
            boolean global = ClashSettings.isGlobalMode(App.get()) && ClashSettings.isEnabled(App.get());
            if (!global) SourceGate.mark(SourceProxyPolicy.KIND_EMBY, serverId == null ? "" : serverId);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 短地址解析 + 登录：逐个候选 ping，通的拿去登录，命中的地址就是最终 baseUrl。
     * ping 通但登录报账密错 = 地址已找对，直接抛错，不再换候选瞎试。
     * 全程在调用线程打点（serverId），走不走节点由策略表决定。
     */
    public static ResolvedLogin resolveLogin(String rawUrl, String username, String password, String serverId) throws Exception {
        return resolveLogin(rawUrl, username, password, serverId, null);
    }

    /**
     * 带备选地址的登录：候选 = 主地址展开 + 备选地址逐个展开（主在前、备选在后），
     * 哪个通就用哪个，回写的 baseUrl 就是实际通的那个。
     */
    public static ResolvedLogin resolveLogin(String rawUrl, String username, String password,
                                            String serverId, List<String> altUrls) throws Exception {
        List<String> cands = candidates(rawUrl);
        if (altUrls != null) for (String alt : altUrls) {
            if (alt == null || alt.trim().isEmpty()) continue;
            for (String c : candidates(alt)) if (!cands.contains(c)) cands.add(c);
        }
        if (cands.isEmpty()) throw new Exception("先填服务器地址");
        Exception last = null;
        for (String base : cands) {
            ServerInfo info;
            markFor(serverId);
            try {
                info = pingInternal(base, 8);
            } catch (Exception e) {
                last = e;
                continue;
            } finally {
                SourceGate.clear();
            }
            markFor(serverId);
            try {
                LoginResult r = loginInternal(base, username, password);
                ResolvedLogin out = new ResolvedLogin();
                out.baseUrl = base;
                out.info = info;
                out.login = r;
                return out;
            } finally {
                SourceGate.clear();
            }
        }
        throw new Exception("连不上服务器：地址再核对下，或打开「走节点」再试"
                + (last != null && last.getMessage() != null ? "（" + last.getMessage() + "）" : ""));
    }

    // ---------- 连服检测（免登录） ----------

    public static ServerInfo ping(String baseUrl) throws Exception {
        return pingInternal(baseUrl, TIMEOUT);
    }

    private static ServerInfo pingInternal(String baseUrl, int timeoutSec) throws Exception {
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        while (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        OkHttpClient c = new OkHttpClient.Builder()
                .connectTimeout(timeoutSec, TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
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
        return loginInternal(baseUrl, username, password);
    }

    private static LoginResult loginInternal(String baseUrl, String username, String password) throws Exception {
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        while (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
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
        return parseList(get("/Users/" + userId + "/Views"));
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
        return items(parentId, start, limit, genre, "SortName", "Ascending", null, null, "Movie,Series");
    }

    /**
     * 某库下条目（服务端分页，全参数）：
     * sortBy 取值 SortName / DateCreated / ProductionYear / CommunityRating，
     * sortOrder 取值 Ascending / Descending；
     * filters 取值 IsPlayed / IsUnplayed / IsFavorite（多个逗号隔开）；
     * years 如 "2024"（多个逗号隔开）；ratings 如 "PG-13"；tags 标签名；
     * includeTypes 如 "Movie,Series"。
     */
    public ItemPage items(String parentId, int start, int limit, String genre,
                          String sortBy, String sortOrder, String filters, String years,
                          String ratings, String tags, String includeTypes) throws Exception {
        String sb = TextUtils.isEmpty(sortBy) ? "SortName" : sortBy;
        String so = TextUtils.isEmpty(sortOrder) ? "Ascending" : sortOrder;
        String path = "/Users/" + userId + "/Items"
                + "?ParentId=" + parentId
                + "&IncludeItemTypes=" + enc(includeTypes == null || includeTypes.isEmpty() ? "Movie,Series" : includeTypes)
                + "&Recursive=true&Fields=" + FIELDS
                + (genre == null || genre.isEmpty() ? "" : "&Genres=" + enc(genre))
                + (filters == null || filters.isEmpty() ? "" : "&Filters=" + enc(filters))
                + (years == null || years.isEmpty() ? "" : "&Years=" + enc(years))
                + (ratings == null || ratings.isEmpty() ? "" : "&OfficialRatings=" + enc(ratings))
                + (tags == null || tags.isEmpty() ? "" : "&Tags=" + enc(tags))
                + "&SortBy=" + sb + "&SortOrder=" + so
                + "&StartIndex=" + start + "&Limit=" + limit;
        JSONObject root = new JSONObject(get(path));
        ItemPage p = new ItemPage();
        p.items.addAll(EmbyItem.listFrom(root));
        p.total = root.optInt("TotalRecordCount", p.items.size());
        return p;
    }

    /** 兼容老调用：genre 版。 */
    public ItemPage items(String parentId, int start, int limit, String genre,
                          String sortBy, String sortOrder, String filters, String years,
                          String includeTypes) throws Exception {
        return items(parentId, start, limit, genre, sortBy, sortOrder, filters, years, null, null, includeTypes);
    }

    /** 音乐库条目（Audio 类型）：views 里 CollectionType=music 的库走这里。 */
    public ItemPage audioItems(String parentId, int start, int limit, String sortBy, String sortOrder) throws Exception {
        return items(parentId, start, limit, null, sortBy, sortOrder, null, null, "Audio");
    }

    /** 按库类型自动路由：音乐库走 Audio，其它走 Movie,Series。 */
    public ItemPage libraryItems(String parentId, int start, int limit, String genre,
                                 String sortBy, String sortOrder, String filters, String years,
                                 String ratings, String tags) throws Exception {
        if (isMusicCollection(parentId)) return audioItems(parentId, start, limit, sortBy, sortOrder);
        return items(parentId, start, limit, genre, sortBy, sortOrder, filters, years, ratings, tags, "Movie,Series");
    }

    /** 兼容老调用（无分级/标签）。 */
    public ItemPage libraryItems(String parentId, int start, int limit, String genre,
                                 String sortBy, String sortOrder, String filters, String years) throws Exception {
        return libraryItems(parentId, start, limit, genre, sortBy, sortOrder, filters, years, null, null);
    }

    /** 某库是不是音乐库：查 views 的 CollectionType，查不到按影视处理（宁可多显示不漏）。 */
    public boolean isMusicCollection(String viewId) {
        try {
            String ct = viewCollectionType(viewId);
            return "music".equalsIgnoreCase(ct);
        } catch (Throwable t) {
            return false;
        }
    }

    private final java.util.Map<String, String> viewTypeCache = new java.util.HashMap<>();

    private synchronized String viewCollectionType(String viewId) throws Exception {
        if (viewId == null) return "";
        String hit = viewTypeCache.get(viewId);
        if (hit != null) return hit;
        String ct = "";
        for (EmbyItem v : views()) {
            viewTypeCache.put(v.id, v.collectionType == null ? "" : v.collectionType);
            if (viewId.equals(v.id)) ct = v.collectionType == null ? "" : v.collectionType;
        }
        return ct;
    }

    /** 某剧的季 */
    public List<EmbyItem> seasons(String seriesId) throws Exception {
        String path = "/Shows/" + seriesId + "/Seasons"
                + "?UserId=" + userId + "&Fields=" + FIELDS;
        return parseList(get(path));
    }

    /** 某季的集 */
    public List<EmbyItem> episodes(String seriesId, String seasonId) throws Exception {
        String path = "/Shows/" + seriesId + "/Episodes"
                + "?SeasonId=" + seasonId + "&UserId=" + userId + "&Fields=" + FIELDS;
        return parseList(get(path));
    }

    /** 继续观看（专属首页首 rail）。 */
    public List<EmbyItem> resume() throws Exception {
        return parseList(get("/Users/" + userId + "/Items/Resume"));
    }

    /** 最新入库。 */
    public List<EmbyItem> latest() throws Exception {
        return parseList(get("/Users/" + userId + "/Items/Latest"));
    }

    /** 接着看下一集。与其它端点同口径：相对 base 拼 /Shows/NextUp，
     * base 自带 /emby 的服不需要再加前缀（之前硬编码 /emby/Shows/NextUp 会拼成
     * /emby/emby/… 白白 404 一次再回退）。 */
    public List<EmbyItem> nextUp() throws Exception {
        return parseList(get("/Shows/NextUp?UserId=" + userId));
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
            return parseList(get("/Items/" + itemId + "/Similar?UserId=" + userId + "&Fields=" + FIELDS));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** 类型列表（筛选栏用）。 */
    public List<String> genres() throws Exception {
        return nameList("/Genres?UserId=" + userId);
    }

    /** 标签列表（筛选栏用；服务端没配标签就返回空，调用方隐藏该筛选项）。 */
    public List<String> tags() throws Exception {
        return nameList("/Tags?UserId=" + userId + "&Limit=200");
    }

    /**
     * 分级列表（如 PG-13；/OfficialRatings 在 4.10.1.0 上回的条目没有 Name 字段，
     * 解析时 Name/Value/Rating 都试，拿不到就返回空，调用方隐藏该筛选项）。
     */
    public List<String> officialRatings() throws Exception {
        List<String> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONObject(get("/OfficialRatings?UserId=" + userId)).optJSONArray("Items");
            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String n = o.optString("Name", "");
                if (n.isEmpty()) n = o.optString("Value", "");
                if (n.isEmpty()) n = o.optString("Rating", "");
                if (!n.isEmpty() && !list.contains(n)) list.add(n);
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    /** 年份列表（/Years 在部分老服上 500，失败返回空，调用方隐藏年份筛选项）。 */
    public List<String> years() {
        List<String> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONObject(get("/Years?UserId=" + userId)).optJSONArray("Items");
            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                String n = arr.optJSONObject(i).optString("Name", "");
                if (!n.isEmpty() && !list.contains(n)) list.add(n);
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    private List<String> nameList(String path) throws Exception {
        List<String> list = new ArrayList<>();
        JSONArray arr = new JSONObject(get(path)).optJSONArray("Items");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            String n = arr.optJSONObject(i).optString("Name", "");
            if (!n.isEmpty()) list.add(n);
        }
        return list;
    }

    /**
     * 合集列表（BoxSet，供新首页分类面板用，只读）。
     * 4.10.1.0 实测：/Users/{uid}/Items?IncludeItemTypes=BoxSet 200，{Items:[]} 形状正常。
     */
    public List<EmbyItem> collections() throws Exception {
        String path = "/Users/" + userId + "/Items"
                + "?Recursive=true&IncludeItemTypes=BoxSet"
                + "&Fields=" + FIELDS + ",ChildCount"
                + "&SortBy=SortName&SortOrder=Ascending";
        return parseList(get(path));
    }

    /**
     * 合集内条目（只读）：优先 ParentId 口径
     * /Users/{uid}/Items?ParentId={collectionId}&Recursive=true，
     * 4.10.1.0 实测 200 且 TotalRecordCount 正确——这是 Emby 取子条目的标准口径，
     * 没有第二个"合集自己的 Items 端点"，不需要回退。
     */
    public ItemPage collectionItems(String collectionId, int start, int limit) throws Exception {
        String path = "/Users/" + userId + "/Items"
                + "?ParentId=" + collectionId
                + "&IncludeItemTypes=Movie,Series"
                + "&Recursive=true&Fields=" + FIELDS
                + "&SortBy=SortName&SortOrder=Ascending"
                + "&StartIndex=" + start + "&Limit=" + limit;
        JSONObject root = new JSONObject(get(path));
        ItemPage p = new ItemPage();
        p.items.addAll(EmbyItem.listFrom(root));
        p.total = root.optInt("TotalRecordCount", p.items.size());
        return p;
    }

    /** 演员列表。 */
    public List<EmbyItem> persons() throws Exception {
        return parseList(get("/Persons?UserId=" + userId));
    }

    /** 某演员的作品。 */
    public List<EmbyItem> personItems(String personId) throws Exception {
        String path = "/Users/" + userId + "/Items"
                + "?PersonIds=" + personId + "&Recursive=true"
                + "&IncludeItemTypes=Movie,Series,Episode&Fields=" + FIELDS;
        return parseList(get(path));
    }

    /** 收藏列表。 */
    public List<EmbyItem> favoriteItems() throws Exception {
        return favoriteItems(0);
    }

    /**
     * 收藏列表（带 limit：大收藏夹分页取，StartIndex 固定 0；limit<=0=全量，老口径）。
     */
    public List<EmbyItem> favoriteItems(int limit) throws Exception {
        String path = "/Users/" + userId + "/Items"
                + "?Filters=IsFavorite&Recursive=true"
                + "&IncludeItemTypes=Movie,Series,Episode&Fields=" + FIELDS
                + (limit > 0 ? "&StartIndex=0&Limit=" + limit : "");
        return parseList(get(path));
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
        JSONObject resp = new JSONObject(post("/Items/" + itemId + "/PlaybackInfo?UserId=" + userId, body.toString()));
        try {
            JSONArray sources = resp.optJSONArray("MediaSources");
            java.util.TreeSet<String> keys = new java.util.TreeSet<>();
            java.util.Iterator<String> it = resp.keys();
            while (it.hasNext()) keys.add(it.next());
            com.github.catvod.crawler.SpiderDebug.log("emby-play", "PlaybackInfo sources=%d keys=%s",
                    sources == null ? -1 : sources.length(), TextUtils.join(",", keys));
        } catch (Throwable ignored) {
        }
        return resp;
    }

    /**
     * 指定版本（MediaSourceId）的转码地址：详情画质行在转码线路上选了直放版时用。
     * 只在这种情形多调一次 PlaybackInfo，平时不走。
     */
    public String transcodeUrlFor(String itemId, String mediaSourceId) throws Exception {
        JSONObject info = playbackInfo(itemId);
        JSONArray sources = info.optJSONArray("MediaSources");
        if (sources != null) for (int i = 0; i < sources.length(); i++) {
            JSONObject ms = sources.optJSONObject(i);
            if (ms != null && mediaSourceId.equals(ms.optString("Id", ""))) {
                return absolute(base, token, transcodeUrl(itemId, mediaSourceId, ms));
            }
        }
        throw new Exception("没找到该版本的转码地址");
    }

    private static String absolute(String base, String token, String url) {
        if (TextUtils.isEmpty(url)) return "";
        String u = url;
        if (!u.startsWith("http")) {
            // 服务器返回的相对地址可能自带 base 路径：服挂 /emby 时 DirectStreamUrl
            // 就是 /emby/Videos/...，无脑拼 base 会成 /emby/emby/... 直接 404 播不出。
            // 已带路径的只拼源头（协议+域名），没带的才拼整个 base。
            String basePath = pathOf(base);
            if (u.startsWith("/") && !basePath.isEmpty()
                    && (u.equals(basePath) || u.startsWith(basePath + "/"))) {
                u = originOf(base) + u;
            } else if (u.startsWith("/")) {
                u = base + u;
            } else {
                u = base + "/" + u;
            }
        }
        if (!u.contains("api_key=")) u += (u.contains("?") ? "&" : "?") + "api_key=" + token;
        return u;
    }

    /**
     * Yamby 实证：直链 URL 自带 StartTimeTicks=0&MaxStreamingBitrate=2147483647，
     * 影桥前置认这个长相（play-dead-diagnosis.md P0②）。只加在直链拼装点，
     * 转码 m3u8/字幕 DeliveryUrl 不动。续播是本地 seek（setPosition→EXTRA_START_MS），
     * 不靠服务端起播点，StartTimeTicks=0 不会丢进度。
     */
    private static String streamParams(String url) {
        if (TextUtils.isEmpty(url)) return url;
        StringBuilder sb = new StringBuilder(url);
        char sep = url.contains("?") ? '&' : '?';
        if (!url.contains("StartTimeTicks=")) {
            sb.append(sep).append("StartTimeTicks=0");
            sep = '&';
        }
        if (!url.contains("MaxStreamingBitrate=")) {
            sb.append(sep).append("MaxStreamingBitrate=2147483647");
        }
        return sb.toString();
    }

    private static String originOf(String base) {
        String b = base == null ? "" : base;
        int p = b.indexOf("://");
        if (p < 0) return b;
        int slash = b.indexOf('/', p + 3);
        return slash >= 0 ? b.substring(0, slash) : b;
    }

    private static String pathOf(String base) {
        String b = base == null ? "" : base;
        int p = b.indexOf("://");
        if (p >= 0) b = b.substring(p + 3);
        int slash = b.indexOf('/');
        String path = slash >= 0 ? b.substring(slash) : "";
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path;
    }

    private String transcodeUrl(String itemId, String msId, JSONObject ms) {
        String t = ms.optString("TranscodingUrl", "");
        if (!TextUtils.isEmpty(t)) return absolute(base, token, t);
        return base + "/Videos/" + itemId + "/master.m3u8?MediaSourceId=" + enc(msId) + "&api_key=" + token;
    }

    /**
     * 播放失败诊断：记地址构成（域名+路径形状，不带令牌和参数值）+回码，不记敏感值。
     * 例：https://host:8096/emby/Videos/{id}/stream?MediaSourceId,Static,api_key code=403
     */
    private void logPlayFail(String phase, String url, Exception e) {
        try {
            int code = lastHttpCode;
            if (code <= 0 && e != null && e.getMessage() != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("http\\s+(\\d{3})").matcher(e.getMessage());
                if (m.find()) code = Integer.parseInt(m.group(1));
            }
            com.github.catvod.crawler.SpiderDebug.log("emby-play", "FAIL phase=%s url=%s code=%s err=%s",
                    phase, urlShape(url), code <= 0 ? "-" : String.valueOf(code),
                    e == null ? "-" : String.valueOf(e.getMessage()));
        } catch (Throwable ignored) {
        }
    }

    /** 播放地址构成（成功时也记一条，方便定位拼坏的地址）：形状脱敏，无令牌无参数值。 */
    private void logPlayOk(String phase, String url) {
        try {
            com.github.catvod.crawler.SpiderDebug.log("emby-play", "OK phase=%s url=%s",
                    phase, urlShape(url));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 地址形状：scheme://host[:port] + 路径（ID 段打码成 {id}）+ 参数名（按字母序，无值）。
     * 令牌与参数值一律不记。
     */
    private static String urlShape(String url) {
        if (TextUtils.isEmpty(url)) return "-";
        try {
            String u = url;
            String query = "";
            int q = u.indexOf('?');
            if (q >= 0) {
                java.util.TreeSet<String> names = new java.util.TreeSet<>();
                for (String kv : u.substring(q + 1).split("&")) {
                    int eq = kv.indexOf('=');
                    String n = eq >= 0 ? kv.substring(0, eq) : kv;
                    if (!n.isEmpty()) names.add(n);
                }
                if (!names.isEmpty()) query = "?" + String.join(",", names);
                u = u.substring(0, q);
            }
            String origin;
            String path;
            int p = u.indexOf("://");
            if (p >= 0) {
                int s = u.indexOf('/', p + 3);
                if (s >= 0) {
                    origin = u.substring(0, s);
                    path = u.substring(s);
                } else {
                    origin = u;
                    path = "";
                }
            } else {
                origin = "";
                path = u;
            }
            String[] segs = path.split("/");
            for (int i = 0; i < segs.length; i++) {
                String s = segs[i];
                if (s.matches("[0-9a-fA-F]{16,}")
                        || s.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                        || s.matches("\\d{6,}")) {
                    segs[i] = "{id}";
                }
            }
            return origin + String.join("/", segs) + query;
        } catch (Throwable t) {
            return "unparseable";
        }
    }

    /** 拿播放地址：直链优先，服务器不支持直链时给转码 m3u8。 */
    public PlayTarget playback(String itemId, String itemName) throws Exception {
        String attempt = "";
        try {
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
                    t.url = streamParams(absolute(base, token, direct));
                    attempt = t.url;
                    logPlayOk("playback/direct", t.url);
                    return t;
                }
            }
            JSONObject ms = sources.getJSONObject(0);
            collectTracks(t, ms, itemId);
            t.url = absolute(base, token, transcodeUrl(itemId, ms.optString("Id", ""), ms));
            attempt = t.url;
            if (TextUtils.isEmpty(t.url)) throw new Exception("没拿到播放地址");
            logPlayOk("playback/transcode", t.url);
            return t;
        } catch (Exception e) {
            logPlayFail("playback", attempt, e);
            throw e;
        }
    }

    /**
     * 全部 MediaSources 候选项 + 会话 id + 自动决策源的字幕/轨道，一次 PlaybackInfo 调用。
     * 播前选画质（EmbyNavigator）、强制转码分支（EmbySpider）都走这里，不再另调 playback() 取会话。
     */
    public PlaybackCandidates playbackCandidates(String itemId, String itemName) throws Exception {
        try {
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
                o.mediaSourceId = msId;
                int[] size = videoSize(ms);
                o.width = size[0];
                o.height = size[1];
                o.videoCodec = videoCodecOf(ms);
                String q = qualityLabel(ms);
                if (canDirect) {
                    o.label = "直链" + (q.isEmpty() ? "" : " · " + q) + bitrateText(bitrate);
                    o.url = streamParams(absolute(base, token, direct));
                } else {
                    o.label = "转码" + (q.isEmpty() ? "" : " · " + q) + bitrateText(bitrate);
                    o.url = absolute(base, token, transcodeUrl(itemId, msId, ms));
                }
                if (!TextUtils.isEmpty(o.url)) c.options.add(o);
            }
            if (c.options.isEmpty()) throw new Exception("没拿到播放地址");
            // 字幕/轨道取自自动决策源（与 playback() 的选择逻辑一致）
            collectTracksInto(c, sources, itemId);
            StringBuilder shapes = new StringBuilder();
            for (PlayOption o : c.options) {
                if (shapes.length() > 0) shapes.append(" | ");
                shapes.append(urlShape(o.url));
            }
            logPlayOk("candidates[" + c.options.size() + "]", shapes.toString());
            return c;
        } catch (Exception e) {
            logPlayFail("candidates", "", e);
            throw e;
        }
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

    /** MediaStreams 里第一个 Video 流的宽高；取不到返回 {0,0}。 */
    private static int[] videoSize(JSONObject ms) {
        JSONArray streams = ms.optJSONArray("MediaStreams");
        if (streams != null) for (int i = 0; i < streams.length(); i++) {
            JSONObject s = streams.optJSONObject(i);
            if (s != null && "Video".equals(s.optString("Type", ""))) {
                return new int[]{s.optInt("Width", 0), s.optInt("Height", 0)};
            }
        }
        return new int[]{0, 0};
    }

    /** MediaStreams 里第一个 Video 流的编码；取不到返回 ""。 */
    private static String videoCodecOf(JSONObject ms) {
        JSONArray streams = ms.optJSONArray("MediaStreams");
        if (streams != null) for (int i = 0; i < streams.length(); i++) {
            JSONObject s = streams.optJSONObject(i);
            if (s != null && "Video".equals(s.optString("Type", ""))) {
                return s.optString("Codec", "");
            }
        }
        return "";
    }

    private static String qualityLabel(JSONObject ms) {
        int[] size = videoSize(ms);
        if (size[0] > 0 && size[1] > 0) return size[0] + "x" + size[1];
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

    /** 播放开始调一次：建立播放会话。顺手记本服上次播放时间（服务器管理页展示用）。 */
    public void reportPlaying(String itemId, String playSessionId) {
        try {
            EmbyStore.touchLastPlayed(serverId);
        } catch (Throwable ignored) {
        }
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
