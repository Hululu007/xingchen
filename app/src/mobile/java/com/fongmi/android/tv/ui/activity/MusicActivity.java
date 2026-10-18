package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.ActivityMusicBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 音乐页：底栏新格。听歌源（点了切成当前源、回点播页听）。 */
public class MusicActivity extends BaseActivity {

    private ActivityMusicBinding binding;
    private final List<Site> sites = new ArrayList<>();

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
        binding.recycler.setLayoutManager(new LinearLayoutManager(this));
        binding.recycler.setAdapter(new SiteAdapter());
        refresh();
    }

    private int retryCount;
    private boolean configTried;

    /** 配置只有首页会主动加载；这边源为空时自己触发一次，别干等。 */
    private void ensureConfig() {
        if (configTried) return;
        configTried = true;
        try {
            VodConfig.get().config(Config.vod()).load(new Callback() {
                @Override
                public void success() {
                    runOnUiThread(() -> {
                        retryCount = 0;
                        refresh();
                    });
                }

                @Override
                public void error(String msg) {
                    configTried = false;
                }
            });
        } catch (Throwable t) {
            configTried = false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        retryCount = 0;
        if (binding != null) refresh();
        // 听书迷你条关闭路径 3：进入音乐页，听书真关闭（停播＋落盘）
        try {
            com.fongmi.android.tv.ui.minibar.ListenMiniBar.get(this)
                    .musicPageEnter();
        } catch (Throwable ignored) {
        }
    }

    private void refresh() {
        sites.clear();
        List<Site> all = new ArrayList<>();
        try {
            all.addAll(VodConfig.get().getSites());
        } catch (Throwable ignored) {
        }
        List<Site> music = new ArrayList<>();
        for (Site s : all) if (looksMusic(s)) music.add(s);
        if (!music.isEmpty()) {
            sites.addAll(music);
            binding.sourceHint.setText("点一个源，切到点播页听它的歌");
        } else {
            sites.addAll(all);
            binding.sourceHint.setText("没认出哪个是音乐源，先把全部源列出来，点一个去听");
        }
        if (sites.isEmpty()) ensureConfig();
        binding.empty.setText(sites.isEmpty() && (retryCount < 6 || configTried) ? "正在加载源…" : "还没有源");
        binding.empty.setVisibility(sites.isEmpty() ? View.VISIBLE : View.GONE);
        if (binding.recycler.getAdapter() != null) binding.recycler.getAdapter().notifyDataSetChanged();
        // 刚装完/刚打开时配置可能还没加载完，源列表先空着：短间隔重试几次，别让他对着空页
        if (sites.isEmpty() && retryCount < 6 && !isFinishing()) {
            retryCount++;
            binding.getRoot().postDelayed(() -> {
                if (binding != null && !isFinishing()) refresh();
            }, 1200);
        }
    }

    static boolean looksMusic(Site s) {
        String n = ((s.getName() == null ? "" : s.getName()) + " " + (s.getKey() == null ? "" : s.getKey())).toLowerCase(Locale.ROOT);
        return n.contains("音乐") || n.contains("听歌") || n.contains("歌曲") || n.contains("电台")
                || n.contains("ktv") || n.contains("music") || n.contains(" mv") || n.contains("[mv]")
                || n.contains("酷我") || n.contains("酷狗") || n.contains("qq音乐") || n.contains("网易云");
    }

    private void openSite(Site site) {
        try {
            VodConfig.get().setHome(site);
        } catch (Throwable ignored) {
        }
        Toast.makeText(this, "已切到「" + site.getName() + "」，去点播页听歌", Toast.LENGTH_SHORT).show();
        startActivity(new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    private class SiteAdapter extends RecyclerView.Adapter<SiteAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(14), dp(16), dp(14));
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(10);
            row.setLayoutParams(lp);
            row.setBackgroundResource(com.fongmi.android.tv.R.drawable.shape_music_card);
            TextView name = new TextView(parent.getContext());
            name.setTextColor(Color.WHITE);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView tag = new TextView(parent.getContext());
            tag.setTextColor(0xFFFFD34D);
            tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            row.addView(name);
            row.addView(tag);
            return new Holder(row, name, tag);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Site site = sites.get(position);
            h.name.setText(site.getName());
            boolean home = false;
            try {
                Site cur = VodConfig.get().getHome();
                home = cur != null && cur.getKey() != null && cur.getKey().equals(site.getKey());
            } catch (Throwable ignored) {
            }
            h.tag.setText(home ? "使用中" : "›");
            h.itemView.setOnClickListener(v -> openSite(site));
        }

        @Override
        public int getItemCount() {
            return sites.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView tag;

            Holder(View itemView, TextView name, TextView tag) {
                super(itemView);
                this.name = name;
                this.tag = tag;
            }
        }
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
