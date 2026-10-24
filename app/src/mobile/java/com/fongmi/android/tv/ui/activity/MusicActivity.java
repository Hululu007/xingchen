package com.fongmi.android.tv.ui.activity;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.music.KwChartSource;
import com.fongmi.android.tv.api.music.MusicSourceManager;
import com.fongmi.android.tv.bean.music.MusicChart;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.databinding.ActivityMusicBinding;
import com.fongmi.android.tv.music.DeviceMusicStore;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicDownloadStore;
import com.fongmi.android.tv.music.MusicLibrary;
import com.fongmi.android.tv.music.MusicRepository;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.music.MusicPlayerActivity;
import com.fongmi.android.tv.utils.CoverColor;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 音乐首页（第十稿定稿构成；本文件为首页收官从零重写，2026-10-25）。
 *
 * 页面自上而下：渐变标题＋金星＋黑胶 → 搜索胶囊（点进搜索页）＋⋮ 菜单（顶滑白卡，
 * 只插件管理／设置两行）→ 通知权限提示条（未授权才显）→ 五快捷独立玻璃片（收藏／
 * 歌单／本地居中／历史／下载，带真实计数）→ 继续播放白卡（历史前 4 封面）→ 三彩卡
 * （猜你喜欢封面堆／排行榜前三名／随心听发光盘）→ 心情电台卡 → 榜单卡（QQ＋酷我
 * 真榜：签行／封面叠／前 3 行／加载与空态）→ 聆听报告卡 → 底部留白给悬浮迷你条。
 * 榜单缓存与磁盘快照在 MusicHomeChartStore，心情表与颜色公式在 MusicHomeMood，
 * 本类只管装配、交互与状态机。液态玻璃全屏背景＝根布局 MusicGlassBackgroundView，
 * 本页是独立 Activity 实底页（清单声明本体，不盖在主壁纸上）。
 * 顶栏无返回箭头（铁律）；界面只放名字和状态，无介绍文字。
 */
public class MusicActivity extends BaseActivity {

    private ActivityMusicBinding binding;

    // 榜单卡状态（实例级；跨实例缓存与快照在 MusicHomeChartStore）
    private final List<MusicChart> chartTabs = new ArrayList<>();
    private List<MusicTrack> chartTracks = new ArrayList<>();
    /** chartTracks 归属签（平台|id）：切签后旧签曲目不算新签「已有内容」 */
    private String chartTracksKey;
    private int chartSel = -1;
    private int chartLoadSeq;
    private boolean chartLoaded;
    /** 当前签拉空已显空态：onResume 不再重试转圈，只点重试／切签重拉 */
    private boolean chartEmpty;
    /** 选中签下划线语气色＝当前榜首封面主色（CoverColor），取色失败回落红 */
    private int chartTone = 0xFFFA243C;
    private String chartToneCover = "";

    /** X-08 重建恢复暂存：心情选中（initMoodCard 消费）、榜单选中签键、滚动位置（onResume 消费一次） */
    private String savedMood;
    private String savedChartSelKey;
    private int savedScrollY = -1;
    private boolean scrollRestoreDone;

    /** 插件侧清过缓存：onResume 消费 MusicHomeChartStore 失效位后同实例也重拉 */
    public static void clearHomeChartCache() {
        MusicHomeChartStore.invalidate();
    }

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, MusicActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityMusicBinding.inflate(getLayoutInflater());
        return binding;
    }

    /** X-08：半状态保存——心情选中／榜单选中签／滚动位置随重建带回，与静态缓存＋磁盘快照的 selKey 对齐兜底。 */
    @Override
    protected void onSaveInstanceState(@androidx.annotation.NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        try {
            if (moodSelected != null) outState.putString("ma_mood", moodSelected);
            if (chartSel >= 0 && chartSel < chartTabs.size()) {
                outState.putString("ma_chartSelKey", chartKey(chartTabs.get(chartSel)));
            }
            if (binding != null && binding.homeScroll != null) {
                outState.putInt("ma_scrollY", binding.homeScroll.getScrollY());
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            savedMood = savedInstanceState.getString("ma_mood");
            savedChartSelKey = savedInstanceState.getString("ma_chartSelKey");
            savedScrollY = savedInstanceState.getInt("ma_scrollY", -1);
        }
        // 离边 16dp 只打在滚动层：背景层与滚动层是根 FrameLayout 的并列子 View，
        // 背景不吃 padding、全屏铺满（旧法把 padding 打在共享根上，真机背景被框进
        // 边距内四边露壁纸、整页像一张带边距的白纸，断点实证后废弃）。
        int statusBar = 0;
        int sbRes = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (sbRes > 0) statusBar = getResources().getDimensionPixelSize(sbRes);
        float density = getResources().getDisplayMetrics().density;
        int side = Math.round(16 * density);
        binding.homeScroll.setPadding(side, statusBar + Math.round(12 * density),
                side, Math.round(16 * density));
        MusicRepository.ensureDebugFake();
        MusicController.get().bind(this);
        // 标题：歌词同款斜度 skewX(-0.12f)＋粉→紫渐变字（shader 宽按文字实测宽，量不到回落纯色不崩）
        binding.homeTitle.getPaint().setTextSkewX(-0.12f);
        try {
            float tw = binding.homeTitle.getPaint().measureText("星辰音乐");
            if (tw > 0) binding.homeTitle.getPaint().setShader(new android.graphics.LinearGradient(
                    0, 0, tw, 0, 0xFFFF5C8A, 0xFF8E7BFF, android.graphics.Shader.TileMode.CLAMP));
            binding.homeTitle.invalidate();
        } catch (Throwable ignored) {
        }
        // 继续播放：卡面点＝有会话进播放页、无会话播最近一首；圆键见 onContinuePlay
        binding.homeContinueCard.setOnClickListener(v -> {
            if (MusicController.get().isActive()) MusicPlayerActivity.start(this);
            else playContinueAt(0);
        });
        binding.homeContinuePlay.setOnClickListener(v -> onContinuePlay());
        // 搜索胶囊整条进搜索页；⋮ 独立点击区开小菜单
        binding.homeSearchBar.setOnClickListener(v -> MusicSearchActivity.startSearch(this, ""));
        binding.homeBtnMore.setOnClickListener(this::showMoreMenu);
        binding.notifPermGo.setOnClickListener(v -> openAppNotificationSettings());
        // 五快捷真跳
        binding.homeQuickFavorite.setOnClickListener(v -> MusicFavoriteActivity.startFavorite(this));
        binding.homeQuickSheets.setOnClickListener(v -> MusicPlaylistsActivity.startSheets(this));
        binding.homeQuickDevice.setOnClickListener(v -> MusicDeviceActivity.startDevice(this));
        binding.homeQuickRecent.setOnClickListener(v -> showRecent());
        binding.homeQuickDownload.setOnClickListener(v -> MusicDownloadActivity.startDownload(this));
        // 三彩卡入口：猜你喜欢页／榜单页／随心听页（歌单广场不在此卡，入口在五快捷「我的歌单」）
        binding.homeCardGuess.setOnClickListener(v -> MusicGuessActivity.startGuess(this));
        binding.homeCardCharts.setOnClickListener(v -> MusicChartsActivity.startCharts(this));
        binding.homeCardShuffle.setOnClickListener(v -> MusicRandomActivity.start(this));
        initMoodCard();
        // 榜单卡：「完整榜单」进榜单页；主封面播放钮从头播该榜；点行从该曲开播整榜
        binding.homeChartMore.setOnClickListener(v -> MusicChartsActivity.startCharts(this));
        binding.homeChartMoreIcon.setOnClickListener(v -> MusicChartsActivity.startCharts(this));
        binding.homeChartEmptyRetry.setOnClickListener(v -> {
            if (chartSel >= 0 && chartSel < chartTabs.size()) selectChart(chartSel, true);
        });
        binding.homeChartPlay.setOnClickListener(v -> playChartFrom(0));
        binding.homeChartRow1.setOnClickListener(v -> playChartFrom(0));
        binding.homeChartRow2.setOnClickListener(v -> playChartFrom(1));
        binding.homeChartRow3.setOnClickListener(v -> playChartFrom(2));
        attachCardPress(binding.homeChartRow1, 0.98f);
        attachCardPress(binding.homeChartRow2, 0.98f);
        attachCardPress(binding.homeChartRow3, 0.98f);
        // 按压反馈：默认 0.96；三彩卡按瓜子口径 0.98（每视图只挂一套，旧码双注册以 attachCardPress 为准）
        attachCardPress(binding.homeSearchBar);
        attachCardPress(binding.homeQuickFavorite);
        attachCardPress(binding.homeQuickSheets);
        attachCardPress(binding.homeQuickDevice);
        attachCardPress(binding.homeQuickRecent);
        attachCardPress(binding.homeQuickDownload);
        attachCardPress(binding.homeCardGuess, 0.98f);
        attachCardPress(binding.homeCardCharts, 0.98f);
        attachCardPress(binding.homeCardShuffle, 0.98f);
        // 三彩卡光晕色（浅色分支定值，常驻静态层；随心听脉动门控在 refreshShuffleSubtitle）
        binding.homeHaloGuess.setHaloColor(0xFFFFE8F3);
        binding.homeHaloCharts.setHaloColor(0xFF9DF3EE);
        binding.homeHaloShuffle.setHaloColor(0xFFEAF989);
        attachCardPress(binding.homeChartCard);
        attachCardPress(binding.homeReportCard);
        binding.homeReportCard.setOnClickListener(v -> openFullReport());
        // 榜单封面圆角 12dp：ImgUtil.loadRounded 固定 8dp，用视图轮廓裁切补到 12dp（12dp 形含于 8dp 形内）
        applyChartCoverCorners();
        refreshCounts();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // X-08 滚动恢复：内容高度随榜单卡显隐变化，等本帧布局后一次性回滚，滚完即弃不反复拽
        if (!scrollRestoreDone && savedScrollY > 0 && binding != null && binding.homeScroll != null) {
            scrollRestoreDone = true;
            final int y = savedScrollY;
            binding.homeScroll.post(() -> {
                try {
                    if (binding != null && binding.homeScroll != null) binding.homeScroll.scrollTo(0, y);
                } catch (Throwable ignored) {
                }
            });
        }
        // 首页浅底：状态栏图标改深色；离开本页在 onPause 恢复浅色图标
        ReadingSkin.setSystemBarIconAppearance(this, false);
        // 插件侧清过缓存时，同实例不许守着旧内容，必须重拉
        if (MusicHomeChartStore.consumeInvalidated()) {
            chartLoaded = false;
            chartEmpty = false;
        }
        if (!chartLoaded && !chartEmpty) loadChart();
        refreshCounts();
        refreshShuffleSubtitle();
        refreshMoodState();
        refreshNotifPermStrip();
        refreshContinue();
        // 进入音乐页：听书迷你条真关闭（音乐会话自身不受影响）
        try {
            com.fongmi.android.tv.ui.minibar.ListenMiniBar.get(this).musicPageEnter();
        } catch (Throwable ignored) {
        }
        // 音乐迷你条再注册：会话被策略层暂停自动关闭摘掉后，回首页按现队列主动对一次
        try {
            MusicController.get().refreshMiniBar();
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onPause() {
        // 离开音乐首页恢复浅色状态栏图标，避免把深色图标带去深底的播放页/歌词页
        ReadingSkin.setSystemBarIconAppearance(this, true);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        MusicController.get().unbind();
        super.onDestroy();
    }

    // ================= 通知权限提示条 =================

    /**
     * 通知权限明示（App 自己摆明面，不推给用户排查）：POST_NOTIFICATIONS 未授权时
     * 前台通知被系统藏起、用户只会觉得「通知没了」。首页实读权限：没授权挂提示条
     * （深底白字＋黄钮），已授权或安卓 13 以下永远不出现、不占位；从设置页回来
     * onResume 再实读一次，开好即自动撤下。
     */
    private void refreshNotifPermStrip() {
        boolean granted = Build.VERSION.SDK_INT < 33
                || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        binding.notifPermStrip.setVisibility(granted ? View.GONE : View.VISIBLE);
    }

    /** 跳系统「应用通知」设置页（本应用那页）；个别系统打不开时退到应用详情页。 */
    private void openAppNotificationSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        } catch (Throwable ignored) {
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Throwable ignored2) {
            }
        }
    }

    // ================= 榜单卡（数据只用酷我＋QQ 真榜） =================

    /**
     * 榜签映射（四类 → 星辰真实榜，逐个核过）：飙升＝QQ topId 62；新歌＝QQ topId 27；
     * 热歌＝QQ topId 26；原创＝QQ 全表无此名，落酷我榜表按「原创」匹配。
     * 解析序固定先 QQ 后酷我；某签两边都匹配不到就不出该签；全签落空整卡隐藏。
     */
    private void loadChart() {
        // 快路：先灌磁盘快照再走内存缓存路（冷启动第一次就有快照显，不空屏等联网——
        // 对齐期 41 的根因与修法见 MusicHomeChartStore 类注释）。
        MusicHomeChartStore.loadDiskIfNeeded();
        if (restoreFromCache()) {
            if (MusicHomeChartStore.isFresh()) return;
            refreshChartSilently();
            return;
        }
        Task.execute(() -> {
            List<MusicChart> tabs = collectChartTabs();
            int def = -1;
            for (int i = 0; i < tabs.size(); i++) if (tabs.get(i).name.contains("新歌")) def = i;
            if (def < 0 && !tabs.isEmpty()) def = 0;
            final int sel = def;
            runOnUiThread(() -> {
                if (binding == null || isFinishing() || isDestroyed()) return;
                // 有缓存 tone 先用它画下划线，不先写死红再跳变
                if (MusicHomeChartStore.toneSet()) {
                    chartTone = MusicHomeChartStore.tone();
                    chartToneCover = MusicHomeChartStore.toneCover();
                }
                boolean same = sameTabs(chartTabs, tabs);
                chartTabs.clear();
                chartTabs.addAll(tabs);
                if (tabs.isEmpty()) {
                    // 签表拉空整卡隐藏、无重试钮：不置空态标志，同实例 onResume 仍可重试
                    binding.homeChartCard.setVisibility(View.GONE);
                    return;
                }
                // 签表一致只 restyle，不重建签行（同实例重进不闪）
                if (same && binding.homeChartTabs.getChildCount() == tabs.size()) restyleChartTabs();
                else buildChartTabs();
                int target = (same && chartSel >= 0 && chartSel < tabs.size()) ? chartSel : sel;
                selectChart(target);
            });
        });
    }

    /** 新实例先用缓存还原：签行首建、tone 先取缓存值、内容直接填，全程不走加载圈；无可用缓存返回 false。 */
    private boolean restoreFromCache() {
        if (!MusicHomeChartStore.hasCache()) return false;
        List<MusicChart> cachedTabs = MusicHomeChartStore.tabs();
        chartTabs.clear();
        chartTabs.addAll(cachedTabs);
        int sel = -1;
        String wantKey = savedChartSelKey != null ? savedChartSelKey : MusicHomeChartStore.selKey();
        if (wantKey != null) {
            for (int i = 0; i < chartTabs.size(); i++) {
                if (chartKey(chartTabs.get(i)).equals(wantKey)) {
                    sel = i;
                    break;
                }
            }
        }
        if (sel < 0) {
            for (int i = 0; i < chartTabs.size(); i++) {
                if (eq(chartTabs.get(i).name, MusicHomeChartStore.selName())) {
                    sel = i;
                    break;
                }
            }
        }
        if (sel < 0) sel = 0;
        chartSel = sel;
        if (MusicHomeChartStore.toneSet()) {
            chartTone = MusicHomeChartStore.tone();
            chartToneCover = MusicHomeChartStore.toneCover();
        }
        chartTracks = MusicHomeChartStore.copyTracks(MusicHomeChartStore.tracks());
        chartTracksKey = chartKey(chartTabs.get(sel));
        chartEmpty = false;
        chartLoaded = true;
        buildChartTabs();
        restyleChartTabs();
        fillChart(chartTabs.get(sel), chartTracks);
        binding.homeChartCard.setVisibility(View.VISIBLE);
        applyShuffleTone();
        return true;
    }

    /** 过期缓存的静默刷新：先验签表——一致只 restyle 再刷当前签曲目（内容不变不重刷）；
     * 不一致（装／删插件改了签表）才重建签行并走默认签；签表拉空保住缓存内容不动、只标过期。 */
    private void refreshChartSilently() {
        final int seq = ++chartLoadSeq;
        Task.execute(() -> {
            List<MusicChart> tabs = collectChartTabs();
            runOnUiThread(() -> {
                if (binding == null || seq != chartLoadSeq) return;
                if (tabs.isEmpty()) {
                    MusicHomeChartStore.markStale();
                    return;
                }
                if (!sameTabs(chartTabs, tabs)) {
                    chartTabs.clear();
                    chartTabs.addAll(tabs);
                    buildChartTabs();
                    int def = -1;
                    for (int i = 0; i < tabs.size(); i++) if (tabs.get(i).name.contains("新歌")) def = i;
                    if (def < 0) def = 0;
                    selectChart(def, true);
                    return;
                }
                restyleChartTabs();
                selectChart(chartSel, true);
            });
        });
    }

    /** 签表采集：站点按 QQ→酷我→其余排序，逐签按固定关键词序取首个命中榜。 */
    private static List<MusicChart> collectChartTabs() {
        List<MusicChart> tabs = new ArrayList<>();
        try {
            List<MusicSite> sites = new ArrayList<>(MusicRepository.sites());
            sites.sort((a, b) -> Integer.compare(chartSiteRank(a), chartSiteRank(b)));
            String[] keys = {"飙升", "新歌", "原创", "热歌"};
            for (String key : keys) {
                MusicChart hit = null;
                for (MusicSite site : sites) {
                    for (MusicChart chart : MusicRepository.charts(site)) {
                        if (chart != null && chart.name != null && chart.name.contains(key)) {
                            hit = chart;
                            break;
                        }
                    }
                    if (hit != null) break;
                }
                if (hit != null) tabs.add(hit);
            }
        } catch (Throwable ignored) {
        }
        return tabs;
    }

    /** 站点序：QQ（平台/名含 qq 或 tx）0、酷我（kw 或 酷我）1、其余 2。 */
    private static int chartSiteRank(MusicSite site) {
        if (site == null) return 2;
        String tag = ((site.platform == null ? "" : site.platform) + (site.name == null ? "" : site.name)).toLowerCase();
        if (tag.contains("qq") || tag.contains("tx")) return 0;
        if (tag.contains("kw") || tag.contains("酷我")) return 1;
        return 2;
    }

    private static String chartKey(MusicChart c) {
        if (c == null) return "";
        return (c.platform == null ? "" : c.platform) + "|" + (c.id == null ? "" : c.id);
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** 签表一致性：名＋平台＋id 全对才算一致。 */
    private static boolean sameTabs(List<MusicChart> a, List<MusicChart> b) {
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            MusicChart x = a.get(i), y = b.get(i);
            if (x == null || y == null) return false;
            if (!eq(x.name, y.name) || !eq(x.platform, y.platform) || !eq(x.id, y.id)) return false;
        }
        return true;
    }

    /** 曲目一致性：id＋标题＋歌手＋封面全对才算不变。 */
    private static boolean sameTracks(List<MusicTrack> a, List<MusicTrack> b) {
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            MusicTrack x = a.get(i), y = b.get(i);
            if (x == null || y == null) return false;
            if (!eq(x.id, y.id) || !eq(x.title, y.title) || !eq(x.artist, y.artist) || !eq(x.cover, y.cover)) return false;
        }
        return true;
    }

    /** 成功拿到内容／分片补封面后写缓存（空结果不写：空态不进缓存，下次进页仍正常拉）。 */
    private void saveChartState() {
        if (chartTabs.isEmpty() || chartTracks.isEmpty()
                || chartSel < 0 || chartSel >= chartTabs.size()) return;
        MusicChart sel = chartTabs.get(chartSel);
        MusicHomeChartStore.save(chartTabs, sel.name, chartKey(sel), chartTracks, chartTone, chartToneCover);
    }

    private void buildChartTabs() {
        LinearLayout box = binding.homeChartTabs;
        box.removeAllViews();
        for (int i = 0; i < chartTabs.size(); i++) {
            final int idx = i;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView label = new TextView(this);
            // 签只改显示名（短名映射）；chartTabs 内 name 不动，标题／选榜仍用全名
            label.setText(chartShortName(chartTabs.get(i).name));
            label.setTextSize(11);
            label.setSingleLine(true);
            View bar = new View(this);
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(ResUtil.dp2px(18), ResUtil.dp2px(3));
            barLp.topMargin = ResUtil.dp2px(5);
            bar.setLayoutParams(barLp);
            cell.addView(label);
            cell.addView(bar);
            cell.setOnClickListener(v -> selectChart(idx));
            box.addView(cell);
        }
    }

    /** 签短名映射（只改显示）：名含「飙升／热歌／新歌／原创」→对应「×榜」，其余沿用全名。 */
    private static String chartShortName(String name) {
        if (name == null) return "";
        if (name.contains("飙升")) return "飙升榜";
        if (name.contains("热歌")) return "热歌榜";
        if (name.contains("新歌")) return "新歌榜";
        if (name.contains("原创")) return "原创榜";
        return name;
    }

    private void restyleChartTabs() {
        LinearLayout box = binding.homeChartTabs;
        for (int i = 0; i < box.getChildCount(); i++) {
            LinearLayout cell = (LinearLayout) box.getChildAt(i);
            TextView label = (TextView) cell.getChildAt(0);
            View bar = cell.getChildAt(1);
            boolean on = i == chartSel;
            label.setTextColor(on ? 0xFF141414 : 0xFF8A8A8A);
            // 选中 800、未选中 500；API 28+ 按字重精确取，低版本退粗体／常规近似
            if (Build.VERSION.SDK_INT >= 28) {
                label.setTypeface(android.graphics.Typeface.create(
                        android.graphics.Typeface.DEFAULT, on ? 800 : 500, false));
            } else {
                label.setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            }
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(ResUtil.dp2px(2));
            // 选中下划线取榜首封面语气色，不再固定红
            bg.setColor(on ? chartTone : Color.TRANSPARENT);
            bar.setBackground(bg);
        }
    }

    private void selectChart(int idx) {
        selectChart(idx, false);
    }

    /** force＝重试／过期静默刷新：绕过同签早退；已有内容时不藏内容不显圈、内容不变不重刷。 */
    private void selectChart(int idx, boolean force) {
        if (idx < 0 || idx >= chartTabs.size()) return;
        MusicChart chart = chartTabs.get(idx);
        boolean keepContent = !chartTracks.isEmpty() && chartKey(chart).equals(chartTracksKey);
        if (!force && idx == chartSel && keepContent) {
            restyleChartTabs();
            return;
        }
        chartSel = idx;
        chartEmpty = false;
        restyleChartTabs();
        binding.homeChartTitle.setText(chart.name);
        if (!keepContent) {
            if (binding.homeChartContent != null) binding.homeChartContent.setVisibility(View.GONE);
            if (binding.homeChartEmpty != null) binding.homeChartEmpty.setVisibility(View.GONE);
            if (binding.homeChartLoading != null) binding.homeChartLoading.setVisibility(View.VISIBLE);
        }
        binding.homeChartCard.setVisibility(View.VISIBLE);
        final int loadSeq = ++chartLoadSeq;
        Task.execute(() -> {
            List<MusicTrack> tracks = new ArrayList<>();
            boolean progressive = false;
            try {
                // 酷我渐进路：先启动分片（与直连并行），直连文字＋时长先出，封面按片后补；非酷我两调皆空走整榜路
                MusicRepository.startKwChartSlices(chart);
                List<MusicTrack> direct = MusicRepository.chartTracksDirect(chart);
                if (direct != null && !direct.isEmpty()) {
                    tracks.addAll(direct);
                    progressive = true;
                } else {
                    List<MusicTrack> got = MusicRepository.chartTracks(chart);
                    if (got != null) tracks.addAll(got);
                }
            } catch (Throwable ignored) {
            }
            final List<MusicTrack> result = tracks;
            final boolean prog = progressive;
            runOnUiThread(() -> {
                if (binding == null || idx != chartSel || loadSeq != chartLoadSeq) return;
                if (result.isEmpty()) {
                    // 已有内容时刷新拉空：保住旧内容，不翻空态、不清曲目（断网一次不把卡刷没）
                    if (keepContent) return;
                    // 当前签拉空：卡体保留显空态（「暂未获取到榜单」＋「点击重试」），签行保留可切榜；
                    // chartEmpty 置位后 onResume 不再重试转圈，只点重试／切签重拉
                    chartTracks = result;
                    chartTracksKey = null;
                    chartLoaded = false;
                    chartEmpty = true;
                    if (binding.homeChartContent != null) binding.homeChartContent.setVisibility(View.GONE);
                    if (binding.homeChartLoading != null) binding.homeChartLoading.setVisibility(View.GONE);
                    if (binding.homeChartEmpty != null) binding.homeChartEmpty.setVisibility(View.VISIBLE);
                    binding.homeChartCard.setVisibility(View.VISIBLE);
                    return;
                }
                // 已有内容且内容不变：不重刷，只续缓存有效期（本次实拉已验证，续期不会把旧签表冻住）
                if (keepContent && sameTracks(chartTracks, result)) {
                    MusicHomeChartStore.renew();
                    return;
                }
                chartTracks = result;
                chartTracksKey = chartKey(chart);
                chartLoaded = true;
                chartEmpty = false;
                fillChart(chart, result);
                saveChartState();
                binding.homeChartCard.setVisibility(View.VISIBLE);
                applyShuffleTone();
                if (!prog) return;
                // 酷我封面按片陆续补：先回先上，只刷变了的封面
                MusicRepository.fetchKwChartCovers(chart, new KwChartSource.CoverListener() {
                    @Override
                    public void onShard(int pn, List<MusicTrack> shard) {
                        runOnUiThread(() -> {
                            if (binding == null || isFinishing() || isDestroyed()) return;
                            if (idx != chartSel || loadSeq != chartLoadSeq) return;
                            List<Integer> changed = applyChartCovers(shard);
                            if (changed.isEmpty()) return;
                            fillChartCoversAt(changed);
                            // 分片补上榜首封面后补一次 tone：下划线跟新榜首封面走
                            if (changed.contains(0)) refreshChartTone(chartTracks);
                            applyShuffleTone();
                            saveChartState();
                        });
                    }
                });
            });
        });
    }

    private void fillChart(MusicChart chart, List<MusicTrack> tracks) {
        binding.homeChartTitle.setText(chart.name);
        fillChartCovers();
        fillEntryCardData(tracks, true);
        TextView[] names = {binding.homeChartName1, binding.homeChartName2, binding.homeChartName3};
        TextView[] artists = {binding.homeChartArtist1, binding.homeChartArtist2, binding.homeChartArtist3};
        View[] rows = {binding.homeChartRow1, binding.homeChartRow2, binding.homeChartRow3};
        for (int i = 0; i < rows.length; i++) {
            if (i < tracks.size()) {
                rows[i].setVisibility(View.VISIBLE);
                names[i].setText(tracks.get(i).title);
                artists[i].setText(tracks.get(i).artist);
            } else rows[i].setVisibility(View.GONE);
        }
        if (binding.homeChartContent != null) binding.homeChartContent.setVisibility(View.VISIBLE);
        if (binding.homeChartLoading != null) binding.homeChartLoading.setVisibility(View.GONE);
        if (binding.homeChartEmpty != null) binding.homeChartEmpty.setVisibility(View.GONE);
        refreshChartTone(tracks);
    }

    /** 选中签下划线语气色＝榜首封面主色（同一封面不重复取）。 */
    private void refreshChartTone(List<MusicTrack> tracks) {
        if (binding == null || tracks == null || tracks.isEmpty()) return;
        String cover = tracks.get(0).cover;
        if (cover == null || cover.isEmpty() || cover.equals(chartToneCover)) return;
        chartToneCover = cover;
        try {
            ImgUtil.load(cover, new com.fongmi.android.tv.impl.CustomTarget<android.graphics.Bitmap>() {
                @Override
                public void onResourceReady(android.graphics.Bitmap bitmap,
                        com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> transition) {
                    if (binding == null || bitmap == null) return;
                    try {
                        // D-09：迟到的取色回调只许染「仍是当前签榜首封面」的色——切签后旧签封面
                        // 才取完色时整段丢弃（不改 tone、不写缓存、不 restyle），否则新签下划线
                        // 会被旧签色染上、新签取色再失败就一直残留到下次成功。
                        if (chartTracks.isEmpty() || !cover.equals(chartTracks.get(0).cover)) return;
                        chartTone = CoverColor.dominantColor(bitmap);
                        // tone 落袋即写缓存：下次新实例先用此 tone 画下划线，不先红再跳变
                        MusicHomeChartStore.saveTone(chartTone, chartToneCover);
                        restyleChartTabs();
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                public void onLoadCleared(android.graphics.drawable.Drawable placeholder) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** 首页卡只刷 3 张封面（名／歌手行不动，首屏已出；封面到了再补调取色）。 */
    private void fillChartCovers() {
        ImageView[] covers = {binding.homeChartCover1, binding.homeChartCover2, binding.homeChartCover3};
        for (int i = 0; i < covers.length; i++) {
            if (i < chartTracks.size()) ImgUtil.loadRounded(chartTracks.get(i).title, chartTracks.get(i).cover, covers[i]);
            else covers[i].setImageDrawable(null);
        }
    }

    /** 按 id 把一片酷我封面贴到已显示的直连列表上，只动封面字段；返回卡面（前 3 张）里变了的位置。 */
    private List<Integer> applyChartCovers(List<MusicTrack> shard) {
        List<Integer> changed = new ArrayList<>();
        if (shard == null || shard.isEmpty() || chartTracks.isEmpty()) return changed;
        Map<String, String> shardCover = new HashMap<>();
        for (MusicTrack s : shard) {
            if (s == null || s.id == null || s.cover == null || s.cover.isEmpty()) continue;
            shardCover.put(s.id, s.cover);
        }
        for (int i = 0; i < chartTracks.size(); i++) {
            MusicTrack cur = chartTracks.get(i);
            if (cur == null || cur.id == null) continue;
            String nc = shardCover.get(cur.id);
            if (nc == null || nc.equals(cur.cover)) continue;
            cur.cover = nc;
            if (i < 3) changed.add(i);
        }
        return changed;
    }

    /** 只刷指定位置的卡面封面（分片补封面用；不变的位置不重发图片请求，不逐张弹出）。 */
    private void fillChartCoversAt(List<Integer> positions) {
        ImageView[] covers = {binding.homeChartCover1, binding.homeChartCover2, binding.homeChartCover3};
        for (int pos : positions) {
            if (pos < covers.length && pos < chartTracks.size()) {
                ImgUtil.loadRounded(chartTracks.get(pos).title, chartTracks.get(pos).cover, covers[pos]);
            }
        }
    }

    private void playChartFrom(int index) {
        if (chartTracks.isEmpty() || index >= chartTracks.size()) return;
        // 入队写队列级来源＝榜单名、route＝home（榜单卡在首页，落点＝首页）；
        // 榜单起播不是随心听队列，副题读服务来源自然回落「随机漫游」，不用清粘性字段。
        MusicChart chart = (chartSel >= 0 && chartSel < chartTabs.size()) ? chartTabs.get(chartSel) : null;
        MusicController.get().play(new ArrayList<>(chartTracks), index,
                chart == null ? null : chart.name, "home", null);
        MusicPlayerActivity.start(this);
    }

    /** 三彩卡数据填充：猜卡封面堆取前 3 张有封面的；榜卡小列表取前 3 名「名次. 歌名」。
     * 只用真实数据填，无数据整块不显、不编假条目；榜单与历史谁后到谁刷新。 */
    private void fillEntryCardData(List<MusicTrack> tracks, boolean chartList) {
        if (binding == null || tracks == null) return;
        try {
            List<String> covers = new ArrayList<>();
            for (MusicTrack t : tracks) {
                if (t != null && t.cover != null && !t.cover.isEmpty()) covers.add(t.cover);
                if (covers.size() >= 3) break;
            }
            if (!covers.isEmpty()) {
                ImageView[] stack = {binding.homeGuessCover1, binding.homeGuessCover2, binding.homeGuessCover3};
                binding.homeGuessStack.setVisibility(View.VISIBLE);
                for (int i = 0; i < stack.length; i++) {
                    if (i < covers.size()) {
                        stack[i].setVisibility(View.VISIBLE);
                        ImgUtil.loadRounded("", covers.get(i), stack[i]);
                    } else stack[i].setVisibility(View.GONE);
                }
            }
            if (chartList && !tracks.isEmpty()) {
                TextView[] lines = {binding.homeCardChart1, binding.homeCardChart2, binding.homeCardChart3};
                binding.homeCardChartList.setVisibility(View.VISIBLE);
                for (int i = 0; i < lines.length; i++) {
                    if (i < tracks.size() && tracks.get(i) != null) {
                        lines[i].setVisibility(View.VISIBLE);
                        lines[i].setText((i + 1) + ". " + tracks.get(i).displayTitle());
                    } else lines[i].setVisibility(View.GONE);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ================= ⋮ 菜单（顶上滑下的白色圆角卡，非全屏） =================
    // 行形态照瓜子菜单行：行内边距 16×14dp、图标框 32dp／圆角 9dp／图标色 12% 底、
    // 图标到标题 14dp、粗标题、右 12sp 灰字、按压 0.98；图标色插件管理 #AF52DE、设置 #FF2D55。
    // 插件行右字显实际插件数（星辰还有 FreeJS，不抄瓜子「LX 音源插件」字样）。

    private void showMoreMenu(View anchor) {
        if (isFinishing() || isDestroyed()) return;
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setDimAmount(0f);
            dialog.getWindow().setGravity(Gravity.TOP);
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.home_bg_menu);
        card.setElevation(dp(6));
        card.setClickable(true);
        final LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int side = dp(16);
        cardLp.leftMargin = side;
        cardLp.rightMargin = side;
        cardLp.topMargin = getStatusBarHeight() + dp(8);
        card.setVisibility(View.INVISIBLE);
        root.addView(card, cardLp);

        final boolean[] closing = new boolean[]{false};
        final java.util.function.Consumer<Runnable> dismissThen = (after) -> {
            if (closing[0]) return;
            closing[0] = true;
            card.animate().translationY(-(card.getHeight() + cardLp.topMargin)).setDuration(220)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .withEndAction(() -> {
                        try {
                            dialog.dismiss();
                        } catch (Throwable ignored) {
                        }
                        if (after != null) after.run();
                    }).start();
        };

        int pluginCount = 0;
        try {
            pluginCount = MusicSourceManager.get().listSites().size();
        } catch (Throwable ignored) {
        }
        card.addView(menuRow(R.drawable.ic_music_puzzle, 0xFFAF52DE,
                getString(R.string.music_plugin_manage),
                pluginCount + " " + getString(R.string.music_plugins),
                () -> dismissThen.accept(() -> MusicListActivity.startSites(this))));
        View divider = new View(this);
        divider.setBackgroundColor(0x14000000);
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dividerLp.leftMargin = dp(62);
        card.addView(divider, dividerLp);
        card.addView(menuRow(R.drawable.ic_control_setting, 0xFFFF2D55,
                getString(R.string.music_setting),
                getString(R.string.music_menu_setting_sub),
                () -> dismissThen.accept(() -> MusicSettingsActivity.start(this))));

        root.setOnClickListener(v -> dismissThen.accept(null));
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK
                    && event.getAction() == android.view.KeyEvent.ACTION_UP) {
                dismissThen.accept(null);
                return true;
            }
            return false;
        });
        dialog.setContentView(root);
        try {
            dialog.show();
        } catch (Throwable ignored) {
            return;
        }
        // 弹窗内容区点按即收（补根布局点击链路未触发的漏网点按）；返回键仍走上面的滑收
        try {
            View decorContent = dialog.findViewById(android.R.id.content);
            if (decorContent != null) decorContent.setOnClickListener(v -> dismissThen.accept(null));
        } catch (Throwable ignored) {
        }
        // 入场：先量高再从顶上滑下（INVISIBLE 起手，不闪一下）；滑入 280ms／滑出 220ms
        card.post(() -> {
            if (closing[0]) return;
            int h = card.getHeight() + cardLp.topMargin;
            card.setTranslationY(-h);
            card.setVisibility(View.VISIBLE);
            card.animate().translationY(0).setDuration(280)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        });
    }

    /** ⋮ 菜单行：图标色 12% 底的圆角方块＋粗标题＋右灰小字。 */
    private LinearLayout menuRow(int iconRes, int iconColor, String title, String sub, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable box = new GradientDrawable();
        box.setShape(GradientDrawable.RECTANGLE);
        box.setCornerRadius(dp(9));
        box.setColor((0x1F << 24) | (iconColor & 0x00FFFFFF));
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(iconColor));
        icon.setBackground(box);
        icon.setPadding(dp(6), dp(6), dp(6), dp(6));
        row.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(16);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setTextColor(0xFF1A1A1A);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.leftMargin = dp(14);
        row.addView(tvTitle, titleLp);
        View spacer = new View(this);
        row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView tvSub = new TextView(this);
        tvSub.setText(sub);
        tvSub.setTextSize(12);
        tvSub.setTextColor(0xFF9A9AA0);
        tvSub.setSingleLine(true);
        row.addView(tvSub, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        attachCardPress(row, 0.98f);
        row.setOnClickListener(v -> action.run());
        return row;
    }

    private int getStatusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) return getResources().getDimensionPixelSize(id);
        return dp(24);
    }

    // ================= 继续播放（白卡四封面横排） =================

    /** 本次刷新用的继续播放列表快照（封面点击按下标取，与填充时同一份，防刷新间隙里历史变了点错首）。 */
    private List<MusicTrack> continueTracks = new ArrayList<>();

    /**
     * 数据走现行历史（MusicStore.loadRecent，新→旧）前 4 首封面横排；不足按实数显、
     * 零首整卡 GONE 不留空洞、不编假数据。历史为空但有活会话／断点队列时回落显
     * 当前曲 1 张，不让正在播的歌在首页失踪。点封面＝以历史整列为队列从该首播；
     * 圆键＝有会话 toggle、无会话按断点 playFrom。
     */
    private void refreshContinue() {
        if (binding == null) return;
        try {
            List<MusicTrack> recent = MusicStore.loadRecent();
            List<MusicTrack> show = new ArrayList<>();
            if (recent != null) {
                for (MusicTrack t : recent) {
                    if (t != null) show.add(t);
                    if (show.size() >= 4) break;
                }
            }
            if (show.isEmpty()) {
                MusicTrack cur = null;
                try {
                    if (MusicController.get().isActive()) cur = MusicController.get().current();
                } catch (Throwable ignored) {
                }
                if (cur == null) {
                    List<MusicTrack> q = MusicStore.loadQueue();
                    if (q != null && !q.isEmpty()) {
                        int idx = MusicStore.loadIndex();
                        if (idx < 0 || idx >= q.size()) idx = 0;
                        cur = q.get(idx);
                    }
                }
                if (cur != null) show.add(cur);
            }
            continueTracks = show;
            if (show.isEmpty()) {
                binding.homeContinueCard.setVisibility(View.GONE);
                return;
            }
            // 猜卡封面堆回落数据源：榜单封面未到时先用历史封面顶上（榜单到了会再刷）
            fillEntryCardData(show, false);
            binding.homeContinueCard.setVisibility(View.VISIBLE);
            binding.homeContinueTitle.setText(show.get(0).displayTitle());
            // 封面横排每次刷新重建（最多 4 张）：等宽 weight 铺满、张间 8dp、高 72dp
            LinearLayout row = binding.homeContinueCovers;
            row.removeAllViews();
            float density = getResources().getDisplayMetrics().density;
            for (int i = 0; i < show.size(); i++) {
                final int idx = i;
                MusicTrack t = show.get(i);
                ImageView iv = new ImageView(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, Math.round(72 * density), 1f);
                if (i > 0) lp.leftMargin = Math.round(8 * density);
                iv.setLayoutParams(lp);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setClickable(true);
                iv.setFocusable(true);
                iv.setContentDescription(t.displayTitle());
                if (t.cover == null || t.cover.isEmpty()) {
                    iv.setImageDrawable(new com.fongmi.android.tv.utils.CoverTextDrawable(
                            t.displayTitle(), false, 10 * density));
                } else {
                    ImgUtil.loadRounded(t.displayTitle(), t.cover, iv);
                }
                iv.setOnClickListener(v -> playContinueAt(idx));
                row.addView(iv);
            }
            boolean playing = false;
            try {
                playing = MusicController.get().isActive() && MusicController.get().isPlaying();
            } catch (Throwable ignored) {
            }
            binding.homeContinuePlay.setImageResource(playing ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
        } catch (Throwable ignored) {
            try {
                binding.homeContinueCard.setVisibility(View.GONE);
            } catch (Throwable ignored2) {
            }
        }
    }

    /** 点继续卡封面：以现行历史整列为队列从该首开播并进播放页（队列来源＝最近播放，落点首页）。 */
    private void playContinueAt(int idx) {
        try {
            List<MusicTrack> recent = MusicStore.loadRecent();
            if (recent == null || recent.isEmpty() || idx < 0 || idx >= recent.size()) {
                // 历史与填充快照不一致（刚被清）时退回快照本身，不点空
                if (continueTracks.isEmpty() || idx < 0 || idx >= continueTracks.size()) return;
                recent = new ArrayList<>(continueTracks);
                MusicController.get().play(recent, idx, "最近播放", "home", null);
            } else {
                MusicController.get().play(new ArrayList<>(recent), idx, "最近播放", "home", null);
            }
            MusicPlayerActivity.start(this);
        } catch (Throwable ignored) {
        }
    }

    /** 继续播放圆键：有会话＝切换播放/暂停；只有断点＝按存盘队列/序号/进度续播并进播放页。 */
    private void onContinuePlay() {
        try {
            if (MusicController.get().isActive()) {
                MusicController.get().toggle();
                refreshContinue();
                return;
            }
            List<MusicTrack> q = MusicStore.loadQueue();
            if (q == null || q.isEmpty()) return;
            int idx = MusicStore.loadIndex();
            if (idx < 0 || idx >= q.size()) idx = 0;
            long pos = MusicStore.loadPosition();
            MusicController.get().playFrom(new ArrayList<>(q), idx, pos,
                    MusicStore.loadQueueSource(), MusicStore.loadQueueRoute(), MusicStore.loadQueueRouteParams());
            MusicPlayerActivity.start(this);
        } catch (Throwable ignored) {
        }
    }

    // ================= 五快捷计数（真数据，异步回填；数字自带单位） =================
    // 收藏＝N首、歌单＝N个歌单、下载＝N首、本地＝N首、历史＝N条（loadRecent 上限 50 条，
    // 满 50 只标「近50条」不冒充总数）。

    private void refreshCounts() {
        if (binding == null) return;
        MusicLibrary.favoriteCount(n -> {
            if (binding != null && !isFinishing() && !isDestroyed()) binding.homeCountFavorite.setText(n + "首");
        });
        MusicLibrary.playlists(list -> {
            if (binding != null && !isFinishing() && !isDestroyed()) binding.homeCountSheets.setText((list == null ? 0 : list.size()) + "个歌单");
        });
        MusicDownloadStore.count(n -> {
            if (binding != null && !isFinishing() && !isDestroyed()) binding.homeCountDownload.setText(n + "首");
        });
        try {
            int recentN = MusicStore.loadRecent().size();
            binding.homeCountRecent.setText(recentN >= 50 ? "近50条" : recentN + "条");
        } catch (Throwable ignored) {
            binding.homeCountRecent.setText("0条");
        }
        // 本地数：DeviceMusicStore.scan 是阻塞扫描，走后台线程回填
        Task.execute(() -> {
            int n = 0;
            try {
                n = DeviceMusicStore.scan(this).size();
            } catch (Throwable ignored) {
            }
            int count = n;
            runOnUiThread(() -> {
                if (binding != null) binding.homeCountDevice.setText(count + "首");
            });
        });
        refreshReport();
    }

    /** 聆听报告卡：只用本地数据算——最近播放数（上限 50 条）、收藏数、最爱歌手
     * （最近播放里出现最多的非占位歌手，并列取先出现）；最近播放为空整卡隐藏，不编假数据。 */
    private void refreshReport() {
        if (binding == null) return;
        List<MusicTrack> recent;
        try {
            recent = MusicStore.loadRecent();
        } catch (Throwable ignored) {
            recent = null;
        }
        if (recent == null || recent.isEmpty()) {
            binding.homeReportCard.setVisibility(View.GONE);
            return;
        }
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (MusicTrack t : recent) {
            // 占位歌手（未知／未知歌手等）不计入最爱统计；isPlaceholderArtist 对空串也返回 true
            if (t == null || com.fongmi.android.tv.utils.LocalTrackMeta.isPlaceholderArtist(t.artist)) continue;
            String a = t.artist.trim();
            counts.put(a, counts.containsKey(a) ? counts.get(a) + 1 : 1);
        }
        String top = "";
        int topN = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > topN) {
                topN = e.getValue();
                top = e.getKey();
            }
        }
        final int recentN = recent.size();
        final String topArtist = top;
        MusicLibrary.favoriteCount(n -> {
            if (binding == null || isFinishing() || isDestroyed()) return;
            binding.homeReportStats.setText("最近播放 " + recentN + " 首"
                    + (recentN >= 50 ? "（近50条内）" : "") + " · 收藏 " + n + " 首");
            if (topArtist.isEmpty()) {
                binding.homeReportTop.setVisibility(View.GONE);
            } else {
                binding.homeReportTop.setVisibility(View.VISIBLE);
                binding.homeReportTop.setText("最爱歌手：" + topArtist);
            }
            binding.homeReportCard.setVisibility(View.VISIBLE);
        });
    }

    /** 报告卡落点：树内无独立统计页，落到播放历史页（报告数据的完整明细）。 */
    private void openFullReport() {
        MusicHistoryActivity.startHistory(this);
    }

    /** 播放历史入口：走历史页（旧的首页对话框不再用）。 */
    private void showRecent() {
        MusicHistoryActivity.startHistory(this);
    }

    // ================= 按压反馈与封面圆角 =================

    /** 卡片按压反馈（只加触感，不吞点击）：默认 0.96 微缩、回弹同长。 */
    private void attachCardPress(View v) {
        attachCardPress(v, 0.96f);
    }

    private void attachCardPress(View v, float scale) {
        if (v == null) return;
        v.setOnTouchListener((view, e) -> {
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(scale).scaleY(scale).setDuration(150)
                        .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start();
            } else if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP
                    || e.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(150)
                        .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start();
            }
            return false;
        });
    }

    /** 入口卡／心情播放钮的即时缩放（按下缩、抬起回；返回 false 不吞点击）。 */
    private void pressScale(View v) {
        pressScale(v, 0.98f);
    }

    private void pressScale(View v, float scale) {
        v.setOnTouchListener((view, e) -> {
            int a = e.getActionMasked();
            if (a == android.view.MotionEvent.ACTION_DOWN) {
                view.setScaleX(scale);
                view.setScaleY(scale);
            } else if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) {
                view.setScaleX(1f);
                view.setScaleY(1f);
            }
            return false;
        });
    }

    /** 榜单三封面按 12dp 圆角裁切（见 initView 处注释）。 */
    private void applyChartCoverCorners() {
        android.view.ViewOutlineProvider provider = new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), ResUtil.dp2px(12));
            }
        };
        ImageView[] covers = {binding.homeChartCover1, binding.homeChartCover2, binding.homeChartCover3};
        for (ImageView cover : covers) {
            if (cover == null) continue;
            cover.setOutlineProvider(provider);
            cover.setClipToOutline(true);
        }
    }

    // ================= 随心听副题与卡面取色 =================

    /**
     * 随心听副题：读服务队列来源——随心听起播时队列来源就是 home_shuffle（「随心听」），
     * 现队列来源是它且正在播即显「正在播放」，其它一律回落「随机漫游」。
     * 旧粘性字段方案已废（字段从未被写是副题恒为死文案的根因）；服务来源是现队列
     * 实时值，换队／暂停／重建都自然回落。光晕脉动与副题同用此判定，不分叉。
     */
    private void refreshShuffleSubtitle() {
        if (binding == null) return;
        boolean playingShuffle = getString(R.string.home_shuffle).equals(MusicController.get().queueSource())
                && MusicController.get().isPlaying();
        binding.homeShuffleSub.setText(playingShuffle
                ? R.string.home_shuffle_playing : R.string.home_shuffle_sub);
        binding.homeHaloShuffle.setPulsing(playingShuffle);
    }

    /**
     * 随心听卡面取色：只算一次——三基色各向「榜首封面主色向白混 0.75」混 0.1，圆角 20dp。
     * 取色源是星辰侧口径：首页榜单榜首封面（瓜子是每日歌曲封面，星辰无每日堆叠）；
     * 无榜单／无封面／取色失败保持布局基色原样。
     */
    private boolean shuffleToneApplied;

    private void applyShuffleTone() {
        if (binding == null || shuffleToneApplied || chartTracks.isEmpty()) return;
        String cover = chartTracks.get(0).cover;
        if (cover == null || cover.isEmpty()) return;
        shuffleToneApplied = true;
        try {
            ImgUtil.load(cover, new com.fongmi.android.tv.impl.CustomTarget<android.graphics.Bitmap>() {
                @Override
                public void onResourceReady(android.graphics.Bitmap bitmap,
                        com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> transition) {
                    if (binding == null || bitmap == null) return;
                    try {
                        int tone = CoverColor.dominantColor(bitmap);
                        int lightTone = MusicHomeMood.mixWhite(tone, 0.75f);
                        int[] base = {0xFFB8EFF9, 0xFFBCEEDD, 0xFFE8FAA8};
                        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                                new int[]{MusicHomeMood.mixColors(base[0], lightTone, 0.1f),
                                        MusicHomeMood.mixColors(base[1], lightTone, 0.1f),
                                        MusicHomeMood.mixColors(base[2], lightTone, 0.1f)});
                        bg.setCornerRadius(dp(20));
                        binding.homeCardShuffle.setBackground(bg);
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                public void onLoadCleared(android.graphics.drawable.Drawable placeholder) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    // ================= 心情电台卡 =================
    // 数据表／渐变／tone／颜色公式在 MusicHomeMood（照瓜子 HomeScreenKt:314 抠值）。
    // 建台链走星辰自家落雪插件搜索（用户令定，不走瓜子服务器）：只搜落雪站点、
    // 关键词＝心情名、上限 30 首。点 pill＝选中并建台；点播放钮＝本心情队列只
    // toggle、否则建台。「是本心情」判定＝MusicStore 快照与现队列同序同曲。
    // 状态文案：建台中「正在连接」／本心情在播「暂停收听」／本心情暂停「继续收听」／
    // 非本心情但别队在播「暂停收听」／其余「开始收听」。状态只在 onResume 与本地动作后刷。

    private String moodSelected;
    private boolean moodBuilding;
    private final List<View> moodPillViews = new ArrayList<>();

    /** 默认选中心情：当前队列标签是「心情电台 · XX」取 XX（须在 12 心情表内），否则取怀旧。 */
    private String moodFromQueueSource() {
        String src = MusicController.get().queueSource();
        if (src != null && src.startsWith("心情电台 · ")) {
            String name = src.substring("心情电台 · ".length());
            if (MusicHomeMood.isMood(name)) return name;
        }
        return MusicHomeMood.DEFAULT;
    }

    private void initMoodCard() {
        moodSelected = moodFromQueueSource();
        // X-08：重建恢复优先于反推——刚点选还没成队列时反推不到，靠 savedInstanceState 带回选中态
        if (savedMood != null && MusicHomeMood.isMood(savedMood)) moodSelected = savedMood;
        for (String[] m : MusicHomeMood.table()) {
            final String name = m[0];
            LinearLayout pill = new LinearLayout(this);
            pill.setOrientation(LinearLayout.HORIZONTAL);
            pill.setGravity(Gravity.CENTER_VERTICAL);
            pill.setPadding(dp(12), dp(8), dp(12), dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
            lp.setMarginEnd(dp(8));
            pill.setLayoutParams(lp);
            View dot = new View(this);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(5), dp(5));
            dlp.setMarginEnd(dp(6));
            dot.setLayoutParams(dlp);
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(MusicHomeMood.tone(name));
            dot.setBackground(dotBg);
            TextView tv = new TextView(this);
            tv.setText(name);
            tv.setTextSize(12);
            tv.setLineHeight(dp(16));
            tv.setTextColor(Color.parseColor("#1C1C1E"));
            pill.addView(dot);
            pill.addView(tv);
            pill.setTag(name);
            pill.setOnClickListener(v -> {
                moodSelected = name;
                updateMoodUI();
                buildMood(name);
            });
            binding.moodPills.addView(pill);
            moodPillViews.add(pill);
        }
        pressScale(binding.btnMoodPlay, 0.9f);
        binding.btnMoodPlay.setOnClickListener(v -> {
            if (isThisMoodQueue()) {
                MusicController.get().toggle();
                refreshMoodState();
            } else {
                buildMood(moodSelected);
            }
        });
        updateMoodUI();
    }

    /** 当前队列是否为本心情建台队列：MusicStore 快照非空且与现队列同序同曲。 */
    private boolean isThisMoodQueue() {
        String snap = MusicStore.getMoodQueueKeys();
        if (snap == null || snap.isEmpty()) return false;
        List<String> cur = trackKeys(MusicController.get().queue());
        if (cur.isEmpty()) return false;
        StringBuilder sb = new StringBuilder();
        for (String k : cur) sb.append(k).append('\n');
        return sb.toString().equals(snap);
    }

    private void refreshMoodState() {
        if (binding == null) return;
        updateMoodUI();
    }

    private void updateMoodUI() {
        if (binding == null || moodSelected == null) return;
        int tone = MusicHomeMood.tone(moodSelected);
        // 卡渐变：横向三段原始值向白混合 0.08/0.20/0.32，圆角 20dp
        String[] g = MusicHomeMood.gradient(moodSelected);
        GradientDrawable cardBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{MusicHomeMood.mixWhite(Color.parseColor(g[0]), 0.08f),
                        MusicHomeMood.mixWhite(Color.parseColor(g[1]), 0.20f),
                        MusicHomeMood.mixWhite(Color.parseColor(g[2]), 0.32f)});
        cardBg.setCornerRadius(dp(20));
        binding.homeMoodCard.setBackground(cardBg);
        binding.tvMoodName.setText(moodSelected);
        binding.tvMoodCopy.setText(MusicHomeMood.copy(moodSelected));
        // pills：选中 tone 0.18 底＋700 字重，未选中白 50% 底＋medium
        for (View pill : moodPillViews) {
            boolean sel = moodSelected.equals(pill.getTag());
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(18));
            bg.setColor(sel ? MusicHomeMood.alphaColor(tone, 0.18f) : 0x80FFFFFF);
            pill.setBackground(bg);
            TextView tv = (TextView) ((ViewGroup) pill).getChildAt(1);
            tv.setTypeface(sel ? android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                    : android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        }
        // 播放钮：横向渐变 tone→向白混 0.22、圆；播/停图标内距 8/11dp、白 tint
        boolean playing = isThisMoodQueue() && MusicController.get().isPlaying();
        GradientDrawable btnBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{tone, MusicHomeMood.mixWhite(tone, 0.22f)});
        btnBg.setShape(GradientDrawable.OVAL);
        binding.btnMoodPlay.setBackground(btnBg);
        binding.btnMoodPlay.setImageResource(playing ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
        binding.btnMoodPlay.setPadding(dp(playing ? 11 : 8), dp(playing ? 11 : 8), dp(playing ? 11 : 8), dp(playing ? 11 : 8));
        binding.btnMoodPlay.setColorFilter(Color.WHITE);
        // 波形：tone 色，播/停振幅随 MoodWaveView 内部口径
        binding.moodWave.setTone(tone);
        binding.moodWave.setPlaying(playing);
        binding.tvMoodState.setText(moodBuilding ? "正在连接"
                : isThisMoodQueue() ? (MusicController.get().isPlaying() ? "暂停收听" : "继续收听")
                : MusicController.get().isPlaying() ? "暂停收听" : "开始收听");
    }

    /** 按心情名走落雪插件搜索拼歌单播；搜不到提示同款文案。 */
    private void buildMood(String mood) {
        if (moodBuilding) return;
        moodBuilding = true;
        updateMoodUI();
        Task.execute(() -> {
            List<MusicTrack> out = new ArrayList<>();
            try {
                for (MusicSite site : MusicRepository.searchableSites()) {
                    if (!MusicSourceManager.isLx(site)) continue;
                    try {
                        List<MusicTrack> hits = MusicRepository.searchSite(site, mood);
                        if (hits != null) out.addAll(hits);
                    } catch (Throwable ignored) {
                    }
                    if (out.size() >= 30) break;
                }
            } catch (Throwable ignored) {
            }
            final List<MusicTrack> tracks = out.size() > 30 ? new ArrayList<>(out.subList(0, 30)) : out;
            runOnUiThread(() -> {
                moodBuilding = false;
                if (tracks.isEmpty()) {
                    Notify.show("暂时没找到这份心情音乐，请稍后重试");
                    updateMoodUI();
                    return;
                }
                List<String> keys = trackKeys(tracks);
                StringBuilder sb = new StringBuilder();
                for (String k : keys) sb.append(k).append('\n');
                MusicStore.setMoodQueueKeys(sb.toString());
                MusicController.get().play(new ArrayList<>(tracks), 0, "心情电台 · " + mood, "home", null);
                updateMoodUI();
                MusicPlayerActivity.start(this);
            });
        });
    }

    /** 曲目键：优先 id，无 id 时用「标题\n歌手」拼键（同曲不同源 id 不同即判为不同队列）。 */
    private static List<String> trackKeys(List<MusicTrack> tracks) {
        List<String> keys = new ArrayList<>();
        if (tracks == null) return keys;
        for (MusicTrack t : tracks) {
            if (t == null) keys.add("");
            else if (t.id != null && !t.id.isEmpty()) keys.add(t.id);
            else keys.add((t.title == null ? "" : t.title) + "\n" + (t.artist == null ? "" : t.artist));
        }
        return keys;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
