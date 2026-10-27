package com.fongmi.android.tv.hongguo.ui;

import android.app.Dialog;
import android.graphics.Color;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ItemHongguoSeriesRowBinding;
import com.fongmi.android.tv.databinding.ViewHongguoSeriesSheetBinding;
import com.fongmi.android.tv.hongguo.model.HgSeriesItem;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 「系列剧」半屏单（研究账 §5.4）：进多季系列前先选部——专辑里的每一部
 * 各列一条（海报、剧名、副行、热度、各自的收藏钮），点中哪部进哪部的
 * 集流。只在回包真带专辑关系（album_data_list）时才弹；副行/热度只拼
 * 回包实证字段（集数、角标、rec_text），引言/演员/点赞数字段无回包
 * 样本（账 U5）不编、不显。
 */
public final class HgSeriesSheet {

    public interface OnPick {
        void onPick(HgSeriesItem item);
    }

    private HgSeriesSheet() {
    }

    /**
     * @param siblings 专辑兄弟部（HgParser 自 album_data_list 抽，已含顺序）
     * @param current  当前短片所属剧：不在 siblings 里时补列在首位
     */
    public static void show(FragmentActivity activity, List<HgSeriesItem> siblings,
                            HgFeedItem current, HgInteractions interactions, OnPick onPick) {
        List<HgSeriesItem> rows = new ArrayList<>();
        boolean hasCurrent = false;
        for (HgSeriesItem s : siblings) {
            if (s == null) continue;
            rows.add(s);
            if (current != null && s.getSeriesId() != null
                    && s.getSeriesId().equals(current.seriesId)) hasCurrent = true;
        }
        if (current != null && !hasCurrent) {
            rows.add(0, HgSeriesItem.of(current.seriesId, current.title, current.coverUrl,
                    current.desc, current.episodeCount,
                    current.reservable, interactions.isReserved(current.seriesId),
                    current.dueStartText).withSeriesStatus(current.seriesStatus));
        }
        if (rows.isEmpty()) return;

        ViewHongguoSeriesSheetBinding sheet = ViewHongguoSeriesSheetBinding.inflate(
                LayoutInflater.from(activity));
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(sheet.getRoot());
        Window window = dialog.getWindow();
        if (window != null) {
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.dimAmount = 0.45f;
            window.setAttributes(lp);
        }
        sheet.hgSeriesClose.setOnClickListener(v -> dialog.dismiss());
        sheet.hgSeriesList.setLayoutManager(new LinearLayoutManager(activity));
        sheet.hgSeriesList.setAdapter(new RecyclerView.Adapter<RowHolder>() {
            @NonNull
            @Override
            public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new RowHolder(ItemHongguoSeriesRowBinding.inflate(
                        LayoutInflater.from(parent.getContext()), parent, false));
            }

            @Override
            public void onBindViewHolder(@NonNull RowHolder holder, int position) {
                HgSeriesItem item = rows.get(position);
                ItemHongguoSeriesRowBinding b = holder.binding;
                ImgUtil.load(item.getTitle() == null ? "" : item.getTitle(),
                        item.getCoverUrl(), b.hgRowCover);
                b.hgRowTitle.setText(item.getTitle() == null ? "" : item.getTitle());
                StringBuilder sub = new StringBuilder();
                // 副行口径（U5 定版）：官方行副行＝sub_title_list 前两条
                // （文案承载，含预约数/题材等），有就用它；没有才回落自拼的
                // 集数/完结/角标（数据缺失面不至空白）
                java.util.List<String> subTitles = item.getTags();
                if (subTitles != null && !subTitles.isEmpty()) {
                    int n = Math.min(2, subTitles.size());
                    for (int i = 0; i < n; i++) {
                        if (sub.length() > 0) sub.append(" · ");
                        sub.append(subTitles.get(i));
                    }
                } else {
                    if (item.getEpisodeCount() > 0) sub.append("全").append(item.getEpisodeCount()).append("集");
                    if (item.isFinished()) {
                        if (sub.length() > 0) sub.append(" · ");
                        sub.append("已完结");
                    }
                    if (item.getBadge() != null && !item.getBadge().isEmpty()) {
                        if (sub.length() > 0) sub.append(" · ");
                        sub.append(item.getBadge());
                    }
                }
                b.hgRowSub.setText(sub.toString());
                b.hgRowSub.setVisibility(sub.length() > 0 ? View.VISIBLE : View.GONE);
                if (item.getRecText() != null && !item.getRecText().isEmpty()) {
                    b.hgRowHeat.setVisibility(View.VISIBLE);
                    b.hgRowHeat.setText(item.getRecText());
                } else {
                    b.hgRowHeat.setVisibility(View.GONE);
                }
                bindFav(b, item, interactions);
                b.hgRowFav.setOnClickListener(v -> {
                    // 收藏钮走互动真口子（只认 seriesId）；未登录在口子内拉登录
                    interactions.toggleFavorite(item.getSeriesId());
                    bindFav(b, item, interactions);
                });
                holder.itemView.setOnClickListener(v -> {
                    dialog.dismiss();
                    if (onPick != null) onPick.onPick(item);
                });
            }

            @Override
            public int getItemCount() {
                return rows.size();
            }
        });
        dialog.show();
    }

    private static void bindFav(ItemHongguoSeriesRowBinding b, HgSeriesItem item,
                                HgInteractions interactions) {
        boolean fav = interactions.isFavorite(item.getSeriesId());
        b.hgRowFav.setImageResource(fav ? R.drawable.ic_hg_star_fill : R.drawable.ic_hg_star);
        // 描边星本体是白色（给深底流页用的），在白底单上着灰；收藏态用星自带的暖黄
        if (fav) b.hgRowFav.clearColorFilter();
        else b.hgRowFav.setColorFilter(Color.rgb(0x9E, 0x9E, 0x9E));
    }

    private static final class RowHolder extends RecyclerView.ViewHolder {
        final ItemHongguoSeriesRowBinding binding;

        RowHolder(ItemHongguoSeriesRowBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
