package com.fongmi.android.tv.hongguo.drama;

import com.fongmi.android.tv.hongguo.api.HgCallback;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 进播放页预取全集（他 2026-10-30 定「进入播放页了才预取全部集」）：
 * 用户在播放页（HongguoFeedActivity 的 MODE_SERIES）开始看某部剧后，
 * 后台把这部剧全部集数的播放模型经播放取数链（DramaClient.
 * fetchModels，自家先行＋共享兜底）按 multi 单发上限分批备进
 * DramaCache，之后切集直接命中缓存。
 *
 * <p>纪律：①当前集永远优先——会话只由页面在当前集解链成功
 *（onReady）后开启，此时当前集模型已落缓存；预取请求与起播请求
 * 是取数池上的独立异步调用，互不排队；计划里当前集恒列第一位
 *（已缓存、fetchModels 发批前自查命中不发网）。②低优先慢拉：
 * 批与批之间留 GAP_MS 间歇，不连发抢带宽。③换剧/退出播放页即停：
 * 会话按代次管理，新会话或 stopFor 使旧代次排队中的批自弃。
 * ④已缓存的集不重拉（fetchModels 逐批自查）。</p>
 *
 * <p>缓存装不下一整部的实情（DramaCache 上限 120 条、满时淘汰最早
 * 写入的）：集数 &gt;120 的剧，最早备下的集会被后续批淘汰，见账
 * hongguo-player-prefetch-20261030，本类不静默截断、照批拉完。</p>
 */
public final class DramaSeriesPrefetch {

    /** 与 DramaClient multi 单发上限同值：一批最多 20 条 vid。 */
    static final int SLICE_SIZE = 20;
    /** 批间歇（毫秒）：慢慢拉，不跟播放抢带宽。 */
    static final long GAP_MS = 1500L;

    /** 一批的实际发送口（生产＝DramaClient.fetchModels；harness 可替换验请求序）。 */
    interface SliceFetcher {
        void fetch(List<DramaClient.ModelRequest> slice, Runnable done);
    }

    private static volatile SliceFetcher fetcher = (slice, done) ->
            // 推测口（speculative=true）：预取成败不动播放冷却状态。
            DramaClient.fetchModels(slice, new HgCallback<Map<String, DramaVideoModel>>() {
                @Override
                public void onSuccess(Map<String, DramaVideoModel> r) {
                    done.run();
                }

                @Override
                public void onError(int c, String m) {
                    done.run();
                }
            }, true);

    private static final ScheduledExecutorService TIMER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "hg-series-prefetch");
                t.setDaemon(true);
                return t;
            });

    private static final Object LOCK = new Object();
    private static int generation;
    private static String activeKey;
    private static String activeSeriesId;

    private DramaSeriesPrefetch() {
    }

    /**
     * 纯计划（harness 直验）：从当前集起先向后、再绕回补当前集之前的，
     * 剔除已缓存与重复与空 vid，切成 ≤{@link #SLICE_SIZE} 的批。
     * 当前集未被缓存时恒在第一批第一位。
     */
    static List<List<String>> plan(List<String> vidsInOrder, String currentVid,
                                   Set<String> cachedVids) {
        List<String> ordered = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int n = vidsInOrder.size();
        int start = currentVid == null ? -1 : vidsInOrder.indexOf(currentVid);
        for (int k = 0; k < n; k++) {
            String vid = vidsInOrder.get(start >= 0 ? (start + k) % n : k);
            if (vid == null || vid.isEmpty() || !seen.add(vid)) continue;
            if (cachedVids != null && cachedVids.contains(vid)) continue;
            ordered.add(vid);
        }
        List<List<String>> slices = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i += SLICE_SIZE) {
            slices.add(new ArrayList<>(ordered.subList(i, Math.min(i + SLICE_SIZE, ordered.size()))));
        }
        return slices;
    }

    /**
     * 开启全集预取会话（页面在当前集 onReady 后调用）。同剧同集表
     * 重复开启忽略；异剧开启即顶替旧会话（旧批在间歇点自弃）。
     * 已缓存集不在此预扫全账（DramaCache 逐条读太贵），由
     * fetchModels 在每批发送前自查、命中不发网。
     */
    public static void start(String seriesId, List<DramaDetail.Episode> episodes,
                             int contentType, String currentVid) {
        if (seriesId == null || episodes == null || episodes.isEmpty()) return;
        List<String> vids = new ArrayList<>();
        for (DramaDetail.Episode ep : episodes) {
            if (ep != null) vids.add(ep.getVideoId());
        }
        if (vids.isEmpty()) return;
        String key = seriesId + "#" + vids.size();
        final int gen;
        synchronized (LOCK) {
            if (key.equals(activeKey)) return;
            gen = ++generation;
            activeKey = key;
            activeSeriesId = seriesId;
        }
        runSlice(gen, plan(vids, currentVid, null), 0, contentType);
    }

    /** 退出播放页时停会话：只停 seriesId 对得上的那个，防误停新剧会话。 */
    public static void stopFor(String seriesId) {
        synchronized (LOCK) {
            if (seriesId != null && seriesId.equals(activeSeriesId)) {
                generation++;
                activeKey = null;
                activeSeriesId = null;
            }
        }
    }

    private static void runSlice(int gen, List<List<String>> slices, int index, int contentType) {
        if (!alive(gen) || index >= slices.size()) return;
        List<DramaClient.ModelRequest> reqs = new ArrayList<>();
        for (String vid : slices.get(index)) {
            reqs.add(new DramaClient.ModelRequest(vid, contentType));
        }
        fetcher.fetch(reqs, () -> {
            if (!alive(gen) || index + 1 >= slices.size()) return;
            TIMER.schedule(() -> {
                if (alive(gen)) runSlice(gen, slices, index + 1, contentType);
            }, GAP_MS, TimeUnit.MILLISECONDS);
        });
    }

    private static boolean alive(int gen) {
        synchronized (LOCK) {
            return gen == generation;
        }
    }

    /** harness 专用：替换发送口/清会话状态，生产不调用。 */
    static void setFetcherForTest(SliceFetcher f) {
        fetcher = f;
    }

    static void resetForTest() {
        synchronized (LOCK) {
            generation++;
            activeKey = null;
            activeSeriesId = null;
        }
    }
}
