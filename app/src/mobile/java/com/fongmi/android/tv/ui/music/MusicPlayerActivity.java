package com.fongmi.android.tv.ui.music;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
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
    private int lastSwapDir = 1;

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
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);

        FrameLayout root = new FrameLayout(this);
        ambient = new CoverAmbientView(this);
        ambient.setFluidEnabled(MusicPlayerSettings.isFluidBg());
        ambient.setBeatEnabled(MusicPlayerSettings.isFluidBeat());
        ambient.setSpeedFactors(MusicPlayerSettings.fluidSpeedFactor(), MusicPlayerSettings.fluidBeatFactor());
        com.fongmi.android.tv.service.MusicBeatState.requestEnabled =
                MusicPlayerSettings.isFluidBg() && MusicPlayerSettings.isFluidBeat();
        root.addView(ambient, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        theme = createTheme(MusicPlayerSettings.getTheme());
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
        theme.setMode(MusicController.get().getMode());
        tickHandler.post(tick);
    }

    private PlayerThemeFace createTheme(String themeId) {
        if (MusicPlayerSettings.THEME_COVER_IMMERSIVE.equals(themeId)) {
            return new CoverCardThemeView(this);
        }
        if (MusicPlayerSettings.THEME_APPLE_MUSIC.equals(themeId)) {
            return new AppleThemeView(this);
        }
        if (MusicPlayerSettings.THEME_LUMINOUS_CD.equals(themeId)
                || MusicPlayerSettings.THEME_LUMINOUS_CD_DARK.equals(themeId)
                || MusicPlayerSettings.THEME_LUMINOUS_CD_AUTO.equals(themeId)) {
            return new CdThemeView(this);
        }
        if (MusicPlayerSettings.THEME_RECORD_SHELF.equals(themeId)) {
            return new ShelfThemeView(this);
        }
        return new ClassicThemeView(this);
    }

    private void wireTheme() {
        theme.setCallback(new PlayerThemeFace.Callback() {
            @Override
            public void onTogglePlay() {
                MusicController.get().toggle();
            }

            @Override
            public void onPrev() {
                lastSwapDir = -1;
                MusicController.get().prev();
            }

            @Override
            public void onNext() {
                lastSwapDir = 1;
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
                if (boundTrack != null) tryLoadLyricFromCache(boundTrack);
            }

            @Override
            public void onOpenTools() {
                if (!isFinishing() && !isDestroyed()) PlayerToolsDialog.show(MusicPlayerActivity.this);
            }

            @Override
            public void onOpenLyricPage() {
                MusicLyricActivity.start(MusicPlayerActivity.this);
            }

            @Override
            public void onModeClick() {
                MusicController.get().cycleMode();
            }

            @Override
            public void onVolume(float fraction) {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (am == null) return;
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int target = Math.round(Math.max(0f, Math.min(1f, fraction)) * max);
                am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
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

    private void bindTrack(MusicTrack track, boolean animate) {
        boundTrack = track;
        boundKey = MusicQueue.trackKey(track);
        lyricSettled = false;
        lyricWaitTicks = 0;
        theme.setTrack(track.displayTitle(), track.displayArtist(), track.album);
        bindNeighbors();
        theme.setCover(track.cover, track.displayTitle(), animate, lastSwapDir);
        ambient.setCover(track.cover);
        theme.setLyrics(null, true);
        theme.setLyricState(null, false);
        theme.setQualityText(describeAudio());
        refreshFavorite(track);
        refreshInfoDetail();
        tryLoadLyricFromCache(track);
    }

    private void bindNeighbors() {
        try {
            List<MusicTrack> queue = MusicController.get().queue();
            if (queue == null || queue.isEmpty() || boundKey == null) return;
            int index = -1;
            for (int i = 0; i < queue.size(); i++) {
                if (boundKey.equals(MusicQueue.trackKey(queue.get(i)))) {
                    index = i;
                    break;
                }
            }
            if (index < 0) return;
            MusicTrack prev = index > 0 ? queue.get(index - 1) : null;
            MusicTrack next = index + 1 < queue.size() ? queue.get(index + 1) : null;
            theme.setSwipeHints(prev == null ? null : prev.displayTitle(),
                    next == null ? null : next.displayTitle());
            if (theme instanceof ClassicThemeView) {
                ((ClassicThemeView) theme).setNeighbors(
                        prev == null ? null : prev.displayTitle(), prev == null ? null : prev.cover,
                        next == null ? null : next.displayTitle(), next == null ? null : next.cover);
            }
            if (theme instanceof CoverCardThemeView) {
                ((CoverCardThemeView) theme).setNeighbors(
                        prev == null ? null : prev.displayTitle(), prev == null ? null : prev.cover,
                        next == null ? null : next.displayTitle(), next == null ? null : next.cover);
            }
        } catch (Throwable ignored) {
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
            sb.append("时长 ").append(PlayerScrubberView.formatTime(track.duration));
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
        theme.setLyricState(chipLabel(lyric, main), hasWords(main));
    }

    private boolean hasWords(List<com.fongmi.android.tv.player.lyrics.LyricsLine> main) {
        if (main == null) return false;
        for (com.fongmi.android.tv.player.lyrics.LyricsLine l : main) {
            if (l != null && l.hasWords()) return true;
        }
        return false;
    }

    private String chipLabel(MusicLyric lyric, List<com.fongmi.android.tv.player.lyrics.LyricsLine> main) {
        if (lyric.format != null && lyric.format.toUpperCase().contains("AMLL")) return "AMLL";
        if (main == null || main.isEmpty()) return "无词";
        if (hasWords(main)) return "逐字";
        long first = main.get(0).getTimeMs();
        long last = main.get(main.size() - 1).getTimeMs();
        if (main.size() >= 2 && first == last) return "纯文本";
        return "逐行";
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
        if (!lyricSettled && lyricWaitTicks < 20) {
            lyricWaitTicks++;
            tryLoadLyricFromCache(boundTrack);
        }
    }

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
    }

    @Override
    public void onQueueChanged() {
        if (isFinishing() || isDestroyed()) return;
        MusicTrack current = MusicController.get().current();
        if (current != null && !MusicQueue.trackKey(current).equals(boundKey)) bindTrack(current, true);
        else bindNeighbors();
    }

    @Override
    public void onModeChanged(int mode) {
        if (isFinishing() || isDestroyed()) return;
        theme.setMode(mode);
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
            theme.setLyricState("无词", false);
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
