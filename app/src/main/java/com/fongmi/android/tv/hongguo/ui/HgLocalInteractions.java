package com.fongmi.android.tv.hongguo.ui;

/** 互动接缝的三阶段实现：全部落 {@link HgStore} 本地状态。 */
public final class HgLocalInteractions implements HgInteractions {

    @Override
    public boolean isLiked(String seriesId) {
        return HgStore.isLiked(seriesId);
    }

    @Override
    public boolean toggleLike(String seriesId) {
        return HgStore.toggleLiked(seriesId);
    }

    @Override
    public boolean isFavorite(String seriesId) {
        return HgStore.isFaved(seriesId);
    }

    @Override
    public boolean toggleFavorite(String seriesId) {
        return HgStore.toggleFaved(seriesId);
    }

    @Override
    public boolean isFollowing(String seriesId) {
        return HgStore.isFollowed(seriesId);
    }

    @Override
    public boolean toggleFollow(String seriesId) {
        return HgStore.toggleFollowed(seriesId);
    }
}
