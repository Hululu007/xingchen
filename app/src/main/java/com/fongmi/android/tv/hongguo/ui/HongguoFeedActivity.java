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
 * <p>两种模式（研究账 §2/§6）：MODE_FEED＝首页短片流（每条是不同剧的
 * 单条短片、上下滑跨剧、点剧条/左滑进剧、播完循环当前条、浏览不写剧
 * 断点）；MODE_SERIES＝进剧后的集流（逐集竖滑、断点记剧一级、播完按
 * 自动连播开关切下集）。播放内核一律 Exo、不走 MPV（账 §6.5）。</p>
 *
 * <p>全屏口径：横屏剧信箱式播＋「全屏观看」在本窗内把整页转 90° 铺满
 * （窗内转、窗口不转——官方主界面 screenOrientation="behind"＋configChanges
 * 自管，见四阶段回报横屏节；旧 HongguoLandActivity 单独横屏页真转窗口的路
 * 已废弃删除）。显隐口径（2026-10-28 他真机亲点后改）：刷剧面常显件
 * （顶栏页签/右列/剧条/弹幕筹/进度细线）不许自动隐藏——旧「播放中空闲
 * 4 秒自动全隐」把界面播几秒全收走、被他当场点名；自动全隐只保留在
 * 横屏全屏观看态（landMode，他定案全屏全隐藏＋防烧屏），竖屏点屏只切
 * 播放/暂停、全屏控制条只在横屏态点屏唤出、自动再隐。</p>
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
    /** 当前页签的数据源实例（续页凭证挂在它身上，loadTab 时换新）。 */
    private HgFeedSources.Source currentSource;
    /** 页签加载代次：迟到的旧回调（切页签/重拉）按代次丢弃，不串新表。 */
    private int loadSeq;
    private boolean feedLoadingMore;
    /** 本页曾被完全遮住（onStop）过：回前台时按 Open 语义重拉当前页签。 */
    private boolean stoppedOnce;
    /** 本次离台是自家页内跳转（搜索/进剧新开实例）：回来不刷新、不丢位置。 */
    private boolean suppressResumeRefresh;
    private int currentPos = -1;
    /** 本页模式：MODE_FEED 短片流 / MODE_SERIES 集流（initView 自 intent 定）。 */
    private int mode = MODE_FEED;
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
    private final Runnable hideChromeRunnable = () -> {
        // 自动全隐只在横屏全屏观看态成立（见类头显隐口径）
        if (landMode) setChromeHidden(true);
    };
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
            if (state == Player.STATE_ENDED) {
                if (mode == MODE_FEED) {
                    // 短片播完循环当前条（账 §4.2；官方行为未定 U7 的兜底口径），不自动滑走
                    if (player != null) {
                        player.seekTo(0);
                        player.play();
                    }
                } else if (HgStore.isAutoNext()) {
                    advanceNext();
                }
            }
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
                // 播放器环失败记编号 P（诊断用，定罪后撤）
                showErrorWithCode(h, "P");
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
        mode = getIntent().getIntExtra(EXTRA_MODE, MODE_FEED);
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
            // 首页短片流（账 §2）：cell 条目逐条成页，不预置「上次的剧」——
            // 断点记在剧一级，从剧条/左滑进剧时才续（账 §2.4/§6.1）
            loadTab(tab);
        }
        ui.post(ticker);
    }

    @Override
    protected void initEvent() {
        binding.hgSearch.setOnClickListener(v -> {
            suppressResumeRefresh = true;
            HongguoSearchActivity.start(this);
        });
        // ☰：四阶段落成真侧口——进「我的」集合页（追更/收藏/观看历史/登录行）
        binding.hgMenu.setOnClickListener(v -> openDrawer());
        binding.hgFsPlay.setOnClickListener(v -> togglePlayPause());
        binding.hgFsSeries.setOnClickListener(v -> {
            if (currentPos < 0) return;
            if (mode == MODE_FEED) enterSeries(currentPos);
            else HgEpisodeSheet.show(this, session, currentPos, this::jumpTo);
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
        feedLoadingMore = false;
        final int seq = ++loadSeq;
        binding.hgState.setVisibility(View.VISIBLE);
        binding.hgState.setText("加载中…");
        session.items.clear();
        adapter.notifyDataSetChanged();
        HgFeedSources.Source source = HgFeedSources.forTab(session.client, target);
        currentSource = source;
        source.load(new HgCallback<List<HgSeriesItem>>() {
            @Override
            public void onSuccess(List<HgSeriesItem> result) {
                runOnUiThread(() -> {
                    if (tab != target || seq != loadSeq) return;
                    appendSeriesItems(result, target);
                    adapter.notifyDataSetChanged();
                    if (session.items.isEmpty()) {
                        binding.hgState.setVisibility(View.VISIBLE);
                        binding.hgState.setText(source.emptyHint());
                    } else {
                        binding.hgState.setVisibility(View.GONE);
                        binding.hgPager.setCurrentItem(0, false);
                        onSettle(0);
                    }
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                runOnUiThread(() -> {
                    if (tab != target || seq != loadSeq) return;
                    binding.hgState.setVisibility(View.VISIBLE);
                    binding.hgState.setText("加载失败，稍后再试");
                });
            }
        });
    }

    /** cell 条目逐条进流（首拉与续页共用）：落 meta/预约镜像，再按账 §2.5 成短片或剧头。 */
    private void appendSeriesItems(List<HgSeriesItem> result, HgFeedSources.Tab target) {
        String category = target == HgFeedSources.Tab.COMIC ? "漫剧"
                : target == HgFeedSources.Tab.LIVE ? "真人剧" : null;
        for (int i = 0; i < result.size(); i++) {
            HgSeriesItem s = result.get(i);
            if (s.getSeriesId() != null) {
                HgStore.putMeta(s.getSeriesId(), s.getTitle(), s.getCoverUrl(), s.getEpisodeCount());
                if (s.hasAppointment()) {
                    // 未上线剧：服务端预约态同步进镜像
                    HgStore.setReserved(s.getSeriesId(), s.isAppointmentSubscribed());
                }
            }
            // 短片流（账 §2.5）：cell 带 vid 直接成短片、无 vid 成剧头待定形态
            session.items.add(HgFeedItem.fromSeries(s, category));
        }
    }

    /**
     * 滑到尾部续页（频道面）：距尾 ≤2 条时向当前源要下一页追加，流不再封顶
     * 首批条数。在飞/无更多/非刷剧面一律不发；续页失败静默（凭证留在源里，
     * 下次滑到尾部再试），不打扰当前播放。
     */
    private void maybeLoadMore() {
        if (mode != MODE_FEED || mineShowing || landMode) return;
        final HgFeedSources.Source source = currentSource;
        if (source == null || feedLoadingMore || !source.hasMore()) return;
        if (currentPos < 0 || currentPos < session.items.size() - 3) return;
        feedLoadingMore = true;
        final int seq = loadSeq;
        source.loadMore(new HgCallback<List<HgSeriesItem>>() {
            @Override
            public void onSuccess(List<HgSeriesItem> result) {
                runOnUiThread(() -> {
                    feedLoadingMore = false;
                    if (seq != loadSeq || source != currentSource) return;
                    if (result == null || result.isEmpty()) return;
                    int start = session.items.size();
                    appendSeriesItems(result, tab);
                    int added = session.items.size() - start;
                    if (added > 0) {
                        adapter.notifyItemRangeInserted(start, added);
                        maybeLoadMore();
                    }
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                runOnUiThread(() -> feedLoadingMore = false);
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
            if (mode == MODE_FEED) {
                // 短片流的剧头（账 §2.3）：只定形态成短片、不展开整剧；
                // 预约面（未上线剧）不取播，停在封面＋预约片
                if (!item.reservable) {
                    maybeLoadMore();
                    session.resolveClip(position, at -> {
                        if (at >= 0) adapter.notifyItemChanged(at);
                        if (at == position && currentPos == position) {
                            HgFeedItem now = session.items.get(position);
                            if (!now.seriesHead && now.videoId != null) {
                                HgFeedAdapter.Holder h = currentHolder();
                                if (h != null) attachSurface(h);
                                startCurrentPlayback();
                                afterSettleSideWork(position);
                            }
                        }
                    });
                }
                return;
            }
            expandAndContinue(position);
            return;
        }
        if (mode == MODE_FEED && item.clip) {
            // 短片富集：后台补作者/头像/简介（账 §3.3），不挡起播
            session.enrichClip(position, at -> {
                if (at >= 0) adapter.notifyItemChanged(at);
            });
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

    /** 点亮错误片；code 为失败出口编号（E1–E5／播放器环 P），带上屏方便定罪（诊断用，定罪后撤）。 */
    private void showErrorWithCode(HgFeedAdapter.Holder holder, String code) {
        if (holder == null) return;
        holder.binding.hgError.setText(code == null || code.isEmpty()
                ? "加载失败，点按重试"
                : "加载失败（" + code + "），点按重试");
        holder.binding.hgError.setVisibility(View.VISIBLE);
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
            public void onFailed(String vid, String code, String message) {
                if (!vid.equals(currentVideoId())) return;
                // 编号随失败回调带到落片处；vid 校验在上一行，迟到失败不串条目
                showErrorWithCode(currentHolder(), code);
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
        if (mode == MODE_FEED) {
            // 短片流的预热只管相邻短片（账 §2.3）：下一条是剧头先定形态再 warm；
            // 不展开整剧、不预取当前短片所属剧的其它集
            // 模型先行：相邻短片的 video_model 走并发预取面先落缓存，解链队列
            // （串行）轮到时直接命中，划过来基本直接起播（仍守未就绪不硬喂）
            java.util.List<String> feedAhead = new java.util.ArrayList<>();
            for (int i = position + 1; i <= Math.min(position + 2, session.items.size() - 1); i++) {
                if (session.items.get(i).videoId != null) feedAhead.add(session.items.get(i).videoId);
            }
            if (!feedAhead.isEmpty()) session.prefetcher.prefetchIds(feedAhead, null);
            for (int i = position + 1; i <= Math.min(position + 2, session.items.size() - 1); i++) {
                HgFeedItem next = session.items.get(i);
                if (next.videoId != null) session.resolver.warm(next.videoId);
                else if (next.seriesHead && i == position + 1 && !next.reservable) {
                    int at = i;
                    session.resolveClip(at, changedAt -> {
                        if (changedAt < 0) return;
                        adapter.notifyItemChanged(changedAt);
                        HgFeedItem clip = session.items.get(changedAt);
                        if (clip.videoId != null) session.resolver.warm(clip.videoId);
                    });
                }
            }
        } else {
            if (item.seriesId != null) {
                session.prefetcher.prefetchAround(session.seriesVideoIds(item.seriesId),
                        session.indexWithinSeries(item), null);
            }
            // 集流同口径：后 2 集的模型也走并发预取面先行（解链队列是串行的）
            java.util.List<String> seriesAhead = new java.util.ArrayList<>();
            for (int i = position + 1; i <= Math.min(position + 2, session.items.size() - 1); i++) {
                if (session.items.get(i).videoId != null) seriesAhead.add(session.items.get(i).videoId);
            }
            if (!seriesAhead.isEmpty()) session.prefetcher.prefetchIds(seriesAhead, null);
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
        }
        for (int i = 0; i < session.items.size(); i++) {
            if (Math.abs(i - position) > 3) {
                HgFeedItem far = session.items.get(i);
                if (far.videoId != null && !far.videoId.equals(playingVideoId)) session.resolver.close(far.videoId);
            }
        }
        maybeLoadMore();
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
            float frac = Math.max(0f, Math.min(1f, (float) position / duration));
            h.binding.hgProgressFill.setScaleX(frac);
            // 游标点（账 §3.7）：贴填充末端走，点宽 6dp 居中压在末端
            int trackW = h.binding.hgProgressFill.getWidth();
            if (trackW > 0) {
                float dot = 3f * h.binding.hgProgressDot.getResources().getDisplayMetrics().density;
                h.binding.hgProgressDot.setTranslationX(
                        Math.max(0f, Math.min(trackW - dot * 2, frac * trackW - dot)));
            }
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
        // 短片流浏览不写剧断点（账 §2.4：短片不是「在看这部剧」），断点只记集流
        if (mode == MODE_FEED) return;
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

    // ---------- 显隐（刷剧面常显；自动全隐只在横屏全屏观看态，防烧屏） ----------

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
            // 全屏观看钮非常驻件：隐藏时一并收；重显口径与适配器绑定一致
            // （真实宽高判横屏或 cell 朝向预判，旧版重显只认真实宽高、与绑定
            // 口径不一致，预判条目会把钮弄丢——审计实证）
            if (hidden) h.binding.hgFullscreenWatch.setVisibility(View.GONE);
            else if (currentPos >= 0 && currentPos < session.items.size()
                    && (session.items.get(currentPos).isLandscape()
                    || session.items.get(currentPos).landscapeHint)) {
                h.binding.hgFullscreenWatch.setVisibility(View.VISIBLE);
            }
        }
        boolean portrait = currentPos < 0 || currentPos >= session.items.size()
                || !session.items.get(currentPos).isLandscape();
        // 横屏全屏观看态（landMode，窗内转、窗口仍竖）也要连系统栏一起隐：
        // 旧条件只认条目朝向，landMode 下条目是横屏、portrait=false，系统栏
        // 一直赖着不走，与「全屏全隐藏」定案对不上
        if (hidden && (portrait || landMode)) {
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
        // 刷剧面常显件不自动隐（他真机亲点，见类头显隐口径）；
        // 自动全隐只在横屏全屏观看态（landMode）排期
        if (!landMode) return;
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
        // 短片流里点剧条＝进这部剧；集流里才是开选集（账 §4/§6.3）
        if (mode == MODE_FEED) {
            enterSeries(position);
            return;
        }
        HgEpisodeSheet.show(this, session, position, this::jumpTo);
    }

    @Override
    public void onEnterSeries(int position) {
        if (mode == MODE_FEED) enterSeries(position);
    }

    /**
     * 短片流进剧（账 §4/§5.4）：回包带专辑兄弟部时先弹系列半屏单选部，
     * 单季剧（或无专辑数据）直进该剧集流；断点在集流展开链里续。
     */
    private void enterSeries(int position) {
        if (position < 0 || position >= session.items.size()) return;
        HgFeedItem item = session.items.get(position);
        if (item.seriesId == null) return;
        suppressResumeRefresh = true;
        if (!item.siblings.isEmpty()) {
            showSeriesSheet(item, item.siblings);
            return;
        }
        // 多季系列但兄弟部不在条目里（频道面包里没 album_data_list，只有
        // is_multi_season＋专辑 id）：现拉专辑详情，≥2 季才弹系列单；
        // 拉不到/已下架/只有一季都直进，不许让用户等在半路（2026-10-28
        // 逐项重对补，原先这类剧永远弹不出系列单）
        String albumId = item.albumId;
        if (albumId == null) {
            com.fongmi.android.tv.hongguo.model.HgSeriesDetail det = session.detailOf(item.seriesId);
            if (det != null) albumId = det.getRelatedAlbumId();
        }
        java.util.List<com.fongmi.android.tv.hongguo.model.HgSeriesItem> cached =
                session.albumOf(item.seriesId);
        if (cached != null) {
            if (cached.size() >= 2) showSeriesSheet(item, cached);
            else startSeries(this, item.seriesId, item.title, item.coverUrl);
            return;
        }
        if (item.multiSeason && albumId != null && !albumId.isEmpty()) {
            final String sid = item.seriesId;
            final String title = item.title;
            final String cover = item.coverUrl;
            session.client.fetchAlbum(albumId, new HgCallback<java.util.List<com.fongmi.android.tv.hongguo.model.HgSeriesItem>>() {
                @Override
                public void onSuccess(java.util.List<com.fongmi.android.tv.hongguo.model.HgSeriesItem> seasons) {
                    session.putAlbum(sid, seasons);
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        if (seasons != null && seasons.size() >= 2) showSeriesSheet(item, seasons);
                        else startSeries(HongguoFeedActivity.this, sid, title, cover);
                    });
                }

                @Override
                public void onError(int httpCode, String message) {
                    session.putAlbum(sid, java.util.Collections.emptyList());
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed()) {
                            startSeries(HongguoFeedActivity.this, sid, title, cover);
                        }
                    });
                }
            });
            return;
        }
        startSeries(this, item.seriesId, item.title, item.coverUrl);
    }

    private void showSeriesSheet(HgFeedItem item, java.util.List<com.fongmi.android.tv.hongguo.model.HgSeriesItem> siblings) {
        HgSeriesSheet.show(this, siblings, item, session.interactions,
                picked -> startSeries(this, picked.getSeriesId(), picked.getTitle(),
                        picked.getCoverUrl()));
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
        if (item.seriesHead) {
            // 剧头没定形态（详情没拉到）：解开记账重新走落定链拉详情，
            // 不去解一个不存在的 videoId（旧退化链的 E1 就是这么锁死的）
            item.expandTried = false;
            HgFeedAdapter.Holder hh = currentHolder();
            if (hh != null) hh.binding.hgError.setVisibility(View.GONE);
            onSettle(position);
            return;
        }
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
        // 回前台刷新（定罪账案二次因）：页面没死时也要吃到新一批——按频道
        // Open 语义重拉当前页签。自家页内跳转（搜索/进剧）回来不刷、不丢
        // 位置；「我的」面与横屏态在场时也不刷（面状态优先）。
        if (!stoppedOnce) return;
        stoppedOnce = false;
        if (suppressResumeRefresh) {
            suppressResumeRefresh = false;
            return;
        }
        if (mode == MODE_FEED && !mineShowing && !landMode) {
            stopPlayback();
            loadTab(tab);
        }
    }

    @Override
    protected void onStop() {
        stoppedOnce = true;
        super.onStop();
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
        binding.hgMine.getRoot().setOnOpenSeriesListener((seriesId, title, cover, episodeCount) -> {
            suppressResumeRefresh = true;
            HongguoFeedActivity.startSeries(this, seriesId, title, cover);
        });
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
                    session.clearRuntimeCache();
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
        applyStatusBarIconMode();
    }

    private void showFeedFace() {
        if (!mineShowing) return;
        mineShowing = false;
        binding.hgMine.getRoot().setVisibility(View.GONE);
        updateBottomTabs();
        applyStatusBarIconMode();
        if (resumeAfterMine && player != null) player.play();
        resumeAfterMine = false;
    }

    /**
     * 状态栏图标随面明暗：刷剧面深底用白图标，「我的」面白底必须换深图标
     * ——旧版全程白图标，进「我的」面后白色图标压白色状态区几乎不可见
     * （审计实证）。基类只在建页时按 useDarkSystemIcons 定一次，运行中
     * 换面要在这里跟着换。
     */
    private void applyStatusBarIconMode() {
        new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(mineShowing);
    }

    /**
     * 底栏随面变色（他真机亲点）：刷剧面＝黑色沉浸底（与流同面，选中白、
     * 未选灰）；「我的」面＝白色底（与我的面同面，选中深、未选灰）。
     * 旧版底色在布局里写死 #FFFFFF、两面恒白，刷剧面上像贴了条白带。
     */
    private void updateBottomTabs() {
        binding.hgBottomBar.setBackgroundColor(mineShowing ? 0xFFFFFFFF : 0xFF000000);
        int on = mineShowing ? 0xFF111111 : 0xFFFFFFFF;
        int off = mineShowing ? 0xFF999999 : 0xFF8E8E93;
        binding.hgTabHomeText.setTextColor(mineShowing ? off : on);
        binding.hgTabMineText.setTextColor(mineShowing ? on : off);
        binding.hgTabHomeIcon.setColorFilter(mineShowing ? off : on);
        binding.hgTabMineIcon.setColorFilter(mineShowing ? on : off);
    }

    private void openDrawer() {
        if (drawerOpen) return;
        drawerOpen = true;
        // 开关显示按 HgStore 当前值重刷（sweep 必修3）：弹幕在剧内另有写入口
        // （onDanmakuToggle），只在 initView 设一次会显示旧值、照着拨会拨反。
        // 监听回写的是同值，对 HgStore 无副作用。
        binding.hgDrawer.hgDrawerDirect.setChecked(HgStore.isLaunchDirect());
        binding.hgDrawer.hgDrawerDanmaku.setChecked(HgStore.isDanmakuOn());
        binding.hgDrawer.hgDrawerAutonext.setChecked(HgStore.isAutoNext());
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
