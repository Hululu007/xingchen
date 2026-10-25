package com.fongmi.android.tv.ui.music;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextUtils;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.fongmi.android.tv.bean.music.MusicLyric;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.music.MusicAudioInfo;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicLibrary;
import com.fongmi.android.tv.music.MusicLyricCache;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.music.RichLyrics;
import com.fongmi.android.tv.player.lyrics.LyricsParser;
import com.fongmi.android.tv.ui.dialog.MusicQualityPanelDialog;
import com.fongmi.android.tv.ui.dialog.MusicQueueDialog;

import java.util.List;

/**
 * 音乐播放页宿主（重做第一步建骨架、第二步接经典）。
 * 职责只有装配与消费：满幅贴顶（封面卡/经典不垫状态栏，T-5 定案）、按设置主题挂主题视图、
 * 把控制器/歌词缓存/收藏/音质的既有读口喂给主题。播放服务、队列、仓库、取词链只消费不改。
 * 主题经 PlayerThemeFace 统一驱动，宿主不按主题分叉：classic→ClassicThemeView（默认，
 * 2026-10-25 纠正回瓜子原值）、cover_immersive→CoverCardThemeView；Apple/CD/黑胶未建，
 * 设置指向它们时回落经典渲染（不崩不空白），后续步逐个接回。
 */
public class MusicPlayerActivity extends AppCompatActivity implements MusicController.Listener {

    public static void start(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, MusicPlayerActivity.class);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private final Handler tickHandler = new Handler(Looper.getMainLooper());
    private CoverAmbientView ambient;
    private PlayerThemeFace theme;
    private MusicTrack boundTrack;
    private String boundKey;
    private boolean bound;
    private boolean lyricSettled;
    private int lyricWaitTicks;
    private int lastSwapDir = CoverStackView.DIR_NEXT;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) return;
            onTick();
            tickHandler.postDelayed(this, 500L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 满幅贴顶：内容（含状态栏带）由封面一体垫底，封面卡主题不加顶垫
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);

        FrameLayout root = new FrameLayout(this);
        ambient = new CoverAmbientView(this);
        // 流体三件套（瓜子默认全开）：流体开关/节拍开关/速度两倍率进氛围层；
        // 节拍分析门同步置位（分析器在播放服务里，帧槽 MusicBeatState 跨层共享）
        ambient.setFluidEnabled(MusicPlayerSettings.isFluidBg());
        ambient.setBeatEnabled(MusicPlayerSettings.isFluidBeat());
        ambient.setSpeedFactors(MusicPlayerSettings.fluidSpeedFactor(), MusicPlayerSettings.fluidBeatFactor());
        com.fongmi.android.tv.service.MusicBeatState.requestEnabled =
                MusicPlayerSettings.isFluidBg() && MusicPlayerSettings.isFluidBeat();
        root.addView(ambient, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 主题路由（对照清单第二步 A2）：封面卡片走封面卡；classic 与未建主题（Apple/CD/黑胶）
        // 一律落经典——经典是默认主题，getTheme() 本身已对非法值回落经典，双保险
        String themeId = MusicPlayerSettings.getTheme();
        if (MusicPlayerSettings.THEME_COVER_IMMERSIVE.equals(themeId)) {
            theme = new CoverCardThemeView(this);
        } else {
            theme = new ClassicThemeView(this);
        }
        root.addView((android.view.View) theme, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        wireTheme();
        MusicController.get().bind(this);
        MusicController.get().addListener(this);
        bound = true;
        MusicTrack current = MusicController.get().current();
        if (current != null) bindTrack(current, false);
        theme.setPlaying(MusicController.get().isPlaying());
        tickHandler.post(tick);
    }

    private void wireTheme() {
        theme.setCallback(new PlayerThemeFace.Callback() {
            @Override
            public void onTogglePlay() {
                MusicController.get().toggle();
            }

            @Override
            public void onPrev() {
                lastSwapDir = CoverStackView.DIR_PREV;
                MusicController.get().prev();
            }

            @Override
            public void onNext() {
                lastSwapDir = CoverStackView.DIR_NEXT;
                MusicController.get().next();
            }

            @Override
            public void onQueue() {
                if (!isFinishing() && !isDestroyed()) MusicQueueDialog.show(MusicPlayerActivity.this);
            }

            @Override
            public void onFavoriteClick() {
                toggleFavorite();
            }

            @Override
            public void onSeekMs(long positionMs) {
                MusicController.get().seekTo(positionMs);
            }

            @Override
            public void onQualityClick() {
                MusicTrack track = MusicController.get().current();
                if (track == null || isFinishing() || isDestroyed()) return;
                MusicQualityPanelDialog.show(MusicPlayerActivity.this, track,
                        MusicController.get().getQuality(),
                        quality -> MusicController.get().setQuality(quality));
            }

            @Override
            public void onLyricChipToggle(boolean showTranslation) {
                MusicStore.setShowTranslation(showTranslation);
            }

            @Override
            public void onOpenTools() {
                // 工具面板本体未建（已随清场删除、属面板步）：本步入口在位、暂无面板可开
                // （对照清单第二步 G5 缺口），不自创替代面板形态
            }
        });
    }

    private void toggleFavorite() {
        MusicTrack track = MusicController.get().current();
        if (track == null) return;
        boolean target = !theme.isFavorite();
        theme.setFavorite(target);
        vibrateFavorite();
        MusicLibrary.setFavorite(track, target, () -> refreshFavorite(track));
    }

    private void refreshFavorite(MusicTrack track) {
        MusicLibrary.isFavorite(track, fav -> {
            if (isFinishing() || isDestroyed()) return;
            if (track == boundTrack) theme.setFavorite(fav);
        });
    }

    private void vibrateFavorite() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(20L, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(20L);
            }
        } catch (Throwable ignored) {
        }
    }

    // ================= 切歌装配（不挂旧脸：封面先清后载由 CoverStackView 内部保证） =================

    private void bindTrack(MusicTrack track, boolean animate) {
        boundTrack = track;
        boundKey = MusicQueue.trackKey(track);
        lyricSettled = false;
        lyricWaitTicks = 0;
        theme.setTrack(track.title, track.artist, track.album);
        theme.setSwipeHints(swipeHintTitle(-1), swipeHintTitle(1));
        theme.setCover(track.cover, track.title, animate, lastSwapDir);
        ambient.setCover(track.cover);
        theme.setLyrics(null, true);
        theme.setQualityText(describeAudio());
        refreshFavorite(track);
        refreshInfoDetail();
        tryLoadLyricFromCache(track);
    }

    /**
     * 竖滑提示的目标歌名：按控制器队列的列表序取当前曲相邻标题（delta −1 上一首／+1 下一首）。
     * 随机模式的真实下一首由服务内队列决定、此读口拿不到，提示按列表序近似（对照清单第二步 G2 注记）。
     */
    private String swipeHintTitle(int delta) {
        try {
            java.util.List<MusicTrack> queue = MusicController.get().queue();
            if (queue == null || queue.isEmpty() || boundKey == null) return null;
            int index = -1;
            for (int i = 0; i < queue.size(); i++) {
                if (boundKey.equals(MusicQueue.trackKey(queue.get(i)))) {
                    index = i;
                    break;
                }
            }
            int target = index + delta;
            if (index < 0 || target < 0 || target >= queue.size()) return null;
            MusicTrack t = queue.get(target);
            return t == null ? null : t.title;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String describeAudio() {
        MusicAudioInfo info = MusicController.get().audioInfo();
        return info == null ? null : info.describe();
    }

    private void refreshInfoDetail() {
        MusicTrack track = boundTrack;
        if (track == null) return;
        StringBuilder sb = new StringBuilder();
        String quality = describeAudio();
        if (!TextUtils.isEmpty(quality)) sb.append("音质 ").append(quality);
        if (track.duration > 0) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("时长 ").append(CoverCardThemeView.formatTime(track.duration));
        }
        theme.setInfoDetail(sb.toString());
    }

    private void tryLoadLyricFromCache(MusicTrack track) {
        MusicLyric lyric = MusicLyricCache.get(track);
        if (lyric == null || !lyric.hasLyric()) return;
        applyLyric(track, lyric);
    }

    private void applyLyric(MusicTrack track, MusicLyric lyric) {
        if (track != boundTrack) return;
        List<com.fongmi.android.tv.player.lyrics.LyricsLine> main =
                LyricsParser.parseWordLyric(lyric.rawLrc);
        List<com.fongmi.android.tv.player.lyrics.LyricsLine> trans = TextUtils.isEmpty(lyric.translation)
                ? null : LyricsParser.parseTimed(lyric.translation);
        List<com.fongmi.android.tv.player.lyrics.LyricsLine> roma = TextUtils.isEmpty(lyric.romanization)
                ? null : LyricsParser.parseTimed(lyric.romanization);
        List<RichLyrics.Line> lines = RichLyrics.align(main, trans, roma);
        lyricSettled = true;
        theme.setLyrics(lines, false);
    }

    private void onTick() {
        MusicController controller = MusicController.get();
        MusicTrack current = controller.current();
        if (current != null && !MusicQueue.trackKey(current).equals(boundKey)) {
            bindTrack(current, true);
        }
        if (boundTrack == null) return;
        long position = controller.position();
        long duration = controller.duration();
        theme.setProgress(position, duration);
        theme.advanceLyric(position);
        // 歌词：未定时先等缓存落位（服务取词完成另走 onLyricReady）；定时后按位置＋偏移推当前行
        if (!lyricSettled && lyricWaitTicks < 20) {
            lyricWaitTicks++;
            tryLoadLyricFromCache(boundTrack);
        }
    }

    // ================= MusicController.Listener =================

    @Override
    public void onTrackChanged(MusicTrack track) {
        if (isFinishing() || isDestroyed()) return;
        if (track != null) bindTrack(track, true);
    }

    @Override
    public void onPlayingChanged(boolean playing) {
        if (isFinishing() || isDestroyed()) return;
        theme.setPlaying(playing);
    }

    @Override
    public void onProgress(long positionMs, long durationMs) {
        // 进度走 tick 轮询统一节拍，这里不单独刷
    }

    @Override
    public void onQueueChanged() {
        if (isFinishing() || isDestroyed()) return;
        MusicTrack current = MusicController.get().current();
        if (current != null && !MusicQueue.trackKey(current).equals(boundKey)) bindTrack(current, true);
    }

    @Override
    public void onModeChanged(int mode) {
    }

    @Override
    public void onSleepChanged(int sleep) {
    }

    @Override
    public void onAudioInfoChanged() {
        if (isFinishing() || isDestroyed()) return;
        theme.setQualityText(describeAudio());
        refreshInfoDetail();
    }

    @Override
    public void onKeyIssue(String platform, String detail) {
    }

    @Override
    public void onTrackReady(MusicTrack track) {
        if (isFinishing() || isDestroyed()) return;
        if (track == boundTrack && !lyricSettled) tryLoadLyricFromCache(track);
    }

    @Override
    public void onLyricReady(MusicTrack track) {
        if (isFinishing() || isDestroyed()) return;
        if (track != boundTrack) return;
        MusicLyric lyric = MusicLyricCache.get(track);
        if (lyric != null && lyric.hasLyric()) {
            applyLyric(track, lyric);
        } else {
            lyricSettled = true;
            theme.setLyrics(java.util.Collections.emptyList(), false);
        }
    }

    @Override
    public void onTrackFailed(MusicTrack track, int reasonRes) {
    }

    @Override
    protected void onDestroy() {
        com.fongmi.android.tv.service.MusicBeatState.requestEnabled = false;
        com.fongmi.android.tv.service.MusicBeatState.silence();
        tickHandler.removeCallbacks(tick);
        if (bound) {
            MusicController.get().removeListener(this);
            MusicController.get().unbind();
            bound = false;
        }
        super.onDestroy();
    }
}
