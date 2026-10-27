package com.fongmi.android.tv.hongguo.ui;

import com.fongmi.android.tv.hongguo.api.HgApiClient;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.model.HgCellPage;
import com.fongmi.android.tv.hongguo.model.HgSeriesItem;

import java.util.Collections;
import java.util.List;

/**
 * 顶部页签的数据源（可替换装配层）：每个页签只负责给出「剧条目」，
 * 展开成逐集页由 {@link HgFeedSession} 统一做。
 *
 * <p>数据来源与未定项（如实）：</p>
 * <ul>
 *   <li>推荐：plan（服务端编排计划位）取剧条目，无结果时退 bookmall/tab；
 *       逐条 feed 的原始报文在拆解中未定版（structure.md 第二节标未定），
 *       故先走这套已落的 plan/bookmall 面，这正是装配层存在的意义——
 *       feed 报文定版后只换本类。</li>
 *   <li>漫剧/真人剧：category/front 的 cell 条目。两类各自的服务端分类参数
 *       未定版，现都走 front 容忍解析，页签先按同一面装配，定版后在此分流。</li>
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
            case LIVE:
                return new Source() {
                    @Override
                    public void load(HgCallback<List<HgSeriesItem>> cb) {
                        client.categoryFront(cellToItems(cb));
                    }

                    @Override
                    public String emptyHint() {
                        return "暂时没有内容";
                    }
                };
            case RECOMMEND:
            default:
                return new Source() {
                    @Override
                    public void load(HgCallback<List<HgSeriesItem>> cb) {
                        client.plan(new HgCallback<HgCellPage>() {
                            @Override
                            public void onSuccess(HgCellPage page) {
                                if (page.getItems() != null && !page.getItems().isEmpty()) {
                                    cb.onSuccess(page.getItems());
                                } else {
                                    client.bookmallTab(cellToItems(cb));
                                }
                            }

                            @Override
                            public void onError(int httpCode, String message) {
                                client.bookmallTab(cellToItems(cb));
                            }
                        });
                    }

                    @Override
                    public String emptyHint() {
                        return "暂时没有内容，下拉或稍后再来";
                    }
                };
        }
    }

    private static HgCallback<HgCellPage> cellToItems(HgCallback<List<HgSeriesItem>> cb) {
        return new HgCallback<HgCellPage>() {
            @Override
            public void onSuccess(HgCellPage page) {
                cb.onSuccess(page.getItems() == null ? Collections.emptyList() : page.getItems());
            }

            @Override
            public void onError(int httpCode, String message) {
                cb.onError(httpCode, message);
            }
        };
    }
}
