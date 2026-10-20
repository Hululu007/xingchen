package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.SeekBar;

import androidx.viewbinding.ViewBinding;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.MusicPlaylist;
import com.fongmi.android.tv.bean.music.MusicLyric;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.databinding.ActivityMusicLyricsBinding;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicDownloadStore;
import com.fongmi.android.tv.music.MusicLibrary;
import com.fongmi.android.tv.music.MusicRepository;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.music.RichLyrics;
import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.player.lyrics.LyricsParser;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.MusicOptionsDialog;
import com.fongmi.android.tv.ui.dialog.MusicQueueDialog;
import com.fongmi.android.tv.ui.dialog.MusicToolsSheetDialog;
import com.fongmi.android.tv.utils.CoverColor;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 全屏歌词页（经典主题底子）：封面拉满糊底＋取色色罩，歌名/歌手小字压在歌词上头，
 * 右侧三裸图标（菜单/播放列表/播放暂停）；主体 KaraokeLyricsView 全屏滚动逐字歌词，
 * 点行 seek；底部细进度＋主控三键。页面不放返回箭头，走系统返回。
 */
public class MusicLyricsActivity extends BaseActivity {

    private ActivityMusicLyricsBinding binding;
    private boolean seeking;
    private String bgCoverUrl;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, MusicLyricsActivity.class));
    }

    private final MusicController.Listener listener = new MusicController.Listener() {
        @Override
        public void onTrackChanged(MusicTrack track) {
            runOnUiThread(() -> bindTrack(track));
        }

        @Override
        public void onPlayingChanged(boolean playing) {
            runOnUiThread(() -> setPlayingUi(playing));
        }

        @Override
        public void onProgress(long position, long duration) {
            runOnUiThread(() -> onProgressUi(position, duration));
        }
    };

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityMusicLyricsBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        MusicRepository.ensureDebugFake();
        MusicController.get().bind(this);
        binding.btnMenu.setOnClickListener(v -> openTools());
        binding.btnQueue.setOnClickListener(v -> MusicQueueDialog.show(this));
        binding.btnPlayTop.setOnClickListener(v -> MusicController.get().toggle());
        binding.btnPlay.setOnClickListener(v -> MusicController.get().toggle());
        binding.btnPrev.setOnClickListener(v -> MusicController.get().prev());
        binding.btnNext.setOnClickListener(v -> MusicController.get().next());
        binding.lyricsView.setOnLineClickListener((index, positionMs) -> {
            MusicController.get().seekTo(positionMs);
            binding.lyricsView.seekUi(positionMs);
        });
        binding.seekBar.setMax(1000);
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
                long target = dur * seekBar.getProgress() / 1000;
                MusicController.get().seekTo(target);
                binding.lyricsView.seekUi(target);
            }
        });
        MusicController.get().addListener(listener);
        bindTrack(MusicController.get().current());
        setPlayingUi(MusicController.get().isPlaying());
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

    // ================= 歌曲绑定与背景 =================

    private void bindTrack(MusicTrack track) {
        if (track == null) {
            binding.tvTitle.setText(R.string.music_no_track);
            binding.tvArtist.setText("");
            applyPlainBackground();
            binding.lyricsView.setLines(new ArrayList<>(), null, null);
            return;
        }
        binding.tvTitle.setText(track.displayTitle());
        binding.tvArtist.setText(track.displayArtist());
        loadBackground(track);
        loadLyrics(track, false);
    }

    private void applyPlainBackground() {
        bgCoverUrl = null;
        binding.ivBg.setImageBitmap(null);
        binding.bgOverlay.setBackgroundColor(0xFF1C1C1E);
    }

    /** 背景：64px 小图糊底（RSBlur/FastBlur 照海阔写法）＋ CoverColor.ambientBackground 色罩；无封面纯色兜底 */
    private void loadBackground(MusicTrack track) {
        if (track.cover == null || track.cover.isEmpty()) {
            applyPlainBackground();
            return;
        }
        if (track.cover.equals(bgCoverUrl)) return;
        bgCoverUrl = track.cover;
        try {
            Glide.with(this).asBitmap().load(com.fongmi.android.tv.utils.ImgUtil.getUrl(track.cover)).override(64, 64).centerCrop()
                    .into(new CustomTarget<Bitmap>() {
                        @Override
                        public void onResourceReady(Bitmap resource, com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                            if (isFinishing() || isDestroyed()) return;
                            if (!track.cover.equals(bgCoverUrl)) return;
                            Bitmap blurred;
                            try {
                                blurred = jp.wasabeef.glide.transformations.internal.RSBlur.blur(
                                        MusicLyricsActivity.this, resource, 24);
                            } catch (Throwable e) {
                                try {
                                    blurred = jp.wasabeef.glide.transformations.internal.FastBlur.blur(resource, 24, true);
                                } catch (Throwable e2) {
                                    blurred = resource;
                                }
                            }
                            binding.ivBg.setImageBitmap(blurred != null ? blurred : resource);
                            int ambient = CoverColor.ambientBackground(resource);
                            binding.bgOverlay.setBackgroundColor(Color.argb(178,
                                    Color.red(ambient), Color.green(ambient), Color.blue(ambient)));
                        }

                        @Override
                        public void onLoadFailed(android.graphics.drawable.Drawable errorDrawable) {
                            if (!track.cover.equals(bgCoverUrl)) return;
                            binding.ivBg.setImageBitmap(null);
                            binding.bgOverlay.setBackgroundColor(0xFF1C1C1E);
                        }
                    });
        } catch (Throwable ignored) {
            applyPlainBackground();
        }
    }

    // ================= 歌词数据链（与播放页同款：lyricRich→parseWordLyric→RichLyrics.align） =================

    private void loadLyrics(MusicTrack track, boolean force) {
        binding.lyricsView.setLines(new ArrayList<>(), null, null);
        Task.execute(() -> {
            MusicLyric rich = null;
            try {
                rich = MusicRepository.lyricRich(track, force);
            } catch (Throwable ignored) {
            }
            List<LyricsLine> mains = new ArrayList<>();
            List<String> trans = new ArrayList<>();
            List<String> romas = new ArrayList<>();
            if (rich != null) {
                List<LyricsLine> main = rich.rawLrc == null ? new ArrayList<>()
                        : LyricsParser.parseWordLyric(rich.rawLrc);
                List<LyricsLine> tran = rich.translation == null ? null
                        : LyricsParser.parseTimed(rich.translation);
                List<LyricsLine> roma = rich.romanization == null ? null
                        : LyricsParser.parseTimed(rich.romanization);
                for (RichLyrics.Line line : RichLyrics.align(main, tran, roma)) {
                    mains.add(line.main);
                    trans.add(line.translation);
                    romas.add(line.romanization);
                }
            }
            List<LyricsLine> resultMain = mains;
            List<String> resultTrans = trans;
            List<String> resultRoma = romas;
            runOnUiThread(() -> {
                MusicTrack cur = MusicController.get().current();
                if (cur == null || !cur.equals(track)) return;
                binding.lyricsView.setLines(resultMain, resultTrans, resultRoma);
                applyLyricStyle();
                binding.lyricsView.setPlaying(MusicController.get().isPlaying());
                binding.lyricsView.updatePosition(MusicController.get().position());
            });
        });
    }

    /** 歌词样式从 MusicStore 读并重应用（字号/粗细/对齐/模糊/主歌词/译音显隐/高级动效全接）。 */
    private void applyLyricStyle() {
        binding.lyricsView.setCoverMode(false);
        binding.lyricsView.setPrimary(MusicStore.getLyricPrimary());
        binding.lyricsView.setShowSub(MusicStore.isShowTranslation(), MusicStore.isShowRomanization());
        binding.lyricsView.setMainTextSizeSp(MusicStore.getLyricFontSize());
        binding.lyricsView.setLyricAlign(MusicStore.getLyricAlign());
        binding.lyricsView.setLyricWeight(MusicStore.getLyricWeight());
        binding.lyricsView.setBlurLevel(MusicStore.getLyricBlur());
        binding.lyricsView.setAdvancedFx(MusicStore.isAdvancedFx());
    }

    /** 工具面板里「搜索歌词」：与播放页同款的强制找词流程，强制跨插件找后重载本页歌词 */
    private void searchLyricForce() {
        MusicTrack track = MusicController.get().current();
        if (track == null) return;
        Notify.show(R.string.music_lyric_searching);
        Task.execute(() -> {
            MusicLyric rich = null;
            try {
                rich = MusicRepository.lyricRich(track, true);
            } catch (Throwable ignored) {
            }
            boolean found = rich != null && rich.rawLrc != null && !rich.rawLrc.trim().isEmpty();
            runOnUiThread(() -> {
                MusicTrack cur = MusicController.get().current();
                if (cur == null || !cur.equals(track)) return;
                Notify.show(found ? R.string.music_lyric_found : R.string.music_lyric_not_found);
            });
            if (found) loadLyrics(track, true);
        });
    }

    // ================= 工具面板与宿主回调 =================

    private void openTools() {
        MusicToolsSheetDialog.show(this, new MusicToolsSheetDialog.Listener() {
            @Override
            public void onThemeChanged() {
                // 本页是经典主题底子，主题切换在本页无操作（播放页侧生效）
            }

            @Override
            public void onLyricStyleChanged() {
                applyLyricStyle();
            }

            @Override
            public void onSearchLyric() {
                searchLyricForce();
            }

            @Override
            public void onAddToPlaylist() {
                MusicTrack track = MusicController.get().current();
                if (track != null) pickPlaylist(track);
            }

            @Override
            public void onDownload() {
                downloadCurrent();
            }
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
        et.setHintTextColor(0xFF8E8E93);
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

    // ================= 进度/主控 =================

    private void onProgressUi(long position, long duration) {
        if (!seeking && duration > 0) {
            binding.seekBar.setProgress((int) (position * 1000 / duration));
        }
        binding.tvPosition.setText(fmt(position));
        binding.tvDuration.setText(fmt(duration));
        binding.lyricsView.updatePosition(position);
    }

    private void setPlayingUi(boolean playing) {
        binding.lyricsView.setPlaying(playing);
        int icon = playing ? R.drawable.ic_music_pause : R.drawable.ic_music_play;
        binding.btnPlay.setImageResource(icon);
        binding.btnPlayTop.setImageResource(icon);
    }

    private String fmt(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        return String.format("%02d:%02d", s / 60, s % 60);
    }
}
