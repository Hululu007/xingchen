package com.fongmi.android.tv.api.music;

import com.fongmi.android.tv.bean.music.LyricVersion;
import com.fongmi.android.tv.bean.music.MusicLyric;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cloudflare 歌词聚合客户端：向 xc-lyric Worker 取一首歌的多平台歌词版本。
 * 只做请求与解析，不改任何现有取词链；接线由词级链统一做。
 * Worker 回包版本平台值与瓜子一致：kugou、netease、qq、kuwo、migu、amll；
 * 本地版本不走 Worker，手机侧自己有。
 * 传输走 catvod OkHttp（与全 App 取数同待遇：DoH／代理选择器／拦截器）——
 * 此前是全 App 唯一裸 HttpURLConnection 的取数口，代理与分应用 VPN 场景下
 * 直连 workers.dev 慢/超时，面板阶段一长期回空（1.4.44 真机实证）。
 * 端点已迁移：主端点是他的 Pages（xingchen-dl.pages.dev/lyric，同一份聚合
 * 逻辑以 Pages Function 形态托管，部署在本机 cf-lyric-worker/deploy_pages.py）；
 * workers.dev 在他的网络下连接超时 12 次零成功（真机日志实锤），老地址降为
 * 备用——主端点传输失败时单发一次兜底，pages.dev 有他真机刷新脚本的成功实证。
 */
public class CfLyricClient {

    public static final String DEFAULT_BASE_URL = "https://xingchen-dl.pages.dev/lyric";

    /** 备用端点：原 workers.dev 上的 xc-lyric（主端点传输失败时单发兜底） */
    public static final String FALLBACK_BASE_URL = "https://xc-lyric.1372659842.workers.dev/lyric";

    /** 单发超时（F2 超时收口）：旧 25 秒×（主两发＋备一发）≈76 秒，超过共享链
     * 等待 75 秒与预取 await 45 秒两层上限，迟到结果与回空竞态、收口不确定。
     * 改单发 8 秒：fetch 一次的最坏总时长＝主两发 8s×2＋间隔 0.6s＋备一发 8s
     * ≈24.6 秒（FETCH_WORST_MS），确定小于上层两道封顶（45/75 秒），层级收口。 */
    static final int FETCH_TIMEOUT_MS = 8000;
    /** fetch（含重试与备用端点）的最坏总时长上界，供上层定 await 封顶时对账 */
    public static final long FETCH_WORST_MS = 25_000L;

    /** 取数结局分类（诊断用）：拿到 JSON 应答 */
    public static final String NOTE_OK = "ok";
    /** 取数结局分类：两发都没拿到应答体（超时/连接失败，OkHttp.string 已把真因记进 SpiderDebug） */
    public static final String NOTE_TRANSPORT = "transport";
    /** 取数结局分类：拿到应答体但不是 JSON（边缘错误页/应答被截断等） */
    public static final String NOTE_NON_JSON = "non-json";

    private String baseUrl = DEFAULT_BASE_URL;
    /** 最近一次 fetch 的结局分类（NOTE_*），随 CfResponse.fetchNote 带出 */
    private String lastNote;

    public void setBaseUrl(String baseUrl) {
        if (baseUrl != null && !baseUrl.trim().isEmpty()) this.baseUrl = baseUrl.trim();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public CfResponse fetch(MusicTrack track) {
        if (track == null) return new CfResponse();
        int durationSec = track.duration > 0 ? (int) (track.duration / 1000) : 0;
        return fetch(track.title, track.artist, durationSec);
    }

    public CfResponse fetch(String title, String artist, int durationSec) {
        return fetchLegs(title, artist, durationSec, "fast");
    }

    /**
     * 慢腿补取（提速⑥阶段二）：只问酷我／咪咕两条慢腿——主路（fetch）已按 Worker
     * 收口只回快腿（酷狗／网易／QQ＋AMLL），慢腿版本由版本面板阶段二的全量聚合
     * 经本入口补回（MusicRepository cfLyricVersionsAll 的 includeSlow 支）。
     * 老 Worker 不认 legs 参数会回全量，调用方按既有去重合并、不重复入列。
     */
    public CfResponse fetchSlowLegs(MusicTrack track) {
        if (track == null) return new CfResponse();
        int durationSec = track.duration > 0 ? (int) (track.duration / 1000) : 0;
        return fetchLegs(track.title, track.artist, durationSec, "slow");
    }

    private CfResponse fetchLegs(String title, String artist, int durationSec, String legs) {
        CfResponse response = fetchPrimary(title, artist, durationSec, legs);
        if (NOTE_TRANSPORT.equals(response.fetchNote)) {
            CfResponse fallback = fetchFallbackOnce(title, artist, durationSec, legs);
            if (fallback != null) return fallback;
        }
        return response;
    }

    /** 备用端点单发兜底：只在主端点传输失败（超时/连不上）时走，一发定生死、
     * 不重试、不碰熔断（熔断只管主端点，键仍是 baseUrl）；拿到像样应答才算数，
     * 否则回 null 让调用方用主端点的结局分类。 */
    private CfResponse fetchFallbackOnce(String title, String artist, int durationSec, String legs) {
        if (FALLBACK_BASE_URL.equals(baseUrl)) return null;
        String query;
        try {
            query = "title=" + URLEncoder.encode(title == null ? "" : title.trim(), "UTF-8")
                    + "&artist=" + URLEncoder.encode(artist == null ? "" : artist.trim(), "UTF-8")
                    + "&duration=" + Math.max(0, durationSec)
                    + "&legs=" + legs;
        } catch (Throwable ignored) {
            return null;
        }
        String body = fetchOnce(FALLBACK_BASE_URL + "?" + query);
        if (body == null) return null;
        CfResponse parsed = parse(body);
        parsed.fetchNote = NOTE_OK;
        SpiderDebug.log("music", "cf lyric fallback ok title=%s", title);
        return parsed;
    }

    private CfResponse fetchPrimary(String title, String artist, int durationSec, String legs) {
        CfResponse response = new CfResponse();
        if (title == null || title.trim().isEmpty()) return response;
        String query;
        try {
            query = "title=" + URLEncoder.encode(title.trim(), "UTF-8")
                    + "&artist=" + URLEncoder.encode(artist == null ? "" : artist.trim(), "UTF-8")
                    + "&duration=" + Math.max(0, durationSec)
                    + "&legs=" + legs;
        } catch (Throwable ignored) {
            return response;
        }
        String url = baseUrl + (baseUrl.contains("?") ? "&" : "?") + query;
        // 熔断（真机日志实锤）：他的网络下 workers.dev 直连每次超时、同会话
        // 反复重试全灭，预取任务两次撞满 45 秒上限——同一端点刚以传输失败收场时，
        // 窗口内后续调用直接按传输失败返回，不再逐条链重复烧整段超时；端点恢复
        // （迁移/换地址都会换 baseUrl）或窗口过后自动重试，拿到应答即解除。
        if (breakerTripped()) {
            response.fetchNote = NOTE_TRANSPORT;
            SpiderDebug.log("music", "cf lyric breaker open url=%s", baseUrl);
            return response;
        }
        // 传输层没拿到应答（超时/非 200/空体）时重试一次：Worker 首发慢是常态，
        // 一发定生死会把面板阶段一长期卡在空。拿到应答（哪怕版本为空）不重试。
        for (int attempt = 1; attempt <= 2; attempt++) {
            String body = fetchOnce(url);
            if (body != null) {
                CfResponse parsed = parse(body);
                parsed.fetchNote = NOTE_OK;
                clearBreaker();
                return parsed;
            }
            if (attempt < 2) {
                try {
                    Thread.sleep(600);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        response.fetchNote = lastNote == null ? NOTE_TRANSPORT : lastNote;
        if (NOTE_TRANSPORT.equals(response.fetchNote)) tripBreaker();
        SpiderDebug.log("music", "cf lyric fetch failed note=%s title=%s", response.fetchNote, title);
        return response;
    }

    /** 熔断窗口：同端点传输失败后 2 分钟内快速失败（只认传输失败；拿到应答不触发） */
    private static final long BREAKER_WINDOW_MS = 120_000L;
    private static volatile long sTransportFailAt;
    private static volatile String sTransportFailBase;

    private boolean breakerTripped() {
        String base = sTransportFailBase;
        return base != null && base.equals(baseUrl)
                && System.currentTimeMillis() - sTransportFailAt < BREAKER_WINDOW_MS;
    }

    private void tripBreaker() {
        sTransportFailBase = baseUrl;
        sTransportFailAt = System.currentTimeMillis();
    }

    private void clearBreaker() {
        if (baseUrl.equals(sTransportFailBase)) {
            sTransportFailBase = null;
            sTransportFailAt = 0;
        }
    }

    /** 拿到像样的应答体返回原文；传输失败/空体/非 JSON（如边缘错误页）返回 null
     * （与「应答了但没版本」区分）。结局分类记进 lastNote 供诊断：OkHttp.string 的
     * 契约是失败回空串，所以空体＝没拿到应答（transport）、有体非 JSON＝应答异常。 */
    private String fetchOnce(String url) {
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("Accept", "application/json");
            String body = OkHttp.string(url, headers, FETCH_TIMEOUT_MS);
            if (body == null || body.trim().isEmpty()) {
                lastNote = NOTE_TRANSPORT;
                return null;
            }
            String trimmed = body.trim();
            if (!trimmed.startsWith("{")) {
                lastNote = NOTE_NON_JSON;
                return null;
            }
            lastNote = NOTE_OK;
            return body;
        } catch (Throwable ignored) {
            lastNote = NOTE_TRANSPORT;
            return null;
        }
    }

    public CfResponse parse(String json) {
        CfResponse response = new CfResponse();
        if (json == null || json.trim().isEmpty()) return response;
        try {
            JSONObject root = new JSONObject(json);
            response.title = root.optString("title");
            response.artist = root.optString("artist");
            JSONArray versions = root.optJSONArray("versions");
            if (versions != null) {
                for (int i = 0; i < versions.length(); i++) {
                    JSONObject item = versions.optJSONObject(i);
                    if (item == null) continue;
                    CfVersion version = new CfVersion();
                    version.platform = item.optString("platform");
                    version.label = item.optString("label");
                    version.songTitle = item.optString("songTitle");
                    version.songArtist = item.optString("songArtist");
                    version.format = item.optString("format");
                    version.kind = item.optString("kind");
                    version.lineCount = item.optInt("lineCount");
                    version.match = item.optInt("match");
                    version.durationSec = item.optInt("durationSec");
                    version.songId = item.optString("songId");
                    version.lyric = item.optString("lyric");
                    version.translation = item.optString("translation");
                    version.romanization = item.optString("romanization");
                    if (version.lyric != null && !version.lyric.trim().isEmpty()) response.versions.add(version);
                }
            }
            JSONObject bundle = root.optJSONObject("bundle");
            if (bundle != null) {
                response.bundleLyric = bundle.optString("lyric");
                response.bundleYrc = bundle.optString("yrc");
                response.bundleKlyric = bundle.optString("klyric");
                response.bundleTlyric = bundle.optString("tlyric");
                response.bundleRomalrc = bundle.optString("romalrc");
            }
            JSONObject errors = root.optJSONObject("errors");
            if (errors != null) {
                for (String key : new String[]{"kugou", "netease", "qq", "kuwo", "migu", "amll"}) {
                    String value = errors.optString(key);
                    if (value != null && !value.isEmpty()) response.errors.put(key, value);
                }
            }
            // errors 消费（F2 低项）：此前解析完无人读——平台级失败原因（哪家
            // no-match／timeout）到不了定案面。这里统一落一条日志，面板/排障按
            // 平台名 grep 即知哪条腿为什么空；只记有错的平台，成功腿不刷屏。
            if (!response.errors.isEmpty()) {
                SpiderDebug.log("music", "cf lyric platform errors title=%s errors=%s",
                        response.title, response.errors.toString());
            }
        } catch (Throwable ignored) {
        }
        return response;
    }

    public static class CfVersion {
        public String platform;
        public String label;
        public String songTitle;
        public String songArtist;
        public String format;
        public String kind;
        public int lineCount;
        public int match;
        /** 候选歌自身时长（秒，平台搜索元数据）：App 本地算偏差与瓜子定级的原料；0＝未知 */
        public int durationSec;
        /** 候选歌源内 id（瓜子定级门要用；空＝无 id，定级按瓜子口径淘汰） */
        public String songId;
        public String lyric;
        public String translation;
        public String romanization;

        public boolean isWord() {
            return "word".equals(kind);
        }

        public boolean isLine() {
            return "line".equals(kind);
        }

        public String displayLabel() {
            String kindLabel = isWord() ? "逐字" : isLine() ? "逐行" : "纯文本";
            if (label == null || label.isEmpty()) return kindLabel;
            return label + "·" + kindLabel;
        }

        public LyricVersion toLyricVersion() {
            MusicLyric musicLyric = new MusicLyric(lyric);
            musicLyric.format = format;
            musicLyric.sourceName = label;
            musicLyric.translation = translation;
            musicLyric.romanization = romanization;
            boolean wordTimed = isWord();
            boolean lineTimed = wordTimed || isLine();
            return new LyricVersion(musicLyric, label, wordTimed, lineTimed);
        }
    }

    public static class CfResponse {
        public String title;
        public String artist;
        /** 取数结局分类（NOTE_*）：面板空态据此显真因；null＝没走到取数（如标题为空） */
        public String fetchNote;
        public List<CfVersion> versions = new ArrayList<>();
        public String bundleLyric;
        public String bundleYrc;
        public String bundleKlyric;
        public String bundleTlyric;
        public String bundleRomalrc;
        public Map<String, String> errors = new HashMap<>();

        public boolean hasVersions() {
            return !versions.isEmpty();
        }

        public List<LyricVersion> toLyricVersions() {
            List<LyricVersion> out = new ArrayList<>();
            for (CfVersion version : versions) out.add(version.toLyricVersion());
            return out;
        }

        public CfVersion firstWordVersion() {
            for (CfVersion version : versions) if (version.isWord()) return version;
            return null;
        }
    }
}
