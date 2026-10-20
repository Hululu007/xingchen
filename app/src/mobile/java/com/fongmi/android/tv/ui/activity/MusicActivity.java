package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
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
import com.fongmi.android.tv.utils.Notify;
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
        refreshCounts();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshCounts();
        // 听书迷你条关闭路径 3：进入音乐页，听书真关闭（音乐会话自身不受影响）
        try {
            com.fongmi.android.tv.ui.minibar.ListenMiniBar.get(this).musicPageEnter();
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        MusicController.get().unbind();
        super.onDestroy();
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
