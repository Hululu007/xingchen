package com.fongmi.android.tv.hongguo.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.SeekBar;

import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.ActivityHongguoLandBinding;
import com.fongmi.android.tv.player.engine.ExoPlayerEngine;
import com.fongmi.android.tv.player.engine.PlaySpec;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.HashMap;
import java.util.Locale;

/**
 * 红果横屏全屏页：从竖屏流的横屏剧「全屏观看」进来，与流页共用会话页表与
 * 解链缓存（同一集 token 不重复注册），断点走 HgStore 与流页互通。
 * 控制件是红果自有的一套（返回/剧名/进度条/选集），不借点播播放页的脸。
 * 显隐规矩与流页全屏一致：播放空闲自动全隐、点屏唤出、自动再隐（防烧屏）。
 */
public class HongguoLandActivity extends BaseActivity {

    private static final String EXTRA_POS = "hg_land_pos";
    private static final long HIDE_DELAY_MS = 3500;

    public static void start(Context context, int globalPos) {
        Intent intent = new Intent(context, HongguoLandActivity.class);
        intent.putExtra(EXTRA_POS, globalPos);
        context.startActivity(intent);
    }

    private ActivityHongguoLandBinding binding;
    private HgFeedSession session;
    private ExoPlayerEngine engine;
    private Player player;
    private int currentPos = -1;
    private String playingVideoId;
    private boolean chromeHidden;
    private boolean seeking;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable hideRunnable = () -> setChromeHidden(true);
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            tick();
            ui.postDelayed(this, 400);
        }
    };

    private final Player.Listener listener = new Player.Listener() {
        @Override
        public void onRenderedFirstFrame() {
            binding.hgLandCover.setVisibility(View.GONE);
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_ENDED) advanceNext();
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            binding.hgLandPlay.setImageResource(isPlaying
                    ? com.fongmi.android.tv.R.drawable.ic_hg_pause : com.fongmi.android.tv.R.drawable.ic_hg_play);
            armAutoHide();
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            binding.hgLandCover.setVisibility(View.VISIBLE);
            binding.hgLandState.setVisibility(View.VISIBLE);
            binding.hgLandState.setText("加载失败，点按重试");
        }
    };

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityHongguoLandBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected boolean useDarkSystemIcons() {
        return false;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        session = HgFeedSession.get();
        binding.hgLandPlayer.setUseController(false);
        binding.hgLandPlayer.setShutterBackgroundColor(0xFF000000);
        currentPos = getIntent().getIntExtra(EXTRA_POS, -1);
        if (currentPos < 0 || currentPos >= session.items.size()) {
            binding.hgLandState.setVisibility(View.VISIBLE);
            binding.hgLandState.setText("内容已失效");
            return;
        }
        playCurrent();
        ui.post(ticker);
        armAutoHide();
    }

    @Override
    protected void initEvent() {
        binding.hgLandBack.setOnClickListener(v -> finish());
        binding.hgLandTap.setOnClickListener(v -> {
            if (chromeHidden) {
                setChromeHidden(false);
                armAutoHide();
            } else {
                setChromeHidden(true);
            }
        });
        binding.hgLandPlay.setOnClickListener(v -> togglePlayPause());
        binding.hgLandEpisodes.setOnClickListener(v ->
                HgEpisodeSheet.show(this, session, currentPos, pos -> {
                    saveProgress();
                    currentPos = pos;
                    playingVideoId = null;
                    playCurrent();
                }));
        binding.hgLandState.setOnClickListener(v -> {
            binding.hgLandState.setVisibility(View.GONE);
            HgFeedItem item = session.items.get(currentPos);
            if (item.videoId != null) session.resolver.close(item.videoId);
            playingVideoId = null;
            playCurrent();
        });
        binding.hgLandSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && player != null && player.getDuration() > 0) {
                    binding.hgLandTime.setText(formatTime(progress * player.getDuration() / 1000)
                            + "/" + formatTime(player.getDuration()));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                seeking = true;
                ui.removeCallbacks(hideRunnable);
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                seeking = false;
                if (player != null && player.getDuration() > 0) {
                    player.seekTo(bar.getProgress() * player.getDuration() / 1000);
                }
                armAutoHide();
            }
        });
    }

    private void playCurrent() {
        if (currentPos < 0 || currentPos >= session.items.size()) return;
        HgFeedItem item = session.items.get(currentPos);
        binding.hgLandTitle.setText(item.title == null ? "" : item.title
                + (item.episodeIndex > 0 ? " 第" + item.episodeIndex + "集" : ""));
        binding.hgLandCover.setVisibility(View.VISIBLE);
        ImgUtil.load(item.title == null ? "" : item.title, item.coverUrl, binding.hgLandCover);
        if (item.videoId == null) {
            binding.hgLandState.setVisibility(View.VISIBLE);
            binding.hgLandState.setText("这一集还没准备好");
            return;
        }
        if (item.videoId.equals(playingVideoId)) return;
        final String videoId = item.videoId;
        session.resolver.resolve(videoId, new HgPlayResolver.Listener() {
            @Override
            public void onReady(String vid, String localUrl, int w, int h) {
                if (!vid.equals(currentVideoId())) return;
                ensureEngine();
                if (engine == null || player == null) return;
                HgStore.Progress p = HgStore.progressOf(item.seriesId);
                long startAt = p != null && p.episodeIndex == item.episodeIndex ? p.positionMs : 0;
                MediaMetadata metadata = new MediaMetadata.Builder()
                        .setTitle(item.title == null ? "" : item.title).build();
                PlaySpec spec = PlaySpec.from("hongguo:" + vid, localUrl, new HashMap<>(), metadata);
                playingVideoId = vid;
                if (startAt > 0) engine.start(spec, startAt, true);
                else engine.start(spec, true);
                armAutoHide();
            }

            @Override
            public void onFailed(String vid, String message) {
                if (!vid.equals(currentVideoId())) return;
                binding.hgLandState.setVisibility(View.VISIBLE);
                binding.hgLandState.setText("加载失败，点按重试");
            }
        });
        session.prefetcher.prefetchAround(session.seriesVideoIds(item.seriesId),
                session.indexWithinSeries(item), null);
    }

    private String currentVideoId() {
        if (currentPos < 0 || currentPos >= session.items.size()) return null;
        return session.items.get(currentPos).videoId;
    }

    private void ensureEngine() {
        if (engine != null) return;
        try {
            int decode = PlayerSetting.isVideoPrefer(PlayerSetting.EXO) ? PlayerEngine.HARD : PlayerEngine.SOFT;
            engine = new ExoPlayerEngine(decode, listener);
            player = engine.getPlayer();
            binding.hgLandPlayer.setPlayer(player);
        } catch (Throwable e) {
            engine = null;
            player = null;
        }
    }

    private void advanceNext() {
        saveProgress();
        if (currentPos + 1 < session.items.size()
                && session.items.get(currentPos + 1).seriesId != null
                && session.items.get(currentPos + 1).seriesId.equals(session.items.get(currentPos).seriesId)) {
            currentPos++;
            playingVideoId = null;
            playCurrent();
        }
    }

    private void togglePlayPause() {
        if (player == null) return;
        if (player.isPlaying()) player.pause();
        else {
            player.play();
            armAutoHide();
        }
    }

    private void tick() {
        if (player == null) return;
        long duration = player.getDuration();
        long position = player.getCurrentPosition();
        if (duration > 0 && !seeking) {
            binding.hgLandSeek.setProgress((int) (position * 1000 / duration));
            binding.hgLandTime.setText(formatTime(position) + "/" + formatTime(duration));
        }
    }

    private void saveProgress() {
        if (player == null || currentPos < 0 || currentPos >= session.items.size()) return;
        HgFeedItem item = session.items.get(currentPos);
        if (item.seriesId == null || item.episodeIndex <= 0) return;
        long pos = player.getCurrentPosition();
        if (pos > 0) HgStore.saveProgress(item.seriesId, item.episodeIndex, pos);
    }

    private void setChromeHidden(boolean hidden) {
        chromeHidden = hidden;
        binding.hgLandTop.setVisibility(hidden ? View.GONE : View.VISIBLE);
        binding.hgLandBottom.setVisibility(hidden ? View.GONE : View.VISIBLE);
        WindowInsetsControllerCompat controller =
                new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        if (hidden) controller.hide(WindowInsetsCompat.Type.systemBars());
        else controller.show(WindowInsetsCompat.Type.systemBars());
    }

    private void armAutoHide() {
        ui.removeCallbacks(hideRunnable);
        if (player != null && player.isPlaying()) ui.postDelayed(hideRunnable, HIDE_DELAY_MS);
    }

    private static String formatTime(long ms) {
        long sec = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%d:%02d", sec / 60, sec % 60);
    }

    @Override
    protected void onPause() {
        saveProgress();
        if (player != null) player.pause();
        super.onPause();
    }

    @Override
    protected void onBackInvoked() {
        if (chromeHidden) {
            setChromeHidden(false);
            armAutoHide();
            return;
        }
        super.onBackInvoked();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(ticker);
        ui.removeCallbacks(hideRunnable);
        saveProgress();
        binding.hgLandPlayer.setPlayer(null);
        if (engine != null) {
            try {
                engine.release();
            } catch (Throwable ignored) {
            }
            engine = null;
            player = null;
        }
        // 解链 token 不在此回收：竖屏流页回来还要续播同一集，回收归流页退出时统一做
        super.onDestroy();
    }
}
