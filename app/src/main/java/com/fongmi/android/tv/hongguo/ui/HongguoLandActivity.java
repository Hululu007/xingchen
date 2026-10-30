package com.fongmi.android.tv.hongguo.ui;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.HongguoDanmakuApi;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.drama.DramaClient;
import com.fongmi.android.tv.hongguo.drama.DramaDetail;
import com.fongmi.android.tv.hongguo.drama.DramaResolver;
import com.fongmi.android.tv.player.engine.ExoPlayerEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 红果横屏页（2026-10-30 重建阶段8）：真独立 Activity（清单
 * userLandscape＋singleTop＋configChanges 全套＋PiP 声明）。播放器
 * 走 DramaPlayerPool 迁移：进页领走竖屏页泊出的同一播放器同流续播，
 * 退出（返回键/传感器转竖）写回交接单泊回，竖屏页领回对位。
 * 横屏锁只活在本页实例内（官方跨页单例，已知差异记账）。页面构成
 * 从简：画面＋顶行（返回/剧名/集号）＋底行（播停/进度/选集）＋锁钮。
 */
public class HongguoLandActivity extends AppCompatActivity {

    private PlayerView playerView;
    private ExoPlayerEngine engine;
    private Player player;
    private final DramaResolver resolver = new DramaResolver();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "hg-land");
        t.setDaemon(true);
        return t;
    });

    private String seriesId;
    private String seriesTitle;
    private int contentType;
    private String curVid;
    private int curEp = 1;
    private DramaDetail detail;
    private String danmakuVid;
    private boolean locked;
    private boolean exited;
    private boolean scrubbing;
    private long createdAtMs;

    private View topBar;
    private View bottomBar;
    private ImageView lockBtn;
    private ImageView playBtn;
    private TextView titleView;
    private TextView epView;
    private TextView timeView;
    private SeekBar seekBar;
    private ProgressBar spin;
    private TextView failView;
    private OrientationEventListener orientationListener;

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (player != null && !scrubbing) {
                long pos = player.getCurrentPosition();
                long dur = player.getDuration();
                if (dur > 0) {
                    seekBar.setMax((int) Math.min(dur, Integer.MAX_VALUE));
                    seekBar.setProgress((int) Math.min(pos, Integer.MAX_VALUE));
                    timeView.setText(formatTime(pos) + " / " + formatTime(dur));
                }
            }
            main.postDelayed(this, 500);
        }
    };

    private final Runnable hideChrome = () -> setChrome(false);

    private static String formatTime(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createdAtMs = System.currentTimeMillis();
        HgWindowStyle.apply(this, false, false);

        DramaPlayerPool.Bag bag = DramaPlayerPool.peekBag();
        engine = DramaPlayerPool.takeEngine();
        if (bag == null || engine == null) {
            if (engine != null) {
                try {
                    engine.release();
                } catch (Throwable ignored) {
                }
                engine = null;
            }
            finish();
            return;
        }
        seriesId = bag.seriesId;
        seriesTitle = bag.seriesTitle;
        contentType = bag.contentType;
        curVid = bag.vid;
        curEp = bag.episodeIndex;
        player = engine.getPlayer();
        player.addListener(playerListener);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars());
            // 画面本身铺满整屏（沉浸式，不内缩）；只让字钮让位：
            // 顶行/底行/锁钮按系统栏真实高度挪开，播放面不受影响
            if (topBar != null) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) topBar.getLayoutParams();
                lp.topMargin = bars.top;
                topBar.setLayoutParams(lp);
            }
            if (bottomBar != null) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bottomBar.getLayoutParams();
                lp.bottomMargin = bars.bottom;
                bottomBar.setLayoutParams(lp);
            }
            if (lockBtn != null) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) lockBtn.getLayoutParams();
                lp.leftMargin = dp(10) + bars.left;
                lockBtn.setLayoutParams(lp);
            }
            return insets;
        });

        playerView = new PlayerView(this);
        playerView.setUseController(false);
        playerView.setShutterBackgroundColor(Color.BLACK);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setDanmakuEnabled(bag.danmakuOn);
        playerView.getDanmakuController().setConfig(danmakuConfig());
        playerView.setPlayer(player);
        playerView.setOnClickListener(v -> onSurfaceTap());
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));

        spin = new ProgressBar(this);
        FrameLayout.LayoutParams spinLp = new FrameLayout.LayoutParams(dp(40), dp(40));
        spinLp.gravity = Gravity.CENTER;
        root.addView(spin, spinLp);
        failView = new TextView(this);
        failView.setText("播放失败，点按重试");
        failView.setTextColor(Color.WHITE);
        failView.setTextSize(14);
        failView.setGravity(Gravity.CENTER);
        failView.setVisibility(View.GONE);
        failView.setOnClickListener(v -> {
            failView.setVisibility(View.GONE);
            switchEpisode(curEp, true);
        });
        FrameLayout.LayoutParams failLp = new FrameLayout.LayoutParams(-1, -1);
        root.addView(failView, failLp);

        // 锁钮（左侧中部）
        lockBtn = new ImageView(this);
        lockBtn.setImageResource(R.drawable.ic_drama_lock);
        lockBtn.setColorFilter(0xFFBDBDBD);
        lockBtn.setPadding(dp(10), dp(10), dp(10), dp(10));
        lockBtn.setOnClickListener(v -> {
            locked = !locked;
            lockBtn.setColorFilter(locked ? 0xFFFF5A4E : 0xFFBDBDBD);
            Toast.makeText(this, locked ? "已锁定" : "已解锁", Toast.LENGTH_SHORT).show();
            pokeChrome();
        });
        FrameLayout.LayoutParams lockLp = new FrameLayout.LayoutParams(dp(44), dp(44));
        lockLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        lockLp.leftMargin = dp(10);
        root.addView(lockBtn, lockLp);

        // 顶行
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(8), dp(16), dp(8));
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_drama_back);
        back.setColorFilter(Color.WHITE);
        back.setPadding(dp(8), dp(8), dp(8), dp(8));
        back.setOnClickListener(v -> exitLand());
        top.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));
        titleView = new TextView(this);
        titleView.setText(seriesTitle == null ? "" : seriesTitle);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(15.5f);
        titleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titleView.setMaxLines(1);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1f));
        epView = new TextView(this);
        epView.setTextColor(0xFFD8D8DC);
        epView.setTextSize(13);
        epView.setText("第" + curEp + "集");
        top.addView(epView);
        topBar = top;
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(-1, -2);
        topLp.gravity = Gravity.TOP;
        root.addView(top, topLp);

        // 底行
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(dp(12), dp(6), dp(12), dp(10));
        playBtn = new ImageView(this);
        playBtn.setImageResource(R.drawable.ic_drama_pause);
        playBtn.setColorFilter(Color.WHITE);
        playBtn.setPadding(dp(8), dp(8), dp(8), dp(8));
        playBtn.setOnClickListener(v -> {
            if (player == null) return;
            if (player.isPlaying() || player.getPlayWhenReady()) {
                player.pause();
                playBtn.setImageResource(R.drawable.ic_drama_play);
            } else {
                player.play();
                playBtn.setImageResource(R.drawable.ic_drama_pause);
            }
            pokeChrome();
        });
        bottom.addView(playBtn, new LinearLayout.LayoutParams(dp(42), dp(42)));
        seekBar = new SeekBar(this);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                scrubbing = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                scrubbing = false;
                if (player != null) player.seekTo(sb.getProgress());
            }
        });
        bottom.addView(seekBar, new LinearLayout.LayoutParams(0, -2, 1f));
        timeView = new TextView(this);
        timeView.setTextColor(0xFFD8D8DC);
        timeView.setTextSize(12);
        timeView.setText("0:00 / 0:00");
        bottom.addView(timeView);
        TextView eps = new TextView(this);
        eps.setText("选集");
        eps.setTextColor(Color.WHITE);
        eps.setTextSize(13.5f);
        eps.setPadding(dp(14), dp(8), dp(6), dp(8));
        eps.setOnClickListener(v -> showEpisodes());
        bottom.addView(eps);
        bottomBar = bottom;
        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(-1, -2);
        bottomLp.gravity = Gravity.BOTTOM;
        root.addView(bottom, bottomLp);

        setContentView(root);
        androidx.core.view.ViewCompat.requestApplyInsets(root);
        main.postDelayed(ticker, 300);
        pokeChrome();
        startDanmaku(curVid);
        fetchDetail();

        orientationListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (System.currentTimeMillis() - createdAtMs < 600) return; // 去抖（自家值）
                boolean portrait = DramaLandLogic.orientationBand(orientation) == 0;
                if (DramaLandLogic.shouldSensorExitLand(portrait, locked)) exitLand();
            }
        };
    }

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_READY) {
                spin.setVisibility(View.GONE);
            } else if (state == Player.STATE_BUFFERING) {
                spin.setVisibility(View.VISIBLE);
            } else if (state == Player.STATE_ENDED) {
                if (HgStore.isAutoNext() && detail != null) {
                    switchEpisode(curEp + 1, false);
                }
            }
        }

        @Override
        public void onRenderedFirstFrame() {
            spin.setVisibility(View.GONE);
        }

        @Override
        public void onPlayerError(@NonNull androidx.media3.common.PlaybackException error) {
            spin.setVisibility(View.GONE);
            failView.setVisibility(View.VISIBLE);
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            playBtn.setImageResource(isPlaying
                    ? R.drawable.ic_drama_pause : R.drawable.ic_drama_play);
        }
    };

    private void fetchDetail() {
        if (seriesId == null) return;
        DramaClient.fetchDetail(seriesId, new HgCallback<DramaDetail>() {
            @Override
            public void onSuccess(DramaDetail d) {
                runOnUiThread(() -> {
                    detail = d;
                    if (d != null && d.getTitle() != null && titleView != null) {
                        titleView.setText(d.getTitle());
                    }
                });
            }

            @Override
            public void onError(int code, String msg) {
            }
        });
    }

    private void showEpisodes() {
        if (detail == null) {
            Toast.makeText(this, "选集加载中", Toast.LENGTH_SHORT).show();
            fetchDetail();
            return;
        }
        HgEpisodeSheet.show(this, detail, curEp, idx -> switchEpisode(idx, false));
    }

    private void switchEpisode(int episodeIndex, boolean retry) {
        if (detail == null) return;
        DramaDetail.Episode target = null;
        for (DramaDetail.Episode e : detail.getEpisodes()) {
            if (e.getIndex() == episodeIndex) {
                target = e;
                break;
            }
        }
        if (target == null || target.getVideoId() == null) {
            if (!retry) Toast.makeText(this, "没有这一集", Toast.LENGTH_SHORT).show();
            return;
        }
        final String vid = target.getVideoId();
        curEp = episodeIndex;
        curVid = vid;
        epView.setText("第" + curEp + "集");
        spin.setVisibility(View.VISIBLE);
        failView.setVisibility(View.GONE);
        resolver.resolve(vid, contentType, new DramaResolver.Listener() {
            @Override
            public void onReady(String videoId, String localUrl, int w, int h) {
                if (!videoId.equals(curVid) || player == null) return;
                player.setMediaItem(MediaItem.fromUri(localUrl));
                player.prepare();
                player.setPlayWhenReady(true);
                startDanmaku(videoId);
            }

            @Override
            public void onFailed(String videoId, String code, String message) {
                if (!videoId.equals(curVid)) return;
                spin.setVisibility(View.GONE);
                failView.setVisibility(View.VISIBLE);
            }
        });
    }

    /**
     * 弹幕配置与竖屏页同一套档表（HgPanelLogic/HgStore）；横屏显示区域
     * 不走竖屏行数，走行高档（lineSpace）：档比折算滚动行数，缺省
     * QUARTER＝1/4 屏（官方横屏口径）。置顶/置底行数仍沿用竖屏行数键。
     */
    private androidx.media3.ui.danmaku.DanmakuConfig danmakuConfig() {
        int lines = HgStore.getDanmakuLines();
        float areaRatio = HgPanelLogic.danmakuAreaRatio(HgStore.getDanmakuLineSpaceIndex());
        androidx.media3.ui.danmaku.DanmakuConfig.Builder builder =
                androidx.media3.ui.danmaku.DanmakuConfig.DEFAULT.buildUpon()
                .setTransparency(HgPanelLogic.danmakuTransparency(HgStore.getDanmakuOpacity()))
                .setScrollSpeedFactor(HgPanelLogic.danmakuSpeedFactor(HgStore.getDanmakuSpeedIndex()))
                .setTextScale(HgPanelLogic.danmakuFontScale(HgStore.getDanmakuFontIndex()))
                .setTextSizeSp(HgPanelLogic.DM_TEXT_SIZE_SP)
                .setDurationMs(HgPanelLogic.DM_DURATION_MS_LANDSCAPE)
                .setMaxTopLines(lines)
                .setMaxBottomLines(lines);
        if (areaRatio < 0) {
            // SINGLE 档＝单行哨兵（HgPanelLogic.danmakuAreaRatio 定版）：滚动区钉 1 行
            builder.setMaxScrollLines(1);
        } else {
            // 其余档：滚动行数＝⌊可排轨道×屏高比⌋（引擎 TrackManager
            // configuredCount），maxScrollLines 置 0＝不另封顶
            builder.setScrollAreaRatio(areaRatio).setMaxScrollLines(0);
        }
        return builder.build();
    }

    // ---------- 弹幕（与竖屏页同源：HongguoDanmakuApi 落盘 XML） ----------

    private void startDanmaku(String vid) {
        danmakuVid = vid;
        if (playerView != null) playerView.getDanmakuController().clearItems();
        if (!HgStore.isDanmakuOn() || seriesId == null || vid == null) return;
        final String sid = seriesId;
        worker.execute(() -> HongguoDanmakuApi.fetchToFile(sid, vid, 0, item -> {
            if (!vid.equals(danmakuVid)) return;
            loadDanmakuFile(vid, item);
        }));
    }

    private void loadDanmakuFile(String vid, Danmaku item) {
        if (item == null || item.getUrl() == null) return;
        worker.execute(() -> {
            List<androidx.media3.ui.danmaku.Danmaku> parsed =
                    HongguoFeedActivity.parseDanmakuXml(item.getRealUrl());
            if (parsed == null) return;
            runOnUiThread(() -> {
                if (!vid.equals(danmakuVid) || playerView == null) return;
                playerView.getDanmakuController().setItems(new ArrayList<>(parsed));
            });
        });
    }

    // ---------- 显隐与退出 ----------

    private void onSurfaceTap() {
        if (locked) {
            lockBtn.setVisibility(lockBtn.getVisibility() == View.VISIBLE
                    ? View.GONE : View.VISIBLE);
            return;
        }
        boolean show = topBar.getVisibility() != View.VISIBLE;
        setChrome(show);
        if (show) pokeChrome();
    }

    private void setChrome(boolean show) {
        int vis = show ? View.VISIBLE : View.GONE;
        topBar.setVisibility(vis);
        bottomBar.setVisibility(vis);
        if (!locked) lockBtn.setVisibility(vis);
    }

    private void pokeChrome() {
        main.removeCallbacks(hideChrome);
        main.postDelayed(hideChrome, 3500);
    }

    private void exitLand() {
        if (exited) return;
        exited = true;
        parkBack();
        finish();
    }

    private void parkBack() {
        if (engine == null || player == null) return;
        DramaPlayerPool.Bag bag = new DramaPlayerPool.Bag();
        bag.seriesId = seriesId;
        bag.vid = curVid;
        bag.episodeIndex = curEp;
        bag.positionMs = player.getCurrentPosition();
        bag.playWhenReady = player.getPlayWhenReady();
        bag.danmakuOn = HgStore.isDanmakuOn();
        bag.speed = player.getPlaybackParameters().speed;
        bag.seriesTitle = seriesTitle;
        bag.contentType = contentType;
        player.removeListener(playerListener);
        if (playerView != null) playerView.setPlayer(null);
        DramaPlayerPool.park(bag, engine);
        engine = null;
        player = null;
    }

    @Override
    public void finish() {
        super.finish();
        HgTransition.applyFadeExit(this);
    }

    @Override
    public void onBackPressed() {
        exitLand();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (orientationListener != null) orientationListener.enable();
    }

    @Override
    protected void onPause() {
        if (orientationListener != null) orientationListener.disable();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(ticker);
        main.removeCallbacks(hideChrome);
        if (!exited) parkBack(); // 异常退出兜底：把播放器泊回，不许泄漏
        resolver.closeAll();
        worker.shutdownNow();
        super.onDestroy();
    }
}
