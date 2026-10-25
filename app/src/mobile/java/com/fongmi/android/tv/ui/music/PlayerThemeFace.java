package com.fongmi.android.tv.ui.music;

import com.fongmi.android.tv.music.RichLyrics;

import java.util.List;

/**
 * 播放页主题契约（宿主 MusicPlayerActivity ↔ 五主题 View）。
 * 宿主负责：控制器接线、取色氛围、收藏、音质/队列/工具/歌词页跳转、迷你进度条互斥。
 * 主题只负责自身构图与手势，动作一律经 Callback 上抛。
 */
public interface PlayerThemeFace {

    interface Callback {
        void onTogglePlay();

        void onPrev();

        void onNext();

        void onQueue();

        void onFavoriteClick();

        /** 点按或拖动松手后的真 seek（宿主 seek 后负责歌词重同步）。 */
        void onSeekMs(long positionMs);

        void onQualityClick();

        /** 歌词筹点按：切换翻译主显（星辰既有口径）。 */
        void onLyricChipToggle(boolean showTranslation);

        void onOpenTools();

        void onOpenLyricPage();

        void onModeClick();

        /** 系统音量（0..1），外观面板/主面板音量卡用。 */
        void onVolume(float fraction);
    }

    void setCallback(Callback callback);

    void setTrack(String title, String artist, String album);

    void setCover(String url, String title, boolean animate, int dir);

    void setPlaying(boolean playing);

    void setMode(int mode);

    void setLyrics(List<RichLyrics.Line> lines, boolean loading);

    /** 歌词筹文案（AMLL/逐字/逐行/纯文本/无词）与是否有逐字数据。 */
    void setLyricState(String chipLabel, boolean hasWords);

    void setQualityText(String text);

    void setInfoDetail(String detail);

    void setSwipeHints(String prevTitle, String nextTitle);

    void setFavorite(boolean favorite);

    boolean isFavorite();

    void setProgress(long positionMs, long durationMs);

    void advanceLyric(long positionMs);

    /** 迷你进度条互斥：false 时本主题底部进度栈隐藏（宿主迷你条接管）。 */
    void setBottomStackExpanded(boolean expanded);
}
