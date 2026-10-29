package com.fongmi.android.tv.hongguo.api;

import com.fongmi.android.tv.hongguo.model.HgVideoModel;

import java.util.ArrayList;
import java.util.List;

/**
 * 预取器：给播放页的「批量＋可预取」入口形态（流畅死线）。
 *
 * <p>生产触发口径（2026-10-29 他亲定，账 hongguo-batch3b-play-20261029）：
 * 只许「播中触发」——列表浏览/刚进页/滑到一集都不许抢取下一集地址
 * （1.4.55 烧 video_model 额度的老路，cdbba10ca 曾整批摘除预取）；
 * 当前集真播起来、播放位置进入尾段（{@link #isTailSegment}）时，才经
 * {@link #prefetchNext(List, int)} 取**下一集一份**备好，切集直接播，
 * 花费＝真看的一集一份。官方侧：包内预取开关缺省关、由服务端配置
 * 决定，活值探不到——此口径是按他体验定的本地策略，不冒充官方活值。</p>
 *
 * <p>{@link #prefetchAround(List, int)} 是通用批量口（当前＋前瞻若干），
 * 现行无生产调用点（其在途去重语义由 HgProxyOfflineTest 钉住）；生产
 * 触发只走 prefetchNext。</p>
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

    // ---------- 播中尾段触发（生产口径，见类头） ----------

    /** 尾段剩余时长门槛：剩余 ≤30 秒即入尾段（短剧单集多为 1–3 分钟，
     * 30 秒够一笔 video_model 往返＋解链，又不至于大半集都在预取窗里）。 */
    public static final long TAIL_REMAINING_MS = 30_000L;

    /** 尾段进度门槛：进度 ≥85% 即入尾段（长集靠百分比兜底，
     * 免得剩余 30 秒门槛对长集触发太晚、切集时还没备好）。 */
    public static final double TAIL_FRACTION = 0.85;

    /**
     * 是否已进尾段（纯函数，离线可验）：必须真在播（playing）——暂停/
     * 未起播恒 false；时长未知（≤0）恒 false；位置入尾段＝剩余
     * ≤{@link #TAIL_REMAINING_MS} 或进度 ≥{@link #TAIL_FRACTION}，任一
     * 成立即是。位置越界（负/超长）按收界后判。
     */
    public static boolean isTailSegment(boolean playing, long positionMs, long durationMs) {
        if (!playing || durationMs <= 0) return false;
        long pos = Math.max(0L, Math.min(positionMs, durationMs));
        if (durationMs - pos <= TAIL_REMAINING_MS) return true;
        return pos >= durationMs * TAIL_FRACTION;
    }

    /**
     * 尾段预取下一集一份：orderedIds 为全剧集 id 有序表，currentIndex
     * 为当前集下标——只取 currentIndex+1 那一集（尾段触发语义下
     * lookahead＝1）；没有下一集/下一集已缓存/已在途时不发请求
     * （去重在 {@link #prefetchIds} 的 cache＋inFlight 机制里）。
     */
    public void prefetchNext(List<String> orderedIds, int currentIndex, HgCallback<Integer> done) {
        if (orderedIds == null || currentIndex < 0 || currentIndex + 1 >= orderedIds.size()) {
            if (done != null) done.onSuccess(0);
            return;
        }
        String next = orderedIds.get(currentIndex + 1);
        if (next == null) {
            if (done != null) done.onSuccess(0);
            return;
        }
        prefetchIds(java.util.Collections.singletonList(next), done);
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
