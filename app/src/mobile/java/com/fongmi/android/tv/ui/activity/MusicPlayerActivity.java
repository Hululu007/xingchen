package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.music.MusicSourceManager;
import com.fongmi.android.tv.bean.MusicPlaylist;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.databinding.ActivityMusicPlayerBinding;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicDownloadStore;
import com.fongmi.android.tv.music.MusicLibrary;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.music.MusicRepository;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.player.lyrics.LyricsParser;
import com.fongmi.android.tv.service.MusicPlaybackService;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.MusicOptionsDialog;
import com.fongmi.android.tv.ui.dialog.MusicPlayerMoreDialog;
import com.fongmi.android.tv.ui.dialog.MusicQueueDialog;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 音乐播放页：Free 格局——顶部「封面｜歌词」切换；封面视图大封面居中，
 * 歌名/歌手/来源标签；功能行（收藏/下载/音质/更多），更多进底部弹窗；
 * 底部主控五键（模式/上一首/播放/下一首/队列）。
 */
public class MusicPlayerActivity extends BaseActivity {

    private ActivityMusicPlayerBinding binding;
    private final List<LyricsLine> lyricLines = new ArrayList<>();
    private final LyricAdapter lyricAdapter = new LyricAdapter();
    private int lyricIndex = -1;
    private boolean lyricMode;
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
        public void onAudioInfoChanged() {
            runOnUiThread(() -> refreshQualityValue());
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
        binding.rvLyric.setLayoutManager(new LinearLayoutManager(this));
        binding.rvLyric.setAdapter(lyricAdapter);
        binding.tabCover.setOnClickListener(v -> showTab(false));
        binding.tabLyric.setOnClickListener(v -> showTab(true));
        binding.btnPlay.setOnClickListener(v -> MusicController.get().toggle());
        binding.btnPrev.setOnClickListener(v -> MusicController.get().prev());
        binding.btnNext.setOnClickListener(v -> MusicController.get().next());
        binding.btnMode.setOnClickListener(v -> MusicController.get().cycleMode());
        binding.btnQueue.setOnClickListener(v -> MusicQueueDialog.show(this));
        binding.funcFav.setOnClickListener(v -> toggleFavorite());
        binding.funcDownload.setOnClickListener(v -> downloadCurrent());
        binding.funcQuality.setOnClickListener(v -> showQualityDialog());
        binding.funcMore.setOnClickListener(v -> showMoreDialog());
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
        showTab(false);
        bindTrack(MusicController.get().current());
        setPlayingIcon(MusicController.get().isPlaying());
        refreshModeIcon(MusicController.get().getMode());
        refreshQualityValue();
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

    // ================= 封面/歌词切换 =================

    private void showTab(boolean lyric) {
        lyricMode = lyric;
        binding.viewCover.setVisibility(lyric ? View.GONE : View.VISIBLE);
        binding.rvLyric.setVisibility(lyric ? View.VISIBLE : View.GONE);
        binding.tvTabCover.setTextColor(lyric ? 0xFF9AA7B8 : 0xFFFFFFFF);
        binding.tvTabLyric.setTextColor(lyric ? 0xFFFFFFFF : 0xFF9AA7B8);
        binding.indCover.setVisibility(lyric ? View.INVISIBLE : View.VISIBLE);
        binding.indLyric.setVisibility(lyric ? View.VISIBLE : View.INVISIBLE);
        if (lyric && lyricIndex >= 0) scrollLyricTo(lyricIndex, false);
    }

    // ================= 歌曲绑定 =================

    private void bindTrack(MusicTrack track) {
        if (track == null) {
            binding.tvTitle.setText(R.string.music_no_track);
            binding.tvArtist.setText("");
            binding.tvSource.setVisibility(View.GONE);
            return;
        }
        binding.tvTitle.setText(track.displayTitle());
        binding.tvArtist.setText(track.displayArtist());
        String source = sourceName(track);
        if (TextUtils.isEmpty(source)) {
            binding.tvSource.setVisibility(View.GONE);
        } else {
            binding.tvSource.setVisibility(View.VISIBLE);
            binding.tvSource.setText(source);
        }
        if (track.cover == null || track.cover.isEmpty()) {
            binding.ivCover.setImageDrawable(coverPlaceholder());
        } else {
            ImgUtil.load(track.displayTitle(), track.cover, binding.ivCover);
        }
        loadLyric(track);
        refreshFavorite(track);
        refreshQualityValue();
    }

    /** 来源标签：查插件名；查不到不显示，本地文件标识为已下载，py 源显示点播源名 */
    private String sourceName(MusicTrack track) {
        if (track == null || track.platform == null) return "";
        try {
            for (MusicSite site : MusicSourceManager.get().listSites()) {
                if (site != null && track.platform.equals(site.platform)) return site.name == null ? "" : site.name;
            }
        } catch (Throwable ignored) {
        }
        if ("local".equals(track.platform)) return getString(R.string.music_downloaded);
        if (com.fongmi.android.tv.music.DeviceMusicSource.PLATFORM.equals(track.platform)) {
            return getString(R.string.music_device);
        }
        if (com.fongmi.android.tv.music.PyMusicSource.PLATFORM.equals(track.platform)) {
            return com.fongmi.android.tv.music.PyMusicSource.siteNameOf(track);
        }
        return "";
    }

    private android.graphics.drawable.Drawable coverPlaceholder() {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF2A3444, 0xFF16202E});
        d.setCornerRadius(28 * getResources().getDisplayMetrics().density);
        return d;
    }

    // ================= 歌词 =================

    private void loadLyric(MusicTrack track) {
        applyLyricLines(new ArrayList<>());
        Task.execute(() -> {
            String lrc = MusicRepository.lyric(track);
            List<LyricsLine> lines = null;
            try {
                if (lrc != null && !lrc.isEmpty()) lines = LyricsParser.parseTimed(lrc);
            } catch (Throwable ignored) {
            }
            List<LyricsLine> result = lines == null ? new ArrayList<>() : lines;
            runOnUiThread(() -> {
                MusicTrack cur = MusicController.get().current();
                if (cur == null || !cur.equals(track)) return;
                applyLyricLines(result);
            });
        });
    }

    /** 「更多」里的手动搜索歌词：强制跨插件找，找到就换上 */
    private void searchLyric() {
        MusicTrack track = MusicController.get().current();
        if (track == null) return;
        Notify.show(R.string.music_lyric_searching);
        Task.execute(() -> {
            String lrc = MusicRepository.lyric(track, true);
            List<LyricsLine> lines = null;
            try {
                if (lrc != null && !lrc.isEmpty()) lines = LyricsParser.parseTimed(lrc);
            } catch (Throwable ignored) {
            }
            List<LyricsLine> result = lines;
            runOnUiThread(() -> {
                MusicTrack cur = MusicController.get().current();
                if (cur == null || !cur.equals(track)) return;
                if (result != null && !result.isEmpty()) {
                    applyLyricLines(result);
                    Notify.show(R.string.music_lyric_found);
                } else {
                    Notify.show(R.string.music_lyric_not_found);
                }
            });
        });
    }

    private void applyLyricLines(List<LyricsLine> lines) {
        lyricLines.clear();
        lyricLines.addAll(lines);
        lyricIndex = -1;
        lyricAdapter.notifyDataSetChanged();
        showLyricLine(findLyricIndex(MusicController.get().position()));
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
        if (idx == lyricIndex) return;
        int old = lyricIndex;
        lyricIndex = idx;
        if (old >= 0 && old < lyricLines.size()) lyricAdapter.notifyItemChanged(old);
        if (idx >= 0 && idx < lyricLines.size()) lyricAdapter.notifyItemChanged(idx);
        if (lyricMode && idx >= 0) scrollLyricTo(idx, true);
    }

    private void scrollLyricTo(int idx, boolean smooth) {
        RecyclerView.LayoutManager lm = binding.rvLyric.getLayoutManager();
        if (!(lm instanceof LinearLayoutManager)) return;
        if (smooth) binding.rvLyric.smoothScrollToPosition(idx);
        else ((LinearLayoutManager) lm).scrollToPositionWithOffset(idx, binding.rvLyric.getHeight() / 3);
    }

    private class LyricAdapter extends RecyclerView.Adapter<LyricAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = (TextView) LayoutInflater.from(parent.getContext())
                    .inflate(android.R.layout.simple_list_item_1, parent, false);
            tv.setGravity(android.view.Gravity.CENTER);
            return new Holder(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            TextView tv = (TextView) h.itemView;
            if (lyricLines.isEmpty()) {
                tv.setText(R.string.music_no_lyric);
                tv.setTextColor(0xFF5E6B7E);
                tv.setTextSize(16);
                tv.getPaint().setFakeBoldText(false);
                return;
            }
            LyricsLine line = lyricLines.get(position);
            boolean cur = position == lyricIndex;
            tv.setText(line.getText());
            tv.setTextColor(cur ? 0xFFF2B33D : 0xFF9AA7B8);
            tv.setTextSize(cur ? 19 : 16);
            tv.getPaint().setFakeBoldText(cur);
        }

        @Override
        public int getItemCount() {
            return lyricLines.isEmpty() ? 1 : lyricLines.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            Holder(View v) {
                super(v);
            }
        }
    }

    // ================= 进度/主控 =================

    private void onProgressUi(long position, long duration) {
        if (!seeking && duration > 0) {
            binding.seekBar.setProgress((int) (position * 1000 / duration));
        }
        binding.tvPosition.setText(fmt(position));
        binding.tvDuration.setText(fmt(duration));
        showLyricLine(findLyricIndex(position));
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

    // ================= 功能行：收藏/下载/音质 =================

    private void refreshFavorite(MusicTrack track) {
        if (track == null || track.id == null) {
            favorited = false;
            binding.ivFav.setImageResource(R.drawable.ic_music_heart);
            binding.ivFav.setImageTintList(ColorStateList.valueOf(0xFF9AA7B8));
            return;
        }
        MusicLibrary.isFavorite(track.id, fav -> {
            favorited = fav;
            binding.ivFav.setImageResource(fav ? R.drawable.ic_music_heart_fill : R.drawable.ic_music_heart);
            binding.ivFav.setImageTintList(ColorStateList.valueOf(fav ? 0xFFF2433D : 0xFF9AA7B8));
        });
    }

    private void toggleFavorite() {
        MusicTrack track = MusicController.get().current();
        if (track == null) return;
        MusicLibrary.setFavorite(track, !favorited, () -> {
            favorited = !favorited;
            binding.ivFav.setImageResource(favorited ? R.drawable.ic_music_heart_fill : R.drawable.ic_music_heart);
            binding.ivFav.setImageTintList(ColorStateList.valueOf(favorited ? 0xFFF2433D : 0xFF9AA7B8));
            Notify.show(favorited ? R.string.music_favorited : R.string.music_unfavorited);
        });
    }

    private void downloadCurrent() {
        MusicTrack track = MusicController.get().current();
        if (track == null) return;
        if (com.fongmi.android.tv.music.DeviceMusicSource.PLATFORM.equals(track.platform)) {
            Notify.show(R.string.music_already_local);
            return;
        }
        if (MusicDownloadStore.isLocal(track)) {
            Notify.show(R.string.music_download_exists);
            return;
        }
        MusicDownloadStore.download(track);
    }

    /**
     * 音质格显示真实参数（服务拿到的 Format：如「FLAC · 1411kbps」）；
     * 还没起播或取不到格式时回退显示所选档名，点开仍是选档。
     */
    private void refreshQualityValue() {
        String real = null;
        try {
            com.fongmi.android.tv.music.MusicAudioInfo info = MusicController.get().audioInfo();
            if (info != null) real = info.describe();
        } catch (Throwable ignored) {
        }
        binding.tvQualityValue.setText(real != null && !real.isEmpty()
                ? real : MusicStore.qualityName(MusicController.get().getQuality()));
    }

    private void showQualityDialog() {
        List<String> opts = new ArrayList<>();
        int[] values = {MusicStore.QUALITY_LOW, MusicStore.QUALITY_STANDARD, MusicStore.QUALITY_HIGH, MusicStore.QUALITY_SUPER};
        for (int v : values) opts.add(MusicStore.qualityName(v));
        MusicOptionsDialog.show(this, getString(R.string.music_quality), opts, MusicController.get().getQuality(), idx -> {
            if (MusicController.get().getQuality() != values[idx]) {
                MusicController.get().setQuality(values[idx]);
                MusicController.get().reloadQuality();
                refreshQualityValue();
                Notify.show("已切换" + MusicStore.qualityName(values[idx]));
            }
        });
    }

    // ================= 「更多」弹窗接线 =================

    private void showMoreDialog() {
        MusicTrack track = MusicController.get().current();
        if (track == null) return;
        String sleep = MusicPlaybackService.sleepName(MusicController.get().getSleep());
        MusicPlayerMoreDialog.show(this, track, sourceName(track), sleep, speedName(), new MusicPlayerMoreDialog.Listener() {
            @Override
            public void onSleep() {
                showSleepDialog();
            }

            @Override
            public void onSpeed() {
                showSpeedDialog();
            }

            @Override
            public void onFx() {
                com.fongmi.android.tv.ui.dialog.MusicFxDialog.show(MusicPlayerActivity.this);
            }

            @Override
            public void onAddToPlaylist() {
                pickPlaylist(track);
            }

            @Override
            public void onPlayNext() {
                MusicController.get().insertNext(track);
                Notify.show(R.string.music_play_next);
            }

            @Override
            public void onAppendQueue() {
                MusicController.get().appendTrack(track);
                Notify.show(R.string.music_added_queue);
            }

            @Override
            public void onSearchLyric() {
                searchLyric();
            }
        });
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

    private String speedName() {
        float s = MusicController.get().getSpeed();
        return (s == Math.floor(s) ? String.format("%.1f", s) : String.valueOf(s)) + "×";
    }

    private void showSpeedDialog() {
        float[] speeds = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
        List<String> opts = new ArrayList<>();
        int checked = 2;
        float cur = MusicController.get().getSpeed();
        for (int i = 0; i < speeds.length; i++) {
            opts.add((speeds[i] == Math.floor(speeds[i]) ? String.format("%.1f", speeds[i]) : String.valueOf(speeds[i])) + "×");
            if (Math.abs(speeds[i] - cur) < 0.01f) checked = i;
        }
        MusicOptionsDialog.show(this, getString(R.string.music_speed), opts, checked,
                idx -> MusicController.get().setSpeed(speeds[idx]));
    }

    private void pickPlaylist(MusicTrack track) {
        MusicLibrary.playlists(list -> {
            List<String> names = new ArrayList<>();
            for (MusicPlaylist p : list) names.add(p.getName());
            names.add(getString(R.string.music_new_playlist));
            MusicOptionsDialog.show(this, getString(R.string.music_add_to_playlist), names, -1, idx -> {
                if (idx < list.size()) {
                    MusicLibrary.addToPlaylist(list.get(idx).getId(), track, added ->
                            Notify.show(added ? R.string.music_added : R.string.music_already_in));
                } else {
                    inputPlaylistName(name -> {
                        if (name == null || name.trim().isEmpty()) return;
                        MusicLibrary.createPlaylist(name.trim(), p ->
                                MusicLibrary.addToPlaylist(p.getId(), track, added ->
                                        Notify.show(added ? R.string.music_added : R.string.music_already_in)));
                    });
                }
            });
        });
    }

    private interface NameInput {
        void onResult(String name);
    }

    private void inputPlaylistName(NameInput cb) {
        EditText et = new EditText(this);
        et.setHint(R.string.music_playlist_name_hint);
        et.setTextColor(0xFFFFFFFF);
        et.setHintTextColor(0xFF5E6B7E);
        et.setSingleLine(true);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad, pad, pad);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.music_new_playlist)
                .setView(et)
                .setPositiveButton(android.R.string.ok, (d, w) -> cb.onResult(et.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
