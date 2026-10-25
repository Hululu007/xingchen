package com.fongmi.android.tv.ui.music;

import com.fongmi.android.tv.music.RichLyrics;

import java.util.List;

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

        void onOpenTools();

        void onOpenLyricPage();

        void onModeClick();

        void onVolume(float fraction);
    }

    void setCallback(Callback callback);

    void setTrack(String title, String artist, String album);

    void setCover(String coverUrl, String title, boolean animate, int direction);

    void setSwipeHints(String prevTitle, String nextTitle);

    void setPlaying(boolean playing);

    void setFavorite(boolean favorite);

    boolean isFavorite();

    void setProgress(long positionMs, long durationMs);

    void setLyrics(List<RichLyrics.Line> lines, boolean loading);

    void advanceLyric(long positionMs);

    void setQualityText(String text);

    void setLyricState(String chipLabel, boolean wordByWord);

    void setInfoDetail(String detail);

    void setMode(int mode);

    void setVolume(float fraction);
}
