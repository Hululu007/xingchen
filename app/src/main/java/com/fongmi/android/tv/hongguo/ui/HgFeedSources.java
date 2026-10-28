package com.fongmi.android.tv.hongguo.ui;

import com.fongmi.android.tv.hongguo.api.HgApiClient;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.api.HgRequests;
import com.fongmi.android.tv.hongguo.model.HgCellPage;
import com.fongmi.android.tv.hongguo.model.HgContinuation;
import com.fongmi.android.tv.hongguo.model.HgSeriesItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 顶部页签的数据源（可替换装配层）：每个页签只负责给出「剧条目」，
 * 展开成逐集页由 {@link HgFeedSession} 统一做。
 *
 * <p>数据来源（2026-10-28 feed 面重建，定罪账 tab-fail-dig-20261028.md）：</p>
 * <ul>
 *   <li>推荐/漫剧/真人剧：统一走已签名 bookmall/tab 频道面（openhongguo
 *       频道口径）——推荐开页请求 tab_type=-1、响应频道 16；漫剧 36、
 *       真人剧 39 请求响应同值。旧装配已定罪摘除：漫剧/真人剧原接的
 *       new_category/front 是小说分类页壳接口（结构上永远不带剧条目）；
 *       推荐原走的 plan/无参 bookmall/tab 不是官方推荐频道（无参回书城
 *       首页壳、只有找剧位 6 张卡且从不续页）。</li>
 *   <li>续页：开页回包带 continuation（session_id/cell_id）＋ has_more，
 *       滑到尾部走 cell/change 续页追加（去重），流不再封顶首批条数。</li>
 *   <li>关注：四阶段接壳子统一登录后，未登录仍给诚实空态（不发注定失败的
 *       请求）；已登录走追剧架 GET /reading/bookapi/bookshelf/video/list 拉
 *       他追的剧，回来的条目顺手把服务端态写进 HgStore 镜像（官方态为准）。</li>
 * </ul>
 */
public final class HgFeedSources {

    public enum Tab {
        FOLLOW("关注"),
        COMIC("漫剧"),
        LIVE("真人剧"),
        RECOMMEND("推荐");

        public final String title;

        Tab(String title) {
            this.title = title;
        }
    }

    public interface Source {
        void load(HgCallback<List<HgSeriesItem>> cb);

        /** 空态文案（列表为空时页面显示）。 */
        String emptyHint();

        /** 是否还有下一页（开页/续页回包的 has_more 与凭证齐备才算）。 */
        default boolean hasMore() {
            return false;
        }

        /** 续页：回调只给新增条目（已与已下发条目去重）。默认不支持续页。 */
        default void loadMore(HgCallback<List<HgSeriesItem>> cb) {
            cb.onSuccess(Collections.emptyList());
        }
    }

    private HgFeedSources() {
    }

    public static Source forTab(HgApiClient client, Tab tab) {
        switch (tab) {
            case FOLLOW:
                return new Source() {
                    @Override
                    public void load(HgCallback<List<HgSeriesItem>> cb) {
                        if (!com.fongmi.android.tv.hongguo.api.HgLoginAuthProvider.isLoggedIn()) {
                            cb.onSuccess(Collections.emptyList());
                            return;
                        }
                        client.bookshelfVideoList(new HgCallback<com.fongmi.android.tv.hongguo.model.HgCellPage>() {
                            @Override
                            public void onSuccess(com.fongmi.android.tv.hongguo.model.HgCellPage page) {
                                List<HgSeriesItem> items = page.getItems() == null
                                        ? Collections.emptyList() : page.getItems();
                                // 服务端态为准：追剧架里的剧同步进本地镜像
                                for (HgSeriesItem s : items) {
                                    if (s.getSeriesId() != null) HgStore.setShelfMirrored(s.getSeriesId(), true);
                                }
                                cb.onSuccess(items);
                            }

                            @Override
                            public void onError(int httpCode, String message) {
                                cb.onError(httpCode, message);
                            }
                        });
                    }

                    @Override
                    public String emptyHint() {
                        return com.fongmi.android.tv.hongguo.api.HgLoginAuthProvider.isLoggedIn()
                                ? "你追的剧会显示在这里" : "登录后这里显示你关注的剧";
                    }
                };
            case COMIC:
                return new ChannelSource(client, HgRequests.TAB_COMIC, HgRequests.TAB_COMIC);
            case LIVE:
                return new ChannelSource(client, HgRequests.TAB_LIVE, HgRequests.TAB_LIVE);
            case RECOMMEND:
            default:
                return new ChannelSource(client, HgRequests.TAB_REQUEST_RECOMMEND,
                        HgRequests.TAB_RESPONSE_RECOMMEND);
        }
    }

    /**
     * bookmall 频道源：开页一发、续页按凭证推进。去重键与流内一致
     * （seriesId + vid，见 HgFeedSession 的条目键口径）；续页回包的
     * next_offset / session_id 每轮覆盖进凭证（对照 openhongguo
     * finishDiscover 的游标推进语义）。
     */
    private static final class ChannelSource implements Source {

        private final HgApiClient client;
        private final int requestTabType;
        private final int responseTabType;
        private final Set<String> seenKeys = new HashSet<>();
        private HgContinuation continuation;
        private boolean moreAvailable;
        private boolean loadingMore;

        ChannelSource(HgApiClient client, int requestTabType, int responseTabType) {
            this.client = client;
            this.requestTabType = requestTabType;
            this.responseTabType = responseTabType;
        }

        @Override
        public void load(HgCallback<List<HgSeriesItem>> cb) {
            client.bookmallChannel(requestTabType, responseTabType, new HgCallback<HgCellPage>() {
                @Override
                public void onSuccess(HgCellPage page) {
                    seenKeys.clear();
                    continuation = page.getContinuation();
                    // 续页门只认凭证在不在：开页 tab 的 has_more 实测恒 false
                    // （真回包 chan_recommend.json：tab16 has_more=false 但
                    // cell/change 续页 has_more=true、next_offset=6），照
                    // openhongguo finishDiscover 口径——开页后先探一页续页，
                    // 续页自身的 has_more/next_offset 才决定还有没有下一页
                    moreAvailable = continuation != null;
                    cb.onSuccess(dedup(page.getItems()));
                }

                @Override
                public void onError(int httpCode, String message) {
                    continuation = null;
                    moreAvailable = false;
                    cb.onError(httpCode, message);
                }
            });
        }

        @Override
        public boolean hasMore() {
            return moreAvailable && continuation != null;
        }

        @Override
        public void loadMore(HgCallback<List<HgSeriesItem>> cb) {
            if (!hasMore() || loadingMore) {
                cb.onSuccess(Collections.emptyList());
                return;
            }
            loadingMore = true;
            client.bookmallCellChange(continuation, new HgCallback<HgCellPage>() {
                @Override
                public void onSuccess(HgCellPage page) {
                    loadingMore = false;
                    boolean rawEmpty = page.getItems() == null || page.getItems().isEmpty();
                    if (rawEmpty && page.isHasMore()) {
                        // 瞬时空回（限流形状）：服务端明说还有下一页、本页却零条目。
                        // 不推进游标、不封口，走 onError 让上层静默待重试——旧逻辑
                        // 在此把 moreAvailable 打成 false 静默封口，流只剩首窗
                        cb.onError(-1, "续页瞬时空回");
                        return;
                    }
                    if (rawEmpty) {
                        // 原始空页且 has_more=false 才是真终点：封口＋成功空回
                        moreAvailable = false;
                        cb.onSuccess(Collections.emptyList());
                        return;
                    }
                    // 续页游标成立条件照 openhongguo finishDiscover：
                    // has_more 且 next_offset>0 且本页真有条目，三者缺一即封口
                    long next = -1;
                    try {
                        next = Long.parseLong(page.getNextOffset());
                    } catch (Exception ignored) {
                    }
                    moreAvailable = page.isHasMore() && next > 0;
                    if (continuation != null) {
                        continuation = continuation.advanced(page.getNextOffset(), page.getSessionId());
                    }
                    cb.onSuccess(dedup(page.getItems()));
                }

                @Override
                public void onError(int httpCode, String message) {
                    loadingMore = false;
                    // 续页失败不封死：凭证保留，下次滑到尾部可再试
                    cb.onError(httpCode, message);
                }
            });
        }

        private List<HgSeriesItem> dedup(List<HgSeriesItem> items) {
            List<HgSeriesItem> out = new ArrayList<>();
            if (items == null) return out;
            for (HgSeriesItem s : items) {
                if (s == null || s.getSeriesId() == null) continue;
                String key = s.getSeriesId() + "#" + (s.getVid() == null ? "" : s.getVid());
                if (seenKeys.add(key)) out.add(s);
            }
            return out;
        }

        @Override
        public String emptyHint() {
            return "暂时没有内容";
        }
    }
}
