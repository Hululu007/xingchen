package com.fongmi.android.tv.api.music;

import android.text.TextUtils;

import com.fongmi.android.tv.bean.music.MusicChart;
import com.fongmi.android.tv.bean.music.MusicLyric;
import com.fongmi.android.tv.bean.music.MusicMediaSource;
import com.fongmi.android.tv.bean.music.MusicQuality;
import com.fongmi.android.tv.bean.music.MusicSearchResult;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.quickjs.bean.Req;
import com.fongmi.quickjs.method.Console;
import com.fongmi.quickjs.utils.Async;
import com.fongmi.quickjs.utils.Connect;
import com.fongmi.android.tv.music.MusicQueue;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Asset;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.whl.quickjs.android.QuickJSLoader;
import com.whl.quickjs.wrapper.JSCallFunction;
import com.whl.quickjs.wrapper.JSFunction;
import com.whl.quickjs.wrapper.JSObject;
import com.whl.quickjs.wrapper.QuickJSContext;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.Response;

/**
 * FreeJS（MusicFree 插件）桥：一期音乐源引擎。
 * 每个插件独占一个 QuickJSContext（单线程串行），新 loader 分支，不走点播 Spider。
 * 垫片见 quickjs 模块 assets/js/lib/freejs.js（CommonJS + fetch/axios/he/dayjs 最小实现，
 * crypto-js / cheerio 由宿主注入原文）。
 * 插件网络经 __freejsRequest 同步走壳子 OkHttp，吃代理/DoH/节点定向，不直连。
 */
public class FreeJsBridge {

    /**
     * 桥调用总上限：插件里是多次串行同步 HTTP（单请求已被 Connect 钳在约 10 秒），
     * 但桥这一层原来 .get() 死等无上限——插件循环重试/源挂死就能把调用线程永久顶住，
     * 搜索结果永远不回、取链永远不落地（1.3.58 真机实证）。到点按失败抛给上层收口。
     */
    private static final long CALL_TIMEOUT_MS = 30000;

    /** 取链（getMediaSource）单步上限：起播路径总预算 20 秒，这里压到 15 秒留出余量 */
    private static final long SOURCE_TIMEOUT_MS = 15000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MusicSite site;
    private final String pluginJs;

    private QuickJSContext ctx;
    private JSObject exports;

    public FreeJsBridge(MusicSite site, String pluginJs) {
        this.site = site;
        this.pluginJs = pluginJs;
    }

    public MusicSite getSite() {
        return site;
    }

    /** 插件 key：用 srcUrl 标识，源管理按它找桥 */
    public String key() {
        return site.srcUrl == null ? "" : site.srcUrl;
    }

    private <T> Future<T> submit(Callable<T> callable) {
        return executor.submit(callable);
    }

    public void init() throws Exception {
        submit(() -> {
            createCtx();
            loadPlugin();
            readMeta();
            return null;
        }).get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void createCtx() {
        try {
            QuickJSLoader.init();
        } catch (Throwable ignored) {
        }
        ctx = QuickJSContext.create();
        // 同 LxBridge：不设 console stdout 时，插件顶层 console.log 会抛
        // QuickJSException 让整插件求值失败，先把出口设上（垫片自己的
        // console 定义有 !global.console 守卫，wrapper console 已存在时不会覆盖）。
        ctx.setConsole(new Console());
        ctx.getGlobalObject().setProperty("__freejsRequest", (JSCallFunction) args -> {
            try {
                String method = args.length > 0 ? String.valueOf(args[0]) : "GET";
                String url = args.length > 1 ? String.valueOf(args[1]) : "";
                String opts = args.length > 2 ? String.valueOf(args[2]) : "{}";
                return nativeRequest(method, url, opts);
            } catch (Throwable t) {
                return "{\"error\":" + JSONObject.quote(String.valueOf(t.getMessage())) + "}";
            }
        });
        ctx.getGlobalObject().setProperty("__freejsLog", (JSCallFunction) args -> {
            try {
                String lv = args.length > 0 ? String.valueOf(args[0]) : "I";
                String msg = args.length > 1 ? String.valueOf(args[1]) : "";
                SpiderDebug.log("freejs-" + lv, "%s", msg);
            } catch (Throwable ignored) {
            }
            return null;
        });
        ctx.evaluate(Asset.read("js/lib/freejs.js"));
        injectLib("crypto-js", Asset.read("js/lib/crypto-js.js"));
        injectLib("cheerio", Asset.read("js/lib/cheerio.cjs.js"));
    }

    /** 把 UMD/CJS 库原文包进 CommonJS 定义，供插件 require */
    private void injectLib(String name, String src) {
        if (TextUtils.isEmpty(src)) return;
        ctx.evaluate("(function(){\nvar __m={exports:{}};\n"
                + "(function(require,module,exports){\n" + src + "\n})(require,__m,__m.exports);\n"
                + "__freejsDefine(\"" + name + "\",function(require,module){module.exports=__m.exports;});\n})();");
    }

    private void loadPlugin() {
        ctx.evaluate(pluginJs);
        // module/exports 的形态完全由插件 JS 决定：先 instanceof 再转，畸形插件
        // （把 module/exports 赋成字符串/函数等）走「没有导出」的失败路，
        // 不许在建桥时直接 ClassCastException 崩掉（大扫查防崩同型命中）。
        Object module = ctx.getProperty(ctx.getGlobalObject(), "module");
        if (!(module instanceof JSObject)) throw new IllegalStateException("插件没有导出 module.exports");
        Object exportsObj = ctx.getProperty((JSObject) module, "exports");
        if (!(exportsObj instanceof JSObject)) throw new IllegalStateException("插件没有导出 module.exports");
        exports = (JSObject) exportsObj;
    }

    private String prop(String name) {
        try {
            Object v = ctx.getProperty(exports, name);
            return v == null ? "" : String.valueOf(v);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 读插件元数据回填 site（platform/version/author）。
     * 注意：插件自报的 srcUrl 绝不能回填——site.srcUrl 是壳子给插件的身份键
     * （桥缓存、_plugin 归属戳、增删/启停全按它对齐），插件自报值与安装地址
     * 不是一回事（元力KW 自报 kstore 地址、预置装的是 jsDelivr 地址），
     * 一旦覆写，搜索结果的 _plugin 戳就与源管理里的地址对不上，
     * bridgeForTrack 认不出归属、取链全灭（1.3.59 真机实证 KW 歌播不出来）。
     */
    private void readMeta() {
        String platform = prop("platform");
        String version = prop("version");
        String author = prop("author");
        if (!TextUtils.isEmpty(platform)) site.name = platform;
        if (!TextUtils.isEmpty(version)) site.version = version;
        if (!TextUtils.isEmpty(author)) site.author = author;
    }

    public boolean hasFunction(String name) throws Exception {
        return submit(() -> {
            JSFunction f = null;
            try {
                f = exports.getJSFunction(name);
                return f != null;
            } catch (Throwable t) {
                return false;
            } finally {
                if (f != null) {
                    try {
                        f.release();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }).get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 是否支持搜歌（search 的 type=music）：判据是插件自己的声明，不按名字猜。
     * 插件声明了 supportedSearchType 时必须包含 "music" 才算（歌词网/歌词千寻只声明
     * ['lyric']、search 对非 lyric 类型直接返回空，不进搜索排）；未声明的老插件，
     * 有 search 函数即按可搜歌处理。
     */
    public boolean supportsMusicSearch() throws Exception {
        return submit(() -> {
            JSFunction f = null;
            try {
                f = exports.getJSFunction("search");
                if (f == null) return false;
            } catch (Throwable t) {
                return false;
            } finally {
                if (f != null) {
                    try {
                        f.release();
                    } catch (Throwable ignored) {
                    }
                }
            }
            Object declared;
            try {
                declared = ctx.getProperty(exports, "supportedSearchType");
            } catch (Throwable t) {
                declared = null;
            }
            if (declared == null) return true;
            try {
                String json = declared instanceof JSObject ? ((JSObject) declared).stringify() : String.valueOf(declared);
                if (json == null || json.isEmpty() || "undefined".equals(json)) return true;
                return json.contains("\"music\"");
            } finally {
                // 扫查待裁 23：getProperty 的 JSObject 用完即 release（与本文件
                // getJSFunction 的 finally release 纪律对齐，不靠 ctx 销毁兜底）
                if (declared instanceof JSObject) {
                    try {
                        ((JSObject) declared).release();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }).get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private Object call(String func, Object... args) throws Exception {
        return callTimed(func, CALL_TIMEOUT_MS, args);
    }

    private Object callTimed(String func, long timeoutMs, Object... args) throws Exception {
        // P3：两段等待到点都必须取消底层任务，不只放弃等待——调用线程（音乐专用池）
        // 不许被挂死的插件拖住；桥是单线程，取消发中断信号，插件内同步网络等待会响应，
        // 插件本身死循环则仍占桥线程至其返回（单线程桥的固有限制，见交卷清单）
        Future<CompletableFuture<Object>> outer = submit(() -> Async.run(exports, func, args));
        CompletableFuture<Object> stage;
        try {
            stage = outer.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            outer.cancel(true);
            throw te;
        }
        try {
            return stage.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            outer.cancel(true);
            stage.cancel(true);
            throw te;
        }
    }

    private String toJson(Object result) throws Exception {
        if (result == null) return null;
        if (result instanceof JSObject)
            // 扫查待裁 23：调用结果对象 stringify 后即在桥线程 release；全部调用方
            // 只用返回的 JSON 串、不再碰 result 本体，释放不影响后续。
            return submit(() -> {
                try {
                    return ((JSObject) result).stringify();
                } finally {
                    try {
                        ((JSObject) result).release();
                    } catch (Throwable ignored) {
                    }
                }
            }).get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        return String.valueOf(result);
    }

    /* ---------------- 协议函数 ---------------- */

    /** search(query, page, type)：type = music/album/artist/sheet/lyric */
    public MusicSearchResult search(String keyword, int page, String type) throws Exception {
        Object result = call("search", keyword, page, type);
        String json = toJson(result);
        MusicSearchResult r = new MusicSearchResult();
        r.setPage(page);
        r.setType(type);
        if (TextUtils.isEmpty(json) || "null".equals(json.trim()) || "undefined".equals(json.trim())) {
            r.setEnd(true);
            return r;
        }
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        r.setEnd(!obj.has("isEnd") || obj.get("isEnd").getAsBoolean());
        List<MusicTrack> tracks = new ArrayList<>();
        if (obj.has("data") && obj.get("data").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("data")) {
                if (el.isJsonObject()) tracks.add(toTrack(el.getAsJsonObject()));
            }
        }
        r.setTracks(tracks);
        return r;
    }

    /**
     * P-3d 源内死通道黑名单：同曲一次取链链内（多档回落／面板逐档取链多次进桥）已死过的
     * （插件，档位）通道不再重进 JS；只记当前一曲（单缓存：换歌即清），绝不跨歌拉黑好通道；
     * 第一波仓库层指派拦截保留，两层不重复拦同一通道（仓库层拦指派单试，本层拦桥内取链）。
     * 第二波堵-4：死键插件＋档位口径不动；死集用并发集，换键清集＋查／记包在同一
     * DEAD_LOCK 同步块里（调用方含服务取链、预加载、面板探链三线程，旧静态 HashSet 无同步）；
     * 探链（probe）只读不记死。
     */
    private static final Object DEAD_LOCK = new Object();
    private static String deadTrackKey = "";
    private static final Set<String> deadChannels = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static boolean isDeadChannel(String trackKey, String channel) {
        synchronized (DEAD_LOCK) {
            if (trackKey == null || !trackKey.equals(deadTrackKey)) {
                deadTrackKey = trackKey == null ? "" : trackKey;
                deadChannels.clear();
                return false;
            }
            return channel != null && deadChannels.contains(channel);
        }
    }

    private static void markDeadChannel(String trackKey, String channel) {
        synchronized (DEAD_LOCK) {
            if (trackKey == null || !trackKey.equals(deadTrackKey)) {
                deadTrackKey = trackKey == null ? "" : trackKey;
                deadChannels.clear();
            }
            if (channel != null) deadChannels.add(channel);
        }
    }

    /** 第二波堵-4：新一次取链开始时清源内死通道（服务 prepareTrack 入口与仓库层一起调，不只清仓库层）。 */
    public static void clearDeadPlayUrlAttempt() {
        synchronized (DEAD_LOCK) {
            deadTrackKey = "";
            deadChannels.clear();
        }
    }

    /** getMediaSource(musicItem, quality)：quality = low/standard/high/super */
    public MusicMediaSource getMediaSource(MusicTrack track, MusicQuality quality) throws Exception {
        return getMediaSource(track, quality, false);
    }

    /** 探链口径：probe＝true 时只读地址、不记死（面板探链用）；死键检查仍尊重已死标记。 */
    public MusicMediaSource getMediaSource(MusicTrack track, MusicQuality quality, boolean probe) throws Exception {
        String trackKey = MusicQueue.trackKey(track);
        String channel = key() + "|" + (quality == null ? "" : quality.getKey());
        if (isDeadChannel(trackKey, channel)) return null;
        MusicMediaSource ms;
        try {
            ms = getMediaSourceInner(track, quality);
        } catch (Throwable t) {
            if (!probe) markDeadChannel(trackKey, channel);
            if (t instanceof Exception) throw (Exception) t;
            throw new Exception(t);
        }
        if (ms == null || TextUtils.isEmpty(ms.url)) {
            if (!probe) markDeadChannel(trackKey, channel);
            return null;
        }
        return ms;
    }

    private MusicMediaSource getMediaSourceInner(MusicTrack track, MusicQuality quality) throws Exception {
        JsonObject item = unwrapItem(track);
        if (item == null) return null;
        Future<Object> argFuture = submit(() -> ctx.parse(item.toString()));
        Object arg;
        try {
            arg = argFuture.get(SOURCE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            argFuture.cancel(true);
            throw te;
        }
        Object result = callTimed("getMediaSource", SOURCE_TIMEOUT_MS, arg, quality.getKey());
        String json = toJson(result);
        if (TextUtils.isEmpty(json)) return null;
        json = json.trim();
        MusicMediaSource ms = new MusicMediaSource();
        if (json.startsWith("\"")) {
            ms.url = JsonParser.parseString(json).getAsString();
        } else {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            ms.url = optStr(obj, "url");
            if (obj.has("headers") && obj.get("headers").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject("headers").entrySet()) {
                    try {
                        ms.headers.put(e.getKey(), e.getValue().getAsString());
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return TextUtils.isEmpty(ms.url) ? null : ms;
    }

    /** getLyric(musicItem)：返回 {rawLrc, translation?} 或纯字符串 */
    public MusicLyric getLyric(MusicTrack track) throws Exception {
        JsonObject item = unwrapItem(track);
        if (item == null) return null;
        Object arg = submit(() -> ctx.parse(item.toString())).get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        Object result = call("getLyric", arg);
        String json = toJson(result);
        if (TextUtils.isEmpty(json)) return null;
        json = json.trim();
        MusicLyric lyric = new MusicLyric();
        if (json.startsWith("\"")) {
            lyric.rawLrc = JsonParser.parseString(json).getAsString();
        } else {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            lyric.rawLrc = firstNonEmpty(optStr(obj, "rawLrc"), optStr(obj, "lyric"), optStr(obj, "lrc"));
            lyric.translation = firstNonEmpty(optStr(obj, "translation"), optStr(obj, "tlyric"));
            lyric.romanization = firstNonEmpty(optStr(obj, "romalrc"), optStr(obj, "romanization"));
        }
        // 空白也算无词：与 MusicLyric.hasLyric 的 trim 口径一致，不许纯空白正文冒充有词堵住兜底
        return isBlank(lyric.rawLrc) && isBlank(lyric.translation) && isBlank(lyric.romanization) ? null : lyric;
    }

    private static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    /**
     * getTopLists()：可选，插件有就接。
     * MusicFree 协议返回的是分组：[{title:组名, data:[榜单条目...]}]，真榜单在 data 里；
     * 早先把组当榜单取 id，组上没 id → 每张榜 id 为空、点开必空（1.3.58 真机榜单全空定根）。
     * 兼容少数直接平铺返回榜单数组的插件（元素自带 id、无 data）。
     */
    public List<MusicChart> getTopLists() throws Exception {
        List<MusicChart> charts = new ArrayList<>();
        Object result = call("getTopLists");
        String json = toJson(result);
        if (TextUtils.isEmpty(json)) return charts;
        JsonArray arr = toArray(json);
        if (arr == null) return charts;
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject obj = el.getAsJsonObject();
            if (obj.has("data") && obj.get("data").isJsonArray()) {
                for (JsonElement item : obj.getAsJsonArray("data")) {
                    if (item.isJsonObject()) addChart(charts, item.getAsJsonObject());
                }
            } else {
                addChart(charts, obj);
            }
        }
        return charts;
    }

    private void addChart(List<MusicChart> charts, JsonObject item) {
        String id = optStr(item, "id");
        if (TextUtils.isEmpty(id)) return;
        MusicChart c = new MusicChart();
        c.platform = FreeJsMusicSource.PLATFORM;
        c.id = key() + "|" + id;
        c.name = optStr(item, "title");
        c.cover = optStr(item, "coverImg");
        c.rawJson = item.toString();
        charts.add(c);
    }

    /**
     * getTopListDetail(榜单条目对象)：可选，返回榜单曲目。
     * 协议要的是整个条目对象（插件读 topListItem.id 等字段）；早先传裸 id 字符串，
     * 插件取到 undefined、请求打到空地址抛错被上层吞掉，点开榜单永远空。
     * itemJson 缺失时退化为只带 id 的对象（只读 .id 的插件够用）。
     */
    public MusicSearchResult getTopListDetail(String listId, String itemJson) throws Exception {
        String argJson = itemJson;
        if (TextUtils.isEmpty(argJson)) {
            JsonObject o = new JsonObject();
            o.addProperty("id", listId == null ? "" : listId);
            argJson = o.toString();
        }
        final String parseSrc = argJson;
        Object arg = submit(() -> ctx.parse(parseSrc)).get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        Object result = call("getTopListDetail", arg);
        String json = toJson(result);
        MusicSearchResult r = new MusicSearchResult();
        r.setEnd(true);
        if (TextUtils.isEmpty(json)) return r;
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        JsonArray arr = null;
        if (obj.has("musicList") && obj.get("musicList").isJsonArray()) arr = obj.getAsJsonArray("musicList");
        else if (obj.has("data") && obj.get("data").isJsonArray()) arr = obj.getAsJsonArray("data");
        List<MusicTrack> tracks = new ArrayList<>();
        if (arr != null) {
            for (JsonElement el : arr) {
                if (el.isJsonObject()) tracks.add(toTrack(el.getAsJsonObject()));
            }
        }
        r.setTracks(tracks);
        return r;
    }

    /** importMusicSheet(url)：可选，返回插件给的原始 JSON（歌单形态由播放器侧再定） */
    public String importMusicSheet(String url) throws Exception {
        Object result = call("importMusicSheet", url);
        return toJson(result);
    }

    public void destroy() {
        try {
            submit(() -> {
                if (exports != null) {
                    try {
                        exports.release();
                    } catch (Throwable ignored) {
                    }
                }
                if (ctx != null) {
                    try {
                        ctx.destroy();
                    } catch (Throwable ignored) {
                    }
                }
                return null;
            }).get(5, TimeUnit.SECONDS);
        } catch (Throwable ignored) {
        }
        executor.shutdownNow();
    }

    /* ---------------- 内部 ---------------- */

    /** rawJson = {_plugin: srcUrl, _item: 插件原始条目}，拆出 _item */
    private JsonObject unwrapItem(MusicTrack track) {
        if (track == null || TextUtils.isEmpty(track.rawJson)) return null;
        try {
            JsonObject wrap = JsonParser.parseString(track.rawJson).getAsJsonObject();
            if (wrap.has("_item") && wrap.get("_item").isJsonObject()) return wrap.getAsJsonObject("_item");
            return wrap;
        } catch (Exception e) {
            return null;
        }
    }

    private MusicTrack toTrack(JsonObject item) {
        MusicTrack t = new MusicTrack();
        t.id = optStr(item, "id");
        t.title = optStr(item, "title");
        t.artist = optStr(item, "artist");
        t.album = optStr(item, "album");
        t.cover = firstNonEmpty(optStr(item, "artwork"), optStr(item, "cover"), optStr(item, "pic"),
                optStr(item, "picUrl"), optStr(item, "artworkUrl"), optStr(item, "coverUrl"),
                optStr(item, "albumPic"), optStr(item, "pic_url"));
        t.duration = toMillis(item);
        t.qualityTag = qualityTagOf(item);
        t.platform = FreeJsMusicSource.PLATFORM;
        JsonObject wrap = new JsonObject();
        wrap.addProperty("_plugin", key());
        wrap.add("_item", item);
        t.rawJson = wrap.toString();
        return t;
    }

    /**
     * 插件 qualities（MusicFree musicItem.qualities）→ 列表行音质小标，只标最高档：
     * 协议键 low/standard/high/super 依次对应 128k/320k/无损/超高，
     * 小标口径只用真规格词 FLAC/320k/128k（super 也只标 FLAC，不冒充母带）。
     * 插件没给 qualities、或给了认不出的键 → 返回 null 不显示，绝不硬凑。
     * （实测：元力KW、bilibili 的搜索条目都不带 qualities，这种歌就是不显示。）
     */
    private static String qualityTagOf(JsonObject item) {
        try {
            if (!item.has("qualities") || item.get("qualities").isJsonNull()) return null;
            JsonElement q = item.get("qualities");
            boolean has128 = false, has320 = false, hasFlac = false;
            List<String> keys = new ArrayList<>();
            if (q.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : q.getAsJsonObject().entrySet()) {
                    JsonElement v = e.getValue();
                    if (v == null || v.isJsonNull()) continue;
                    if (v.isJsonPrimitive() && v.getAsString().isEmpty()) continue;
                    keys.add(e.getKey());
                }
            } else if (q.isJsonArray()) {
                for (JsonElement el : q.getAsJsonArray()) {
                    if (el != null && el.isJsonPrimitive()) keys.add(el.getAsString());
                }
            }
            for (String raw : keys) {
                // 键名先归一：hi-res/hi_res 都按 hires 认，别因分隔符漏标
                String k = raw == null ? "" : raw.toLowerCase().replace("-", "").replace("_", "");
                if (k.contains("flac") || k.contains("hires") || k.contains("24bit")
                        || k.equals("high") || k.equals("super")) hasFlac = true;
                else if (k.contains("320") || k.equals("standard")) has320 = true;
                else if (k.contains("128") || k.equals("low")) has128 = true;
            }
            if (hasFlac) return "FLAC";
            if (has320) return "320k";
            if (has128) return "128k";
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 插件 duration 三种形态都要认：数字（多为秒；毫秒级 >=100000 直接用）、
     * 数字字符串、以及 "mm:ss"/"hh:mm:ss" 字符串——哔哩哔哩搜索接口给的就是
     * "3:45" 这种字符串，老写法 getAsLong 直接抛异常被吞成 0，播放页时长恒 00:00。
     * 源没给/给不明白就保持 0，不编造。
     */
    private long toMillis(JsonObject item) {
        try {
            if (!item.has("duration") || item.get("duration").isJsonNull()) return 0;
            JsonElement el = item.get("duration");
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                String s = el.getAsString().trim();
                if (s.isEmpty()) return 0;
                if (s.contains(":")) {
                    long totalSec = 0;
                    for (String p : s.split(":")) totalSec = totalSec * 60 + Long.parseLong(p.trim());
                    return totalSec * 1000;
                }
                long d = Long.parseLong(s);
                return d < 100000 ? d * 1000 : d;
            }
            long d = el.getAsLong();
            return d < 100000 ? d * 1000 : d;
        } catch (Exception e) {
            return 0;
        }
    }

    private JsonArray toArray(String json) {
        try {
            json = json.trim();
            if (json.startsWith("[")) return JsonParser.parseString(json).getAsJsonArray();
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (obj.has("data") && obj.get("data").isJsonArray()) return obj.getAsJsonArray("data");
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String optStr(JsonObject obj, String name) {
        try {
            if (obj.has(name) && !obj.get(name).isJsonNull()) return obj.get(name).getAsString();
        } catch (Exception ignored) {
        }
        return "";
    }

    private String firstNonEmpty(String... vals) {
        for (String v : vals) if (!TextUtils.isEmpty(v)) return v;
        return "";
    }

    /**
     * 插件网络出口：复用 quickjs 模块的 Connect（壳子 OkHttp，吃代理/DoH/节点定向），不直连。
     * 返回 {status, headers, text} 的 JSON；失败返回 {error}。
     */
    private String nativeRequest(String method, String url, String optsJson) {
        JSONObject out = new JSONObject();
        try {
            Req req = Req.objectFrom(buildReqJson(method, optsJson));
            try (Response res = Connect.to(url, req).execute()) {
                byte[] bytes = res.body() != null ? res.body().bytes() : new byte[0];
                out.put("status", res.code());
                JSONObject headers = new JSONObject();
                for (Map.Entry<String, List<String>> e : res.headers().toMultimap().entrySet()) {
                    List<String> vals = e.getValue();
                    if (vals != null && !vals.isEmpty()) headers.put(e.getKey(), vals.get(vals.size() - 1));
                }
                out.put("headers", headers);
                out.put("text", new String(bytes, req.getCharset()));
            }
        } catch (Throwable t) {
            try {
                out.put("error", String.valueOf(t.getMessage()));
            } catch (Exception ignored) {
            }
        }
        return out.toString();
    }

    /**
     * 把垫片的 {headers, body, timeout} 转成 quickjs 模块 Req bean 的 JSON。
     * Connect 只认 GET/POST/HEAD：PUT/DELETE/PATCH 统一按 POST 发（body 保留），
     * 与 Req.normalizeBody 的归一思路一致。
     */
    private String buildReqJson(String method, String optsJson) {
        JSONObject req = new JSONObject();
        try {
            JSONObject opts = new JSONObject(optsJson);
            String m = method == null ? "GET" : method.toUpperCase();
            if (!"GET".equals(m) && !"POST".equals(m) && !"HEAD".equals(m)) m = "POST";
            req.put("method", m);
            JSONObject hs = opts.optJSONObject("headers");
            if (hs != null) req.put("headers", hs);
            if (!opts.isNull("body")) req.put("body", opts.optString("body", ""));
            long timeout = opts.optLong("timeout", 20000);
            req.put("timeout", (int) Math.max(5000, Math.min(60000, timeout)));
            req.put("redirect", 1);
        } catch (Exception e) {
            try {
                req.put("method", "GET");
            } catch (Exception ignored) {
            }
        }
        return req.toString();
    }
}
