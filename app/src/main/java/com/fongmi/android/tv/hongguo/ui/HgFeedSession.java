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
    public final com.fongmi.android.tv.hongguo.api.HgCommentClient fqClient =
            new com.fongmi.android.tv.hongguo.api.HgCommentClient();
    public final HgModelCache cache = new HgModelCache();
    public final HgPrefetcher prefetcher;
    public final HgPlayResolver resolver;
    /** 四阶段默认互动实现：已登录走真接口、未登录页面先拉登录；本地实现保留作对照。 */
    public final HgRemoteInteractions remoteInteractions;
    public final HgInteractions interactions;

    /** 当前流页表（主线程限定）。 */
    public final List<HgFeedItem> items = new ArrayList<>();
    /** 评论总数（按剧，评论面板真拉到才有；右列计数以此为准，没数不亮）。 */
    public final Map<String, Long> commentCounts = new HashMap<>();

    public String commentCountOf(String seriesId) {
        Long n = seriesId == null ? null : commentCounts.get(seriesId);
        if (n == null || n <= 0) return null;
        return n >= 10000 ? String.format(java.util.Locale.US, "%.1f万", n / 10000.0) : String.valueOf(n);
    }
    private final Map<String, HgSeriesDetail> details = new HashMap<>();
    /** 专辑各季表缓存（进剧时 fetchAlbum 落，同一剧反复进不再重拉）。 */
    private final Map<String, java.util.List<HgSeriesItem>> albums = new HashMap<>();

    /** 已取过的专辑各季表；没取过返回 null（空表＝取过且无季/已下架）。 */
    public java.util.List<HgSeriesItem> albumOf(String seriesId) {
        return albums.get(seriesId);
    }

    public void putAlbum(String seriesId, java.util.List<HgSeriesItem> seasons) {
        if (seriesId != null) albums.put(seriesId, seasons);
    }

    /** 详情缓存（进剧取专辑 id 用）；没拉过返回 null。 */
    public HgSeriesDetail detailOf(String seriesId) {
        return details.get(seriesId);
    }
    private final Handler main = new Handler(Looper.getMainLooper());

    /**
     * 「清红果缓存」落点：红果运行时的两份进程内缓存（取模型＋集表详情）整清。
     * 旧版抽屉只清模型缓存一份、详情缓存清不到，与按钮字面不符（审计实证）。
     * 图片缓存是全 App 共用的 Glide 池，不归红果单独清，不在此列。
     */
    public void clearRuntimeCache() {
        cache.clear();
        details.clear();
    }

    private HgFeedSession() {
        // 登录态回填：凭证由壳子统一登录托管（红果提供方）持有，这里只挂取口
        com.fongmi.android.tv.hongguo.api.HgLoginAuthProvider auth =
                new com.fongmi.android.tv.hongguo.api.HgLoginAuthProvider();
        client.setAuthProvider(auth);
        fqClient.setAuthProvider(auth);
        this.remoteInteractions = new HgRemoteInteractions(client, fqClient);
        this.interactions = this.remoteInteractions;
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
     * 详情没拿到/集表为空时不退化（旧「videoId＝seriesId 假单集」已定罪删除，
     * 见 applyDetail 内注）：剧头原样保留、expandTried 解开待重试。
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

    /**
     * 流里剧头定形态（短片流专用，研究账 §2.3）：拉详情只为定下这一条短片，
     * 不把整剧集表插进流页表（与 {@link #expandHead} 的集流展开相对）。
     * 有集表取首集为短片（vid＋集序＋作者/简介一并富集）；未上线剧
     * （appointment 且无集）剧头原样留作预约面；详情拿不到/集表为空时
     * 不退化（旧「videoId＝seriesId 假短片」已定罪删除，见 applyClip 内注）。
     * changed 回调在主线程执行（-1＝无变化）。
     */
    public void resolveClip(int position, java.util.function.IntConsumer changed) {
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
            applyClip(position, head, cached);
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
                    applyClip(at, head, detail);
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
                    applyClip(at, head, null);
                    changed.accept(at);
                });
            }
        });
    }

    private void applyClip(int position, HgFeedItem head, HgSeriesDetail detail) {
        String title = detail != null && detail.getTitle() != null ? detail.getTitle() : head.title;
        HgStore.putMeta(head.seriesId, title,
                detail != null && detail.getCoverUrl() != null ? detail.getCoverUrl() : head.coverUrl,
                detail != null && detail.getEpisodeCount() > 0 ? detail.getEpisodeCount() : head.episodeCount);
        String desc = detail != null && detail.getDesc() != null ? detail.getDesc() : head.desc;
        String author = detail == null ? null : detail.getAuthorName();
        String avatar = avatarOf(detail);
        List<HgSeriesDetail.Episode> eps = detail == null ? null : detail.getEpisodes();
        if ((eps == null || eps.isEmpty()) && detail != null && detail.hasAppointment()) {
            // 未上线剧：不退化成假短片去取模型，剧头留作预约面（同 applyDetail 口径）
            head.reservable = true;
            head.dueStartText = detail.getDueStartText();
            HgStore.setReserved(head.seriesId, detail.isAppointmentSubscribed());
            items.set(position, head);
            return;
        }
        if (eps == null || eps.isEmpty()) {
            // 详情没拿到/集表为空：不退化。旧退化把 seriesId 当 videoId 喂
            // 取模型口——服务端对剧 id 恒回 101000 错误体（本机探针实证：
            // 176 字节、data={}）→ 模型恒空 → E1，且 expandTried 锁死后点
            // 重试永远重复同一错（E1 案定罪，fix-batch-1455.md）。剧头原样
            // 保留并解开 expandTried，下次落定或点重试时重新拉详情。
            head.expandTried = false;
            items.set(position, head);
            return;
        }
        List<HgSeriesDetail.Episode> sorted = new ArrayList<>(eps);
        sorted.sort((a, b) -> Integer.compare(
                a.getIndex() < 0 ? Integer.MAX_VALUE : a.getIndex(),
                b.getIndex() < 0 ? Integer.MAX_VALUE : b.getIndex()));
        HgSeriesDetail.Episode first = sorted.get(0);
        int idx = first.getIndex() > 0 ? first.getIndex() : 1;
        HgFeedItem clipItem = head.withDetail(first.getVideoId(), idx, author, avatar, desc);
        if (detail != null && detail.getStatement() != null) clipItem.statement = detail.getStatement();
        items.set(position, clipItem);
    }

    /**
     * 短片富集：已有 vid 的短片落定时后台补详情里的作者/头像/简介
     * （cell 面没有作者字段，账 §2.5/§3.3），只换当前页、不动页表结构。
     * 每条只富集一次（expandTried 记账），失败静默（页面照常播）。
     */
    public void enrichClip(int position, java.util.function.IntConsumer changed) {
        if (position < 0 || position >= items.size()) {
            changed.accept(-1);
            return;
        }
        HgFeedItem item = items.get(position);
        if (!item.clip || item.expandTried || item.seriesId == null) {
            changed.accept(-1);
            return;
        }
        item.expandTried = true;
        HgSeriesDetail cached = details.get(item.seriesId);
        if (cached != null) {
            HgFeedItem enriched = item.withDetail(null, -1,
                    cached.getAuthorName(), avatarOf(cached),
                    cached.getDesc() != null ? cached.getDesc() : item.desc)
                    .withSeriesTitle(cached.getTitle());
            if (cached.getStatement() != null) enriched.statement = cached.getStatement();
            items.set(position, enriched);
            changed.accept(position);
            return;
        }
        client.fetchVideoDetail(item.seriesId, new HgCallback<HgSeriesDetail>() {
            @Override
            public void onSuccess(HgSeriesDetail detail) {
                main.post(() -> {
                    if (detail != null) details.put(item.seriesId, detail);
                    int at = items.indexOf(item);
                    if (at < 0 || detail == null) {
                        changed.accept(-1);
                        return;
                    }
                    HgFeedItem enriched = item.withDetail(null, -1,
                            detail.getAuthorName(), avatarOf(detail),
                            detail.getDesc() != null ? detail.getDesc() : item.desc)
                            .withSeriesTitle(detail.getTitle());
                    if (detail.getStatement() != null) enriched.statement = detail.getStatement();
                    items.set(at, enriched);
                    changed.accept(at);
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                main.post(() -> changed.accept(-1));
            }
        });
    }

    private void applyDetail(int position, HgFeedItem head, HgSeriesDetail detail) {
        String title = detail != null && detail.getTitle() != null ? detail.getTitle() : head.title;
        // 元信息顺手落盘：追更/收藏/历史列表页靠它显示标题封面（不另拉接口）
        HgStore.putMeta(head.seriesId, title,
                detail != null && detail.getCoverUrl() != null ? detail.getCoverUrl() : head.coverUrl,
                detail != null && detail.getEpisodeCount() > 0 ? detail.getEpisodeCount() : head.episodeCount);
        String desc = detail != null && detail.getDesc() != null ? detail.getDesc() : head.desc;
        String cover = detail != null && detail.getCoverUrl() != null ? detail.getCoverUrl() : head.coverUrl;
        String author = detail == null ? null : detail.getAuthorName();
        String avatar = avatarOf(detail);
        int count = detail != null && detail.getEpisodeCount() > 0 ? detail.getEpisodeCount() : head.episodeCount;
        List<HgSeriesDetail.Episode> eps = detail == null ? null : detail.getEpisodes();
        if ((eps == null || eps.isEmpty()) && detail != null && detail.hasAppointment()) {
            // 未上线剧：详情带 appointment 且无集可播——不退化成假单集去取模型，
            // 剧头原样留作预约页（封面＋预约片），服务端预约态同步进镜像。
            head.reservable = true;
            head.dueStartText = detail.getDueStartText();
            HgStore.setReserved(head.seriesId, detail.isAppointmentSubscribed());
            items.set(position, head);
            return;
        }
        if (eps == null || eps.isEmpty()) {
            // 同 applyClip 口径：不退化成 videoId=seriesId 的假单集（E1 定罪，
            // fix-batch-1455.md）——剧头保留、解开 expandTried 待重试拉详情
            head.expandTried = false;
            items.set(position, head);
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
            HgFeedItem page = HgFeedItem.episode(head.seriesId, ep.getVideoId(), idx,
                    count > 0 ? count : sorted.size(), title, head.category, author, avatar, desc, cover);
            if (detail.getStatement() != null) page.statement = detail.getStatement();
            pages.add(page);
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
