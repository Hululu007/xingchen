package com.fongmi.android.tv.hongguo.api;

import com.fongmi.android.tv.hongguo.model.HgParser;
import com.fongmi.android.tv.hongguo.model.HgVideoModel;
import com.github.catvod.utils.Prefers;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 播放模型缓存（线程安全）：内存表＋落盘持久（Prefers 一键整表 JSON），
 * 预取与现取共用。条目按回包 urlExpire 判新鲜；urlExpire 未给（0）时按
 * 5 分钟兜底时效（海阔线预热缓存实测口径：直链有时效，超时不再复用）。
 *
 * <p>落盘口径（2026-10-30，hongguo-fetch-quota-dig2-20261030.md §六-2）：
 * video_model 配额按成功请求计、回补小时级——模型只活在内存时进程
 * 一死冷启动每集重取、白烧额度。落盘存每条的 video_model 原始 JSON
 * 串（{@link HgVideoModel#getSourceJson()}），读回经
 * {@link HgParser#restoreModel} 同路重建，字段零漂移；存取走
 * {@link TypeToken} 定型＋{@link SerializedName} 钉名（防 R8 泛型/
 * 改名坑）。过期条目落盘与读回两头都裁，盘上最多留
 * {@link #MAX_DISK_ENTRIES} 条最新。落盘全程在自有后台线程，失败
 * 静默降级纯内存（缓存是优化项，不许反噬取链）。</p>
 */
public final class HgModelCache {

    private static final long FALLBACK_TTL_MS = 5 * 60 * 1000L;

    /** 落盘键（hongguo_ 前缀走 Prefers，与红果自有存储同口径）。 */
    private static final String DISK_KEY = "hongguo_model_cache_v1";

    /** 盘上条数上限：按 fetchedAt 留最新，防 JSON 无界膨胀。 */
    private static final int MAX_DISK_ENTRIES = 120;

    private static final Gson GSON = new Gson();
    private static final Type DISK_TYPE = new TypeToken<Map<String, DiskEntry>>() {}.getType();

    /** 落盘条目：模型原串＋外层宽高＋取回时刻；字段名以 SerializedName 钉死。 */
    static final class DiskEntry {
        @SerializedName("m")
        String modelJson;
        @SerializedName("w")
        int videoWidth;
        @SerializedName("h")
        int videoHeight;
        @SerializedName("t")
        long fetchedAtMs;
    }

    private static final class Slot {
        final HgVideoModel model;
        final long fetchedAtMs;

        Slot(HgVideoModel model, long fetchedAtMs) {
            this.model = model;
            this.fetchedAtMs = fetchedAtMs;
        }
    }

    private final ConcurrentHashMap<String, Slot> map = new ConcurrentHashMap<>();
    private final Object loadLock = new Object();
    private volatile boolean loaded;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hg-model-cache-io");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean persistQueued = new AtomicBoolean();

    public void put(String requestedId, HgVideoModel model) {
        if (requestedId == null || model == null) return;
        ensureLoaded();
        map.put(requestedId, new Slot(model, System.currentTimeMillis()));
        schedulePersist();
    }

    public void putAll(java.util.Map<String, HgVideoModel> models) {
        for (java.util.Map.Entry<String, HgVideoModel> e : models.entrySet()) {
            put(e.getKey(), e.getValue());
        }
    }

    /** 取新鲜条目；过期或没有返回 null。 */
    public HgVideoModel getFresh(String requestedId) {
        if (requestedId == null) return null;
        ensureLoaded();
        Slot s = map.get(requestedId);
        if (s == null) return null;
        if (isStale(s, System.currentTimeMillis())) {
            map.remove(requestedId, s);
            return null;
        }
        return s.model;
    }

    public boolean hasFresh(String requestedId) {
        return getFresh(requestedId) != null;
    }

    public void clear() {
        ensureLoaded();
        map.clear();
        try {
            Prefers.put(DISK_KEY, "");
        } catch (Throwable ignored) {
            // 落盘不可用时只清内存（见类头降级口径）
        }
        schedulePersist();
    }

    private static boolean isStale(Slot s, long nowMs) {
        long expireMs = s.model.getUrlExpire() > 0
                ? s.model.getUrlExpire() * 1000L
                : s.fetchedAtMs + FALLBACK_TTL_MS;
        return nowMs >= expireMs;
    }

    // ---------- 落盘（读回与 HgParser 同路重建；全程容错降级） ----------

    private void ensureLoaded() {
        if (loaded) return;
        synchronized (loadLock) {
            if (loaded) return;
            loaded = true;
            try {
                String json = Prefers.getString(DISK_KEY);
                if (json == null || json.isEmpty()) return;
                Map<String, DiskEntry> disk = GSON.fromJson(json, DISK_TYPE);
                if (disk == null) return;
                long now = System.currentTimeMillis();
                for (Map.Entry<String, DiskEntry> e : disk.entrySet()) {
                    DiskEntry d = e.getValue();
                    if (d == null || d.modelJson == null) continue;
                    HgVideoModel m = HgParser.restoreModel(e.getKey(), d.modelJson, d.videoWidth, d.videoHeight);
                    if (m == null) continue;
                    Slot s = new Slot(m, d.fetchedAtMs);
                    if (!isStale(s, now)) map.put(e.getKey(), s);
                }
            } catch (Throwable ignored) {
                // 盘坏/环境不可用：当空盘处理，内存链不受影响
            }
        }
    }

    private void schedulePersist() {
        if (!persistQueued.compareAndSet(false, true)) return;
        try {
            io.execute(() -> {
                persistQueued.set(false);
                persistNow();
            });
        } catch (Throwable ignored) {
            persistQueued.set(false);
        }
    }

    /** 快照在执行时取（不锁存调度时刻状态）：clear 之后跑到的写盘只会写清后的表。 */
    private void persistNow() {
        try {
            long now = System.currentTimeMillis();
            List<Map.Entry<String, Slot>> fresh = new ArrayList<>();
            for (Map.Entry<String, Slot> e : map.entrySet()) {
                if (!isStale(e.getValue(), now)) fresh.add(e);
            }
            fresh.sort((a, b) -> Long.compare(b.getValue().fetchedAtMs, a.getValue().fetchedAtMs));
            Map<String, DiskEntry> disk = new LinkedHashMap<>();
            for (Map.Entry<String, Slot> e : fresh) {
                if (disk.size() >= MAX_DISK_ENTRIES) break;
                String src = e.getValue().model.getSourceJson();
                if (src == null || src.isEmpty()) continue;
                DiskEntry d = new DiskEntry();
                d.modelJson = src;
                d.videoWidth = e.getValue().model.getVideoWidth();
                d.videoHeight = e.getValue().model.getVideoHeight();
                d.fetchedAtMs = e.getValue().fetchedAtMs;
                disk.put(e.getKey(), d);
            }
            Prefers.put(DISK_KEY, disk.isEmpty() ? "" : GSON.toJson(disk));
        } catch (Throwable ignored) {
            // 写盘失败静默：内存表仍在，本进程内缓存语义不变
        }
    }
}
