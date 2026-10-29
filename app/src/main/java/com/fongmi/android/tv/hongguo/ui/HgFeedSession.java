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
    /**
     * 集流借表快照栈（串剧定根，账 video-issues-20261029 V5）：页表全进程
     * 一份，集流实例 init 会整表换成自家集表——动表前把在下实例的表压栈
     * 存好，集流实例销毁时弹栈整表恢复；不恢复则在下流页的画面（它自家
     * 适配器早先绑好的像素）与表（已成别剧集表）脱节，点击按位置回表取
     * 件必然串剧。栈式是为嵌套（流→竖屏集流→横屏实例同为集流形态、逐
     * 层借表逐层还，销毁序与压栈序天然同为后进先出）。只存条目引用副本：
     * 条目是会话内共享对象，集流只动自家表、不改写流表条目本体（展开/
     * 富集都发生在当时的共享表上、即集流自己的表），浅拷贝足够。
     */
    private final java.util.Deque<List<HgFeedItem>> tableSnapshots = new java.util.ArrayDeque<>();

    /** 集流实例动表前调用：当前页表压栈，与 {@link #restoreTableSnapshot()} 成对。 */
    public void snapshotTableForSeries() {
        tableSnapshots.push(new ArrayList<>(items));
    }

    /**
     * 集流实例销毁时调用：弹栈把页表整表恢复成借表前的样子。原地
     * clear＋addAll——各实例的适配器持的是同一份 List 引用，换对象会让
     * 在下实例的适配器永远指着旧表。没压过栈（不成对）时不动表。
     */
    public void restoreTableSnapshot() {
        List<HgFeedItem> snap = tableSnapshots.poll();
        if (snap == null) return;
        items.clear();
        items.addAll(snap);
    }
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
            if (detail.getFollowedCnt() > 0) head.favCount = HgFeedItem.formatCount(detail.getFollowedCnt());
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
        // 展示集序同步成实际在播的这一集（简介「第N集｜」前缀用，
        // 纯展示字段 pushIndex、不碰 episodeIndex 的进度语义）
        clipItem.pushIndex = idx;
        if (detail != null && detail.getStatement() != null) clipItem.statement = detail.getStatement();
        if (detail != null && detail.getAuthorUid() != null) clipItem.authorUid = detail.getAuthorUid();
        // 星数富集：cell 面没带 followed_cnt 的剧头（存档实证 fav=0/空）
        // 落定后用详情的真值补右列星数——详情模型本就带此字段，此前
        // 富集只补作者/声明、星数漏补（feed-infostack-reaudit-20261029）
        if (detail != null && detail.getFollowedCnt() > 0) {
            clipItem.favCount = HgFeedItem.formatCount(detail.getFollowedCnt());
        }
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
            if (cached.getFollowedCnt() > 0) enriched.favCount = HgFeedItem.formatCount(cached.getFollowedCnt());
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
                    if (detail.getFollowedCnt() > 0) enriched.favCount = HgFeedItem.formatCount(detail.getFollowedCnt());
                    items.set(at, enriched);
                    changed.accept(at);
                    kickAuthorResolve(enriched, changed);
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                main.post(() -> {
                    changed.accept(-1);
                    // 详情没拉到：作者名无从富集，但出品方词还在条目上，
                    // 反查照样能试（词表在核验链里组装）
                    kickAuthorResolve(item, changed);
                });
            }
        });
    }

    /** 反查核验过的正主缓存：按搜索词记，只存作品核验通过的命中，
     * 用作同名候选的优先核验序；跨剧复用仍须过本剧作品核验，不许拿
     * 名字相同直接回补（旧 verifyCachedHit 的名/头像核对由作品核验
     * 整体取代——正剧详情根本不给头像，用户签头像又是签名缩略图，
     * 全等比对只会误杀）。 */
    private final Map<String, HgAuthorHit> authorHitByName = new HashMap<>();
    /** 反查已完整试过的（剧＋搜索词）组合：词表逐词推进的记账，
     * 试尽的词本会话内不再重发。 */
    private final java.util.Set<String> authorResolveTried = new java.util.HashSet<>();
    /** 点按反查的在途等待表（按剧合并同剧连点，只发一次核验链）。 */
    private final Map<String, List<java.util.function.Consumer<Boolean>>> authorTapWaiters = new HashMap<>();

    /**
     * 正剧作者 uid 兜底反查（2026-10-29 晚实拉定根，账
     * author-entry-still-dead-20261029）：阅读面全口不下发正剧出品方
     * 账号身份（流 cell 只有 copyright、单剧详情 410KB 全量扫零作者
     * 字段、漫剧频道 1.9MB 全扫 ugc_user_info 零出现），正剧条目
     * authorUid 只能靠搜索「用户」签（tab_type=27）按名反查、再以
     * 作品核验定正主：
     *
     * <p>词表逐词试（条目作者名→详情作者名→出品方品牌），含「有限
     * 公司」的公司全称词直接跳过（实拉：用户签对公司词回空壳/零候
     * 选，灵龙科技、北京种花寻梦影视有限公司同型）；精确同名候选逐
     * 个拉剧作列（tab_type=11、time_desc、最多 2 面）查本剧
     * seriesId，命中者才是正主——旧「同名取 celebrity_work_cnt 最
     * 高」已被九天漫剧实证判死（正主作品数 2、粉丝 3052 与官方图全
     * 对上；错号作品数 914、粉丝 30 万，点开是别人主页比打不开更
     * 坏）；头像全等硬门一并摘除（理由见 authorHitByName 注）。全
     * 不中不配、不硬认。命中后给同剧全部条目回补 uid（缺名/头像一
     * 并补）、回写详情缓存，并报首个受影响下标。</p>
     *
     * <p>两个入口共用核验链：落定富集的 kickAuthorResolve（静默、
     * 不打扰播放）与点头像的 resolveAuthorForTap（必有回音，见
     * HongguoFeedActivity.onAuthor）。UGC 条目 uid 本就齐、不走此
     * 路。调用方都在主线程，网络回调经 main 转手后才碰页表。</p>
     */
    private void kickAuthorResolve(HgFeedItem hint, java.util.function.IntConsumer changed) {
        if (hint == null || hint.authorUid != null || hint.seriesId == null) return;
        authorResolveChain(hint, changed, null);
    }

    /**
     * 点头像触发的反查：与落定链同一核验链，但结果必回 done——
     * true＝已回补（调用方重读条目开作者页），false＝词表试尽无正
     * 主（调用方给用户明确反馈）。同剧在途合并，连点只发一次链。
     */
    public void resolveAuthorForTap(HgFeedItem item, java.util.function.Consumer<Boolean> done) {
        if (item == null || done == null) return;
        if (item.authorUid != null && !item.authorUid.isEmpty()) {
            done.accept(true);
            return;
        }
        if (item.seriesId == null) {
            done.accept(false);
            return;
        }
        for (HgFeedItem it : items) {
            if (item.seriesId.equals(it.seriesId)
                    && it.authorUid != null && !it.authorUid.isEmpty()) {
                done.accept(true);
                return;
            }
        }
        synchronized (authorTapWaiters) {
            List<java.util.function.Consumer<Boolean>> ws = authorTapWaiters.get(item.seriesId);
            if (ws != null) {
                ws.add(done);
                return;
            }
            ws = new ArrayList<>();
            ws.add(done);
            authorTapWaiters.put(item.seriesId, ws);
        }
        authorResolveChain(item, null, ok -> finishAuthorTap(item.seriesId, ok));
    }

    private void finishAuthorTap(String seriesId, boolean ok) {
        List<java.util.function.Consumer<Boolean>> ws;
        synchronized (authorTapWaiters) {
            ws = authorTapWaiters.remove(seriesId);
        }
        if (ws == null) return;
        for (java.util.function.Consumer<Boolean> c : ws) c.accept(ok);
    }

    /** 反查词表：条目作者名→详情作者名→出品方品牌，去空去重去公司全称。 */
    private List<String> authorResolveWords(HgFeedItem hint) {
        List<String> words = new ArrayList<>();
        addResolveWord(words, hint.authorName);
        HgSeriesDetail d = details.get(hint.seriesId);
        addResolveWord(words, d == null ? null : d.getAuthorName());
        addResolveWord(words, hint.publisher);
        return words;
    }

    private static void addResolveWord(List<String> words, String word) {
        if (word == null) return;
        String w = word.trim();
        if (w.isEmpty() || words.contains(w)) return;
        // 公司全称（…有限公司）在用户签里没有账号（实拉空壳/零候选），
        // 发出去只白等一轮往返，直接跳过
        if (w.contains("有限公司")) return;
        words.add(w);
    }

    private void authorResolveChain(HgFeedItem hint, java.util.function.IntConsumer changed,
                                    java.util.function.Consumer<Boolean> done) {
        List<String> words = authorResolveWords(hint);
        if (words.isEmpty()) {
            if (done != null) done.accept(false);
            return;
        }
        tryResolveWord(hint, words, 0, changed, done);
    }

    private void tryResolveWord(HgFeedItem hint, List<String> words, int wi,
                                java.util.function.IntConsumer changed,
                                java.util.function.Consumer<Boolean> done) {
        if (wi >= words.size()) {
            if (done != null) done.accept(false);
            return;
        }
        final String name = words.get(wi);
        if (!authorResolveTried.add(hint.seriesId + "\n" + name)) {
            tryResolveWord(hint, words, wi + 1, changed, done);
            return;
        }
        client.searchUsers(name, new HgCallback<List<HgAuthorHit>>() {
            @Override
            public void onSuccess(List<HgAuthorHit> hits) {
                main.post(() -> {
                    List<HgAuthorHit> cands = new ArrayList<>();
                    if (hits != null) {
                        for (HgAuthorHit h : hits) {
                            if (h != null && name.equals(h.name)) cands.add(h);
                        }
                    }
                    // 核验过的正主排首位先核（多半一击命中，省请求）
                    HgAuthorHit cached = authorHitByName.get(name);
                    if (cached != null) {
                        cands.removeIf(h -> cached.uid.equals(h.uid));
                        cands.add(0, cached);
                    }
                    if (cands.isEmpty()) {
                        tryResolveWord(hint, words, wi + 1, changed, done);
                        return;
                    }
                    verifyAuthorCandidate(hint, name, cands, 0, changed,
                            () -> tryResolveWord(hint, words, wi + 1, changed, done), done);
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                main.post(() -> tryResolveWord(hint, words, wi + 1, changed, done));
            }
        });
    }

    /** 逐个候选作品核验：首个剧作列含本剧的即正主；全不中走 next（换词）。 */
    private void verifyAuthorCandidate(HgFeedItem hint, String name, List<HgAuthorHit> cands, int ci,
                                       java.util.function.IntConsumer changed, Runnable next,
                                       java.util.function.Consumer<Boolean> done) {
        if (ci >= cands.size()) {
            next.run();
            return;
        }
        HgAuthorHit cand = cands.get(ci);
        verifyCandidateWorks(hint.seriesId, cand, "", 0, ok -> {
            if (ok) {
                authorHitByName.put(name, cand);
                HgSeriesDetail d = details.get(hint.seriesId);
                if (d != null) d.attachAuthorUid(cand.uid);
                patchAuthor(hint.seriesId, cand, changed);
                if (done != null) done.accept(true);
            } else {
                verifyAuthorCandidate(hint, name, cands, ci + 1, changed, next, done);
            }
        });
    }

    /** 作品核验：拉候选的剧作列（tab_type=11）查本剧，最多 2 面。 */
    private void verifyCandidateWorks(String seriesId, HgAuthorHit cand, String cursor, int page,
                                      java.util.function.Consumer<Boolean> verdict) {
        fqClient.authorProfileList(cand.uid, 11, -1, "time_desc", cursor,
                new HgCallback<com.fongmi.android.tv.hongguo.model.HgAuthorWork.Page>() {
                    @Override
                    public void onSuccess(com.fongmi.android.tv.hongguo.model.HgAuthorWork.Page p) {
                        main.post(() -> {
                            if (p != null && p.items != null) {
                                for (com.fongmi.android.tv.hongguo.model.HgAuthorWork w : p.items) {
                                    if (w != null && seriesId.equals(w.seriesId)) {
                                        verdict.accept(true);
                                        return;
                                    }
                                }
                            }
                            if (p != null && p.hasMore && p.nextCursor != null && !p.nextCursor.isEmpty()
                                    && page + 1 < 2) {
                                verifyCandidateWorks(seriesId, cand, p.nextCursor, page + 1, verdict);
                            } else {
                                verdict.accept(false);
                            }
                        });
                    }

                    @Override
                    public void onError(int code, String msg) {
                        main.post(() -> verdict.accept(false));
                    }
                });
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
        fetchUgc(vid);
    }

    /** UGC 详情实发（等待表已登记）：成败统一经 finishUgcPrepare 收口。 */
    private void fetchUgc(String vid) {
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
                // 官方取数链口径：失败即按取数失败收口，不叠自有恢复
                // 机制（轮次/换档案已整批摘除，账 feed-stuck-autoscroll
                // -20261029 主线节）
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
