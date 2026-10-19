package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.os.Bundle;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.SeekBar;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.databinding.ActivityMusicPlayerBinding;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicLibrary;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.music.MusicRepository;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.player.lyrics.LyricsParser;
import com.fongmi.android.tv.service.MusicPlaybackService;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.MusicOptionsDialog;
import com.fongmi.android.tv.ui.dialog.MusicQueueDialog;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;

import java.util.ArrayList;
import java.util.List;

/**
 * 音乐播放页：左上歌名大金字＋歌手小灰，大封面居中，歌词三行，
 * 细进度＋两端时间，主控三键超大（暂停金色最大），底部次级行 5 个。
 */
public class MusicPlayerActivity extends BaseActivity {

    private ActivityMusicPlayerBinding binding;
    private final List<LyricsLine> lyricLines = new ArrayList<>();
    private int lyricIndex = -1;
    private boolean seeking;
    private boolean favorited;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, MusicPlayerActivity.class));
    }

    private final MusicController.Listener listener = new MusicController.Listener() {
        @Override
        public void onTrackChanged(MusicTrack track) {
            runOnUiThread(() -> bindTrack(track));
        }

        @Override
        public void onPlayingChanged(boolean playing) {
            runOnUiThread(() -> setPlayingIcon(playing));
        }

        @Override
        public void onProgress(long position, long duration) {
            runOnUiThread(() -> onProgressUi(position, duration));
        }

        @Override
        public void onModeChanged(int mode) {
            runOnUiThread(() -> refreshModeIcon(mode));
        }

        @Override
        public void onSleepChanged(int sleep) {
            runOnUiThread(() -> refreshSleepIcon(sleep));
        }
    };

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityMusicPlayerBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        MusicRepository.ensureDebugFake();
        MusicController.get().bind(this);
        // 封面 28dp 圆角裁剪
        binding.ivCover.setClipToOutline(true);
        binding.ivCover.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                int r = (int) (28 * view.getResources().getDisplayMetrics().density);
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        binding.btnPlay.setOnClickListener(v -> MusicController.get().toggle());
        binding.btnPrev.setOnClickListener(v -> MusicController.get().prev());
        binding.btnNext.setOnClickListener(v -> MusicController.get().next());
        binding.btnMode.setOnClickListener(v -> MusicController.get().cycleMode());
        binding.btnSleep.setOnClickListener(v -> showSleepDialog());
        binding.btnEq.setOnClickListener(v -> {
            if (!MusicController.get().openEqualizer(this)) Notify.show("没找到系统均衡器");
        });
        binding.btnQueue.setOnClickListener(v -> MusicQueueDialog.show(this));
        binding.btnFav.setOnClickListener(v -> toggleFavorite());
        binding.btnQuality.setOnClickListener(v -> showQualityDialog());
        binding.seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                seeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                seeking = false;
                long dur = MusicController.get().duration();
                MusicController.get().seekTo(dur * seekBar.getProgress() / 1000);
            }
        });
        MusicController.get().addListener(listener);
        bindTrack(MusicController.get().current());
        setPlayingIcon(MusicController.get().isPlaying());
        refreshModeIcon(MusicController.get().getMode());
        refreshSleepIcon(MusicController.get().getSleep());
        refreshQualityBadge();
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            com.fongmi.android.tv.ui.minibar.ListenMiniBar.get(this).pageResumed("music");
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onPause() {
        try {
            com.fongmi.android.tv.ui.minibar.ListenMiniBar.get(this).pagePaused("music");
        } catch (Throwable ignored) {
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        MusicController.get().removeListener(listener);
        MusicController.get().unbind();
        super.onDestroy();
    }

    private void bindTrack(MusicTrack track) {
        if (track == null) {
            binding.tvTitle.setText(R.string.music_no_track);
            binding.tvArtist.setText("");
            return;
        }
        binding.tvTitle.setText(track.displayTitle());
        String sub = track.displayArtist();
        if (track.album != null && !track.album.isEmpty()) sub += " · " + track.album;
        binding.tvArtist.setText(sub);
        if (track.cover == null || track.cover.isEmpty()) {
            binding.ivCover.setImageDrawable(coverPlaceholder());
        } else {
            ImgUtil.load(track.displayTitle(), track.cover, binding.ivCover);
        }
        loadLyric(track);
        refreshFavorite(track);
    }

    private android.graphics.drawable.Drawable coverPlaceholder() {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF2A3444, 0xFF16202E});
        d.setCornerRadius(28 * getResources().getDisplayMetrics().density);
        return d;
    }

    private void loadLyric(MusicTrack track) {
        lyricLines.clear();
        lyricIndex = -1;
        showLyricLine(-1);
        Task.execute(() -> {
            String lrc = MusicRepository.lyric(track);
            List<LyricsLine> lines = null;
            try {
                if (lrc != null && !lrc.isEmpty()) lines = LyricsParser.parseTimed(lrc);
            } catch (Throwable ignored) {
            }
            List<LyricsLine> result = lines == null ? new ArrayList<>() : lines;
            runOnUiThread(() -> {
                // 切歌了才用
                MusicTrack cur = MusicController.get().current();
                if (cur == null || !cur.equals(track)) return;
                lyricLines.clear();
                lyricLines.addAll(result);
                lyricIndex = -1;
                showLyricLine(findLyricIndex(MusicController.get().position()));
            });
        });
    }

    private int findLyricIndex(long position) {
        int idx = -1;
        for (int i = 0; i < lyricLines.size(); i++) {
            if (lyricLines.get(i).getTimeMs() <= position) idx = i;
            else break;
        }
        return idx;
    }

    private void showLyricLine(int idx) {
        lyricIndex = idx;
        if (lyricLines.isEmpty()) {
            binding.tvLyricPrev.setText("");
            binding.tvLyricCurrent.setText("暂无歌词");
            binding.tvLyricNext.setText("");
            return;
        }
        binding.tvLyricPrev.setText(idx > 0 ? lyricLines.get(idx - 1).getText() : "");
        binding.tvLyricCurrent.setText(idx >= 0 ? lyricLines.get(idx).getText() : "");
        binding.tvLyricNext.setText(idx + 1 < lyricLines.size() ? lyricLines.get(idx + 1).getText() : "");
    }

    private void onProgressUi(long position, long duration) {
        if (!seeking && duration > 0) {
            binding.seekBar.setProgress((int) (position * 1000 / duration));
        }
        binding.tvPosition.setText(fmt(position));
        binding.tvDuration.setText(fmt(duration));
        int idx = findLyricIndex(position);
        if (idx != lyricIndex) showLyricLine(idx);
    }

    private String fmt(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    private void setPlayingIcon(boolean playing) {
        binding.btnPlay.setImageResource(playing ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
    }

    private void refreshModeIcon(int mode) {
        int icon;
        switch (mode) {
            case MusicQueue.MODE_REPEAT_ONE:
                icon = R.drawable.ic_music_repeat_one;
                break;
            case MusicQueue.MODE_REPEAT_ALL:
                icon = R.drawable.ic_music_repeat;
                break;
            case MusicQueue.MODE_SHUFFLE:
                icon = R.drawable.ic_music_shuffle;
                break;
            default:
                icon = R.drawable.ic_music_sequential;
                break;
        }
        binding.btnMode.setImageResource(icon);
        boolean active = mode != MusicQueue.MODE_SEQUENTIAL;
        binding.btnMode.setImageTintList(ColorStateList.valueOf(active ? 0xFFF2B33D : 0xFF9AA7B8));
        binding.btnMode.setContentDescription(MusicQueue.modeName(mode));
    }

    private void refreshSleepIcon(int sleep) {
        boolean active = sleep != MusicPlaybackService.SLEEP_OFF;
        binding.btnSleep.setImageTintList(ColorStateList.valueOf(active ? 0xFFF2B33D : 0xFF9AA7B8));
        binding.btnSleep.setContentDescription(getString(R.string.music_sleep) + "：" + MusicPlaybackService.sleepName(sleep));
    }

    private void showSleepDialog() {
        List<String> opts = new ArrayList<>();
        int[] values = {MusicPlaybackService.SLEEP_OFF, MusicPlaybackService.SLEEP_AFTER_CURRENT,
                MusicPlaybackService.SLEEP_15, MusicPlaybackService.SLEEP_30,
                MusicPlaybackService.SLEEP_60, MusicPlaybackService.SLEEP_90};
        int cur = MusicController.get().getSleep();
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            opts.add(MusicPlaybackService.sleepName(values[i]));
            if (values[i] == cur) checked = i;
        }
        MusicOptionsDialog.show(this, getString(R.string.music_sleep), opts, checked,
                idx -> MusicController.get().setSleep(values[idx]));
    }

    private void refreshQualityBadge() {
        binding.btnQuality.setText(MusicStore.qualityName(MusicController.get().getQuality()));
    }

    private void showQualityDialog() {
        List<String> opts = new ArrayList<>();
        int[] values = {MusicStore.QUALITY_LOW, MusicStore.QUALITY_STANDARD, MusicStore.QUALITY_HIGH, MusicStore.QUALITY_SUPER};
        for (int v : values) opts.add(MusicStore.qualityName(v));
        MusicOptionsDialog.show(this, getString(R.string.music_quality), opts, MusicController.get().getQuality(), idx -> {
            if (MusicController.get().getQuality() != values[idx]) {
                MusicController.get().setQuality(values[idx]);
                MusicController.get().reloadQuality();
                refreshQualityBadge();
                Notify.show("已切换" + MusicStore.qualityName(values[idx]));
            }
        });
    }

    private void refreshFavorite(MusicTrack track) {
        if (track == null || track.id == null) {
            favorited = false;
            binding.btnFav.setImageResource(R.drawable.ic_music_heart);
            binding.btnFav.setImageTintList(ColorStateList.valueOf(0xFF9AA7B8));
            return;
        }
        MusicLibrary.isFavorite(track.id, fav -> {
            favorited = fav;
            binding.btnFav.setImageResource(fav ? R.drawable.ic_music_heart_fill : R.drawable.ic_music_heart);
            binding.btnFav.setImageTintList(ColorStateList.valueOf(fav ? 0xFFF2B33D : 0xFF9AA7B8));
        });
    }

    private void toggleFavorite() {
        MusicTrack track = MusicController.get().current();
        if (track == null) return;
        MusicLibrary.setFavorite(track, !favorited, () -> {
            favorited = !favorited;
            binding.btnFav.setImageResource(favorited ? R.drawable.ic_music_heart_fill : R.drawable.ic_music_heart);
            binding.btnFav.setImageTintList(ColorStateList.valueOf(favorited ? 0xFFF2B33D : 0xFF9AA7B8));
            Notify.show(favorited ? R.string.music_favorited : R.string.music_unfavorited);
        });
    }
}
