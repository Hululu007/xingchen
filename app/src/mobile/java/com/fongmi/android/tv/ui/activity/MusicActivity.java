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
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.music.MusicChart;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;
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
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 音乐首页（v7 定稿，2026-10-21 用户逐版改定）：
 * ① 搜索胶囊条（占位「搜索歌曲、歌手、专辑」）＋右侧竖三点 ⋮ 小菜单（插件管理／设置，非全屏）；
 * ② 白色圆角卡内五个快捷横排：音乐收藏／我的歌单／本地音乐（正中）／播放历史／下载音乐，
 *    彩色图标＋名字＋下方真实数量（异步回填，取不到显示 0）；
 * ③ 三张渐变入口卡垫底：猜你喜欢·每日精选（粉）／排行榜·此刻热门（蓝绿）／随心听·随机漫游（绿黄）。
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
        MusicRepository.ensureDebugFake();
        MusicController.get().bind(this);
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
        binding.homeCardGuess.setOnClickListener(v -> MusicListActivity.startCharts(this));
        binding.homeCardCharts.setOnClickListener(v -> MusicListActivity.startCharts(this));
        binding.homeCardShuffle.setOnClickListener(v -> shufflePlay());
        // ④ 榜单卡：完整榜单进现有榜单页；主封面播放从头播该榜；点行从该曲开播整榜
        binding.homeChartMore.setOnClickListener(v -> MusicListActivity.startCharts(this));
        binding.homeChartPlay.setOnClickListener(v -> playHomeChartFrom(0));
        binding.homeChartRow1.setOnClickListener(v -> playHomeChartFrom(0));
        binding.homeChartRow2.setOnClickListener(v -> playHomeChartFrom(1));
        binding.homeChartRow3.setOnClickListener(v -> playHomeChartFrom(2));
        // 卡片按压反馈与 GZ Motion 同套（0.96 微缩）：搜索条/五快捷/三彩卡/榜单卡统一
        attachCardPress(binding.homeSearchBar);
        attachCardPress(binding.homeQuickFavorite);
        attachCardPress(binding.homeQuickSheets);
        attachCardPress(binding.homeQuickDevice);
        attachCardPress(binding.homeQuickRecent);
        attachCardPress(binding.homeQuickDownload);
        attachCardPress(binding.homeCardGuess);
        attachCardPress(binding.homeCardCharts);
        attachCardPress(binding.homeCardShuffle);
        attachCardPress(binding.homeChartCard);
        refreshCounts();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 首页浅底：状态栏图标改深色（06:17 令「背景把状态栏盖住了」）；离开本页在 onPause 恢复浅色图标
        ReadingSkin.setSystemBarIconAppearance(this, false);
        if (!homeChartLoaded) loadHomeChart();
        refreshCounts();
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
            label.setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
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
        MusicController.get().play(new ArrayList<>(homeChartTracks), index);
        MusicPlayerActivity.start(this);
    }

    // ================= ⋮ 小菜单（小下拉，非全屏） =================

    private void showMoreMenu(View anchor) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.home_bg_menu);
        root.setElevation(dp(6));
        int pad = dp(6);
        root.setPadding(pad, pad, pad, pad);
        PopupWindow pw = new PopupWindow(root,
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true);
        pw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        pw.setElevation(dp(6));
        addMenuRow(root, pw, getString(R.string.music_plugin_manage),
                () -> MusicListActivity.startSites(this));
        addMenuRow(root, pw, getString(R.string.music_setting),
                () -> MusicSettingsActivity.start(this));
        try {
            pw.showAsDropDown(anchor, -dp(110), dp(4), Gravity.END);
        } catch (Throwable t) {
            pw.showAsDropDown(anchor, 0, 0);
        }
    }

    private void addMenuRow(LinearLayout root, PopupWindow pw, String text, Runnable action) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFF1A1A1A);
        tv.setTextSize(15);
        tv.setMinWidth(dp(150));
        int hp = dp(16);
        int vp = dp(12);
        tv.setPadding(hp, vp, hp, vp);
        tv.setBackgroundResource(R.drawable.home_bg_menu_row);
        tv.setOnClickListener(v -> {
            try {
                pw.dismiss();
            } catch (Throwable ignored) {
            }
            action.run();
        });
        root.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
    /** 卡片按压反馈（GZ MotionKt 口径 0.96 微缩、回弹同长）：只加触感，不吞点击 */
    private void attachCardPress(android.view.View v) {
        if (v == null) return;
        v.setOnTouchListener((view, e) -> {
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(150)
                        .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start();
            } else if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP
                    || e.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(150)
                        .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start();
            }
            return false;
        });
    }

    private void showRecent() {
        List<MusicTrack> recent;
        try {
            recent = MusicStore.loadRecent();
        } catch (Throwable ignored) {
            recent = new ArrayList<>();
        }
        HomeDialogRecentBinding dlg = HomeDialogRecentBinding.inflate(getLayoutInflater());
        AlertDialog dialog = new AlertDialog.Builder(this).setView(dlg.getRoot()).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        if (recent.isEmpty()) {
            dlg.homeRecentEmpty.setVisibility(View.VISIBLE);
        } else {
            for (int i = 0; i < recent.size(); i++) {
                MusicTrack track = recent.get(i);
                int index = i;
                View row = LayoutInflater.from(this)
                        .inflate(R.layout.home_item_recent, dlg.homeRecentList, false);
                TextView title = row.findViewById(R.id.homeRecentTitle);
                TextView artist = row.findViewById(R.id.homeRecentArtist);
                title.setText(track.displayTitle());
                String a = track.displayArtist();
                artist.setText(a == null ? "" : a);
                artist.setVisibility(a == null || a.isEmpty() ? View.GONE : View.VISIBLE);
                List<MusicTrack> queue = new ArrayList<>(recent);
                row.setOnClickListener(v -> {
                    dialog.dismiss();
                    MusicController.get().play(queue, index);
                    MusicPlayerActivity.start(this);
                });
                dlg.homeRecentList.addView(row);
            }
        }
        dialog.show();
    }

    // ================= 随心听（真随机播放） =================

    /**
     * 随机队列来源（按现行能力依次取）：当前队列 → 最近播放 → 收藏 → 已下载；
     * 都为空才提示无歌。取到后照 MusicListActivity.shufflePlay 的现行写法：
     * 打乱副本 → setMode(MODE_SHUFFLE) → play(队列, 0) → 进播放页。
     */
    private void shufflePlay() {
        List<MusicTrack> queue = new ArrayList<>();
        try {
            queue = MusicController.get().queue();
        } catch (Throwable ignored) {
        }
        if (queue != null && !queue.isEmpty()) {
            startShuffle(new ArrayList<>(queue));
            return;
        }
        List<MusicTrack> recent = new ArrayList<>();
        try {
            recent = MusicStore.loadRecent();
        } catch (Throwable ignored) {
        }
        if (!recent.isEmpty()) {
            startShuffle(recent);
            return;
        }
        MusicLibrary.favorites(list -> {
            if (list != null && !list.isEmpty()) {
                startShuffle(new ArrayList<>(list));
                return;
            }
            MusicDownloadStore.all(downloads -> {
                if (downloads != null && !downloads.isEmpty()) {
                    startShuffle(new ArrayList<>(downloads));
                } else {
                    Notify.show(R.string.music_no_track);
                }
            });
        });
    }

    private void startShuffle(List<MusicTrack> tracks) {
        if (tracks == null || tracks.isEmpty()) {
            Notify.show(R.string.music_no_track);
            return;
        }
        List<MusicTrack> shuffled = new ArrayList<>(tracks);
        Collections.shuffle(shuffled);
        MusicController.get().setMode(MusicQueue.MODE_SHUFFLE);
        MusicController.get().play(shuffled, 0);
        MusicPlayerActivity.start(this);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
