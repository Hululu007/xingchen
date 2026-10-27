package com.fongmi.android.tv.hongguo.ui;

/**
 * 红果互动接缝：右列点赞/收藏/关注的唯一入口。
 *
 * <p>本阶段（三阶段）实现是 {@link HgLocalInteractions}：状态落红果自有存储、
 * UI 即时反映。四阶段换真接口时只换实现类，页面不改。分享走系统分享、
 * 评论走面板壳，不经本接口。</p>
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
}
