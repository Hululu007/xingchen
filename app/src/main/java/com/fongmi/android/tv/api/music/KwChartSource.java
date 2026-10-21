package com.fongmi.android.tv.api.music;

import android.text.TextUtils;

import com.fongmi.android.tv.bean.music.MusicChart;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.music.MusicSource;
import com.fongmi.quickjs.bean.Req;
import com.fongmi.quickjs.utils.Connect;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Response;

/**
 * 酷我榜单壳内源（潇洒哥 2026-10-21 拍板：酷我榜单改壳子直连，对照报告中方案）。
 * 只供酷我榜单详情：榜单列表仍走元力KW 插件（FreeJsMusicSource.charts），
 * 列表返回的酷我榜 platform 被改写为本源的 "kw"，详情因此路由到这里，不再走插件。
 * 详情一次请求拿全榜：kbangserver ksong.s（插件 getTopListDetail 自己调的同接口，
 * 2026-10-21 本机实测：热歌榜 80 首，song_duration 全有；请求数与插件详情一次换一次）。
 * 时长只认 song_duration（秒×1000）：同条目的 duration 字段不是歌曲时长（实测 83 对 209），
 * 认错会把 3 分半的歌显示成 1 分半，绝不能用。
 * 封面：kbangserver 条目里没有 pic 类字段（2026-10-21 实测 musiclist 条目无 pic/img/cover 键），
 * 插件回包同样无封面——封面由 xc-kuwo-chart Worker 在服务端补齐（kbangserver＋search API albuminfo，
 * 按 albumid 去重取专辑图，一张专辑图在 Worker 图库里缓存 24 小时；榜单排名每次现取、不缓存）。
 * Worker 单次调用只补一片（40 首）：Cloudflare 免费版单次调用子请求上限 50，80 首整榜一次补会超限
 * （线上实测只剩 20 首有图），故手机侧两片并行各取 40 首再合并；Worker 挂了回落 kbangserver 直连
 * （时长齐、封面素图），一片失败只回落该片，绝不崩、绝不编假图。
 * 冷启动优化（2026-10-21，用户嫌冷图库 8.8 秒太久）：详情页不再等整榜——先直连快出文字＋时长，
 * 封面按片陆续补（先回的片先上图，不等另一片）；榜单列表页后台预热详情图库（预热必须手机侧发，
 * 才热到用户那个 colo；我方机器预热热不到用户 colo），点进详情时多半已经热了。
 * 分片层统一：详情／预览／预热共用同一批分片请求，在途的分片不重复发（FutureTask＋putIfAbsent）；
 * 预热只发裸 HTTP 读完丢弃（不占取片池、不挡详情），1 小时内不重复预热同一榜。
 * albumId 拼图不成立（专辑图 id 与 albumid 无规律可推，图 id 只能从服务端回包里拿）；
 * www 侧 musicList＋csrf 舞蹈不通（首页拿不到 kw_token，自造 token 也被判 illegal），故不走这两条路。
 * 条目 id 保持纯数字 rid、rawJson 的 _item 拼成插件同形（id/title/artist/album/albumId/artistId），
 * 曲目 platform 仍是 freejs、_plugin 戳仍是元力KW 插件地址——播放/歌词仍走插件原链，
 * 本源的 playUrl/lyric 永远不会被路由到（返回 null 兜底）。
 * 全部方法阻塞，只能在子线程调。
 */
public class KwChartSource implements MusicSource {

    /** 榜单路由键：只用于 MusicChart.platform，不与曲目 platform 混用 */
    public static final String PLATFORM = "kw";

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final int TIMEOUT = 20000;
    private static final String REFERER = "https://www.kuwo.cn/";

    /** 酷我榜单封面补齐 Worker（2026-10-21 上线）：服务端聚合封面再回手机，照瓜子服务器聚合的路子 */
    private static final String WORKER_BASE = "https://xc-kuwo-chart.1372659842.workers.dev/kuwo-chart";
    /** Worker 单次调用只补一片：Cloudflare 免费版单次调用子请求上限 50，80 首整榜一次补会超限被砍（线上实测只剩 20 首有图） */
    private static final int WORKER_SLICE = 40;

    /** 元力KW 预置地址：启用插件里找不到酷我时 _plugin 戳的回落值（此时桥对不上，取链走既有失败路径） */
    private static final String FALLBACK_PLUGIN_URL =
            "https://cdn.jsdelivr.net/gh/qwerwhr/musicfree-plugins@master/%E5%85%83%E5%8A%9BKW.js";

    /** 分片取片池：详情／预览共用，守护线程，不持进程；取片是纯网络等待，4 线程够用 */
    private static final ExecutorService FETCH_POOL = Executors.newFixedThreadPool(4, new ThreadFactory() {
        private final AtomicInteger n = new AtomicInteger(0);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "kw-chart-fetch-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    /** 预热池：只发裸 HTTP 暖 Worker 图库，与取片池分开，预热排队永远挡不住详情取片 */
    private static final ExecutorService PREWARM_POOL = Executors.newFixedThreadPool(2, new ThreadFactory() {
        private final AtomicInteger n = new AtomicInteger(0);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "kw-chart-prewarm-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    /** 在途分片：键 bangId|pn|plugin，详情／预览／预热同一分片只发一次请求 */
    private static final ConcurrentHashMap<String, Future<List<MusicTrack>>> FLIGHTS = new ConcurrentHashMap<>();
    /** 预热成功时间：键 bangId|pn，1 小时内不重复预热同一榜同一片 */
    private static final ConcurrentHashMap<String, Long> WARMED_AT = new ConcurrentHashMap<>();
    private static final long WARM_TTL_MS = 3600000L;

    /** 分片封面回调：每片先回先调，不等另一片；调用方自己贴到 UI 线程 */
    public interface CoverListener {
        void onShard(int pn, List<MusicTrack> shard);
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    /** 榜单列表不归本源：返回空，列表页与首页榜单卡的酷我榜仍从插件来，不重复出榜 */
    @Override
    public List<MusicSite> sites() {
        return Collections.emptyList();
    }

    /** 只供榜单详情，不进搜索横排 */
    @Override
    public List<MusicSite> searchableSites() {
        return Collections.emptyList();
    }

    @Override
    public List<MusicTrack> search(String keyword) {
        return Collections.emptyList();
    }

    @Override
    public List<MusicTrack> tracks(MusicSite site) {
        return Collections.emptyList();
    }

    @Override
    public List<MusicChart> charts(MusicSite site) {
        return Collections.emptyList();
    }

    /**
     * 酷我榜单详情：先走 Worker 分片补封面（两片并行，各 40 首），一片失败就该片回落 kbangserver 直连；
     * Worker 全挂时整榜回落 kbangserver 直连（时长齐、封面素图，与改动前一致）。
     * 时长只认 song_duration（秒×1000），封面只认 Worker 回包的 pic，不编假图。
     */
    @Override
    public List<MusicTrack> chartTracks(MusicChart chart) throws Exception {
        if (chart == null || TextUtils.isEmpty(chart.id)) return Collections.emptyList();
        String plugin = resolveKwPlugin();
        List<MusicTrack> merged = tryWorkerSlices(chart.id, plugin);
        if (merged != null && !merged.isEmpty()) return merged;
        return directFull(chart.id, plugin);
    }

    /** Worker 两片并行：pn=0/1 各 40 首，合并后顺序与整榜一致；两片都空才返回 null 走整榜直连。
     * 分片走共享在途表：详情／预览同一分片只发一次请求，详情点进来时预览在途的分片直接复用，不重取。 */
    private List<MusicTrack> tryWorkerSlices(String bangId, String plugin) {
        Future<List<MusicTrack>> f0 = sliceFuture(bangId, 0, plugin);
        Future<List<MusicTrack>> f1 = sliceFuture(bangId, 1, plugin);
        List<MusicTrack> a = getQuiet(f0);
        List<MusicTrack> b = getQuiet(f1);
        if ((a == null || a.isEmpty()) && (b == null || b.isEmpty())) return null;
        List<MusicTrack> out = new ArrayList<>();
        if (a != null && !a.isEmpty()) {
            out.addAll(a);
        } else {
            out.addAll(safeDirectSlice(bangId, 0, plugin));
        }
        if (b != null && !b.isEmpty()) {
            out.addAll(b);
        } else {
            out.addAll(safeDirectSlice(bangId, 1, plugin));
        }
        return out.isEmpty() ? null : out;
    }

    /** 详情首屏直连：kbangserver 一次拿 80 首文字＋时长（无封面），快，不等 Worker；_plugin 戳内部解析 */
    public List<MusicTrack> directTracks(String bangId) throws Exception {
        return directFull(bangId, resolveKwPlugin());
    }

    /** 只启动分片不等待：详情页直连前先调，让 Worker 分片与直连并行跑，封面齐全时间≈两者取大而非相加；
     * 已在途的分片直接复用，不重复发。 */
    public void startSlices(String bangId) {
        if (bangId == null || bangId.isEmpty()) return;
        String plugin = resolveKwPlugin();
        sliceFuture(bangId, 0, plugin);
        sliceFuture(bangId, 1, plugin);
    }

    /** 详情封面陆续补：两片各起一个独立守护等待线程——谁先回谁先回调，互不等；
     * 某片失败跳过该片（封面留素图）。调用方可在任意线程调（本方法不阻塞），回调里自己贴到 UI 线程；
     * 已预热的分片 get 立刻返回。等待线程是守护线程，不持进程；Activity 销毁后回调里的旧页守卫仍起作用。 */
    public void fetchCovers(String bangId, CoverListener listener) {
        if (bangId == null || bangId.isEmpty() || listener == null) return;
        String plugin = resolveKwPlugin();
        startShardWaiter(bangId, 0, plugin, listener);
        startShardWaiter(bangId, 1, plugin, listener);
    }

    /** 单片独立等待：只等自己那片，回了立刻回调，不等另一片；失败／空静默跳过 */
    private void startShardWaiter(final String bangId, final int pn, final String plugin, final CoverListener listener) {
        final Future<List<MusicTrack>> f = sliceFuture(bangId, pn, plugin);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                List<MusicTrack> shard = getQuiet(f);
                if (shard != null && !shard.isEmpty()) listener.onShard(pn, shard);
            }
        }, "kw-chart-cover-" + pn);
        t.setDaemon(true);
        t.start();
    }

    /** 列表页后台预热：不阻塞调用方，只发裸 HTTP 把 Worker 图库热好（结果丢弃，不解析、不占取片池）。
     * 预热必须手机侧发才热到用户那个 colo；1 小时内同一榜同一片不重复发，失败静默（详情回落兜底）。 */
    public void prewarm(String bangId) {
        if (bangId == null || bangId.isEmpty()) return;
        final String id = bangId;
        for (int pn = 0; pn < 2; pn++) {
            final int slice = pn;
            final String warmKey = id + "|" + slice;
            Long at = WARMED_AT.get(warmKey);
            if (at != null && System.currentTimeMillis() - at < WARM_TTL_MS) continue;
            PREWARM_POOL.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        String body = httpGet(WORKER_BASE + "?id=" + enc(id) + "&pn=" + slice + "&rn=" + WORKER_SLICE);
                        if (body != null && !body.isEmpty()) WARMED_AT.put(warmKey, System.currentTimeMillis());
                    } catch (Exception ignored) {
                    }
                }
            });
        }
    }

    /** 共享分片请求：同一 bangId|pn|plugin 在途只发一次，后来者复用同一 Future；跑完即从表里摘掉。
     * 取片任务先于等待者提交（FutureTask 先入表再执行），等待者只等已提交的任务，不死锁。 */
    private Future<List<MusicTrack>> sliceFuture(final String bangId, final int pn, final String plugin) {
        final String key = bangId + "|" + pn + "|" + plugin;
        Future<List<MusicTrack>> existing = FLIGHTS.get(key);
        if (existing != null) return existing;
        synchronized (FLIGHTS) {
            Future<List<MusicTrack>> cur = FLIGHTS.get(key);
            if (cur != null) return cur;
            final FutureTask<List<MusicTrack>> task = new FutureTask<>(new Callable<List<MusicTrack>>() {
                @Override
                public List<MusicTrack> call() throws Exception {
                    return workerSlice(bangId, pn, plugin);
                }
            });
            FLIGHTS.put(key, task);
            FETCH_POOL.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        task.run();
                        List<MusicTrack> r = task.get();
                        if (r != null && !r.isEmpty()) WARMED_AT.put(bangId + "|" + pn, System.currentTimeMillis());
                    } catch (Exception ignored) {
                    } finally {
                        FLIGHTS.remove(key, task);
                    }
                }
            });
            return task;
        }
    }

    /** 等分片：失败／空都算该片无封面（不抛，调用方按回落处理）；get 本身无超时，Worker HTTP 自带 20 秒超时 */
    private static List<MusicTrack> getQuiet(Future<List<MusicTrack>> f) {
        if (f == null) return Collections.emptyList();
        try {
            List<MusicTrack> r = f.get();
            return r == null ? Collections.emptyList() : r;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /** Worker 单片：kbangserver 该片＋服务端补封面，空片算失败以便回落 */
    private List<MusicTrack> workerSlice(String bangId, int pn, String plugin) throws Exception {
        String url = WORKER_BASE + "?id=" + enc(bangId) + "&pn=" + pn + "&rn=" + WORKER_SLICE;
        JsonObject root = parse(httpGet(url));
        List<MusicTrack> tracks = tracksFromRoot(root, plugin);
        if (tracks.isEmpty()) throw new Exception("empty worker slice");
        return tracks;
    }

    /** 直连单片：kbangserver 该片，无封面字段，封面留空落素图 */
    private List<MusicTrack> safeDirectSlice(String bangId, int pn, String plugin) {
        try {
            return directSlice(bangId, pn, plugin);
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private List<MusicTrack> directSlice(String bangId, int pn, String plugin) throws Exception {
        String url = kbangUrl(bangId, pn, WORKER_SLICE);
        JsonObject root = parse(httpGet(url));
        return tracksFromRoot(root, plugin);
    }

    /** 整榜直连（既有行为）：kbangserver 一次拿 80 首，时长齐、封面素图 */
    private List<MusicTrack> directFull(String bangId, String plugin) throws Exception {
        String url = kbangUrl(bangId, 0, 80);
        JsonObject root = parse(httpGet(url));
        return tracksFromRoot(root, plugin);
    }

    private String kbangUrl(String bangId, int pn, int rn) {
        return "http://kbangserver.kuwo.cn/ksong.s?from=pc&fmt=json&pn=" + pn + "&rn=" + rn
                + "&type=bang&data=content&id=" + enc(bangId)
                + "&show_copyright_off=0&pcmp4=1&isbang=1&userid=0&httpStatus=1";
    }

    /** kbangserver 回包拼曲目：id 纯数字 rid、platform 仍是 freejs、rawJson 的 _item 拼成插件同形 */
    private List<MusicTrack> tracksFromRoot(JsonObject root, String plugin) {
        JsonArray list = root != null && root.has("musiclist") && root.get("musiclist").isJsonArray()
                ? root.getAsJsonArray("musiclist") : null;
        if (list == null) return Collections.emptyList();
        List<MusicTrack> out = new ArrayList<>();
        for (JsonElement el : list) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String rid = optStr(o, "id");
            if (rid.isEmpty()) continue;
            MusicTrack t = new MusicTrack();
            t.platform = FreeJsMusicSource.PLATFORM;
            t.id = rid;
            t.title = optStr(o, "name");
            t.artist = optStr(o, "artist");
            t.album = optStr(o, "album");
            t.duration = optSeconds(o, "song_duration") * 1000;
            t.cover = optStr(o, "pic");
            JsonObject item = new JsonObject();
            item.addProperty("id", rid);
            item.addProperty("title", t.title);
            item.addProperty("artist", t.artist);
            item.addProperty("album", t.album);
            item.addProperty("albumId", optStr(o, "albumid"));
            item.addProperty("artistId", optStr(o, "artistid"));
            JsonObject raw = new JsonObject();
            raw.addProperty("_plugin", plugin);
            raw.add("_item", item);
            t.rawJson = raw.toString();
            out.add(t);
        }
        return out;
    }

    /** 播放/歌词不走本源：曲目 platform 是 freejs，路由到 FreeJsMusicSource，返回 null 兜底 */
    @Override
    public String playUrl(MusicTrack track, int quality) {
        return null;
    }

    @Override
    public String lyric(MusicTrack track) {
        return null;
    }

    /** _plugin 戳取自当前启用的酷我插件地址（与 FreeJsMusicSource.isKuwoSite 同判据），桥按它认归属 */
    private static String resolveKwPlugin() {
        try {
            for (MusicSite s : MusicSourceManager.get().enabledSitesOfFormat(MusicSourceManager.FORMAT_FREEJS)) {
                if (FreeJsMusicSource.isKuwoSite(s)) return s.srcUrl;
            }
        } catch (Throwable ignored) {
        }
        return FALLBACK_PLUGIN_URL;
    }

    /* ---------------- 网络（与 QqChartSource 同出口：quickjs Connect 吃代理/DoH/节点定向） ---------------- */

    private static String httpGet(String url) throws Exception {
        Req req = req(url);
        try (Response res = Connect.to(url, req).execute()) {
            if (!res.isSuccessful()) throw new Exception("http " + res.code());
            byte[] bytes = res.body() != null ? res.body().bytes() : new byte[0];
            return new String(bytes, req.getCharset());
        }
    }

    private static Req req(String url) throws Exception {
        JSONObject reqJson = new JSONObject();
        reqJson.put("method", "GET");
        JSONObject headers = new JSONObject();
        headers.put("User-Agent", UA);
        headers.put("Referer", REFERER);
        reqJson.put("headers", headers);
        reqJson.put("timeout", TIMEOUT);
        reqJson.put("redirect", 1);
        return Req.objectFrom(reqJson.toString());
    }

    private static JsonObject parse(String text) throws Exception {
        if (text == null) throw new Exception("empty body");
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }

    private static String optStr(JsonObject obj, String name) {
        if (obj == null || !obj.has(name) || obj.get(name).isJsonNull()) return "";
        try {
            return obj.get(name).getAsString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** 秒数字段：字符串/数字两形态都认，解析失败为 0，不编造 */
    private static long optSeconds(JsonObject obj, String name) {
        if (obj == null || !obj.has(name) || obj.get(name).isJsonNull()) return 0;
        try {
            JsonElement el = obj.get(name);
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                return Long.parseLong(el.getAsString().trim());
            }
            return el.getAsLong();
        } catch (Exception e) {
            return 0;
        }
    }
}
