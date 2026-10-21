package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.music.MusicChart;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.api.music.MusicSourceManager;
import com.fongmi.android.tv.databinding.ActivityMusicBinding;
import com.fongmi.android.tv.databinding.HomeDialogRecentBinding;
import com.fongmi.android.tv.music.DeviceMusicStore;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicDownloadStore;
import com.fongmi.android.tv.music.MusicLibrary;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.music.MusicRepository;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.CoverColor;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 音乐首页（v7 定稿，2026-10-21 用户逐版改定）：
 * ① 搜索胶囊条（占位「搜索歌曲、歌手、专辑」）＋右侧竖三点 ⋮ 菜单（顶上滑下的白色圆角卡，非全屏）；
 * ② 白色圆角卡内五个快捷横排：音乐收藏／我的歌单／本地音乐（正中）／播放历史／下载音乐，
 *    彩色图标＋名字＋下方真实数量（异步回填，取不到显示 0）；
 * ③ 三张渐变入口卡垫底：猜你喜欢·每日精选（粉）／排行榜·此刻热门（蓝绿）／随心听（绿黄，
 *    副题动态：未播随心听队列时「随机漫游」，正在播随心听队列时「正在播放」，见 refreshShuffleSubtitle）。
 * 底部迷你条是 ListenMiniBar 悬浮条（只在音乐首页显示，白名单机制在迷你条侧），本页不内嵌。
 * 源列表不放首页；顶栏无返回箭头；界面只放名字和状态，无介绍文字。
 */
public class MusicActivity extends BaseActivity {

    private ActivityMusicBinding binding;

    // 榜单卡（06:20 令）：榜签只映射酷我＋QQ 真榜，按关键词对齐参照图四类
    private final List<MusicChart> homeChartTabs = new ArrayList<>();
    private List<MusicTrack> homeChartTracks = new ArrayList<>();
    private int homeChartSel = -1;
    private boolean homeChartLoaded;

    // 随心听队列来源类型（瓜子 HomeScreenKt:2843 的 z5 条件：queueSource.type == "shuffle_listen" 且正在播）。
    // 只有本页随心听起播的队列才记 "shuffle_listen"；其它来源的队列不记，副题即回落「随机漫游」。
    // 另存一份打乱后队列的曲目键快照：回首页时若服务队列已不是这份队列（别处起了新队列），
    // 即使本字段仍为 shuffle_listen 也回落，不做「只在点击时改字、永不回落」的假态。
    private String homeQueueSourceType;
    private List<String> shuffleQueueKeys;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, MusicActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityMusicBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        // 首页离边 16dp 实机生效：根布局已不吃 fitsSystemWindows（旧版系统左右 inset 0 会盖掉 XML 边距致贴边），
        // 这里直接定死左右 16dp、顶＝状态栏高＋12dp、底 16dp；不依赖 inset 分发，旋转／异形屏也不丢边距。
        // 圆角不动（搜索 24dp、三彩卡 20dp、五快捷 16dp）；三段上距见布局（32/40/40dp）。
        View homeRoot = binding.getRoot();
        int sbHome = 0;
        int sbRes = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (sbRes > 0) sbHome = getResources().getDimensionPixelSize(sbRes);
        float homeDensity = getResources().getDisplayMetrics().density;
        homeRoot.setPadding(Math.round(16 * homeDensity), sbHome + Math.round(12 * homeDensity),
                Math.round(16 * homeDensity), Math.round(16 * homeDensity));
        MusicRepository.ensureDebugFake();
        MusicController.get().bind(this);
        // ⓪ 首页标题斜度：歌词同款 skewX(-0.12f)（他 16:42 令，幅度只要一点；
        // TextView 无 setTextSkewX，照树内 MusicPlayerActivity:1788 用 getPaint().setTextSkewX）
        binding.homeTitle.getPaint().setTextSkewX(-0.12f);
        // ① 搜索胶囊：整条点按进现行搜索页；⋮ 单独点开小菜单（stop 冒泡靠独立点击区）
        binding.homeSearchBar.setOnClickListener(v -> MusicListActivity.startSearch(this, ""));
        binding.homeBtnMore.setOnClickListener(this::showMoreMenu);
        // ② 五快捷真跳
        binding.homeQuickFavorite.setOnClickListener(v -> MusicListActivity.startFavorite(this));
        binding.homeQuickSheets.setOnClickListener(v -> MusicListActivity.startSheets(this));
        binding.homeQuickDevice.setOnClickListener(v -> MusicListActivity.startDevice(this));
        binding.homeQuickRecent.setOnClickListener(v -> showRecent());
        binding.homeQuickDownload.setOnClickListener(v -> MusicListActivity.startDownload(this));
        // ③ 三彩卡
        // 猜你喜欢：树内现行推荐来源只有榜单聚合页（MusicRepository.charts 聚合各源榜单），用 charts 模式承接
        binding.homeCardGuess.setOnClickListener(v -> MusicListActivity.startGuess(this));
        binding.homeCardCharts.setOnClickListener(v -> MusicListActivity.startCharts(this));
        binding.homeCardShuffle.setOnClickListener(v -> MusicRandomActivity.start(this));
        // GZ 三彩卡按压缩放 0.98（MotionKt；返回 false 不吞点击，只做缩放反馈）
        pressScale(binding.homeCardGuess);
        pressScale(binding.homeCardCharts);
        pressScale(binding.homeCardShuffle);
        // 心情电台卡（GZ MoodRadioPanel 移植，见布局注释与本文件 mood 段）
        initMoodCard();
        // ④ 榜单卡：完整榜单进现有榜单页；主封面播放从头播该榜；点行从该曲开播整榜
        binding.homeChartMore.setOnClickListener(v -> MusicListActivity.startCharts(this));
        binding.homeChartMoreIcon.setOnClickListener(v -> MusicListActivity.startCharts(this));
        binding.homeChartPlay.setOnClickListener(v -> playHomeChartFrom(0));
        binding.homeChartRow1.setOnClickListener(v -> playHomeChartFrom(0));
        binding.homeChartRow2.setOnClickListener(v -> playHomeChartFrom(1));
        binding.homeChartRow3.setOnClickListener(v -> playHomeChartFrom(2));
        attachCardPress(binding.homeChartRow1, 0.98f);
        attachCardPress(binding.homeChartRow2, 0.98f);
        attachCardPress(binding.homeChartRow3, 0.98f);
        // 卡片按压反馈：三彩卡按瓜子 Motion 0.98 微缩；搜索条／五快捷／榜单卡保持现行 0.96
        attachCardPress(binding.homeSearchBar);
        attachCardPress(binding.homeQuickFavorite);
        attachCardPress(binding.homeQuickSheets);
        attachCardPress(binding.homeQuickDevice);
        attachCardPress(binding.homeQuickRecent);
        attachCardPress(binding.homeQuickDownload);
        attachCardPress(binding.homeCardGuess, 0.98f);
        attachCardPress(binding.homeCardCharts, 0.98f);
        attachCardPress(binding.homeCardShuffle, 0.98f);
        // 三彩卡光晕（瓜子 HomeEntryHalo，浅色分支 only：星辰首页固定浅色）：色按卡取，
        // 常驻静态层（脉动门控在 refreshShuffleSubtitle）；84dp 半出环（C10）保留不动，光晕是另加的一层。
        binding.homeHaloGuess.setHaloColor(0xFFFFE8F3);
        binding.homeHaloCharts.setHaloColor(0xFF9DF3EE);
        binding.homeHaloShuffle.setHaloColor(0xFFEAF989);
        attachCardPress(binding.homeChartCard);
        // 榜单封面圆角 12dp：ImgUtil.loadRounded 固定 8dp 且该文件不在本摊可改范围，
        // 这里用视图轮廓裁切补到 12dp（12dp 形含于 8dp 形内，交集即 12dp 圆角）
        applyChartCoverCorners();
        refreshCounts();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 首页浅底：状态栏图标改深色（06:17 令「背景把状态栏盖住了」）；离开本页在 onPause 恢复浅色图标
        ReadingSkin.setSystemBarIconAppearance(this, false);
        if (!homeChartLoaded) loadHomeChart();
        refreshCounts();
        refreshShuffleSubtitle();
        refreshMoodState();
        // 听书迷你条关闭路径 3：进入音乐页，听书真关闭（音乐会话自身不受影响）
        try {
            com.fongmi.android.tv.ui.minibar.ListenMiniBar.get(this).musicPageEnter();
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

    // ================= 榜单卡（数据只用酷我＋QQ 真榜） =================

    /**
     * 榜签映射（参照图四类 → 星辰真实榜，逐个核过）：
     * 飙升榜＝QQ topId 62「飙升榜」；新歌榜＝QQ topId 27「新歌榜」；热歌榜＝QQ topId 26「热歌榜」；
     * 原创榜＝QQ 全表无此名，落酷我榜表按「原创」匹配（元力KW 插件运行时榜表）。
     * 解析序固定先 QQ 后酷我；某签两边都匹配不到就不出该签；全签落空或榜内无曲 → 整卡隐藏。
     */
    private void loadHomeChart() {
        Task.execute(() -> {
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
            int def = -1;
            for (int i = 0; i < tabs.size(); i++) if (tabs.get(i).name.contains("新歌")) def = i;
            if (def < 0 && !tabs.isEmpty()) def = 0;
            final int sel = def;
            runOnUiThread(() -> {
                homeChartTabs.clear();
                homeChartTabs.addAll(tabs);
                if (tabs.isEmpty()) {
                    binding.homeChartCard.setVisibility(View.GONE);
                    return;
                }
                buildHomeChartTabs();
                selectHomeChart(sel);
            });
        });
    }

    private static int chartSiteRank(MusicSite site) {
        String tag = ((site.platform == null ? "" : site.platform) + (site.name == null ? "" : site.name)).toLowerCase();
        if (tag.contains("qq") || tag.contains("tx")) return 0;
        if (tag.contains("kw") || tag.contains("酷我")) return 1;
        return 2;
    }

    private void buildHomeChartTabs() {
        LinearLayout box = binding.homeChartTabs;
        box.removeAllViews();
        for (int i = 0; i < homeChartTabs.size(); i++) {
            final int idx = i;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView label = new TextView(this);
            label.setText(homeChartTabs.get(i).name);
            label.setTextSize(14);
            label.setSingleLine(true);
            View bar = new View(this);
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(ResUtil.dp2px(18), ResUtil.dp2px(3));
            barLp.topMargin = ResUtil.dp2px(5);
            bar.setLayoutParams(barLp);
            cell.addView(label);
            cell.addView(bar);
            cell.setOnClickListener(v -> selectHomeChart(idx));
            box.addView(cell);
        }
    }

    private void restyleHomeChartTabs() {
        LinearLayout box = binding.homeChartTabs;
        for (int i = 0; i < box.getChildCount(); i++) {
            LinearLayout cell = (LinearLayout) box.getChildAt(i);
            TextView label = (TextView) cell.getChildAt(0);
            View bar = cell.getChildAt(1);
            boolean on = i == homeChartSel;
            label.setTextColor(on ? 0xFF141414 : 0xFF8A8A8A);
            // 瓜子榜签：选中 800、未选中 500；API 28+ 按字重精确取，低版本退粗体／常规近似
            // （取法同 KaraokeLyricsView.mainTypeface 的 API 28 分支口径）
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                label.setTypeface(android.graphics.Typeface.create(
                        android.graphics.Typeface.DEFAULT, on ? 800 : 500, false));
            } else {
                label.setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            }
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(ResUtil.dp2px(2));
            bg.setColor(on ? 0xFFFA243C : Color.TRANSPARENT);
            bar.setBackground(bg);
        }
    }

    private void selectHomeChart(int idx) {
        if (idx < 0 || idx >= homeChartTabs.size()) return;
        homeChartSel = idx;
        restyleHomeChartTabs();
        MusicChart chart = homeChartTabs.get(idx);
        Task.execute(() -> {
            List<MusicTrack> tracks = new ArrayList<>();
            try {
                List<MusicTrack> got = MusicRepository.chartTracks(chart);
                if (got != null) tracks.addAll(got);
            } catch (Throwable ignored) {
            }
            final List<MusicTrack> result = tracks;
            runOnUiThread(() -> {
                if (idx != homeChartSel) return;
                homeChartTracks = result;
                if (result.isEmpty()) {
                    binding.homeChartCard.setVisibility(View.GONE);
                    return;
                }
                homeChartLoaded = true;
                fillHomeChart(chart, result);
                binding.homeChartCard.setVisibility(View.VISIBLE);
                applyShuffleTone();
            });
        });
    }

    private void fillHomeChart(MusicChart chart, List<MusicTrack> tracks) {
        binding.homeChartTitle.setText(chart.name);
        ImageView[] covers = {binding.homeChartCover1, binding.homeChartCover2, binding.homeChartCover3};
        for (int i = 0; i < covers.length; i++) {
            if (i < tracks.size()) ImgUtil.loadRounded(tracks.get(i).title, tracks.get(i).cover, covers[i]);
            else covers[i].setImageDrawable(null);
        }
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
    }

    private void playHomeChartFrom(int index) {
        if (homeChartTracks.isEmpty() || index >= homeChartTracks.size()) return;
        // 榜单起播不是随心听队列：清来源类型，副题回落「随机漫游」
        clearShuffleSource();
        // B7：入队时写队列级来源＝榜单名、route＝home（榜单卡在首页，落点＝首页）
        MusicChart chart = (homeChartSel >= 0 && homeChartSel < homeChartTabs.size())
                ? homeChartTabs.get(homeChartSel) : null;
        MusicController.get().play(new ArrayList<>(homeChartTracks), index,
                chart == null ? null : chart.name, "home", null);
        MusicPlayerActivity.start(this);
    }

    // ================= ⋮ 菜单（顶上滑下的白色圆角卡，非全屏） =================
    // 行形态照瓜子菜单行（ProfileScreenKt m282MenuRow）：行内边距 16×14dp、图标框 32dp／圆角 9dp／图标色 12% 底、
    // 图标到标题 14dp、粗标题、右 12sp 灰字、按压 0.98；图标色插件管理 #AF52DE、软件设置 #FF2D55（瓜子代码值）。
    // 插件行右字不抄瓜子「LX 音源插件」（星辰还有 FreeJS）：显示实际插件数。

    private void showMoreMenu(View anchor) {
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
        // 插件行：紫色拼图＋实际插件数；设置行：红色齿轮＋「播放／下载／外观」
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
        // py 脚本弹窗同款：decor 内容区点按即收（SourceScriptsDialog 在 onCreate 末尾给 android.R.id.content 挂 dismiss）。
        // ⋮菜单原有根布局点击收起保留（220ms 滑收）；decor 点击只补“根点击链路未触发”的漏网点按，不改动画形态。
        // 返回键仍走上面 OnKeyListener 的滑收（监听器先消费 BACK，不触发默认直接 dismiss）。
        try {
            View decorContent = dialog.findViewById(android.R.id.content);
            if (decorContent != null) decorContent.setOnClickListener(v -> dismissThen.accept(null));
        } catch (Throwable ignored) {
        }
        // 入场：先量高再从顶上滑下（INVISIBLE 起手，不闪一下）
        card.post(() -> {
            if (closing[0]) return;
            int h = card.getHeight() + cardLp.topMargin;
            card.setTranslationY(-h);
            card.setVisibility(View.VISIBLE);
            card.animate().translationY(0).setDuration(280)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        });
    }

    /** ⋮ 菜单行：浅色圆角方块图标＋粗标题＋右灰小字（瓜子菜单行形态，见 showMoreMenu 处注释）。 */
    private LinearLayout menuRow(int iconRes, int iconColor, String title, String sub, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        android.graphics.drawable.GradientDrawable box = new android.graphics.drawable.GradientDrawable();
        box.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
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

    // ================= 五快捷数量（真数据，异步回填） =================

    private void refreshCounts() {
        if (binding == null) return;
        MusicLibrary.favoriteCount(n -> {
            if (binding != null) binding.homeCountFavorite.setText(String.valueOf(n));
        });
        MusicLibrary.playlists(list -> {
            if (binding != null) binding.homeCountSheets.setText(String.valueOf(list == null ? 0 : list.size()));
        });
        MusicDownloadStore.count(n -> {
            if (binding != null) binding.homeCountDownload.setText(String.valueOf(n));
        });
        // 历史数：loadRecent 同步读（上限 50 条的小 JSON），直接取条数
        try {
            binding.homeCountRecent.setText(String.valueOf(MusicStore.loadRecent().size()));
        } catch (Throwable ignored) {
            binding.homeCountRecent.setText("0");
        }
        // 本地数：DeviceMusicStore.scan 是阻塞扫描（MediaStore＋目录），走后台线程回填
        Task.execute(() -> {
            int n = 0;
            try {
                n = DeviceMusicStore.scan(this).size();
            } catch (Throwable ignored) {
            }
            int count = n;
            runOnUiThread(() -> {
                if (binding != null) binding.homeCountDevice.setText(String.valueOf(count));
            });
        });
    }

    // ================= 播放历史（树内无 recent 列表页，按现行「列表点歌」模式承接） =================

    /**
     * MusicListActivity 没有 recent 模式、树内也没有最近播放的展示页，
     * 这里按 MusicListActivity 的现行模式承接：列出 MusicStore.loadRecent() 的曲目，
     * 点一行＝以整份历史为队列从该行开播并进播放页（与 MusicListActivity.playTrack 同写法）。
     */
    /** 卡片按压反馈（只加触感，不吞点击）：默认 0.96 微缩、回弹同长 */
    private void attachCardPress(android.view.View v) {
        attachCardPress(v, 0.96f);
    }

    /** 卡片按压反馈：按压缩放由调用方给（瓜子三彩卡 0.98，其余现行 0.96） */
    private void attachCardPress(android.view.View v, float scale) {
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

    /** 榜单三封面按 12dp 圆角裁切（见 initView 处注释：不动 ImgUtil 的前提下实现瓜子圆角） */
    private void applyChartCoverCorners() {
        android.view.ViewOutlineProvider provider = new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(android.view.View view, android.graphics.Outline outline) {
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

    /** 播放历史：走历史页（英雄卡＋排序＋续播卡＋多选＋清空），旧的首页对话框不再用 */
    private void showRecent() {
        MusicListActivity.startHistory(this);
    }

    private void clearShuffleSource() {
        homeQueueSourceType = null;
        shuffleQueueKeys = null;
    }

    /**
     * 随心听副题（瓜子 HomeScreenKt:2848：z5 ? "正在播放" : "随机漫游"，z5 见字段注释）：
     * 来源类型为 shuffle_listen、服务正在播、且服务现队列与随心听快照同序同曲，三者齐了才显「正在播放」，
     * 任一不满足即显「随机漫游」。暂停、切到别队列、Activity 重建丢字段时均回落，不留假态。
     * 残留口径：别处恰好播了与快照同序同曲的队列时无法区分，仍会显「正在播放」——队列来源字段在
     * MusicQueue 侧缺失（本摊文件范围外），本页字段＋快照是文件范围内的最严判定。
     */
    private void refreshShuffleSubtitle() {
        if (binding == null) return;
        boolean playingShuffle = "shuffle_listen".equals(homeQueueSourceType)
                && shuffleQueueKeys != null
                && MusicController.get().isPlaying()
                && trackKeys(MusicController.get().queue()).equals(shuffleQueueKeys);
        binding.homeShuffleSub.setText(playingShuffle
                ? R.string.home_shuffle_playing : R.string.home_shuffle_sub);
        // 光晕脉动与副题同用 playingShuffle（oa3 z5），不分叉：只有随心听卡动，另两卡恒为静态层；
        // 系统动画开关在 HomeEntryHaloView 内再判，关时落回静态 0.5。
        binding.homeHaloShuffle.setPulsing(playingShuffle);
    }

    // ================= 心情电台卡（GZ MoodRadioPanel 移植，链路按他令走星辰落雪插件搜索） =================
    // GZ 原生链走网易云分类歌单服务端（o5→me3.j→me3.p→bd5.i，失败文案含「网易云分类歌单请求失败」，
    // jp6 source 写死 netease；me3 虽持有 DataPluginManager 但心情链无其调用点，且该管理器只有
    // getState/markAccessDenied/refresh，无搜歌入口）——星辰移植链是用户令定的自家落雪插件搜索方案，
    // 不是 GZ 原文方法；不走 GZ 服务器红线不变。GZ 链路实证与字段见对照表。
    // 12 心情数据照 GZ HomeScreenKt:314（名＋文案＋tone 色；渐变三段原始值见 moodGradient()，
    // 卡渐变向白混合 0.08/0.20/0.32，播放钮渐变终色向白混合 0.22）：
    // 怀旧 #D88445、清新 #20B996、浪漫 #DB5C98、伤感 #7388DB、治愈 #EAAF4B、放松 #9476D8、
    // 夜晚 #747AD1、清晨 #F5AC4D、散步 #5AAF7B、运动 #ED795F、民谣 #D99353、电子 #8874E7。
    // 点 pill：选中并建台（GZ 行为）；点播放钮：当前队列即本心情只 toggle，否则建台。
    // “是本心情”判定＝ prefs 快照与现队列同序同曲（同随心听副题口径）；状态文案映射：
    // 建台中→「正在连接」，本心情且正在播→「暂停收听」，本心情且已暂停→「继续收听」，否则→「开始收听」。
    // 状态刷新只在 onResume 与本地动作后（与随心听副题同口径；别页切播后回首页前状态可能滞后）。
    private String moodSelected;
    private boolean moodBuilding;
    private final List<View> moodPillViews = new ArrayList<>();

    /** 心情表：{名，文案，tone}，顺序即 GZ HomeScreenKt:314 代码顺序 */
    private static String[][] moodTable() {
        return new String[][]{
                {"怀旧", "让旧时光重新播放", "#D88445"}, {"清新", "给耳朵一阵清风", "#20B996"},
                {"浪漫", "把心动写进旋律", "#DB5C98"}, {"伤感", "把没说完的话听完", "#7388DB"},
                {"治愈", "让旋律轻轻接住你", "#EAAF4B"}, {"放松", "把紧绷慢慢放下", "#9476D8"},
                {"夜晚", "留一盏灯给深夜", "#747AD1"}, {"清晨", "用好心情叫醒今天", "#F5AC4D"},
                {"散步", "跟着晚风慢慢走", "#5AAF7B"}, {"运动", "让节拍推你向前", "#ED795F"},
                {"民谣", "听见故事里的远方", "#D99353"}, {"电子", "让脉冲点亮此刻", "#8874E7"},
        };
    }

    /** 心情卡渐变三段原始值（左→右，HomeScreenKt:314 前后渐变组；电子无专属组走默认） */
    private static String[] moodGradient(String mood) {
        switch (mood) {
            case "伤感": return new String[]{"#E8ECFF", "#D6DEFF", "#C6D3FA"};
            case "夜晚": return new String[]{"#E5E4FF", "#CDCFFA", "#BABFF2"};
            case "怀旧": return new String[]{"#FFEDD5", "#FFDDBA", "#FFCAA5"};
            case "放松": return new String[]{"#EFE7FF", "#E2D5FF", "#CCDFFF"};
            case "散步": return new String[]{"#E6F7DE", "#D1F0C9", "#BFEADB"};
            case "治愈": return new String[]{"#FFF3D6", "#FFE9B9", "#FFD9B5"};
            case "民谣": return new String[]{"#FFF0DA", "#FFE2C0", "#FAD2AF"};
            case "浪漫": return new String[]{"#FFE7F2", "#FFCFE8", "#F4BBE6"};
            case "清新": return new String[]{"#DDF9F1", "#C5F3E3", "#DAF6AC"};
            case "清晨": return new String[]{"#FFF5D9", "#FFE8AB", "#FFD3B4"};
            case "运动": return new String[]{"#FFE7DA", "#FFCFBB", "#FFBFAE"};
            default: return new String[]{"#EAE5FF", "#D7CEFF", "#C3D8FF"};
        }
    }

    /** tone 色：GZ 12 心情 tone 值（moodTable 第三列，恒为 #RRGGBB） */
    private static int moodTone(String mood) {
        for (String[] m : moodTable()) if (m[0].equals(mood)) return Color.parseColor(m[2]);
        return Color.parseColor("#D88445");
    }

    /** 文案：GZ 12 心情文案（moodTable 第二列） */
    private static String moodCopy(String mood) {
        for (String[] m : moodTable()) if (m[0].equals(mood)) return m[1];
        return "";
    }

    /** 颜色向白混合：mix = c + (255-c)*ratio（GZ 渐变／播放钮混白公式） */
    private static int mixWhite(int color, float ratio) {
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        return Color.rgb(Math.round(r + (255 - r) * ratio),
                Math.round(g + (255 - g) * ratio), Math.round(b + (255 - b) * ratio));
    }

    /** 两色线性混合（ratio=0 取 a，=1 取 b）。 */
    private static int mixColors(int a, int b, float ratio) {
        return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * ratio),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * ratio),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * ratio));
    }

    /** 随心听渐变跟封面取色混（GZ：基色混向取色向白 0.75 后的色，混合比＝取色 alpha×0.1f；
     * 取色 alpha 未抠到，按 1.0 计即混合比 0.1；取色源是星辰侧口径：首页榜单首首封面（GZ 是首张每日歌曲封面，星辰无每日堆叠）；
     * 无榜单／无封面／取色失败时保持基色原样；混后无固定值。只算一次，榜单首首不变则不重算。 */
    private boolean shuffleToneApplied;

    private void applyShuffleTone() {
        if (binding == null || shuffleToneApplied || homeChartTracks.isEmpty()) return;
        String cover = homeChartTracks.get(0).cover;
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
                        int lightTone = mixWhite(tone, 0.75f);
                        int[] base = {0xFFB8EFF9, 0xFFBCEEDD, 0xFFE8FAA8};
                        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                                new int[]{mixColors(base[0], lightTone, 0.1f),
                                        mixColors(base[1], lightTone, 0.1f),
                                        mixColors(base[2], lightTone, 0.1f)});
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

    private static int alphaColor(int color, float alpha) {
        return (Math.round(alpha * 255) << 24) | (color & 0x00FFFFFF);
    }

    /** 默认选中心情按 GZ：当前队列标签是“心情电台 · XX”时取 XX（须在 12 心情表内），否则取怀旧。 */
    private String moodFromQueueSource() {
        String src = MusicController.get().queueSource();
        if (src != null && src.startsWith("心情电台 · ")) {
            String name = src.substring("心情电台 · ".length());
            for (String[] m : moodTable()) if (m[0].equals(name)) return name;
        }
        return "怀旧";
    }

    private void initMoodCard() {
        // 默认选中按 GZ：从当前队列标签反推（剥“心情电台 · ”前缀），反推不到取怀旧；不用 prefs 上次选择。
        moodSelected = moodFromQueueSource();
        for (String[] m : moodTable()) {
            final String name = m[0];
            LinearLayout pill = new LinearLayout(this);
            pill.setOrientation(LinearLayout.HORIZONTAL);
            pill.setGravity(android.view.Gravity.CENTER_VERTICAL);
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
            dotBg.setColor(moodTone(name));
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

    /** 当前队列是否为本心情建台队列：prefs 快照非空且与现队列同序同曲 */
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
        int tone = moodTone(moodSelected);
        // 卡渐变：横向三段（左→右），原始值向白混合 0.08/0.20/0.32，圆角 20dp
        String[] g = moodGradient(moodSelected);
        GradientDrawable cardBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{mixWhite(Color.parseColor(g[0]), 0.08f),
                        mixWhite(Color.parseColor(g[1]), 0.20f),
                        mixWhite(Color.parseColor(g[2]), 0.32f)});
        cardBg.setCornerRadius(dp(20));
        binding.homeMoodCard.setBackground(cardBg);
        binding.tvMoodName.setText(moodSelected);
        binding.tvMoodCopy.setText(moodCopy(moodSelected));
        // pills：选中 tone 0.18 底＋字重 700，未选中白 50% 底＋字重 500（sans-serif-medium 近似）
        for (View pill : moodPillViews) {
            boolean sel = moodSelected.equals(pill.getTag());
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(18));
            bg.setColor(sel ? alphaColor(tone, 0.18f) : 0x80FFFFFF);
            pill.setBackground(bg);
            TextView tv = (TextView) ((ViewGroup) pill).getChildAt(1);
            tv.setTypeface(sel ? android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                    : android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        }
        // 播放钮：横向渐变 tone→tone 向白混合 0.22，圆；播放中暂停图标 18dp（内边距 11dp），未播放播放图标 24dp（内边距 8dp）
        boolean playing = isThisMoodQueue() && MusicController.get().isPlaying();
        GradientDrawable btnBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{tone, mixWhite(tone, 0.22f)});
        btnBg.setShape(GradientDrawable.OVAL);
        binding.btnMoodPlay.setBackground(btnBg);
        binding.btnMoodPlay.setImageResource(playing ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
        binding.btnMoodPlay.setPadding(dp(playing ? 11 : 8), dp(playing ? 11 : 8), dp(playing ? 11 : 8), dp(playing ? 11 : 8));
        binding.btnMoodPlay.setColorFilter(Color.WHITE);
        // 波形：tone 0.7，播放振幅 1.0＋动画，暂停 0.35 静态
        binding.moodWave.setTone(tone);
        binding.moodWave.setPlaying(playing);
        // 钮旁状态文案
        binding.tvMoodState.setText(moodBuilding ? "正在连接"
                : isThisMoodQueue() ? (MusicController.get().isPlaying() ? "暂停收听" : "继续收听")
                : "开始收听");
    }

    /** 按心情名走星辰落雪插件搜索拼歌单播（不走 GZ 服务器）；搜不到 toast 同款文案 */
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

    /** 曲目键：优先 id，无 id 时用标题＋歌手拼键（同曲不同源时 id 不同即判为不同队列）。 */
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

    /** GZ 卡片按压缩放（入口卡 0.98、心情播放钮 0.9）：按下缩、抬起／取消回弹；返回 false 不吞点击 */
    private void pressScale(android.view.View v, float scale) {
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

    private void pressScale(android.view.View v) {
        pressScale(v, 0.98f);
    }
}
