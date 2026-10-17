package com.fongmi.android.tv.api;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.github.catvod.utils.Prefers;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 首页预热：App 冷启动、主页首屏加载发起后，后台把所有 py/js 点播蜘蛛源的首页
 * 提前跑一遍写进预热缓存，用户切任何源都是秒出。
 *
 * 独立通道（与前台首页加载互不打架）：
 * - 不走 cancelHomeLoads / 代次（generation）作废体系：不用 spiderHomePool、
 *   不登记 homeLoadFutures、不读不写 homeLoadGen；前台切源/进详情/搜索/刷新
 *   触发的任何作废都碰不到预热任务（射程外的独立池＋独立 future 表）。
 * - 绝不调用任何作废接口：不调 cancelHomeLoads、不递增前台代次。
 * - 慢源专用耐心：单源超时按账自适应（历史耗时×1.5＋10 秒余量，60–300 秒钳制；
 *   无账新源默认 120 秒），前台 30/35 秒口径一字不动；只有真挂死才跳过。
 * - 耗时记账：每个源每次成功加载的实际耗时持久化，下次启动慢源优先先热。
 */
public final class HomePrewarm {


    /** 预热单源默认上限（秒）：无账可查的新源用它；慢但健康的源（带登记握手、第一次十几秒）正是预热要救的，不许套前台口径。 */
    static final long PREWARM_SOURCE_TIMEOUT_SECONDS = 120;
    /** 耐心上限钳制（毫秒）：按账自适应也不许超过它，避免真挂死的源拖住队列。 */
    private static final long TIMEOUT_MAX_MS = 300_000;
    private static final long TIMEOUT_MIN_MS = 60_000;
    /** 无账源的排序估计（毫秒）：未知源按中等偏慢排，不抢已知慢源的先机，也不垫底。 */
    private static final long UNKNOWN_COST_ESTIMATE_MS = 15_000;
    /** 耗时账本 key 前缀：prewarm_cost_<源key> → 最近一次成功加载耗时（毫秒）。 */
    private static final String COST_PREFIX = "prewarm_cost_";
    /** 前台切到在途预热源时的最长等待（秒）：接上在途那趟，不重跑。 */
    static final long PREWARM_JOIN_TIMEOUT_SECONDS = 25;
    /** 预热条目 TTL（毫秒）：用户慢慢逛时不轻易过期；被打开命中即晋升为普通首页缓存。 */
    static final long PREWARM_CACHE_TTL = 15 * 60 * 1000;
    /** 首屏先让路：主页首屏加载发起后延迟再开始，避免跟首屏抢 Python。 */
    private static final long START_DELAY_MS = 3000;
    /** 播放让路轮询间隔。 */
    private static final long PLAYBACK_WAIT_MS = 2000;

    /** 播放进行中标记：SiteApi.playerContent 置 true；PlayerManager.stop()/release() 清 false。 */
    public static volatile boolean playbackActive = false;

    private static final AtomicBoolean kicked = new AtomicBoolean(false);
    private static final AtomicInteger threadNo = new AtomicInteger(0);

    private static ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name + "-" + threadNo.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /** 驱动：单线程，按源列表顺序逐个推进（同时最多 1 个源在跑）。 */
    private static final ExecutorService driver = Executors.newSingleThreadExecutor(daemon("prewarm-driver"));
    /** 子任务：2 线程，只跑预热自己的 home/homeVideo 双路，不占前台 spiderHomePool。 */
    static final ExecutorService workers = Executors.newFixedThreadPool(2, daemon("prewarm-worker"));

    /** 在途预热（single-flight）：前台切到正在预热的源时复用/等待它，不另起一趟。 */
    private static final ConcurrentHashMap<String, CompletableFuture<String>> inflight = new ConcurrentHashMap<>();
    /** 本次启动已处理过的源：坏源只跳过一次，不重试打站点。 */
    private static final Set<String> doneThisBoot = ConcurrentHashMap.newKeySet();

    /** 预热缓存 key：py 用 脚本戳（与普通首页缓存同口径，可直接晋升）；js 用固定后缀。 */
    static String cacheKey(Site site) {
        String key = site.getKey();
        String api = site.getApi();
        boolean py = (key != null && key.startsWith("py_")) || (api != null && api.contains(".py"));
        if (py) {
            String stamp;
            try {
                stamp = com.fongmi.android.tv.api.loader.PyLoader.scriptStamp(api);
            } catch (Throwable t) {
                stamp = "nostamp";
            }
            return key + "|" + stamp;
        }
        return key + "|js";
    }

    /** 只预热点播 py/js 蜘蛛源：海阔规则、Emby、type4、push、直播一律不进。 */
    static boolean isPrewarmable(String key, Site site) {
        if (site == null || key == null) return false;
        boolean spider = site.getType() == 3 || key.startsWith("py_");
        if (!spider) return false;
        String api = site.getApi();
        String a = api == null ? "" : api.toLowerCase();
        return key.startsWith("py_") || a.contains(".py") || a.contains(".js") || a.contains(".wv");
    }

    /** App 冷启动且主页首屏加载发起后调用；每进程只生效一次。 */
    public static void kick() {
        if (!kicked.compareAndSet(false, true)) return;
        driver.submit(HomePrewarm::runQueue);
    }

    /**
     * 耗时记账：只读/写一个小表（SharedPreferences，apply 异步落盘），不进加载主链。
     * 成功才记账：失败/超时的那趟不记，避免把"挂死"写成"慢"、下次给更大的耐心。
     */
    static void recordCost(String siteKey, long costMs) {
        if (siteKey == null || costMs <= 0) return;
        try {
            Prefers.put(COST_PREFIX + siteKey, costMs);
        } catch (Throwable ignored) {
        }
    }

    /** 最近一次成功耗时（毫秒）；无账返回 -1。 */
    static long costOf(String siteKey) {
        if (siteKey == null) return -1;
        try {
            return Prefers.getLong(COST_PREFIX + siteKey, -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 单源耐心：历史耗时 ×1.5 ＋ 10 秒余量，钳制在 60–300 秒；无账用默认 120 秒。 */
    static long timeoutFor(String siteKey) {
        long h = costOf(siteKey);
        if (h <= 0) return PREWARM_SOURCE_TIMEOUT_SECONDS;
        long ms = (long) (h * 1.5) + 10_000;
        if (ms < TIMEOUT_MIN_MS) ms = TIMEOUT_MIN_MS;
        if (ms > TIMEOUT_MAX_MS) ms = TIMEOUT_MAX_MS;
        return ms / 1000;
    }

    private static void runQueue() {
        try {
            Thread.sleep(START_DELAY_MS);
        } catch (InterruptedException e) {
            return;
        }
        if (Prefers.getBoolean("crash")) return; // 崩溃恢复模式：不添乱
        List<Site> sites;
        try {
            sites = VodConfig.get().getSites();
        } catch (Throwable t) {
            return;
        }
        Site home = null;
        try {
            home = VodConfig.get().getHome();
        } catch (Throwable ignored) {
        }
        // 慢源优先：按账本耗时降序排，慢的先热（痛点先治），快的捎带就完；
        // 无账源按中等偏慢估计，不抢已知慢源的先机。
        try {
            sites.sort((a, b) -> Long.compare(sortCost(b), sortCost(a)));
        } catch (Throwable ignored) {
        }
        for (Site site : sites) {
            try {
                if (!wantSite(site, home)) continue;
                // 播放让路：用户开始播放/解析时暂停推进，播完再续（只等，不取消）。
                while (playbackActive) {
                    try {
                        Thread.sleep(PLAYBACK_WAIT_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                prewarmOne(site);
            } catch (Throwable t) {
                // 队列尽力而为：单个条目异常绝不中断全队列。
                com.github.catvod.crawler.SpiderDebug.log("prewarm", "queue item failed: " + safeKey(site));
            }
        }
    }

    private static boolean wantSite(Site site, Site home) {
        if (site == null) return false;
        String key = site.getKey();
        if (key == null) return false;
        if (home != null && key.equals(home.getKey())) return false; // 当前源：它自己正在加载
        if (!isPrewarmable(key, site)) return false;
        if (doneThisBoot.contains(key)) return false;                 // 本次启动只试一次
        try {
            if (SiteApi.homeCacheHasFresh(key)) return false;         // 已经暖了，不重跑
        } catch (Throwable ignored) {
        }
        return true;
    }

    private static long sortCost(Site site) {
        long c = costOf(site == null ? null : site.getKey());
        return c > 0 ? c : UNKNOWN_COST_ESTIMATE_MS;
    }

    private static void prewarmOne(Site site) {
        String key = site.getKey();
        CompletableFuture<String> f = new CompletableFuture<>();
        if (inflight.putIfAbsent(key, f) != null) return; // 已有在途：不重复
        try {
            long start = System.currentTimeMillis();
            String json = SiteApi.prewarmSpiderHome(site, timeoutFor(key)); // 抛异常/超时/空都算失败
            long cost = System.currentTimeMillis() - start;
            if (json != null && !json.isEmpty()) {
                SiteApi.putPrewarmCache(cacheKey(site), json);
                recordCost(key, cost); // 成功才记账
                com.github.catvod.crawler.SpiderDebug.log("prewarm", "warmed: " + key + " cost=" + cost + "ms");
            } else {
                com.github.catvod.crawler.SpiderDebug.log("prewarm", "skip (empty): " + key);
            }
            f.complete(json);
            doneThisBoot.add(key); // 成功或空结果都算试过，本次启动不再重试
        } catch (Throwable t) {
            // 坏源只跳过：记一笔日志，继续下一个，绝不卡死队列；失败不记账。
            com.github.catvod.crawler.SpiderDebug.log("prewarm", "skip (fail): " + key + " " + t.getClass().getSimpleName());
            f.completeExceptionally(t);
            doneThisBoot.add(key);
        } finally {
            inflight.remove(key, f);
        }
    }

    /** 前台 single-flight：窥视在途预热（不移除，预热完成后自己清）。 */
    static CompletableFuture<String> pollInflight(String key) {
        return key == null ? null : inflight.get(key);
    }

    private static String safeKey(Site site) {
        try {
            return site == null ? "null" : String.valueOf(site.getKey());
        } catch (Throwable t) {
            return "?";
        }
    }
}
