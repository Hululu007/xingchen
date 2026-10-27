package com.fongmi.android.tv.hongguo.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.login.LoginManager;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 红果「我的」面（刷剧页壳内、底栏「我的」格）：登录区＋页签排＋海报格子，
 * 页签点开直接是格子内容，不再一层层菜单条（他对旧集合页的定罪点）。
 *
 * <p>构成按他的收敛口径：登录是死规格（未登录显「立即登录」走壳子统一托管的
 * 红果登录、登录后显账号态）；页签序＝历史／收藏／点赞／预约——预约链没抠通
 * 之前预约页签先不摆（不许假占位）；顶部四口排整排取消不留。历史格子带
 * 已看百分比（HgStore 断点＋总集数算得出才带）。收藏/点赞读本地镜像
 * （与旧列表页同源同口径），点格子回刷剧页续播。</p>
 */
public final class HgMineView extends LinearLayout {

    public interface OnOpenSeries {
        void onOpenSeries(String seriesId, String title, String cover, int episodeCount);
    }

    private static final int TAB_HISTORY = 0;
    private static final int TAB_FAV = 1;
    private static final int TAB_LIKE = 2;

    private TextView accountView;
    private TextView emptyView;
    private final TextView[] tabViews = new TextView[3];
    private final MineAdapter adapter = new MineAdapter();
    private int currentTab = TAB_HISTORY;
    private OnOpenSeries openListener;
    private int loginPollLeft;
    private final Runnable loginPoll = new Runnable() {
        @Override
        public void run() {
            if (LoginManager.isHongguoLoggedIn()) {
                refreshAccount();
                return;
            }
            if (--loginPollLeft > 0) postDelayed(this, 600);
        }
    };

    public HgMineView(Context context) {
        super(context);
    }

    public HgMineView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        accountView = findViewById(R.id.hg_mine_account);
        emptyView = findViewById(R.id.hg_mine_empty);
        tabViews[TAB_HISTORY] = findViewById(R.id.hg_mine_tab_history);
        tabViews[TAB_FAV] = findViewById(R.id.hg_mine_tab_fav);
        tabViews[TAB_LIKE] = findViewById(R.id.hg_mine_tab_like);
        RecyclerView grid = findViewById(R.id.hg_mine_grid);
        grid.setLayoutManager(new GridLayoutManager(getContext(), 3));
        grid.setAdapter(adapter);
        accountView.setOnClickListener(v -> onAccountClick());
        for (int i = 0; i < tabViews.length; i++) {
            final int tab = i;
            tabViews[i].setOnClickListener(v -> selectTab(tab));
        }
    }

    public void setOnOpenSeriesListener(OnOpenSeries listener) {
        this.openListener = listener;
    }

    /** 每次切到本面时调：账号态与当前页签数据都按最新状态重读。 */
    public void refresh() {
        refreshAccount();
        renderTab();
    }

    /** 页面销毁时停掉登录轮询，别让回调落到已销毁的面上。 */
    public void cancelPending() {
        removeCallbacks(loginPoll);
    }

    private void refreshAccount() {
        if (LoginManager.isHongguoLoggedIn()) {
            accountView.setText("红果账号已登录 · 点此退出");
        } else {
            accountView.setText("立即登录");
        }
    }

    private void onAccountClick() {
        if (!LoginManager.isHongguoLoggedIn()) {
            // 走壳子统一托管的红果登录（短信链），回来轮询刷账号态
            LoginManager.loginHongguoDelivered();
            loginPollLeft = 20;
            removeCallbacks(loginPoll);
            postDelayed(loginPoll, 600);
            return;
        }
        new AlertDialog.Builder(getContext())
                .setMessage("退出红果登录？")
                .setNegativeButton("取消", null)
                .setPositiveButton("退出", (d, w) -> {
                    LoginManager.logoutHongguo();
                    refreshAccount();
                })
                .show();
    }

    private void selectTab(int tab) {
        currentTab = tab;
        renderTab();
    }

    private void renderTab() {
        for (int i = 0; i < tabViews.length; i++) {
            boolean on = i == currentTab;
            tabViews[i].setTextColor(on ? 0xFF111111 : 0xFF999999);
            tabViews[i].setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
        List<Row> rows = new ArrayList<>();
        if (currentTab == TAB_HISTORY) {
            for (HgStore.HistoryRecord r : HgStore.historyRecords()) {
                if (r.title == null) continue;
                String meta;
                if (r.episodeCount > 0) {
                    int pct = Math.min(100, (r.episodeIndex + 1) * 100 / r.episodeCount);
                    meta = "已看 " + pct + "% · 共" + r.episodeCount + "集";
                } else {
                    meta = "看到第" + (r.episodeIndex + 1) + "集";
                }
                rows.add(new Row(r.seriesId, r.title, r.cover, r.episodeCount, meta));
            }
        } else {
            Set<String> ids = currentTab == TAB_FAV ? HgStore.favedIds() : HgStore.likedIds();
            for (String id : ids) {
                String[] meta = HgStore.metaOf(id);
                if (meta == null) continue;
                int count = -1;
                try {
                    count = Integer.parseInt(meta[2]);
                } catch (Exception ignored) {
                }
                rows.add(new Row(id, meta[0], meta[1], count, count > 0 ? "共" + count + "集" : ""));
            }
        }
        adapter.setRows(rows);
        emptyView.setVisibility(rows.isEmpty() ? VISIBLE : GONE);
        if (rows.isEmpty()) {
            emptyView.setText(currentTab == TAB_HISTORY ? "还没有观看记录"
                    : currentTab == TAB_FAV ? "还没有收藏" : "还没有点赞");
        }
    }

    private static final class Row {
        final String seriesId;
        final String title;
        final String cover;
        final int episodeCount;
        final String meta;

        Row(String seriesId, String title, String cover, int episodeCount, String meta) {
            this.seriesId = seriesId;
            this.title = title;
            this.cover = cover;
            this.episodeCount = episodeCount;
            this.meta = meta;
        }
    }

    private final class MineAdapter extends RecyclerView.Adapter<MineAdapter.Holder> {

        private List<Row> rows = new ArrayList<>();

        void setRows(List<Row> next) {
            rows = next;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_hongguo_theater, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Row row = rows.get(position);
            ImgUtil.load(row.title == null ? "" : row.title, row.cover, h.cover);
            h.title.setText(row.title == null ? "" : row.title);
            h.meta.setText(row.meta == null ? "" : row.meta);
            h.reserve.setVisibility(GONE);
            h.itemView.setOnClickListener(v -> {
                if (openListener != null) {
                    openListener.onOpenSeries(row.seriesId, row.title, row.cover, row.episodeCount);
                }
            });
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView cover;
            final TextView title;
            final TextView meta;
            final TextView reserve;

            Holder(View itemView) {
                super(itemView);
                cover = itemView.findViewById(R.id.hg_t_cover);
                title = itemView.findViewById(R.id.hg_t_title);
                meta = itemView.findViewById(R.id.hg_t_meta);
                reserve = itemView.findViewById(R.id.hg_t_reserve);
            }
        }
    }
}
