package com.fongmi.android.tv.hongguo.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;
import androidx.viewpager2.widget.ViewPager2;

import com.fongmi.android.tv.databinding.ActivityHongguoFeedBinding;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.model.HgSeriesItem;
import com.fongmi.android.tv.player.engine.ExoPlayerEngine;
import com.fongmi.android.tv.player.engine.PlaySpec;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.ui.base.BaseActivity;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * 红果刷剧播放页：点进就是全屏竖屏流，竖滑切集/切剧、滑到即播。
 *
 * <p>播放接线：全页共用一个 {@link ExoPlayerEngine}（星辰 Exo 芯，与 PlayerManager
 * 同款构建口径，但不走 PlaybackService/History 那套点播面）＋一个 PlayerView，
 * settle 后只把 PlayerView 移进当前页的表面挂载口，播放器不重建。起播链：
 * HgPrefetcher 预取模型→HgPlayResolver 解链注册→本地代理 URL→engine.start。</p>
 *
 * <p>全屏口径：竖屏剧在本页内沉浸（元素全隐＋系统栏隐，点屏唤极简控制、自动再隐）；
 * 横屏剧信箱式播＋「全屏观看」在本窗内把整页转 90° 铺满（窗内转、窗口不转——
 * 官方主界面 screenOrientation="behind"＋configChanges 自管，见四阶段回报横屏节；
 * 旧 HongguoLandActivity 单独横屏页真转窗口的路已废弃删除）。任何元素不常驻：
 * 播放中空闲 4 秒自动隐（进度细线除外，它随播放一直动）。</p>
 *
 * <p>弹幕：本阶段只做开关与状态落盘（HgStore）。弹幕数据源在红果样本回包里
 * 没实证，不编内容；树里另有 HongguoDanmakuApi（海阔规则线共建的壳子抓取器，
 * 挂在点播播放链上），原生流的弹幕渲染接线不在本阶段范围。</p>
 */
public class HongguoFeedActivity extends BaseActivity implements HgFeedAdapter.Host {

    private static final String EXTRA_MODE = "hg_mode";
    private static final String EXTRA_SERIES_ID = "hg_series_id";
    private static final String EXTRA_TITLE = "hg_title";
    private static final String EXTRA_COVER = "hg_cover";
    private static final int MODE_FEED = 0;
    private static final int MODE_SERIES = 1;
    private static final long HIDE_DELAY_MS = 4000;
    private static final long FS_HIDE_DELAY_MS = 3000;

    public static void start(Context context) {
        Intent intent = new Intent(context, HongguoFeedActivity.class);
        intent.putExtra(EXTRA_MODE, MODE_FEED);
        context.startActivity(intent);
    }

    /** 从搜索/详情带一部剧进来：以该剧为流首展开续播。 */
    public static void startSeries(Context context, String seriesId, String title, String cover) {
        Intent intent = new Intent(context, HongguoFeedActivity.class);
        intent.putExtra(EXTRA_MODE, MODE_SERIES);
        intent.putExtra(EXTRA_SERIES_ID, seriesId);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_COVER, cover);
        context.startActivity(intent);
    }

    private ActivityHongguoFeedBinding binding;
    private HgFeedSession session;
    private HgFeedAdapter adapter;
    private PlayerView playerView;
    private ExoPlayerEngine engine;
    private Player player;
    private HgFeedSources.Tab tab = HgFeedSources.Tab.RECOMMEND;
    private int currentPos = -1;
    private String playingVideoId;
    private boolean chromeHidden;
    /** 红果自家底栏当前面：false=首页（刷剧流本体） true=我的（壳内「我的」面）。 */
    private boolean mineShowing;
    private boolean drawerOpen;
    private boolean resumeAfterMine;
    /** 横屏窗内转模式（整页转 90°，窗口不转）；进入时的页下标，滑走即退出。 */
    private boolean landMode;
    private int landPos = -1;
    private boolean seeking;
    private long lastSaveMs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable hideChromeRunnable = () -> setChromeHidden(true);
    private final Runnable hideFsRunnable = () -> binding.hgFsBar.setVisibility(View.GONE);
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            tickProgress();
            ui.postDelayed(this, 400);
        }
    };

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onRenderedFirstFrame() {
            HgFeedAdapter.Holder h = currentHolder();
            if (h != null) h.binding.hgCover.setVisibility(View.GONE);
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_ENDED && HgStore.isAutoNext()) advanceNext();
            if (state == Player.STATE_READY) updateFsBar();
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            HgFeedAdapter.Holder h = currentHolder();
            if (h != null && !isPlaying && player != null
                    && player.getPlaybackState() == Player.STATE_READY) {
                h.binding.hgPauseIcon.setVisibility(View.VISIBLE);
            } else if (h != null) {
                h.binding.hgPauseIcon.setVisibility(View.GONE);
            }
            binding.hgFsPlay.setImageResource(isPlaying
                    ? com.fongmi.android.tv.R.drawable.ic_hg_pause : com.fongmi.android.tv.R.drawable.ic_hg_play);
            armAutoHide();
        }

        @Override
        public void onVideoSizeChanged(VideoSize size) {
            if (size.width <= 0 || size.height <= 0) return;
            boolean landscape = size.width > size.height;
            playerView.setResizeMode(landscape
                    ? AspectRatioFrameLayout.RESIZE_MODE_FIT : AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
            HgFeedAdapter.Holder h = currentHolder();
            if (h != null) h.binding.hgFullscreenWatch.setVisibility(landscape ? View.VISIBLE : View.GONE);
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            HgFeedAdapter.Holder h = currentHolder();
            if (h != null) {
                h.binding.hgCover.setVisibility(View.VISIBLE);
                h.binding.hgError.setVisibility(View.VISIBLE);
            }
        }
    };

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityHongguoFeedBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected boolean useDarkSystemIcons() {
        return false; // 深色播放页：系统栏图标用白色
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        session = HgFeedSession.get();
        session.remoteInteractions.setListener(new HgRemoteInteractions.Listener() {
            @Override
            public void onSynced(String kind, String seriesId, boolean state) {
                // 预约带服务端回读对齐：回读态可能与乐观值不同，回来刷新当前页预约片
                if (HgRemoteInteractions.KIND_RESERVE.equals(kind)) {
                    runOnUiThread(() -> {
                        HgFeedAdapter.Holder h = currentHolder();
                        if (h != null && currentPos >= 0 && currentPos < session.items.size()
                                && seriesId != null
                                && seriesId.equals(session.items.get(currentPos).seriesId)) {
                            adapter.bindInteractionState(h, session.items.get(currentPos));
                        }
                    });
                }
            }

            @Override
            public void onSyncFailed(String kind, String seriesId, boolean revertedTo, String message) {
                runOnUiThread(() -> onInteractionSyncFailed(kind, seriesId));
            }
        });
        com.fongmi.android.tv.server.Server.get().start(); // 本地代理兜底（App 冷启已起则空转）
        adapter = new HgFeedAdapter(session.items, session, this);
        binding.hgPager.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
        binding.hgPager.setOffscreenPageLimit(1);
        binding.hgPager.setAdapter(adapter);
        binding.hgPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                onSettle(position);
            }
        });
        playerView = new PlayerView(this);
        playerView.setUseController(false);
        playerView.setShutterBackgroundColor(0xFF000000);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
        setupTabs();
        setupShellFaces();
        int mode = getIntent().getIntExtra(EXTRA_MODE, MODE_FEED);
        if (mode == MODE_SERIES) {
            String seriesId = getIntent().getStringExtra(EXTRA_SERIES_ID);
            session.items.clear();
            adapter.notifyDataSetChanged();
            if (seriesId != null) {
                session.items.add(HgFeedItem.head(seriesId,
                        getIntent().getStringExtra(EXTRA_TITLE), null, null,
                        getIntent().getStringExtra(EXTRA_COVER), -1));
                adapter.notifyItemInserted(0);
                onSettle(0);
            }
        } else {
            // 重进续播：上次的剧置顶（详情展开后跳到上次集与位置）
            String last = HgStore.lastSeriesId();
            if (last != null && session.items.isEmpty()) {
                session.items.add(HgFeedItem.head(last, null, null, null, null, -1));
                adapter.notifyItemInserted(0);
            }
            loadTab(tab);
        }
        ui.post(ticker);
    }

    @Override
    protected void initEvent() {
        binding.hgSearch.setOnClickListener(v -> HongguoSearchActivity.start(this));
        // ☰：四阶段落成真侧口——进「我的」集合页（追更/收藏/观看历史/登录行）
        binding.hgMenu.setOnClickListener(v -> openDrawer());
        binding.hgFsPlay.setOnClickListener(v -> togglePlayPause());
        binding.hgFsSeries.setOnClickListener(v -> {
            if (currentPos >= 0) HgEpisodeSheet.show(this, session, currentPos, this::jumpTo);
        });
        binding.hgFsSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && player != null && player.getDuration() > 0) {
                    binding.hgFsTime.setText(formatTime(progress * player.getDuration() / 1000)
                            + "/" + formatTime(player.getDuration()));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                seeking = true;
                ui.removeCallbacks(hideFsRunnable);
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                seeking = false;
                if (player != null && player.getDuration() > 0) {
                    player.seekTo(bar.getProgress() * player.getDuration() / 1000);
                }
                ui.postDelayed(hideFsRunnable, FS_HIDE_DELAY_MS);
            }
        });
    }

    // ---------- 页签 ----------

    private void setupTabs() {
        View[] groups = {binding.hgTabFollow, binding.hgTabComic, binding.hgTabLive, binding.hgTabRecommend};
        HgFeedSources.Tab[] tabs = {HgFeedSources.Tab.FOLLOW, HgFeedSources.Tab.COMIC,
                HgFeedSources.Tab.LIVE, HgFeedSources.Tab.RECOMMEND};
        for (int i = 0; i < groups.length; i++) {
            HgFeedSources.Tab t = tabs[i];
            groups[i].setOnClickListener(v -> {
                if (t != tab) switchTab(t);
            });
        }
        refreshTabStyle();
    }

    private void refreshTabStyle() {
        View[] groups = {binding.hgTabFollow, binding.hgTabComic, binding.hgTabLive, binding.hgTabRecommend};
        HgFeedSources.Tab[] tabs = {HgFeedSources.Tab.FOLLOW, HgFeedSources.Tab.COMIC,
                HgFeedSources.Tab.LIVE, HgFeedSources.Tab.RECOMMEND};
        for (int i = 0; i < groups.length; i++) {
            boolean selected = tabs[i] == tab;
            ViewGroup g = (ViewGroup) groups[i];
            TextView label = (TextView) g.getChildAt(0);
            View line = g.getChildAt(1);
            label.setTextColor(selected ? 0xFFFFFFFF : 0x99FFFFFF);
            label.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            line.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private void switchTab(HgFeedSources.Tab target) {
        saveProgress();
        stopPlayback();
        tab = target;
        refreshTabStyle();
        loadTab(target);
    }

    private void loadTab(HgFeedSources.Tab target) {
        currentPos = -1;
        playingVideoId = null;
        binding.hgState.setVisibility(View.VISIBLE);
        binding.hgState.setText("加载中…");
        session.items.clear();
        adapter.notifyDataSetChanged();
        HgFeedSources.Source source = HgFeedSources.forTab(session.client, target);
        source.load(new HgCallback<List<HgSeriesItem>>() {
            @Override
            public void onSuccess(List<HgSeriesItem> result) {
                runOnUiThread(() -> {
                    if (tab != target) return;
                    String category = target == HgFeedSources.Tab.COMIC ? "漫剧"
                            : target == HgFeedSources.Tab.LIVE ? "真人剧" : null;
                    int resumeAt = 0;
                    for (int i = 0; i < result.size(); i++) {
                        HgSeriesItem s = result.get(i);
                        if (s.getSeriesId() != null) {
                            HgStore.putMeta(s.getSeriesId(), s.getTitle(), s.getCoverUrl(), s.getEpisodeCount());
                        }
                        HgFeedItem head = HgFeedItem.head(s.getSeriesId(), s.getTitle(), category,
                                s.getDesc(), s.getCoverUrl(), s.getEpisodeCount());
                        if (s.hasAppointment()) {
                            // 未上线剧：亮预约面，服务端预约态同步进镜像
                            head.reservable = true;
                            head.dueStartText = s.getDueStartText();
                            if (s.getSeriesId() != null) {
                                HgStore.setReserved(s.getSeriesId(), s.isAppointmentSubscribed());
                            }
                        }
                        session.items.add(head);
                        // 续播剧若在流里，优先落它
                        if (s.getSeriesId() != null && s.getSeriesId().equals(HgStore.lastSeriesId())) resumeAt = i;
                    }
                    adapter.notifyDataSetChanged();
                    if (session.items.isEmpty()) {
                        binding.hgState.setVisibility(View.VISIBLE);
                        binding.hgState.setText(source.emptyHint());
                    } else {
                        binding.hgState.setVisibility(View.GONE);
                        binding.hgPager.setCurrentItem(resumeAt, false);
                        onSettle(resumeAt);
                    }
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                runOnUiThread(() -> {
                    if (tab != target) return;
                    binding.hgState.setVisibility(View.VISIBLE);
                    binding.hgState.setText("加载失败，稍后再试");
                });
            }
        });
    }

    // ---------- settle 与起播 ----------

    private void onSettle(int position) {
        if (position < 0 || position >= session.items.size()) return;
        if (landMode && position != landPos) exitLandMode(); // 滑离横屏页：窗内转自动退出
        if (position != currentPos) saveProgress();
        currentPos = position;
        adapter.setCurrentPos(position);
        setChromeHidden(false);
        HgFeedItem item = session.items.get(position);
        HgFeedAdapter.Holder holder = currentHolder();
        if (holder != null) attachSurface(holder);
        if (item.seriesHead) {
            expandAndContinue(position);
            return;
        }
        startCurrentPlayback();
        afterSettleSideWork(position);
    }

    private void expandAndContinue(int position) {
        int sizeBefore = session.items.size();
        session.expandHead(position, at -> {
            if (at < 0) return;
            adapter.notifyItemChanged(at);
            int inserted = session.items.size() - sizeBefore;
            if (inserted > 0) adapter.notifyItemRangeInserted(at + 1, inserted);
            if (currentPos == position && at == position) {
                HgFeedItem now = session.items.get(position);
                if (!now.seriesHead) {
                    // 续播定位：有断点集时跳过去（只跳一次，靠 expandTried 防重复）
                    HgStore.Progress p = HgStore.progressOf(now.seriesId);
                    if (p != null && p.episodeIndex > 1) {
                        int target = findEpisodePosition(now.seriesId, p.episodeIndex);
                        if (target > position) {
                            binding.hgPager.setCurrentItem(target, false);
                            onSettle(target);
                            return;
                        }
                    }
                    HgFeedAdapter.Holder h = currentHolder();
                    if (h != null) {
                        attachSurface(h);
                        adapter.notifyItemChanged(position);
                    }
                    startCurrentPlayback();
                    afterSettleSideWork(position);
                }
            }
        });
    }

    private int findEpisodePosition(String seriesId, int episodeIndex) {
        for (int i = 0; i < session.items.size(); i++) {
            HgFeedItem it = session.items.get(i);
            if (seriesId.equals(it.seriesId) && it.episodeIndex == episodeIndex) return i;
        }
        return -1;
    }

    private void startCurrentPlayback() {
        if (currentPos < 0 || currentPos >= session.items.size()) return;
        HgFeedItem item = session.items.get(currentPos);
        if (item.videoId == null) return;
        if (item.videoId.equals(playingVideoId) && player != null) return;
        final String videoId = item.videoId;
        final int posAtRequest = currentPos;
        session.resolver.resolve(videoId, new HgPlayResolver.Listener() {
            @Override
            public void onReady(String vid, String localUrl, int w, int h) {
                if (!vid.equals(currentVideoId())) return; // 已滑走，迟到的解链只入缓存
                if (w > 0 && h > 0 && (item.videoWidth != w || item.videoHeight != h)) {
                    session.items.set(posAtRequest, item.withSize(w, h));
                    adapter.notifyItemChanged(posAtRequest);
                }
                play(videoId, localUrl, item);
            }

            @Override
            public void onFailed(String vid, String message) {
                if (!vid.equals(currentVideoId())) return;
                HgFeedAdapter.Holder holder = currentHolder();
                if (holder != null) holder.binding.hgError.setVisibility(View.VISIBLE);
            }
        });
    }

    private String currentVideoId() {
        if (currentPos < 0 || currentPos >= session.items.size()) return null;
        return session.items.get(currentPos).videoId;
    }

    private void play(String videoId, String localUrl, HgFeedItem item) {
        ensureEngine();
        if (engine == null || player == null) return;
        HgStore.Progress p = HgStore.progressOf(item.seriesId);
        long startAt = p != null && p.episodeIndex == item.episodeIndex ? p.positionMs : 0;
        MediaMetadata metadata = new MediaMetadata.Builder()
                .setTitle(item.title == null ? "" : item.title).build();
        PlaySpec spec = PlaySpec.from("hongguo:" + videoId, localUrl, new HashMap<>(), metadata);
        playingVideoId = videoId;
        if (startAt > 0) engine.start(spec, startAt, true);
        else engine.start(spec, true);
        armAutoHide();
    }

    /** settle 后的预备工作：同剧预取后 2 集数据、预热后 1–2 条流、回收滑远的流、预展开下一剧头。 */
    private void afterSettleSideWork(int position) {
        HgFeedItem item = session.items.get(position);
        if (item.seriesId != null) {
            session.prefetcher.prefetchAround(session.seriesVideoIds(item.seriesId),
                    session.indexWithinSeries(item), null);
        }
        for (int i = position + 1; i <= Math.min(position + 2, session.items.size() - 1); i++) {
            HgFeedItem next = session.items.get(i);
            if (next.videoId != null) session.resolver.warm(next.videoId);
            else if (next.seriesHead && i == position + 1) {
                int sizeBefore = session.items.size();
                int at = i;
                session.expandHead(at, changedAt -> {
                    if (changedAt < 0) return;
                    adapter.notifyItemChanged(changedAt);
                    int inserted = session.items.size() - sizeBefore;
                    if (inserted > 0) adapter.notifyItemRangeInserted(changedAt + 1, inserted);
                    HgFeedItem first = session.items.get(changedAt);
                    if (first.videoId != null) session.resolver.warm(first.videoId);
                });
            }
        }
        for (int i = 0; i < session.items.size(); i++) {
            if (Math.abs(i - position) > 3) {
                HgFeedItem far = session.items.get(i);
                if (far.videoId != null && !far.videoId.equals(playingVideoId)) session.resolver.close(far.videoId);
            }
        }
    }

    private void ensureEngine() {
        if (engine != null) return;
        try {
            int decode = PlayerSetting.isVideoPrefer(PlayerSetting.EXO) ? PlayerEngine.HARD : PlayerEngine.SOFT;
            engine = new ExoPlayerEngine(decode, playerListener);
            player = engine.getPlayer();
            playerView.setPlayer(player);
        } catch (Throwable e) {
            engine = null;
            player = null;
        }
    }

    private void stopPlayback() {
        if (engine != null) {
            try {
                engine.stop();
            } catch (Throwable ignored) {
            }
        }
        playingVideoId = null;
    }

    private void advanceNext() {
        saveProgress();
        if (currentPos + 1 < session.items.size()) {
            binding.hgPager.setCurrentItem(currentPos + 1, true);
        }
    }

    private void jumpTo(int position) {
        if (position < 0 || position >= session.items.size()) return;
        binding.hgPager.setCurrentItem(position, false);
        onSettle(position);
    }

    // ---------- 表面与进度 ----------

    private HgFeedAdapter.Holder currentHolder() {
        if (currentPos < 0) return null;
        RecyclerView rv = (RecyclerView) binding.hgPager.getChildAt(0);
        RecyclerView.ViewHolder vh = rv == null ? null : rv.findViewHolderForAdapterPosition(currentPos);
        return vh instanceof HgFeedAdapter.Holder ? (HgFeedAdapter.Holder) vh : null;
    }

    private void attachSurface(HgFeedAdapter.Holder holder) {
        ViewGroup host = holder.binding.hgSurfaceHost;
        if (playerView.getParent() != host) {
            if (playerView.getParent() instanceof ViewGroup) {
                ((ViewGroup) playerView.getParent()).removeView(playerView);
            }
            host.addView(playerView, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        if (currentPos >= 0 && currentPos < session.items.size()) {
            playerView.setResizeMode(session.items.get(currentPos).isLandscape()
                    ? AspectRatioFrameLayout.RESIZE_MODE_FIT : AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
        }
    }

    private void tickProgress() {
        if (player == null || currentPos < 0) return;
        long duration = player.getDuration();
        long position = player.getCurrentPosition();
        HgFeedAdapter.Holder h = currentHolder();
        if (h != null && duration > 0) {
            h.binding.hgProgressFill.setScaleX(Math.max(0f, Math.min(1f, (float) position / duration)));
        }
        if (binding.hgFsBar.getVisibility() == View.VISIBLE && !seeking && duration > 0) {
            binding.hgFsSeek.setProgress((int) (position * 1000 / duration));
            binding.hgFsTime.setText(formatTime(position) + "/" + formatTime(duration));
        }
        long now = System.currentTimeMillis();
        if (now - lastSaveMs > 4000 && player.isPlaying()) {
            lastSaveMs = now;
            saveProgress();
        }
    }

    private void saveProgress() {
        if (player == null || currentPos < 0 || currentPos >= session.items.size()) return;
        HgFeedItem item = session.items.get(currentPos);
        if (item.seriesId == null || item.episodeIndex <= 0) return;
        long pos = player.getCurrentPosition();
        if (pos > 0) HgStore.saveProgress(item.seriesId, item.episodeIndex, pos);
    }

    private static String formatTime(long ms) {
        long sec = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%d:%02d", sec / 60, sec % 60);
    }

    // ---------- 显隐（防烧屏：播放中自动全隐） ----------

    private void setChromeHidden(boolean hidden) {
        chromeHidden = hidden;
        binding.hgTopBar.setVisibility(hidden ? View.GONE : View.VISIBLE);
        HgFeedAdapter.Holder h = currentHolder();
        if (h != null) {
            int v = hidden ? View.GONE : View.VISIBLE;
            h.binding.hgRightRail.setVisibility(v);
            h.binding.hgInfo.setVisibility(v);
            h.binding.hgSeriesBar.setVisibility(v);
            h.binding.hgDanmaku.setVisibility(v);
            // 全屏观看钮非常驻件：隐藏时一并收；显示时只给横屏剧（与 bind 口径一致）
            if (hidden) h.binding.hgFullscreenWatch.setVisibility(View.GONE);
            else if (currentPos >= 0 && currentPos < session.items.size()
                    && session.items.get(currentPos).isLandscape()) {
                h.binding.hgFullscreenWatch.setVisibility(View.VISIBLE);
            }
        }
        boolean portrait = currentPos < 0 || currentPos >= session.items.size()
                || !session.items.get(currentPos).isLandscape();
        if (hidden && portrait) {
            new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView())
                    .hide(WindowInsetsCompat.Type.systemBars());
        } else if (!hidden) {
            new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView())
                    .show(WindowInsetsCompat.Type.systemBars());
        }
        if (hidden) binding.hgFsBar.setVisibility(View.GONE);
    }

    private void armAutoHide() {
        ui.removeCallbacks(hideChromeRunnable);
        if (player != null && player.isPlaying()) ui.postDelayed(hideChromeRunnable, HIDE_DELAY_MS);
    }

    private void updateFsBar() {
        if (currentPos < 0 || currentPos >= session.items.size()) return;
        HgFeedItem item = session.items.get(currentPos);
        String label = item.title == null ? "" : item.title;
        if (item.episodeIndex > 0) label = label + " 第" + item.episodeIndex + "集";
        binding.hgFsSeries.setText(label);
    }

    private void togglePlayPause() {
        if (player == null) return;
        if (player.isPlaying()) player.pause();
        else {
            player.play();
            armAutoHide();
        }
    }

    // ---------- HgFeedAdapter.Host ----------

    @Override
    public void onHolderBound(HgFeedAdapter.Holder holder, int position) {
        if (position == currentPos) attachSurface(holder);
    }

    @Override
    public void onTap(int position) {
        if (position != currentPos) return;
        if (binding.hgFsBar.getVisibility() == View.VISIBLE) {
            binding.hgFsBar.setVisibility(View.GONE);
            ui.removeCallbacks(hideFsRunnable);
            return;
        }
        if (chromeHidden) {
            boolean portrait = !session.items.get(currentPos).isLandscape();
            if (portrait || landMode) {
                // 全屏沉浸点屏：只唤极简控制，自动再隐
                updateFsBar();
                binding.hgFsBar.setVisibility(View.VISIBLE);
                ui.removeCallbacks(hideFsRunnable);
                ui.postDelayed(hideFsRunnable, FS_HIDE_DELAY_MS);
            } else {
                setChromeHidden(false);
                armAutoHide();
            }
            return;
        }
        togglePlayPause();
        if (player != null && player.isPlaying()) armAutoHide();
    }

    @Override
    public void onAction(int position, HgFeedAdapter.Action action) {
        if (position < 0 || position >= session.items.size()) return;
        HgFeedItem item = session.items.get(position);
        HgFeedAdapter.Holder h = currentHolder();
        switch (action) {
            case LIKE, FAV, FOLLOW, RESERVE -> {
                // 四阶段：未登录先拉壳子统一登录，不落状态、不假成功
                if (session.interactions.requiresLogin()) {
                    promptHongguoLogin();
                    return;
                }
                switch (action) {
                    case LIKE -> session.interactions.toggleLike(item);
                    case FAV -> session.interactions.toggleFavorite(item.seriesId);
                    case FOLLOW -> session.interactions.toggleFollow(item.seriesId);
                    case RESERVE -> {
                        boolean now = session.interactions.toggleReserve(item.seriesId);
                        android.widget.Toast.makeText(this,
                                now ? "预约成功，上线后会通知你" : "预约已取消",
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                    default -> {
                    }
                }
                if (h != null && position == currentPos) adapter.bindInteractionState(h, item);
            }
            case COMMENT -> HgCommentsPanel.show(this, item, total -> {
                if (item.seriesId != null) session.commentCounts.put(item.seriesId, total);
                HgFeedAdapter.Holder ch = currentHolder();
                if (ch != null && position == currentPos) adapter.bindInteractionState(ch, item);
            });
            case SHARE -> share(item);
        }
        armAutoHide();
    }

    /** 未登录点互动：拉壳子统一登录托管（红果提供方）；投递不出去时如实提示。 */
    private void promptHongguoLogin() {
        boolean delivered = com.fongmi.android.tv.login.LoginManager.loginHongguoDelivered();
        if (!delivered) {
            android.widget.Toast.makeText(this, "登录界面暂时打不开，稍后再试", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** 远程同步结果：失败已在互动层回滚本地镜像，这里只刷新当前页并如实提示。 */
    private void onInteractionSyncFailed(String kind, String seriesId) {
        HgFeedAdapter.Holder h = currentHolder();
        if (h != null && currentPos >= 0 && currentPos < session.items.size()
                && seriesId != null && seriesId.equals(session.items.get(currentPos).seriesId)) {
            adapter.bindInteractionState(h, session.items.get(currentPos));
        }
        String label = HgRemoteInteractions.KIND_LIKE.equals(kind) ? "点赞"
                : HgRemoteInteractions.KIND_FAV.equals(kind) ? "收藏"
                : HgRemoteInteractions.KIND_RESERVE.equals(kind) ? "预约" : "追更";
        android.widget.Toast.makeText(this, label + "没成功，已恢复原来的状态",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private void share(HgFeedItem item) {
        String text = (item.title == null ? "红果短剧" : item.title)
                + (item.episodeIndex > 0 ? " 第" + item.episodeIndex + "集" : "");
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(intent, "分享"));
    }

    @Override
    public void onSeriesBar(int position) {
        HgEpisodeSheet.show(this, session, position, this::jumpTo);
    }

    @Override
    public void onDanmakuToggle(int position) {
        HgStore.setDanmakuOn(!HgStore.isDanmakuOn());
        HgFeedAdapter.Holder h = currentHolder();
        if (h != null) adapter.bindDanmakuState(h);
        // 开关只落状态：弹幕内容本阶段不渲染（数据源未实证，见类头注）
    }

    @Override
    public void onFullscreenWatch(int position) {
        if (position != currentPos) return;
        saveProgress();
        // 横屏全屏＝窗内转（不跳页、不转窗口）：再点一次退出
        if (landMode) exitLandMode();
        else enterLandMode(position);
    }

    /**
     * 进横屏窗内转：根视图宽高互换＋绕中心转 90°＋平移回窗口中心，视频层与
     * 控制层（顶栏/右列/本剧条/全屏控制条）整体转过去铺满。窗口本身保持
     * behind＋竖屏锁定、永不旋转，故进出没有任何窗口旋转残影。
     * 旋转方向取 -90°（顶边朝左）；方向手感待真机定。
     */
    private void enterLandMode(int position) {
        if (landMode) return;
        android.view.View root = binding.getRoot();
        int w = root.getWidth();
        int h = root.getHeight();
        if (w <= 0 || h <= 0) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            w = dm.widthPixels;
            h = dm.heightPixels;
        }
        if (w >= h) return; // 已是横向尺寸（异常），不转
        landMode = true;
        landPos = position;
        ViewGroup.LayoutParams lp = root.getLayoutParams();
        lp.width = h;
        lp.height = w;
        root.setLayoutParams(lp);
        root.setPivotX(h / 2f);
        root.setPivotY(w / 2f);
        root.setTranslationX((w - h) / 2f);
        root.setTranslationY((h - w) / 2f);
        root.setRotation(-90f);
        if (playerView != null) playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        // 全屏全隐藏（他定案）：底栏随界面一起藏，pager 底距一并收掉铺满
        binding.hgBottomBar.setVisibility(View.GONE);
        setPagerBottomMargin(0);
        setChromeHidden(false);
        armAutoHide();
    }

    /** 出横屏窗内转：全部变换复原，播放器与页表原样不动（同窗同引擎，无交接）。 */
    private void exitLandMode() {
        if (!landMode) return;
        landMode = false;
        landPos = -1;
        android.view.View root = binding.getRoot();
        root.setRotation(0f);
        root.setTranslationX(0f);
        root.setTranslationY(0f);
        ViewGroup.LayoutParams lp = root.getLayoutParams();
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        root.setLayoutParams(lp);
        if (playerView != null && currentPos >= 0 && currentPos < session.items.size()) {
            playerView.setResizeMode(session.items.get(currentPos).isLandscape()
                    ? AspectRatioFrameLayout.RESIZE_MODE_FIT : AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
        }
        binding.hgBottomBar.setVisibility(View.VISIBLE);
        setPagerBottomMargin(56);
        setChromeHidden(false);
        armAutoHide();
    }

    @Override
    public void onRetry(int position) {
        if (position != currentPos) return;
        HgFeedItem item = session.items.get(position);
        if (item.videoId != null) session.resolver.close(item.videoId);
        playingVideoId = null;
        HgFeedAdapter.Holder h = currentHolder();
        if (h != null) {
            h.binding.hgError.setVisibility(View.GONE);
            h.binding.hgCover.setVisibility(View.VISIBLE);
        }
        startCurrentPlayback();
    }

    // ---------- 生命周期 ----------

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onPause() {
        saveProgress();
        if (player != null) player.pause();
        super.onPause();
    }

    /** 窗口不转、config 自管（Manifest 已声明全套 configChanges）：配置变化不重建页面，窗内转由 landMode 处理。 */
    @Override
    public void onConfigurationChanged(@androidx.annotation.NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    @Override
    protected void onBackInvoked() {
        if (drawerOpen) {
            closeDrawer();
            return;
        }
        if (mineShowing) {
            showFeedFace();
            return;
        }
        if (landMode) {
            exitLandMode();
            return;
        }
        if (binding.hgFsBar.getVisibility() == View.VISIBLE) {
            binding.hgFsBar.setVisibility(View.GONE);
            return;
        }
        if (chromeHidden) {
            setChromeHidden(false);
            armAutoHide();
            return;
        }
        super.onBackInvoked();
    }


    // ---------- 红果自家底栏两格＋「我的」面＋三杠小抽屉 ----------
    // 定案口径（他亲点）：底栏就「首页/我的」两格（剧场不要、找剧走搜索页）；
    // 两格是同一壳里的两个面、互切不新开 Activity；「我的」面构成＝登录区＋
    // 页签排＋海报格子（HgMineView）；三杠改侧边小抽屉（半幅、尺寸随内容），
    // 只放真能用的开关，第一项钉死「启动直达短剧」。

    private void setupShellFaces() {
        binding.hgMine.getRoot().setOnOpenSeriesListener((seriesId, title, cover, episodeCount) ->
                HongguoFeedActivity.startSeries(this, seriesId, title, cover));
        binding.hgTabHome.setOnClickListener(v -> showFeedFace());
        binding.hgTabMine.setOnClickListener(v -> showMineFace());
        binding.hgDrawerScrim.setOnClickListener(v -> closeDrawer());
        android.widget.Switch direct = binding.hgDrawer.hgDrawerDirect;
        direct.setChecked(HgStore.isLaunchDirect());
        direct.setOnCheckedChangeListener((b, on) -> HgStore.setLaunchDirect(on));
        android.widget.Switch danmaku = binding.hgDrawer.hgDrawerDanmaku;
        danmaku.setChecked(HgStore.isDanmakuOn());
        danmaku.setOnCheckedChangeListener((b, on) -> {
            HgStore.setDanmakuOn(on);
            HgFeedAdapter.Holder h = currentHolder();
            if (h != null) adapter.bindDanmakuState(h);
        });
        android.widget.Switch autoNext = binding.hgDrawer.hgDrawerAutonext;
        autoNext.setChecked(HgStore.isAutoNext());
        autoNext.setOnCheckedChangeListener((b, on) -> HgStore.setAutoNext(on));
        binding.hgDrawer.hgDrawerClear.setOnClickListener(v -> {
                    session.cache.clear();
                    android.widget.Toast.makeText(this, "已清红果缓存",
                            android.widget.Toast.LENGTH_SHORT).show();
                });
        setPagerBottomMargin(56);
        updateBottomTabs();
        applyStatusBarInsets();
    }

    /** 顶栏/抽屉/「我的」面的顶部留距按状态栏真实高度走 insets，不写死数值（他亲点）。 */
    private void applyStatusBarInsets() {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(getWindow().getDecorView(), (v, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) binding.hgTopBar.getLayoutParams();
            if (lp.topMargin != top) {
                lp.topMargin = top;
                binding.hgTopBar.setLayoutParams(lp);
            }
            if (binding.hgMine.getRoot().getPaddingTop() != top) {
                binding.hgMine.getRoot().setPadding(binding.hgMine.getRoot().getPaddingLeft(), top,
                        binding.hgMine.getRoot().getPaddingRight(),
                        binding.hgMine.getRoot().getPaddingBottom());
            }
            ViewGroup.MarginLayoutParams dlp =
                    (ViewGroup.MarginLayoutParams) binding.hgDrawer.getRoot().getLayoutParams();
            if (dlp.topMargin != top) {
                dlp.topMargin = top;
                binding.hgDrawer.getRoot().setLayoutParams(dlp);
            }
            return insets;
        });
    }

    private void setPagerBottomMargin(int dp) {
        ViewGroup.MarginLayoutParams lp =
                (ViewGroup.MarginLayoutParams) binding.hgPager.getLayoutParams();
        int px = Math.round(dp * getResources().getDisplayMetrics().density);
        if (lp.bottomMargin != px) {
            lp.bottomMargin = px;
            binding.hgPager.setLayoutParams(lp);
        }
    }

    private void showMineFace() {
        if (mineShowing) return;
        mineShowing = true;
        closeDrawer();
        binding.hgMine.getRoot().setVisibility(View.VISIBLE);
        binding.hgMine.getRoot().refresh();
        resumeAfterMine = player != null && player.isPlaying();
        if (resumeAfterMine) player.pause();
        updateBottomTabs();
    }

    private void showFeedFace() {
        if (!mineShowing) return;
        mineShowing = false;
        binding.hgMine.getRoot().setVisibility(View.GONE);
        updateBottomTabs();
        if (resumeAfterMine && player != null) player.play();
        resumeAfterMine = false;
    }

    private void updateBottomTabs() {
        int on = 0xFF111111;
        int off = 0xFF999999;
        binding.hgTabHomeText.setTextColor(mineShowing ? off : on);
        binding.hgTabMineText.setTextColor(mineShowing ? on : off);
        binding.hgTabHomeIcon.setColorFilter(mineShowing ? off : on);
        binding.hgTabMineIcon.setColorFilter(mineShowing ? on : off);
    }

    private void openDrawer() {
        if (drawerOpen) return;
        drawerOpen = true;
        binding.hgDrawerRoot.setVisibility(View.VISIBLE);
        binding.hgDrawer.getRoot().post(() -> {
            binding.hgDrawer.getRoot().setTranslationX(-binding.hgDrawer.getRoot().getWidth());
            binding.hgDrawer.getRoot().animate().translationX(0f).setDuration(200).start();
        });
    }

    private void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        binding.hgDrawer.getRoot().animate()
                .translationX(-binding.hgDrawer.getRoot().getWidth()).setDuration(180)
                .withEndAction(() -> binding.hgDrawerRoot.setVisibility(View.GONE)).start();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(ticker);
        ui.removeCallbacks(hideChromeRunnable);
        ui.removeCallbacks(hideFsRunnable);
        saveProgress();
        if (playerView != null) playerView.setPlayer(null);
        if (engine != null) {
            try {
                engine.release();
            } catch (Throwable ignored) {
            }
            engine = null;
            player = null;
        }
        binding.hgMine.getRoot().cancelPending();
        session.resolver.closeAll();
        super.onDestroy();
    }
}
