package com.fongmi.android.tv.hongguo.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.ActivityHongguoListBinding;
import com.fongmi.android.tv.databinding.ItemHongguoSearchBinding;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.api.HgLoginAuthProvider;
import com.fongmi.android.tv.hongguo.model.HgCellPage;
import com.fongmi.android.tv.hongguo.model.HgSeriesItem;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 红果列表页（追更/收藏/观看历史共用一页，kind 区分）：
 * <ul>
 *   <li>追更：已登录先显 HgStore 镜像、再拉追剧架 bookshelf/video/list 以
 *       服务端为准刷新（回来顺手同步镜像）；未登录只显镜像（通常为空态）。</li>
 *   <li>收藏：HgStore 收藏镜像（远程同步落的镜像，官方态为准）。</li>
 *   <li>观看历史：HgStore 断点记录（剧/集/位置/时刻齐），最近在前。</li>
 * </ul>
 * 条目点中一律走 {@link HongguoFeedActivity#startSeries} 进该剧刷剧流，
 * 续播由流页按 HgStore 断点定位（历史带位置、追更/收藏带上次集数）。
 * 空态诚实：没数据就一句文案，不编条目。
 */
public class HgListActivity extends BaseActivity {

    public static final int KIND_FOLLOW = 0;
    public static final int KIND_FAV = 1;
    public static final int KIND_HISTORY = 2;
    private static final String EXTRA_KIND = "hg_list_kind";

    public static void start(Context context, int kind) {
        Intent intent = new Intent(context, HgListActivity.class);
        intent.putExtra(EXTRA_KIND, kind);
        context.startActivity(intent);
    }

    /** 列表一行（内部行模型）。 */
    private static final class Row {
        final String seriesId;
        final String title;
        final String cover;
        final String meta;

        Row(String seriesId, String title, String cover, String meta) {
            this.seriesId = seriesId;
            this.title = title;
            this.cover = cover;
            this.meta = meta;
        }
    }

    private ActivityHongguoListBinding binding;
    private HgFeedSession session;
    private int kind;
    private final List<Row> rows = new ArrayList<>();
    private RecyclerView.Adapter<Holder> adapter;

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityHongguoListBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected boolean useDarkSystemIcons() {
        return false;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        session = HgFeedSession.get();
        kind = getIntent().getIntExtra(EXTRA_KIND, KIND_HISTORY);
        binding.hgListTitle.setText(kind == KIND_FOLLOW ? "追更" : kind == KIND_FAV ? "收藏" : "观看历史");
        adapter = new RecyclerView.Adapter<Holder>() {
            @NonNull
            @Override
            public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new Holder(ItemHongguoSearchBinding.inflate(
                        LayoutInflater.from(parent.getContext()), parent, false));
            }

            @Override
            public void onBindViewHolder(@NonNull Holder holder, int position) {
                Row row = rows.get(position);
                ItemHongguoSearchBinding b = holder.binding;
                ImgUtil.load(row.title == null ? "" : row.title, row.cover, b.hgSCover);
                b.hgSTitle.setText(row.title == null || row.title.isEmpty() ? row.seriesId : row.title);
                b.hgSMeta.setText(row.meta == null ? "" : row.meta);
                b.getRoot().setOnClickListener(v ->
                        HongguoFeedActivity.startSeries(HgListActivity.this, row.seriesId, row.title, row.cover));
            }

            @Override
            public int getItemCount() {
                return rows.size();
            }
        };
        binding.hgListList.setLayoutManager(new LinearLayoutManager(this));
        binding.hgListList.setAdapter(adapter);
        load();
    }

    @Override
    protected void initEvent() {
        binding.hgListBack.setOnClickListener(v -> finish());
    }

    private void load() {
        switch (kind) {
            case KIND_FAV -> {
                for (String id : HgStore.favedIds()) rows.add(rowFromLocal(id));
                showRows("还没有收藏的剧");
            }
            case KIND_HISTORY -> {
                for (HgStore.HistoryRecord r : HgStore.historyRecords()) {
                    StringBuilder meta = new StringBuilder();
                    meta.append("看到第").append(r.episodeIndex).append("集");
                    if (r.positionMs > 0) meta.append(" · ").append(formatDuration(r.positionMs));
                    if (r.episodeCount > 0) meta.append(" · 全").append(r.episodeCount).append("集");
                    rows.add(new Row(r.seriesId, r.title, r.cover, meta.toString()));
                }
                showRows("还没有观看记录");
            }
            case KIND_FOLLOW -> {
                for (String id : HgStore.followedIds()) rows.add(rowFromLocal(id));
                showRows(HgLoginAuthProvider.isLoggedIn() ? "还没有追更的剧" : "登录后这里显示你追的剧");
                if (HgLoginAuthProvider.isLoggedIn()) refreshFollowFromServer();
            }
            default -> showRows("");
        }
    }

    /** 追更页后台刷新：追剧架列表回来后以服务端为准重列，并同步本地镜像。 */
    private void refreshFollowFromServer() {
        session.client.bookshelfVideoList(new HgCallback<HgCellPage>() {
            @Override
            public void onSuccess(HgCellPage page) {
                runOnUiThread(() -> {
                    if (page.getItems() == null) return;
                    List<Row> fresh = new ArrayList<>();
                    for (HgSeriesItem s : page.getItems()) {
                        if (s.getSeriesId() == null) continue;
                        HgStore.setShelfMirrored(s.getSeriesId(), true);
                        fresh.add(rowOf(s.getSeriesId(),
                                s.getTitle() != null ? s.getTitle() : titleOf(s.getSeriesId()),
                                s.getCoverUrl() != null ? s.getCoverUrl() : coverOf(s.getSeriesId()),
                                s.getEpisodeCount()));
                    }
                    if (!fresh.isEmpty() || rows.isEmpty()) {
                        rows.clear();
                        rows.addAll(fresh);
                        showRows("还没有追更的剧");
                    }
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                // 镜像已先显，刷新失败不打扰（页面已有内容/空态）
            }
        });
    }

    private Row rowFromLocal(String seriesId) {
        return rowOf(seriesId, titleOf(seriesId), coverOf(seriesId), countOf(seriesId));
    }

    private Row rowOf(String seriesId, String title, String cover, int episodeCount) {
        StringBuilder meta = new StringBuilder();
        if (episodeCount > 0) meta.append("全").append(episodeCount).append("集");
        HgStore.Progress p = HgStore.progressOf(seriesId);
        if (p != null) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append("看到第").append(p.episodeIndex).append("集");
        }
        return new Row(seriesId, title, cover, meta.toString());
    }

    private static String titleOf(String seriesId) {
        String[] meta = HgStore.metaOf(seriesId);
        return meta == null || meta[0].isEmpty() ? null : meta[0];
    }

    private static String coverOf(String seriesId) {
        String[] meta = HgStore.metaOf(seriesId);
        return meta == null || meta[1].isEmpty() ? null : meta[1];
    }

    private static int countOf(String seriesId) {
        String[] meta = HgStore.metaOf(seriesId);
        if (meta == null) return -1;
        try {
            return Integer.parseInt(meta[2]);
        } catch (Exception e) {
            return -1;
        }
    }

    private void showRows(String emptyText) {
        adapter.notifyDataSetChanged();
        if (rows.isEmpty()) {
            binding.hgListState.setVisibility(View.VISIBLE);
            binding.hgListState.setText(emptyText);
        } else {
            binding.hgListState.setVisibility(View.GONE);
        }
    }

    private static String formatDuration(long ms) {
        long sec = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%d:%02d", sec / 60, sec % 60);
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final ItemHongguoSearchBinding binding;

        Holder(ItemHongguoSearchBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
