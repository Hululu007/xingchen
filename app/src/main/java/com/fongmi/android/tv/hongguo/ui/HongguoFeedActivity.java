package com.fongmi.android.tv.hongguo.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
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
import androidx.core.view.WindowCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.HongguoDanmakuApi;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.hongguo.api.HgApiClient;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.drama.DramaCache;
import com.fongmi.android.tv.hongguo.drama.DramaClient;
import com.fongmi.android.tv.hongguo.drama.DramaDetail;
import com.fongmi.android.tv.hongguo.drama.DramaItem;
import com.fongmi.android.tv.hongguo.drama.DramaItemKind;
import com.fongmi.android.tv.hongguo.drama.DramaResolver;
import com.fongmi.android.tv.hongguo.drama.DramaVideoModel;
import com.fongmi.android.tv.hongguo.model.HgContinuation;
import com.fongmi.android.tv.hongguo.ui.HgPanelLogic;
import com.fongmi.android.tv.player.PlayerEngine;
import com.fongmi.android.tv.player.engine.ExoPlayerEngine;
import com.fongmi.android.tv.setting.PlayerSetting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 红果竖屏流播放页（2026-10-30 从零重建）：刷剧频道流（真人剧/
 * 漫剧两大类，他亲令）与单剧集流（MODE_SERIES，详情/搜索/我的页进入）共用一页。
 *
 * <p>形态（自主设计，主流短剧 App 口径）：全屏竖滑 Pager＋单 PlayerView
 * 随页迁移；信息摞在左下（剧名/作者/简介/收藏药丸＋选集条），右列
 * 作者头像/收藏/评论/全屏；右下清屏钮（按下全隐只剩它且变淡）；
 * 顶部频道页签＋搜索，底部「首页/我的」两格（他定案）。手势：单击
 * 播放/暂停、长按中部开更多面板、长按其余处 2 倍速（松手恢复）。
 * 弹幕走 PlayerView 自带层（数据源 HongguoDanmakuApi 落盘 XML），
 * 「发弹幕」入口在左下药丸。</p>
 *
 * <p>数据：频道/详情走 DramaClient（共享优先＋冷却降级）；解链走
 * DramaResolver；历史/断点/收藏走 HgStore 本地。无登录/追更/点赞/
 * 预约/关注任何入口。</p>
 */
public class HongguoFeedActivity extends AppCompatActivity {

    public static final String EXTRA_MODE = "hg_mode";
    public static final String EXTRA_SERIES_ID = "hg_series_id";
    public static final String EXTRA_TITLE = "hg_title";
    public static final String EXTRA_EPISODE = "hg_episode";
    public static final int MODE_FEED = 0;
    public static final int MODE_SERIES = 1;
    public static final int MODE_UGC = 2;
    public static final String EXTRA_UGC_VID = "hg_ugc_vid";
    public static final String EXTRA_UGC_SERIES = "hg_ugc_series";

    private static final int[][] CHANNELS = {{39, 39}, {36, 36}};
    private static final String[] CHANNEL_NAMES = {"真人剧", "漫剧"};

    public static void start(Context context) {
        context.startActivity(new Intent(context, HongguoFeedActivity.class));
    }

    /** UGC 单条直开（作者作品/动态条目）：一页一条，播 item 自带 vid。 */
    public static void startUgc(Context context, String vid, String seriesId, String title,
                                String cover, int contentType) {
        Intent intent = new Intent(context, HongguoFeedActivity.class);
        intent.putExtra(EXTRA_MODE, MODE_UGC);
        intent.putExtra(EXTRA_UGC_VID, vid);
        intent.putExtra(EXTRA_UGC_SERIES, seriesId);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_COVER, cover);
        intent.putExtra(EXTRA_CONTENT_TYPE, contentType);
        context.startActivity(intent);
    }

    public static final String EXTRA_COVER = "hg_cover";
    public static final String EXTRA_CONTENT_TYPE = "hg_content_type";

    public static void startSeries(Context context, String seriesId, String title, int episodeIndex) {
        Intent intent = new Intent(context, HongguoFeedActivity.class);
        intent.putExtra(EXTRA_MODE, MODE_SERIES);
        intent.putExtra(EXTRA_SERIES_ID, seriesId);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_EPISODE, episodeIndex);
        context.startActivity(intent);
    }

    // ---------- 视图 ----------
    private FrameLayout root;
    private RecyclerView pager;
    private FeedAdapter adapter;
    private PlayerView playerView;
    private View topBar;
    private TextView[] tabViews;
    private View bottomBar;
    private View seriesBar;
    private TextView seriesTitleView;
    private View progressWrap;
    private View progressFill;
    private TextView cleanBtn;
    private TextView boostPill;
    private ImageView centerFlash;
    private TextView danmakuPill;
    private View stateView;
    private TextView stateText;
    private ProgressBar stateSpin;
    private View drawerScrim;
    private View drawerPanel;

    // ---------- 播放 ----------
    private ExoPlayerEngine engine;
    private Player player;
    private final DramaResolver resolver = new DramaResolver();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "hg-feed");
        t.setDaemon(true);
        return t;
    });

    // ---------- 会话 ----------
    private int mode = MODE_FEED;
    private int channel = 0;
    private final List<DramaItem> items = new ArrayList<>();
    private final Map<String, DramaDetail> detailCache = new HashMap<>();
    private HgContinuation continuation;
    private boolean hasMore;
    private boolean loadingPage;
    private int currentIndex = -1;
    private String currentVid;
    private String currentSeriesId;
    private int currentEpisodeIndex = 1;
    private String seriesIdArg;
    private List<DramaDetail.Episode> episodes = new ArrayList<>();
    private boolean cleanMode;
    private boolean boosting;
    private boolean prefetchedNext;
    private long lastSaveMs;
    private String danmakuVid;
    private final List<androidx.media3.ui.danmaku.Danmaku> danmakuItems = new ArrayList<>();
    private boolean firstFrameForVid;
    private boolean feedStartPending = true;
    // 横屏交接（阶段8）：泊出旗/列表静止旗/最近一集画幅/弹窗守卫/朝向监视
    private boolean engineParked;
    private boolean pagerIdle = true;
    private int lastVideoW;
    private int lastVideoH;
    private long panelGuardUntil;
    private OrientationEventListener orientationListener;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        mode = getIntent().getIntExtra(EXTRA_MODE, MODE_FEED);
        seriesIdArg = getIntent().getStringExtra(EXTRA_SERIES_ID);
        buildUi();
        setContentView(root);
        if (mode == MODE_SERIES || mode == MODE_UGC) {
            topBar.setVisibility(View.GONE);
            bottomBar.setVisibility(View.GONE);
            progressWrap.setVisibility(View.VISIBLE);
            seriesBar.setVisibility(View.VISIBLE);
            String t = getIntent().getStringExtra(EXTRA_TITLE);
            seriesTitleView.setText(t == null ? "" : t);
            if (mode == MODE_SERIES) loadSeries();
            else loadUgc();
        } else {
            seriesBar.setVisibility(View.GONE);
            channel = tabIndexOf(HgStore.feedPosTab());
            selectTab(channel);
            loadChannel(true);
        }
        main.post(ticker);
        orientationListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (DramaLandLogic.orientationBand(orientation) == 1) enterLand(false);
            }
        };
    }

    // ---------- 横屏交接（阶段8） ----------

    /**
     * 进横屏：横版门（条目 vertical 旗优先、未知时看画幅）＋总闸
     * （DramaLandLogic.canEnterLand）双过，才把播放器泊进
     * DramaPlayerPool 并拉起独立横屏页同流续播。无剧短片不进横屏。
     */
    private void enterLand(boolean fromButton) {
        if (engineParked || player == null || currentVid == null) return;
        if (currentIndex < 0 || currentIndex >= items.size()) return;
        DramaItem item = items.get(currentIndex);
        if (item.kind == DramaItemKind.UGC_CLIP) return;
        boolean land;
        if (Boolean.FALSE.equals(item.getVertical())) {
            land = true;
        } else if (Boolean.TRUE.equals(item.getVertical())) {
            return;
        } else {
            land = DramaLandLogic.isLandEligible(lastVideoW, lastVideoH);
        }
        if (!land) return;
        boolean popup = (drawerPanel != null && drawerPanel.getVisibility() == View.VISIBLE)
                || System.currentTimeMillis() < panelGuardUntil;
        if (!DramaLandLogic.canEnterLand(pagerIdle, firstFrameForVid, boosting,
                popup, fromButton)) return;
        DramaPlayerPool.Bag bag = new DramaPlayerPool.Bag();
        bag.seriesId = currentSeriesId != null ? currentSeriesId : item.getSeriesId();
        bag.vid = currentVid;
        bag.episodeIndex = currentEpisodeIndex;
        bag.positionMs = player.getCurrentPosition();
        bag.playWhenReady = player.getPlayWhenReady();
        bag.danmakuOn = HgStore.isDanmakuOn();
        bag.speed = player.getPlaybackParameters().speed;
        bag.seriesTitle = item.getTitle();
        bag.contentType = item.contentType;
        saveProgressNow();
        player.removeListener(playerListener);
        playerView.setPlayer(null);
        DramaPlayerPool.park(bag, engine);
        engineParked = true;
        startActivity(new Intent(this, HongguoLandActivity.class));
    }

    /** 横屏页退回后领回播放器：按 DramaLandLogic.reclaimDecision 对位。 */
    private void reclaimFromLand() {
        engineParked = false;
        DramaPlayerPool.Bag bag = DramaPlayerPool.peekBag();
        DramaItem item = currentIndex >= 0 && currentIndex < items.size()
                ? items.get(currentIndex) : null;
        String ctxSeries = mode == MODE_SERIES ? seriesIdArg
                : (item == null ? null : item.getSeriesId());
        int decision = bag == null ? DramaLandLogic.RECLAIM_MISMATCH
                : DramaLandLogic.reclaimDecision(mode == MODE_SERIES, bag.seriesId,
                        ctxSeries, bag.vid, currentVid, bag.episodeIndex,
                        currentEpisodeIndex);
        if (decision == DramaLandLogic.RECLAIM_MISMATCH) {
            DramaPlayerPool.releaseParked();
            engine = null;
            player = null;
            if (currentVid != null && item != null) {
                playVid(currentVid, item.contentType, 0);
            }
            return;
        }
        engine = DramaPlayerPool.takeEngine();
        player = engine == null ? null : engine.getPlayer();
        if (player == null) {
            if (currentVid != null && item != null) {
                playVid(currentVid, item.contentType, 0);
            }
            return;
        }
        player.addListener(playerListener);
        playerView.setPlayer(player);
        applyDanmakuVisibility();
        if (decision == DramaLandLogic.RECLAIM_SERIES_JUMP) {
            playEpisodeFromSheet(bag.episodeIndex);
        } else if (decision == DramaLandLogic.RECLAIM_FEED_DIFF) {
            if (item != null) playVid(currentVid, item.contentType, 0);
        } else {
            player.setPlayWhenReady(bag.playWhenReady);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (engineParked) reclaimFromLand();
        if (orientationListener != null) orientationListener.enable();
    }

    private int tabIndexOf(String tabName) {
        if (tabName != null) {
            for (int i = 0; i < CHANNEL_NAMES.length; i++) {
                if (CHANNEL_NAMES[i].equals(tabName)) return i;
            }
        }
        return 0;
    }

    // ---------- UI 构建 ----------

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        pager = new RecyclerView(this);
        pager.setLayoutManager(new LinearLayoutManager(this, RecyclerView.VERTICAL, false));
        adapter = new FeedAdapter();
        pager.setAdapter(adapter);
        PagerSnapHelper snap = new PagerSnapHelper();
        snap.attachToRecyclerView(pager);
        pager.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                pagerIdle = state == RecyclerView.SCROLL_STATE_IDLE;
                if (state == RecyclerView.SCROLL_STATE_IDLE) {
                    View v = snap.findSnapView(pager.getLayoutManager());
                    if (v != null) settleTo(pager.getChildAdapterPosition(v));
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy > 0 && !loadingPage && hasMore && currentIndex >= items.size() - 3) {
                    loadChannel(false);
                }
            }
        });
        root.addView(pager, match());

        playerView = new PlayerView(this);
        playerView.setUseController(false);
        playerView.setShutterBackgroundColor(Color.BLACK);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setDanmakuEnabled(HgStore.isDanmakuOn());
        playerView.getDanmakuController().setConfig(danmakuConfig());

        buildTopBar();
        buildSeriesBar();
        buildBottomBar();
        buildOverlays();
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(-1, -1);
    }

    private GradientDrawable scrim(boolean top) {
        return new GradientDrawable(
                top ? GradientDrawable.Orientation.TOP_BOTTOM : GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0x99000000, 0x00000000});
    }

    private void buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(scrim(true));
        int sb = statusBarHeight();
        bar.setPadding(dp(6), sb, dp(6), 0);

        ImageView menu = iconBtn(R.drawable.ic_drama_menu, v -> openDrawer());
        bar.addView(menu, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER);
        tabViews = new TextView[CHANNEL_NAMES.length];
        for (int i = 0; i < CHANNEL_NAMES.length; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(CHANNEL_NAMES[i]);
            t.setTextSize(16);
            t.setPadding(dp(12), dp(6), dp(12), dp(6));
            t.setOnClickListener(v -> {
                if (channel != idx) {
                    saveFeedPos();
                    channel = idx;
                    selectTab(idx);
                    loadChannel(true);
                }
            });
            tabViews[i] = t;
            tabs.addView(t);
        }
        bar.addView(tabs, new LinearLayout.LayoutParams(0, -2, 1f));

        ImageView search = iconBtn(R.drawable.ic_drama_search,
                v -> startActivity(new Intent(this, HongguoSearchActivity.class)));
        bar.addView(search, new LinearLayout.LayoutParams(dp(40), dp(40)));

        topBar = bar;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, dp(52) + sb);
        lp.gravity = Gravity.TOP;
        root.addView(bar, lp);
    }

    private void buildSeriesBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(scrim(true));
        bar.setPadding(dp(6), statusBarHeight(), dp(6), 0);
        ImageView back = iconBtn(R.drawable.ic_drama_back, v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));
        seriesTitleView = new TextView(this);
        seriesTitleView.setTextColor(Color.WHITE);
        seriesTitleView.setTextSize(16);
        seriesTitleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        seriesTitleView.setMaxLines(1);
        seriesTitleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        seriesTitleView.setShadowLayer(dp(2), 0, dp(1), 0x99000000);
        bar.addView(seriesTitleView, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView more = iconBtn(R.drawable.ic_drama_more, v -> showMorePanel());
        bar.addView(more, new LinearLayout.LayoutParams(dp(40), dp(40)));
        seriesBar = bar;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, dp(52) + statusBarHeight());
        lp.gravity = Gravity.TOP;
        root.addView(bar, lp);
    }

    private void buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xE6121214);
        bar.setPadding(0, 0, 0, navBarHeight());
        LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, dp(52), 1f);
        bar.addView(bottomCell(R.drawable.ic_drama_home, "首页", true, null), cell);
        bar.addView(bottomCell(R.drawable.ic_drama_theater, "剧场", false,
                v -> startActivity(new Intent(this, HongguoTheaterActivity.class))), cell);
        bottomBar = bar;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, dp(52) + navBarHeight());
        lp.gravity = Gravity.BOTTOM;
        root.addView(bar, lp);
    }

    private View bottomCell(int icon, String label, boolean selected, View.OnClickListener click) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(selected ? 0xFFFF5A4E : 0xFFBDBDBD);
        c.addView(iv, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(10);
        t.setTextColor(selected ? 0xFFFF5A4E : 0xFFBDBDBD);
        c.addView(t);
        if (click != null) c.setOnClickListener(click);
        return c;
    }

    private void buildOverlays() {
        // 底部进度线（集流贴底、频道流在底栏上沿）
        FrameLayout track = new FrameLayout(this);
        View bg = new View(this);
        bg.setBackgroundColor(0x55FFFFFF);
        track.addView(bg, match());
        progressFill = new View(this);
        progressFill.setBackgroundColor(0xFFFF5A4E);
        track.addView(progressFill, new FrameLayout.LayoutParams(0, -1));
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-1, dp(2.5f));
        tlp.gravity = Gravity.BOTTOM;
        tlp.bottomMargin = (mode == MODE_FEED ? dp(52) : 0) + navBarHeight();
        root.addView(track, tlp);
        progressWrap = track;

        // 清屏钮（右下）：按下清屏、全隐只剩它且变淡
        cleanBtn = new TextView(this);
        cleanBtn.setBackground(roundBg(0x66000000, dp(10)));
        cleanBtn.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_drama_exitfull, 0, 0, 0);
        cleanBtn.setGravity(Gravity.CENTER);
        cleanBtn.setPadding(dp(9), dp(9), dp(9), dp(9));
        cleanBtn.setOnClickListener(v -> setCleanMode(!cleanMode));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(42), dp(42));
        clp.gravity = Gravity.END | Gravity.BOTTOM;
        clp.rightMargin = dp(12);
        clp.bottomMargin = dp(148) + navBarHeight() + (mode == MODE_FEED ? dp(52) : 0);
        root.addView(cleanBtn, clp);

        // 倍速胶囊
        boostPill = new TextView(this);
        boostPill.setText("2.0x 快进中");
        boostPill.setTextColor(Color.WHITE);
        boostPill.setTextSize(12);
        boostPill.setBackground(roundBg(0x88000000, dp(14)));
        boostPill.setPadding(dp(12), dp(5), dp(12), dp(5));
        boostPill.setVisibility(View.GONE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2);
        blp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        blp.topMargin = dp(64) + statusBarHeight();
        root.addView(boostPill, blp);

        // 中央播放/暂停闪标
        centerFlash = new ImageView(this);
        centerFlash.setColorFilter(0xE6FFFFFF);
        centerFlash.setBackground(roundBg(0x59000000, dp(32)));
        centerFlash.setPadding(dp(14), dp(14), dp(14), dp(14));
        centerFlash.setVisibility(View.GONE);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(dp(64), dp(64));
        flp.gravity = Gravity.CENTER;
        root.addView(centerFlash, flp);

        // 发弹幕药丸（左下）
        danmakuPill = new TextView(this);
        danmakuPill.setText("发弹幕…");
        danmakuPill.setTextColor(0xDDFFFFFF);
        danmakuPill.setTextSize(12.5f);
        danmakuPill.setBackground(roundBg(0x59000000, dp(16)));
        danmakuPill.setPadding(dp(14), dp(7), dp(14), dp(7));
        danmakuPill.setOnClickListener(v -> HgFeedPanels.showDanmakuSend(this, host()));
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(-2, -2);
        dlp.gravity = Gravity.START | Gravity.BOTTOM;
        dlp.leftMargin = dp(14);
        dlp.bottomMargin = dp(104) + navBarHeight() + (mode == MODE_FEED ? dp(52) : 0);
        root.addView(danmakuPill, dlp);
        danmakuPill.setVisibility(HgStore.isDanmakuOn() ? View.VISIBLE : View.GONE);

        // 整页状态（加载/失败/空，点按重试）
        LinearLayout state = new LinearLayout(this);
        state.setOrientation(LinearLayout.VERTICAL);
        state.setGravity(Gravity.CENTER);
        stateSpin = new ProgressBar(this);
        state.addView(stateSpin);
        stateText = new TextView(this);
        stateText.setTextColor(0xFFCCCCCC);
        stateText.setTextSize(14);
        stateText.setGravity(Gravity.CENTER);
        stateText.setPadding(dp(24), dp(12), dp(24), 0);
        state.addView(stateText);
        state.setVisibility(View.GONE);
        state.setOnClickListener(v -> {
            if (items.isEmpty()) {
                if (mode == MODE_SERIES) loadSeries();
                else loadChannel(true);
            }
        });
        stateView = state;
        root.addView(state, match());

        buildDrawer();
    }

    private void buildDrawer() {
        drawerScrim = new View(this);
        drawerScrim.setBackgroundColor(0x66000000);
        drawerScrim.setVisibility(View.GONE);
        drawerScrim.setOnClickListener(v -> closeDrawer());
        root.addView(drawerScrim, match());

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xFF1C1C1E);
        panel.setPadding(dp(20), dp(24) + statusBarHeight(), dp(20), dp(20));
        TextView head = new TextView(this);
        head.setText("红果短剧");
        head.setTextColor(Color.WHITE);
        head.setTextSize(18);
        head.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        panel.addView(head);
        TextView mineRow = new TextView(this);
        mineRow.setText("我的  ›");
        mineRow.setTextColor(0xFFEEEEEE);
        mineRow.setTextSize(15.5f);
        mineRow.setPadding(0, dp(16), 0, dp(4));
        mineRow.setOnClickListener(v -> {
            closeDrawer();
            startActivity(new Intent(this, HongguoMineActivity.class));
        });
        panel.addView(mineRow);
        panel.addView(drawerSwitch("启动直达短剧", HgStore.isLaunchDirect(),
                on -> HgStore.setLaunchDirect(on)));
        panel.addView(drawerSwitch("弹幕", HgStore.isDanmakuOn(), on -> {
            HgStore.setDanmakuOn(on);
            applyDanmakuVisibility();
        }));
        panel.addView(drawerSwitch("自动连播", HgStore.isAutoNext(),
                on -> HgStore.setAutoNext(on)));
        panel.addView(drawerAction("刷新设备档案", v -> {
            closeDrawer();
            HgApiClient.getInstance().resetDeviceProfile(new HgCallback<Boolean>() {
                @Override
                public void onSuccess(Boolean ok) {
                    runOnUiThread(() -> Toast.makeText(HongguoFeedActivity.this,
                            "设备档案已刷新", Toast.LENGTH_SHORT).show());
                }

                @Override
                public void onError(int code, String msg) {
                    runOnUiThread(() -> Toast.makeText(HongguoFeedActivity.this,
                            "刷新失败，请重试", Toast.LENGTH_SHORT).show());
                }
            });
        }));
        panel.addView(drawerAction("清红果缓存", v -> {
            DramaCache.clear();
            closeDrawer();
            Toast.makeText(this, "缓存已清", Toast.LENGTH_SHORT).show();
        }));
        drawerPanel = panel;
        panel.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(260), -1);
        lp.gravity = Gravity.START;
        root.addView(panel, lp);
    }

    private interface BoolSetter {
        void set(boolean on);
    }

    private View drawerSwitch(String label, boolean initial, BoolSetter setter) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(14), 0, dp(14));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(0xFFEEEEEE);
        t.setTextSize(15);
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        android.widget.Switch sw = new android.widget.Switch(this);
        sw.setChecked(initial);
        sw.setOnCheckedChangeListener((b, on) -> setter.set(on));
        row.addView(sw);
        return row;
    }

    private View drawerAction(String label, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(0xFFEEEEEE);
        t.setTextSize(15);
        t.setPadding(0, dp(14), 0, dp(14));
        t.setOnClickListener(click);
        return t;
    }

    private void openDrawer() {
        drawerScrim.setVisibility(View.VISIBLE);
        drawerPanel.setVisibility(View.VISIBLE);
    }

    private void closeDrawer() {
        drawerScrim.setVisibility(View.GONE);
        drawerPanel.setVisibility(View.GONE);
    }

    private ImageView iconBtn(int res, View.OnClickListener click) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setColorFilter(Color.WHITE);
        iv.setPadding(dp(8), dp(8), dp(8), dp(8));
        iv.setOnClickListener(click);
        return iv;
    }

    private GradientDrawable roundBg(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private int navBarHeight() {
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private void selectTab(int idx) {
        for (int i = 0; i < tabViews.length; i++) {
            boolean on = i == idx;
            tabViews[i].setTextColor(on ? Color.WHITE : 0xAAFFFFFF);
            tabViews[i].setTypeface(on ? android.graphics.Typeface.DEFAULT_BOLD
                    : android.graphics.Typeface.DEFAULT);
            tabViews[i].setTextSize(on ? 17 : 15.5f);
        }
    }

    private void showState(String text, boolean spin) {
        stateText.setText(text);
        stateSpin.setVisibility(spin ? View.VISIBLE : View.GONE);
        stateView.setVisibility(View.VISIBLE);
    }

    private void hideState() {
        stateView.setVisibility(View.GONE);
    }

    // ---------- 数据 ----------

    private void loadChannel(boolean first) {
        if (loadingPage) return;
        if (first) {
            items.clear();
            adapter.notifyDataSetChanged();
            currentIndex = -1;
            continuation = null;
            hasMore = false;
            showState("加载中…", true);
        }
        loadingPage = true;
        if (first || continuation == null) {
            int[] ch = CHANNELS[channel];
            DramaClient.fetchChannel(ch[0], ch[1], new HgCallback<com.fongmi.android.tv.hongguo.drama.DramaCellPage>() {
                @Override
                public void onSuccess(com.fongmi.android.tv.hongguo.drama.DramaCellPage page) {
                    runOnUiThread(() -> onChannelPage(page, first));
                }

                @Override
                public void onError(int code, String msg) {
                    runOnUiThread(() -> {
                        loadingPage = false;
                        if (items.isEmpty()) showState("取数据失败，点按重试", false);
                    });
                }
            });
        } else {
            DramaClient.fetchChannelMore(continuation, new HgCallback<com.fongmi.android.tv.hongguo.drama.DramaCellPage>() {
                @Override
                public void onSuccess(com.fongmi.android.tv.hongguo.drama.DramaCellPage page) {
                    runOnUiThread(() -> onChannelPage(page, false));
                }

                @Override
                public void onError(int code, String msg) {
                    runOnUiThread(() -> loadingPage = false);
                }
            });
        }
    }

    private void onChannelPage(com.fongmi.android.tv.hongguo.drama.DramaCellPage page, boolean first) {
        loadingPage = false;
        hasMore = page.isHasMore();
        if (page.getContinuation() != null) continuation = page.getContinuation();
        else if (page.getNextOffset() != null && continuation != null) {
            continuation = new HgContinuation(continuation.getCellId(), page.getSessionId(),
                    continuation.getClientTemplate(), continuation.getShowType(),
                    continuation.getTabType(), page.getNextOffset());
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (DramaItem it : items) if (it.getSeriesId() != null) seen.add(it.getSeriesId() + "/" + it.getVid());
        int added = 0;
        for (DramaItem it : page.getItems()) {
            String key = it.getSeriesId() + "/" + it.getVid();
            if (it.getVid() == null && it.getSeriesId() == null) continue;
            if (!seen.add(key)) continue;
            items.add(it);
            added++;
        }
        adapter.notifyDataSetChanged();
        if (items.isEmpty()) {
            showState("暂时没有内容，点按重试", false);
            return;
        }
        hideState();
        if (first) {
            int start = HgStore.feedPosIndex(CHANNEL_NAMES[channel]);
            if (start < 0 || start >= items.size()) start = 0;
            feedStartPending = true;
            pager.scrollToPosition(start);
            main.post(() -> settleTo(start));
        }
    }

    private void loadUgc() {
        String vid = getIntent().getStringExtra(EXTRA_UGC_VID);
        String sid = getIntent().getStringExtra(EXTRA_UGC_SERIES);
        if (vid == null || vid.isEmpty()) {
            showState("内容不存在", false);
            return;
        }
        hideState();
        items.clear();
        items.add(DramaItem.fromDetail(sid == null ? vid : sid,
                getIntent().getStringExtra(EXTRA_TITLE),
                getIntent().getStringExtra(EXTRA_COVER), null, 0, vid, null,
                null, null, null, getIntent().getIntExtra(EXTRA_CONTENT_TYPE, 1001), -1));
        adapter.notifyDataSetChanged();
        feedStartPending = true;
        pager.scrollToPosition(0);
        main.post(() -> settleTo(0));
    }

    private void loadSeries() {
        showState("加载中…", true);
        if (seriesIdArg == null || seriesIdArg.isEmpty()) {
            showState("剧集不存在", false);
            return;
        }
        DramaClient.fetchDetail(seriesIdArg, new HgCallback<DramaDetail>() {
            @Override
            public void onSuccess(DramaDetail detail) {
                runOnUiThread(() -> {
                    detailCache.put(seriesIdArg, detail);
                    episodes = detail.getEpisodes();
                    items.clear();
                    String firstVid = episodes.isEmpty() ? null : episodes.get(0).getVideoId();
                    items.add(DramaItem.fromDetail(detail.getSeriesId(), detail.getTitle(),
                            detail.getCoverUrl(), detail.getDesc(), detail.getEpisodeCount(),
                            firstVid, detail.getSeriesStatus(), detail.getAuthorName(),
                            null, detail.getAuthorUid(), detail.getContentType(), -1));
                    adapter.notifyDataSetChanged();
                    hideState();
                    int startEp = getIntent().getIntExtra(EXTRA_EPISODE, 0);
                    if (startEp <= 0) {
                        HgStore.Progress p = HgStore.progressOf(seriesIdArg);
                        startEp = p != null ? p.episodeIndex : 1;
                    }
                    currentEpisodeIndex = Math.max(1, startEp);
                    feedStartPending = true;
                    pager.scrollToPosition(0);
                    main.post(() -> settleTo(0));
                });
            }

            @Override
            public void onError(int code, String msg) {
                runOnUiThread(() -> showState("取数据失败，点按重试", false));
            }
        });
    }

    private void saveFeedPos() {
        if (mode == MODE_FEED && currentIndex >= 0) {
            HgStore.saveFeedPos(CHANNEL_NAMES[channel], currentIndex);
        }
    }

    // ---------- 播放会话 ----------

    private void settleTo(int index) {
        if (index < 0 || index >= items.size()) return;
        if (index == currentIndex && currentVid != null && player != null) return;
        saveProgressNow();
        currentIndex = index;
        DramaItem item = items.get(index);
        currentSeriesId = item.getSeriesId();
        mountPlayerView(index);
        bindRailState(index);
        if (mode == MODE_SERIES) {
            playEpisode(currentEpisodeIndex, true);
        } else if (mode == MODE_UGC) {
            currentEpisodeIndex = 1;
            playVid(item.getVid(), item.contentType, 0);
        } else {
            playFeedItem(item);
        }
        prefetchNeighbors(index);
        if (mode == MODE_FEED) saveFeedPosDelayed();
    }

    private void saveFeedPosDelayed() {
        main.removeCallbacks(savePosRunnable);
        main.postDelayed(savePosRunnable, 600);
    }

    private final Runnable savePosRunnable = this::saveFeedPos;

    private void mountPlayerView(int index) {
        RecyclerView.ViewHolder vh = pager.findViewHolderForAdapterPosition(index);
        if (vh instanceof FeedAdapter.Holder) {
            FeedAdapter.Holder h = (FeedAdapter.Holder) vh;
            if (playerView.getParent() != null) {
                ((ViewGroup) playerView.getParent()).removeView(playerView);
            }
            h.surface.addView(playerView, match());
            h.cover.setVisibility(View.VISIBLE);
            h.spin.setVisibility(View.VISIBLE);
            h.fail.setVisibility(View.GONE);
        }
    }

    private FeedAdapter.Holder currentHolder() {
        RecyclerView.ViewHolder vh = pager.findViewHolderForAdapterPosition(currentIndex);
        return vh instanceof FeedAdapter.Holder ? (FeedAdapter.Holder) vh : null;
    }

    private void playFeedItem(DramaItem item) {
        String sid = item.getSeriesId();
        // 有断点先取详情定集（同集续播），无断点直接播条目自带 vid
        HgStore.Progress p = sid == null ? null : HgStore.progressOf(sid);
        if (p != null && p.episodeIndex > 1 && sid != null) {
            ensureDetail(sid, detail -> {
                if (detail != null && detail.getEpisodes().size() >= p.episodeIndex) {
                    currentEpisodeIndex = p.episodeIndex;
                    playVid(detail.getEpisodes().get(p.episodeIndex - 1).getVideoId(),
                            detail.getContentType(), p.positionMs);
                    return;
                }
                playVid(item.getVid(), item.contentType, 0);
            });
            return;
        }
        currentEpisodeIndex = 1;
        if (p != null && p.positionMs > 0 && item.getVid() != null) {
            playVid(item.getVid(), item.contentType, p.positionMs);
        } else {
            playVid(item.getVid(), item.contentType, 0);
        }
    }

    private void playEpisode(int episodeIndex, boolean resume) {
        if (episodes.isEmpty()) return;
        int idx = Math.min(Math.max(1, episodeIndex), episodes.size());
        currentEpisodeIndex = idx;
        DramaDetail.Episode ep = episodes.get(idx - 1);
        long seek = 0;
        if (resume && seriesIdArg != null) {
            HgStore.Progress p = HgStore.progressOf(seriesIdArg);
            if (p != null && p.episodeIndex == idx) seek = p.positionMs;
        }
        DramaDetail d = detailCache.get(seriesIdArg);
        playVid(ep.getVideoId(), d == null ? 0 : d.getContentType(), seek);
        refreshStrip();
    }

    public void playEpisodeFromSheet(int episodeIndex) {
        if (mode == MODE_SERIES) {
            playEpisode(episodeIndex, false);
        } else {
            // 频道流：当前条的选集——取详情后在本页切到该集播
            String sid = currentSeriesId;
            if (sid == null) return;
            ensureDetail(sid, detail -> {
                if (detail == null || detail.getEpisodes().size() < episodeIndex) return;
                episodes = detail.getEpisodes();
                currentEpisodeIndex = episodeIndex;
                playVid(detail.getEpisodes().get(episodeIndex - 1).getVideoId(),
                        detail.getContentType(), 0);
            });
        }
    }

    private interface DetailCb {
        void onDetail(DramaDetail detail);
    }

    private void ensureDetail(String seriesId, DetailCb cb) {
        DramaDetail cached = detailCache.get(seriesId);
        if (cached != null) {
            cb.onDetail(cached);
            return;
        }
        DramaClient.fetchDetail(seriesId, new HgCallback<DramaDetail>() {
            @Override
            public void onSuccess(DramaDetail detail) {
                runOnUiThread(() -> {
                    detailCache.put(seriesId, detail);
                    cb.onDetail(detail);
                });
            }

            @Override
            public void onError(int code, String msg) {
                runOnUiThread(() -> cb.onDetail(null));
            }
        });
    }

    private void ensurePlayer() {
        if (player != null) return;
        int decode = PlayerSetting.isVideoPrefer(PlayerSetting.EXO) ? PlayerEngine.HARD : PlayerEngine.SOFT;
        engine = new ExoPlayerEngine(decode, new Player.Listener() {
        });
        player = engine.getPlayer();
        player.addListener(playerListener);
        playerView.setPlayer(player);
        player.setPlaybackSpeed(HgStore.getSpeed());
    }

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_READY) {
                FeedAdapter.Holder h = currentHolder();
                if (h != null) h.spin.setVisibility(View.GONE);
            } else if (state == Player.STATE_ENDED) {
                onPlayEnded();
            }
        }

        @Override
        public void onRenderedFirstFrame() {
            FeedAdapter.Holder h = currentHolder();
            if (h != null) {
                h.cover.setVisibility(View.GONE);
                h.spin.setVisibility(View.GONE);
            }
            firstFrameForVid = true;
        }

        @Override
        public void onPlayerError(@NonNull androidx.media3.common.PlaybackException error) {
            FeedAdapter.Holder h = currentHolder();
            if (h != null) {
                h.spin.setVisibility(View.GONE);
                h.fail.setVisibility(View.VISIBLE);
            }
        }
    };

    private void playVid(String vid, int contentType, long seekMs) {
        if (vid == null || vid.isEmpty()) {
            FeedAdapter.Holder h = currentHolder();
            if (h != null) {
                h.spin.setVisibility(View.GONE);
                h.fail.setVisibility(View.VISIBLE);
            }
            return;
        }
        ensurePlayer();
        stopBoost();
        currentVid = vid;
        firstFrameForVid = false;
        prefetchedNext = false;
        danmakuVid = null;
        player.stop();
        resolver.resolve(vid, contentType, new DramaResolver.Listener() {
            @Override
            public void onReady(String videoId, String localUrl, int w, int h) {
                if (!videoId.equals(currentVid) || player == null) return;
                lastVideoW = w;
                lastVideoH = h;
                player.setMediaItem(MediaItem.fromUri(localUrl));
                playerView.setResizeMode(w >= h && h > 0
                        ? AspectRatioFrameLayout.RESIZE_MODE_FIT
                        : AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
                player.prepare();
                if (seekMs > 0) player.seekTo(seekMs);
                player.setPlayWhenReady(true);
                startDanmaku(videoId);
            }

            @Override
            public void onFailed(String videoId, String code, String message) {
                if (!videoId.equals(currentVid)) return;
                FeedAdapter.Holder h = currentHolder();
                if (h != null) {
                    h.spin.setVisibility(View.GONE);
                    h.fail.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    private void onPlayEnded() {
        saveProgressNow();
        if (!HgStore.isAutoNext()) return;
        if (mode == MODE_SERIES) {
            if (currentEpisodeIndex < episodes.size()) playEpisode(currentEpisodeIndex + 1, false);
        } else if (currentIndex + 1 < items.size()) {
            pager.smoothScrollToPosition(currentIndex + 1);
        }
    }

    private void prefetchNeighbors(int index) {
        List<DramaClient.ModelRequest> reqs = new ArrayList<>();
        for (int i = index + 1; i <= Math.min(index + 2, items.size() - 1); i++) {
            DramaItem it = items.get(i);
            if (it.getVid() != null) {
                reqs.add(new DramaClient.ModelRequest(it.getVid(), it.contentType));
            }
        }
        if (!reqs.isEmpty()) {
            DramaClient.fetchModels(reqs, new HgCallback<Map<String, DramaVideoModel>>() {
                @Override
                public void onSuccess(Map<String, DramaVideoModel> r) {
                }

                @Override
                public void onError(int c, String m) {
                }
            });
        }
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            main.postDelayed(this, 500);
            tick();
        }
    };

    private void tick() {
        if (player == null || currentVid == null) return;
        if (player.isPlaying()) watchAccumMs += 500;
        long pos = player.getCurrentPosition();
        long dur = player.getDuration();
        if (dur > 0) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) progressFill.getLayoutParams();
            int w = progressWrap.getWidth();
            lp.width = (int) (w * Math.min(1f, pos * 1f / dur));
            progressFill.setLayoutParams(lp);
            // 播中尾段预取下一集（isPlaying＋剩余≤30s 或进度≥85%，单次记账）
            if (player.isPlaying() && !prefetchedNext
                    && (dur - pos <= 30_000 || pos * 1f / dur >= 0.85f)) {
                prefetchedNext = true;
                prefetchNextEpisode();
            }
        }
        long now = System.currentTimeMillis();
        if (now - lastSaveMs > 5000) {
            lastSaveMs = now;
            saveProgressNow();
        }
        if (cleanMode) shiftCleanBtn();
    }

    private void prefetchNextEpisode() {
        if (mode == MODE_SERIES) {
            if (currentEpisodeIndex < episodes.size()) {
                DramaDetail d = detailCache.get(seriesIdArg);
                resolver.warm(episodes.get(currentEpisodeIndex).getVideoId(),
                        d == null ? 0 : d.getContentType());
            }
        } else if (currentSeriesId != null) {
            ensureDetail(currentSeriesId, detail -> {
                if (detail == null) return;
                if (currentEpisodeIndex < detail.getEpisodes().size()) {
                    resolver.warm(detail.getEpisodes().get(currentEpisodeIndex).getVideoId(),
                            detail.getContentType());
                }
            });
        }
    }

    private void saveProgressNow() {
        if (player == null || currentSeriesId == null || currentVid == null) return;
        long pos = Math.max(0, player.getCurrentPosition());
        if (pos <= 0) return;
        DramaItem item = currentIndex >= 0 && currentIndex < items.size() ? items.get(currentIndex) : null;
        if (item != null) {
            HgStore.putMeta(currentSeriesId, item.getTitle(), item.getCoverUrl(),
                    item.getEpisodeCount());
        }
        HgStore.saveProgressQuiet(currentSeriesId, currentEpisodeIndex, pos);
        if (watchAccumMs > 0) {
            HgStore.addWatchMs(currentSeriesId, watchAccumMs);
            watchAccumMs = 0;
        }
    }

    private long watchAccumMs;

    // ---------- 手势/清屏 ----------

    private void onPageTap(View pageRoot) {
        if (cleanMode) return;
        if (player == null) return;
        if (player.isPlaying()) {
            player.pause();
            flashCenter(R.drawable.ic_drama_pause);
        } else {
            player.play();
            flashCenter(R.drawable.ic_drama_play);
        }
    }

    private void flashCenter(int icon) {
        centerFlash.setImageResource(icon);
        centerFlash.setVisibility(View.VISIBLE);
        centerFlash.setAlpha(1f);
        centerFlash.animate().alpha(0f).setDuration(450).withEndAction(() -> {
            centerFlash.setVisibility(View.GONE);
            centerFlash.setAlpha(1f);
        }).start();
    }

    private void onPageLongPress(boolean center) {
        if (cleanMode) return;
        if (center) {
            showMorePanel();
        } else if (player != null && player.isPlaying()) {
            boosting = true;
            player.setPlaybackSpeed(2f);
            boostPill.setVisibility(View.VISIBLE);
        }
    }

    private void stopBoost() {
        if (!boosting) return;
        boosting = false;
        if (player != null) player.setPlaybackSpeed(HgStore.getSpeed());
        boostPill.setVisibility(View.GONE);
    }

    private void setCleanMode(boolean on) {
        cleanMode = on;
        int vis = on ? View.GONE : View.VISIBLE;
        topBar.setVisibility(mode == MODE_FEED ? vis : View.GONE);
        seriesBar.setVisibility(mode == MODE_SERIES ? vis : View.GONE);
        bottomBar.setVisibility(mode == MODE_FEED ? vis : View.GONE);
        progressWrap.setVisibility(vis);
        danmakuPill.setVisibility(on ? View.GONE
                : (HgStore.isDanmakuOn() ? View.VISIBLE : View.GONE));
        FeedAdapter.Holder h = currentHolder();
        if (h != null) h.chrome.setVisibility(vis);
        cleanBtn.setAlpha(on ? 0.35f : 1f);
        applyDanmakuVisibility();
    }

    private int cleanShiftStep;

    private void shiftCleanBtn() {
        // 防烧屏：清屏态下钮位每 30 秒在 4 个近位间轮换（tick 500ms 计步）
        if (++cleanShiftStep % 60 != 0) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) cleanBtn.getLayoutParams();
        int phase = (cleanShiftStep / 60) % 4;
        lp.rightMargin = dp(12) + (phase == 1 ? dp(2) : phase == 3 ? -dp(2) : 0);
        lp.bottomMargin = dp(148) + navBarHeight() + (mode == MODE_FEED ? dp(52) : 0)
                + (phase == 2 ? dp(2) : 0);
        cleanBtn.setLayoutParams(lp);
    }

    // ---------- 弹幕 ----------

    private androidx.media3.ui.danmaku.DanmakuConfig danmakuConfig() {
        int lines = HgStore.getDanmakuLines();
        return androidx.media3.ui.danmaku.DanmakuConfig.DEFAULT.buildUpon()
                .setTransparency(HgPanelLogic.danmakuTransparency(HgStore.getDanmakuOpacity()))
                .setScrollSpeedFactor(HgPanelLogic.danmakuSpeedFactor(HgStore.getDanmakuSpeedIndex()))
                .setTextScale(HgPanelLogic.danmakuFontScale(HgStore.getDanmakuFontIndex()))
                .setMaxTopLines(lines)
                .setMaxBottomLines(lines)
                .setMaxScrollLines(lines)
                .build();
    }

    void applyDanmakuConfig() {
        if (playerView != null) {
            playerView.getDanmakuController().setConfig(danmakuConfig());
        }
    }

    private void applyDanmakuVisibility() {
        boolean on = HgStore.isDanmakuOn() && (!cleanMode || HgStore.getDanmakuShowInClean());
        if (playerView != null) playerView.setDanmakuEnabled(on);
        if (!cleanMode) {
            danmakuPill.setVisibility(HgStore.isDanmakuOn() ? View.VISIBLE : View.GONE);
        }
    }

    private void startDanmaku(String vid) {
        danmakuVid = vid;
        danmakuItems.clear();
        if (playerView != null) playerView.getDanmakuController().clearItems();
        if (!HgStore.isDanmakuOn() || currentSeriesId == null) return;
        final String sid = currentSeriesId;
        worker.execute(() -> {
            HongguoDanmakuApi.fetchToFile(sid, vid, 0, item -> {
                if (!vid.equals(danmakuVid)) return;
                loadDanmakuFile(vid, item);
            });
        });
    }

    private void loadDanmakuFile(String vid, Danmaku item) {
        if (item == null || item.getUrl() == null) return;
        worker.execute(() -> {
            List<androidx.media3.ui.danmaku.Danmaku> parsed = parseDanmakuXml(item.getRealUrl());
            if (parsed == null) return;
            runOnUiThread(() -> {
                if (!vid.equals(danmakuVid) || playerView == null) return;
                danmakuItems.clear();
                danmakuItems.addAll(parsed);
                playerView.getDanmakuController().setItems(new ArrayList<>(danmakuItems));
            });
        });
    }

    /** Bilibili 形弹幕 XML（HongguoDanmakuApi 落盘格式）→ media3 条目（全滚动白字）。 */
    static List<androidx.media3.ui.danmaku.Danmaku> parseDanmakuXml(String fileUrl) {
        try {
            String path = fileUrl.startsWith("file:") ? android.net.Uri.parse(fileUrl).getPath() : fileUrl;
            java.io.File f = new java.io.File(path);
            if (!f.exists()) return null;
            byte[] bytes = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int off = 0;
                while (off < bytes.length) {
                    int n = in.read(bytes, off, bytes.length - off);
                    if (n < 0) break;
                    off += n;
                }
            }
            String xml = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            List<androidx.media3.ui.danmaku.Danmaku> out = new ArrayList<>();
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<d p=\"([0-9]+(?:\\.[0-9]+)?),[^\"]*\">(.*?)</d>", java.util.regex.Pattern.DOTALL)
                    .matcher(xml);
            while (m.find()) {
                long at = (long) (Double.parseDouble(m.group(1)) * 1000);
                String text = android.text.Html.fromHtml(m.group(2), android.text.Html.FROM_HTML_MODE_LEGACY).toString();
                if (!text.isEmpty()) {
                    out.add(new androidx.media3.ui.danmaku.Danmaku(text, at,
                            androidx.media3.ui.danmaku.Danmaku.TYPE_SCROLL, Color.WHITE, 0f, 0, null, 0));
                }
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- 面板宿主（HgFeedPanels 回调面） ----------

    private HgFeedPanels.Host host() {
        return new HgFeedPanels.Host() {
            @Override
            public Player player() {
                return player;
            }

            @Override
            public String vid() {
                return currentVid;
            }

            @Override
            public String seriesId() {
                return currentSeriesId;
            }

            @Override
            public DramaVideoModel currentModel() {
                return currentVid == null ? null : DramaCache.get(currentVid);
            }

            @Override
            public String currentDefinition() {
                return resolver.currentDefinition(currentVid);
            }

            @Override
            public void changeDefinition(String definition) {
                if (currentVid == null || player == null) return;
                long pos = player.getCurrentPosition();
                DramaItem item = items.get(currentIndex);
                resolver.resolveAs(currentVid, item.contentType, definition, new DramaResolver.Listener() {
                    @Override
                    public void onReady(String videoId, String url, int w, int h) {
                        if (!videoId.equals(currentVid) || player == null) return;
                        player.setMediaItem(MediaItem.fromUri(url));
                        player.prepare();
                        if (pos > 0) player.seekTo(pos);
                        player.setPlayWhenReady(true);
                    }

                    @Override
                    public void onFailed(String videoId, String code, String message) {
                        Toast.makeText(HongguoFeedActivity.this, "切换清晰度失败", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void setSpeed(float speed) {
                HgStore.setSpeed(speed);
                if (player != null && !boosting) player.setPlaybackSpeed(speed);
            }

            @Override
            public void openEpisodes() {
                showEpisodeSheet();
            }

            @Override
            public void openDanmakuSettings() {
                HgFeedPanels.showDanmakuSettings(HongguoFeedActivity.this, this);
            }

            @Override
            public void onDanmakuOptionsChanged() {
                applyDanmakuConfig();
                applyDanmakuVisibility();
            }

            @Override
            public void onDanmakuSent(String text, long offsetMs) {
                if (playerView == null) return;
                danmakuItems.add(new androidx.media3.ui.danmaku.Danmaku(text, offsetMs,
                        androidx.media3.ui.danmaku.Danmaku.TYPE_SCROLL, Color.WHITE, 0f, 0, null, 0));
                playerView.getDanmakuController().setItems(new ArrayList<>(danmakuItems));
                Toast.makeText(HongguoFeedActivity.this, "弹幕已发送", Toast.LENGTH_SHORT).show();
            }
        };
    }

    private void showMorePanel() {
        panelGuardUntil = System.currentTimeMillis() + 1500;
        HgFeedPanels.showMore(this, host());
    }

    public void showEpisodeSheet() {
        panelGuardUntil = System.currentTimeMillis() + 1500;
        String sid = mode == MODE_SERIES ? seriesIdArg : currentSeriesId;
        if (sid == null) return;
        ensureDetail(sid, detail -> {
            if (detail == null) {
                Toast.makeText(this, "选集加载失败", Toast.LENGTH_SHORT).show();
                return;
            }
            HgEpisodeSheet.show(this, detail, currentEpisodeIndex,
                    idx -> playEpisodeFromSheet(idx));
        });
    }

    private void bindRailState(int index) {
        FeedAdapter.Holder h = currentHolder();
        if (h == null || index >= items.size()) return;
        DramaItem item = items.get(index);
        boolean faved = item.getSeriesId() != null && HgStore.isFaved(item.getSeriesId());
        h.favIcon.setColorFilter(faved ? 0xFFFFC531 : Color.WHITE);
    }

    // ---------- 生命周期 ----------

    @Override
    protected void onPause() {
        super.onPause();
        if (orientationListener != null) orientationListener.disable();
        saveProgressNow();
        saveFeedPos();
        if (player != null && !engineParked) player.pause();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(ticker);
        main.removeCallbacks(savePosRunnable);
        saveProgressNow();
        if (engine != null && !engineParked) {
            try {
                engine.release();
            } catch (Throwable ignored) {
            }
        }
        // 泊出中（横屏页在前台）：本实例的解链流正被横屏页用着，不许关。
        if (!engineParked) resolver.closeAll();
        worker.shutdownNow();
        super.onDestroy();
    }

    public void retryCurrent() {
        if (currentVid == null) return;
        FeedAdapter.Holder h = currentHolder();
        if (h != null) {
            h.fail.setVisibility(View.GONE);
            h.spin.setVisibility(View.VISIBLE);
        }
        DramaItem item = currentIndex >= 0 && currentIndex < items.size() ? items.get(currentIndex) : null;
        resolver.close(currentVid);
        playVid(currentVid, item == null ? 0 : item.contentType, 0);
    }

    public void openComments() {
        panelGuardUntil = System.currentTimeMillis() + 1500;
        if (currentSeriesId == null || currentVid == null) return;
        DramaItem item = currentIndex >= 0 && currentIndex < items.size() ? items.get(currentIndex) : null;
        HgCommentsSheet.show(this, currentSeriesId, currentVid,
                item == null ? "" : item.getTitle());
    }

    public void refreshStrip() {
        adapter.notifyItemChanged(currentIndex);
    }

    // ---------- Pager 适配器 ----------

    private class FeedAdapter extends RecyclerView.Adapter<FeedAdapter.Holder> {

        class Holder extends RecyclerView.ViewHolder {
            final FrameLayout surface;
            final ImageView cover;
            final ProgressBar spin;
            final TextView fail;
            final View chrome;
            final TextView title;
            final TextView authorLine;
            final TextView desc;
            final TextView pill;
            final TextView strip;
            final ImageView favIcon;
            final TextView commentCount;
            final TextView fullPill;
            final View rail;
            int boundIndex = -1;

            Holder(FrameLayout page) {
                super(page);
                surface = page.findViewWithTag("surface");
                cover = page.findViewWithTag("cover");
                spin = page.findViewWithTag("spin");
                fail = page.findViewWithTag("fail");
                chrome = page.findViewWithTag("chrome");
                title = page.findViewWithTag("title");
                authorLine = page.findViewWithTag("author");
                desc = page.findViewWithTag("desc");
                pill = page.findViewWithTag("pill");
                strip = page.findViewWithTag("strip");
                favIcon = page.findViewWithTag("fav");
                commentCount = page.findViewWithTag("ccount");
                fullPill = page.findViewWithTag("fullpill");
                rail = page.findViewWithTag("rail");
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout page = new FrameLayout(HongguoFeedActivity.this);
            page.setLayoutParams(new RecyclerView.LayoutParams(-1, -1));
            page.setBackgroundColor(Color.BLACK);

            FrameLayout surface = new FrameLayout(HongguoFeedActivity.this);
            surface.setTag("surface");
            page.addView(surface, match());

            ImageView cover = new ImageView(HongguoFeedActivity.this);
            cover.setTag("cover");
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            page.addView(cover, match());

            ProgressBar spin = new ProgressBar(HongguoFeedActivity.this);
            spin.setTag("spin");
            FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(dp(40), dp(40));
            slp.gravity = Gravity.CENTER;
            page.addView(spin, slp);

            TextView fail = new TextView(HongguoFeedActivity.this);
            fail.setTag("fail");
            fail.setText("播放失败，点按重试");
            fail.setTextColor(0xFFDDDDDD);
            fail.setTextSize(13.5f);
            fail.setBackground(roundBg(0x66000000, dp(16)));
            fail.setPadding(dp(16), dp(9), dp(16), dp(9));
            fail.setVisibility(View.GONE);
            fail.setOnClickListener(v -> retryCurrent());
            FrameLayout.LayoutParams flp2 = new FrameLayout.LayoutParams(-2, -2);
            flp2.gravity = Gravity.CENTER;
            page.addView(fail, flp2);

            // chrome：信息摞＋右列
            FrameLayout chrome = new FrameLayout(HongguoFeedActivity.this);
            chrome.setTag("chrome");
            int bottomBase = dp(140) + navBarHeight() + (mode == MODE_FEED ? dp(52) : 0);

            LinearLayout info = new LinearLayout(HongguoFeedActivity.this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setPadding(dp(14), 0, dp(8), 0);
            TextView title = new TextView(HongguoFeedActivity.this);
            title.setTag("title");
            title.setTextColor(Color.WHITE);
            title.setTextSize(17);
            title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setShadowLayer(dp(3), 0, dp(1), 0xB3000000);
            info.addView(title);
            TextView author = new TextView(HongguoFeedActivity.this);
            author.setTag("author");
            author.setTextColor(0xE6FFFFFF);
            author.setTextSize(12.5f);
            author.setShadowLayer(dp(2), 0, dp(1), 0xB3000000);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2);
            alp.topMargin = dp(5);
            info.addView(author, alp);
            TextView desc = new TextView(HongguoFeedActivity.this);
            desc.setTag("desc");
            desc.setTextColor(0xD9FFFFFF);
            desc.setTextSize(12.5f);
            desc.setMaxLines(2);
            desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
            desc.setShadowLayer(dp(2), 0, dp(1), 0xB3000000);
            LinearLayout.LayoutParams dlp2 = new LinearLayout.LayoutParams(-2, -2);
            dlp2.topMargin = dp(5);
            info.addView(desc, dlp2);

            LinearLayout pillRow = new LinearLayout(HongguoFeedActivity.this);
            pillRow.setOrientation(LinearLayout.HORIZONTAL);
            pillRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams prlp = new LinearLayout.LayoutParams(-2, -2);
            prlp.topMargin = dp(10);
            TextView pill = new TextView(HongguoFeedActivity.this);
            pill.setTag("pill");
            pill.setTextSize(12.5f);
            pill.setPadding(dp(12), dp(6), dp(12), dp(6));
            pillRow.addView(pill);
            TextView strip = new TextView(HongguoFeedActivity.this);
            strip.setTag("strip");
            strip.setTextColor(0xF2FFFFFF);
            strip.setTextSize(12.5f);
            strip.setBackground(roundBg(0x59000000, dp(15)));
            strip.setPadding(dp(12), dp(6), dp(12), dp(6));
            LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(-2, -2);
            stlp.leftMargin = dp(8);
            pillRow.addView(strip, stlp);
            TextView fullPill = new TextView(HongguoFeedActivity.this);
            fullPill.setTag("fullpill");
            fullPill.setText("全屏观看");
            fullPill.setTextColor(0xF2FFFFFF);
            fullPill.setTextSize(12.5f);
            fullPill.setBackground(roundBg(0x59000000, dp(15)));
            fullPill.setPadding(dp(12), dp(6), dp(12), dp(6));
            fullPill.setVisibility(View.GONE);
            LinearLayout.LayoutParams fplp = new LinearLayout.LayoutParams(-2, -2);
            fplp.leftMargin = dp(8);
            pillRow.addView(fullPill, fplp);
            info.addView(pillRow, prlp);

            FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(-2, -2);
            ilp.gravity = Gravity.START | Gravity.BOTTOM;
            ilp.rightMargin = dp(78);
            ilp.bottomMargin = bottomBase;
            chrome.addView(info, ilp);

            LinearLayout rail = new LinearLayout(HongguoFeedActivity.this);
            rail.setTag("rail");
            rail.setOrientation(LinearLayout.VERTICAL);
            rail.setGravity(Gravity.CENTER_HORIZONTAL);
            ImageView fav = railIcon(R.drawable.ic_drama_star);
            fav.setTag("fav");
            rail.addView(fav, railIconLp(34));
            ImageView comment = railIcon(R.drawable.ic_drama_comment);
            comment.setTag("comment");
            rail.addView(comment, railIconLp(32));
            TextView ccount = new TextView(HongguoFeedActivity.this);
            ccount.setTag("ccount");
            ccount.setTextColor(Color.WHITE);
            ccount.setTextSize(10.5f);
            ccount.setGravity(Gravity.CENTER);
            ccount.setShadowLayer(dp(2), 0, dp(1), 0xB3000000);
            LinearLayout.LayoutParams cclp = new LinearLayout.LayoutParams(-2, -2);
            cclp.topMargin = -dp(4);
            rail.addView(ccount, cclp);
            FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(dp(56), -2);
            rlp.gravity = Gravity.END | Gravity.BOTTOM;
            rlp.rightMargin = dp(6);
            rlp.bottomMargin = bottomBase;
            chrome.addView(rail, rlp);

            page.addView(chrome, match());

            // 手势层：不消费事件（竖滑照常），只认单击/长按
            final android.view.GestureDetector gd = new android.view.GestureDetector(
                    HongguoFeedActivity.this, new android.view.GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onSingleTapConfirmed(MotionEvent e) {
                    onPageTap(page);
                    return false;
                }

                @Override
                public void onLongPress(MotionEvent e) {
                    boolean center = e.getX() > page.getWidth() / 3f && e.getX() < page.getWidth() * 2 / 3f
                            && e.getY() > page.getHeight() / 3f && e.getY() < page.getHeight() * 2 / 3f;
                    onPageLongPress(center);
                }
            });
            page.setOnTouchListener((v, e) -> {
                gd.onTouchEvent(e);
                if ((e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL)
                        && boosting) {
                    stopBoost();
                }
                return false;
            });
            return new Holder(page);
        }

        private LinearLayout.LayoutParams railIconLp(int sizeDp) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
            lp.topMargin = dp(14);
            return lp;
        }

        private ImageView railIcon(int res) {
            ImageView iv = new ImageView(HongguoFeedActivity.this);
            iv.setImageResource(res);
            iv.setColorFilter(Color.WHITE);
            iv.setPadding(dp(3), dp(3), dp(3), dp(3));
            return iv;
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            DramaItem item = items.get(position);
            h.boundIndex = position;
            if (item.getCoverUrl() != null && !item.getCoverUrl().isEmpty()) {
                Glide.with(HongguoFeedActivity.this).load(item.getCoverUrl()).into(h.cover);
            } else {
                h.cover.setImageDrawable(null);
            }
            h.cover.setVisibility(position == currentIndex && firstFrameForVid ? View.GONE : View.VISIBLE);
            h.spin.setVisibility(View.GONE);
            h.fail.setVisibility(View.GONE);
            h.title.setText(item.getTitle() == null ? "" : item.getTitle());
            String authorName = item.getAuthorName() != null ? item.getAuthorName() : item.getPublisher();
            h.authorLine.setText(authorName == null ? "" : "@" + authorName);
            h.authorLine.setVisibility(authorName == null ? View.GONE : View.VISIBLE);
            h.desc.setText(item.getDesc() == null ? "" : item.getDesc());
            h.desc.setVisibility(item.getDesc() == null || item.getDesc().isEmpty()
                    ? View.GONE : View.VISIBLE);
            boolean faved = item.getSeriesId() != null && HgStore.isFaved(item.getSeriesId());
            h.pill.setText(faved ? "★ 已收藏" : "☆ 收藏");
            h.pill.setTextColor(faved ? 0xFF3D2E00 : Color.WHITE);
            h.pill.setBackground(roundBg(faved ? 0xFFFFC531 : 0x59000000, dp(15)));
            h.pill.setOnClickListener(v -> {
                if (item.getSeriesId() == null) return;
                boolean now = HgStore.toggleFaved(item.getSeriesId());
                HgStore.putMeta(item.getSeriesId(), item.getTitle(), item.getCoverUrl(),
                        item.getEpisodeCount());
                h.pill.setText(now ? "★ 已收藏" : "☆ 收藏");
                h.pill.setTextColor(now ? 0xFF3D2E00 : Color.WHITE);
                h.pill.setBackground(roundBg(now ? 0xFFFFC531 : 0x59000000, dp(15)));
                h.favIcon.setColorFilter(now ? 0xFFFFC531 : Color.WHITE);
            });
            boolean finished = item.isFinished();
            int total = item.getEpisodeCount();
            h.strip.setVisibility(mode == MODE_UGC ? View.GONE : View.VISIBLE);
            String stripText;
            if (mode == MODE_SERIES) {
                stripText = "第" + currentEpisodeIndex + "集" + (total > 0 ? " / 全" + total + "集" : "") + " · 选集 ›";
            } else if (total > 0) {
                stripText = (finished ? "已完结 · 全" + total + "集" : "更新至第" + total + "集") + " · 选集 ›";
            } else {
                stripText = "选集 ›";
            }
            h.strip.setText(stripText);
            h.strip.setOnClickListener(v -> {
                if (position == currentIndex) showEpisodeSheet();
            });
            h.favIcon.setColorFilter(faved ? 0xFFFFC531 : Color.WHITE);
            h.favIcon.setOnClickListener(v -> h.pill.performClick());
            h.authorLine.setOnClickListener(v -> {
                if (item.getAuthorUid() != null && !item.getAuthorUid().isEmpty()) {
                    HongguoAuthorActivity.start(HongguoFeedActivity.this,
                            item.getAuthorUid(), item.getAuthorName(), item.getAuthorAvatar());
                }
            });
            h.commentCount.setText(item.getCommentCount() > 0
                    ? HgTimeTextCount.format(item.getCommentCount()) : "评论");
            h.commentCount.setVisibility(View.VISIBLE);
            h.itemView.findViewWithTag("comment").setOnClickListener(v -> {
                if (position == currentIndex) openComments();
            });
            boolean landscape = Boolean.FALSE.equals(item.getVertical());
            h.fullPill.setVisibility(landscape ? View.VISIBLE : View.GONE);
            h.fullPill.setOnClickListener(v -> {
                if (position == currentIndex) enterLand(true);
            });
            if (cleanMode) h.chrome.setVisibility(View.GONE);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    /** 计数简写（万级一位小数去尾零）。 */
    static final class HgTimeTextCount {
        static String format(long n) {
            if (n < 10000) return String.valueOf(n);
            double w = n / 10000.0;
            String s = w >= 100 ? String.valueOf(Math.round(w)) : String.format(
                    java.util.Locale.US, "%.1f", w).replaceAll("\\.0$", "");
            return s + "万";
        }
    }
}
