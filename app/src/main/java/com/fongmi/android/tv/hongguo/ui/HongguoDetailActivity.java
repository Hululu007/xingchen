package com.fongmi.android.tv.hongguo.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.drama.DramaClient;
import com.fongmi.android.tv.hongguo.drama.DramaDetail;
import com.fongmi.android.tv.hongguo.drama.DramaItem;

import java.util.ArrayList;
import java.util.List;

/**
 * 剧详情页（2026-10-30 从零重建，简单口径）：一屏完事——海报＋剧名
 * ＋简介＋播放/收藏＋选集网格，没有多段陈列、没有卡片堆。作者名可点
 * 进作者页。合集（多季）时顶部多一排季签。
 */
public class HongguoDetailActivity extends AppCompatActivity {

    public static final String EXTRA_SERIES_ID = "hg_detail_series";
    public static final String EXTRA_TITLE = "hg_detail_title";
    public static final String EXTRA_ALBUM_ID = "hg_detail_album";

    public static void start(Context context, String seriesId, String title, String albumId) {
        Intent intent = new Intent(context, HongguoDetailActivity.class);
        intent.putExtra(EXTRA_SERIES_ID, seriesId);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_ALBUM_ID, albumId);
        context.startActivity(intent);
    }

    private String seriesId;
    private DramaDetail detail;
    private TextView playBtn;
    private TextView favBtn;
    private TextView descText;
    private boolean descExpanded;
    private EpGridAdapter epAdapter;
    private TextView epSub;
    private LinearLayout seasonRow;
    private int currentEpisode = 1;

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private int navBarHeight() {
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private GradientDrawable roundBg(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private TextView metaTitle;
    private TextView metaLine;
    private ImageView coverView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        seriesId = getIntent().getStringExtra(EXTRA_SERIES_ID);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF111114);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), statusBarHeight() + dp(6), dp(16), dp(20) + navBarHeight());

        // 顶栏：返回
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_drama_back);
        back.setColorFilter(Color.WHITE);
        back.setPadding(dp(4), dp(8), dp(12), dp(8));
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView topTitle = new TextView(this);
        topTitle.setText("详情");
        topTitle.setTextColor(Color.WHITE);
        topTitle.setTextSize(16.5f);
        topTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        top.addView(topTitle);
        col.addView(top);

        // 头部：海报＋剧名信息
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = dp(12);
        coverView = new ImageView(this);
        coverView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverView.setBackground(roundBg(0xFF232328, dp(8)));
        head.addView(coverView, new LinearLayout.LayoutParams(dp(96), dp(132)));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, -2, 1f);
        ilp.leftMargin = dp(14);
        metaTitle = new TextView(this);
        metaTitle.setTextColor(Color.WHITE);
        metaTitle.setTextSize(18);
        metaTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        metaTitle.setMaxLines(2);
        metaTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        String t = getIntent().getStringExtra(EXTRA_TITLE);
        metaTitle.setText(t == null ? "" : t);
        info.addView(metaTitle);
        metaLine = new TextView(this);
        metaLine.setTextColor(0xFF9E9EA7);
        metaLine.setTextSize(12.5f);
        metaLine.setLineSpacing(0, 1.35f);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
        mlp.topMargin = dp(7);
        info.addView(metaLine, mlp);
        head.addView(info, ilp);
        col.addView(head, hlp);

        // 季签（合集才显）
        seasonRow = new LinearLayout(this);
        seasonRow.setOrientation(LinearLayout.HORIZONTAL);
        seasonRow.setVisibility(View.GONE);
        LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(-2, -2);
        srlp.topMargin = dp(14);
        col.addView(seasonRow, srlp);

        // 动作行：播放＋收藏
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.topMargin = dp(18);
        playBtn = new TextView(this);
        playBtn.setText("播放");
        playBtn.setTextColor(Color.WHITE);
        playBtn.setTextSize(15.5f);
        playBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        playBtn.setGravity(Gravity.CENTER);
        playBtn.setBackground(roundBg(0xFFFF5A4E, dp(10)));
        playBtn.setPadding(0, dp(12), 0, dp(12));
        playBtn.setOnClickListener(v -> {
            if (detail == null) return;
            HongguoFeedActivity.startSeries(this, seriesId, detail.getTitle(), currentEpisode);
        });
        actions.addView(playBtn, new LinearLayout.LayoutParams(0, -2, 1f));
        favBtn = new TextView(this);
        favBtn.setGravity(Gravity.CENTER);
        favBtn.setTextSize(15);
        favBtn.setPadding(0, dp(12), 0, dp(12));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(dp(104), -2);
        flp.leftMargin = dp(10);
        favBtn.setOnClickListener(v -> {
            if (detail == null) return;
            boolean now = HgStore.toggleFaved(seriesId);
            HgStore.putMeta(seriesId, detail.getTitle(), detail.getCoverUrl(), detail.getEpisodeCount());
            styleFav(now);
        });
        actions.addView(favBtn, flp);
        col.addView(actions, alp);

        // 简介
        TextView descLabel = sectionLabel("简介");
        col.addView(descLabel);
        descText = new TextView(this);
        descText.setTextColor(0xFFD8D8DC);
        descText.setTextSize(13.5f);
        descText.setLineSpacing(0, 1.45f);
        descText.setMaxLines(3);
        descText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        descText.setOnClickListener(v -> {
            descExpanded = !descExpanded;
            descText.setMaxLines(descExpanded ? Integer.MAX_VALUE : 3);
        });
        col.addView(descText);

        // 选集
        TextView epLabel = sectionLabel("选集");
        col.addView(epLabel);
        epSub = new TextView(this);
        epSub.setTextColor(0xFF8E8E96);
        epSub.setTextSize(12);
        col.addView(epSub);
        RecyclerView grid = new RecyclerView(this);
        grid.setLayoutManager(new GridLayoutManager(this, 5));
        grid.setNestedScrollingEnabled(false);
        epAdapter = new EpGridAdapter();
        grid.setAdapter(epAdapter);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
        glp.topMargin = dp(8);
        col.addView(grid, glp);

        scroll.addView(col);
        setContentView(scroll);
        load();
        String albumId = getIntent().getStringExtra(EXTRA_ALBUM_ID);
        if (albumId != null && !albumId.isEmpty()) loadSeasons(albumId);
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(20);
        lp.bottomMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private void styleFav(boolean faved) {
        favBtn.setText(faved ? "★ 已收藏" : "☆ 收藏");
        favBtn.setTextColor(faved ? 0xFF3D2E00 : 0xFFEEEEEE);
        favBtn.setBackground(roundBg(faved ? 0xFFFFC531 : 0xFF232328, dp(10)));
    }

    private void load() {
        if (seriesId == null || seriesId.isEmpty()) {
            Toast.makeText(this, "剧集不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        DramaClient.fetchDetail(seriesId, new HgCallback<DramaDetail>() {
            @Override
            public void onSuccess(DramaDetail d) {
                runOnUiThread(() -> bind(d));
            }

            @Override
            public void onError(int code, String msg) {
                runOnUiThread(() -> Toast.makeText(HongguoDetailActivity.this,
                        "取数据失败，请重试", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void bind(DramaDetail d) {
        detail = d;
        if (d.getTitle() != null) metaTitle.setText(d.getTitle());
        if (d.getCoverUrl() != null && !d.getCoverUrl().isEmpty()) {
            Glide.with(this).load(d.getCoverUrl())
                    .transform(new RoundedCorners(dp(8))).into(coverView);
        }
        StringBuilder meta = new StringBuilder();
        int total = d.getEpisodes().size();
        if (total > 0) {
            meta.append(d.getSeriesStatus() != null && d.getSeriesStatus() == 1
                    ? "已完结 · 全" + total + "集" : "更新至第" + total + "集");
        }
        if (d.getAuthorName() != null && !d.getAuthorName().isEmpty()) {
            if (meta.length() > 0) meta.append("\n");
            meta.append("作者：").append(d.getAuthorName());
        }
        if (d.getTags() != null && !d.getTags().isEmpty()) {
            if (meta.length() > 0) meta.append("\n");
            meta.append(String.join(" · ", d.getTags()));
        }
        metaLine.setText(meta.toString());
        if (d.getAuthorUid() != null && !d.getAuthorUid().isEmpty()) {
            metaLine.setOnClickListener(v -> HongguoAuthorActivity.start(this,
                    d.getAuthorUid(), d.getAuthorName(), null));
        }
        descText.setText(d.getDesc() == null ? "暂无简介" : d.getDesc());
        HgStore.Progress p = HgStore.progressOf(seriesId);
        currentEpisode = p != null ? Math.max(1, p.episodeIndex) : 1;
        if (total > 0) currentEpisode = Math.min(currentEpisode, total);
        playBtn.setText(p != null && p.positionMs > 0
                ? "继续播放 第" + currentEpisode + "集" : "播放 第1集");
        styleFav(HgStore.isFaved(seriesId));
        epSub.setText(total > 0 ? "共 " + total + " 集" : "");
        epAdapter.notifyDataSetChanged();
    }

    private void loadSeasons(String albumId) {
        DramaClient.fetchAlbum(albumId, new HgCallback<List<DramaItem>>() {
            @Override
            public void onSuccess(List<DramaItem> seasons) {
                runOnUiThread(() -> {
                    if (seasons == null || seasons.size() <= 1) return;
                    seasonRow.removeAllViews();
                    seasonRow.setVisibility(View.VISIBLE);
                    for (DramaItem s : seasons) {
                        TextView chip = new TextView(HongguoDetailActivity.this);
                        chip.setText(s.getTitle() == null ? "" : s.getTitle());
                        chip.setTextSize(12.5f);
                        chip.setMaxLines(1);
                        chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
                        boolean on = seriesId.equals(s.getSeriesId());
                        chip.setTextColor(on ? Color.WHITE : 0xFFB8B8BE);
                        chip.setBackground(roundBg(on ? 0xFFFF5A4E : 0xFF232328, dp(13)));
                        chip.setPadding(dp(11), dp(5), dp(11), dp(5));
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                        lp.rightMargin = dp(8);
                        chip.setOnClickListener(v -> {
                            if (s.getSeriesId() == null || s.getSeriesId().equals(seriesId)) return;
                            seriesId = s.getSeriesId();
                            detail = null;
                            epAdapter.notifyDataSetChanged();
                            load();
                            styleSeasonChips(seasons);
                        });
                        seasonRow.addView(chip, lp);
                    }
                });
            }

            @Override
            public void onError(int code, String msg) {
            }
        });
    }

    private void styleSeasonChips(List<DramaItem> seasons) {
        for (int i = 0; i < seasonRow.getChildCount() && i < seasons.size(); i++) {
            TextView chip = (TextView) seasonRow.getChildAt(i);
            boolean on = seriesId.equals(seasons.get(i).getSeriesId());
            chip.setTextColor(on ? Color.WHITE : 0xFFB8B8BE);
            chip.setBackground(roundBg(on ? 0xFFFF5A4E : 0xFF232328, dp(13)));
        }
    }

    private class EpGridAdapter extends RecyclerView.Adapter<EpGridAdapter.H> {

        class H extends RecyclerView.ViewHolder {
            final TextView text;

            H(TextView t) {
                super(t);
                text = t;
            }
        }

        @NonNull
        @Override
        public H onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView t = new TextView(HongguoDetailActivity.this);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(14.5f);
            GridLayoutManager.LayoutParams lp = new GridLayoutManager.LayoutParams(-1, dp(46));
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            t.setLayoutParams(lp);
            return new H(t);
        }

        @Override
        public void onBindViewHolder(@NonNull H h, int position) {
            int ep = position + 1;
            boolean on = ep == currentEpisode;
            h.text.setText(String.valueOf(ep));
            h.text.setTextColor(on ? Color.WHITE : 0xFFE8E8EA);
            h.text.setTypeface(on ? android.graphics.Typeface.DEFAULT_BOLD
                    : android.graphics.Typeface.DEFAULT);
            h.text.setBackground(roundBg(on ? 0xFFFF5A4E : 0xFF232328, dp(10)));
            h.text.setOnClickListener(v -> {
                if (detail == null) return;
                HongguoFeedActivity.startSeries(HongguoDetailActivity.this,
                        seriesId, detail.getTitle(), ep);
            });
        }

        @Override
        public int getItemCount() {
            return detail == null ? 0 : detail.getEpisodes().size();
        }
    }
}
