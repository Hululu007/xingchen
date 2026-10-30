package com.fongmi.android.tv.hongguo.ui;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.hongguo.api.HgApiClient;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.drama.DramaCache;

import java.util.ArrayList;
import java.util.List;

/**
 * 我的页（2026-10-30 从零重建）：入口在首页抽屉顶部一行（他亲定）。
 * 只放三格——观看历史/我的收藏（本地 HgStore）/小说历史（小说线既有
 * 本地口径）——加两件必要设置：刷新设备档案、清红果缓存。无登录、
 * 无账号区。点历史/收藏条目带断点回竖屏流；点小说条目进小说详情。
 */
public class HongguoMineActivity extends AppCompatActivity {

    private static final int MODE_HOME = 0;
    private static final int MODE_HISTORY = 1;
    private static final int MODE_FAV = 2;
    private static final int MODE_NOVEL = 3;

    private TextView titleText;
    private LinearLayout homeBox;
    private RecyclerView list;
    private RowAdapter adapter;
    private TextView emptyText;
    private int mode = MODE_HOME;
    private final List<Row> rows = new ArrayList<>();

    private static final class Row {
        String id;
        String title;
        String sub;
        String cover;
    }

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
        root.setPadding(0, 0, 0, navBarHeight());

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(6), statusBarHeight() + dp(6), dp(16), dp(8));
        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextColor(Color.WHITE);
        back.setTextSize(26);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> onBackRow());
        top.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));
        titleText = new TextView(this);
        titleText.setText("我的");
        titleText.setTextColor(Color.WHITE);
        titleText.setTextSize(18);
        titleText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        top.addView(titleText);
        root.addView(top);

        homeBox = new LinearLayout(this);
        homeBox.setOrientation(LinearLayout.VERTICAL);
        homeBox.setPadding(dp(16), dp(6), dp(16), 0);
        homeBox.addView(entryRow("观看历史", v -> openList(MODE_HISTORY, "观看历史")));
        homeBox.addView(entryRow("我的收藏", v -> openList(MODE_FAV, "我的收藏")));
        homeBox.addView(entryRow("小说历史", v -> openList(MODE_NOVEL, "小说历史")));
        TextView settingsLabel = new TextView(this);
        settingsLabel.setText("设置");
        settingsLabel.setTextColor(0xFF8E8E96);
        settingsLabel.setTextSize(12.5f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(22);
        slp.bottomMargin = dp(2);
        homeBox.addView(settingsLabel, slp);
        homeBox.addView(entryRow("刷新设备档案", v -> refreshDevice()));
        homeBox.addView(entryRow("清红果缓存", v -> {
            DramaCache.clear();
            Toast.makeText(this, "缓存已清", Toast.LENGTH_SHORT).show();
        }));
        root.addView(homeBox);

        list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setVisibility(View.GONE);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));

        emptyText = new TextView(this);
        emptyText.setTextColor(0xFF9E9EA7);
        emptyText.setTextSize(13.5f);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setPadding(0, dp(28), 0, 0);
        emptyText.setVisibility(View.GONE);
        root.addView(emptyText);

        setContentView(root);
    }

    private View entryRow(String label, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(15), dp(2), dp(15));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(0xFFF2F2F4);
        t.setTextSize(15);
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(0xFF6E6E76);
        arrow.setTextSize(17);
        row.addView(arrow);
        row.setOnClickListener(click);
        return row;
    }

    private void onBackRow() {
        if (mode != MODE_HOME) {
            mode = MODE_HOME;
            titleText.setText("我的");
            list.setVisibility(View.GONE);
            emptyText.setVisibility(View.GONE);
            homeBox.setVisibility(View.VISIBLE);
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        if (mode != MODE_HOME) onBackRow();
        else super.onBackPressed();
    }

    private void openList(int target, String title) {
        mode = target;
        titleText.setText(title);
        homeBox.setVisibility(View.GONE);
        rows.clear();
        if (target == MODE_HISTORY) {
            for (HgStore.HistoryRecord r : HgStore.historyRecords()) {
                Row row = new Row();
                row.id = r.seriesId;
                row.title = r.title == null ? "" : r.title;
                row.cover = r.cover;
                row.sub = "看到第" + (r.episodeIndex + 1) + "集";
                rows.add(row);
            }
        } else if (target == MODE_FAV) {
            for (String id : HgStore.favedIds()) {
                String[] meta = HgStore.metaOf(id);
                Row row = new Row();
                row.id = id;
                row.title = meta == null ? "" : meta[0];
                row.cover = meta == null ? null : meta[1];
                int count = -1;
                if (meta != null) {
                    try {
                        count = Integer.parseInt(meta[2]);
                    } catch (Exception ignored) {
                    }
                }
                row.sub = count > 0 ? "共" + count + "集" : "";
                rows.add(row);
            }
        } else if (target == MODE_NOVEL) {
            for (String id : HgStore.novelHistoryIds()) {
                String[] meta = HgStore.novelMetaOf(id);
                Row row = new Row();
                row.id = id;
                row.title = meta == null ? "" : meta[0];
                row.sub = meta == null ? "" : meta[1];
                row.cover = meta == null ? null : meta[2];
                rows.add(row);
            }
        }
        adapter.notifyDataSetChanged();
        list.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
        emptyText.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        if (rows.isEmpty()) {
            emptyText.setText(target == MODE_HISTORY ? "还没有观看记录"
                    : target == MODE_FAV ? "还没有收藏" : "还没有小说记录");
        }
    }

    private void refreshDevice() {
        HgApiClient.getInstance().resetDeviceProfile(new HgCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean ok) {
                runOnUiThread(() -> Toast.makeText(HongguoMineActivity.this,
                        "设备档案已刷新", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onError(int code, String msg) {
                runOnUiThread(() -> Toast.makeText(HongguoMineActivity.this,
                        "刷新失败，请重试", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void openRow(Row row) {
        if (mode == MODE_NOVEL) {
            Intent intent = new Intent(this, HongguoNovelDetailActivity.class);
            intent.putExtra(HongguoNovelDetailActivity.EXTRA_BOOK_ID, row.id);
            intent.putExtra(HongguoNovelDetailActivity.EXTRA_TITLE, row.title);
            startActivity(intent);
            return;
        }
        HgStore.Progress p = mode == MODE_HISTORY ? HgStore.progressOf(row.id) : null;
        int ep = p == null ? 0 : p.episodeIndex;
        HongguoFeedActivity.startSeries(this, row.id, row.title, ep);
    }

    private GradientDrawable roundBg(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private class RowAdapter extends RecyclerView.Adapter<RowAdapter.H> {

        class H extends RecyclerView.ViewHolder {
            final ImageView cover;
            final TextView title;
            final TextView sub;

            H(LinearLayout cell) {
                super(cell);
                cover = cell.findViewWithTag("cover");
                title = cell.findViewWithTag("title");
                sub = cell.findViewWithTag("sub");
            }
        }

        @NonNull
        @Override
        public H onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout cell = new LinearLayout(HongguoMineActivity.this);
            cell.setOrientation(LinearLayout.HORIZONTAL);
            cell.setGravity(Gravity.CENTER_VERTICAL);
            cell.setPadding(dp(16), dp(9), dp(16), dp(9));
            ImageView cover = new ImageView(HongguoMineActivity.this);
            cover.setTag("cover");
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackground(roundBg(0xFF232328, dp(6)));
            cell.addView(cover, new LinearLayout.LayoutParams(dp(46), dp(62)));
            LinearLayout texts = new LinearLayout(HongguoMineActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView title = new TextView(HongguoMineActivity.this);
            title.setTag("title");
            title.setTextColor(0xFFF2F2F4);
            title.setTextSize(14.5f);
            title.setMaxLines(1);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(title);
            TextView sub = new TextView(HongguoMineActivity.this);
            sub.setTag("sub");
            sub.setTextColor(0xFF8E8E96);
            sub.setTextSize(12);
            sub.setMaxLines(1);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
            slp.topMargin = dp(4);
            texts.addView(sub, slp);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
            tlp.leftMargin = dp(12);
            cell.addView(texts, tlp);
            return new H(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull H h, int position) {
            Row row = rows.get(position);
            if (row.cover != null && !row.cover.isEmpty()) {
                Glide.with(HongguoMineActivity.this).load(row.cover).into(h.cover);
            } else {
                h.cover.setImageDrawable(null);
            }
            h.title.setText(row.title);
            h.sub.setText(row.sub == null ? "" : row.sub);
            h.itemView.setOnClickListener(v -> openRow(row));
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }
}
