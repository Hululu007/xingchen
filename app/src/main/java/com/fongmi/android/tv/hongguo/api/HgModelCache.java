package com.fongmi.android.tv.hongguo.api;

import com.fongmi.android.tv.hongguo.model.HgVideoModel;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 播放模型内存缓存（线程安全）：预取与现取共用一张表。
 * 条目按回包 urlExpire 判新鲜；urlExpire 未给（0）时按 5 分钟兜底时效
 * （海阔线预热缓存实测口径：直链有时效，超时不再复用）。
 */
public final class HgModelCache {

    private static final long FALLBACK_TTL_MS = 5 * 60 * 1000L;

    private static final class Slot {
        final HgVideoModel model;
        final long fetchedAtMs;

        Slot(HgVideoModel model, long fetchedAtMs) {
            this.model = model;
            this.fetchedAtMs = fetchedAtMs;
        }
    }

    private final ConcurrentHashMap<String, Slot> map = new ConcurrentHashMap<>();

    public void put(String requestedId, HgVideoModel model) {
        if (requestedId == null || model == null) return;
        map.put(requestedId, new Slot(model, System.currentTimeMillis()));
    }

    public void putAll(java.util.Map<String, HgVideoModel> models) {
        for (java.util.Map.Entry<String, HgVideoModel> e : models.entrySet()) {
            put(e.getKey(), e.getValue());
        }
    }

    /** 取新鲜条目；过期或没有返回 null。 */
    public HgVideoModel getFresh(String requestedId) {
        Slot s = requestedId == null ? null : map.get(requestedId);
        if (s == null) return null;
        long expireMs = s.model.getUrlExpire() > 0
                ? s.model.getUrlExpire() * 1000L
                : s.fetchedAtMs + FALLBACK_TTL_MS;
        if (System.currentTimeMillis() >= expireMs) {
            map.remove(requestedId, s);
            return null;
        }
        return s.model;
    }

    public boolean hasFresh(String requestedId) {
        return getFresh(requestedId) != null;
    }

    public void clear() {
        map.clear();
    }
}
