package com.fongmi.android.tv.api;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.collection.ArrayMap;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.hk.HkDetail;
import com.fongmi.android.tv.api.hk.HkDetailBridge;
import com.fongmi.android.tv.api.hk.HkPlay;
import com.fongmi.android.tv.api.hk.HkRouter;
import com.fongmi.android.tv.api.hk.HkRuleManager;
import com.fongmi.android.tv.api.loader.BaseLoader;
import com.fongmi.android.tv.api.loader.PyLoader;
import com.fongmi.android.tv.clash.SourceProxyPolicy;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.player.Source;
import com.fongmi.android.tv.read.ReadingGate;
import com.fongmi.android.tv.read.ReadingSession;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.PushNameClean;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Sniffer;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.web.WebHomeInlineVodStore;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.SourceGate;
import com.github.catvod.utils.Prefers;
import com.github.catvod.utils.Util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.Response;

public class SiteApi {

    public static final String PUSH = "push_agent";

    public static String call(@NonNull Site site, @NonNull ArrayMap<String, String> params) throws IOException {
        if (!site.getExt().isEmpty()) params.put("extend", site.getExt());
        Call call = site.getExt().length() <= 1000 ? OkHttp.newCall(site.getApi(), site.getHeader(), params) : OkHttp.newCall(site.getApi(), site.getHeader(), OkHttp.toBody(params));
        try (Response response = call.execute()) {
            return response.body().string();
        }
    }

    private static boolean isSpider(@NonNull String key, @NonNull Site site) {
        return site.getType() == 3 || key.startsWith("py_");
    }

    /**
     * 源归属打点口径：与 BaseLoader.isPy/isJs 对齐用 contains 判（id 用 site key）。
     * 旧实现用 endsWith：api 带参数/不以 .py 结尾的 PY 源判不出种类、根本不打点，
     * py 侧问代理口时无归属、无日志、静默直连（真机日志 via=py 全天 0 条即此故）。
     * 再兜一层：key 以 py_ 开头也算 py（与 isSpider 的口径一致）。
     */
    static String proxyKindOf(Site site) {
        String api = site == null ? null : site.getApi();
        if (api == null) return null;
        String a = api.toLowerCase();
        if (a.contains(".py")) return SourceProxyPolicy.KIND_PY;
        if (a.contains(".js") || a.contains(".wv")) return SourceProxyPolicy.KIND_JS;
        String key = site.getKey();
        if (key != null && key.startsWith("py_")) return SourceProxyPolicy.KIND_PY;
        return SourceProxyPolicy.KIND_VOD;
    }

    /** 包一层源归属打点：当前线程的 spider 同步请求走按源定向决策，finally 里清掉。 */
    private static String marked(Site site, Callable<String> call) throws Exception {
        String kind = proxyKindOf(site);
        if (kind == null) return call.call();
        SourceGate.mark(kind, site.getKey());
        try {
            return call.call();
        } finally {
            SourceGate.clear();
        }
    }



    private static String ac(int type) {
        return type == 0 ? "videolist" : "detail";
    }

    private static final long DETAIL_CACHE_TTL = 5 * 60 * 1000;
    private static final ConcurrentHashMap<String, DetailCacheEntry> detailCache = new ConcurrentHashMap<>();

    private static class DetailCacheEntry {
        final long time;
        final String detail;

        DetailCacheEntry(long time, String detail) {
            this.time = time;
            this.detail = detail;
        }

        boolean fresh() {
            return System.currentTimeMillis() - time < DETAIL_CACHE_TTL;
        }
    }

    public static void clearDetailCache() {
        detailCache.clear();
    }

    public static void clearDetailCache(String key) {
        if (key != null) detailCache.remove(key);
    }

    /**
     * py 首页结果短时内存缓存：key = 源 key + 脚本文件戳（路径#mtime/size）。
     * 命中直接返回，不跑 Python；TTL 3 分钟。只做内存、不落盘。
     * 失效：删插件（PyLoader 文件监听回调）/换配置（VodConfig.clear）清全部；
     * 下拉刷新走 forceRefresh 强制重跑并刷新缓存；换脚本文件戳变化 key 自动失效。
     */
    private static final long HOME_CACHE_TTL = 3 * 60 * 1000;
    private static final ConcurrentHashMap<String, HomeCacheEntry> homeCache = new ConcurrentHashMap<>();

    private static class HomeCacheEntry {
        final long time;
        final String json;

        HomeCacheEntry(long time, String json) {
            this.time = time;
            this.json = json;
        }

        boolean fresh() {
            return System.currentTimeMillis() - time < HOME_CACHE_TTL;
        }
    }

    /**
     * 预热缓存：与普通首页缓存同口径（key=源 key|脚本戳），但走独立通道写入、
     * TTL 更长（HomePrewarm.PREWARM_CACHE_TTL）。命中即晋升为普通首页缓存。
     * 失效红线与普通缓存一致：删插件/换配置清全部，下拉刷新按源清。
     */
    private static final ConcurrentHashMap<String, HomeCacheEntry> prewarmCache = new ConcurrentHashMap<>();

    static void putPrewarmCache(String key, String json) {
        if (key != null && json != null && !json.isEmpty())
            prewarmCache.put(key, new HomeCacheEntry(System.currentTimeMillis(), json));
    }

    static String getPrewarmCache(String key) {
        if (key == null) return null;
        HomeCacheEntry e = prewarmCache.get(key);
        if (e == null) return null;
        if (System.currentTimeMillis() - e.time >= HomePrewarm.PREWARM_CACHE_TTL) {
            prewarmCache.remove(key, e);
            return null;
        }
        return e.json;
    }

    static void dropPrewarmCache(String key) {
        if (key == null) return;
        for (String k : prewarmCache.keySet()) {
            if (k.startsWith(key + "|")) prewarmCache.remove(k);
        }
    }

    /** 按完整 key 精确删一条（下拉刷新用，传的是 key|戳 的完整预热 key）。 */
    static void dropPrewarmKey(String fullKey) {
        if (fullKey != null) prewarmCache.remove(fullKey);
    }

    /** 某源是否有新鲜的普通首页缓存（预热队列用它判断"已经暖了"）。 */
    static boolean homeCacheHasFresh(String siteKey) {
        if (siteKey == null) return false;
        for (Map.Entry<String, HomeCacheEntry> e : homeCache.entrySet()) {
            if (e.getKey().startsWith(siteKey + "|") && e.getValue().fresh()) return true;
        }
        return false;
    }

    public static void clearHomeCache() {
        homeCache.clear();
        prewarmCache.clear();
        // 首页内容走 site.recent().spider()，残留就是 BaseLoader 里缓存的旧 Spider：
        // 删插件/换配置后不清它，首页就一直挂着已删插件的旧内容。
        try {
            BaseLoader.get().clear();
        } catch (Throwable ignored) {
        }
    }

    public static void clearHomeCache(String key) {
        if (key == null) {
            clearHomeCache();
            return;
        }
        for (String k : homeCache.keySet()) {
            if (k.startsWith(key + "|")) homeCache.remove(k);
        }
        dropPrewarmCache(key);
        try {
            BaseLoader.get().removePySpider(key);
            BaseLoader.get().removeJsSpider(key);
        } catch (Throwable ignored) {
        }
    }

    /** py 蜘蛛判别：与 proxyKindOf 的口径一致。 */
    private static boolean isPySpiderSite(@NonNull Site site) {
        String key = site.getKey();
        String api = site.getApi();
        return (key != null && key.startsWith("py_")) || (api != null && api.contains(".py"));
    }

    @NonNull
    public static Result homeContent(@NonNull Site site) throws Exception {
        return homeContent(site, false);
    }

    /**
     * 首页 spider 双路并发的子任务调度。
     * 以前子任务直接复用 Task.executor()（固定 5 线程）：外层任务本身也跑在同一池里，
     * 再 submit 两个子任务并用无超时的 get() 死等。切源时只取消外层，子任务继续占着
     * 池线程跑到网络超时；连切两次就把池占满，新源的子任务排不上队——表现就是
     * “非等旧源加载出来或失败，才给切别的源”。现在子任务走独立池，并按“代次”登记，
     * 切源时直接作废旧代次的子任务（结果回来也丢弃，请求能断就断）。
     */
    private static final ExecutorService spiderHomePool = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "spider-home");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicInteger homeLoadGen = new AtomicInteger(0);
    private static final ConcurrentHashMap<Integer, CopyOnWriteArrayList<Future<?>>> homeLoadFutures = new ConcurrentHashMap<>();
    /**
     * spider-home 子任务硬上限（秒）。外层 30 秒超时正常先触发兜底；这里防 get() 无限
     * 挂住池线程——池一共 8 个线程，每源占 2 个，挂住几个切几次源就排不上队了。
     */
    private static final long HOME_SUB_TIMEOUT_SECONDS = 35;

    /**
     * 作废所有在途的首页 spider 子任务，并开启一个新代次。
     * 由 SiteViewModel 在发起新的 RESULT 任务前调用（切源/刷新/进分类/进详情）；
     * ViewModel 侧已有代次校验，旧结果回来直接丢弃。
     */
    public static void cancelHomeLoads() {
        int gen = homeLoadGen.incrementAndGet();
        // 代次推送到 Python 透传层（修 3）：过期代次的首页调用在入口直接返回空 JSON，
        // 不再进脚本——被取消/排队中的旧任务不再打站点（僵尸加载止血）。
        com.fongmi.chaquo.Spider.setHomeGeneration(gen);
        for (Map.Entry<Integer, CopyOnWriteArrayList<Future<?>>> entry : homeLoadFutures.entrySet()) {
            if (entry.getKey() < gen) {
                for (Future<?> f : entry.getValue()) f.cancel(true);
                homeLoadFutures.remove(entry.getKey());
            }
        }
    }

    private static void trackHomeFuture(int gen, Future<?> future) {
        homeLoadFutures.computeIfAbsent(gen, k -> new CopyOnWriteArrayList<>()).add(future);
    }

    private static void untrackHomeFuture(int gen, Future<?> future) {
        CopyOnWriteArrayList<Future<?>> list = homeLoadFutures.get(gen);
        if (list != null) {
            list.remove(future);
            if (list.isEmpty()) homeLoadFutures.remove(gen, list);
        }
    }

    /**
     * py 首页取数：把当前代次带进 Python 透传层。过期代次的调用在入口直接返回空 JSON
     * 不再进脚本（修 3）；非 py 源（js 等）走原路径，不受代次影响。
     */
    private static String pyHomeContent(Spider spider, int gen) throws Exception {
        if (spider instanceof com.fongmi.chaquo.Spider)
            return ((com.fongmi.chaquo.Spider) spider).homeContent(true, gen);
        return spider.homeContent(true);
    }

    private static String pyHomeVideoContent(Spider spider, int gen) throws Exception {
        if (spider instanceof com.fongmi.chaquo.Spider)
            return ((com.fongmi.chaquo.Spider) spider).homeVideoContent(gen);
        return spider.homeVideoContent();
    }

    @NonNull
    public static Result homeContent(@NonNull Site site, boolean forceRefresh) throws Exception {
        if (isSpider(site.getKey(), site)) {
            Spider spider = site.recent().spider();
            boolean crash = Prefers.getBoolean("crash");
            // py 首页短时缓存：命中直接返回，不跑 Python。crash 兜底分支不走缓存。
            boolean useHomeCache = isPySpiderSite(site) && !crash;
            String homeCacheKey = null;
            if (useHomeCache) {
                homeCacheKey = site.getKey() + "|" + PyLoader.scriptStamp(site.getApi());
                if (!forceRefresh) {
                    HomeCacheEntry cached = homeCache.get(homeCacheKey);
                    if (cached != null && cached.fresh()) return Result.fromJson(cached.json);
                }
            }
            // 首页预热 single-flight：预热缓存命中即晋升为普通缓存直接返回；
            // 切到在途预热源时接上等完（不另起一趟，更不掐了重跑）。
            if (!crash) {
                String pwKey = HomePrewarm.cacheKey(site);
                if (forceRefresh) {
                    dropPrewarmKey(pwKey);
                } else {
                    String pwJson = getPrewarmCache(pwKey);
                    if (pwJson != null) {
                        Result pre = Result.fromJson(pwJson);
                        if (useHomeCache) homeCache.put(homeCacheKey, new HomeCacheEntry(System.currentTimeMillis(), pwJson));
                        return pre;
                    }
                    java.util.concurrent.CompletableFuture<String> inflight = HomePrewarm.pollInflight(site.getKey());
                    if (inflight != null) {
                        try {
                            String ij = inflight.get(HomePrewarm.PREWARM_JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                            if (ij != null && !ij.isEmpty()) {
                                Result ir = Result.fromJson(ij);
                                if (!ir.getList().isEmpty()) {
                                    if (useHomeCache) homeCache.put(homeCacheKey, new HomeCacheEntry(System.currentTimeMillis(), ij));
                                    return ir;
                                }
                            }
                        } catch (Throwable ignored) {
                            // 落回正常前台链：自己跑一趟。
                        }
                    }
                }
            }
            String home;
            String video;
            int gen = homeLoadGen.get();
            long loadStart = System.currentTimeMillis(); // 耗时记账：只统计真正跑了脚本的加载
            if (crash) {
                home = "";
                video = "";
            } else {
                Future<String> fHome = spiderHomePool.submit(() -> marked(site, () -> pyHomeContent(spider, gen)));
                trackHomeFuture(gen, fHome);
                Future<String> fVideo = spiderHomePool.submit(() -> marked(site, () -> pyHomeVideoContent(spider, gen)));
                trackHomeFuture(gen, fVideo);
                try {
                    home = fHome.get(HOME_SUB_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    video = fVideo.get(HOME_SUB_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    // 子任务超时：外层按超时处理（空列表）；这里先把两个子任务都掐掉，
                    // 不让它们继续占池线程、继续打站点——否则用户一重试就再叠一层。
                    fHome.cancel(true);
                    fVideo.cancel(true);
                    throw e;
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof Exception) throw (Exception) cause;
                    else throw new RuntimeException(cause);
                } finally {
                    untrackHomeFuture(gen, fHome);
                    untrackHomeFuture(gen, fVideo);
                }
            }
            Prefers.put("crash", false);
            SpiderDebug.log("homeVideo", video);
            Result result = Result.fromJson(home);
            List<Vod> list = Result.fromJson(video).getList();
            if (!list.isEmpty()) result.setList(list);
            setTypes(site, result);
            // 只缓存仍是当前代次的结果：被作废的旧加载即使跑完回来，也不许覆盖新加载的缓存。
            if (useHomeCache && gen == homeLoadGen.get())
                homeCache.put(homeCacheKey, new HomeCacheEntry(System.currentTimeMillis(), result.toString()));
            // 耗时记账：只记真正跑了脚本的成功加载；crash 兜底（没跑脚本）不记，避免 0ms 污染账本。
            if (!crash) HomePrewarm.recordCost(site.getKey(), System.currentTimeMillis() - loadStart);
            return result;
        } else if (site.getType() == 4) {
            ArrayMap<String, String> params = new ArrayMap<>();
            params.put("filter", "true");
            String homeContent = call(site.fetchExt(), params);
            SpiderDebug.log("home", homeContent);
            Result result = Result.fromJson(homeContent);
            setTypes(site, result);
            return result;
        } else {
            try (Response response = OkHttp.newCall(site.getApi(), site.getHeader()).execute()) {
                String homeContent = response.body().string();
                SpiderDebug.log("home", homeContent);
                Result result = Result.fromType(site.getType(), homeContent);
                fetchPic(site, result);
                setTypes(site, result);
                return result;
            }
        }
    }

    @NonNull
    /**
     * 首页预热专用取数：独立通道，不套前台口径——
     * ① 不读不写 homeLoadGen、不登记 homeLoadFutures、不调 cancelHomeLoads（前台作废射程外）；
     * ② Python 代次传 -1（透传层不校验），不受前台代次推送影响；
     * ③ 单源超时按账自适应（HomePrewarm.timeoutFor：历史×1.5＋10s 余量，60–300s 钳制，
     *    无账新源默认 120s）：慢但健康的源（带登记握手、第一次十几秒）正是预热要救的，
     *    只有真挂死才抛异常，由调用方记日志跳过、继续下一个。
     * @return 首页 JSON；失败/空抛异常或返回 null。
     */
    static String prewarmSpiderHome(@NonNull Site site, long timeoutSeconds) throws Exception {
        Spider spider = site.recent().spider();
        java.util.concurrent.Future<String> fHome = HomePrewarm.workers.submit(() -> marked(site, () -> pyHomeContent(spider, -1)));
        java.util.concurrent.Future<String> fVideo = HomePrewarm.workers.submit(() -> marked(site, () -> pyHomeVideoContent(spider, -1)));
        String home;
        String video;
        try {
            home = fHome.get(timeoutSeconds, TimeUnit.SECONDS);
            video = fVideo.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            fHome.cancel(true);
            fVideo.cancel(true);
            throw e;
        }
        SpiderDebug.log("prewarm", "done key=%s", site.getKey());
        Result result = Result.fromJson(home);
        List<Vod> list = Result.fromJson(video).getList();
        if (!list.isEmpty()) result.setList(list);
        setTypes(site, result);
        if (result.getList() == null || result.getList().isEmpty()) return null;
        return result.toString();
    }

    public static Result categoryContent(@NonNull String key, @NonNull String tid, @NonNull String page, boolean filter, @NonNull HashMap<String, String> extend) throws Exception {
        SpiderDebug.log("category", "key=%s,tid=%s,page=%s,filter=%s,extend=%s", key, tid, page, filter, extend);
        Site site = VodConfig.get().getSite(key);
        if (isSpider(key, site)) {
            String categoryContent = marked(site, () -> site.recent().spider().categoryContent(tid, page, filter, extend));
            SpiderDebug.log("category", categoryContent);
            return Result.fromJson(categoryContent);
        } else {
            ArrayMap<String, String> params = new ArrayMap<>();
            if (site.getType() == 1 && !extend.isEmpty()) params.put("f", App.gson().toJson(extend));
            if (site.getType() == 4) params.put("ext", Util.base64(App.gson().toJson(extend), Util.URL_SAFE));
            params.put("ac", ac(site.getType()));
            params.put("t", tid);
            params.put("pg", page);
            String categoryContent = call(site, params);
            SpiderDebug.log("category", categoryContent);
            return Result.fromType(site.getType(), categoryContent);
        }
    }

    // 推送标题：ed2k/磁力整串当标题会把协议头、大小、哈希全露出来（用户点名），标题应取
    // 链接里自带的文件名（ed2k 的文件名段 / 磁力的 dn 参数）；取不到才回退原串
    private static String pushTitle(String id) {
        try {
            if (id.startsWith("ed2k://")) {
                String[] parts = id.split("\\|");
                if (parts.length > 2 && !parts[2].isEmpty()) return java.net.URLDecoder.decode(parts[2], "UTF-8");
            } else if (id.startsWith("magnet:")) {
                String dn = Uri.parse(id).getQueryParameter("dn");
                if (dn != null && !dn.isEmpty()) return dn;
            }
        } catch (Exception ignored) {
        }
        return id;
    }

    @NonNull
    public static Result detailContent(@NonNull String key, @NonNull String id) throws Exception {
        return detailContent(key, id, false);
    }

    @NonNull
    public static Result detailContent(@NonNull String key, @NonNull String id, boolean forceRefresh) throws Exception {
        return detailContent(key, id, forceRefresh, true);
    }

    /**
     * @param parse 是否同步做选集展开（磁力/网盘链接展开成子选集）。
     *              详情页 UI 走 false：主数据一到先渲染，展开由界面层在后台跑完再补，
     *              免得 DHT 拉种子（最长 60 秒）拖住线路/选集上屏。
     */
    @NonNull
    public static Result detailContent(@NonNull String key, @NonNull String id, boolean forceRefresh, boolean parse) throws Exception {
        SpiderDebug.log("detail", "key=%s,id=%s", key, id);
        if (WebHomeInlineVodStore.KEY.equals(key)) return WebHomeInlineVodStore.detail(id);
        Site site = VodConfig.get().getSite(key);
        if (site.isEmpty() && PUSH.equals(key)) {
            Vod vod = new Vod();
            vod.setId(id);
            vod.setName(PushNameClean.cleanPushTitle(pushTitle(id), id));
            vod.setPlayUrl(id);
            vod.setPlayFrom(ResUtil.getString(R.string.push));
            vod.setPic(ResUtil.getString(R.string.push_image));
            vod.setFlags();
            if (parse) Source.get().parse(vod);
            PushNameClean.applyEpisodeDisplayNames(vod);
            return Result.vod(vod);
        } else if (isSpider(key, site)) {
            String cacheKey = key + "_" + id;
            if (!forceRefresh) {
                DetailCacheEntry cached = detailCache.get(cacheKey);
                if (cached != null && cached.fresh()) {
                    Result result = Result.fromJson(cached.detail);
                    result.getVod().setFlags();
                    if (parse) Source.get().parse(result.getVod());
                    return result;
                }
            }
            String detailContent = marked(site, () -> site.recent().spider().detailContent(Arrays.asList(id)));
            SpiderDebug.log("detail", detailContent);
            Result result = Result.fromJson(detailContent);
            Vod vod = result.getVod();
            if (vod == null || vod.getPlayFrom().isEmpty() || vod.getPlayUrl().isEmpty()) {
                if (site.getApi() != null && site.getApi().contains(".js")) BaseLoader.get().removeJsSpider(key);
                else BaseLoader.get().removePySpider(key);
            } else {
                detailCache.put(cacheKey, new DetailCacheEntry(System.currentTimeMillis(), detailContent));
            }
            result.getVod().setFlags();
            if (parse) Source.get().parse(result.getVod());
            return result;
        } else {
            String cacheKey = key + "_" + id;
            if (!forceRefresh) {
                DetailCacheEntry cached = detailCache.get(cacheKey);
                if (cached != null && cached.fresh()) {
                    Result result = Result.fromType(site.getType(), cached.detail);
                    result.getVod().setFlags();
                    if (parse) Source.get().parse(result.getVod());
                    return result;
                }
            }
            ArrayMap<String, String> params = new ArrayMap<>();
            params.put("ac", ac(site.getType()));
            params.put("ids", id);
            String detailContent = call(site, params);
            SpiderDebug.log("detail", detailContent);
            detailCache.put(cacheKey, new DetailCacheEntry(System.currentTimeMillis(), detailContent));
            Result result = Result.fromType(site.getType(), detailContent);
            result.getVod().setFlags();
            if (parse) Source.get().parse(result.getVod());
            return result;
        }
    }

    /**
     * CloudFront rejects requests carrying an unexpected Referer (HTTP 403).
     * The PY sets Referer to its api domain; strip it for CloudFront video URLs
     * so the request goes out clean (verified: curl with no Referer gets 200).
     * Other headers (e.g. User-Agent) are kept intact.
     */
    private static void stripRefererForCloudFront(@NonNull Result result) {
        String url = result.getUrl().v();
        if (url.isEmpty() || !url.contains("cloudfront.net")) return;
        Map<String, String> header = result.getHeader();
        if (header.isEmpty()) return;
        header.remove("Referer");
        header.remove("referer");
    }

    /**
     * 解析结果短时内存缓存：key = 源 key|flag|集 id。命中直接返回，不重跑解析。
     * 普通源 TTL 10 分钟；Emby 直链易过期缩到 3 分钟；
     * 迅雷/磁力/ed2k 类不缓存（解析交给播放器引擎，缓存解析结果无意义）。
     * 只做内存、不落盘；缓存值是解析完的 URL＋header 原样（JSON 存取，防调用方篡改）。
     */
    private static final long PLAYER_CACHE_TTL = 10 * 60 * 1000;
    private static final long PLAYER_CACHE_TTL_EMBY = 3 * 60 * 1000;
    private static final ConcurrentHashMap<String, PlayerCacheEntry> playerCache = new ConcurrentHashMap<>();

    private static class PlayerCacheEntry {
        final long time;
        final long ttl;
        final String json;

        PlayerCacheEntry(long time, long ttl, String json) {
            this.time = time;
            this.ttl = ttl;
            this.json = json;
        }

        boolean fresh() {
            return System.currentTimeMillis() - time < ttl;
        }
    }

    /** 迅雷/磁力类判别：与 VideoActivity.isThunderUrl 同口径。 */
    private static boolean isThunderId(String id) {
        if (id == null) return false;
        String lower = id.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("magnet:") || lower.startsWith("ed2k:") || lower.startsWith("thunder:");
    }

    private static boolean isEmbySite(Site site) {
        return site != null && "csp_Emby".equals(site.getApi());
    }

    /**
     * py/接口源阅读分流：只认"非视频数据"（见 {@link ReadingGate} 红线说明），
     * Emby 源直接放行（不进判别）。
     */
    private static ReadingSession divertPyReading(String key, Site site, String rawUrl) {
        try {
            if (site == null || isEmbySite(site)) return null;
            return ReadingGate.detectPy(key, site.getName(), null, null, rawUrl);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static long playerCacheTtl(Site site, String finalUrl) {
        if (isEmbySite(site)) return PLAYER_CACHE_TTL_EMBY;
        try {
            if (com.fongmi.android.tv.emby.EmbyMediaHeader.forUrl(finalUrl) != null) return PLAYER_CACHE_TTL_EMBY;
        } catch (Throwable ignored) {
        }
        return PLAYER_CACHE_TTL;
    }

    private static Result cachePlayerResult(String cacheKey, Result result, long ttl) {
        if (cacheKey != null && result != null) {
            try {
                playerCache.put(cacheKey, new PlayerCacheEntry(System.currentTimeMillis(), ttl, result.toString()));
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    @NonNull
    public static Result playerContent(@NonNull String key, @NonNull String flag, @NonNull String id) throws Exception {
        return playerContent(key, flag, id, PlayerSetting.getPlayer());
    }

    @NonNull
    public static Result playerContent(@NonNull String key, @NonNull String flag, @NonNull String id, int playerType) throws Exception {
        SpiderDebug.log("player", "key=%s,flag=%s,id=%s", key, flag, id);
        HomePrewarm.playbackActive = true; // 首页预热让路：播放/解析开始即暂停推进
        Source.get().stop();
        if (WebHomeInlineVodStore.KEY.equals(key)) return WebHomeInlineVodStore.player(flag, id);
        if (HkDetailBridge.isHkKey(key)) return hkPlayerContent(key, flag, id, playerType);
        Site site = VodConfig.get().getSite(key);
        // 解析结果缓存：迅雷/磁力类不进缓存；海阔走独立路由（hkPlayerContent）不进缓存。
        String playerCacheKey = isThunderId(id) ? null : key + "|" + flag + "|" + id;
        if (playerCacheKey != null) {
            PlayerCacheEntry cached = playerCache.get(playerCacheKey);
            if (cached != null && cached.fresh()) return Result.fromJson(cached.json);
        }
        if (isSpider(key, site)) {
            long spiderStart = System.currentTimeMillis();
            String playerContent = marked(site, () -> site.recent().spider().playerContent(flag, id, VodConfig.get().getFlags()));
            SpiderDebug.log("player", "spider done key=%s cost=%dms", key, System.currentTimeMillis() - spiderStart);
            SpiderDebug.log("player", playerContent);
            Result result = Result.fromJson(playerContent);
            if (result.getFlag().isEmpty()) result.setFlag(flag);
            // 阅读分流（漫画/小说）：原始地址串判别，命中则跳过 Source.fetch，直接改道阅读器。
            // 红线见 ReadingGate：Emby 链不进此分支（isEmbySite 在别处），判别只认"非视频数据"。
            ReadingSession reading = divertPyReading(key, site, result.getUrl() == null ? null : result.getUrl().v());
            if (reading != null) {
                result.setReadingSession(reading);
                result.setKey(key);
                SpiderDebug.log("player", "reading diverted kind=%s key=%s", reading.kind, key);
                return result;
            }
            long fetchStart = System.currentTimeMillis();
            result.setUrl(Source.get().fetch(result, playerType));
            SpiderDebug.log("player", "fetch done key=%s cost=%dms empty=%s", key, System.currentTimeMillis() - fetchStart, result.getUrl() == null || TextUtils.isEmpty(result.getUrl().v()));
            result.setHeader(site.getHeader());
            stripRefererForCloudFront(result);
            result.setKey(key);
            return cachePlayerResult(playerCacheKey, result, playerCacheTtl(site, result.getUrl().v()));
        } else if (site.getType() == 4) {
            ArrayMap<String, String> params = new ArrayMap<>();
            params.put("play", id);
            params.put("flag", flag);
            long callStart = System.currentTimeMillis();
            String playerContent = call(site, params);
            SpiderDebug.log("player", "spider done key=%s cost=%dms", key, System.currentTimeMillis() - callStart);
            SpiderDebug.log("player", playerContent);
            Result result = Result.fromJson(playerContent);
            if (result.getFlag().isEmpty()) result.setFlag(flag);
            // 阅读分流（漫画/小说）：接口源返回同样可能带漫画图片串/小说正文，判别口径与蜘蛛源一致。
            ReadingSession reading4 = divertPyReading(key, site, result.getUrl() == null ? null : result.getUrl().v());
            if (reading4 != null) {
                result.setReadingSession(reading4);
                result.setKey(key);
                SpiderDebug.log("player", "reading diverted kind=%s key=%s", reading4.kind, key);
                return result;
            }
            long fetchStart = System.currentTimeMillis();
            result.setUrl(Source.get().fetch(result, playerType));
            SpiderDebug.log("player", "fetch done key=%s cost=%dms empty=%s", key, System.currentTimeMillis() - fetchStart, result.getUrl() == null || TextUtils.isEmpty(result.getUrl().v()));
            result.setHeader(site.getHeader());
            return cachePlayerResult(playerCacheKey, result, playerCacheTtl(site, result.getUrl().v()));
        } else if (site.isEmpty() && "push_agent".equals(key)) {
            Result result = new Result();
            result.setUrl(id);
            result.setParse(0);
            result.setFlag(flag);
            result.setUrl(Source.get().fetch(result, playerType));
            // Emby 直链走 PUSH 裸 URL（详情直放/换画质/410 续播/AI 换集），不进蜘蛛：
            // 媒体请求补 X-Emby-Authorization，否则影桥前置 302→410（play-dead-diagnosis.md）。
            // 找不到归属服（磁力/txt 等）返回 null，原行为一字不变。
            String finalUrl = result.getUrl() == null ? id : result.getUrl().v();
            java.util.Map<String, String> embyHeader = com.fongmi.android.tv.emby.EmbyMediaHeader.forUrl(finalUrl);
            if (embyHeader != null) result.setHeader(embyHeader);
            SpiderDebug.log("player", result.toString());
            return cachePlayerResult(playerCacheKey, result, playerCacheTtl(site, finalUrl));
        } else {
            Result result = new Result();
            result.setUrl(id);
            result.setFlag(flag);
            result.setHeader(site.getHeader());
            result.setPlayUrl(site.getPlayUrl());
            result.setParse(Sniffer.isVideoFormat(id) && result.getPlayUrl().isEmpty() ? 0 : 1);
            result.setUrl(Source.get().fetch(result, playerType));
            SpiderDebug.log("player", result.toString());
            return cachePlayerResult(playerCacheKey, result, playerCacheTtl(site, result.getUrl().v()));
        }
    }

    /**
     * 海阔播放路由缓存。
     *
     * <p>根因：之前每次播放都 {@code HkRouter.open(title)} 全新初始化——新建 QuickJSContext、
     * 注册上百个 API、读 kv/config 文件、跑 preRule（很多规则的 preRule 会发网络请求）。
     * 点一次封面要等好几秒，大头是这里重复初始化，而不是真正的地址解析。</p>
     *
     * <p>缓存按规则名复用已初始化的 HkRouter：HkJsRuntime 的 JS 调用全部走单线程 executor
     * 串行，跨播放复用线程安全；每次取用前校验规则文件 mtime（用户改规则立即失效）
     * 与 15 分钟 TTL（preRule 里的登录态/cookie 定期刷新）。最多缓存 3 个规则，
     * 淘汰时 destroy 释放 QuickJS 上下文。</p>
     */
    private static final int HK_ROUTER_CACHE_MAX = 3;
    private static final long HK_ROUTER_CACHE_TTL_MS = 15 * 60 * 1000L;
    private static final ExecutorService HK_INIT_EXEC = Executors.newSingleThreadExecutor();
    private static final ConcurrentHashMap<String, HkRouterCacheEntry> hkRouterCache = new ConcurrentHashMap<>();

    private static class HkRouterCacheEntry {
        volatile Future<HkRouter> future;
        volatile long ruleModified;
        volatile long createdAt;
    }

    /**
     * 预热某规则的播放路由（点封面预检出直接播放地址后调用）：后台提前做
     * QuickJS 初始化 + preRule，等播放器真正调 playerContent 时缓存已就绪。
     * UI 线程调用也安全，只提交任务立即返回。
     */
    public static void warmHkRouter(String title) {
        if (title == null || title.isEmpty()) return;
        try {
            acquireHkRouterFuture(title);
        } catch (Throwable ignored) {
        }
    }

    /** 取已初始化的 HkRouter（缓存命中直接返回，未命中则同步初始化）。 */
    private static HkRouter acquireHkRouter(String title) throws Exception {
        Future<HkRouter> f = acquireHkRouterFuture(title);
        try {
            HkRouter router = f.get(30, TimeUnit.SECONDS);
            // 口令流程的一次性 input 覆盖只属于详情页那次求值，播放路由复用时清掉，
            // 防止上次残留污染本次 lazyRule 的 input。
            try {
                router.getEngine().getJsRuntime().setNextInputOverride(null);
            } catch (Throwable ignored) {
            }
            return router;
        } catch (ExecutionException ee) {
            // 初始化失败：清掉坏条目，下次重建；把原始异常抛给调用方（行为与之前一致）
            hkRouterCache.remove(title);
            Throwable c = ee.getCause();
            if (c instanceof Exception) throw (Exception) c;
            throw new RuntimeException(c);
        }
    }

    private static synchronized Future<HkRouter> acquireHkRouterFuture(String title) {
        long mtime = HkRuleManager.get().ruleModified(title);
        HkRouterCacheEntry e = hkRouterCache.get(title);
        if (e != null && e.ruleModified == mtime && !hkRouterExpired(e) && !hkRouterFailed(e)) {
            return e.future;
        }
        if (e != null) {
            hkRouterCache.remove(title);
            destroyHkRouterFuture(e.future);
        }
        // LRU 淘汰：按创建时间踢掉最老的
        while (hkRouterCache.size() >= HK_ROUTER_CACHE_MAX) {
            String oldest = null;
            long oldestAt = Long.MAX_VALUE;
            for (Map.Entry<String, HkRouterCacheEntry> en : hkRouterCache.entrySet()) {
                if (en.getValue().createdAt < oldestAt) {
                    oldestAt = en.getValue().createdAt;
                    oldest = en.getKey();
                }
            }
            if (oldest == null) break;
            HkRouterCacheEntry out = hkRouterCache.remove(oldest);
            if (out != null) destroyHkRouterFuture(out.future);
        }
        HkRouterCacheEntry ne = new HkRouterCacheEntry();
        ne.ruleModified = mtime;
        ne.createdAt = System.currentTimeMillis();
        ne.future = HK_INIT_EXEC.submit(() -> HkRouter.open(title));
        hkRouterCache.put(title, ne);
        return ne.future;
    }

    private static boolean hkRouterExpired(HkRouterCacheEntry e) {
        return System.currentTimeMillis() - e.createdAt > HK_ROUTER_CACHE_TTL_MS;
    }

    private static boolean hkRouterFailed(HkRouterCacheEntry e) {
        Future<HkRouter> f = e.future;
        if (f == null || !f.isDone() || f.isCancelled()) return false;
        try {
            f.get(1, TimeUnit.MILLISECONDS);
            return false;
        } catch (ExecutionException ee) {
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void destroyHkRouterFuture(Future<HkRouter> f) {
        if (f == null) return;
        if (!f.isDone()) {
            // 初始化还在排队/执行：cancel(false) 不中断，只防未开始的任务启动；
            // 若已在执行，完成后顺手 destroy，避免泄漏 QuickJS 上下文。
            f.cancel(false);
            HK_INIT_EXEC.submit(() -> {
                try {
                    HkRouter r = f.get(60, TimeUnit.SECONDS);
                    if (r != null) r.destroy();
                } catch (Throwable ignored) {
                }
            });
            return;
        }
        try {
            HkRouter r = f.get(1, TimeUnit.MILLISECONDS);
            if (r != null) r.destroy();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 取已初始化的 HkRouter（给详情页用）：与播放链共用 hkRouterCache，
     * 同规则同会话只初始化一次（preRule 只在首次建立时跑），不再每次进详情
     * new HkRouter + destroy。key 维度=规则名，与缓存现有设计一致，不会串规则。
     */
    public static HkRouter getHkRouter(String title) throws Exception {
        return acquireHkRouter(title);
    }

    /**
     * 海阔小程序播放（M4）：{@code hk_<规则名>} key 拦截 → {@link HkRouter#play(String)}
     * 分流链解析出真地址 → 直接组装 Result，不走 spider/嗅探。
     *
     * <p>详情页点选集（VideoActivity → SiteViewModel.playerContent → 本方法）统一走这里，
     * 因此选集、下一集、历史续播都会先经过海阔分流链。</p>
     */
    @NonNull
    private static Result hkPlayerContent(@NonNull String key, @NonNull String flag, @NonNull String id, int playerType) throws Exception {
        String title = key.substring(HkDetailBridge.KEY_PREFIX.length());
        // 路由缓存复用：不再每次播放重建 QuickJS 上下文+跑 preRule（之前点封面等几秒的主因）
        HkRouter router = acquireHkRouter(title);
        // 阅读分流（小说规则）：直接返回阅读会话，正文在阅读器里懒取，不跑 play 分流链。
        // 视频规则不受影响（type 不是"小说"）。
        if (ReadingGate.isHkNovelRule(title)) {
            Result novelResult = new Result();
            novelResult.setReadingSession(ReadingGate.fromHkNovel(title, "", null, 0));
            novelResult.setKey(key);
            novelResult.setFlag(flag);
            SpiderDebug.log("player", "hk reading diverted kind=NOVEL rule=%s", title);
            return novelResult;
        }
        HkPlay play = router.play(id);
        // 阅读分流（漫画 pics://）：命中则直接返回带会话的 Result，不再组装视频地址。
        // pics 只由 pics:// 分支产出，视频规则永远不走该分支。
        ReadingSession comic = ReadingGate.fromHkComic(title, "", "", id, play,
                play.getHeaders() != null ? play.getHeaders().get("Referer") : "");
        if (comic != null) {
            Result comicResult = new Result();
            comicResult.setReadingSession(comic);
            comicResult.setKey(key);
            comicResult.setFlag(flag);
            SpiderDebug.log("player", "hk reading diverted kind=COMIC rule=%s pics=%d", title, play.getPics().size());
            return comicResult;
        }
        // P2 多线路：按 (规则名,剧集) 记忆的线路下标取线（默认首线），
        // 该线路的地址+请求头按下标取、不跨线路合并（8.83 getPlayData/getHeaders 语义）
        int line = HkRouter.lineIndex(title, id);
        Result result = new Result();
        result.setUrl(play.getUrl(line));
        result.setParse(0);
        result.setFlag(flag);
        java.util.Map<String, String> lineHeaders = play.getHeaders(line);
        if (!lineHeaders.isEmpty()) result.setHeader(lineHeaders);
        // 规则解析出的字幕此前在 HkPlay 里带着、到这一步被丢掉（审计 P7）：
        // 照 8.83 PlayerChooser 把 subtitle 传进播放器的做法接到 Result 上。
        if (!play.getSubtitle().isEmpty()) {
            result.setSubs(java.util.List.of(com.fongmi.android.tv.bean.Sub.from(play.getSubtitle())));
        }
        result.setKey(key);
        // 磁力/电驴轻修：播放器不认这两种协议，直塞就是黑屏 0 KB/s。
        // 只拦这两类（HkRouter.isP2pPlayLink），走壳子已有的迅雷引擎边下边播；
        // 其余地址（http/m3u8 等）原样不动。失败时迅雷抛中文真错，不再黑屏无下文。
        if (HkRouter.isP2pPlayLink(play.getUrl())) {
            result.setUrl(Source.get().fetch(result, playerType));
        }
        SpiderDebug.log("player", "hk play resolved: %s", result.getUrl());
        return result;
    }

    @NonNull
    public static Result searchContent(@NonNull Site site, @NonNull String keyword, boolean quick, @NonNull String page) throws Exception {
        SpiderDebug.log("search", "site=%s,keyword=%s,quick=%s,page=%s", site.getName(), keyword, quick, page);
        boolean hasPage = !page.equals("1");
        if (isSpider(site.getKey(), site)) {
            String searchContent = marked(site, () -> hasPage ? site.spider().searchContent(keyword, quick, page) : site.spider().searchContent(keyword, quick));
            SpiderDebug.log("search", searchContent);
            Result result = Result.fromJson(searchContent);
            for (Vod vod : result.getList()) vod.setSite(site);
            return result;
        } else {
            ArrayMap<String, String> params = new ArrayMap<>();
            params.put("wd", keyword);
            params.put("quick", String.valueOf(quick));
            params.put("extend", "");
            if (hasPage) params.put("pg", page);
            String searchContent = call(site, params);
            SpiderDebug.log("search", searchContent);
            Result result = fetchPic(site, Result.fromType(site.getType(), searchContent));
            for (Vod vod : result.getList()) vod.setSite(site);
            return result;
        }
    }

    @NonNull
    public static Result action(@NonNull String key, @NonNull String action) throws Exception {
        Site site = VodConfig.get().getSite(key);
        if (isSpider(key, site)) return Result.fromJson(marked(site, () -> site.recent().spider().action(action)));
        if (site.getType() == 4) return Result.fromJson(OkHttp.string(action));
        return Result.empty();
    }

    @NonNull
    public static Result fetchPic(@NonNull Site site, @NonNull Result result) throws Exception {
        if (site.getType() > 2 || result.getList().isEmpty() || !result.getVod().getPic().isEmpty()) return result;
        ArrayList<String> ids = new ArrayList<>();
        boolean empty = site.getCategories().isEmpty();
        for (Vod item : result.getList()) if (empty || site.getCategories().contains(item.getTypeName())) ids.add(item.getId());
        if (ids.isEmpty()) return result.clear();
        ArrayMap<String, String> params = new ArrayMap<>();
        params.put("ac", ac(site.getType()));
        params.put("ids", TextUtils.join(",", ids));
        try (Response response = OkHttp.newCall(site.getApi(), site.getHeader(), params).execute()) {
            result.setList(Result.fromType(site.getType(), response.body().string()).getList());
            return result;
        }
    }

    private static void setTypes(@NonNull Site site, @NonNull Result result) {
        result.getTypes().stream().filter(type -> result.getFilters().containsKey(type.getTypeId())).forEach(type -> type.setFilters(result.getFilters().get(type.getTypeId())));
        if (site.getCategories().isEmpty()) return;
        Map<String, Class> typeByName = new HashMap<>();
        result.getTypes().forEach(type -> typeByName.put(type.getTypeName(), type));
        List<Class> types = site.getCategories().stream().map(typeByName::get).filter(Objects::nonNull).toList();
        if (!types.isEmpty()) result.setTypes(types);
    }

    /**
     * 手动阅读入口的解析探针（只取解析产出，不做 {@code Source.fetch}，
     * 不写播放缓存，不进自动判别层）。供"用阅读器打开"在后台线程调用。
     *
     * @return 探针载荷；不支持的 key（如 push_agent）返回 null。
     */
    public static ReadingProbe readingProbe(@NonNull String key, @NonNull String flag, @NonNull String id) throws Exception {
        if (HkDetailBridge.isHkKey(key)) {
            String title = key.substring(HkDetailBridge.KEY_PREFIX.length());
            HkRouter router = getHkRouter(title);
            return ReadingProbe.hk(router.play(id));
        }
        Site site = VodConfig.get().getSite(key);
        if (site == null) return null;
        if (isSpider(key, site)) {
            String json = marked(site, () -> site.recent().spider().playerContent(flag, id, VodConfig.get().getFlags()));
            Result r = Result.fromJson(json);
            return ReadingProbe.py(r.getUrl() == null ? null : r.getUrl().v());
        } else if (site.getType() == 4) {
            ArrayMap<String, String> params = new ArrayMap<>();
            params.put("play", id);
            params.put("flag", flag);
            String json = call(site, params);
            Result r = Result.fromJson(json);
            return ReadingProbe.py(r.getUrl() == null ? null : r.getUrl().v());
        }
        return null;
    }

    /** 手动阅读入口的探针载荷：海阔带 HkPlay，py/接口源带解析前的原始地址串。 */
    public static class ReadingProbe {
        public final com.fongmi.android.tv.api.hk.HkPlay hkPlay;
        public final String pyRaw;

        private ReadingProbe(com.fongmi.android.tv.api.hk.HkPlay hkPlay, String pyRaw) {
            this.hkPlay = hkPlay;
            this.pyRaw = pyRaw;
        }

        public static ReadingProbe hk(com.fongmi.android.tv.api.hk.HkPlay play) {
            return new ReadingProbe(play, null);
        }

        public static ReadingProbe py(String rawUrl) {
            return new ReadingProbe(null, rawUrl);
        }
    }

}



