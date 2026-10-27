package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.music.MusicSourceManager;
import com.fongmi.android.tv.bean.MusicPlaylist;
import com.fongmi.android.tv.bean.music.MusicChart;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicDownloadStore;
import com.fongmi.android.tv.music.MusicLibrary;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.music.MusicRepository;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.music.DeviceMusicStore;
import com.fongmi.android.tv.ui.base.MusicBaseActivity;
import com.fongmi.android.tv.ui.minibar.ListenMiniBar;
import com.fongmi.android.tv.ui.music.MusicFrostDrawable;
import com.fongmi.android.tv.ui.music.MusicFrostWire;
import com.fongmi.android.tv.ui.view.MoodWaveView;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.XcFadeTransition;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 音乐首页（第十稿定稿构成，从零重写，独立页：自有布局、自有背景铺满整屏）。
 * 自上而下：渐变标题＋金星＋黑胶斜探 → 通知提示条（不可用才显） → 搜索胶囊＋⋮ 两项菜单 →
 * 五快捷独立磨砂片一行 → 继续播放卡（有断点才显） → 三彩卡（暗磨砂卡面＋光晕淡水印） →
 * 心情电台卡（末卡）。榜单管线（拉取＋快照缓存，状态在 MusicHomeChartStore）只喂三彩卡前三行与猜你喜欢封面堆。
 * 2026-10-27 试点：本页底图换他发的海水照片（MusicHomeBackgroundView），件面液态玻璃撤下、
 * 换暗磨砂——布局先挂 bg_home_frost_* XML 件作可见下限，initView 走 MusicFrostWire 升级
 * 同源暗磨砂（钉死深灰 #333333@.85、不从背景取色，糊底＝身后照片缓冲缩微糊化、只剩明暗色影）；其它音乐页照旧。
 * 迷你条只在首页显示：白名单在 ListenMiniBar，本页只负责回页补注册（refreshMiniBar）。
 */
public class MusicActivity extends MusicBaseActivity {

    /** 布局壳（BaseActivity 要求 getBinding；本页不用生成 Binding，视图一律 findViewById）。 */
    private static final class SimpleBinding implements ViewBinding {
        private final View root;

        SimpleBinding(View root) {
            this.root = root;
        }

        @Override
        public View getRoot() {
            return root;
        }
    }

    private View rootView;
    private ScrollView homeScroll;
    private TextView homeTitle;
    private TextView homeContinueName;
    private ImageView homeContinuePlay;
    private ProgressBar homeContinueProgress;
    private ImageView[] homeContinueCovers;
    private TextView[] homeChartTops;
    private TextView homeShuffleSub;
    private TextView homeMoodName;
    private TextView homeMoodDesc;
    private MoodWaveView homeMoodWave;
    private ImageView homeMoodPlay;

    private final List<TextView> moodPills = new ArrayList<>();
    private int moodIndex = -1;
    private boolean moodBuilding;

    private final List<MusicChart> chartTabs = new ArrayList<>();
    private List<MusicTrack> chartTracks = new ArrayList<>();
    private String chartSelKey = "";
    private int chartSeq;
    /** 榜单全失败且无任何旧数据可显时置位：三彩卡三行改失败态＋点行重试，不许空白假装正常。 */
    private boolean chartFailed;

    private String savedChartSelKey;
    private int pendingScrollY;
    private boolean scrollRestoreDone;
    private boolean moreMenuClosing;

    private final MusicController.Listener listener = new MusicController.Listener() {
        @Override
        public void onTrackChanged(MusicTrack track) {
            runOnUiThread(() -> {
                refreshContinue();
                refreshMoodState();
                refreshShuffleSubtitle();
            });
        }

        @Override
        public void onPlayingChanged(boolean playing) {
            runOnUiThread(() -> {
                refreshContinue();
                refreshMoodState();
                refreshShuffleSubtitle();
            });
        }

        /** 继续播放卡进度跟随真位置：此前 Listener 没消费 onProgress，停在首页播放时
         * 进度条只在回页/切歌时跳一格、平时冻结（体检 A 片）。这里只推进度条本身，
         * 不整卡重绑（封面/歌名由 onTrackChanged 管）。 */
        @Override
        public void onProgress(long position, long duration) {
            runOnUiThread(() -> {
                if (homeContinueProgress != null && duration > 0) {
                    homeContinueProgress.setProgress((int) (1000L * position / duration));
                }
            });
        }
    };

    public static void start(Activity activity) {
        activity.startActivity(new android.content.Intent(activity, MusicActivity.class));
    }

    // 切页过渡已收口到 MusicBaseActivity（音乐各页共用 xc_tab 交叉淡入淡出），本页不再自带覆盖。

    /** 榜单快照清缓存的同名静态入口（其它页调用点契约不变）：转调 MusicHomeChartStore。
     * 只清快照不够：已活首页实例手里还攥着旧 chartTabs，onResume 的重拉判据是
     * 「失效位‖chartTabs 空」——必须同时置失效位，回页才真重拉（插件增删/启停走这条）。 */
    public static void clearHomeChartCache() {
        MusicHomeChartStore.clear();
        MusicHomeChartStore.invalidate();
    }

    @Override
    protected ViewBinding getBinding() {
        rootView = LayoutInflater.from(this).inflate(R.layout.activity_music, null);
        return new SimpleBinding(rootView);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        // 磨砂升级：XML 暗面件是可见下限，这里走树换同源暗磨砂（取样失效也有下限成形）
        MusicFrostWire.frostifyTree(rootView);
        // 心情电台卡保底：走树没换上就按卡圆角 20 直换，不许留实底
        View moodCard = rootView.findViewById(R.id.homeMoodCard);
        if (moodCard != null && !(moodCard.getBackground() instanceof MusicFrostDrawable)) {
            MusicFrostWire.frostifyView(moodCard, 20f);
        }
        // 搜索/⋮ 图标钉白系（矢量自带 #9AA7B8 浅蓝灰，在深灰磨砂面上发闷，钉成与件内字同系的白）
        View searchBar = rootView.findViewById(R.id.homeSearchBar);
        if (searchBar instanceof ViewGroup && ((ViewGroup) searchBar).getChildCount() > 0
                && ((ViewGroup) searchBar).getChildAt(0) instanceof ImageView) {
            ((ImageView) ((ViewGroup) searchBar).getChildAt(0))
                    .setImageTintList(ColorStateList.valueOf(0xFFF1F3F5));
        }
        View moreBtn = rootView.findViewById(R.id.homeMoreBtn);
        if (moreBtn instanceof ImageView) {
            ((ImageView) moreBtn).setImageTintList(ColorStateList.valueOf(0xFFF1F3F5));
        }
        MusicRepository.ensureDebugFake();
        MusicController.get().bind(this);
        MusicController.get().addListener(listener);

        if (savedInstanceState != null) {
            moodIndex = savedInstanceState.getInt("ma_mood", -1);
            savedChartSelKey = savedInstanceState.getString("ma_chartSelKey");
            pendingScrollY = savedInstanceState.getInt("ma_scrollY", 0);
        }

        homeScroll = findViewById(R.id.homeScroll);
        homeTitle = findViewById(R.id.homeTitle);
        // 边距只打滚动层：左右 16dp 在布局，顶＝状态栏高＋12dp 在这里补（背景层不吃边距、铺满整屏）
        View content = findViewById(R.id.homeContent);
        int status = statusBarHeight();
        content.setPadding(content.getPaddingStart(), status + dp(12),
                content.getPaddingEnd(), content.getPaddingBottom());
        // 内容区入场：与列表页 fadeListInOnce 同口径——整区淡入 200ms（initView 只跑一次，即入场一次）
        content.setAlpha(0f);
        XcFadeTransition.fadeIn(content);

        paintTitle();
        tintQuickIcons();
        paintHalos();

        homeContinueName = findViewById(R.id.homeContinueName);
        homeContinuePlay = findViewById(R.id.homeContinuePlay);
        homeContinuePlay.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        homeContinueProgress = findViewById(R.id.homeContinueProgress);
        homeContinueCovers = new ImageView[]{
                findViewById(R.id.homeContinueCover0), findViewById(R.id.homeContinueCover1),
                findViewById(R.id.homeContinueCover2), findViewById(R.id.homeContinueCover3)};
        homeChartTops = new TextView[]{
                findViewById(R.id.homeChartTop0), findViewById(R.id.homeChartTop1),
                findViewById(R.id.homeChartTop2)};
        homeShuffleSub = findViewById(R.id.homeShuffleSub);
        ImageView shufflePlayIcon = (ImageView) ((ViewGroup) findViewById(R.id.homeShuffleDiscWrap)).getChildAt(1);
        shufflePlayIcon.setImageTintList(ColorStateList.valueOf(0xFF1C1C1E));
        homeMoodName = findViewById(R.id.homeMoodName);
        homeMoodDesc = findViewById(R.id.homeMoodDesc);
        homeMoodWave = findViewById(R.id.homeMoodWave);
        homeMoodPlay = findViewById(R.id.homeMoodPlay);
        homeMoodPlay.setImageTintList(ColorStateList.valueOf(Color.WHITE));

        wireClicks();
        buildMoodPills();
        initMoodCard();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 滚动只恢复一次（一次性门）
        if (!scrollRestoreDone && pendingScrollY > 0 && homeScroll != null) {
            final int y = pendingScrollY;
            homeScroll.post(() -> homeScroll.scrollTo(0, y));
        }
        scrollRestoreDone = true;
        // 消费榜单失效位：源增删等外部变化置位时回页必须重拉——失效路直走 fetchChart，
        // 不许再过 loadChart 的快照/TTL 闸（快照刚被清也可能被静默刷新路当新鲜停掉）
        boolean invalidated = MusicHomeChartStore.consumeInvalidated();
        if (invalidated) fetchChart(null);
        else if (chartTabs.isEmpty()) loadChart();
        refreshCounts();
        refreshShuffleSubtitle();
        refreshMoodState();
        refreshNotifStrip();
        refreshContinue();
        // 迷你条双注册：进音乐页先关听书条，再按现队列补注册音乐条（暂停自动关后无事件再触发同步，靠这里对一次）
        try {
            ListenMiniBar.get(this).musicPageEnter();
            MusicController.get().refreshMiniBar();
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("ma_mood", moodIndex);
        outState.putString("ma_chartSelKey", chartSelKey);
        outState.putInt("ma_scrollY", homeScroll == null ? 0 : homeScroll.getScrollY());
    }

    @Override
    protected void onDestroy() {
        MusicController.get().removeListener(listener);
        MusicController.get().unbind();
        super.onDestroy();
    }

    // ================= 标题 / 装饰 =================

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** 渐变标题：粉→紫 Shader 按实测字宽铺，量不到宽回落纯色；字带一点斜（-0.12）。 */
    private void paintTitle() {
        homeTitle.getPaint().setTextSkewX(-0.12f);
        homeTitle.post(() -> {
            float w = homeTitle.getPaint().measureText(homeTitle.getText().toString());
            if (w > 0) {
                homeTitle.getPaint().setShader(new LinearGradient(0, 0, w, 0,
                        0xFFFF5C8A, 0xFF8E7BFF, Shader.TileMode.CLAMP));
                homeTitle.invalidate();
            }
        });
    }

    /** 五快捷彩色图标（浅底不是无色：彩色照定稿给足）。 */
    private void tintQuickIcons() {
        tintQuickIcon(R.id.homeQuickFavorite, 0xFFF04B6E);
        tintQuickIcon(R.id.homeQuickSheets, 0xFF3FA7E0);
        tintQuickIcon(R.id.homeQuickDevice, 0xFFFF9F2E);
        tintQuickIcon(R.id.homeQuickRecent, 0xFF8A7CF5);
        tintQuickIcon(R.id.homeQuickDownload, 0xFF2FBF9B);
    }

    private void tintQuickIcon(int containerId, int color) {
        ViewGroup g = findViewById(containerId);
        if (g == null || g.getChildCount() == 0) return;
        if (g.getChildAt(0) instanceof ImageView) {
            ((ImageView) g.getChildAt(0)).setImageTintList(ColorStateList.valueOf(color));
        }
    }

    /** 三彩卡光晕：只作卡面内的淡水印（径向淡色盘＋白 0.30 描边环），压在内容下层、不单独成卡面。 */
    private void paintHalos() {
        paintHalo(findViewById(R.id.homeGuessHalo), 0xFFFFE8F3);
        paintHalo(findViewById(R.id.homeChartHalo), 0xFF9DF3EE);
        paintHalo(findViewById(R.id.homeShuffleHalo), 0xFFEAF989);
    }

    private void paintHalo(View halo, int color) {
        if (halo == null) return;
        GradientDrawable fill = new GradientDrawable();
        fill.setShape(GradientDrawable.OVAL);
        fill.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        fill.setGradientRadius(dp(52));
        fill.setColors(new int[]{MusicHomeMood.alphaColor(color, 0.85f), MusicHomeMood.alphaColor(color, 0f)});
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(dp(3), 0x4DFFFFFF);
        ring.setColor(0x00000000);
        halo.setBackground(new LayerDrawable(new android.graphics.drawable.Drawable[]{fill, ring}));
    }

    /** 按压反馈：微缩 0.98（宿主变换，与磨砂件自身的按压态不叠加冲突）。 */
    private void pressScale(View v) {
        v.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) view.animate().scaleX(0.98f).scaleY(0.98f).setDuration(90).start();
            else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL)
                view.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
            return false;
        });
    }

    // ================= 入口接线 =================

    private void wireClicks() {
        findViewById(R.id.homeSearchBar).setOnClickListener(v -> MusicSearchActivity.startSearch(this, ""));
        findViewById(R.id.homeMoreBtn).setOnClickListener(v -> showMoreMenu());
        findViewById(R.id.notifHintGo).setOnClickListener(v -> Notify.openNotificationSettings(this));
        findViewById(R.id.homeQuickFavorite).setOnClickListener(v -> MusicFavoriteActivity.startFavorite(this));
        findViewById(R.id.homeQuickSheets).setOnClickListener(v -> MusicPlaylistsActivity.startSheets(this));
        findViewById(R.id.homeQuickDevice).setOnClickListener(v -> MusicDeviceActivity.startDevice(this));
        findViewById(R.id.homeQuickRecent).setOnClickListener(v -> MusicHistoryActivity.startHistory(this));
        findViewById(R.id.homeQuickDownload).setOnClickListener(v -> MusicDownloadActivity.startDownload(this));
        findViewById(R.id.homeCardGuess).setOnClickListener(v -> MusicGuessActivity.startGuess(this));
        findViewById(R.id.homeCardCharts).setOnClickListener(v -> MusicChartsActivity.startCharts(this));
        findViewById(R.id.homeCardShuffle).setOnClickListener(v -> MusicRandomActivity.start(this));
        findViewById(R.id.homeShuffleDiscWrap).setOnClickListener(v -> shufflePlay());
        homeContinuePlay = findViewById(R.id.homeContinuePlay);
        pressScale(findViewById(R.id.homeCardGuess));
        pressScale(findViewById(R.id.homeCardCharts));
        pressScale(findViewById(R.id.homeCardShuffle));
    }

    private void openPlayer() {
        try {
        } catch (Throwable ignored) {
        }
    }

    // ================= ⋮ 小菜单（顶滑卡，只两项） =================

    private void showMoreMenu() {
        if (isFinishing() || isDestroyed()) return;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(6), dp(6), dp(6), dp(6));
        card.setBackground(new MusicFrostDrawable(this, 16));
        MusicFrostWire.applyFloatShadow(card, 16f); // 磨砂框要浮起来：柔影＋圆角描边轮廓

        int sites = 0;
        try {
            // 计数口径与插件管理页对齐（listSites：含停用插件、不含 QQ 合成站与 py 源）；
            // 此前用 Repository.sites() 聚合口径：停用不计、没装的 QQ 合成站多计 1、py 源冒充插件
            sites = MusicSourceManager.get().listSites().size();
        } catch (Throwable ignored) {
        }
        card.addView(menuRow(R.drawable.ic_music_puzzle, 0xFFAF52DE, "插件管理",
                sites + " 个插件", v -> MusicPluginsActivity.startSites(this)));
        View divider = new View(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        dlp.setMarginStart(dp(62));
        divider.setLayoutParams(dlp);
        divider.setBackgroundColor(0x1FFFFFFF);
        card.addView(divider);
        card.addView(menuRow(R.drawable.ic_nav_setting, 0xFFFF2D55, "设置",
                "", v -> MusicSettingsActivity.start(this)));

        // 全屏根容器承接点外/返回：系统 dismiss 拦不住滑出动画，故关掉系统 cancel 路、三路关闭统一走收口
        FrameLayout menuRoot = new FrameLayout(this);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.TOP;
        cardLp.topMargin = statusBarHeight() + dp(108);
        menuRoot.addView(card, cardLp);
        card.setClickable(true); // 卡本体吃掉自身范围内的点击，不落根容器误关

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(menuRoot);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setCancelable(false);
        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.gravity = Gravity.TOP;
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.MATCH_PARENT;
            window.setAttributes(lp);
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            lp.dimAmount = 0.25f;
            window.setAttributes(lp);
        }
        moreMenuClosing = false;
        menuRoot.setOnClickListener(v -> closeMoreMenu(dialog, card, null));
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                closeMoreMenu(dialog, card, null);
                return true;
            }
            return false;
        });
        // 行点击：先滑出关菜单，动画结束才真正关窗并跳页
        for (int i = 0; i < card.getChildCount(); i++) {
            View child = card.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof View.OnClickListener) {
                final View.OnClickListener action = (View.OnClickListener) tag;
                child.setOnClickListener(v -> closeMoreMenu(dialog, card, () -> action.onClick(v)));
            }
        }
        dialog.show();
        // 顶滑入 280ms
        card.setTranslationY(-dp(16));
        card.setAlpha(0f);
        card.animate().translationY(0).alpha(1f).setDuration(280).start();
    }

    /** ⋮ 菜单统一收口：滑出 220ms（上移 16dp＋淡出），动画结束才 dismiss 并执行待办；闩防重入。 */
    private void closeMoreMenu(final Dialog dialog, final View card, final Runnable after) {
        if (moreMenuClosing) return;
        moreMenuClosing = true;
        card.animate().translationY(-dp(16)).alpha(0f).setDuration(220)
                .withEndAction(() -> {
                    dialog.dismiss();
                    moreMenuClosing = false;
                    if (after != null) after.run();
                }).start();
    }

    private View menuRow(int iconRes, int iconColor, String title, String right, View.OnClickListener action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setTag(action);

        FrameLayoutBox box = new FrameLayoutBox(this, iconRes, iconColor);
        row.addView(box);
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(0xFFF1F4F5);
        tv.setTextSize(16);
        tv.getPaint().setFakeBoldText(true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tlp.setMarginStart(dp(14));
        tv.setLayoutParams(tlp);
        row.addView(tv);
        TextView rv = new TextView(this);
        rv.setText(right);
        rv.setTextColor(0x99FFFFFF);
        rv.setTextSize(12);
        row.addView(rv);
        return row;
    }

    /** 菜单行图标框：32dp、圆角 9dp；底改同源磨砂片＋图标色 tone 留身份，图标本身的同色 tint 不动。 */
    private final class FrameLayoutBox extends LinearLayout {
        FrameLayoutBox(Activity activity, int iconRes, int iconColor) {
            super(activity);
            setLayoutParams(new LinearLayout.LayoutParams(dp(32), dp(32)));
            MusicFrostWire.frostifyView(this, 9f);
            if (getBackground() instanceof MusicFrostDrawable) {
                ((MusicFrostDrawable) getBackground()).setToneColor(iconColor, 0.25f);
            }
            setGravity(Gravity.CENTER);
            ImageView iv = new ImageView(activity);
            iv.setImageResource(iconRes);
            iv.setImageTintList(ColorStateList.valueOf(iconColor));
            int s = dp(19);
            iv.setLayoutParams(new LinearLayout.LayoutParams(s, s));
            addView(iv);
        }
    }

    // ================= 通知提示条 =================

    /** 通知不可用才显：同进程每次回页现查（Notify 口径），不用缓存跨进程结论。 */
    private void refreshNotifStrip() {
        View strip = findViewById(R.id.notifPermStrip);
        if (strip == null) return;
        int reason;
        try {
            reason = Notify.notificationBlockReason(this);
        } catch (Throwable t) {
            reason = Notify.NOTIF_OK;
        }
        // 显隐走统一渐入渐出 200ms，不再直切。
        if (reason != Notify.NOTIF_OK) XcFadeTransition.fadeIn(strip);
        else XcFadeTransition.fadeOutThenGone(strip);
    }

    // ================= 五快捷计数 =================

    /** 计数全走现行真实口径异步回填，取不到显 0；不编数。 */
    private void refreshCounts() {
        MusicLibrary.favoriteCount(n -> runOnUiThread(() -> setCount(R.id.homeQuickFavCount, n == null ? "0" : String.valueOf(n))));
        MusicLibrary.playlists(list -> runOnUiThread(() -> setCount(R.id.homeQuickSheetCount, String.valueOf(list == null ? 0 : list.size()))));
        MusicDownloadStore.count(n -> runOnUiThread(() -> setCount(R.id.homeQuickDownloadCount, n == null ? "0" : String.valueOf(n))));
        try {
            List<MusicTrack> recent = MusicStore.loadRecent();
            int n = recent == null ? 0 : recent.size();
            setCount(R.id.homeQuickRecentCount, n >= 50 ? "近50条" : String.valueOf(n));
        } catch (Throwable ignored) {
            setCount(R.id.homeQuickRecentCount, "0");
        }
        Task.execute(() -> {
            int n = 0;
            try {
                List<MusicTrack> local = DeviceMusicStore.scan(this);
                n = local == null ? 0 : local.size();
            } catch (Throwable ignored) {
            }
            final int count = n;
            runOnUiThread(() -> setCount(R.id.homeQuickDeviceCount, String.valueOf(count)));
        });
    }

    private void setCount(int id, String text) {
        TextView tv = findViewById(id);
        if (tv != null) tv.setText(text);
    }

    // ================= 继续播放卡 =================

    /**
     * 数据优先当前会话（Controller.current/queue/position/duration），其次断点（MusicStore 只读），
     * 封面列以历史前 4 为主形态；无任何数据整卡 GONE 不留空洞，全程兜底防崩。
     */
    private void refreshContinue() {
        View card = findViewById(R.id.homeContinueCard);
        try {
            MusicController ctl = MusicController.get();
            MusicTrack current = ctl.current();
            List<MusicTrack> recent = MusicStore.loadRecent();
            if (current != null) {
                List<MusicTrack> queue = ctl.queue();
                List<MusicTrack> covers = queue != null && !queue.isEmpty() ? queue : recent;
                bindContinueCovers(covers, queue != null && !queue.isEmpty() ? -1 : 0, true);
                homeContinueName.setText(current.displayTitle());
                long dur = ctl.duration();
                homeContinueProgress.setProgress(dur > 0 ? (int) (1000L * ctl.position() / dur) : 0);
                homeContinuePlay.setImageResource(ctl.isPlaying() ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
                homeContinuePlay.setOnClickListener(v -> {
                    MusicController.get().toggle();
                    refreshContinue();
                });
                card.setOnClickListener(v -> openPlayer());
                XcFadeTransition.fadeIn(card);
                return;
            }
            List<MusicTrack> bpQueue = MusicStore.loadQueue();
            if (recent != null && !recent.isEmpty()) {
                bindContinueCovers(recent, 0, false);
                homeContinueName.setText(recent.get(0).displayTitle());
                int progress = 0;
                if (bpQueue != null && !bpQueue.isEmpty()) {
                    int idx = Math.max(0, Math.min(MusicStore.loadIndex(), bpQueue.size() - 1));
                    MusicTrack bp = bpQueue.get(idx);
                    if (bp != null && bp.duration > 0) {
                        progress = (int) (1000L * MusicStore.loadPosition() / bp.duration);
                    }
                }
                homeContinueProgress.setProgress(progress);
                homeContinuePlay.setImageResource(R.drawable.ic_music_play);
                homeContinuePlay.setOnClickListener(v -> onContinuePlay());
                card.setOnClickListener(v -> onContinuePlay());
                XcFadeTransition.fadeIn(card);
                return;
            }
            if (bpQueue != null && !bpQueue.isEmpty()) {
                int idx = Math.max(0, Math.min(MusicStore.loadIndex(), bpQueue.size() - 1));
                bindContinueCovers(bpQueue, idx, false);
                homeContinueName.setText(bpQueue.get(idx).displayTitle());
                MusicTrack bp = bpQueue.get(idx);
                homeContinueProgress.setProgress(bp.duration > 0
                        ? (int) (1000L * MusicStore.loadPosition() / bp.duration) : 0);
                homeContinuePlay.setImageResource(R.drawable.ic_music_play);
                homeContinuePlay.setOnClickListener(v -> onContinuePlay());
                card.setOnClickListener(v -> onContinuePlay());
                XcFadeTransition.fadeIn(card);
                return;
            }
            XcFadeTransition.fadeOutThenGone(card);
        } catch (Throwable t) {
            XcFadeTransition.fadeOutThenGone(card);
        }
    }

    /** 封面横排前 4：点第 i 张从该首续播整列（fromQueue=true 时走会话 playAt）。 */
    private void bindContinueCovers(List<MusicTrack> tracks, int startHint, boolean fromQueue) {
        for (int i = 0; i < homeContinueCovers.length; i++) {
            ImageView iv = homeContinueCovers[i];
            final int idx = i;
            if (tracks == null || i >= tracks.size() || tracks.get(i) == null) {
                iv.setVisibility(View.INVISIBLE);
                iv.setOnClickListener(null);
                continue;
            }
            iv.setVisibility(View.VISIBLE);
            MusicTrack t = tracks.get(i);
            if (t.cover != null && !t.cover.isEmpty()) {
                ImgUtil.loadRounded(t.displayTitle(), t.cover, iv);
            } else {
                iv.setImageDrawable(new com.fongmi.android.tv.utils.CoverTextDrawable(t.displayTitle(), false, 8));
            }
            iv.setOnClickListener(v -> {
                try {
                    if (fromQueue) {
                        MusicController.get().playAt(idx);
                    } else if (tracks != null && idx < tracks.size()) {
                        Map<String, String> params = new HashMap<>();
                        params.put("mode", "home");
                        params.put("title", "继续播放");
                        MusicController.get().play(new ArrayList<>(tracks), idx, "继续播放", "list", params);
                    }
                    openPlayer();
                } catch (Throwable ignored) {
                }
            });
        }
    }

    /** 无会话时按断点续播：有断点队列 playFrom 断点位，没有就播历史首列。 */
    private void onContinuePlay() {
        try {
            List<MusicTrack> bpQueue = MusicStore.loadQueue();
            Map<String, String> params = new HashMap<>();
            params.put("mode", "home");
            params.put("title", "继续播放");
            if (bpQueue != null && !bpQueue.isEmpty()) {
                int idx = Math.max(0, Math.min(MusicStore.loadIndex(), bpQueue.size() - 1));
                MusicController.get().playFrom(new ArrayList<>(bpQueue), idx,
                        MusicStore.loadPosition(), "继续播放", "list", params);
            } else {
                List<MusicTrack> recent = MusicStore.loadRecent();
                if (recent == null || recent.isEmpty()) return;
                MusicController.get().play(new ArrayList<>(recent), 0, "继续播放", "list", params);
            }
            openPlayer();
        } catch (Throwable ignored) {
        }
    }

    // ================= 随心听 =================

    private void refreshShuffleSubtitle() {
        if (homeShuffleSub == null) return;
        boolean shufflePlaying = false;
        try {
            shufflePlaying = MusicController.get().getMode() == MusicQueue.MODE_SHUFFLE
                    && MusicController.get().current() != null;
        } catch (Throwable ignored) {
        }
        homeShuffleSub.setText(shufflePlaying ? R.string.home_shuffle_playing : R.string.home_shuffle_sub);
    }

    /** 发光盘点按：队列→最近→收藏→已下载取池，打乱播＋开播放页；全空给明确提示。 */
    private void shufflePlay() {
        try {
            List<MusicTrack> queue = MusicController.get().queue();
            if (queue != null && !queue.isEmpty()) {
                playShuffled(new ArrayList<>(queue));
                return;
            }
            List<MusicTrack> recent = MusicStore.loadRecent();
            if (recent != null && !recent.isEmpty()) {
                playShuffled(new ArrayList<>(recent));
                return;
            }
        } catch (Throwable ignored) {
        }
        MusicLibrary.favorites(favs -> {
            if (favs != null && !favs.isEmpty()) {
                playShuffled(new ArrayList<>(favs));
                return;
            }
            MusicDownloadStore.all(dls -> {
                if (dls != null && !dls.isEmpty()) playShuffled(new ArrayList<>(dls));
                else Notify.show("还没有可播的歌");
            });
        });
    }

    private void playShuffled(List<MusicTrack> pool) {
        runOnUiThread(() -> {
            try {
                Collections.shuffle(pool);
                MusicController.get().setMode(MusicQueue.MODE_SHUFFLE);
                Map<String, String> params = new HashMap<>();
                params.put("mode", "home_shuffle");
                params.put("title", "随心听");
                MusicController.get().play(pool, 0, "随心听", "list", params);
                openPlayer();
            } catch (Throwable ignored) {
            }
        });
    }

    // ================= 心情电台 =================

    private void buildMoodPills() {
        LinearLayout row1 = findViewById(R.id.homeMoodPills1);
        LinearLayout row2 = findViewById(R.id.homeMoodPills2);
        moodPills.clear();
        for (int i = 0; i < MusicHomeMood.count(); i++) {
            final int idx = i;
            TextView pill = new TextView(this);
            pill.setText(MusicHomeMood.at(i).name);
            pill.setTextSize(12);
            pill.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(30), 1);
            if (i % 6 != 0) lp.setMarginStart(dp(6));
            pill.setLayoutParams(lp);
            pill.setOnClickListener(v -> selectMood(idx, true));
            ((LinearLayout) (i < 6 ? row1 : row2)).addView(pill);
            moodPills.add(pill);
        }
    }

    private void initMoodCard() {
        if (moodIndex < 0) {
            moodIndex = MusicHomeMood.defaultIndex(Calendar.getInstance().get(Calendar.HOUR_OF_DAY));
        }
        homeMoodPlay.setOnClickListener(v -> onMoodPlay());
        selectMood(moodIndex, false);
    }

    private void selectMood(int idx, boolean fromUser) {
        moodIndex = idx;
        MusicHomeMood.Mood mood = MusicHomeMood.at(idx);
        homeMoodName.setText(mood.name);
        homeMoodDesc.setText(mood.desc);
        // 波浪只换颜色不换形（2026-10-26 他改口径）：固定白色半透、跟磨砂卡白字同系，
        // 不再喂心情 tone 满色（扎眼）；心情色只活在选中片/播放钮上。
        homeMoodWave.setWaveColor(MoodWaveView.WAVE_COLOR);
        // 卡面保持纯磨砂（卡面平铺心情色的旧法早已摘除）；播放钮：磨砂圆钮＋心情色 tone。
        if (!(homeMoodPlay.getBackground() instanceof MusicFrostDrawable)) {
            MusicFrostWire.frostifyView(homeMoodPlay, 999f);
        }
        if (homeMoodPlay.getBackground() instanceof MusicFrostDrawable) {
            ((MusicFrostDrawable) homeMoodPlay.getBackground()).setToneColor(mood.tone, 0.5f);
        }
        for (int i = 0; i < moodPills.size(); i++) {
            TextView pill = moodPills.get(i);
            MusicHomeMood.Mood m = MusicHomeMood.at(i);
            boolean sel = i == idx;
            // 片本体换同源暗磨砂：首过换磨砂、之后只切 tone——选中＝磨砂上带心情色，未选清 tone 回中性磨砂。
            MusicFrostWire.frostifyView(pill, 999f);
            if (pill.getBackground() instanceof MusicFrostDrawable) {
                MusicFrostDrawable g = (MusicFrostDrawable) pill.getBackground();
                if (sel) g.setToneColor(m.tone, 0.45f);
                else g.setToneColor(0);
            }
            pill.setTextColor(sel ? 0xFFFFFFFF : 0xD9FFFFFF);
            pill.getPaint().setFakeBoldText(sel);
        }
        refreshMoodState();
    }

    /** 当前队列是不是本心情的台：比队列曲目键串与存盘的心情队列键串（MusicStore  mood 键）。 */
    private boolean isThisMoodQueue() {
        try {
            String saved = MusicStore.getMoodQueueKeys();
            if (saved == null || saved.isEmpty()) return false;
            List<MusicTrack> queue = MusicController.get().queue();
            if (queue == null || queue.isEmpty()) return false;
            return saved.equals(joinTrackKeys(queue));
        } catch (Throwable t) {
            return false;
        }
    }

    private String joinTrackKeys(List<MusicTrack> tracks) {
        StringBuilder sb = new StringBuilder();
        for (MusicTrack t : tracks) {
            if (sb.length() > 0) sb.append(',');
            sb.append(MusicQueue.trackKey(t));
        }
        return sb.toString();
    }

    private void refreshMoodState() {
        if (homeMoodWave == null || homeMoodPlay == null) return;
        boolean active = isThisMoodQueue();
        boolean playing = false;
        try {
            playing = active && MusicController.get().isPlaying();
        } catch (Throwable ignored) {
        }
        homeMoodWave.setPlaying(playing);
        homeMoodPlay.setImageResource(playing ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
    }

    /** 播放钮：本心情台在播/暂停走 toggle；不是本台则按心情词搜索建台（落雪搜索）后播。 */
    private void onMoodPlay() {
        if (isThisMoodQueue()) {
            try {
                MusicController.get().toggle();
            } catch (Throwable ignored) {
            }
            refreshMoodState();
            return;
        }
        if (moodBuilding) {
            // 建台中再点不能静默吞掉：给明确反馈（此前无任何提示，用户无从知道正在建台）
            Notify.show("正在找这个心情的歌…");
            return;
        }
        moodBuilding = true;
        homeMoodPlay.setAlpha(0.45f);
        final MusicHomeMood.Mood mood = MusicHomeMood.at(moodIndex);
        Task.execute(() -> {
            List<MusicTrack> result = new ArrayList<>();
            try {
                List<MusicTrack> r = MusicRepository.search(mood.keyword());
                if (r != null) result.addAll(r);
            } catch (Throwable ignored) {
            }
            runOnUiThread(() -> {
                moodBuilding = false;
                homeMoodPlay.setAlpha(1f);
                if (result.isEmpty()) {
                    Notify.show("没找到这个心情的歌");
                    return;
                }
                try {
                    MusicStore.setMoodQueueKeys(joinTrackKeys(result));
                    Map<String, String> params = new HashMap<>();
                    params.put("mode", "home_mood");
                    params.put("title", "心情电台");
                    MusicController.get().play(result, 0, "心情电台 · " + mood.name, "list", params);
                } catch (Throwable ignored) {
                }
                refreshMoodState();
            });
        });
    }

    // ================= 榜单管线（只喂三彩卡前三行＋猜你喜欢封面堆） =================

    /** 榜单只留酷我＋QQ（他定），与榜单页同口径。 */
    private static boolean isChartSite(MusicSite s) {
        if (s == null) return false;
        String tag = ((s.platform == null ? "" : s.platform) + (s.name == null ? "" : s.name)).toLowerCase();
        return tag.contains("qq") || tag.contains("tx") || tag.contains("kw") || tag.contains("酷我");
    }

    private static String chartKey(MusicChart c) {
        return (c.platform == null ? "" : c.platform) + "|" + (c.id == null ? "" : c.id);
    }

    /**
     * 加载序：先灌盘快照、先显；新鲜（10 分钟）直接停；过期/无快照再拉取。
     * 回页恢复的选中签：savedInstanceState 的 ma_chartSelKey 优先于快照 selKey。
     */
    private void loadChart() {
        MusicHomeChartStore.loadDiskIfNeeded();
        if (MusicHomeChartStore.hasCache()) {
            restoreFromCache();
            if (MusicHomeChartStore.isFresh()) return;
            refreshChartSilently();
            return;
        }
        fetchChart(null);
    }

    private void restoreFromCache() {
        MusicHomeChartStore.Snapshot s = MusicHomeChartStore.snapshot();
        if (s == null) return;
        chartTabs.clear();
        chartTabs.addAll(s.tabs);
        String want = savedChartSelKey != null && !savedChartSelKey.isEmpty() ? savedChartSelKey : s.selKey;
        MusicChart sel = null;
        for (MusicChart c : chartTabs) {
            if (chartKey(c).equals(want)) sel = c;
        }
        if (sel == null && !chartTabs.isEmpty()) sel = chartTabs.get(0);
        chartSelKey = sel == null ? "" : chartKey(sel);
        if (sel != null && chartKey(sel).equals(s.selKey)) {
            chartTracks = MusicHomeChartStore.copyTracks(s.tracks);
            fillChart();
        } else if (sel != null) {
            fetchChartTracks(sel);
        }
    }

    /** 静默刷新：先显快照、后台重拉；签表没变只换曲目，变了整卡重填。 */
    private void refreshChartSilently() {
        final String keepKey = chartSelKey;
        Task.execute(() -> {
            List<MusicChart> fresh = fetchTabsBlocking();
            if (fresh.isEmpty()) return;
            StringBuilder sig = new StringBuilder();
            for (MusicChart c : fresh) sig.append(chartKey(c)).append(';');
            StringBuilder old = new StringBuilder();
            for (MusicChart c : chartTabs) old.append(chartKey(c)).append(';');
            MusicChart sel = null;
            for (MusicChart c : fresh) if (chartKey(c).equals(keepKey)) sel = c;
            if (sel == null) sel = fresh.get(0);
            final MusicChart selF = sel;
            List<MusicTrack> tracks = fetchTracksBlocking(selF);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                chartTabs.clear();
                chartTabs.addAll(fresh);
                chartSelKey = chartKey(selF);
                if (!sig.toString().equals(old.toString()) || tracks != null) {
                    if (tracks != null) chartTracks = tracks;
                    fillChart();
                    saveChartSnapshot();
                }
            });
        });
    }

    private void fetchChart(String wantKey) {
        final int seq = ++chartSeq;
        Task.execute(() -> {
            List<MusicChart> fresh = fetchTabsBlocking();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || seq != chartSeq) return;
                if (fresh.isEmpty()) {
                    // 全部站点失败/无榜可拉：手里也没旧账时上失败态，不许三行空白假装正常
                    if (chartTabs.isEmpty()) showChartFailed();
                    return;
                }
                chartTabs.clear();
                chartTabs.addAll(fresh);
                MusicChart sel = null;
                String want = wantKey != null ? wantKey
                        : (savedChartSelKey != null && !savedChartSelKey.isEmpty() ? savedChartSelKey : chartSelKey);
                for (MusicChart c : chartTabs) if (chartKey(c).equals(want)) sel = c;
                if (sel == null) sel = chartTabs.get(0);
                chartSelKey = chartKey(sel);
                fetchChartTracks(sel);
            });
        });
    }

    private List<MusicChart> fetchTabsBlocking() {
        List<MusicChart> result = new ArrayList<>();
        try {
            for (MusicSite s : MusicRepository.sites()) {
                if (!isChartSite(s)) continue;
                List<MusicChart> cs = MusicRepository.charts(s);
                if (cs != null) result.addAll(cs);
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    private List<MusicTrack> fetchTracksBlocking(MusicChart chart) {
        try {
            List<MusicTrack> r = MusicRepository.chartTracks(chart);
            return r == null ? new ArrayList<>() : r;
        } catch (Throwable t) {
            return null;
        }
    }

    private void fetchChartTracks(MusicChart chart) {
        final int seq = ++chartSeq;
        final String key = chartKey(chart);
        Task.execute(() -> {
            List<MusicTrack> tracks = fetchTracksBlocking(chart);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || seq != chartSeq || !key.equals(chartSelKey)) return;
                if (tracks == null) return;
                chartTracks = tracks;
                fillChart();
                saveChartSnapshot();
            });
        });
    }

    /** 榜单全失败态：三行改失败文案，点任意一行重拉；成功回填（fillChart）时摘掉行点击。 */
    private void showChartFailed() {
        chartFailed = true;
        homeChartTops[0].setText("榜单加载失败");
        homeChartTops[1].setText("点这里重试");
        homeChartTops[2].setText("");
        for (TextView tv : homeChartTops) tv.setOnClickListener(v -> fetchChart(null));
    }

    /** 榜单数据落点只剩两处：三彩卡前三行小字＋猜你喜欢封面堆（大榜单卡已摘）。 */
    private void fillChart() {
        if (chartFailed) {
            chartFailed = false;
            for (TextView tv : homeChartTops) tv.setOnClickListener(null);
        }
        for (int i = 0; i < 3; i++) {
            MusicTrack t = i < chartTracks.size() ? chartTracks.get(i) : null;
            homeChartTops[i].setText(t == null ? "" : (i + 1) + " " + t.displayTitle());
        }
        fillGuessCovers();
    }

    /** 猜你喜欢卡的封面堆＝当前榜前 3 首封面（榜单未到时堆隐藏，不编假图）。 */
    private void fillGuessCovers() {
        ImageView[] covers = {
                findViewById(R.id.homeGuessCover0), findViewById(R.id.homeGuessCover1),
                findViewById(R.id.homeGuessCover2)};
        for (int i = 0; i < covers.length; i++) {
            MusicTrack t = i < chartTracks.size() ? chartTracks.get(i) : null;
            if (t == null || t.cover == null || t.cover.isEmpty()) {
                covers[i].setVisibility(View.GONE);
                continue;
            }
            covers[i].setVisibility(View.VISIBLE);
            ImgUtil.loadRounded(t.displayTitle(), t.cover, covers[i]);
        }
    }

    private void saveChartSnapshot() {
        MusicChart sel = null;
        for (MusicChart c : chartTabs) if (chartKey(c).equals(chartSelKey)) sel = c;
        String firstCover = chartTracks.isEmpty() || chartTracks.get(0) == null
                ? "" : (chartTracks.get(0).cover == null ? "" : chartTracks.get(0).cover);
        // tone 字段只原样回存既有快照值（页签下划线已随大榜单卡摘除、不再新取色）；存盘格式契约不变
        MusicHomeChartStore.Snapshot s = MusicHomeChartStore.snapshot();
        boolean toneSet = s != null && s.toneSet && firstCover.equals(s.toneCover);
        MusicHomeChartStore.save(chartTabs, sel == null ? "" : (sel.name == null ? "" : sel.name),
                chartSelKey, chartTracks, toneSet ? s.tone : 0, firstCover, toneSet);
    }

}
