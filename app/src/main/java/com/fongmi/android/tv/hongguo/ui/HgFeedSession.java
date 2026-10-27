package com.fongmi.android.tv.hongguo.ui;

import android.os.Handler;
import android.os.Looper;

import com.fongmi.android.tv.hongguo.api.HgApiClient;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.api.HgModelCache;
import com.fongmi.android.tv.hongguo.api.HgPrefetcher;
import com.fongmi.android.tv.hongguo.model.HgSeriesDetail;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 刷剧会话：全 App 一份，持有数据层三件（客户端/模型缓存/预取器）、解链器、
 * 互动实现，以及当前流的页表与已拉详情缓存。播放页与横屏页共用这一份，
 * 页面只消费、不各自新建——流表也是横屏页接续同一集的交接面。
 *
 * <p>线程口径：页表只在主线程动；接口回调在 OkHttp 分发线程进来，本类统一
 * 经主线程 Handler 转手后再碰页表。</p>
 */
public final class HgFeedSession {

    private static HgFeedSession instance;

    public static synchronized HgFeedSession get() {
        if (instance == null) instance = new HgFeedSession();
        return instance;
    }

    public final HgApiClient client = new HgApiClient();
    public final HgModelCache cache = new HgModelCache();
    public final HgPrefetcher prefetcher;
    public final HgPlayResolver resolver;
    public final HgInteractions interactions = new HgLocalInteractions();

    /** 当前流页表（主线程限定）。 */
    public final List<HgFeedItem> items = new ArrayList<>();
    private final Map<String, HgSeriesDetail> details = new HashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    private HgFeedSession() {
        // 登录态回填：凭证由壳子统一登录托管（红果提供方）持有，这里只挂取口
        client.setAuthProvider(new com.fongmi.android.tv.hongguo.api.HgLoginAuthProvider());
        this.prefetcher = new HgPrefetcher(client, cache);
        this.resolver = new HgPlayResolver(client, cache);
    }

    public HgSeriesDetail detailOf(String seriesId) {
        return seriesId == null ? null : details.get(seriesId);
    }

    /** 同剧全部集 id（页表顺序）＋某集在其中的下标，供 prefetchAround 用。 */
    public List<String> seriesVideoIds(String seriesId) {
        List<String> out = new ArrayList<>();
        for (HgFeedItem it : items) {
            if (it.seriesId != null && it.seriesId.equals(seriesId) && it.videoId != null) out.add(it.videoId);
        }
        return out;
    }

    public int indexWithinSeries(HgFeedItem item) {
        if (item == null || item.videoId == null) return 0;
        int idx = 0;
        for (HgFeedItem it : items) {
            if (it.seriesId != null && it.seriesId.equals(item.seriesId) && it.videoId != null) {
                if (it.videoId.equals(item.videoId)) return idx;
                idx++;
            }
        }
        return 0;
    }

    /**
     * 展开剧头：拉详情→页表里把剧头换成逐集页（首集占原位、其余集依次插入）。
     * 详情字段口径未定版（见 HgApiClient.fetchVideoDetail 注）：集表为空时退化为
     * 单集页（videoId＝seriesId，取模型时再兜底），不让页面卡在剧头。
     * changed 回调在主线程执行，参数为受影响的起始下标与插入集数（-1 起始＝无变化）。
     */
    public void expandHead(int position, java.util.function.IntConsumer changed) {
        if (position < 0 || position >= items.size()) {
            changed.accept(-1);
            return;
        }
        HgFeedItem head = items.get(position);
        if (!head.seriesHead || head.expandTried) {
            changed.accept(-1);
            return;
        }
        head.expandTried = true;
        HgSeriesDetail cached = details.get(head.seriesId);
        if (cached != null) {
            applyDetail(position, head, cached);
            changed.accept(position);
            return;
        }
        client.fetchVideoDetail(head.seriesId, new HgCallback<HgSeriesDetail>() {
            @Override
            public void onSuccess(HgSeriesDetail detail) {
                main.post(() -> {
                    if (detail != null) details.put(head.seriesId, detail);
                    int at = items.indexOf(head);
                    if (at < 0) {
                        changed.accept(-1);
                        return;
                    }
                    applyDetail(at, head, detail);
                    changed.accept(at);
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                main.post(() -> {
                    int at = items.indexOf(head);
                    if (at < 0) {
                        changed.accept(-1);
                        return;
                    }
                    applyDetail(at, head, null);
                    changed.accept(at);
                });
            }
        });
    }

    private void applyDetail(int position, HgFeedItem head, HgSeriesDetail detail) {
        String title = detail != null && detail.getTitle() != null ? detail.getTitle() : head.title;
        String desc = detail != null && detail.getDesc() != null ? detail.getDesc() : head.desc;
        String cover = detail != null && detail.getCoverUrl() != null ? detail.getCoverUrl() : head.coverUrl;
        String author = detail == null ? null : detail.getAuthorName();
        String avatar = avatarOf(detail);
        int count = detail != null && detail.getEpisodeCount() > 0 ? detail.getEpisodeCount() : head.episodeCount;
        List<HgSeriesDetail.Episode> eps = detail == null ? null : detail.getEpisodes();
        if (eps == null || eps.isEmpty()) {
            // 退化单集：集 id 未知时用 seriesId 当 videoId 尝试取模型
            HgStore.Progress p = HgStore.progressOf(head.seriesId);
            items.set(position, HgFeedItem.episode(head.seriesId, head.seriesId,
                    p == null ? 1 : Math.max(1, p.episodeIndex), count,
                    title, head.category, author, avatar, desc, cover));
            return;
        }
        List<HgSeriesDetail.Episode> sorted = new ArrayList<>(eps);
        sorted.sort((a, b) -> Integer.compare(
                a.getIndex() < 0 ? Integer.MAX_VALUE : a.getIndex(),
                b.getIndex() < 0 ? Integer.MAX_VALUE : b.getIndex()));
        List<HgFeedItem> pages = new ArrayList<>();
        int i = 0;
        for (HgSeriesDetail.Episode ep : sorted) {
            int idx = ep.getIndex() > 0 ? ep.getIndex() : i + 1;
            pages.add(HgFeedItem.episode(head.seriesId, ep.getVideoId(), idx,
                    count > 0 ? count : sorted.size(), title, head.category, author, avatar, desc, cover));
            i++;
        }
        items.set(position, pages.get(0));
        if (pages.size() > 1) items.addAll(position + 1, pages.subList(1, pages.size()));
    }

    /** 作者头像：详情模型无此字段，从 raw 里容忍抽（author_avatar/avatar_url/avatar）。 */
    private String avatarOf(HgSeriesDetail detail) {
        if (detail == null || detail.getRaw() == null) return null;
        JsonObject raw = detail.getRaw();
        JsonObject data = raw.has("data") && raw.get("data").isJsonObject() ? raw.getAsJsonObject("data") : raw;
        for (String key : new String[]{"author_avatar", "avatar_url", "avatar"}) {
            JsonElement e = data.get(key);
            if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                String v = e.getAsString();
                if (!v.isEmpty()) return v;
            }
        }
        return null;
    }
}
