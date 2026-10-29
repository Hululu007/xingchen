package com.fongmi.android.tv.hongguo.ui;

import com.fongmi.android.tv.hongguo.api.HgApiClient;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.api.HgCommentClient;
import com.fongmi.android.tv.hongguo.api.HgLoginAuthProvider;

/**
 * 互动接缝的四阶段实现：已登录走官方真接口，未登录不落状态（页面先拉登录）。
 *
 * <p>接口实证（样本出处详见 HgRequests 两个构造方法的注）：</p>
 * <ul>
 *   <li>点赞：POST /novel/articleapi/do_action/v1/，action_type=Like(3)/CancelLike(4)，
 *       object＝集 vid（Item=6），business_param.video_id＝剧 id。</li>
 *   <li>收藏/关注（追更）：同走追剧架 POST /reading/bookapi/bookshelf/video/update
 *       的 AddShelf/DelShelf——官方右列头像上的关注钮（SeriesFollowButton /
 *       PugcSubscribeView 追剧/订阅）与收藏在服务端是同一个追剧架
 *       （jadx9 rs4/o1.java 的 FollowScene 加入收藏链）。批二归一：本地只留
 *       一份正源状态（HgStore followed 集，收藏入口全部转正源），toggleFavorite
 *       与 toggleFollow 共用一条 toggleShelf 写路，KIND 只区分失败文案。</li>
 *   <li>关注作者本人：feed/详情数据里没有作者 uid（详情只有 author_name），
 *       构造不出 Follow 动作的 object_id，不冒充——本接口的关注只到「追剧」为止，
 *       作者关注列四阶段未定项。</li>
 *   <li>预约（未上线剧）：POST /reading/bookapi/search/uncover_subscribe
 *       （item_type=14 短剧、op_type=1 预约/2 取消，出处链见 HgRequests
 *       searchUncoverSubscribeBody 注）——与追剧架是两套服务端状态，不混用。
 *       写成功后拉 GET /reading/user/subscribe/list 回读对镜像（在不在列表里
 *       为准）；回读本身失败不回滚已成功的写，镜像保留乐观值待下次对齐。</li>
 * </ul>
 *
 * <p>同步口径：toggle 先乐观改本地镜像并立即返回新状态（UI 不等网络），
 * 网络回来失败则回滚到旧状态并经 {@link Listener} 如实上报，页面刷新该页
 * 并提示。全部请求异步（OkHttp 分发线程），点按不卡。</p>
 */
public final class HgRemoteInteractions implements HgInteractions {

    public static final String KIND_LIKE = "like";
    public static final String KIND_FAV = "fav";
    public static final String KIND_FOLLOW = "follow";
    public static final String KIND_RESERVE = "reserve";

    /** 同步结果上报（在 OkHttp 分发线程回调，页面须自己切主线程）。 */
    public interface Listener {
        /** 远程同步成功（本地镜像已是服务端态）。 */
        void onSynced(String kind, String seriesId, boolean state);

        /** 远程同步失败：本地镜像已回滚到 revertedTo，页面刷新并如实提示。 */
        void onSyncFailed(String kind, String seriesId, boolean revertedTo, String message);
    }

    private final HgApiClient client;
    private final HgCommentClient fqClient;
    private volatile Listener listener;
    /** 附加监听（详情页等第二宿主用）：主 listener 仍归流页，附加者只收
     * 自己关心的条目、须在宿主销毁时 remove，防抢占与泄漏。 */
    private final java.util.List<Listener> extraListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public HgRemoteInteractions(HgApiClient client, HgCommentClient fqClient) {
        this.client = client;
        this.fqClient = fqClient;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void addListener(Listener l) {
        if (l != null) extraListeners.add(l);
    }

    public void removeListener(Listener l) {
        extraListeners.remove(l);
    }

    private void fireSynced(String kind, String seriesId, boolean state) {
        Listener l = listener;
        if (l != null) l.onSynced(kind, seriesId, state);
        for (Listener x : extraListeners) x.onSynced(kind, seriesId, state);
    }

    private void fireSyncFailed(String kind, String seriesId, boolean revertedTo, String message) {
        Listener l = listener;
        if (l != null) l.onSyncFailed(kind, seriesId, revertedTo, message);
        for (Listener x : extraListeners) x.onSyncFailed(kind, seriesId, revertedTo, message);
    }

    @Override
    public boolean requiresLogin() {
        return !HgLoginAuthProvider.isLoggedIn();
    }

    // ---------- 点赞 ----------

    @Override
    public boolean isLiked(String seriesId) {
        return HgStore.isLiked(seriesId);
    }

    @Override
    public boolean toggleLike(String seriesId) {
        // 无集信息时只有本地镜像可动（页面正常路径走 toggleLike(HgFeedItem)）
        boolean now = HgStore.toggleLiked(seriesId);
        return now;
    }

    @Override
    public boolean toggleLike(HgFeedItem item) {
        if (item == null || item.seriesId == null) return false;
        final String seriesId = item.seriesId;
        final boolean before = HgStore.isLiked(seriesId);
        final boolean now = !before;
        HgStore.setLiked(seriesId, now);
        if (item.videoId == null) return now; // 剧头未展开：无集 vid 可发，先只落镜像
        fqClient.doArticleAction(now, item.videoId, seriesId, new HgCallback<Long>() {
            @Override
            public void onSuccess(Long count) {
                fireSynced(KIND_LIKE, seriesId, now);
            }

            @Override
            public void onError(int httpCode, String message) {
                HgStore.setLiked(seriesId, before);
                fireSyncFailed(KIND_LIKE, seriesId, before, message);
            }
        });
        return now;
    }

    // ---------- 收藏 / 关注（追更）：同走追剧架 ----------

    /** 收藏与追更同体（批二归一）：两读口同读正源一份状态。 */
    @Override
    public boolean isFavorite(String seriesId) {
        return HgStore.isFollowed(seriesId);
    }

    @Override
    public boolean toggleFavorite(String seriesId) {
        return toggleShelf(KIND_FAV, seriesId);
    }

    @Override
    public boolean isFollowing(String seriesId) {
        return HgStore.isFollowed(seriesId);
    }

    @Override
    public boolean toggleFollow(String seriesId) {
        return toggleShelf(KIND_FOLLOW, seriesId);
    }

    // ---------- 预约（未上线剧，走 uncover_subscribe，不经追剧架） ----------

    @Override
    public boolean isReserved(String seriesId) {
        return HgStore.isReserved(seriesId);
    }

    @Override
    public boolean toggleReserve(String seriesId) {
        if (seriesId == null || seriesId.isEmpty()) return false;
        final boolean before = HgStore.isReserved(seriesId);
        final boolean now = !before;
        HgStore.setReserved(seriesId, now);
        client.subscribeSeries(seriesId, now, new HgCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean ok) {
                // 服务端回读对齐：在不在「我的预约」列表里为准（预约已按
                // is_online 分档，两档任一命中即在列）
                fetchReservedContains(seriesId, true, new HgCallback<Boolean>() {
                    @Override
                    public void onSuccess(Boolean hit) {
                        HgStore.setReserved(seriesId, Boolean.TRUE.equals(hit));
                        fireSynced(KIND_RESERVE, seriesId, Boolean.TRUE.equals(hit));
                    }

                    @Override
                    public void onError(int httpCode, String message) {
                        // 写已成功、只是回读没回来：保留乐观镜像，不假报失败
                        fireSynced(KIND_RESERVE, seriesId, now);
                    }
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                HgStore.setReserved(seriesId, before);
                fireSyncFailed(KIND_RESERVE, seriesId, before, message);
            }
        });
        return now;
    }

    /** 预约回读对齐：先查已上线档、未命中再查待上线档，任一命中即在预约列。 */
    private void fetchReservedContains(String seriesId, boolean onlineTier, HgCallback<Boolean> cb) {
        client.fetchMyReservations(onlineTier, new HgCallback<java.util.List<com.fongmi.android.tv.hongguo.model.HgSeriesItem>>() {
            @Override
            public void onSuccess(java.util.List<com.fongmi.android.tv.hongguo.model.HgSeriesItem> list) {
                boolean hit = false;
                if (list != null) {
                    for (com.fongmi.android.tv.hongguo.model.HgSeriesItem s : list) {
                        if (s != null && seriesId.equals(s.getSeriesId())) {
                            hit = true;
                            break;
                        }
                    }
                }
                if (hit || !onlineTier) cb.onSuccess(hit);
                else fetchReservedContains(seriesId, false, cb);
            }

            @Override
            public void onError(int httpCode, String message) {
                if (onlineTier) fetchReservedContains(seriesId, false, cb);
                else cb.onError(httpCode, message);
            }
        });
    }

    /**
     * 追剧架唯一写路（批二归一）：收藏/追更共用——读正源一份状态、乐观写
     * 正源一份、失败回滚正源一份。kind 只决定失败提示文案，不分状态。
     */
    private boolean toggleShelf(String kind, String seriesId) {
        if (seriesId == null || seriesId.isEmpty()) return false;
        final boolean before = HgStore.isFollowed(seriesId);
        final boolean now = !before;
        HgStore.setShelfMirrored(seriesId, now);
        client.bookshelfVideoUpdate(seriesId, now, new HgCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean ok) {
                fireSynced(kind, seriesId, now);
            }

            @Override
            public void onError(int httpCode, String message) {
                HgStore.setShelfMirrored(seriesId, before);
                fireSyncFailed(kind, seriesId, before, message);
            }
        });
        return now;
    }
}
