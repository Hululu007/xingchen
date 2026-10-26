package com.fongmi.android.tv.music;

import com.fongmi.android.tv.bean.music.LyricVersion;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 歌词版本结果进程内缓存＋在飞合并＋全量总时限收口（版本面板「总是重复加载」治本）。
 *
 * 此前版本列表全 App 零缓存：面板每次打开都新发阶段一 Worker 请求＋阶段二全量聚合
 * （跨源逐家搜＋插件＋Worker 再一趟＋平台直连＋酷我＋AMLL），阶段二整链还没有总时限，
 * 慢网下「继续搜索中」几分钟收不住；重复打开再并发多条同歌全链互相拖慢。
 *
 * 本类只管协调，不取数：两阶段链由 MusicRepository 以 LoadChain 注入。
 * 键由调用方按 MusicLyricCache.key（id＋平台＋插件戳三段身份）算好传入——切歌必换键、
 * 不串账。口径：
 * - 同歌已结算且在有效期内：直接回结算结果，不发新一轮取数。
 * - 同歌取数在飞：后来者只登记等结算，不重复发链；已有快照先给快照。
 * - 已结算但过期：先回旧账显示，同时后台补搜，补搜结算后再回一次新账。
 * - 阶段二超过 FULL_DEADLINE_MS 未回：按当前最佳（阶段一快照）先行结算，搜索态必终止；
 *   迟到的全量结果只静默升级缓存，不再打扰已结算的等待者。
 * - 起播预取已拿到的 Worker 版本可经 seedWorker 种下当阶段一快照，面板首开免再发一趟。
 *
 * 回调可能在调用线程（缓存命中）或加载线程（在飞结算）上触发，消费方自行切主线程。
 */
public class LyricVersionsCache {

    public interface Callback {
        /** 阶段一快照到达（可能为空列表，消费方自行决定中间态文案） */
        void onPartial(List<LyricVersion> versions);

        /** 结算：全链跑完（complete=true）或总时限收口（complete=false，按当前最佳结算）。
         * 后台补搜结算时可能被再调一次（列表原地更新）。 */
        void onSettled(List<LyricVersion> versions, boolean complete);
    }

    /** 两阶段取数链（MusicRepository 提供：阶段一 Worker 版本、阶段二全量聚合） */
    public interface LoadChain {
        List<LyricVersion> loadWorker() throws Exception;

        FullResult loadFull() throws Exception;
    }

    public static class FullResult {
        public final List<LyricVersion> versions;
        /** 聚合是否跑完整条链（与 LyricRichAllResult.complete 同口径） */
        public final boolean complete;

        public FullResult(List<LyricVersion> versions, boolean complete) {
            this.versions = versions;
            this.complete = complete;
        }
    }

    /** 结算结果有效期：期内重开面板直接用，不补搜 */
    static long ttlMs = 10 * 60 * 1000L;
    /** 阶段二总时限：到点必按当前最佳结算，不许搜索态无限挂 */
    static long fullDeadlineMs = 90_000L;
    private static final int MAX_ENTRIES = 50;

    private static class Entry {
        List<LyricVersion> worker;
        List<LyricVersion> full;
        boolean settled;
        boolean complete;
        boolean loading;
        long settledAtMs;
        int generation;
        final List<Callback> waiters = new ArrayList<>();
    }

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>(64, 0.75f, true);

    /**
     * 请求某歌的版本列表。首个到达且无可用缓存的调用方线程会同步跑完两阶段链
     * （与旧面板同口径：调用方须在后台线程）；其余调用方登记后立即返回。
     */
    public static void request(String key, LoadChain chain, Callback cb) {
        if (cb == null) return;
        if (key == null || chain == null) {
            runInline(chain, cb);
            return;
        }
        List<LyricVersion> settledNow = null;
        boolean settledComplete = false;
        List<LyricVersion> partialNow = null;
        Entry entry;
        int gen;
        boolean startLoad = false;
        boolean skipWorker = false;
        synchronized (ENTRIES) {
            entry = ENTRIES.get(key);
            if (entry == null) {
                entry = new Entry();
                ENTRIES.put(key, entry);
                trimLocked();
            }
            long now = System.currentTimeMillis();
            if (entry.settled && now - entry.settledAtMs <= ttlMs) {
                // 已结算且新鲜：直接回账，不发新一轮取数
                settledNow = entry.full;
                settledComplete = entry.complete;
            } else if (entry.loading) {
                // 在飞合并：登记等结算；已有产物先给
                entry.waiters.add(cb);
                if (entry.settled) {
                    settledNow = entry.full;
                    settledComplete = entry.complete;
                } else if (entry.worker != null) {
                    partialNow = entry.worker;
                }
            } else if (entry.settled) {
                // 过期：先回旧账，本调用方顺带当后台补搜的加载者
                settledNow = entry.full;
                settledComplete = entry.complete;
                entry.settled = false;
                entry.loading = true;
                entry.generation++;
                entry.waiters.add(cb);
                startLoad = true;
            } else {
                entry.loading = true;
                entry.generation++;
                entry.waiters.add(cb);
                startLoad = true;
                // 起播预取种下的阶段一快照在：免再发阶段一，直接用它先行、只跑全量
                skipWorker = entry.worker != null;
            }
            gen = entry.generation;
        }
        if (partialNow != null) safePartial(cb, partialNow);
        if (settledNow != null) safeSettled(cb, settledNow, settledComplete);
        if (!startLoad) return;
        runLoad(key, entry, gen, chain, skipWorker);
    }

    /** 起播预取顺手种下阶段一快照（Worker 版本）：已有全量账不覆盖，加载中/已有快照不抢。 */
    public static void seedWorker(String key, List<LyricVersion> versions) {
        if (key == null || versions == null || versions.isEmpty()) return;
        synchronized (ENTRIES) {
            Entry e = ENTRIES.get(key);
            if (e == null) {
                e = new Entry();
                ENTRIES.put(key, e);
                trimLocked();
            }
            if (e.settled) return;
            if (e.worker == null || e.worker.isEmpty()) e.worker = new ArrayList<>(versions);
        }
    }

    /** 作废某歌的版本账（手动重搜口径）：在飞的那条不打断（其等待者照常结算），
     * 只让账面过期，下次请求走补搜；没在飞的直接删账。 */
    public static void invalidate(String key) {
        if (key == null) return;
        synchronized (ENTRIES) {
            Entry e = ENTRIES.get(key);
            if (e == null) return;
            if (e.loading) e.settledAtMs = 0;
            else ENTRIES.remove(key);
        }
    }

    private static void runLoad(String key, Entry entry, int gen, LoadChain chain, boolean skipWorker) {
        // 阶段一：Worker 版本先行（有预取快照时免发）
        List<LyricVersion> worker;
        if (skipWorker) {
            synchronized (ENTRIES) {
                worker = entry.worker;
            }
        } else {
            List<LyricVersion> w = null;
            try {
                w = chain.loadWorker();
            } catch (Throwable ignored) {
            }
            worker = w == null ? new ArrayList<>() : w;
            List<Callback> targets;
            synchronized (ENTRIES) {
                if (ENTRIES.get(key) != entry || entry.generation != gen) return;
                entry.worker = worker;
                targets = new ArrayList<>(entry.waiters);
            }
            for (Callback t : targets) safePartial(t, worker);
        }
        if (skipWorker) {
            List<Callback> targets;
            synchronized (ENTRIES) {
                if (ENTRIES.get(key) != entry || entry.generation != gen) return;
                targets = new ArrayList<>(entry.waiters);
            }
            for (Callback t : targets) safePartial(t, worker);
        }
        // 阶段二：独立线程跑全量，本线程只等到总时限
        runFull(key, entry, gen, chain);
    }

    private static void runFull(final String key, final Entry entry, final int gen, final LoadChain chain) {
        Thread t = new Thread(() -> {
            FullResult r = null;
            try {
                r = chain.loadFull();
            } catch (Throwable ignored) {
            }
            if (r == null) r = new FullResult(null, false);
            List<Callback> targets = null;
            List<LyricVersion> deliver = null;
            synchronized (ENTRIES) {
                if (ENTRIES.get(key) == entry && entry.generation == gen) {
                    if (r.versions != null) entry.full = r.versions;
                    if (entry.full == null) entry.full = entry.worker;
                    entry.complete = r.complete;
                    entry.settledAtMs = System.currentTimeMillis();
                    entry.loading = false;
                    entry.settled = true;
                    deliver = entry.full;
                    // 通知看等待者、不看 settled 旗：过期补搜那轮 settled 本就是真，
                    // 等待者（先拿了旧账的那位）靠这里收补搜后的新账；总时限收口已把
                    // 等待者排空时这里为空、迟到全量只静默升级，不重复打扰。
                    if (!entry.waiters.isEmpty()) {
                        targets = new ArrayList<>(entry.waiters);
                        entry.waiters.clear();
                    }
                }
            }
            if (targets != null) {
                if (deliver == null) deliver = new ArrayList<>();
                for (Callback w : targets) safeSettled(w, deliver, r.complete);
            }
        }, "lyric-versions-full");
        t.setDaemon(true);
        t.start();
        try {
            t.join(fullDeadlineMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        if (!t.isAlive()) return;
        // 总时限收口：按当前最佳先行结算，搜索态到此终止；
        // 全量线程迟到回来时只静默升级缓存（上方逻辑里 settled 已真、不再通知）
        List<Callback> targets = null;
        List<LyricVersion> best = null;
        synchronized (ENTRIES) {
            if (ENTRIES.get(key) == entry && entry.generation == gen && !entry.settled) {
                entry.settled = true;
                entry.complete = false;
                entry.full = entry.worker != null ? entry.worker : new ArrayList<>();
                entry.settledAtMs = System.currentTimeMillis();
                entry.loading = false;
                best = entry.full;
                targets = new ArrayList<>(entry.waiters);
                entry.waiters.clear();
            }
        }
        if (targets != null) {
            for (Callback w : targets) safeSettled(w, best, false);
        }
    }

    /** 无身份键时的兜底：不缓存、不合併，照旧两阶段直跑（面板正常路径不会走到） */
    private static void runInline(LoadChain chain, Callback cb) {
        List<LyricVersion> worker = null;
        try {
            worker = chain.loadWorker();
        } catch (Throwable ignored) {
        }
        safePartial(cb, worker == null ? new ArrayList<>() : worker);
        FullResult r = null;
        try {
            r = chain.loadFull();
        } catch (Throwable ignored) {
        }
        if (r == null) r = new FullResult(null, false);
        safeSettled(cb, r.versions != null ? r.versions : (worker == null ? new ArrayList<>() : worker), r.complete);
    }

    private static void trimLocked() {
        if (ENTRIES.size() <= MAX_ENTRIES) return;
        Iterator<Map.Entry<String, Entry>> it = ENTRIES.entrySet().iterator();
        while (ENTRIES.size() > MAX_ENTRIES && it.hasNext()) {
            Entry e = it.next().getValue();
            if (!e.loading && e.settled) it.remove();
        }
    }

    private static void safePartial(Callback cb, List<LyricVersion> vs) {
        try {
            cb.onPartial(vs);
        } catch (Throwable ignored) {
        }
    }

    private static void safeSettled(Callback cb, List<LyricVersion> vs, boolean complete) {
        try {
            cb.onSettled(vs, complete);
        } catch (Throwable ignored) {
        }
    }

    /** 单测用：清账并复位时限口径 */
    static void resetForTest() {
        synchronized (ENTRIES) {
            ENTRIES.clear();
        }
    }
}
