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
import com.fongmi.android.tv.hongguo.model.HgUpdateLogic;
import com.fongmi.android.tv.login.LoginManager;
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
 * （竖屏面窗口不转——官方主界面 screenOrientation="behind"＋configChanges
 * 自管）。横屏观看已改真横屏页（2026-10-28 重核定案，plan-landpage）：
 * 进剧/全屏观看走 activity-alias 横屏实例（本类同类、真横屏窗口、代官方
 * ShortSeriesLandActivity），四阶段「窗内转代横屏页」的旧路作废；窗内转
 * （landMode）只剩单条横屏短片无剧归属时的原地保底。显隐口径（2026-10-28
 * 他真机亲点后改）：刷剧面常显件
 * （顶栏页签/右列/剧条/弹幕筹/进度细线）不许自动隐藏——旧「播放中空闲
 * 4 秒自动全隐」把界面播几秒全收走、被他当场点名；自动全隐只保留在
 * 横屏观看态（横屏实例与 landMode 保底，他定案全屏全隐藏＋防烧屏），竖屏点屏只切
 * 播放/暂停、全屏控制条只在横屏态点屏唤出、自动再隐。</p>
 *
 * <p>弹幕：数据源已定版（danmaku-dig-20261028.md——评论口同端点换弹幕档，
 * 匿名可拉）：起播按窗拉取（服务端 30 秒切窗）、播放头跨窗界续拉、seek 后
 * 以新位置重拉，渲染走 PlayerView 自带的 media3 弹幕层（与点播播放器同套），
 * 开关在「弹」筹与抽屉两处同源（HgStore）。</p>
 *
 * <p>长按与更多面板（2026-10-28 他定版，dig-morepanel）：竖屏长按中间
 * 1/3 带内开五项面板（倍速/清晰度/弹幕/投屏/定时关闭，只这五项）、带外
 * 按住 2 倍速快进（亮「2倍速快进中」提示、松手恢复面板档）；横屏全屏态
 * 长按只有快进。面板倍速是持久档（HgStore 红果键，起播读它），长按快进
 * 是临时覆盖、两者不互改（官方同口径）。</p>
 */
public class HongguoFeedActivity extends BaseActivity implements HgFeedAdapter.Host {

    private static final String EXTRA_MODE = "hg_mode";
    private static final String EXTRA_SERIES_ID = "hg_series_id";
    private static final String EXTRA_TITLE = "hg_title";
    private static final String EXTRA_COVER = "hg_cover";
    private static final String EXTRA_START_FIRST = "hg_start_first";
    private static final String EXTRA_LAND_INSTANCE = "hg_land_instance";
    /** 进剧条目的作者 uid 透传：详情面不下发 uid，集流页的作者靠它（见
     * HgFeedSession.applyDetail 的继承注）；没有来源的入口不带、集流里
     * 点作者行就静默不打开（不许按名认人）。 */
    private static final String EXTRA_AUTHOR_UID = "hg_author_uid";
    private static final int MODE_FEED = 0;
    private static final int MODE_SERIES = 1;
    private static final long HIDE_DELAY_MS = 4000;
    private static final long FS_HIDE_DELAY_MS = 3000;

    public static void start(Context context) {
        Intent intent = new Intent(context, HongguoFeedActivity.class);
        intent.putExtra(EXTRA_MODE, MODE_FEED);
        // 重进红果复用已在栈里的实例（manifest 是 standard、无 launchMode，
        // 不加旗每次点底栏都新开一个实例从头 loadTab——「重进又重新加载」的一路）
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        context.startActivity(intent);
    }

    /** 从搜索/详情带一部剧进来：以该剧为流首展开续播。 */
    public static void startSeries(Context context, String seriesId, String title, String cover) {
        startSeries(context, seriesId, title, cover, null);
    }

    /** 带作者 uid 的进剧：uid 只从流条目 cell 抬升来，透传给集流页头。 */
    public static void startSeries(Context context, String seriesId, String title, String cover,
                                   String authorUid) {
        Intent intent = new Intent(context, HongguoFeedActivity.class);
        intent.putExtra(EXTRA_MODE, MODE_SERIES);
        intent.putExtra(EXTRA_SERIES_ID, seriesId);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_COVER, cover);
        if (authorUid != null) intent.putExtra(EXTRA_AUTHOR_UID, authorUid);
        context.startActivity(intent);
    }

    /** 精彩片段播完自动进剧（他亲授的官方交互）：不走断点、从第一集起。 */
    public static void startSeriesFirst(Context context, String seriesId, String title, String cover) {
        startSeriesFirst(context, seriesId, title, cover, null);
    }

    public static void startSeriesFirst(Context context, String seriesId, String title, String cover,
                                        String authorUid) {
        Intent intent = new Intent(context, HongguoFeedActivity.class);
        intent.putExtra(EXTRA_MODE, MODE_SERIES);
        intent.putExtra(EXTRA_SERIES_ID, seriesId);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_COVER, cover);
        intent.putExtra(EXTRA_START_FIRST, true);
        if (authorUid != null) intent.putExtra(EXTRA_AUTHOR_UID, authorUid);
        context.startActivity(intent);
    }

    /** 横屏剧从首页进剧（他亲授：点击/全屏观看＝全屏直接进剧集）：集流以横屏全屏态落。 */
    public static void startSeriesLandscape(Context context, String seriesId, String title, String cover) {
        startSeriesLandscape(context, seriesId, title, cover, null);
    }

    /**
     * 横屏剧进剧（他亲授：点击/全屏观看＝全屏直接进剧集）：走横屏实例
     * （activity-alias HongguoLandEntry，真横屏窗口、本类集流以横屏面落），
     * 落点走 HgStore 断点（集＋秒）与竖屏进剧同口径。
     */
    public static void startSeriesLandscape(Context context, String seriesId, String title, String cover,
                                            String authorUid) {
        Intent intent = new Intent();
        intent.setClassName(context, "com.fongmi.android.tv.hongguo.ui.HongguoLandEntry");
        intent.putExtra(EXTRA_MODE, MODE_SERIES);
        intent.putExtra(EXTRA_SERIES_ID, seriesId);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_COVER, cover);
        intent.putExtra(EXTRA_LAND_INSTANCE, true);
        if (authorUid != null) intent.putExtra(EXTRA_AUTHOR_UID, authorUid);
        context.startActivity(intent);
    }

    private ActivityHongguoFeedBinding binding;
    private HgFeedSession session;
    private HgFeedAdapter adapter;
    private PlayerView playerView;
    // 弹幕窗状态（当前条 vid 一换全清）：累积条目每窗回包后整表重喂控制器
    private String danmakuVid;
    private String danmakuSeriesId;
    private final java.util.List<androidx.media3.ui.danmaku.Danmaku> danmakuItems =
            new java.util.ArrayList<>();
    private String danmakuCursor;
    private long danmakuNextQueryMs = -1;
    private boolean danmakuFetching;
    private ExoPlayerEngine engine;
    private Player player;
    private HgFeedSources.Tab tab = HgFeedSources.Tab.RECOMMEND;
    /** 当前页签的数据源实例（续页凭证挂在它身上，loadTab 时换新）。 */
    private HgFeedSources.Source currentSource;
    /** 页签加载代次：迟到的旧回调（切页签/重拉）按代次丢弃，不串新表。 */
    private int loadSeq;
    private boolean feedLoadingMore;
    /** 页签首拉在飞标记与目标页签：同页签首拉未回不许重入（切页签/再点/下拉/onResume 都会撞 loadTab）。 */
    private boolean feedLoadingFirst;
    private HgFeedSources.Tab loadingFirstTab;
    /** 取链/播放失败自动跳下一条的连跳计数：成功起播或用户手动滑动清零，预算 3 次。 */
    private int autoSkipCount;
    /** 本次翻页是程序化自动跳（setCurrentItem 触发的 settle 不算用户滑动、不清连跳计数）。 */
    private boolean autoSkipping;
    private final Runnable hideErrorRunnable = () -> {
        HgFeedAdapter.Holder h = currentHolder();
        if (h != null) h.binding.hgError.setVisibility(View.GONE);
    };
    /** 本页曾被完全遮住（onStop）过：回前台只原地续播、不重拉（见 onResume）。 */
    private boolean stoppedOnce;
    /** 进程被杀后重建的首拉：成功后按 HgStore 落盘的流位置落位（仅首拉一次，
     * 切页签/下拉刷新等显式重拉不落位、回顶部）。 */
    private boolean feedPosRestorePending = true;
    /** 本次离台是自家页内跳转（搜索/进剧新开实例）：回来不刷新、不丢位置。 */
    private boolean suppressResumeRefresh;
    /** 本次离台是去作者主页：回来只重绑当前页互动态（关注徽标跟主页同源），
     * 不整表刷新、不停播。 */
    private boolean authorReturnRefresh;
    private int currentPos = -1;
    /** 精彩片段自动进剧：落集不走断点、从第一集起（EXTRA_START_FIRST）。 */
    private boolean startFromFirst;
    /** 横屏独立页实例（经别名 HongguoLandEntry 启动的本类横屏集流）：竖屏件
     * 全不显、互动走横排控制条，落点走断点与竖屏集流同口径。 */
    private boolean landInstance;
    /** 横屏实例锁定态：禁滑、chrome 全收，点屏只唤锁定钮（5 秒自动淡出）。 */
    private boolean landLocked;
    private final Runnable landLockFadeRunnable = () -> {
        if (landLocked) binding.hgLandLock.setVisibility(View.GONE);
    };

    /** 本页模式：MODE_FEED 短片流 / MODE_SERIES 集流（initView 自 intent 定）。 */
    private int mode = MODE_FEED;
    private String playingVideoId;
    private boolean chromeHidden;
    /** 清屏态（集流竖屏，用户亲点口径）：播中点画面进入——界面件全隐、
     * 只剩画面与右下全屏钮（钮变淡）；再点恢复。与 chromeHidden 分开记：
     * chromeHidden 是横屏观看态的自动全隐，互不串。 */
    private boolean cleanMode;
    /** 红果自家底栏当前面：false=首页（刷剧流本体） true=我的（壳内「我的」面）。 */
    private boolean mineShowing;
    private boolean drawerOpen;
    private boolean resumeAfterMine;
    /** 横屏窗内转模式（整页转 90°，窗口不转）；进入时的页下标，滑走即退出。 */
    private boolean landMode;
    // 窗内转方向随握持（传感器）：窗口被 behind＋竖屏锁住、系统不转，
    // 显示方向读不到——旧版写死 -90°，他往另一边转手机画面就是倒着的
    // （他 07:35 点名「竖屏横屏进入全屏完全乱」的实证之一）。监听器取
    // 物理朝向定方向：60–120°（顶边朝左）用 -90°、240–300°（顶边朝右）
    // 用 +90°；横屏中途翻面（两横向区之间）跟着翻 180°。
    // 下拉刷新（官方 feed 有此件）：手势接管状态与提示胶囊
    private boolean pullTracking;
    private boolean pullRefreshing;
    private float pullStartY;
    private float pullDy;
    private final Runnable hideRefreshHintRunnable = this::hideRefreshHint;
    private android.view.OrientationEventListener orientationListener;
    private int lastOrientationDeg = -1;
    private float landRotation = -90f;
    private int landPos = -1;
    private boolean seeking;
    private long lastSaveMs;
    /** 长按快进中（临时 2x 覆盖；松手恢复 HgStore 面板档，两者不互改）。 */
    private boolean speedBoost;
    /** 清晰度切换的重播位置（play() 消费一次；-1＝不用、走断点口径）。 */
    private long pendingSeekMs = -1;
    /** 定时关闭：档位/自定义分钟（会话内状态，不落盘）；计时档走 timerFireRunnable。 */
    private int timerMode = HgPanelLogic.TIMER_OFF;
    private int timerMinutes;
    private final Runnable timerFireRunnable = this::onTimerFire;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable hideChromeRunnable = () -> {
        // 自动全隐只在横屏观看态成立（横屏实例或窗内转保底，见类头显隐口径）
        if (landMode || landInstance) setChromeHidden(true);
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
                    // 定时「播完本剧」在流面：当前短片所属剧即本剧，播完
                    // 不再自动进剧，停播退出（面板定时口径）
                    if (timerMode == HgPanelLogic.TIMER_SERIES_END) {
                        stopPlayback();
                        finish();
                        return;
                    }
                    // 首页播的是官方选的精彩片段：播完自动进剧、从第一集起，
                    // 不循环（2026-10-28 他亲授的官方交互；此前「原地循环」
                    // 的 U7 定论作废——那是把别处的代码读成了流页行为）
                    if (currentPos >= 0 && currentPos < session.items.size()) {
                        HgFeedItem item = session.items.get(currentPos);
                        if (item.seriesId != null) {
                            suppressResumeRefresh = true;
                            startSeriesFirst(HongguoFeedActivity.this,
                                    item.seriesId, item.title, item.coverUrl, item.authorUid);
                        }
                    }
                } else if (timerMode == HgPanelLogic.TIMER_SERIES_END && atSeriesEnd()) {
                    // 定时「播完本剧」：最后一集播完停播退出，不连播下一部
                    stopPlayback();
                    finish();
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
            playerView.setResizeMode(landInstance || landscape
                    ? AspectRatioFrameLayout.RESIZE_MODE_FIT : AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
            HgFeedAdapter.Holder h = currentHolder();
            if (h != null) {
                android.view.View btn = h.binding.hgFullscreenWatch;
                btn.setVisibility(!landInstance && landscape ? View.VISIBLE : View.GONE);
                if (landscape && !landInstance) anchorFullscreenWatch(h, size.width, size.height);
            }
        }

        /**
         * 「全屏观看」钮锚位：官方实拍是贴在视频画面下沿之下居中（113482），
         * 不是挂在屏幕正中——旧版 layout 居中＋marginTop 130dp 是估的，宽高比
         * 一变就飘。真尺寸到达后按 FIT 渲染高算：钮心 = 视频下沿 + 15dp 间隙
         * + 钮半高（钮在布局里 gravity 居中，translationY 以页面中心为原点）。
         */
        private void anchorFullscreenWatch(HgFeedAdapter.Holder h, int videoW, int videoH) {
            android.view.View root = h.binding.getRoot();
            android.view.View btn = h.binding.hgFullscreenWatch;
            int pageW = root.getWidth();
            if (pageW <= 0 || videoW <= 0 || videoH <= 0) return;
            float density = getResources().getDisplayMetrics().density;
            float renderedH = (float) pageW * videoH / videoW;
            int btnH = btn.getHeight() > 0 ? btn.getHeight() : Math.round(40 * density);
            btn.setTranslationY(renderedH / 2f + 15 * density + btnH / 2f);
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            HgFeedAdapter.Holder h = currentHolder();
            if (h != null) h.binding.hgCover.setVisibility(View.VISIBLE);
            // 播放器环失败记编号 P，与取链失败同口径：先自动跳、跳不动才瞬时提示
            onItemPlayFailed("P");
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
        // 下拉刷新手势：挂在翻页器的内层列表上，只在刷剧面第一条、
        // 下拉越过起拖线时才接管，正常翻页与点按一概不碰
        RecyclerView feedRv = (RecyclerView) binding.hgPager.getChildAt(0);
        feedRv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        feedRv.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(RecyclerView rv, android.view.MotionEvent e) {
                return onPullIntercept(e);
            }

            @Override
            public void onTouchEvent(RecyclerView rv, android.view.MotionEvent e) {
                onPullTouch(e);
            }
        });
        binding.hgPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                onSettle(position);
            }

            @Override
            public void onPageScrollStateChanged(int state) {
                // 用户手指拖动翻页＝手动滑动：自动连跳计数清零
                if (state == ViewPager2.SCROLL_STATE_DRAGGING) autoSkipCount = 0;
            }
        });
        orientationListener = new android.view.OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (orientation == ORIENTATION_UNKNOWN) return;
                lastOrientationDeg = orientation;
                if (!landMode) return;
                float want = Float.NaN;
                if (orientation >= 60 && orientation <= 120) want = -90f;
                else if (orientation >= 240 && orientation <= 300) want = 90f;
                if (!Float.isNaN(want) && want != landRotation) {
                    landRotation = want;
                    binding.getRoot().animate().rotation(want).setDuration(200).start();
                }
            }
        };
        if (orientationListener.canDetectOrientation()) orientationListener.enable();
        playerView = new PlayerView(this);
        playerView.setUseController(false);
        playerView.setDanmakuEnabled(HgStore.isDanmakuOn());
        playerView.getDanmakuController().setConfig(hgDanmakuConfig());
        playerView.setShutterBackgroundColor(0xFF000000);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
        setupTabs();
        // 加载失败态可点重试（列表为空时点状态文案重拉当前页签）：
        // 此前失败只剩切页签/杀进程两条路，文案写着「稍后再试」却没给试的口
        binding.hgState.setOnClickListener(v -> {
            if (mode == MODE_FEED && session.items.isEmpty() && !mineShowing) loadTab(tab);
        });
        setupShellFaces();
        mode = getIntent().getIntExtra(EXTRA_MODE, MODE_FEED);
        adapter.setSeriesMode(mode == MODE_SERIES);
        startFromFirst = getIntent().getBooleanExtra(EXTRA_START_FIRST, false);
        landInstance = getIntent().getBooleanExtra(EXTRA_LAND_INSTANCE, false);
        if (mode == MODE_SERIES) {
            // 集流页不是刷剧页：刷剧页的页签栏/搜索/抽屉与自家底栏在集流里
            // 全不该在场（此前从没按 mode 分过 chrome，集流里点页签会把
            // 集流当场换成频道流、还共用同一份会话页表——交互串面实证）
            binding.hgTopBar.setVisibility(View.GONE);
            binding.hgBottomBar.setVisibility(View.GONE);
            setPagerBottomMargin(0);
            if (landInstance) {
                // 横屏实例：竖屏剧页标题栏不在场，互动走横排控制条；
                // 适配器竖屏件（右列配套钮）整套抑制
                adapter.setLandInstance(true);
                setupLandChrome();
            } else {
                // 剧页标题栏（官方构成：返回＋标题＋倍速位＋更多）：返回即退出
                // 剧页、更多开五项面板（与长按中心带同面板）、倍速位只读显示
                // 当前持久档（长按临时 2 倍速不写这里，走它自己的提示胶囊）
                binding.hgSeriesBar.setVisibility(View.VISIBLE);
                binding.hgTopScrim.setVisibility(View.VISIBLE);
                binding.hgSeriesTitle.setText(getIntent().getStringExtra(EXTRA_TITLE));
                binding.hgSeriesBack.setOnClickListener(v -> finish());
                binding.hgSeriesMore.setOnClickListener(v -> showMorePanel());
                refreshSeriesSpeedText();
            }
            String seriesId = getIntent().getStringExtra(EXTRA_SERIES_ID);
            session.items.clear();
            adapter.notifyDataSetChanged();
            if (seriesId != null) {
                HgFeedItem headPage = HgFeedItem.head(seriesId,
                        getIntent().getStringExtra(EXTRA_TITLE), null, null,
                        getIntent().getStringExtra(EXTRA_COVER), -1);
                // 作者 uid 从进剧条目透传来：展开成集页时由 applyDetail 继承
                headPage.authorUid = getIntent().getStringExtra(EXTRA_AUTHOR_UID);
                session.items.add(headPage);
                adapter.notifyItemInserted(0);
                onSettle(0);
            }
        } else {
            // 首页短片流（账 §2）：cell 条目逐条成页，不预置「上次的剧」——
            // 断点记在剧一级，从剧条/左滑进剧时才续（账 §2.4/§6.1）。
            // 进程被杀后重建：先回到上次页签，首拉成功后按落盘位置落位
            String savedTab = HgStore.feedPosTab();
            if (savedTab != null) {
                try {
                    tab = HgFeedSources.Tab.valueOf(savedTab);
                    refreshTabStyle();
                } catch (Exception ignored) {
                }
            }
            loadTab(tab);
            // 预约上线召回：首帧与首播先行，提示检查延后一拍（见 maybeCheckSubscribeRecall）
            ui.postDelayed(this::maybeCheckSubscribeRecall, 1500);
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
                    long to = bar.getProgress() * player.getDuration() / 1000;
                    player.seekTo(to);
                    // seek 后弹幕以新位置重拉（官方 RequestFrom.ON_SEEK_FINISH 同口径）
                    if (danmakuVid != null) {
                        danmakuItems.clear();
                        danmakuCursor = null;
                        danmakuNextQueryMs = -1;
                        fetchDanmakuWindow(to);
                    }
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
                else if (!session.items.isEmpty()) {
                    // 再点当前页签且已有内容：只滚回顶部，不重拉第一页
                    // （旧行为无条件 refreshCurrentTab 重跑 load()，流位置全丢）
                    if (currentPos != 0) binding.hgPager.setCurrentItem(0, false);
                } else refreshCurrentTab();
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
        feedPosRestorePending = false;
        tab = target;
        refreshTabStyle();
        loadTab(target);
    }

    /**
     * 再点当前页签＝刷新本页签（回顶部＋按 Open 语义重拉一批）：此前再点
     * 没任何反应，用户想换一批只能杀进程重进（他点名「每次打开都一样」
     * 的一条自救路也没有）。页签再点与下拉刷新共用此口。
     */
    private boolean pullEnabled() {
        return mode == MODE_FEED && !landMode && !mineShowing && !pullRefreshing
                && binding.hgPager.getCurrentItem() == 0
                && binding.hgPager.getScrollState() == ViewPager2.SCROLL_STATE_IDLE;
    }

    private boolean onPullIntercept(android.view.MotionEvent e) {
        if (!pullEnabled()) return false;
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                pullStartY = e.getY();
                pullDy = 0;
                return false;
            case android.view.MotionEvent.ACTION_MOVE:
                pullDy = e.getY() - pullStartY;
                if (pullDy > pullPx(28)) {
                    pullTracking = true;
                    showPullHint(pullDy);
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    private void onPullTouch(android.view.MotionEvent e) {
        if (!pullTracking) return;
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_MOVE:
                pullDy = e.getY() - pullStartY;
                showPullHint(pullDy);
                break;
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL:
                pullTracking = false;
                if (pullDy * 0.45f >= pullPx(64)) startPullRefresh();
                else hideRefreshHint();
                break;
            default:
                break;
        }
    }

    private void showPullHint(float dy) {
        float shown = Math.min(Math.max(dy, 0) * 0.45f, pullPx(96));
        View hint = binding.hgRefreshHint;
        hint.setVisibility(View.VISIBLE);
        hint.setAlpha(Math.min(1f, shown / pullPx(48)));
        hint.setTranslationY(shown - pullPx(40));
        binding.hgRefreshText.setText(shown >= pullPx(64) ? "松开刷新" : "下拉刷新");
    }

    private void startPullRefresh() {
        pullRefreshing = true;
        View hint = binding.hgRefreshHint;
        hint.setVisibility(View.VISIBLE);
        hint.setAlpha(1f);
        hint.setTranslationY(pullPx(8));
        binding.hgRefreshSpin.setVisibility(View.VISIBLE);
        binding.hgRefreshText.setText("正在刷新");
        refreshCurrentTab();
        // 收尾在 loadTab 的成败回调里（hideRefreshHint），此处只留超时兜底
        ui.removeCallbacks(hideRefreshHintRunnable);
        ui.postDelayed(hideRefreshHintRunnable, 6000);
    }

    private void hideRefreshHint() {
        pullRefreshing = false;
        ui.removeCallbacks(hideRefreshHintRunnable);
        binding.hgRefreshSpin.setVisibility(View.GONE);
        binding.hgRefreshHint.setVisibility(View.GONE);
        binding.hgRefreshHint.setTranslationY(0);
        binding.hgRefreshHint.setAlpha(1f);
    }

    private float pullPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    private void refreshCurrentTab() {
        if (mineShowing) return;
        saveProgress();
        stopPlayback();
        feedPosRestorePending = false;
        loadTab(tab);
    }

    private void loadTab(HgFeedSources.Tab target) {
        // 同页签首拉在飞时重入直接返回：切页签连点/再点页签/下拉刷新/onResume
        // 都会撞到这里，旧实现无条件重跑会把在飞的首拉代次顶掉、白打一轮请求
        if (feedLoadingFirst && loadingFirstTab == target) return;
        feedLoadingFirst = true;
        loadingFirstTab = target;
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
                    feedLoadingFirst = false;
                    if (pullRefreshing) hideRefreshHint();
                    appendSeriesItems(result, target);
                    adapter.notifyDataSetChanged();
                    if (session.items.isEmpty()) {
                        binding.hgState.setVisibility(View.VISIBLE);
                        binding.hgState.setText(source.emptyHint());
                    } else {
                        binding.hgState.setVisibility(View.GONE);
                        int start = 0;
                        if (feedPosRestorePending) {
                            // 进程被杀后重建：落回上次流位置（仅首批范围内
                            // 可直落；更深位置要顺序续拉多页才够得着，与限流
                            // 对策冲突，本批不做连环续拉，见汇总说明）
                            feedPosRestorePending = false;
                            int saved = HgStore.feedPosIndex(target.name());
                            if (saved > 0 && saved < session.items.size()) start = saved;
                        }
                        binding.hgPager.setCurrentItem(start, false);
                        onSettle(start);
                    }
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                runOnUiThread(() -> {
                    if (tab != target || seq != loadSeq) return;
                    feedLoadingFirst = false;
                    if (pullRefreshing) hideRefreshHint();
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
                HgStore.putMeta(s.getSeriesId(), s.getTitle(), s.getCoverUrl(), s.getEpisodeCount(),
                        s.getBadge());
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
                    if (result == null || result.isEmpty()) {
                        // 本页被 dedup 吃空但源仍称有下一页（游标每页都推进、
                        // 必然终止）：链式再拉一页，不许就此静默封口只剩首窗
                        if (source.hasMore()) maybeLoadMore();
                        return;
                    }
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
        autoSkipping = false; // 程序化自动跳的 settle 在此消费标记（计数不清零，见字段注）
        if (landMode && position != landPos) exitLandMode(); // 滑离横屏页：窗内转自动退出
        if (position != currentPos) saveProgress();
        currentPos = position;
        // 发现流位置随 settle 落盘：进程被杀重进时落位用（集流不记，断点在剧一级）
        if (mode == MODE_FEED) HgStore.saveFeedPos(tab.name(), position);
        adapter.setCurrentPos(position);
        if (cleanMode) exitCleanMode(); // 清屏是当前页的观看态，滑页即退出、不串页
        setChromeHidden(false);
        HgFeedItem item = session.items.get(position);
        refreshSeriesTitle(item);
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
                    if (!startFromFirst && p != null && p.episodeIndex > 1) {
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

    /**
     * 条目取链/播放失败的统一出口（官方「直接刷」口径）：不把条目停成
     * 封面＋重试药丸的可停留状态——还有下一条且连跳预算未尽（≤3）就自动
     * 翻到下一条；预算耗尽或已是末条，错误文案只短暂显示约 2.5 秒自动隐。
     * 成功起播（play）与用户手动滑动（DRAGGING）清零连跳计数。
     */
    private void onItemPlayFailed(String code) {
        if (code != null && !code.isEmpty()) android.util.Log.w("HgFeed", "条目加载失败 code=" + code);
        if (autoSkipCount < 3 && currentPos >= 0 && currentPos < session.items.size() - 1) {
            autoSkipCount++;
            autoSkipping = true;
            binding.hgPager.setCurrentItem(currentPos + 1, true);
            return;
        }
        HgFeedAdapter.Holder h = currentHolder();
        if (h == null) return;
        showErrorWithCode(h, code);
        ui.removeCallbacks(hideErrorRunnable);
        ui.postDelayed(hideErrorRunnable, 2500);
    }

    /** 点亮错误片；code 为失败出口编号（E1–E5／播放器环 P），只进日志不上屏
     * （诊断编号曾直接拼进文案、他真机截图里就是「加载失败（E1）」——内部编号不许给用户看）。 */
    private void showErrorWithCode(HgFeedAdapter.Holder holder, String code) {
        if (holder == null) return;
        if (code != null && !code.isEmpty()) android.util.Log.w("HgFeed", "条目加载失败 code=" + code);
        holder.binding.hgError.setText(errorTextFor(code));
        holder.binding.hgError.setVisibility(View.VISIBLE);
    }

    /** 失败阶段的人话（编号只进日志）：让他一眼知道卡在哪一环。 */
    private static String errorTextFor(String code) {
        if (code == null) return "加载失败，点按重试";
        switch (code) {
            case "E1": return "取数据失败，点按重试";
            case "E2": return "这一集暂时没有可播的源";
            case "E3": return "取地址失败，点按重试";
            case "E4": return "解密失败，点按重试";
            case "E5": return "播放准备失败，点按重试";
            default: return "加载失败，点按重试";
        }
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
                // 官方口径：发现流没有可停留的失败态——失败先自动跳下一条，
                // 跳不动才短暂提示后自动隐（编号只进日志，见 onItemPlayFailed）
                onItemPlayFailed(code);
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
        long startAt;
        if (pendingSeekMs >= 0) {
            // 清晰度切换续播：回原位置，不走断点口径
            startAt = pendingSeekMs;
            pendingSeekMs = -1;
        } else {
            HgStore.Progress p = HgStore.progressOf(item.seriesId);
            startAt = p != null && p.episodeIndex == item.episodeIndex ? p.positionMs : 0;
        }
        MediaMetadata metadata = new MediaMetadata.Builder()
                .setTitle(item.title == null ? "" : item.title).build();
        PlaySpec spec = PlaySpec.from("hongguo:" + videoId, localUrl, new HashMap<>(), metadata);
        playingVideoId = videoId;
        autoSkipCount = 0; // 成功起播：自动连跳计数清零
        if (startAt > 0) engine.start(spec, startAt, true);
        else engine.start(spec, true);
        // 起播初始倍速读红果键（面板持久档）：红果链不经 PlayerManager，
        // 此前恒为播放器默认 1x；长按快进的临时覆盖不经此口
        player.setPlaybackSpeed(HgStore.getSpeed());
        refreshSeriesSpeedText();
        refreshSeriesTitle(item);
        if (landInstance) refreshLandTexts();
        startDanmakuFor(item, startAt);
        armAutoHide();
        // 评论预拉：当前条目起播后空闲期拉第一页填进程内缓存（只一页、
        // 每条目一次，接口已实证连发无恙），点开评论时不再全价等首包
        HgCommentsPanel.prefetchFirstPage(item);
    }

    /**
     * settle 后的预备工作：只做详情类预展开（下一剧头定 vid）、回收滑远的流、
     * 临近末尾续拉下一页。video_model 一律不再预取——限流对策口径
     * （feed-all-placeholder §E）：play 类接口只为真正要播的那一条取，
     * 预取批量正是把设备档案打爆的压力源；封面靠列表数据本身渲染，不另发请求。
     */
    private void afterSettleSideWork(int position) {
        if (mode == MODE_FEED) {
            for (int i = position + 1; i <= Math.min(position + 2, session.items.size() - 1); i++) {
                HgFeedItem next = session.items.get(i);
                if (next.videoId == null && next.seriesHead && i == position + 1 && !next.reservable) {
                    int at = i;
                    session.resolveClip(at, changedAt -> {
                        if (changedAt < 0) return;
                        adapter.notifyItemChanged(changedAt);
                    });
                }
            }
        } else {
            for (int i = position + 1; i <= Math.min(position + 2, session.items.size() - 1); i++) {
                HgFeedItem next = session.items.get(i);
                if (next.videoId == null && next.seriesHead && i == position + 1) {
                    int sizeBefore = session.items.size();
                    int at = i;
                    session.expandHead(at, changedAt -> {
                        if (changedAt < 0) return;
                        adapter.notifyItemChanged(changedAt);
                        int inserted = session.items.size() - sizeBefore;
                        if (inserted > 0) adapter.notifyItemRangeInserted(changedAt + 1, inserted);
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
        stopSpeedBoost();
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
            playerView.setResizeMode(landInstance || session.items.get(currentPos).isLandscape()
                    ? AspectRatioFrameLayout.RESIZE_MODE_FIT : AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
        }
    }

    // 累计观看（详情页评分门槛的 watch_duration 数据源）：按播放位置推进量
    // 累加到当前剧——暂停不计、单拍跳变 >2 秒（拖动/快进/切集残值）不计；
    // 待冲量随 saveProgress 的 4 秒节律与各落盘点一起入 HgStore。
    private String watchSeriesId;
    private long watchLastPos = -1;
    private long watchAccumMs;

    private void accumulateWatch(long position) {
        if (player == null || !player.isPlaying()
                || currentPos < 0 || currentPos >= session.items.size()) {
            watchLastPos = -1;
            return;
        }
        String sid = session.items.get(currentPos).seriesId;
        if (sid == null || sid.isEmpty()) {
            watchLastPos = -1;
            return;
        }
        if (!sid.equals(watchSeriesId)) {
            flushWatch();
            watchSeriesId = sid;
            watchLastPos = -1;
        }
        if (watchLastPos >= 0) {
            long d = position - watchLastPos;
            if (d >= 0 && d <= 2000) watchAccumMs += d;
        }
        watchLastPos = position;
    }

    private void flushWatch() {
        if (watchSeriesId != null && watchAccumMs > 0) {
            HgStore.addWatchMs(watchSeriesId, watchAccumMs);
        }
        watchAccumMs = 0;
    }

    private void tickProgress() {
        if (player == null || currentPos < 0) return;
        if (landInstance) tickLandStatus();
        long duration = player.getDuration();
        long position = player.getCurrentPosition();
        accumulateWatch(position);
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
        // 弹幕续拉：播放头跨过服务端给的下一窗起点就拉下一窗（游标原样回传）
        if (danmakuVid != null && !danmakuFetching && danmakuNextQueryMs >= 0
                && position >= danmakuNextQueryMs) {
            fetchDanmakuWindow(danmakuNextQueryMs);
        }
    }

    private void saveProgress() {
        flushWatch();
        if (player == null || currentPos < 0 || currentPos >= session.items.size()) return;
        HgFeedItem item = session.items.get(currentPos);
        if (item.seriesId == null || item.episodeIndex <= 0) return;
        long pos = player.getCurrentPosition();
        if (pos <= 0) return;
        if (mode == MODE_FEED) {
            // 短片流也落集内断点（用户口径：进程被杀重进也要落断点续播）；
            // 只写断点本体不进历史索引——账 §2.4「短片不算在看这部剧」的
            // 历史面口径保留，断点面按新口径覆盖
            HgStore.saveProgressQuiet(item.seriesId, item.episodeIndex, pos);
        } else {
            HgStore.saveProgress(item.seriesId, item.episodeIndex, pos);
        }
    }

    private static String formatTime(long ms) {
        long sec = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%d:%02d", sec / 60, sec % 60);
    }

    // ---------- 显隐（刷剧面常显；自动全隐只在横屏全屏观看态，防烧屏） ----------

    /** 剧页标题栏倍速位文案（官方定版：1.0 显「倍速」，其余显原始浮点串如「1.25x」）。 */
    private void refreshSeriesSpeedText() {
        // 集流标题栏倍速位恒显数值串（官方实拍 113558：1.0 也显「1.0x」、
        // 带仪表图标；旧「1.0 显倍速二字」是横屏横排条的口径、串面了）
        binding.hgSeriesSpeed.setText(Float.toString(HgStore.getSpeed()) + "x");
    }

    /** 集流标题栏标题：显当前集「第N集」（官方实拍 113558 证），不是剧名；
     * 剧头页还没展开成集（episodeIndex&lt;0）时回落进剧时带的剧名。 */
    private void refreshSeriesTitle(HgFeedItem item) {
        if (mode != MODE_SERIES || landInstance) return;
        if (item != null && item.episodeIndex > 0) {
            binding.hgSeriesTitle.setText("第" + item.episodeIndex + "集");
        } else {
            binding.hgSeriesTitle.setText(getIntent().getStringExtra(EXTRA_TITLE));
        }
    }

    private void setChromeHidden(boolean hidden) {
        chromeHidden = hidden;
        if (mode == MODE_FEED) {
            binding.hgTopBar.setVisibility(hidden ? View.GONE : View.VISIBLE);
        }
        // 剧页标题栏：竖屏集流常显（与刷剧面常驻件同口径），横屏观看态
        // （landMode 保底）与横屏实例随全屏全隐藏一起收——进/出横屏都经此函数收口
        binding.hgSeriesBar.setVisibility(
                mode == MODE_SERIES && !landMode && !landInstance ? View.VISIBLE : View.GONE);
        // 顶部渐变随标题栏同显隐，清屏态一并收（exitCleanMode 时 cleanMode
        // 已先清零、经此恢复）
        binding.hgTopScrim.setVisibility(
                mode == MODE_SERIES && !landMode && !landInstance && !cleanMode ? View.VISIBLE : View.GONE);
        HgFeedAdapter.Holder h = currentHolder();
        if (h != null) {
            if (landInstance) {
                // 横屏页没有竖屏件：右列/信息层/剧条/发弹幕入口/全屏观看钮恒收
                h.binding.hgRightRail.setVisibility(View.GONE);
                h.binding.hgInfo.setVisibility(View.GONE);
                h.binding.hgSeriesBar.setVisibility(View.GONE);
                h.binding.hgDanmakuBox.setVisibility(View.GONE);
                h.binding.hgFullscreenWatch.setVisibility(View.GONE);
            } else {
                int v = hidden ? View.GONE : View.VISIBLE;
                h.binding.hgRightRail.setVisibility(v);
                h.binding.hgInfo.setVisibility(v);
                h.binding.hgSeriesBar.setVisibility(v);
                // 进度线与清屏同进退；方钮透明度在恢复态归位（清屏的变淡
                // 不许串到常态/别的页——回收不重绑时 bind 的归位管不到）
                h.binding.hgProgressBox.setVisibility(v);
                if (!hidden) h.binding.hgFsBtn.setAlpha(1f);
                // 「弹」重显走适配器同一口径（集流常显带斜杠/流页开才显，
                // 见 HgFeedAdapter.bindDanmakuState），不许在这里另立一份
                if (hidden) h.binding.hgDanmakuBox.setVisibility(View.GONE);
                else adapter.bindDanmakuState(h);
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
        }
        if (landInstance) {
            // 横屏 chrome（状态条＋横排条）随 hidden 收放；锁定钮在锁定态由
            // 锁定机单独管（点屏唤出/5 秒淡出），不跟 chrome 一起亮
            int lv = hidden ? View.GONE : View.VISIBLE;
            binding.hgLandStatus.setVisibility(lv);
            binding.hgLandBar.setVisibility(lv);
            if (!landLocked) binding.hgLandLock.setVisibility(lv);
            if (hidden) {
                new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView())
                        .hide(WindowInsetsCompat.Type.systemBars());
            } else {
                new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView())
                        .show(WindowInsetsCompat.Type.systemBars());
            }
            if (hidden) binding.hgFsBar.setVisibility(View.GONE);
            return;
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

    /**
     * 进清屏（用户亲点口径「点一下屏幕只剩画面，啥都隐藏」「只剩这个按钮」）：
     * 集流竖屏的标题栏/右列/信息摞/选集条/发弹幕入口/全屏观看筹/进度线与
     * 系统栏全隐，唯一留屏的是右下全屏钮、且变淡（防烧屏）。选集条用
     * INVISIBLE 不用 GONE——底部行是横排，条塌掉会把方钮拽到左边。
     * 淡值照官方定版 0.4：官方清屏体验件 fq3/a（CleanScreenExperienceBiz）
     * 与 fq3/b 在 enable_nav_alpha_landscape_btn_opt 开时均以 0.4 设全屏钮
     * 透明度、恢复 1.0（配置 CleanScreenExperienceOptV725）；该开关关时
     * 官方在沉浸态干脆不显全屏钮（utils/x1 isSupportShowLandIcon）。
     */
    private void enterCleanMode() {
        cleanMode = true;
        binding.hgSeriesBar.setVisibility(View.GONE);
        binding.hgTopScrim.setVisibility(View.GONE);
        HgFeedAdapter.Holder h = currentHolder();
        if (h != null) {
            h.binding.hgRightRail.setVisibility(View.GONE);
            h.binding.hgInfo.setVisibility(View.GONE);
            h.binding.hgSeriesBar.setVisibility(View.INVISIBLE);
            h.binding.hgDanmakuBox.setVisibility(View.GONE);
            h.binding.hgFullscreenWatch.setVisibility(View.GONE);
            h.binding.hgProgressBox.setVisibility(View.GONE);
            h.binding.hgFsBtn.setVisibility(View.VISIBLE);
            h.binding.hgFsBtn.setAlpha(0.4f);
        }
        new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView())
                .hide(WindowInsetsCompat.Type.systemBars());
    }

    /** 出清屏：走 setChromeHidden(false) 的常态恢复口径（进度线/方钮
     * 透明度在那条路上一并归位，见 setChromeHidden 注）。 */
    private void exitCleanMode() {
        if (!cleanMode) return;
        cleanMode = false;
        setChromeHidden(false);
    }

    private void armAutoHide() {
        ui.removeCallbacks(hideChromeRunnable);
        // 刷剧面常显件不自动隐（他真机亲点，见类头显隐口径）；
        // 自动全隐只在横屏观看态（横屏实例或窗内转保底）排期
        if (!landMode && !landInstance) return;
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
        if (landInstance) {
            onLandTap();
            return;
        }
        if (binding.hgFsBar.getVisibility() == View.VISIBLE) {
            binding.hgFsBar.setVisibility(View.GONE);
            ui.removeCallbacks(hideFsRunnable);
            return;
        }
        if (mode == MODE_SERIES && !landMode && !landInstance) {
            // 清屏口径（用户亲点）：播中点画面进清屏、清屏中点画面恢复；
            // 暂停态点画面仍是续播（落到下面 togglePlayPause），清屏只在播中进
            if (cleanMode) {
                exitCleanMode();
                return;
            }
            if (player != null && player.isPlaying()) {
                enterCleanMode();
                return;
            }
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
        if (mode == MODE_FEED && currentPos >= 0 && currentPos < session.items.size()) {
            HgFeedItem it = session.items.get(currentPos);
            // 横屏剧在首页点击＝全屏直接进剧集（他亲授）；竖屏剧点击仍是播停
            if (it.isLandscape() || it.landscapeHint) {
                enterSeriesFullscreen(currentPos);
                return;
            }
        }
        togglePlayPause();
        if (player != null && player.isPlaying()) armAutoHide();
    }

    // ---------- 长按：竖屏中间带出面板／带外与横屏只有快进 ----------

    /**
     * 长按分工（他定版＋官方 F6 定版）：竖屏点在横向中间 1/3 带内＝开
     * 五项面板；带外＝按住 2 倍速快进。横屏观看态（横屏实例与 landMode
     * 保底）不分带、任何位置都只有快进；锁定态长按不响应。长按触发后
     * 本次单击已被适配器抑制（suppressTap）。
     */
    @Override
    public void onLongPress(int position, float x, int width) {
        if (position != currentPos || mineShowing || drawerOpen) return;
        if (landLocked) return;
        if (!landInstance && !landMode && HgPanelLogic.inCenterBand(x, width)) {
            showMorePanel();
            return;
        }
        startSpeedBoost();
    }

    @Override
    public void onLongPressRelease(int position) {
        stopSpeedBoost();
    }

    /** 长按快进起：只在播放中生效（官方 isPlaying 门）；临时切 2x 并亮提示胶囊。 */
    private void startSpeedBoost() {
        if (speedBoost || player == null || !player.isPlaying()) return;
        speedBoost = true;
        player.setPlaybackSpeed(HgPanelLogic.BOOST_SPEED);
        binding.hgSpeedTips.setVisibility(View.VISIBLE);
    }

    /** 长按快进收：灭提示、恢复面板持久档（HgStore 红果倍速键）。 */
    private void stopSpeedBoost() {
        if (!speedBoost) return;
        speedBoost = false;
        binding.hgSpeedTips.setVisibility(View.GONE);
        if (player != null) player.setPlaybackSpeed(HgStore.getSpeed());
    }

    /** 横屏剧进剧（首页点击与「全屏观看」共用）：开集流并以横屏全屏态落。 */
    private void enterSeriesFullscreen(int position) {
        if (position < 0 || position >= session.items.size()) return;
        HgFeedItem item = session.items.get(position);
        if (item.seriesId == null) {
            // 无剧归属的单条横屏短片：保底原地窗内转（不跳页）——横屏实例是
            // 集流形态、装不下无剧短片，此边角官方形态未证实，维持现状
            if (landMode) exitLandMode();
            else enterLandMode(position);
            return;
        }
        suppressResumeRefresh = true;
        startSeriesLandscape(this, item.seriesId, item.title, item.coverUrl, item.authorUid);
    }

    @Override
    public void onAction(int position, HgFeedAdapter.Action action) {
        if (position < 0 || position >= session.items.size()) return;
        HgFeedItem item = session.items.get(position);
        HgFeedAdapter.Holder h = currentHolder();
        switch (action) {
            case LIKE, FAV, RESERVE -> {
                // 四阶段：未登录先拉壳子统一登录，不落状态、不假成功
                if (session.interactions.requiresLogin()) {
                    promptHongguoLogin();
                    return;
                }
                switch (action) {
                    case LIKE -> session.interactions.toggleLike(item);
                    case FAV -> session.interactions.toggleFavorite(item.seriesId);
                    case RESERVE -> {
                        boolean now = session.interactions.toggleReserve(item.seriesId);
                        // 点击时只报本地状态（已预约/已取消）：服务端写失败会
                        // 回滚＋另行提示，不在点击瞬间假报「成功」
                        android.widget.Toast.makeText(this,
                                now ? "已预约，上线后会通知你" : "已取消预约",
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
            }, () -> player == null ? 0L : Math.max(0L, player.getCurrentPosition()));
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
        // 分享文案：官方分享文案格式未在包内抠到，自定「我在看《剧名》」
        // （账 fix-rail-catalog-series 标明自定，不冒充官方）
        String text = item.title == null || item.title.isEmpty()
                ? "红果短剧" : "我在看《" + item.title + "》";
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

    /** 剧名行点入剧详情页（用户亲授：与作者页分开的另一个去处）。 */
    @Override
    public void onSeriesDetail(int position) {
        if (position < 0 || position >= session.items.size()) return;
        HgFeedItem item = session.items.get(position);
        if (item.seriesId == null || item.seriesId.isEmpty()) return;
        suppressResumeRefresh = true;
        HongguoSeriesDetailActivity.start(this, item.seriesId, item.title, item.coverUrl);
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
            else startSeries(this, item.seriesId, item.title, item.coverUrl, item.authorUid);
            return;
        }
        if (item.multiSeason && albumId != null && !albumId.isEmpty()) {
            final String sid = item.seriesId;
            final String title = item.title;
            final String cover = item.coverUrl;
            final String entryAuthorUid = item.authorUid;
            session.client.fetchAlbum(albumId, new HgCallback<java.util.List<com.fongmi.android.tv.hongguo.model.HgSeriesItem>>() {
                @Override
                public void onSuccess(java.util.List<com.fongmi.android.tv.hongguo.model.HgSeriesItem> seasons) {
                    session.putAlbum(sid, seasons);
                    runOnUiThread(() -> {
                        // 迟到回调防串面：页签已切/条目已不在当前表就丢弃
                        if (isFinishing() || isDestroyed() || !session.items.contains(item)) return;
                        if (seasons != null && seasons.size() >= 2) showSeriesSheet(item, seasons);
                        else startSeries(HongguoFeedActivity.this, sid, title, cover, entryAuthorUid);
                    });
                }

                @Override
                public void onError(int httpCode, String message) {
                    session.putAlbum(sid, java.util.Collections.emptyList());
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed() && session.items.contains(item)) {
                            startSeries(HongguoFeedActivity.this, sid, title, cover, entryAuthorUid);
                        }
                    });
                }
            });
            return;
        }
        startSeries(this, item.seriesId, item.title, item.coverUrl, item.authorUid);
    }

    private void showSeriesSheet(HgFeedItem item, java.util.List<com.fongmi.android.tv.hongguo.model.HgSeriesItem> siblings) {
        HgSeriesSheet.show(this, siblings, item, session.interactions,
                picked -> startSeries(this, picked.getSeriesId(), picked.getTitle(),
                        picked.getCoverUrl(), picked.getAuthorUid()));
    }

    /**
     * 点头像/作者名进作者主页（官方 HGOpenProfilePageServiceImpl 门禁口径：
     * 非本人才能打开）。条目没带作者 uid（详情/搜索来源不下发）时静默
     * 不打开——不许按名字认人；本人（uid 与本地账号 uid 相同）同样不开。
     * 本人 uid 未回填（从没登录成功过）时门禁无从比对，按非本人放行。
     */
    @Override
    public void onAuthor(int position) {
        if (position < 0 || position >= session.items.size()) return;
        HgFeedItem item = session.items.get(position);
        String authorUid = item.authorUid;
        if (authorUid == null || authorUid.isEmpty()) return;
        if (authorUid.equals(HgStore.profileUid())) return;
        authorReturnRefresh = true;
        HongguoAuthorActivity.start(this, authorUid, item.authorName,
                item.authorAvatar, item.authorDesc);
    }

    /**
     * 集流作者行关注钮（只未关注时亮）：与作者页同一条关注链——未登录先拉
     * 壳子统一登录、不落状态；乐观先行＋真接口收口，失败回滚并如实提示。
     * 取关不在此钮（钮已关注即不亮），走作者页的确认框口径。
     */
    @Override
    public void onAuthorFollow(int position) {
        if (position < 0 || position >= session.items.size()) return;
        HgFeedItem item = session.items.get(position);
        String uid = item.authorUid;
        if (uid == null || uid.isEmpty() || HgStore.isAuthorFollowed(uid)) return;
        if (session.interactions.requiresLogin()) {
            promptHongguoLogin();
            return;
        }
        HgStore.setAuthorFollowed(uid, true);
        adapter.notifyItemChanged(position);
        session.fqClient.doFollowAction(true, uid, new HgCallback<Void>() {

            @Override
            public void onSuccess(Void result) {
            }

            @Override
            public void onError(int code, String msg) {
                ui.post(() -> {
                    HgStore.setAuthorFollowed(uid, false);
                    if (currentPos >= 0 && currentPos < session.items.size()) {
                        adapter.notifyItemChanged(currentPos);
                    }
                    android.widget.Toast.makeText(HongguoFeedActivity.this,
                            "关注没成功，再试试", android.widget.Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * 「从第1集开始看」引导筹点击：回本剧第一集、从 0 起播（不走断点）。
     * 已在第一集页时直接 seek 回 0；跳页时借 play() 的 pendingSeekMs
     * 一次性口径（与清晰度切换续播同机制）压掉断点定位。
     */
    @Override
    public void onStartFromFirst(int position) {
        if (mode != MODE_SERIES || position < 0 || position >= session.items.size()) return;
        HgFeedItem item = session.items.get(position);
        int target = findEpisodePosition(item.seriesId, 1);
        if (target < 0) return;
        if (target == currentPos) {
            if (player != null) player.seekTo(0);
            return;
        }
        pendingSeekMs = 0;
        jumpTo(target);
    }

    /**
     * 点「弹」＝开发弹幕编辑层（官方 A25：入口是 PublishDanmakuEntranceView，
     * 不是开关）。未登录先拉壳子统一登录（与互动四件同口径），不打开编辑层；
     * 条目缺 vid/剧 id 时无处可发，不开层。
     */
    @Override
    public void onDanmakuPublish(int position) {
        if (position != currentPos || position < 0 || position >= session.items.size()) return;
        if (session.interactions.requiresLogin()) {
            promptHongguoLogin();
            return;
        }
        HgFeedItem item = session.items.get(position);
        if (item.videoId == null || item.seriesId == null) return;
        showDanmakuPublishDialog(item);
    }

    /**
     * 发弹幕编辑层（自家小 Dialog，深底照红果面口径——官方框体是社区
     * Compose 编辑器、无经典布局可抄，见 dig-comment-danmaku.md §3.3）：
     * 输入框（hint 照官方串「发条友善的弹幕吧」）＋发表＋取消；空文本发表
     * 不可点；发送中发表钮禁用防连点；失败不关层、层内提示再试；成功关层
     * 并把自己这条并入当前弹幕表重喂控制器（滚动、白字、offset＝发表位置）。
     */
    private void showDanmakuPublishDialog(HgFeedItem item) {
        com.fongmi.android.tv.databinding.DialogHongguoDanmakuBinding db =
                com.fongmi.android.tv.databinding.DialogHongguoDanmakuBinding.inflate(getLayoutInflater());
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.setContentView(db.getRoot());
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT));
            int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.85f);
            dialog.getWindow().setLayout(w, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        final boolean[] sending = {false};
        android.text.TextWatcher watcher = new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                boolean ready = s != null && !s.toString().trim().isEmpty() && !sending[0];
                db.hgDmPublish.setEnabled(ready);
                db.hgDmPublish.setAlpha(ready ? 1f : 0.45f);
            }
        };
        db.hgDmInput.addTextChangedListener(watcher);
        db.hgDmCancel.setOnClickListener(v -> dialog.dismiss());
        db.hgDmPublish.setOnClickListener(v -> {
            if (sending[0]) return;
            String text = db.hgDmInput.getText() == null ? "" : db.hgDmInput.getText().toString().trim();
            if (text.isEmpty()) return;
            sending[0] = true;
            db.hgDmPublish.setEnabled(false);
            db.hgDmPublish.setAlpha(0.45f);
            db.hgDmError.setVisibility(View.GONE);
            final long offsetMs = player == null ? 0 : Math.max(0, player.getCurrentPosition());
            final String vid = item.videoId;
            final String sid = item.seriesId;
            session.fqClient.publishDanmaku(sid, vid, text, offsetMs, new HgCallback<Void>() {
                @Override
                public void onSuccess(Void ignored) {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        dialog.dismiss();
                        // 只并入仍是当前条的弹幕表：已滑走/已关弹幕就不喂，
                        // 防把这条塞进别集的表里
                        if (vid.equals(danmakuVid) && playerView != null) {
                            danmakuItems.add(toMedia3Danmaku(
                                    new com.fongmi.android.tv.hongguo.model.HgDanmaku(
                                            text, offsetMs, 0, null, 0)));
                            playerView.getDanmakuController().setItems(
                                    new java.util.ArrayList<>(danmakuItems));
                        }
                    });
                }

                @Override
                public void onError(int httpCode, String message) {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed() || !dialog.isShowing()) return;
                        sending[0] = false;
                        db.hgDmError.setVisibility(View.VISIBLE);
                        watcher.afterTextChanged(db.hgDmInput.getText());
                    });
                }
            });
        });
        dialog.show();
    }

    // ---------- 更多面板（他定五项＋弹幕开后的弹幕设置行） ----------

    /**
     * 五项面板：倍速六档筹（他实拍档表，点了立即变速并存红果默认倍速）／
     * 清晰度（当前集模型现拉档表、高→低、当前档对勾，点选以该档重解续播；
     * 单档整行隐藏）／弹幕开关（与抽屉同源走 applyDanmakuSwitch）／投屏
     * （星辰现成 CastDialog）／定时关闭（子选四档）。外加他 2026-10-28
     * 22:40 补钉的弹幕设置行：弹幕开时在弹幕行下方出、关时摘除（官方
     * HGSeriesPanelServiceImpl 的 insert/remove 同口径，面板内拨开关即时
     * 跟随），点开 showDanmakuSettings。白底底部单，形态照官方实拍
     * 113510、与选集单同族口径。
     */
    private void showMorePanel() {
        if (currentPos < 0 || currentPos >= session.items.size()) return;
        HgFeedItem item = session.items.get(currentPos);
        com.fongmi.android.tv.databinding.DialogHongguoMorePanelBinding pb =
                com.fongmi.android.tv.databinding.DialogHongguoMorePanelBinding.inflate(getLayoutInflater());
        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        dialog.setContentView(pb.getRoot());
        bindPanelSpeed(pb);
        bindPanelDefinitions(pb, dialog, item);
        pb.hgPanelDanmakuSwitch.setChecked(HgStore.isDanmakuOn());
        pb.hgPanelDanmakuSettings.setVisibility(
                HgStore.isDanmakuOn() ? View.VISIBLE : View.GONE);
        pb.hgPanelDanmakuSwitch.setOnCheckedChangeListener((b, on) -> {
            applyDanmakuSwitch(on);
            pb.hgPanelDanmakuSettings.setVisibility(on ? View.VISIBLE : View.GONE);
        });
        pb.hgPanelDanmakuSettings.setOnClickListener(v -> showDanmakuSettings());
        pb.hgPanelCast.setOnClickListener(v -> {
            dialog.dismiss();
            doCast();
        });
        pb.hgPanelTimerValue.setText(HgPanelLogic.timerStateText(timerMode, timerMinutes));
        pb.hgPanelTimer.setOnClickListener(v -> showTimerDialog(
                () -> pb.hgPanelTimerValue.setText(
                        HgPanelLogic.timerStateText(timerMode, timerMinutes))));
        dialog.show();
    }

    private void bindPanelSpeed(com.fongmi.android.tv.databinding.DialogHongguoMorePanelBinding pb) {
        for (float speed : HgPanelLogic.SPEEDS) {
            TextView chip = new TextView(this);
            chip.setText(HgPanelLogic.speedLabel(speed));
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setTextSize(14);
            pb.hgPanelSpeedRow.addView(chip, new android.widget.LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            chip.setOnClickListener(v -> {
                HgStore.setSpeed(speed);
                // 长按快进中不抢临时覆盖（松手按新档恢复）；平时立即变速
                if (player != null && !speedBoost) player.setPlaybackSpeed(speed);
                refreshPanelSpeedChips(pb);
                refreshSeriesSpeedText();
                android.widget.Toast.makeText(this,
                        "倍速已切换到 " + HgPanelLogic.speedLabel(speed),
                        android.widget.Toast.LENGTH_SHORT).show();
            });
        }
        refreshPanelSpeedChips(pb);
    }

    private void refreshPanelSpeedChips(
            com.fongmi.android.tv.databinding.DialogHongguoMorePanelBinding pb) {
        int selected = HgPanelLogic.speedIndex(HgStore.getSpeed());
        for (int i = 0; i < pb.hgPanelSpeedRow.getChildCount(); i++) {
            TextView chip = (TextView) pb.hgPanelSpeedRow.getChildAt(i);
            boolean on = i == selected;
            chip.setBackgroundResource(on ? com.fongmi.android.tv.R.drawable.bg_hg_panel_chip_on : 0);
            chip.setTextColor(on ? 0xFF111111 : 0xFF999999);
            chip.setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    private void bindPanelDefinitions(
            com.fongmi.android.tv.databinding.DialogHongguoMorePanelBinding pb,
            android.app.Dialog dialog, HgFeedItem item) {
        com.fongmi.android.tv.hongguo.model.HgVideoModel model =
                item.videoId == null ? null : session.cache.getFresh(item.videoId);
        java.util.List<String> defs = new java.util.ArrayList<>();
        if (model != null) {
            java.util.LinkedHashSet<String> raw = new java.util.LinkedHashSet<>();
            for (com.fongmi.android.tv.hongguo.model.HgVideoEntry e : model.getEntries()) {
                if (e.isPlayableCodec()) raw.add(e.getDefinition());
            }
            defs = HgPanelLogic.orderedDefinitions(raw);
        }
        if (defs.size() < 2) {
            // 模型没到手/只有一档：整行隐藏（官方非多档源同样不成行），不编档
            pb.hgPanelDefBlock.setVisibility(View.GONE);
            return;
        }
        String current = session.resolver.currentDefinition(item.videoId);
        if (current == null || !defs.contains(current)) {
            com.fongmi.android.tv.hongguo.model.HgVideoEntry first =
                    model == null ? null : model.firstPlayableEntry();
            current = first == null ? defs.get(0) : first.getDefinition();
        }
        final String currentDef = current;
        float density = getResources().getDisplayMetrics().density;
        for (String def : defs) {
            boolean on = def.equals(currentDef);
            TextView chip = new TextView(this);
            chip.setText((on ? "✓ " : "") + HgPanelLogic.definitionLabel(def));
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setTextSize(13);
            chip.setPadding(Math.round(10 * density), 0, Math.round(10 * density), 0);
            chip.setBackgroundResource(on ? com.fongmi.android.tv.R.drawable.bg_hg_panel_chip_on : 0);
            chip.setTextColor(on ? 0xFF111111 : 0xFF999999);
            chip.setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            if (pb.hgPanelDefRow.getChildCount() > 0) lp.setMarginStart(Math.round(2 * density));
            pb.hgPanelDefRow.addView(chip, lp);
            chip.setOnClickListener(v -> {
                dialog.dismiss();
                if (def.equals(currentDef)) return;
                android.widget.Toast.makeText(this,
                        "正在切换到 " + HgPanelLogic.definitionLabel(def),
                        android.widget.Toast.LENGTH_SHORT).show();
                switchDefinition(def, item);
            });
        }
    }

    /** 清晰度切换：以选档重解当前 vid，解好后 seek 回原位置续播（pendingSeekMs 由 play 消费）。 */
    private void switchDefinition(String definition, HgFeedItem item) {
        if (player == null || item.videoId == null) return;
        final String videoId = item.videoId;
        final int posAtRequest = currentPos;
        pendingSeekMs = Math.max(0, player.getCurrentPosition());
        playingVideoId = null;
        session.resolver.resolveAs(videoId, definition, new HgPlayResolver.Listener() {
            @Override
            public void onReady(String vid, String localUrl, int w, int h) {
                if (!vid.equals(currentVideoId())) {
                    pendingSeekMs = -1;
                    return;
                }
                HgFeedItem use = item;
                if (w > 0 && h > 0 && posAtRequest >= 0 && posAtRequest < session.items.size()
                        && (item.videoWidth != w || item.videoHeight != h)) {
                    use = item.withSize(w, h);
                    session.items.set(posAtRequest, use);
                    adapter.notifyItemChanged(posAtRequest);
                }
                play(videoId, localUrl, use);
            }

            @Override
            public void onFailed(String vid, String code, String message) {
                if (!vid.equals(currentVideoId())) return;
                pendingSeekMs = -1;
                showErrorWithCode(currentHolder(), code);
            }
        });
    }

    /**
     * 投屏：复用星辰播放器现成投屏路——点播页 onCast 同款 CastDialog＋
     * CastVideo（mobile/leanback 两 flavor 同 API）；地址用解链的本地代理
     * URL，CastVideo 构造时把 127.0.0.1 改写成局域网 IP 供电视拉流。
     */
    private void doCast() {
        if (currentPos < 0 || currentPos >= session.items.size() || player == null) return;
        HgFeedItem item = session.items.get(currentPos);
        String url = item.videoId == null ? null : session.resolver.readyUrl(item.videoId);
        if (url == null) {
            android.widget.Toast.makeText(this, "投屏还没准备好，稍后再试",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String name = (item.title == null ? "红果短剧" : item.title)
                + (item.episodeIndex > 0 ? " 第" + item.episodeIndex + "集" : "");
        com.fongmi.android.tv.bean.CastVideo video = new com.fongmi.android.tv.bean.CastVideo(
                name, url, Math.max(0, player.getCurrentPosition()),
                java.util.Collections.emptyMap());
        com.fongmi.android.tv.ui.dialog.CastDialog.create().video(video).fm(true).show(this);
    }

    /** 定时关闭子选（官方四档）：关闭/播完本剧/30分钟即点即定；自定义输分钟点设定。 */
    private void showTimerDialog(Runnable onChanged) {
        com.fongmi.android.tv.databinding.DialogHongguoTimerBinding tb =
                com.fongmi.android.tv.databinding.DialogHongguoTimerBinding.inflate(getLayoutInflater());
        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        dialog.setContentView(tb.getRoot());
        tb.hgTimerOffCheck.setVisibility(timerMode == HgPanelLogic.TIMER_OFF ? View.VISIBLE : View.GONE);
        tb.hgTimerSeriesCheck.setVisibility(timerMode == HgPanelLogic.TIMER_SERIES_END ? View.VISIBLE : View.GONE);
        tb.hgTimerFixedCheck.setVisibility(timerMode == HgPanelLogic.TIMER_FIXED ? View.VISIBLE : View.GONE);
        if (timerMode == HgPanelLogic.TIMER_CUSTOM && timerMinutes > 0) {
            tb.hgTimerCustomInput.setText(String.valueOf(timerMinutes));
        }
        tb.hgTimerOff.setOnClickListener(v -> {
            armTimer(HgPanelLogic.TIMER_OFF, 0);
            dialog.dismiss();
            onChanged.run();
        });
        tb.hgTimerSeries.setOnClickListener(v -> {
            armTimer(HgPanelLogic.TIMER_SERIES_END, 0);
            dialog.dismiss();
            onChanged.run();
        });
        tb.hgTimerFixed.setOnClickListener(v -> {
            armTimer(HgPanelLogic.TIMER_FIXED, HgPanelLogic.FIXED_MINUTES);
            dialog.dismiss();
            onChanged.run();
        });
        tb.hgTimerCustomOk.setOnClickListener(v -> {
            int minutes = -1;
            try {
                minutes = Integer.parseInt(tb.hgTimerCustomInput.getText() == null
                        ? "" : tb.hgTimerCustomInput.getText().toString().trim());
            } catch (NumberFormatException ignored) {
            }
            if (minutes <= 0) {
                android.widget.Toast.makeText(this, "请输入分钟数",
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            armTimer(HgPanelLogic.TIMER_CUSTOM, minutes);
            dialog.dismiss();
            onChanged.run();
        });
        dialog.show();
    }

    private void armTimer(int mode, int minutes) {
        timerMode = mode;
        timerMinutes = minutes;
        ui.removeCallbacks(timerFireRunnable);
        long delay = HgPanelLogic.timerDelayMs(mode, minutes);
        if (delay > 0) ui.postDelayed(timerFireRunnable, delay);
    }

    /** 计时到点：停播并退出本页（官方定时关闭语义）。 */
    private void onTimerFire() {
        if (isFinishing() || isDestroyed()) return;
        stopPlayback();
        finish();
    }

    /** 当前条是不是本剧最后一集（定时「播完本剧」的判定点）：无下一条、下一条换剧或下一条是别剧剧头都算到头。 */
    private boolean atSeriesEnd() {
        if (currentPos < 0 || currentPos >= session.items.size()) return true;
        if (currentPos + 1 >= session.items.size()) return true;
        HgFeedItem cur = session.items.get(currentPos);
        HgFeedItem next = session.items.get(currentPos + 1);
        return next.seriesHead || next.seriesId == null || !next.seriesId.equals(cur.seriesId);
    }

    /** 弹幕开关统一落点（抽屉开关，自家小设置）：落状态＋启停渲染层＋连带刷新「弹」入口显隐＋开时为当前条起拉。 */
    private void applyDanmakuSwitch(boolean on) {
        HgStore.setDanmakuOn(on);
        HgFeedAdapter.Holder h = currentHolder();
        if (h != null) adapter.bindDanmakuState(h);
        if (playerView != null) playerView.setDanmakuEnabled(on);
        if (on) {
            if (currentPos >= 0 && currentPos < session.items.size()) {
                startDanmakuFor(session.items.get(currentPos),
                        player == null ? 0 : player.getCurrentPosition());
            }
        } else {
            clearDanmaku();
        }
    }

    /**
     * 红果弹幕渲染配置（自有口径）：渲染器缺省打底＋HgStore 四项覆盖
     * （不透明度/移动速度/显示区域/字体大小，映射在 HgPanelLogic）。
     * 此前借点播 DanmakuSetting 的全局配置——红果设置与点播设置互串，
     * 本批起断开：红果只读红果自有键（官方同为自有 danmaku_config 存储）。
     * 横屏实例共用本 Activity 的装配、同走红果键（与官方单一存储同构）；
     * 本批未动任何横屏界面/交互（横屏无设置入口，维持现状）。
     */
    private androidx.media3.ui.danmaku.DanmakuConfig hgDanmakuConfig() {
        int lines = HgStore.getDanmakuLines();
        return androidx.media3.ui.danmaku.DanmakuConfig.DEFAULT.buildUpon()
                .setTransparency(HgPanelLogic.danmakuTransparency(HgStore.getDanmakuOpacity()))
                .setScrollSpeedFactor(HgPanelLogic.danmakuSpeedFactor(HgStore.getDanmakuSpeedIndex()))
                .setTextScale(HgPanelLogic.danmakuFontScale(HgStore.getDanmakuFontIndex()))
                .setMaxScrollLines(lines)
                .setMaxTopLines(lines)
                .setMaxBottomLines(lines)
                .build();
    }

    /** 弹幕设置改值即时回刷渲染层：正在播的当前条同步换观感，不等切集。 */
    private void applyDanmakuOptions() {
        if (playerView != null) playerView.getDanmakuController().setConfig(hgDanmakuConfig());
    }

    /**
     * 弹幕设置单（官方 DanmakuOptionDialog 竖屏形态的自家等价）：不透明度／
     * 移动速度／显示区域／字体大小四滑杆＋恢复默认，改值即存 HgStore 并
     * 即时回刷渲染。官方另有四行（弹幕屏蔽词/屏蔽彩色弹幕/智能防遮/清屏
     * 模式展示弹幕）由服务端旗标门控、官方包内无缺省值且咱家渲染层无
     * 对应通道，不摆假项（留痕见 hongguo/panel-danmaku-settings-20261028.md）。
     * 面板不关、设置单叠在面板之上（官方同为叠弹）。
     */
    private void showDanmakuSettings() {
        com.fongmi.android.tv.databinding.DialogHongguoDanmakuSettingsBinding sb =
                com.fongmi.android.tv.databinding.DialogHongguoDanmakuSettingsBinding.inflate(getLayoutInflater());
        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        dialog.setContentView(sb.getRoot());
        bindDanmakuSettings(sb);
        sb.hgDmReset.setOnClickListener(v -> {
            HgStore.resetDanmakuOptions();
            bindDanmakuSettings(sb);
            applyDanmakuOptions();
            android.widget.Toast.makeText(this, "已恢复默认",
                    android.widget.Toast.LENGTH_SHORT).show();
        });
        dialog.show();
    }

    /** 四滑杆按 HgStore 现值回填并挂监听（恢复默认后重绑一次即全量刷新）。 */
    private void bindDanmakuSettings(
            com.fongmi.android.tv.databinding.DialogHongguoDanmakuSettingsBinding sb) {
        // 不透明度：滑杆 0–80 对应 20%–100%（官方滑杆 max 80＋偏移 20）
        sb.hgDmOpacityValue.setText(HgStore.getDanmakuOpacity() + "%");
        sb.hgDmOpacity.setOnSeekBarChangeListener(null);
        sb.hgDmOpacity.setProgress(HgStore.getDanmakuOpacity() - HgPanelLogic.DM_OPACITY_MIN);
        sb.hgDmOpacity.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean fromUser) {
                int percent = progress + HgPanelLogic.DM_OPACITY_MIN;
                HgStore.setDanmakuOpacity(percent);
                sb.hgDmOpacityValue.setText(percent + "%");
                applyDanmakuOptions();
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar bar) {
            }
        });
        // 移动速度：五档（下标即档位）
        sb.hgDmSpeedValue.setText(HgPanelLogic.danmakuSpeedLabel(HgStore.getDanmakuSpeedIndex()));
        sb.hgDmSpeed.setOnSeekBarChangeListener(null);
        sb.hgDmSpeed.setProgress(HgStore.getDanmakuSpeedIndex());
        sb.hgDmSpeed.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean fromUser) {
                HgStore.setDanmakuSpeedIndex(progress);
                sb.hgDmSpeedValue.setText(HgPanelLogic.danmakuSpeedLabel(progress));
                applyDanmakuOptions();
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar bar) {
            }
        });
        // 显示区域：滑杆 0–3 对应 1–4 行
        sb.hgDmAreaValue.setText(HgPanelLogic.danmakuAreaText(HgStore.getDanmakuLines()));
        sb.hgDmArea.setOnSeekBarChangeListener(null);
        sb.hgDmArea.setProgress(HgStore.getDanmakuLines() - HgPanelLogic.DM_LINES_MIN);
        sb.hgDmArea.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean fromUser) {
                int lines = progress + HgPanelLogic.DM_LINES_MIN;
                HgStore.setDanmakuLines(lines);
                sb.hgDmAreaValue.setText(HgPanelLogic.danmakuAreaText(lines));
                applyDanmakuOptions();
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar bar) {
            }
        });
        // 字体大小：五档（下标即档位）
        sb.hgDmFontValue.setText(HgPanelLogic.danmakuFontLabel(HgStore.getDanmakuFontIndex()));
        sb.hgDmFont.setOnSeekBarChangeListener(null);
        sb.hgDmFont.setProgress(HgStore.getDanmakuFontIndex());
        sb.hgDmFont.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean fromUser) {
                HgStore.setDanmakuFontIndex(progress);
                sb.hgDmFontValue.setText(HgPanelLogic.danmakuFontLabel(progress));
                applyDanmakuOptions();
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar bar) {
            }
        });
    }

    /** 为一条起弹幕：关着/无 vid 直接清；同 vid 已有条目不重拉。 */
    private void startDanmakuFor(HgFeedItem item, long startAtMs) {
        if (playerView == null || item == null) return;
        if (!HgStore.isDanmakuOn() || item.videoId == null || item.seriesId == null) {
            clearDanmaku();
            return;
        }
        if (item.videoId.equals(danmakuVid) && !danmakuItems.isEmpty()) return;
        danmakuVid = item.videoId;
        danmakuSeriesId = item.seriesId;
        danmakuItems.clear();
        danmakuCursor = null;
        danmakuNextQueryMs = -1;
        playerView.getDanmakuController().clearItems();
        fetchDanmakuWindow(Math.max(0, startAtMs));
    }

    private void clearDanmaku() {
        danmakuVid = null;
        danmakuSeriesId = null;
        danmakuItems.clear();
        danmakuCursor = null;
        danmakuNextQueryMs = -1;
        danmakuFetching = false;
        if (playerView != null) playerView.getDanmakuController().clearItems();
    }

    /** 拉一窗弹幕（窗界服务端定）：回包整表重喂控制器，迟到回包按 vid 丢弃。 */
    private void fetchDanmakuWindow(long startMs) {
        final String vid = danmakuVid;
        final String sid = danmakuSeriesId;
        if (vid == null || sid == null || danmakuFetching) return;
        danmakuFetching = true;
        long durationMs = player == null ? 0 : Math.max(0, player.getDuration());
        session.fqClient.episodeDanmaku(sid, vid, startMs, durationMs, danmakuCursor,
                new com.fongmi.android.tv.hongguo.api.HgCallback<
                        com.fongmi.android.tv.hongguo.model.HgDanmaku.Window>() {
                    @Override
                    public void onSuccess(com.fongmi.android.tv.hongguo.model.HgDanmaku.Window w) {
                        runOnUiThread(() -> {
                            danmakuFetching = false;
                            if (!vid.equals(danmakuVid)) return; // 已滑走/已关
                            for (com.fongmi.android.tv.hongguo.model.HgDanmaku dm : w.items) {
                                danmakuItems.add(toMedia3Danmaku(dm));
                            }
                            if (playerView != null) {
                                playerView.getDanmakuController().setItems(
                                        new java.util.ArrayList<>(danmakuItems));
                            }
                            danmakuCursor = w.cursor;
                            danmakuNextQueryMs = w.nextQueryTimeMs;
                        });
                    }

                    @Override
                    public void onError(int httpCode, String message) {
                        runOnUiThread(() -> {
                            danmakuFetching = false;
                            danmakuNextQueryMs = -1; // 失败不重试刷屏，seek/重播会再起
                        });
                    }
                });
    }

    /** HgDanmaku → media3 弹幕条：位置 0 滚动／1 底／2 顶，颜色缺省白。 */
    private static androidx.media3.ui.danmaku.Danmaku toMedia3Danmaku(
            com.fongmi.android.tv.hongguo.model.HgDanmaku dm) {
        int type = dm.positionType == 1 ? androidx.media3.ui.danmaku.Danmaku.TYPE_BOTTOM
                : dm.positionType == 2 ? androidx.media3.ui.danmaku.Danmaku.TYPE_TOP
                : androidx.media3.ui.danmaku.Danmaku.TYPE_SCROLL;
        int color = android.graphics.Color.WHITE;
        if (dm.colorHex != null && !dm.colorHex.isEmpty()) {
            try {
                color = android.graphics.Color.parseColor(
                        dm.colorHex.startsWith("#") ? dm.colorHex : "#" + dm.colorHex);
            } catch (Exception ignored) {
            }
        }
        return new androidx.media3.ui.danmaku.Danmaku(dm.text, dm.offsetTimeMs, type, color,
                0f, 0, "", 0);
    }

    @Override
    public void onFullscreenWatch(int position) {
        if (position != currentPos || landInstance) return;
        saveProgress();
        // 首页（流）里点它＝全屏直接进剧集（他亲授）；竖屏集流里点它同样进
        // 横屏实例（断点已落盘、横屏实例按断点集/秒落位）；旧窗内转只剩
        // enterSeriesFullscreen 无剧归属短片的保底，不再是剧的去向
        if (mode == MODE_FEED) {
            enterSeriesFullscreen(position);
            return;
        }
        HgFeedItem item = session.items.get(position);
        if (item.seriesId != null) {
            suppressResumeRefresh = true;
            startSeriesLandscape(this, item.seriesId, item.title, item.coverUrl, item.authorUid);
        }
    }

    /**
     * 进横屏窗内转：根视图宽高互换＋绕中心转 90°＋平移回窗口中心，视频层与
     * 控制层（顶栏/右列/本剧条/全屏控制条）整体转过去铺满。窗口本身保持
     * behind＋竖屏锁定、永不旋转，故进出没有任何窗口旋转残影。
     * 旋转方向随握持传感器定（见 orientationListener 字段注），不再写死。
     */
    // ---------- 横屏独立页（landInstance）：状态条/横排控制条/锁定/抽屉 ----------

    /**
     * 横屏 chrome 接线（集流 init 的横屏分支调）：三件互动走 onAction 同路
     * （与右列同一口子）、弹幕走全 App 同源开关、清晰度/倍速/选集开右侧
     * 抽屉、锁定钮走锁定机。
     */
    private void setupLandChrome() {
        // 横排条内层宽＝屏宽×0.78 居中（官方 ca_ 的 f0w 约束定版），XML 给不出百分比宽、代码落位
        ViewGroup.LayoutParams ilp = binding.hgLandBarInner.getLayoutParams();
        ilp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.78f);
        binding.hgLandBarInner.setLayoutParams(ilp);
        binding.hgLandLike.setOnClickListener(v -> {
            if (!landLocked) onAction(currentPos, HgFeedAdapter.Action.LIKE);
        });
        binding.hgLandComment.setOnClickListener(v -> {
            if (!landLocked) onAction(currentPos, HgFeedAdapter.Action.COMMENT);
        });
        binding.hgLandFav.setOnClickListener(v -> {
            if (!landLocked) onAction(currentPos, HgFeedAdapter.Action.FAV);
        });
        binding.hgLandDanmaku.setOnClickListener(v -> {
            if (landLocked) return;
            applyDanmakuSwitch(!HgStore.isDanmakuOn());
            refreshLandStrip();
        });
        binding.hgLandDefinition.setOnClickListener(v -> {
            if (!landLocked) showLandDefinitionDrawer();
        });
        binding.hgLandSpeed.setOnClickListener(v -> {
            if (!landLocked) showLandSpeedDrawer();
        });
        binding.hgLandEpisodes.setOnClickListener(v -> {
            if (!landLocked) HgEpisodeDrawer.show(this, session, currentPos, this::jumpTo);
        });
        binding.hgLandLock.setOnClickListener(v -> toggleLandLock());
        binding.hgLandPlayHint.setOnClickListener(v -> {
            if (!landLocked) onLandTap();
        });
        updateLandNetIcon();
        refreshLandStrip();
        refreshLandTexts();
    }

    /**
     * 横屏点屏：锁定中只唤锁定钮；暂停时点屏起播（中央播放提示同步收）；
     * 播放中 chrome 隐时唤出并排自动隐、chrome 显时点屏暂停（与竖屏点屏
     * 播停同口径，暂停后 chrome 常驻、播放提示亮）。
     */
    private void onLandTap() {
        if (landLocked) {
            wakeLockButton();
            return;
        }
        if (player == null) return;
        if (!player.isPlaying()) {
            player.play();
            setLandPauseHint(false);
            setChromeHidden(false);
            armAutoHide();
            return;
        }
        if (chromeHidden) {
            setChromeHidden(false);
            armAutoHide();
        } else {
            player.pause();
            setLandPauseHint(true);
            ui.removeCallbacks(hideChromeRunnable);
        }
    }

    /**
     * 横屏暂停提示（官方 ce9 两件套）：左下播放三角＋右下时间文本同显同隐；
     * 时间文本＝当前播放位置（00:00:00 形态，照官方 evj 缺省串的时:分:秒格式），
     * 亮着时随状态条 ticker 一起保鲜（见 tickLandStatus）。
     */
    private void setLandPauseHint(boolean show) {
        binding.hgLandPlayHint.setVisibility(show ? View.VISIBLE : View.GONE);
        binding.hgLandTime.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) refreshLandTimeText();
    }

    private void refreshLandTimeText() {
        if (player == null) return;
        long ms = Math.max(0, player.getCurrentPosition());
        long totalSec = ms / 1000;
        binding.hgLandTime.setText(String.format(java.util.Locale.US, "%02d:%02d:%02d",
                totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60));
    }

    /** 横排条三件态（与右列 bindInteractionState 同源）：填充态＋计数（数据有才显）＋弹幕开关态。 */
    private void refreshLandStrip() {
        if (!landInstance || currentPos < 0 || currentPos >= session.items.size()) return;
        HgFeedItem item = session.items.get(currentPos);
        HgInteractions in = session.interactions;
        binding.hgLandLikeIcon.setImageResource(in.isLiked(item.seriesId)
                ? com.fongmi.android.tv.R.drawable.ic_hg_heart_fill
                : com.fongmi.android.tv.R.drawable.ic_hg_heart);
        binding.hgLandFavIcon.setImageResource(in.isFavorite(item.seriesId)
                ? com.fongmi.android.tv.R.drawable.ic_hg_star_fill
                : com.fongmi.android.tv.R.drawable.ic_hg_star);
        setLandCount(binding.hgLandLikeCount, item.likeCount);
        String cc = session.commentCountOf(item.seriesId);
        setLandCount(binding.hgLandCommentCount, cc != null ? cc : item.commentCount);
        setLandCount(binding.hgLandFavCount, item.favCount);
        binding.hgLandDanmaku.setAlpha(HgStore.isDanmakuOn() ? 1f : 0.45f);
    }

    private void setLandCount(TextView view, String count) {
        if (count == null || count.isEmpty()) {
            view.setVisibility(View.GONE);
        } else {
            view.setVisibility(View.VISIBLE);
            view.setText(count);
        }
    }

    /** 横排条右段文本：清晰度值（官方 hlk 位＝当前档）＋倍速值（当前持久档）。 */
    private void refreshLandTexts() {
        if (!landInstance) return;
        HgFeedItem item = currentPos >= 0 && currentPos < session.items.size()
                ? session.items.get(currentPos) : null;
        String def = null;
        if (item != null && item.videoId != null) {
            def = session.resolver.currentDefinition(item.videoId);
            if (def == null) {
                com.fongmi.android.tv.hongguo.model.HgVideoModel m =
                        session.cache.getFresh(item.videoId);
                com.fongmi.android.tv.hongguo.model.HgVideoEntry first =
                        m == null ? null : m.firstPlayableEntry();
                if (first != null) def = first.getDefinition();
            }
        }
        // 清晰度位（官方 hlk）：无当前档串时整位隐藏，不留空占位（kr3/t0.w0 定版）
        if (def == null) {
            binding.hgLandDefinition.setVisibility(View.GONE);
        } else {
            binding.hgLandDefinition.setVisibility(View.VISIBLE);
            binding.hgLandDefinition.setText(HgPanelLogic.definitionLabel(def));
        }
        binding.hgLandSpeed.setText(HgPanelLogic.landSpeedLabel(HgStore.getSpeed()));
    }

    /** 状态条（官方 cah 口径）：实时时钟＋电量百分比，随 400ms ticker 刷新；计数顺带保鲜。 */
    private void tickLandStatus() {
        binding.hgLandClock.setText(new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                .format(new java.util.Date()));
        if (binding.hgLandTime.getVisibility() == View.VISIBLE) refreshLandTimeText();
        android.content.Intent bi = registerReceiver(null,
                new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
        if (bi != null) {
            int level = bi.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
            int scale = bi.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
            if (level >= 0 && scale > 0) {
                binding.hgLandBatteryText.setText(Math.round(level * 100f / scale) + "%");
            }
        }
        if (!chromeHidden) refreshLandStrip();
    }

    private void updateLandNetIcon() {
        boolean wifi = false;
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            android.net.NetworkCapabilities caps = cm == null ? null
                    : cm.getNetworkCapabilities(cm.getActiveNetwork());
            wifi = caps != null && caps.hasTransport(
                    android.net.NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Exception ignored) {
        }
        binding.hgLandNet.setImageResource(wifi
                ? com.fongmi.android.tv.R.drawable.ic_hg_wifi
                : com.fongmi.android.tv.R.drawable.ic_hg_cell);
    }

    /**
     * 锁定机（官方 ur3.c 口径）：锁定后禁竖滑切集、控制层全收、点屏只唤
     * 锁定钮、5 秒自动淡出；解锁恢复控制层。钮图标随态（锁/开锁），官方
     * 本体是 Lottie、自家静态矢量标近似。
     */
    private void toggleLandLock() {
        if (!landInstance) return;
        if (landLocked) {
            landLocked = false;
            ui.removeCallbacks(landLockFadeRunnable);
            binding.hgPager.setUserInputEnabled(true);
            binding.hgLandLock.setImageResource(com.fongmi.android.tv.R.drawable.ic_hg_unlock);
            setChromeHidden(false);
            armAutoHide();
        } else {
            landLocked = true;
            binding.hgPager.setUserInputEnabled(false);
            setChromeHidden(true);
            binding.hgLandLock.setImageResource(com.fongmi.android.tv.R.drawable.ic_hg_lock);
            binding.hgLandLock.setVisibility(View.VISIBLE);
            wakeLockButton();
        }
    }

    /** 锁定中唤出锁定钮并重排 5 秒淡出（官方定版时长）。 */
    private void wakeLockButton() {
        if (!landLocked) return;
        binding.hgLandLock.setVisibility(View.VISIBLE);
        ui.removeCallbacks(landLockFadeRunnable);
        ui.postDelayed(landLockFadeRunnable, 5000);
    }

    /** 右侧抽屉通用壳（倍速面板定版宽 232dp，清晰度表共用）：右锚、深底、行由 filler 逐行加。 */
    private void showLandDrawer(String title,
            java.util.function.BiConsumer<android.widget.LinearLayout, android.app.Dialog> filler) {
        com.fongmi.android.tv.databinding.DialogHongguoLandListBinding lb =
                com.fongmi.android.tv.databinding.DialogHongguoLandListBinding.inflate(getLayoutInflater());
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(lb.getRoot());
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setGravity(android.view.Gravity.END);
            int width = (int) (232 * getResources().getDisplayMetrics().density);
            window.setLayout(width, android.view.WindowManager.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            android.view.WindowManager.LayoutParams lp = window.getAttributes();
            lp.dimAmount = 0.45f;
            window.setAttributes(lp);
        }
        lb.hgLlTitle.setText(title);
        filler.accept(lb.hgLlRows, dialog);
        dialog.show();
    }

    private TextView landDrawerRow(String text, boolean on) {
        TextView row = new TextView(this);
        row.setText(text);
        row.setTextSize(15);
        row.setTextColor(on ? 0xFFFA7705 : 0xFFFFFFFF);
        row.setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        int pad = Math.round(11 * getResources().getDisplayMetrics().density);
        row.setPadding(0, pad, 0, pad);
        return row;
    }

    /** 横屏倍速面板（官方 xr3.f 定版）：档表 LAND_SPEEDS 自上而下、行文案「Nx倍速」、1x 带「（默认）」。 */
    private void showLandSpeedDrawer() {
        float current = HgStore.getSpeed();
        showLandDrawer("倍速", (rows, dialog) -> {
            for (float speed : HgPanelLogic.LAND_SPEEDS) {
                TextView row = landDrawerRow(HgPanelLogic.speedLabel(speed) + "倍速"
                        + (speed == 1f ? "（默认）" : ""), speed == current);
                row.setOnClickListener(v -> {
                    dialog.dismiss();
                    HgStore.setSpeed(speed);
                    // 长按快进中不抢临时覆盖（与竖屏面板同口径）
                    if (player != null && !speedBoost) player.setPlaybackSpeed(speed);
                    refreshLandTexts();
                });
                rows.addView(row);
            }
        });
    }

    /** 横屏清晰度表（官方 hlk 入口）：与竖屏面板同源——模型档表、高→低、当前档高亮，点选走 switchDefinition 重解续播。 */
    private void showLandDefinitionDrawer() {
        if (currentPos < 0 || currentPos >= session.items.size()) return;
        HgFeedItem item = session.items.get(currentPos);
        com.fongmi.android.tv.hongguo.model.HgVideoModel model =
                item.videoId == null ? null : session.cache.getFresh(item.videoId);
        java.util.List<String> defs = new java.util.ArrayList<>();
        if (model != null) {
            java.util.LinkedHashSet<String> raw = new java.util.LinkedHashSet<>();
            for (com.fongmi.android.tv.hongguo.model.HgVideoEntry e : model.getEntries()) {
                if (e.isPlayableCodec()) raw.add(e.getDefinition());
            }
            defs = HgPanelLogic.orderedDefinitions(raw);
        }
        if (defs.size() < 2) {
            android.widget.Toast.makeText(this, "当前只有一档清晰度",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String current = session.resolver.currentDefinition(item.videoId);
        if (current == null || !defs.contains(current)) {
            com.fongmi.android.tv.hongguo.model.HgVideoEntry first =
                    model == null ? null : model.firstPlayableEntry();
            current = first == null ? defs.get(0) : first.getDefinition();
        }
        final String currentDef = current;
        final java.util.List<String> defsF = defs;
        showLandDrawer("清晰度", (rows, dialog) -> {
            for (String def : defsF) {
                boolean on = def.equals(currentDef);
                TextView row = landDrawerRow((on ? "✓ " : "") + HgPanelLogic.definitionLabel(def), on);
                row.setOnClickListener(v -> {
                    dialog.dismiss();
                    if (!def.equals(currentDef)) switchDefinition(def, item);
                });
                rows.addView(row);
            }
        });
    }

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
        // 初始方向按最近一次物理朝向（横持另一边就 +90°，别把画面转倒）
        landRotation = lastOrientationDeg >= 240 && lastOrientationDeg <= 300 ? 90f : -90f;
        root.setRotation(landRotation);
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
        landRotation = -90f;
        binding.getRoot().animate().cancel();
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
        if (mode == MODE_FEED) {
            binding.hgBottomBar.setVisibility(View.VISIBLE);
            setPagerBottomMargin(56);
        }
        setChromeHidden(false);
        armAutoHide();
    }

    @Override
    public void onRetry(int position) {
        if (position != currentPos) return;
        autoSkipCount = 0; // 用户手动重试：连跳预算重新给
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
        // 回前台不重载（用户亲点「切到后台，再进去，短剧又重新加载」）：
        // onPause 只暂停播放器、onStop 只置标记，会话与播放器都没拆——
        // 旧实现却在回前台无条件 stopPlayback＋loadTab 回第一页，纯属自找。
        // 现口径：原地续播（onPause 暂停的原样放回去）；播放器异常不在场
        // 时才对当前条目走一次起播兜底。想看新一批有下拉刷新/再点页签在。
        // 此口径覆盖此前「回前台吃新一批」的定罪账修法，冲突见汇总文件。
        if (!stoppedOnce) return;
        stoppedOnce = false;
        if (authorReturnRefresh) {
            authorReturnRefresh = false;
            HgFeedAdapter.Holder h = currentHolder();
            if (h != null && currentPos >= 0 && currentPos < session.items.size()) {
                adapter.bindInteractionState(h, session.items.get(currentPos));
            }
        }
        suppressResumeRefresh = false;
        if (mineShowing) return;
        maybeCheckSubscribeRecall();
        if (player != null && playingVideoId != null) {
            player.play();
        } else if (currentPos >= 0 && currentPos < session.items.size()) {
            startCurrentPlayback();
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
        if (landInstance) {
            // 横屏页屏上无返回钮、系统返回即退回竖屏实例（官方同口径）：
            // 不被「唤 chrome」等分支截留
            super.onBackInvoked();
            return;
        }
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


    // ---------- 预约上线召回弹窗（官方 SubscribeRecallDialog 的自家链） ----------
    // 官方由浮层任务（show_subscribe_recall_dialog）在刷剧面弹，数据是
    // 服务端按新上线下发的；咱家无浮层任务链，进刷剧面时自拉预约已上
    // 线档差集等价：首轮只播种不弹（存量已上线不是新上线），此后新进
    // 已上线档的才弹，弹过记名不再重复（判定 HgUpdateLogic、记名
    // HgStore）。触发点＝首进延后一拍＋每次回前台（onResume）；集流
    // 实例与「我的」面在场时不查不弹。失败静默，不打扰刷剧。

    private boolean recallChecking;

    private void maybeCheckSubscribeRecall() {
        if (mode != MODE_FEED || mineShowing || recallChecking || isFinishing()) return;
        if (!LoginManager.isHongguoLoggedIn()) return;
        recallChecking = true;
        session.client.fetchMyReservations(true, new HgCallback<List<HgSeriesItem>>() {
            @Override
            public void onSuccess(List<HgSeriesItem> list) {
                runOnUiThread(() -> {
                    recallChecking = false;
                    if (list == null || isFinishing() || isDestroyed()) return;
                    java.util.List<String> onlineIds = new java.util.ArrayList<>();
                    java.util.Map<String, HgSeriesItem> byId = new HashMap<>();
                    for (HgSeriesItem s : list) {
                        if (s.getSeriesId() == null) continue;
                        onlineIds.add(s.getSeriesId());
                        byId.put(s.getSeriesId(), s);
                    }
                    if (!HgStore.recallSeeded()) {
                        HgStore.addRecallPrompted(onlineIds);
                        HgStore.markRecallSeeded();
                        return;
                    }
                    List<String> freshIds = HgUpdateLogic.newOnlineArrivals(
                            onlineIds, HgStore.recallPromptedIds());
                    if (freshIds.isEmpty()) return;
                    // 先记名再弹：弹的过程被杀也不会下次重弹同一批
                    HgStore.addRecallPrompted(freshIds);
                    List<HgSeriesItem> fresh = new java.util.ArrayList<>();
                    for (String id : freshIds) fresh.add(byId.get(id));
                    if (mode != MODE_FEED || mineShowing || isFinishing() || isDestroyed()) return;
                    HgSubscribeRecallDialog.show(HongguoFeedActivity.this, fresh,
                            new HgSubscribeRecallDialog.Listener() {
                                @Override
                                public void onOpenSeries(HgSeriesItem item) {
                                    suppressResumeRefresh = true;
                                    HongguoFeedActivity.startSeries(HongguoFeedActivity.this,
                                            item.getSeriesId(), item.getTitle(), item.getCoverUrl());
                                }

                                @Override
                                public void onOpenReservations() {
                                    binding.hgMine.getRoot().openReservations();
                                    showMineFace();
                                }
                            });
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                runOnUiThread(() -> recallChecking = false);
            }
        });
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
        danmaku.setOnCheckedChangeListener((b, on) -> applyDanmakuSwitch(on));
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

    /** 系统导航区真实高度（手势/三键都读 insets，不写死数值）；底栏让位与 pager 边距联动用。 */
    private int navBottomPx;

    /**
     * 顶栏/抽屉/「我的」面的顶部留距按状态栏真实高度走 insets，不写死数值（他亲点）。
     * 底部同规（2026-10-28 他立「所有页面都要给系统手势让位」）：自家底栏按导航区
     * 真实高度抬起——栏高＝56dp＋导航高、内距底＝导航高（内容盒仍是 56dp），
     * pager 底边距随栏的实际占位（56dp＋导航高）联动，不被手势横杠压住。
     */
    private void applyStatusBarInsets() {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(getWindow().getDecorView(), (v, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            int nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            if (nav != navBottomPx) {
                navBottomPx = nav;
                ViewGroup.LayoutParams blp = binding.hgBottomBar.getLayoutParams();
                int barH = Math.round(56 * getResources().getDisplayMetrics().density) + nav;
                if (blp.height != barH) {
                    blp.height = barH;
                    binding.hgBottomBar.setLayoutParams(blp);
                }
                if (binding.hgBottomBar.getPaddingBottom() != nav) {
                    binding.hgBottomBar.setPadding(binding.hgBottomBar.getPaddingLeft(),
                            binding.hgBottomBar.getPaddingTop(),
                            binding.hgBottomBar.getPaddingRight(), nav);
                }
                if (binding.hgBottomBar.getVisibility() == View.VISIBLE) setPagerBottomMargin(56);
                // 全屏极简控制条（竖屏全屏唤出条）同规让位：底内距＝原 6dp＋导航高
                int fsBottom = Math.round(6 * getResources().getDisplayMetrics().density) + nav;
                if (binding.hgFsBar.getPaddingBottom() != fsBottom) {
                    binding.hgFsBar.setPadding(binding.hgFsBar.getPaddingLeft(),
                            binding.hgFsBar.getPaddingTop(),
                            binding.hgFsBar.getPaddingRight(), fsBottom);
                }
                // 横屏控制条同规：条高＝40dp＋导航高、底内距＝导航高（内层内容盒仍 40dp）
                ViewGroup.LayoutParams llp = binding.hgLandBar.getLayoutParams();
                int landH = Math.round(40 * getResources().getDisplayMetrics().density) + nav;
                if (llp.height != landH) {
                    llp.height = landH;
                    binding.hgLandBar.setLayoutParams(llp);
                }
                if (binding.hgLandBar.getPaddingBottom() != nav) {
                    binding.hgLandBar.setPadding(binding.hgLandBar.getPaddingLeft(),
                            binding.hgLandBar.getPaddingTop(),
                            binding.hgLandBar.getPaddingRight(), nav);
                }
                // 「我的」面底边距跟底栏实际占位走：56dp＋导航高（旧写死 50dp 比底栏还矮 6dp、且不让位）
                ViewGroup.MarginLayoutParams mlp =
                        (ViewGroup.MarginLayoutParams) binding.hgMine.getRoot().getLayoutParams();
                int mineBottom = Math.round(56 * getResources().getDisplayMetrics().density) + nav;
                if (mlp.bottomMargin != mineBottom) {
                    mlp.bottomMargin = mineBottom;
                    binding.hgMine.getRoot().setLayoutParams(mlp);
                }
                // 锁定钮水平边距＝屏宽×11%（官方 P5() 定版 (屏宽×0.22)/2；旧写死 10dp 作废）
                ViewGroup.MarginLayoutParams klp =
                        (ViewGroup.MarginLayoutParams) binding.hgLandLock.getLayoutParams();
                int lockMargin = (int) (getResources().getDisplayMetrics().widthPixels * 0.11f);
                if (klp.leftMargin != lockMargin) {
                    klp.leftMargin = lockMargin;
                    binding.hgLandLock.setLayoutParams(klp);
                }
            }
            ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) binding.hgTopBar.getLayoutParams();
            if (lp.topMargin != top) {
                lp.topMargin = top;
                binding.hgTopBar.setLayoutParams(lp);
            }
            ViewGroup.MarginLayoutParams slp =
                    (ViewGroup.MarginLayoutParams) binding.hgSeriesBar.getLayoutParams();
            if (slp.topMargin != top) {
                slp.topMargin = top;
                binding.hgSeriesBar.setLayoutParams(slp);
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
        // dp>0 是给底栏让位：底栏实际占位＝56dp＋导航高，pager 边距跟到栏顶
        int px = Math.round(dp * getResources().getDisplayMetrics().density)
                + (dp > 0 ? navBottomPx : 0);
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
        // 开关显示按 HgStore 当前值重刷（sweep 必修3）：弹幕状态在抽屉外
        // 没有第二个写入口，只在 initView 设一次会显示旧值、照着拨会拨反。
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
        if (orientationListener != null) orientationListener.disable();
        ui.removeCallbacks(ticker);
        ui.removeCallbacks(timerFireRunnable);
        ui.removeCallbacks(hideChromeRunnable);
        ui.removeCallbacks(hideFsRunnable);
        ui.removeCallbacks(hideRefreshHintRunnable);
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
