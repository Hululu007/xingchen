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
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
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
 * 播放页宿主（从零重写）：封面一体氛围层＋主题层＋迷你进度条（星辰自有，与主题底部栈互斥）。
 * 主题经 PlayerThemeFace 契约接线；取色走 CoverColors（瓜子双链），状态栏色独立 450ms 动画。
 */
public class MusicPlayerActivity extends AppCompatActivity implements MusicController.Listener {

    public static void start(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, MusicPlayerActivity.class);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private static final long MINI_HIDE_MS = 3000L;

    private final Handler tickHandler = new Handler(Looper.getMainLooper());
    private CoverAmbientView ambient;
    private PlayerThemeFace theme;
    private View themeView;
    private MiniProgressView miniBar;
    private MusicTrack boundTrack;
    private String boundKey;
    private boolean bound;
    private boolean lyricSettled;
    private int lyricWaitTicks;
    private int lastSwapDir = 1;
    private boolean stackExpanded = true;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) return;
            onTick();
            tickHandler.postDelayed(this, 500L);
        }
    };

    private final Runnable collapseRunnable = () -> {
        if (isFinishing() || isDestroyed()) return;
        if (MusicController.get().isPlaying()) setStackExpanded(false);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        if (MusicStore.isPlayerKeepScreenOn()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        FrameLayout root = new FrameLayout(this);
        ambient = new CoverAmbientView(this);
        ambient.setFluid(MusicPlayerSettings.isFluidBg(), MusicPlayerSettings.isFluidBeat(),
                MusicPlayerSettings.fluidSpeedFactor());
        com.fongmi.android.tv.service.MusicBeatState.requestEnabled =
                MusicPlayerSettings.isFluidBg() && MusicPlayerSettings.isFluidBeat();
        root.addView(ambient, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        theme = createTheme(MusicPlayerSettings.getTheme());
        themeView = (View) theme;
        root.addView(themeView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        miniBar = new MiniProgressView(this);
        FrameLayout.LayoutParams miniLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        miniLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        miniLp.bottomMargin = Math.round(12f * getResources().getDisplayMetrics().density);
        root.addView(miniBar, miniLp);
        miniBar.setVisibility(View.GONE);
        miniBar.setOnClickListener(v -> {
            setStackExpanded(true);
            restartCollapseTimer();
        });
        setContentView(root);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = 0;
            int bottom = 0;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            themeView.setPadding(0, top, 0, 0);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) miniBar.getLayoutParams();
            lp.bottomMargin = Math.round(12f * getResources().getDisplayMetrics().density) + bottom;
            miniBar.setLayoutParams(lp);
            return insets;
        });

        wireTheme();
        MusicController.get().bind(this);
        MusicController.get().addListener(this);
        bound = true;
        MusicTrack current = MusicController.get().current();
        if (current != null) bindTrack(current, false);
        theme.setPlaying(MusicController.get().isPlaying());
        theme.setMode(MusicController.get().getMode());
        tickHandler.post(tick);
        restartCollapseTimer();
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

    /** 主题切换后重建主题层（工具面板主题子面板调用）。 */
    public void recreateTheme() {
        if (themeView == null) return;
        FrameLayout root = (FrameLayout) themeView.getParent();
        if (root == null) return;
        int idx = root.indexOfChild(themeView);
        int topPad = themeView.getPaddingTop();
        root.removeView(themeView);
        theme = createTheme(MusicPlayerSettings.getTheme());
        themeView = (View) theme;
        root.addView(themeView, idx, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        themeView.setPadding(0, topPad, 0, 0);
        wireTheme();
        MusicTrack current = MusicController.get().current();
        if (current != null) bindTrack(current, false);
        theme.setPlaying(MusicController.get().isPlaying());
        theme.setMode(MusicController.get().getMode());
        theme.setBottomStackExpanded(stackExpanded);
        ambient.setFluid(MusicPlayerSettings.isFluidBg(), MusicPlayerSettings.isFluidBeat(),
                MusicPlayerSettings.fluidSpeedFactor());
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

    private void setStackExpanded(boolean expanded) {
        stackExpanded = expanded;
        theme.setBottomStackExpanded(expanded);
        miniBar.setVisibility(expanded ? View.GONE : View.VISIBLE);
        if (!expanded) tickHandler.removeCallbacks(collapseRunnable);
    }

    private void restartCollapseTimer() {
        tickHandler.removeCallbacks(collapseRunnable);
        if (MusicController.get().isPlaying()) tickHandler.postDelayed(collapseRunnable, MINI_HIDE_MS);
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        if (!stackExpanded) setStackExpanded(true);
        restartCollapseTimer();
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
        applyCoverColors(track.cover);
        theme.setLyrics(null, true);
        theme.setLyricState(null, false);
        theme.setQualityText(describeAudio());
        setStackExpanded(true);
        restartCollapseTimer();
        refreshFavorite(track);
        refreshInfoDetail();
        tryLoadLyricFromCache(track);
    }

    private void applyCoverColors(String url) {
        CoverColors.load(this, url, (single, extracted) -> {
            if (isFinishing() || isDestroyed()) return;
            MusicTrack current = MusicController.get().current();
            if (current == null || url == null || !url.equals(current.cover)) return;
            int base = single != null ? single : CoverColors.fallback();
            ambient.setCover(url, base);
            int bar = extracted != null ? extracted.statusBar : base;
            animateStatusBar(bar);
        });
        Integer cachedSingle = CoverColors.single(url);
        if (cachedSingle == null) ambient.setCover(url, CoverColors.fallback());
    }

    private android.animation.ValueAnimator statusAnim;

    private void animateStatusBar(int to) {
        if (statusAnim != null) statusAnim.cancel();
        final int from = getWindow().getStatusBarColor();
        statusAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        statusAnim.setDuration(450);
        statusAnim.setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f));
        statusAnim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            getWindow().setStatusBarColor(blendColor(from, to, f));
        });
        statusAnim.start();
    }

    private static int blendColor(int from, int to, float f) {
        int a = Math.round(android.graphics.Color.alpha(from) + (android.graphics.Color.alpha(to) - android.graphics.Color.alpha(from)) * f);
        int r = Math.round(android.graphics.Color.red(from) + (android.graphics.Color.red(to) - android.graphics.Color.red(from)) * f);
        int g = Math.round(android.graphics.Color.green(from) + (android.graphics.Color.green(to) - android.graphics.Color.green(from)) * f);
        int b = Math.round(android.graphics.Color.blue(from) + (android.graphics.Color.blue(to) - android.graphics.Color.blue(from)) * f);
        return android.graphics.Color.argb(a, r, g, b);
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
        } catch (Throwable ignored) {
        }
    }

    private String describeAudio() {
        MusicAudioInfo info = MusicController.get().audioInfo();
        return info == null ? null : info.describe();
    }

    /** 播放页音质格口径：无损只显示编码名、有损显示编码+真实码率，取不到不显示。 */
    public static String qualityGridText(MusicAudioInfo info) {
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
            sb.append("时长 ").append(formatTime(track.duration));
        }
        theme.setInfoDetail(sb.toString());
    }

    public static String formatTime(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return h + ":" + two(m) + ":" + two(s);
        return m + ":" + two(s);
    }

    private static String two(long v) {
        return v < 10 ? "0" + v : String.valueOf(v);
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
        if (MusicLyricActivity.isShowing()) MusicLyricActivity.reloadLyrics();
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
        miniBar.setProgress(position, duration);
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
        if (!playing) {
            setStackExpanded(true);
            tickHandler.removeCallbacks(collapseRunnable);
        } else {
            restartCollapseTimer();
        }
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
            if (MusicLyricActivity.isShowing()) MusicLyricActivity.reloadLyrics();
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
        tickHandler.removeCallbacks(collapseRunnable);
        if (bound) {
            MusicController.get().removeListener(this);
            MusicController.get().unbind();
            bound = false;
        }
        super.onDestroy();
    }
}
