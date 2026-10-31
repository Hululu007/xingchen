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
import com.fongmi.android.tv.hongguo.drama.DramaCache;
import com.fongmi.android.tv.hongguo.drama.DramaClient;
import com.fongmi.android.tv.hongguo.drama.DramaDetail;
import com.fongmi.android.tv.hongguo.drama.DramaResolver;
import com.fongmi.android.tv.hongguo.drama.DramaVideoModel;
import com.fongmi.android.tv.player.engine.ExoPlayerEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 红果横屏页（2026-10-30 重建阶段8）：真独立 Activity（清单
 * userLandscape＋singleTop＋configChanges 全套＋PiP 声明）。播放器
 * 走 DramaPlayerPool 迁移：进页领走竖屏页泊出的同一播放器同流续播，
 * 退出（返回键/传感器转竖）写回交接单泊回，竖屏页领回对位。
 * 横屏锁只活在本页实例内（官方跨页单例，已知差异记账）。页面构成
 * 从简：画面＋顶行（返回/剧名/集号）＋底行（播停/进度/选集）＋锁钮
 * ＋右侧点赞钮（右列点赞位的横屏落点，与竖屏页同走 HgStore liked 集）。
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
    /** 取链窗口去重（resolveWithWindow，与刷剧页同口径）：已发窗口的起始位。 */
    private int windowStart = -1;
    private String danmakuVid;
    private boolean locked;
    private boolean exited;
    private boolean scrubbing;
    private long createdAtMs;

    private View topBar;
    private View bottomBar;
    /** 发弹幕入口「弹」标（#21 乙）。 */
    private TextView dmSend;
    /** 当前弹幕列表留存（自己发的回显并入用）。 */
    private final java.util.List<androidx.media3.ui.danmaku.Danmaku> dmItems =
            new java.util.ArrayList<>();
    private ImageView lockBtn;
    private ImageView likeBtn;
    private ImageView playBtn;
    private TextView titleView;
    private TextView epView;
    private TextView timeView;
    private TextView timeDurView;
    private FrameLayout progressWrap;
    private View progressFill;
    private View progressThumb;
    private float progressFraction;
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
                    setProgressFraction((float) pos / (float) dur);
                    timeView.setText(formatTime(pos));
                    timeDurView.setText(formatTime(dur));
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

    /** 细轨进度落位：已走宽＝轨宽×分数，白点骑在已走末端。 */
    private void setProgressFraction(float f) {
        progressFraction = Math.max(0f, Math.min(1f, f));
        applyProgress();
    }

    private void applyProgress() {
        if (progressWrap == null || progressFill == null || progressThumb == null) return;
        int w = progressWrap.getWidth();
        if (w <= 0) return;
        int fillW = (int) (w * progressFraction);
        ViewGroup.LayoutParams flp = progressFill.getLayoutParams();
        if (flp.width != fillW) {
            flp.width = fillW;
            progressFill.setLayoutParams(flp);
        }
        FrameLayout.LayoutParams tlp = (FrameLayout.LayoutParams) progressThumb.getLayoutParams();
        int left = Math.max(0, Math.min(w - dp(11), fillW - dp(5)));
        if (tlp.leftMargin != left) {
            tlp.leftMargin = left;
            progressThumb.setLayoutParams(tlp);
        }
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
            if (likeBtn != null) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) likeBtn.getLayoutParams();
                lp.rightMargin = dp(10) + bars.right;
                likeBtn.setLayoutParams(lp);
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
            lockBtn.setColorFilter(locked ? 0xFFE63A2E : 0xFFBDBDBD);
            Toast.makeText(this, locked ? "已锁定" : "已解锁", Toast.LENGTH_SHORT).show();
            pokeChrome();
        });
        FrameLayout.LayoutParams lockLp = new FrameLayout.LayoutParams(dp(44), dp(44));
        lockLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        lockLp.leftMargin = dp(10);
        root.addView(lockBtn, lockLp);

        // 点赞钮（右侧中部、与锁钮对称；横屏无右列整列，只落点赞一颗，
        // 与竖屏页共用 HgStore liked 集与激活红，显隐跟 chrome 走）
        likeBtn = new ImageView(this);
        likeBtn.setImageResource(R.drawable.ic_mine_heart);
        likeBtn.setPadding(dp(10), dp(10), dp(10), dp(10));
        likeBtn.setOnClickListener(v -> {
            if (seriesId == null) return;
            // 账号终定口径（#22）：点赞绑账号，未登录跳登录页
            if (!com.fongmi.android.tv.hongguo.api.HgAccountStore.isLoggedIn()) {
                HongguoLoginActivity.start(this);
                return;
            }
            HgStore.toggleLiked(seriesId);
            if (detail != null) {
                HgStore.putMeta(seriesId, detail.getTitle(), detail.getCoverUrl(),
                        detail.getEpisodeCount());
            }
            paintLike();
            pokeChrome();
        });
        FrameLayout.LayoutParams likeLp = new FrameLayout.LayoutParams(dp(44), dp(44));
        likeLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        likeLp.rightMargin = dp(10);
        root.addView(likeBtn, likeLp);
        paintLike();

        // 顶行
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(14), dp(8), dp(16), dp(8));
        // 返回：38dp 玻璃圆钮（与播放页 .iconbtn 同形态）
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_drama_back);
        back.setColorFilter(Color.WHITE);
        android.graphics.drawable.GradientDrawable backBg =
                new android.graphics.drawable.GradientDrawable();
        backBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        backBg.setColor(0x52141212);
        backBg.setStroke(1, 0x2EFFFFFF);
        back.setBackground(backBg);
        back.setPadding(dp(9), dp(9), dp(9), dp(9));
        back.setOnClickListener(v -> exitLand());
        top.addView(back, new LinearLayout.LayoutParams(dp(38), dp(38)));
        titleView = new TextView(this);
        titleView.setText(seriesTitle == null ? "" : seriesTitle);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(15.5f);
        titleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titleView.setMaxLines(1);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1f);
        titleLp.leftMargin = dp(10);
        top.addView(titleView, titleLp);
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
        bottom.setPadding(dp(16), dp(6), dp(16), dp(10));
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
        // 时间签左右分置＋细轨（定稿 .p-progress）：当前在左、时长
        // 在右，11sp rgba(255,255,255,.75)；轨 3dp rgba(.25)、已走
        // rgba(.92)、11dp 白点。系统 SeekBar 粗轨大拇指整件弃用，
        // 拖动在本控件触摸里 seek（ scrubbing 语义与旧监听一致）。
        timeView = new TextView(this);
        timeView.setTextColor(0xBFFFFFFF);
        timeView.setTextSize(11);
        timeView.setText("0:00");
        LinearLayout.LayoutParams curLp = new LinearLayout.LayoutParams(-2, -2);
        curLp.leftMargin = dp(10);
        bottom.addView(timeView, curLp);
        progressWrap = new FrameLayout(this);
        View track = new View(this);
        android.graphics.drawable.GradientDrawable trackBg =
                new android.graphics.drawable.GradientDrawable();
        trackBg.setColor(0x40FFFFFF);
        trackBg.setCornerRadius(dp(2));
        track.setBackground(trackBg);
        FrameLayout.LayoutParams trackLp = new FrameLayout.LayoutParams(-1, dp(3));
        trackLp.gravity = Gravity.CENTER_VERTICAL;
        progressWrap.addView(track, trackLp);
        progressFill = new View(this);
        android.graphics.drawable.GradientDrawable fillBg =
                new android.graphics.drawable.GradientDrawable();
        fillBg.setColor(0xEBFFFFFF);
        fillBg.setCornerRadius(dp(2));
        progressFill.setBackground(fillBg);
        FrameLayout.LayoutParams fillLp = new FrameLayout.LayoutParams(0, dp(3));
        fillLp.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
        progressWrap.addView(progressFill, fillLp);
        progressThumb = new View(this);
        android.graphics.drawable.GradientDrawable thumbBg =
                new android.graphics.drawable.GradientDrawable();
        thumbBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        thumbBg.setColor(Color.WHITE);
        progressThumb.setBackground(thumbBg);
        FrameLayout.LayoutParams thumbLp = new FrameLayout.LayoutParams(dp(11), dp(11));
        thumbLp.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
        progressWrap.addView(progressThumb, thumbLp);
        progressWrap.setOnTouchListener((v, ev) -> {
            if (player == null) return true;
            long dur = player.getDuration();
            if (dur <= 0) return true;
            float f = Math.max(0f, Math.min(1f,
                    ev.getX() / Math.max(1, v.getWidth())));
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    scrubbing = true;
                    setProgressFraction(f);
                    timeView.setText(formatTime((long) (f * dur)));
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    setProgressFraction(f);
                    timeView.setText(formatTime((long) (f * dur)));
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    scrubbing = false;
                    player.seekTo((long) (f * dur));
                    pokeChrome();
                    return true;
                default:
                    return true;
            }
        });
        progressWrap.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) ->
                applyProgress());
        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(0, dp(30), 1f);
        wrapLp.leftMargin = dp(10);
        wrapLp.rightMargin = dp(10);
        bottom.addView(progressWrap, wrapLp);
        timeDurView = new TextView(this);
        timeDurView.setTextColor(0xBFFFFFFF);
        timeDurView.setTextSize(11);
        timeDurView.setText("0:00");
        bottom.addView(timeDurView);
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

        // 发弹幕入口（#21 乙，横屏同加）：左下「弹」标，座位在 #17
        // 控件按画面框重排时随底行一并收进框内；显隐跟弹幕开关＋
        // chrome 同步，点击先过登录门（与竖屏同口径）。
        dmSend = new TextView(this);
        dmSend.setText("弹");
        dmSend.setTextColor(0xF2FFFFFF);
        dmSend.setTextSize(12.5f);
        android.graphics.drawable.GradientDrawable dmBg =
                new android.graphics.drawable.GradientDrawable();
        dmBg.setColor(0x59000000);
        dmBg.setCornerRadius(dp(13));
        dmSend.setBackground(dmBg);
        dmSend.setPadding(dp(11), dp(5), dp(11), dp(5));
        dmSend.setOnClickListener(v -> onDanmakuSendTap());
        dmSend.setVisibility(HgStore.isDanmakuOn() ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams dmlp = new FrameLayout.LayoutParams(-2, -2);
        dmlp.gravity = Gravity.START | Gravity.BOTTOM;
        dmlp.leftMargin = dp(16);
        dmlp.bottomMargin = dp(64);
        root.addView(dmSend, dmlp);

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
            // 播时常亮、停时放行：与主播放器 PlaybackActivity 同口径
            // （本页此前没设过这面旗，播着也会按系统超时息屏）
            if (isPlaying) {
                getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else if (player == null
                    || player.getPlaybackState() != Player.STATE_BUFFERING) {
                getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
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
        DramaResolver.Listener listener = new DramaResolver.Listener() {
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
        };
        resolveWithWindow(vid, contentType, listener);
    }

    /**
     * 取链窗口（与刷剧页 HongguoFeedActivity.resolveWithWindow 同口径，
     * 2026-10-31）：服务端对 video_model 家族每身份只放行少数发，
     * 单集逐发会把放行发浪费在一集上。当前集未命中缓存且不在已发
     * 窗口内时，先用一发批量把「当前集起 20 集」取回落缓存再 resolve；
     * 窗内切集全走缓存、不另发网。成败都回 resolve，不堵播放。
     */
    private void resolveWithWindow(String vid, int contentType,
                                   DramaResolver.Listener listener) {
        if (detail == null) {
            resolver.resolve(vid, contentType, listener);
            return;
        }
        DramaVideoModel cached = DramaCache.get(vid);
        if (cached != null && cached.firstPlayableEntry() != null) {
            resolver.resolve(vid, contentType, listener);
            return;
        }
        List<DramaDetail.Episode> eps = detail.getEpisodes();
        int pos = -1;
        for (int i = 0; i < eps.size(); i++) {
            if (vid.equals(eps.get(i).getVideoId())) {
                pos = i;
                break;
            }
        }
        if (pos < 0 || (windowStart >= 0 && pos >= windowStart && pos < windowStart + 20)) {
            resolver.resolve(vid, contentType, listener);
            return;
        }
        windowStart = pos;
        int ct = detail.getContentType();
        List<DramaClient.ModelRequest> reqs = new ArrayList<>();
        for (int i = pos; i < Math.min(pos + 20, eps.size()); i++) {
            String ev = eps.get(i).getVideoId();
            if (ev != null) reqs.add(new DramaClient.ModelRequest(ev, ct));
        }
        if (reqs.isEmpty()) {
            resolver.resolve(vid, contentType, listener);
            return;
        }
        DramaClient.fetchModels(reqs, new HgCallback<Map<String, DramaVideoModel>>() {
            @Override
            public void onSuccess(Map<String, DramaVideoModel> r) {
                resolver.resolve(vid, contentType, listener);
            }

            @Override
            public void onError(int c, String m) {
                resolver.resolve(vid, contentType, listener);
            }
        }, false);
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
        dmItems.clear();
        if (playerView != null) playerView.getDanmakuController().clearItems();
        if (dmSend != null) {
            dmSend.setVisibility(HgStore.isDanmakuOn() ? View.VISIBLE : View.GONE);
        }
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
                dmItems.clear();
                dmItems.addAll(parsed);
                playerView.getDanmakuController().setItems(new ArrayList<>(dmItems));
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
        if (likeBtn != null) likeBtn.setVisibility(locked ? View.GONE : vis);
        if (dmSend != null) {
            dmSend.setVisibility(show && HgStore.isDanmakuOn() && !locked
                    ? View.VISIBLE : View.GONE);
        }
    }

    /** 点发弹幕入口（#21 乙＋账号终定口径，与竖屏同门）：未登录
     * 跳登录页，登录后开编辑层；发表走 DramaDanmakuSender 定版
     * 载荷，成功把自己这条并入弹幕列表回显。 */
    private void onDanmakuSendTap() {
        if (!com.fongmi.android.tv.hongguo.api.HgAccountStore.isLoggedIn()) {
            HongguoLoginActivity.start(this);
            return;
        }
        if (curVid == null || seriesId == null || player == null) return;
        final String vid = curVid;
        final String sid = seriesId;
        final long offset = Math.max(0, player.getCurrentPosition());
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("发条友善的弹幕吧");
        input.setTextColor(0xFF1B1B1F);
        input.setHintTextColor(0xFF8A8A93);
        input.setTextSize(14.5f);
        input.setSingleLine(true);
        androidx.appcompat.app.AlertDialog dialog =
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("发弹幕")
                        .setView(input)
                        .setNegativeButton("取消", null)
                        .setPositiveButton("发表", null)
                        .create();
        dialog.setOnShowListener(d -> {
            android.widget.Button ok = dialog.getButton(
                    androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
            ok.setOnClickListener(v -> {
                String text = input.getText() == null ? ""
                        : input.getText().toString().trim();
                if (text.isEmpty()) {
                    input.setError("先写点什么");
                    return;
                }
                ok.setEnabled(false);
                com.fongmi.android.tv.hongguo.drama.DramaDanmakuSender.publish(
                        sid, vid, text, offset, new HgCallback<Void>() {
                            @Override
                            public void onSuccess(Void r) {
                                runOnUiThread(() -> {
                                    dialog.dismiss();
                                    if (vid.equals(curVid) && playerView != null) {
                                        dmItems.add(new androidx.media3.ui.danmaku.Danmaku(
                                                text, offset,
                                                androidx.media3.ui.danmaku.Danmaku.TYPE_SCROLL,
                                                Color.WHITE, 0f, 0, null, 0));
                                        playerView.getDanmakuController()
                                                .setItems(new java.util.ArrayList<>(dmItems));
                                    }
                                    Toast.makeText(HongguoLandActivity.this,
                                            "已发送", Toast.LENGTH_SHORT).show();
                                });
                            }

                            @Override
                            public void onError(int c, String m) {
                                runOnUiThread(() -> {
                                    ok.setEnabled(true);
                                    Toast.makeText(HongguoLandActivity.this,
                                            "没发出去，再试试", Toast.LENGTH_SHORT).show();
                                });
                            }
                        });
            });
        });
        dialog.show();
    }

    /** 点赞态回显：已赞激活红、未赞白（与竖屏页 LIKE_ON_COLOR 同值）。 */
    private void paintLike() {
        if (likeBtn == null) return;
        boolean liked = seriesId != null && HgStore.isLiked(seriesId);
        likeBtn.setColorFilter(liked ? HongguoFeedActivity.LIKE_ON_COLOR : Color.WHITE);
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
        // #18：横屏是本页自己强制的（机主系统自动旋转关着也一样）；
        // 切后台再回来时把强制横屏重新申明一遍，不许被竖屏页带回去。
        setRequestedOrientation(
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE);
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
