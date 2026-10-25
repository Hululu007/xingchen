package com.fongmi.android.tv.ui.music;

import com.fongmi.android.tv.music.RichLyrics;

import java.util.List;

/**
 * 播放页主题统一接口（重做第二步）：宿主 MusicPlayerActivity 只认这一套写入面＋回调，
 * 各主题（封面卡片/经典/后续 Apple/CD/黑胶）逐个实现，宿主驱动代码不按主题分叉。
 * setCover 的 dir 沿用 CoverStackView.DIR_NEXT/DIR_PREV 语义，方向的具体轴向各主题自解
 * （封面卡＝横向、经典＝竖向）。
 */
public interface PlayerThemeFace {

    interface Callback {
        void onTogglePlay();

        void onPrev();

        void onNext();

        void onQueue();

        void onFavoriteClick();

        void onSeekMs(long positionMs);

        void onQualityClick();

        void onLyricChipToggle(boolean showTranslation);

        /** 开工具面板（经典菜单键/封面长按；面板本体属面板步，未建时宿主暂空转）。 */
        void onOpenTools();
    }

    void setCallback(Callback callback);

    void setTrack(String title, String artist, String album);

    /** 歌曲信息明细（封面卡展开信息卡专用；无此面的主题空实现）。 */
    void setInfoDetail(String detail);

    void setCover(String url, String title, boolean animate, int dir);

    void setPlaying(boolean value);

    boolean isPlaying();

    void setFavorite(boolean value);

    boolean isFavorite();

    void setQualityText(String text);

    void setLyricChipState(boolean hasTranslation, boolean show);

    void setProgress(long positionMs, long totalMs);

    void setLyrics(List<RichLyrics.Line> lines, boolean loading);

    void advanceLyric(long positionMs);

    /** 竖滑切歌方向提示的目标歌名（经典专用；无此面的主题空实现）。 */
    void setSwipeHints(String prevTitle, String nextTitle);
}
