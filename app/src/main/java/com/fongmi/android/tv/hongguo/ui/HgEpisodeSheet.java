package com.fongmi.android.tv.hongguo.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogHongguoEpisodesBinding;
import com.fongmi.android.tv.databinding.ItemHongguoEpisodeBinding;
import com.fongmi.android.tv.databinding.ItemHongguoEpisodePageBinding;
import com.fongmi.android.tv.hongguo.model.HgSeriesDetail;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * 选集弹层（官方口径）：半屏底部层（单高＝屏高 50%、可拉升），顶部小横把、
 * 无关闭钮；集格 6 列、每页 30 集横向分页；当前集＝橙字 #FFFA7705＋粗体＋
 * 底 #1AFA7705。演员区与完结标记只认详情真字段（celebrities/series_status），
 * 没数据不显。点集回调全局下标由播放页跳播。剧头尚未展开时只列已知集。
 */
public final class HgEpisodeSheet {

    /** 每页集数（官方：手机 6 列×5 行＝30 集一页）。 */
    private static final int PER_PAGE = 30;
    private static final int COLUMNS = 6;
    /** 一行格高：格 46dp＋上下 margin 各 4dp。 */
    private static final int ROW_DP = 54;

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

        // 完结标记：详情口径（没下发默认完结）或条目下发值＝1，任一成立才显
        boolean finished = (detail != null && detail.isFinished())
                || (current.seriesStatus != null && current.seriesStatus == 1);
        binding.hgEpStatus.setVisibility(finished ? View.VISIBLE : View.GONE);
        bindActors(activity, binding, detail);

        int pageCount = (positions.size() + PER_PAGE - 1) / PER_PAGE;
        binding.hgEpPager.setAdapter(new RecyclerView.Adapter<PageHolder>() {
            @NonNull
            @Override
            public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new PageHolder(ItemHongguoEpisodePageBinding.inflate(
                        LayoutInflater.from(parent.getContext()), parent, false));
            }

            @Override
            public void onBindViewHolder(@NonNull PageHolder holder, int page) {
                int from = page * PER_PAGE;
                int to = Math.min(positions.size(), from + PER_PAGE);
                List<Integer> slice = positions.subList(from, to);
                RecyclerView grid = holder.binding.hgEpPageGrid;
                grid.setLayoutManager(new GridLayoutManager(activity, COLUMNS));
                grid.setNestedScrollingEnabled(false);
                grid.setAdapter(new RecyclerView.Adapter<EpHolder>() {
                    @NonNull
                    @Override
                    public EpHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                        return new EpHolder(ItemHongguoEpisodeBinding.inflate(
                                LayoutInflater.from(parent.getContext()), parent, false));
                    }

                    @Override
                    public void onBindViewHolder(@NonNull EpHolder h, int index) {
                        int globalPos = slice.get(index);
                        HgFeedItem ep = session.items.get(globalPos);
                        TextView tv = h.binding.hgEpNum;
                        tv.setText(ep.episodeIndex > 0 ? String.valueOf(ep.episodeIndex) : String.valueOf(from + index + 1));
                        boolean isCurrent = globalPos == currentGlobalPos;
                        tv.setSelected(isCurrent);
                        tv.setTextColor(isCurrent ? 0xFFFA7705 : 0xFFFFFFFF);
                        tv.setTypeface(null, isCurrent ? Typeface.BOLD : Typeface.NORMAL);
                        tv.setOnClickListener(v -> {
                            dialog.dismiss();
                            if (globalPos != currentGlobalPos) onPick.accept(globalPos);
                        });
                    }

                    @Override
                    public int getItemCount() {
                        return slice.size();
                    }
                });
            }

            @Override
            public int getItemCount() {
                return pageCount;
            }
        });
        int currentIdx = positions.indexOf(currentGlobalPos);
        if (currentIdx > 0) binding.hgEpPager.setCurrentItem(currentIdx / PER_PAGE, false);
        // 分页器高＝最满页的行数×行距（页再多也只有 5 行）
        int rows = (Math.min(positions.size(), PER_PAGE) + COLUMNS - 1) / COLUMNS;
        ViewGroup.LayoutParams plp = binding.hgEpPager.getLayoutParams();
        plp.height = (int) (rows * ROW_DP * activity.getResources().getDisplayMetrics().density);
        binding.hgEpPager.setLayoutParams(plp);

        // 半屏起步（官方单高＝屏高 50%）、可拉升到近全屏
        int half = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.5f);
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

    /**
     * 演员区（官方 ActorDescriptionLayout 口径）：筹最多取前 2 个演员，
     * 圆角 4dp、padding 6/2/6/2、字 12sp；文案只拼真字段
     * （昵称＋sub_title 饰演行，缺 sub_title 时用 role_name 拼「饰 X」）。
     * 详情没带演员就不显，不编。
     */
    private static void bindActors(Activity activity, DialogHongguoEpisodesBinding binding,
                                   HgSeriesDetail detail) {
        if (detail == null || detail.getCelebrities().isEmpty()) {
            binding.hgEpActors.setVisibility(View.GONE);
            return;
        }
        float density = activity.getResources().getDisplayMetrics().density;
        int shown = 0;
        for (HgSeriesDetail.Celebrity c : detail.getCelebrities()) {
            if (shown >= 2) break;
            String text = c.getNickname() == null ? "" : c.getNickname();
            if (c.getSubTitle() != null && !c.getSubTitle().isEmpty()) {
                text = text.isEmpty() ? c.getSubTitle() : text + " " + c.getSubTitle();
            } else if (c.getRoleName() != null && !c.getRoleName().isEmpty()) {
                text = text.isEmpty() ? c.getRoleName() : text + " 饰 " + c.getRoleName();
            }
            if (text.isEmpty()) continue;
            TextView chip = new TextView(activity);
            chip.setText(text);
            chip.setTextSize(12);
            chip.setTextColor(0xFFFFFFFF);
            chip.setBackgroundResource(R.drawable.bg_hg_actor_chip);
            chip.setPadding((int) (6 * density), (int) (2 * density),
                    (int) (6 * density), (int) (2 * density));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (shown > 0) lp.setMarginStart((int) (6 * density));
            chip.setLayoutParams(lp);
            binding.hgEpActors.addView(chip);
            shown++;
        }
        binding.hgEpActors.setVisibility(shown > 0 ? View.VISIBLE : View.GONE);
    }

    private static final class PageHolder extends RecyclerView.ViewHolder {
        final ItemHongguoEpisodePageBinding binding;

        PageHolder(ItemHongguoEpisodePageBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    private static final class EpHolder extends RecyclerView.ViewHolder {
        final ItemHongguoEpisodeBinding binding;

        EpHolder(ItemHongguoEpisodeBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
