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
import com.fongmi.quickjs.utils.Async;
import com.fongmi.quickjs.utils.Connect;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import okhttp3.Response;

/**
 * FreeJS（MusicFree 插件）桥：一期音乐源引擎。
 * 每个插件独占一个 QuickJSContext（单线程串行），新 loader 分支，不走点播 Spider。
 * 垫片见 quickjs 模块 assets/js/lib/freejs.js（CommonJS + fetch/axios/he/dayjs 最小实现，
 * crypto-js / cheerio 由宿主注入原文）。
 * 插件网络经 __freejsRequest 同步走壳子 OkHttp，吃代理/DoH/节点定向，不直连。
 */
public class FreeJsBridge {

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
        }).get();
    }

    private void createCtx() {
        try {
            QuickJSLoader.init();
        } catch (Throwable ignored) {
        }
        ctx = QuickJSContext.create();
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
        JSObject module = (JSObject) ctx.getProperty(ctx.getGlobalObject(), "module");
        exports = (JSObject) ctx.getProperty(module, "exports");
        if (exports == null) throw new IllegalStateException("插件没有导出 module.exports");
    }

    private String prop(String name) {
        try {
            Object v = ctx.getProperty(exports, name);
            return v == null ? "" : String.valueOf(v);
        } catch (Exception e) {
            return "";
        }
    }

    /** 读插件元数据回填 site（platform/version/author/srcUrl） */
    private void readMeta() {
        String platform = prop("platform");
        String version = prop("version");
        String author = prop("author");
        String srcUrl = prop("srcUrl");
        if (!TextUtils.isEmpty(platform)) site.name = platform;
        if (!TextUtils.isEmpty(version)) site.version = version;
        if (!TextUtils.isEmpty(srcUrl)) site.srcUrl = srcUrl;
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
        }).get();
    }

    private Object call(String func, Object... args) throws Exception {
        return submit(() -> Async.run(exports, func, args)).get().get();
    }

    private String toJson(Object result) throws Exception {
        if (result == null) return null;
        if (result instanceof JSObject) return submit(() -> ((JSObject) result).stringify()).get();
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

    /** getMediaSource(musicItem, quality)：quality = low/standard/high/super */
    public MusicMediaSource getMediaSource(MusicTrack track, MusicQuality quality) throws Exception {
        JsonObject item = unwrapItem(track);
        if (item == null) return null;
        Object arg = submit(() -> ctx.parse(item.toString())).get();
        Object result = call("getMediaSource", arg, quality.getKey());
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
        Object arg = submit(() -> ctx.parse(item.toString())).get();
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
        }
        return TextUtils.isEmpty(lyric.rawLrc) && TextUtils.isEmpty(lyric.translation) ? null : lyric;
    }

    /** getTopLists()：可选，插件有就接 */
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
            MusicChart c = new MusicChart();
            c.platform = FreeJsMusicSource.PLATFORM;
            c.id = key() + "|" + optStr(obj, "id");
            c.name = optStr(obj, "title");
            charts.add(c);
        }
        return charts;
    }

    /** getTopListDetail(id)：可选，返回榜单曲目 */
    public MusicSearchResult getTopListDetail(String listId) throws Exception {
        Object result = call("getTopListDetail", listId);
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
            }).get();
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
        t.cover = firstNonEmpty(optStr(item, "artwork"), optStr(item, "cover"), optStr(item, "pic"));
        t.duration = toMillis(item);
        t.platform = FreeJsMusicSource.PLATFORM;
        JsonObject wrap = new JsonObject();
        wrap.addProperty("_plugin", key());
        wrap.add("_item", item);
        t.rawJson = wrap.toString();
        return t;
    }

    /** 插件 duration 多为秒；毫秒级（>=100000）直接用 */
    private long toMillis(JsonObject item) {
        try {
            if (!item.has("duration")) return 0;
            long d = item.get("duration").getAsLong();
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
