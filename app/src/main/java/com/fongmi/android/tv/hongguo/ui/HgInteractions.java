package com.fongmi.android.tv.hongguo.ui;

/**
 * 红果互动接缝：右列点赞/收藏/关注的唯一入口。
 *
 * <p>三阶段实现是 {@link HgLocalInteractions}（纯本地）；四阶段默认实现换成
 * {@link HgRemoteInteractions}：已登录走官方真接口（乐观更新＋失败回滚），
 * 未登录由页面先拉壳子统一登录。分享走系统分享、评论走面板，不经本接口。</p>
 */
public interface HgInteractions {

    boolean isLiked(String seriesId);

    /** 切换点赞，返回切换后状态。 */
    boolean toggleLike(String seriesId);

    boolean isFavorite(String seriesId);

    /** 切换收藏，返回切换后状态。 */
    boolean toggleFavorite(String seriesId);

    boolean isFollowing(String seriesId);

    /** 切换关注，返回切换后状态。 */
    boolean toggleFollow(String seriesId);

    /** 是否已预约（未上线剧的到货提醒预约，与收藏/追更是不同的服务端状态）。 */
    boolean isReserved(String seriesId);

    /** 切换预约，返回切换后状态。 */
    boolean toggleReserve(String seriesId);

    /**
     * 当前实现是否要求先登录（四阶段远程实现未登录时为 true）：
     * 页面据此先拉壳子统一登录，不调 toggle、不许静默假成功。
     */
    default boolean requiresLogin() {
        return false;
    }

    /** 带集信息的点赞切换（点赞的 object 是集不是剧，需要 videoId）；默认退回按剧切换。 */
    default boolean toggleLike(HgFeedItem item) {
        return item == null ? false : toggleLike(item.seriesId);
    }
}
