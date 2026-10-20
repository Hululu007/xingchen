package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.databinding.ActivityMusicBinding;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicDownloadStore;
import com.fongmi.android.tv.music.MusicLibrary;
import com.fongmi.android.tv.music.MusicRepository;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.MusicMenuDialog;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;

import java.util.ArrayList;
import java.util.List;

/**
 * 音乐首页：只放四个导航行（歌单/收藏/榜单/已下载）。
 * 源列表不在首页：顶栏左上角 ☰ 图标钮点开面板（插件管理→源管理页，设置→音乐设置页）。
 * 顶部播放行只做续播＋「N 个源」状态显示。
 * 顶栏只留标题，无返回箭头；卡片只有名字和状态，无介绍文字。
 */
public class MusicActivity extends BaseActivity {

    private ActivityMusicBinding binding;
    private final List<Object> items = new ArrayList<>();
    private final List<NavItem> navItems = new ArrayList<>();
    private HomeAdapter adapter;

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
        binding.recycler.setLayoutManager(new LinearLayoutManager(this));
        binding.recycler.setAdapter(adapter = new HomeAdapter());
        binding.btnSearch.setOnClickListener(v -> MusicListActivity.startSearch(this, ""));
        // ☰ 点开面板：插件管理（进源管理，原行为）／设置（进音乐设置页），只有两项
        binding.btnSourceManage.setOnClickListener(v -> MusicMenuDialog.show(this, which -> {
            if (which == MusicMenuDialog.MENU_SITES) MusicListActivity.startSites(this);
            else if (which == MusicMenuDialog.MENU_SETTINGS) MusicSettingsActivity.start(this);
        }));
        binding.btnPlayAll.setOnClickListener(v -> playAll());
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshNavStatus();
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

    private void refresh() {
        buildNavItems();
        Task.execute(() -> {
            int n = MusicRepository.sites().size();
            runOnUiThread(() -> {
                items.clear();
                items.addAll(navItems);
                adapter.notifyDataSetChanged();
                binding.tvCount.setText(n + " " + getString(R.string.music_sites));
                binding.empty.setVisibility(View.GONE);
            });
        });
    }

    private void refreshNavStatus() {
        MusicLibrary.favoriteCount(n -> {
            navItems.get(1).status = n + " " + getString(R.string.music_tracks);
            if (adapter != null) adapter.notifyDataSetChanged();
        });
        MusicLibrary.playlists(list -> {
            navItems.get(0).status = list.size() + " 个歌单";
            if (adapter != null) adapter.notifyDataSetChanged();
        });
        MusicDownloadStore.count(n -> {
            navItems.get(3).status = n + " " + getString(R.string.music_tracks);
            if (adapter != null) adapter.notifyDataSetChanged();
        });
    }

    private void buildNavItems() {
        navItems.clear();
        navItems.add(new NavItem("sheets", getString(R.string.music_playlist), "", 0xFF22C55E, R.drawable.ic_music_queue));
        navItems.add(new NavItem("favorite", getString(R.string.music_favorite), "", 0xFFEF4444, R.drawable.ic_music_heart));
        navItems.add(new NavItem("charts", getString(R.string.music_charts), "", 0xFFA855F7, R.drawable.ic_music_eq));
        navItems.add(new NavItem("download", getString(R.string.music_downloaded), "", 0xFFF2B33D, R.drawable.ic_music_download));
    }

    /** 首页大播放键：上次队列从断点位置续播；无断点播最近播放第一首；再无则不动作 */
    private void playAll() {
        List<MusicTrack> queue = MusicStore.loadQueue();
        if (!queue.isEmpty()) {
            int idx = Math.max(0, Math.min(MusicStore.loadIndex(), queue.size() - 1));
            MusicController.get().playFrom(queue, idx, MusicStore.loadPosition());
            MusicPlayerActivity.start(this);
            return;
        }
        List<MusicTrack> recent = MusicStore.loadRecent();
        if (!recent.isEmpty()) {
            MusicController.get().play(recent, 0);
            MusicPlayerActivity.start(this);
            return;
        }
        Notify.show("还没有听过歌");
    }

    private void onNavClick(NavItem item) {
        switch (item.key) {
            case "sheets":
                MusicListActivity.startSheets(this);
                break;
            case "favorite":
                MusicListActivity.startFavorite(this);
                break;
            case "charts":
                MusicListActivity.startCharts(this);
                break;
            case "download":
                MusicListActivity.startDownload(this);
                break;
        }
    }

    private static class NavItem {
        final String key;
        final String name;
        String status;
        final int color;
        final int icon;

        NavItem(String key, String name, String status, int color, int icon) {
            this.key = key;
            this.name = name;
            this.status = status;
            this.color = color;
            this.icon = icon;
        }
    }

    private class HomeAdapter extends RecyclerView.Adapter<HomeAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_music_nav, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            NavItem item = (NavItem) items.get(position);
            h.name.setText(item.name);
            h.status.setText(item.status);
            h.icon.setImageResource(item.icon);
            h.icon.setBackground(circle(item.color));
            h.icon.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
            h.itemView.setOnClickListener(v -> onNavClick(item));
        }

        private GradientDrawable circle(int color) {
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(color);
            return d;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView name;
            final TextView status;

            Holder(View v) {
                super(v);
                icon = v.findViewById(R.id.icon);
                name = v.findViewById(R.id.name);
                status = v.findViewById(R.id.status);
            }
        }
    }
}
