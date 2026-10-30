package com.fongmi.android.tv.hongguo.ui;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.drama.DramaCellPage;
import com.fongmi.android.tv.hongguo.drama.DramaClient;
import com.fongmi.android.tv.hongguo.drama.DramaItem;
import com.fongmi.android.tv.hongguo.model.HgContinuation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 剧场页（2026-10-30 从零重建，他亲定底栏第二签）：简单分类浏览——
 * 分类签（推荐/漫剧/真人剧）＋海报网格，一页只干浏览这一件事；
 * 点海报进详情页。底栏两格与首页同构（剧场选中态）。
 */
public class HongguoTheaterActivity extends AppCompatActivity {

    private static final int[][] CHANNELS = {{-1, 16}, {36, 36}, {39, 39}};
    private static final String[] CHANNEL_NAMES = {"推荐", "漫剧", "真人剧"};

    private RecyclerView grid;
    private GridAdapter adapter;
    private TextView[] tabViews;
    private TextView stateText;
    private final List<DramaItem> items = new ArrayList<>();
    private int channel = 0;
    private HgContinuation continuation;
    private boolean hasMore;
    private boolean loading;

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

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF111114);

        // 顶栏：标题＋搜索
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), statusBarHeight() + dp(8), dp(10), dp(4));
        TextView title = new TextView(this);
        title.setText("剧场");
        title.setTextColor(Color.WHITE);
        title.setTextSize(19);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView search = new ImageView(this);
        search.setImageResource(R.drawable.ic_drama_search);
        search.setColorFilter(Color.WHITE);
        search.setPadding(dp(8), dp(8), dp(8), dp(8));
        search.setOnClickListener(v -> startActivity(new Intent(this, HongguoSearchActivity.class)));
        top.addView(search, new LinearLayout.LayoutParams(dp(40), dp(40)));
        root.addView(top);

        // 分类签
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(10), dp(2), dp(10), dp(6));
        tabViews = new TextView[CHANNEL_NAMES.length];
        for (int i = 0; i < CHANNEL_NAMES.length; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(CHANNEL_NAMES[i]);
            t.setTextSize(14.5f);
            t.setPadding(dp(13), dp(6), dp(13), dp(6));
            t.setOnClickListener(v -> {
                if (channel != idx) {
                    channel = idx;
                    selectTab();
                    load(true);
                }
            });
            tabViews[i] = t;
            tabs.addView(t);
        }
        root.addView(tabs);

        // 网格
        grid = new RecyclerView(this);
        GridLayoutManager glm = new GridLayoutManager(this, 3);
        grid.setLayoutManager(glm);
        grid.setPadding(dp(8), dp(2), dp(8), dp(8));
        grid.setClipToPadding(false);
        adapter = new GridAdapter();
        grid.setAdapter(adapter);
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy > 0 && !loading && hasMore
                        && glm.findLastVisibleItemPosition() >= items.size() - 6) {
                    load(false);
                }
            }
        });
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));

        stateText = new TextView(this);
        stateText.setTextColor(0xFF9E9EA7);
        stateText.setTextSize(13.5f);
        stateText.setGravity(Gravity.CENTER);
        stateText.setPadding(0, dp(10), 0, dp(10));
        stateText.setVisibility(View.GONE);
        stateText.setOnClickListener(v -> {
            if (items.isEmpty()) load(true);
        });
        root.addView(stateText);

        // 底栏（首页/剧场，剧场选中）
        root.addView(buildBottomBar());

        setContentView(root);
        selectTab();
        load(true);
    }

    private View buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xFF17171A);
        bar.setPadding(0, 0, 0, navBarHeight());
        LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, dp(52), 1f);
        bar.addView(bottomCell(R.drawable.ic_drama_home, "首页", false, v -> finish()), cell);
        bar.addView(bottomCell(R.drawable.ic_drama_theater, "剧场", true, null), cell);
        return bar;
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

    private void selectTab() {
        for (int i = 0; i < tabViews.length; i++) {
            boolean on = i == channel;
            tabViews[i].setTextColor(on ? Color.WHITE : 0xFF9E9EA7);
            tabViews[i].setTypeface(on ? android.graphics.Typeface.DEFAULT_BOLD
                    : android.graphics.Typeface.DEFAULT);
            tabViews[i].setBackground(roundBg(on ? 0xFF2C2C30 : Color.TRANSPARENT, dp(14)));
        }
    }

    private GradientDrawable roundBg(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private void load(boolean first) {
        if (loading) return;
        loading = true;
        if (first) {
            items.clear();
            adapter.notifyDataSetChanged();
            continuation = null;
            hasMore = false;
            stateText.setText("加载中…");
            stateText.setVisibility(View.VISIBLE);
        }
        HgCallback<DramaCellPage> cb = new HgCallback<DramaCellPage>() {
            @Override
            public void onSuccess(DramaCellPage page) {
                runOnUiThread(() -> onPage(page));
            }

            @Override
            public void onError(int code, String msg) {
                runOnUiThread(() -> {
                    loading = false;
                    if (items.isEmpty()) {
                        stateText.setText("取数据失败，点按重试");
                        stateText.setVisibility(View.VISIBLE);
                    }
                });
            }
        };
        if (first || continuation == null) {
            int[] ch = CHANNELS[channel];
            DramaClient.fetchChannel(ch[0], ch[1], cb);
        } else {
            DramaClient.fetchChannelMore(continuation, cb);
        }
    }

    private void onPage(DramaCellPage page) {
        loading = false;
        hasMore = page.isHasMore();
        if (page.getContinuation() != null) continuation = page.getContinuation();
        Set<String> seen = new HashSet<>();
        for (DramaItem it : items) if (it.getSeriesId() != null) seen.add(it.getSeriesId());
        for (DramaItem it : page.getItems()) {
            String sid = it.getSeriesId();
            if (sid == null || !seen.add(sid)) continue;
            items.add(it);
        }
        adapter.notifyDataSetChanged();
        stateText.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        if (items.isEmpty()) stateText.setText("暂时没有内容，点按重试");
    }

    private class GridAdapter extends RecyclerView.Adapter<GridAdapter.H> {

        class H extends RecyclerView.ViewHolder {
            final ImageView cover;
            final TextView title;
            final TextView meta;

            H(LinearLayout cell) {
                super(cell);
                cover = cell.findViewWithTag("cover");
                title = cell.findViewWithTag("title");
                meta = cell.findViewWithTag("meta");
            }
        }

        @NonNull
        @Override
        public H onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout cell = new LinearLayout(HongguoTheaterActivity.this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setPadding(dp(5), dp(6), dp(5), dp(10));
            ImageView cover = new ImageView(HongguoTheaterActivity.this);
            cover.setTag("cover");
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackground(roundBg(0xFF232328, dp(8)));
            int w = (getResources().getDisplayMetrics().widthPixels - dp(46)) / 3;
            cell.addView(cover, new LinearLayout.LayoutParams(-1, (int) (w * 1.38f)));
            TextView title = new TextView(HongguoTheaterActivity.this);
            title.setTag("title");
            title.setTextColor(0xFFF2F2F4);
            title.setTextSize(13);
            title.setMaxLines(1);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
            tlp.topMargin = dp(6);
            cell.addView(title, tlp);
            TextView meta = new TextView(HongguoTheaterActivity.this);
            meta.setTag("meta");
            meta.setTextColor(0xFF8E8E96);
            meta.setTextSize(11);
            meta.setMaxLines(1);
            cell.addView(meta);
            return new H(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull H h, int position) {
            DramaItem item = items.get(position);
            if (item.getCoverUrl() != null && !item.getCoverUrl().isEmpty()) {
                Glide.with(HongguoTheaterActivity.this).load(item.getCoverUrl()).into(h.cover);
            } else {
                h.cover.setImageDrawable(null);
            }
            h.title.setText(item.getTitle() == null ? "" : item.getTitle());
            int total = item.getEpisodeCount();
            h.meta.setText(total > 0
                    ? (item.isFinished() ? "全" + total + "集" : "更新至" + total + "集")
                    : "");
            h.itemView.setOnClickListener(v -> {
                if (item.getAlbumId() != null && item.isMultiSeason()) {
                    HongguoDetailActivity.start(HongguoTheaterActivity.this,
                            item.getSeriesId(), item.getTitle(), item.getAlbumId());
                } else {
                    HongguoDetailActivity.start(HongguoTheaterActivity.this,
                            item.getSeriesId(), item.getTitle(), null);
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
