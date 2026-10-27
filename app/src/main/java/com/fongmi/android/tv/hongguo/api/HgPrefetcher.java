package com.fongmi.android.tv.hongguo.api;

import com.fongmi.android.tv.hongguo.model.HgVideoModel;

import java.util.ArrayList;
import java.util.List;

/**
 * 预取器：给播放页的「批量＋可预取」入口形态（流畅死线）。
 *
 * <p>用法：播放页拿到一剧的集 id 有序表后，每滑到一集就调
 * {@link #prefetchAround(List, int)}，它把后面若干集里缓存没有的，
 * 合成一次 multi 批量请求取回并入缓存——滑到之前数据与钥匙（spade_a 在模型里，
 * DEK 可即时推导）已经备好，不做单集现取现等。</p>
 */
public final class HgPrefetcher {

    /** 默认向前预取集数。 */
    public static final int DEFAULT_LOOKAHEAD = 2;

    private final HgApiClient client;
    private final HgModelCache cache;
    private final int lookahead;
    /** 在途集 id：同一批缺集已有请求在飞时不重复发；请求完成／失败后清除。 */
    private final java.util.Set<String> inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public HgPrefetcher(HgApiClient client, HgModelCache cache) {
        this(client, cache, DEFAULT_LOOKAHEAD);
    }

    public HgPrefetcher(HgApiClient client, HgModelCache cache, int lookahead) {
        this.client = client;
        this.cache = cache;
        this.lookahead = Math.max(1, lookahead);
    }

    public HgModelCache getCache() {
        return cache;
    }

    /** 已备好的模型（新鲜）直接取；没有返回 null，调用方再走现取。 */
    public HgVideoModel ready(String videoId) {
        return cache.getFresh(videoId);
    }

    /**
     * 围绕当前集向前预取：orderedIds 为全剧集 id 有序表，currentIndex 为当前集下标。
     * 缺的集一次批量取回；全部已备好时不发请求。回调可为 null（只入缓存）。
     */
    public void prefetchAround(List<String> orderedIds, int currentIndex, HgCallback<Integer> done) {
        if (orderedIds == null || orderedIds.isEmpty()) {
            if (done != null) done.onSuccess(0);
            return;
        }
        List<String> missing = new ArrayList<>();
        int from = Math.max(0, currentIndex);
        int to = Math.min(orderedIds.size(), from + lookahead + 1);
        for (int i = from; i < to; i++) {
            String id = orderedIds.get(i);
            if (id != null && !cache.hasFresh(id)) missing.add(id);
        }
        prefetchIds(missing, done);
    }

    /** 预取任意一批集（如跨剧的下一部第一集）：缺的一次批量取回。
     * 在途去重：已在飞的 id 本次跳过（不重复发请求），全部跳过时直接回调 0。 */
    public void prefetchIds(List<String> videoIds, HgCallback<Integer> done) {
        List<String> missing = new ArrayList<>();
        if (videoIds != null) {
            for (String id : videoIds) {
                // cache.hasFresh 先判：在途标记只加在真要发的 id 上
                if (id != null && !cache.hasFresh(id) && inFlight.add(id)) missing.add(id);
            }
        }
        if (missing.isEmpty()) {
            if (done != null) done.onSuccess(0);
            return;
        }
        final List<String> fetching = missing;
        client.fetchVideoModels(fetching, new HgCallback<java.util.Map<String, HgVideoModel>>() {
            @Override
            public void onSuccess(java.util.Map<String, HgVideoModel> result) {
                inFlight.removeAll(fetching);
                cache.putAll(result);
                if (done != null) done.onSuccess(result.size());
            }

            @Override
            public void onError(int httpCode, String message) {
                inFlight.removeAll(fetching);
                if (done != null) done.onError(httpCode, message);
            }
        });
    }

    /** 当前在途的预取集数（单测／诊断用）。 */
    public int inFlightCount() {
        return inFlight.size();
    }
}
