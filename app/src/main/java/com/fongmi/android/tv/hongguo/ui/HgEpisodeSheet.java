package com.fongmi.android.tv.hongguo.ui;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.databinding.DialogHongguoEpisodesBinding;
import com.fongmi.android.tv.databinding.ItemHongguoEpisodeBinding;
import com.fongmi.android.tv.hongguo.model.HgSeriesDetail;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * 选集弹层：半屏底部层（可拉升），集数格子直接上下滑、无分页签，
 * 当前集高亮，点集回调全局下标由播放页跳播。剧头尚未展开时只列已知集。
 */
public final class HgEpisodeSheet {

    private HgEpisodeSheet() {
    }

    public static void show(Activity activity, HgFeedSession session, int currentGlobalPos,
                            IntConsumer onPick) {
        if (currentGlobalPos < 0 || currentGlobalPos >= session.items.size()) return;
        HgFeedItem current = session.items.get(currentGlobalPos);
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < session.items.size(); i++) {
            HgFeedItem it = session.items.get(i);
            if (it.seriesId != null && it.seriesId.equals(current.seriesId) && !it.seriesHead) positions.add(i);
        }
        positions.sort((a, b) -> Integer.compare(
                session.items.get(a).episodeIndex, session.items.get(b).episodeIndex));
        if (positions.isEmpty()) positions.add(currentGlobalPos);

        DialogHongguoEpisodesBinding binding = DialogHongguoEpisodesBinding.inflate(
                LayoutInflater.from(activity));
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        dialog.setContentView(binding.getRoot());
        binding.hgEpTitle.setText(current.seriesBarText());
        HgSeriesDetail detail = session.detailOf(current.seriesId);
        String desc = detail != null && detail.getDesc() != null ? detail.getDesc() : current.desc;
        if (desc == null || desc.isEmpty()) binding.hgEpDesc.setVisibility(View.GONE);
        else binding.hgEpDesc.setText(desc);
        binding.hgEpClose.setOnClickListener(v -> dialog.dismiss());

        binding.hgEpGrid.setLayoutManager(new GridLayoutManager(activity, 5));
        binding.hgEpGrid.setAdapter(new RecyclerView.Adapter<EpHolder>() {
            @NonNull
            @Override
            public EpHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new EpHolder(ItemHongguoEpisodeBinding.inflate(
                        LayoutInflater.from(parent.getContext()), parent, false));
            }

            @Override
            public void onBindViewHolder(@NonNull EpHolder holder, int index) {
                int globalPos = positions.get(index);
                HgFeedItem ep = session.items.get(globalPos);
                TextView tv = holder.binding.hgEpNum;
                tv.setText(ep.episodeIndex > 0 ? String.valueOf(ep.episodeIndex) : String.valueOf(index + 1));
                tv.setSelected(globalPos == currentGlobalPos);
                tv.setOnClickListener(v -> {
                    dialog.dismiss();
                    if (globalPos != currentGlobalPos) onPick.accept(globalPos);
                });
            }

            @Override
            public int getItemCount() {
                return positions.size();
            }
        });
        int currentIdx = positions.indexOf(currentGlobalPos);
        if (currentIdx > 0) binding.hgEpGrid.scrollToPosition(currentIdx);

        // 半屏起步、可拉升到近全屏（SwipeCeilingLayout 的等价口径：BottomSheetBehavior 拖升）
        int half = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.55f);
        dialog.setOnShowListener(d -> {
            FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet == null) return;
            try {
                BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(sheet);
                behavior.setPeekHeight(half);
                behavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            } catch (IllegalArgumentException ignored) {
                // behavior 未关联时降级为默认高度，不崩
            }
        });
        dialog.show();
    }

    private static final class EpHolder extends RecyclerView.ViewHolder {
        final ItemHongguoEpisodeBinding binding;

        EpHolder(ItemHongguoEpisodeBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
