package com.fongmi.android.tv.hongguo.ui;

/**
 * 红果互动接缝：右列点赞/收藏/关注的唯一入口。
 *
 * <p>唯一实现是 {@link HgRemoteInteractions}（四阶段起）：已登录走官方真
 * 接口（乐观更新＋失败回滚），未登录由页面先拉壳子统一登录。三阶段的纯
 * 本地备用实现自始无装配点、与 Remote 语义分歧，批五已整件删除（账
 * hongguo-batch5-cleanup-20261029）。分享走系统分享、评论走面板，不经
 * 本接口。</p>
 */
public interface HgInteractions {

    boolean isLiked(String seriesId);

    /** 切换点赞，返回切换后状态。 */
    boolean toggleLike(String seriesId);

    /** 收藏与追更同体（批二归一）：与 isFollowing 同读一份追剧架正源状态。 */
    boolean isFavorite(String seriesId);

    /** 切换收藏＝切换追剧架正源，返回切换后状态（与 toggleFollow 同一写路）。 */
    boolean toggleFavorite(String seriesId);

    boolean isFollowing(String seriesId);

    /** 切换追更＝切换追剧架正源，返回切换后状态（与 toggleFavorite 同一写路）。 */
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
