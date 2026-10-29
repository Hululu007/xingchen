package com.fongmi.android.tv.hongguo.ui;

import android.os.Handler;
import android.os.Looper;

import com.fongmi.android.tv.hongguo.api.HgApiClient;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.api.HgModelCache;
import com.fongmi.android.tv.hongguo.api.HgPrefetcher;
import com.fongmi.android.tv.hongguo.model.HgAuthorHit;
import com.fongmi.android.tv.hongguo.model.HgParser;
import com.fongmi.android.tv.hongguo.model.HgSeriesDetail;
import com.fongmi.android.tv.hongguo.model.HgSeriesItem;
import com.fongmi.android.tv.hongguo.model.HgVideoModel;
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
    /** 专辑各季表缓存（进剧时 fetchAlbum 落，同一剧反复进不再重拉）。
     * 写在网络回调线程、读在主线程，用并发图（details 图是主线程内写读，不在此列）。 */
    private final Map<String, java.util.List<HgSeriesItem>> albums =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 已取过的专辑各季表；没取过返回 null（空表＝取过且无季/已下架）。 */
    public java.util.List<HgSeriesItem> albumOf(String seriesId) {
        return albums.get(seriesId);
    }

    public void putAlbum(String seriesId, java.util.List<HgSeriesItem> seasons) {
        if (seriesId != null) albums.put(seriesId, seasons);
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
        albums.clear();
    }

    private HgFeedSession() {
        // 登录态回填：凭证由壳子统一登录托管（红果提供方）持有，这里只挂取口
        com.fongmi.android.tv.hongguo.api.HgLoginAuthProvider auth =
                new com.fongmi.android.tv.hongguo.api.HgLoginAuthProvider();
        client.setAuthProvider(auth);
        fqClient.setAuthProvider(auth);
        // 关注作者备路：主路（fqClient 的 commentapi do_action）回包非 0 时
        // 转投 bookapi 的 follow/ 端点（client 侧签名传输）
        fqClient.setFollowFallback((follow, authorUid, cb) -> client.followAuthor(follow, authorUid, cb));
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
            kickAuthorResolve(head, changed);
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
                    kickAuthorResolve(head, changed);
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
                    kickAuthorResolve(head, changed);
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
            kickAuthorResolve(head, changed);
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
                    kickAuthorResolve(head, changed);
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
                    kickAuthorResolve(head, changed);
                });
            }
        });
    }

    private void applyClip(int position, HgFeedItem head, HgSeriesDetail detail) {
        seedUgcModel(detail);
        String title = detail != null && detail.getTitle() != null ? detail.getTitle() : head.title;
        HgStore.putMeta(head.seriesId, title,
                detail != null && detail.getCoverUrl() != null ? detail.getCoverUrl() : head.coverUrl,
                detail != null && detail.getEpisodeCount() > 0 ? detail.getEpisodeCount() : head.episodeCount,
                head.badge);
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
        if (detail != null && detail.getAuthorUid() != null) clipItem.authorUid = detail.getAuthorUid();
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
            if (cached.getAuthorUid() != null) enriched.authorUid = cached.getAuthorUid();
            items.set(position, enriched);
            changed.accept(position);
            kickAuthorResolve(enriched, changed);
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
                    if (detail.getAuthorUid() != null) enriched.authorUid = detail.getAuthorUid();
                    items.set(at, enriched);
                    changed.accept(at);
                    kickAuthorResolve(enriched, changed);
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                main.post(() -> changed.accept(-1));
            }
        });
    }

    /** 正剧作者 uid 反查的会话内命中缓存：按搜索名记；跨剧复用必须先过
     * verifyCachedHit 二次核对，不许拿名字相同直接补（扫查 H4）。 */
    private final Map<String, HgAuthorHit> authorHitByName = new HashMap<>();
    /** 反查已发起过的（剧＋搜索名）组合：按名记而不是只按剧——首个 hint
     * 用的名查无结果后，换一个名（作者名/出品方品牌互为备选）还要能再试
     * （扫查 H4 附带项）。 */
    private final java.util.Set<String> authorResolveTried = new java.util.HashSet<>();

    /**
     * 正剧作者 uid 兜底反查（2026-10-29 本机实拉定根，账
     * author-page-why-missing-20261029 追补）：详情口对剧集内容恒不
     * 下发作者身份（单剧/批量/账号自制剧/单集模型五路全量扫零 uid），
     * 正剧条目 authorUid 只能靠搜索「用户」签按名反查——搜索名取作者名、
     * 没有取出品方品牌（cell copyright 抬升），定正主口径＝同名精确
     * 匹配里作品数最高者（潮汐剧场实证：同名三号中作品数 628 的正主
     * uid＝3336352032369972、与结果序首位一致；公司全称类品牌多半查无
     * 账号，无果即止）。命中后给同剧全部条目回补 uid（缺名/头像一并
     * 补）、回写详情缓存让后续展开直接继承，并报首个受影响下标。
     * UGC 条目 uid 本就齐、进门即回，不走此路；失败一律静默，不打扰播放。
     * 调用方都在主线程（页表口径），网络回调经 main 转手后才碰页表。
     *
     * <p>同名误配防线（扫查 H4）：按名缓存的命中是「为另一部剧」核出来
     * 的，跨剧复用前必须二次核对（verifyCachedHit：头像、详情作者名）——
     * 核出不符不配、核不了不直接用缓存而是落回本剧自己的搜索；搜索选
     * 正主时本剧条目带头像的，同名候选先过头像核对，核不上宁可不配，
     * 不许静默补一个错 uid。</p>
     */
    private void kickAuthorResolve(HgFeedItem hint, java.util.function.IntConsumer changed) {
        if (hint == null || hint.authorUid != null || hint.seriesId == null) return;
        final boolean nameFromAuthor = hint.authorName != null && !hint.authorName.isEmpty();
        final String name = nameFromAuthor ? hint.authorName : hint.publisher;
        if (name == null || name.isEmpty()) return;
        HgAuthorHit hit = authorHitByName.get(name);
        if (hit != null) {
            int verdict = verifyCachedHit(hint, nameFromAuthor, hit);
            if (verdict > 0) {
                patchAuthor(hint.seriesId, hit, changed);
                return;
            }
            if (verdict < 0) {
                // 详情作者名与命中名冲突＝核出不是此人：此剧按未配收口，
                // 同名不再重试（按此名搜候选全是同名，换汤不换药）
                authorResolveTried.add(hint.seriesId + "\n" + name);
                return;
            }
            // 头像冲突（缓存里是重名者）或核不了（本剧无可比信号）：不直
            // 接用缓存，落到本剧自己的搜索路径——选正主有头像核对兜底，
            // 真正主在候选里才配得上，核不上宁可不配
        }
        if (!authorResolveTried.add(hint.seriesId + "\n" + name)) return;
        final String seriesId = hint.seriesId;
        final String seriesAvatar = seriesAvatarOf(seriesId, hint);
        client.searchUsers(name, new HgCallback<List<HgAuthorHit>>() {
            @Override
            public void onSuccess(List<HgAuthorHit> hits) {
                main.post(() -> {
                    HgAuthorHit best = selectAuthorHit(hits, name, seriesAvatar);
                    if (best == null) return;
                    authorHitByName.put(name, best);
                    HgSeriesDetail d = details.get(seriesId);
                    if (d != null) d.attachAuthorUid(best.uid);
                    patchAuthor(seriesId, best, changed);
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                // 反查失败静默：作者入口保持现状，不打扰播放
            }
        });
    }

    /** 本剧已知的作者头像：hint 自带优先，其次同剧任一条目带的；都没有为 null（无可比信号）。 */
    private String seriesAvatarOf(String seriesId, HgFeedItem hint) {
        if (hint != null && hint.authorAvatar != null && !hint.authorAvatar.isEmpty()) {
            return hint.authorAvatar;
        }
        for (HgFeedItem it : items) {
            if (it.seriesId != null && it.seriesId.equals(seriesId)
                    && it.authorAvatar != null && !it.authorAvatar.isEmpty()) {
                return it.authorAvatar;
            }
        }
        return null;
    }

    /**
     * 缓存命中跨剧复用的二次核对：1＝有正向证据（头像一致／详情作者名
     * 一致）且无冲突；-1＝详情作者名与命中名冲突（硬否决）；0＝其余
     * （头像冲突说明缓存里是重名者、无可比信号说明核不了）——一律不用
     * 缓存、落回本剧自己的搜索路径再定。
     */
    private int verifyCachedHit(HgFeedItem hint, boolean nameFromAuthor, HgAuthorHit hit) {
        boolean positive = false;
        String avatar = seriesAvatarOf(hint.seriesId, hint);
        if (avatar != null && hit.avatar != null) {
            // 头像两边都有却对不上：缓存里是重名者，名字再像也不是此人，
            // 直接落回本剧搜索（下方详情名一致也不许翻案）
            if (!avatar.equals(hit.avatar)) return 0;
            positive = true;
        }
        if (nameFromAuthor) {
            HgSeriesDetail d = details.get(hint.seriesId);
            String detailName = d == null ? null : d.getAuthorName();
            if (detailName != null && !detailName.isEmpty()) {
                if (!detailName.equals(hit.name)) return -1;
                positive = true;
            }
        }
        return positive ? 1 : 0;
    }

    /**
     * 搜索候选定正主：同名精确匹配＋作品数最高（原口径）；本剧条目带
     * 头像时先过头像核对——候选头像对不上的视同重名者剔除，全部对不
     * 上（或都没带头像可核）时返回 null 不配，不许拿作品数硬认。
     */
    private static HgAuthorHit selectAuthorHit(List<HgAuthorHit> hits, String name, String seriesAvatar) {
        HgAuthorHit best = null;
        if (hits != null) {
            for (HgAuthorHit h : hits) {
                if (h == null || !name.equals(h.name)) continue;
                if (seriesAvatar != null && (h.avatar == null || !seriesAvatar.equals(h.avatar))) continue;
                if (best == null || h.workCount > best.workCount) best = h;
            }
        }
        return best;
    }

    /** 把命中的作者身份回补到同剧全部条目（只补缺的），报首个受影响下标。 */
    private void patchAuthor(String seriesId, HgAuthorHit hit,
                             java.util.function.IntConsumer changed) {
        int first = -1;
        for (int i = 0; i < items.size(); i++) {
            HgFeedItem it = items.get(i);
            if (it.seriesId == null || !it.seriesId.equals(seriesId)) continue;
            boolean lacksName = it.authorName == null || it.authorName.isEmpty();
            boolean lacksAvatar = it.authorAvatar == null || it.authorAvatar.isEmpty();
            if (it.authorUid == null || lacksName || lacksAvatar) {
                items.set(i, it.withAuthor(
                        it.authorUid == null ? hit.uid : null,
                        lacksName ? hit.name : null,
                        lacksAvatar ? hit.avatar : null));
            }
            if (first < 0) first = i;
        }
        if (first >= 0 && changed != null) changed.accept(first);
    }

    /** UGC 起播准备的在途等待表（按 vid 合并同条多次落定，只发一次详情请求）。 */
    private final Map<String, List<java.util.function.Consumer<Boolean>>> ugcWaiters = new HashMap<>();

    /**
     * UGC 短片落定起播准备（推荐流落定链，定根账 feed-item-fetch-fail
     * -20261029 §2/§4）：UGC vid 在单集取模型口恒回 101000，模型只随
     * UGC 批量详情（source=17，即 {@link HgApiClient#fetchUgcVideoDetail}）
     * 下发——起播前先拉详情把 model 灌进缓存，灌到才放行起播（done
     * 回 true）；回包没带 model／拉不到按取数失败收口（done 回 false），
     * 不灌空、不许退回单集口假播。缓存已有（含作者页预灌、展开定形态
     * 灌过的）直接放行，不重复发请求。回调在主线程执行。
     */
    public void prepareUgcClip(int position, java.util.function.Consumer<Boolean> done) {
        if (position < 0 || position >= items.size()) {
            done.accept(false);
            return;
        }
        HgFeedItem item = items.get(position);
        String vid = item.videoId;
        if (vid == null) {
            done.accept(false);
            return;
        }
        if (cache.hasFresh(vid)) {
            done.accept(true);
            return;
        }
        synchronized (ugcWaiters) {
            List<java.util.function.Consumer<Boolean>> ws = ugcWaiters.get(vid);
            if (ws != null) {
                ws.add(done);
                return;
            }
            ws = new ArrayList<>();
            ws.add(done);
            ugcWaiters.put(vid, ws);
        }
        client.fetchUgcVideoDetail(vid, new HgCallback<HgSeriesDetail>() {
            @Override
            public void onSuccess(HgSeriesDetail detail) {
                main.post(() -> {
                    seedUgcModel(detail);
                    // 以缓存复核定成败：详情没带 model 时 seedUgcModel
                    // 静默不灌，hasFresh 仍为假——按取数失败收口
                    finishUgcPrepare(vid, cache.hasFresh(vid));
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                main.post(() -> finishUgcPrepare(vid, false));
            }
        });
    }

    private void finishUgcPrepare(String vid, boolean ready) {
        List<java.util.function.Consumer<Boolean>> ws;
        synchronized (ugcWaiters) {
            ws = ugcWaiters.remove(vid);
        }
        if (ws == null) return;
        for (java.util.function.Consumer<Boolean> c : ws) c.accept(ready);
    }

    /**
     * UGC 单条视频的随详情 model 灌缓存：动态视频的 model 装在详情里
     * （UgcVideoDetail.video_model）、单集取模型口对它回 101000——展开
     * 定形态时先把 model 按集 vid 入缓存，解链器命中缓存直接起播。
     */
    private void seedUgcModel(HgSeriesDetail detail) {
        if (detail == null || detail.getUgcVideoModel() == null) return;
        for (HgSeriesDetail.Episode ep : detail.getEpisodes()) {
            HgVideoModel m = HgParser.parseUgcVideoModel(detail.getUgcVideoModel(),
                    ep.getVideoId(), detail.getUgcVideoWidth(), detail.getUgcVideoHeight());
            if (m != null) cache.put(ep.getVideoId(), m);
        }
    }

    private void applyDetail(int position, HgFeedItem head, HgSeriesDetail detail) {
        seedUgcModel(detail);
        String title = detail != null && detail.getTitle() != null ? detail.getTitle() : head.title;
        // 元信息顺手落盘：追更/收藏/历史列表页靠它显示标题封面（不另拉接口）
        HgStore.putMeta(head.seriesId, title,
                detail != null && detail.getCoverUrl() != null ? detail.getCoverUrl() : head.coverUrl,
                detail != null && detail.getEpisodeCount() > 0 ? detail.getEpisodeCount() : head.episodeCount,
                head.badge);
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
            // 作者 uid：详情抬升到的优先（HgSeriesDetail.getAuthorUid，
            // 现行回包恒不下发、口先建对），没有再从进剧条目继承（流条目
            // cell 抬升来的）；两处都没有时展开后还有搜索反查兜底回补
            // （kickAuthorResolve）。
            page.authorUid = detail.getAuthorUid() != null ? detail.getAuthorUid() : head.authorUid;
            page.authorDesc = head.authorDesc;
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
