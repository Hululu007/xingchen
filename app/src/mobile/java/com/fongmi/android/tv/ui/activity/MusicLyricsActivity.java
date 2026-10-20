package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.os.Bundle;
import android.widget.EditText;

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
 * 全屏歌词页（照 GZ 本体＋实拍转写）：上半部约 45% 封面图本体整幅显示、下缘渐变融进
 * CoverColor 氛围底；封面下方歌名歌手在左（歌手行小心形收藏态）、右侧三裸图标；
 * 歌词区在标题行下到胶囊上；底部居中双胶囊（音质：点按开四档选档、显示走实测口径；
 * 逐字：点按真切换逐字/整行渲染路径）＋发丝细进度线（仅显示、不可拖——GZ 歌词页底部
 * 无控制行、进度线仅显示，参数表第 3 节），无主控行（暂停只在头部第三图标）。
 * 页面不放返回箭头，走系统返回。
 */
public class MusicLyricsActivity extends BaseActivity {

    private ActivityMusicLyricsBinding binding;
    private boolean favorited;
    /** 当前歌词是否带逐字时间轴（逐字胶囊显隐与可点状态据此）。 */
    private boolean hasWordLines;
    private String bgCoverUrl;
    private int ambientColor = 0xFF1C1C1E;

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

        @Override
        public void onAudioInfoChanged() {
            runOnUiThread(() -> refreshQualityChip());
        }
    };

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityMusicLyricsBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        // 深底页：状态栏图标显式浅色，防音乐首页设置的深色图标串扰（06:17 状态栏衔接核查）
        ReadingSkin.setSystemBarIconAppearance(this, true);
        MusicRepository.ensureDebugFake();
        MusicController.get().bind(this);
        binding.btnMenu.setOnClickListener(v -> openTools());
        binding.btnQueue.setOnClickListener(v -> MusicQueueDialog.show(this));
        binding.btnPlayTop.setOnClickListener(v -> MusicController.get().toggle());
        binding.btnFav.setOnClickListener(v -> toggleFavorite());
        binding.lyricsView.setOnLineClickListener((index, positionMs) -> {
            MusicController.get().seekTo(positionMs);
            binding.lyricsView.seekUi(positionMs);
        });
        // 底部发丝进度线仅显示（GZ 口径）：SeekBar 只作进度呈现，禁用交互（布局 enabled=false），不挂拖动监听。
        binding.seekBar.setMax(1000);
        binding.chipQuality.setOnClickListener(v -> showQualityDialog());
        binding.chipWord.setOnClickListener(v -> toggleWordByWord());
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
            // 无曲目：歌词视图等待态（不许露空态/旧歌词）
            binding.tvTitle.setText(R.string.music_no_track);
            binding.tvArtist.setText("");
            applyPlainBackground();
            binding.lyricsView.showWaiting();
            hasWordLines = false;
            binding.chipQuality.setVisibility(View.GONE);
            binding.chipWord.setVisibility(View.GONE);
            return;
        }
        binding.tvTitle.setText(track.displayTitle());
        binding.tvArtist.setText(track.displayArtist());
        loadBackground(track);
        loadLyrics(track, false);
        refreshFavorite(track);
        refreshQualityChip();
    }

    private void applyPlainBackground() {
        bgCoverUrl = null;
        binding.ivCover.setImageBitmap(null);
        ambientColor = 0xFF1C1C1E;
        binding.rootLayout.setBackgroundColor(ambientColor);
        applyCoverFade();
    }

    /** 封面下缘渐变：上部透明、下部融进当前氛围色（切歌取色后重生成） */
    private void applyCoverFade() {
        GradientDrawable fade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x00000000, ambientColor, ambientColor});
        binding.coverFade.setBackground(fade);
    }

    /** 封面本体整幅显示（ivCover 非糊底）；氛围底另取 64px 小图 CoverColor 取色刷根背景＋下缘渐变 */
    private void loadBackground(MusicTrack track) {
        if (track.cover == null || track.cover.isEmpty()) {
            applyPlainBackground();
            return;
        }
        if (track.cover.equals(bgCoverUrl)) return;
        bgCoverUrl = track.cover;
        com.fongmi.android.tv.utils.ImgUtil.load(track.displayTitle(), track.cover, binding.ivCover);
        try {
            Glide.with(this).asBitmap().load(com.fongmi.android.tv.utils.ImgUtil.getUrl(track.cover)).override(64, 64).centerCrop()
                    .into(new CustomTarget<Bitmap>() {
                        @Override
                        public void onResourceReady(Bitmap resource, com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                            if (isFinishing() || isDestroyed()) return;
                            if (!track.cover.equals(bgCoverUrl)) return;
                            ambientColor = CoverColor.ambientBackground(resource);
                            binding.rootLayout.setBackgroundColor(ambientColor);
                            applyCoverFade();
                        }

                        @Override
                        public void onLoadFailed(android.graphics.drawable.Drawable errorDrawable) {
                            if (!track.cover.equals(bgCoverUrl)) return;
                            ambientColor = 0xFF1C1C1E;
                            binding.rootLayout.setBackgroundColor(ambientColor);
                            applyCoverFade();
                        }
                    });
        } catch (Throwable ignored) {
            applyPlainBackground();
        }
    }

    // ================= 歌词数据链（与播放页同款：lyricRich→parseWordLyric→RichLyrics.align） =================

    private void loadLyrics(MusicTrack track, boolean force) {
        // 取词未回先露加载态，不许先露空态（时序接线口径）
        binding.lyricsView.showLoading();
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
                if (resultMain.isEmpty()) {
                    // 取词确认为空才露空态
                    binding.lyricsView.showNoLyric();
                    refreshWordChip(null);
                } else {
                    binding.lyricsView.setLines(resultMain, resultTrans, resultRoma);
                    refreshWordChip(resultMain);
                }
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

    // ================= 进度 =================

    private void onProgressUi(long position, long duration) {
        if (duration > 0) {
            binding.seekBar.setProgress((int) (position * 1000 / duration));
        }
        binding.lyricsView.updatePosition(position);
    }

    private void setPlayingUi(boolean playing) {
        binding.lyricsView.setPlaying(playing);
        // 暂停只剩头部第三图标（底部主控行已撤）
        binding.btnPlayTop.setImageResource(playing ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
    }

    // ================= 胶囊与收藏 =================

    /**
     * 音质胶囊：显示走实测口径（无损只显编码名、有损显「码率k」如 320k；取不到实测时只留
     * 「音质」通用标签，所选档永不进显示——与播放页经典筹同口径）；点按开四档选档。
     */
    private void refreshQualityChip() {
        if (MusicController.get().current() == null) {
            binding.chipQuality.setVisibility(View.GONE);
            return;
        }
        String label = null;
        try {
            com.fongmi.android.tv.music.MusicAudioInfo info = MusicController.get().audioInfo();
            if (info != null && info.codec != null && !info.codec.isEmpty()) {
                if (info.lossless) label = info.codec;
                else if (info.bitrateKbps > 0) label = info.bitrateKbps + "k";
            }
        } catch (Throwable ignored) {
        }
        binding.chipQuality.setText(label == null ? getString(R.string.music_quality) : label);
        binding.chipQuality.setVisibility(View.VISIBLE);
    }

    /** 音质四档选档（与播放页 showQualityDialog 同链：MusicOptionsDialog＋setQuality＋reloadQuality）。 */
    private void showQualityDialog() {
        if (MusicController.get().current() == null) return;
        List<String> opts = new ArrayList<>();
        int[] values = {MusicStore.QUALITY_LOW, MusicStore.QUALITY_STANDARD, MusicStore.QUALITY_HIGH, MusicStore.QUALITY_SUPER};
        for (int v : values) opts.add(MusicStore.qualityName(v));
        MusicOptionsDialog.show(this, getString(R.string.music_quality), opts, MusicController.get().getQuality(), idx -> {
            if (MusicController.get().getQuality() != values[idx]) {
                MusicController.get().setQuality(values[idx]);
                MusicController.get().reloadQuality();
                refreshQualityChip();
                Notify.show(getString(R.string.lyr_quality_switched) + MusicStore.qualityName(values[idx]));
            }
        });
    }

    /** 逐字胶囊：任一行带逐字时间轴才显示；点按真切换逐字/整行渲染路径，关时胶囊压暗示意。 */
    private void refreshWordChip(List<LyricsLine> lines) {
        hasWordLines = false;
        if (lines != null) {
            for (LyricsLine line : lines) {
                if (line != null && line.hasWords()) {
                    hasWordLines = true;
                    break;
                }
            }
        }
        binding.chipWord.setVisibility(hasWordLines ? View.VISIBLE : View.GONE);
        paintWordChip();
    }

    private void paintWordChip() {
        boolean on = binding.lyricsView.isWordByWord();
        binding.chipWord.setAlpha(on ? 1f : 0.45f);
        binding.chipWord.setTextColor(on ? 0xFFFFFFFF : 0xFF9A9AA0);
    }

    private void toggleWordByWord() {
        if (!hasWordLines) return;
        boolean on = !binding.lyricsView.isWordByWord();
        binding.lyricsView.setWordByWord(on);
        paintWordChip();
        Notify.show(on ? R.string.lyr_word_on : R.string.lyr_word_off);
    }

    private void refreshFavorite(MusicTrack track) {
        if (track == null || track.id == null) {
            favorited = false;
            paintFav();
            return;
        }
        MusicLibrary.isFavorite(track.id, fav -> {
            favorited = fav;
            paintFav();
        });
    }

    private void paintFav() {
        binding.btnFav.setImageResource(favorited ? R.drawable.ic_music_heart_fill : R.drawable.ic_music_heart);
        binding.btnFav.setImageTintList(ColorStateList.valueOf(favorited ? 0xFFFA243C : 0xFFFFFFFF));
    }

    private void toggleFavorite() {
        MusicTrack track = MusicController.get().current();
        if (track == null) return;
        MusicLibrary.setFavorite(track, !favorited, () -> {
            favorited = !favorited;
            paintFav();
            Notify.show(favorited ? R.string.music_favorited : R.string.music_unfavorited);
        });
    }
}
