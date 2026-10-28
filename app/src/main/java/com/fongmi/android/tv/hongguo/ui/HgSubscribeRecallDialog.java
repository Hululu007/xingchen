package com.fongmi.android.tv.hongguo.ui;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.databinding.DialogHongguoSubscribeRecallBinding;
import com.fongmi.android.tv.databinding.ItemHongguoRecallBinding;
import com.fongmi.android.tv.hongguo.model.HgSeriesItem;
import com.fongmi.android.tv.utils.ImgUtil;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.List;

/**
 * 预约上线召回弹窗（官方 SubscribeRecallDialog 的自家等价）：官方由
 * 浮层任务链（show_subscribe_recall_dialog）在刷剧面弹出、数据是服务
 * 端按新上线下发的；咱家无浮层任务链，进刷剧面时自拉预约已上线档、
 * 与本地已提示集差集后由 HongguoFeedActivity 调本件弹（判定在
 * HgUpdateLogic、已提示集在 HgStore）。
 *
 * <p>构成照官方：底部单、标题按条数三选一的咱家收敛（1 部＝「预约剧
 * 已上线」，多部＝「%d 部预约剧已上线」）、条目＝封面＋标题＋集数副行
 * （点条目进剧）、底部链接「去「我的-预约」查看所有已预约剧」跳我的
 * 预约面。官方条目上的封面收藏角标与底部批量收藏钮不在本批口径内，
 * 不摆。链接底距叠系统导航区真实高度（手势让位，他立的规矩）。</p>
 */
public final class HgSubscribeRecallDialog {

    public interface Listener {
        void onOpenSeries(HgSeriesItem item);

        void onOpenReservations();
    }

    /** 列表限高行数：超过则固定四行高、余下滚动（官方同有列表最大高约束）。 */
    private static final int MAX_ROWS = 4;
    /** 单行高＝封面 62dp＋上下 padding 10dp×2。 */
    private static final int ROW_DP = 82;

    private HgSubscribeRecallDialog() {
    }

    public static void show(Activity activity, List<HgSeriesItem> items, Listener listener) {
        if (activity == null || items == null || items.isEmpty()) return;
        DialogHongguoSubscribeRecallBinding binding =
                DialogHongguoSubscribeRecallBinding.inflate(LayoutInflater.from(activity));
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        dialog.setContentView(binding.getRoot());
        binding.hgRecallTitle.setText(items.size() > 1
                ? String.format("%d 部预约剧已上线", items.size())
                : "预约剧已上线");
        binding.hgRecallList.setLayoutManager(new LinearLayoutManager(activity));
        binding.hgRecallList.setAdapter(new RecallAdapter(items, item -> {
            dialog.dismiss();
            if (listener != null) listener.onOpenSeries(item);
        }));
        if (items.size() > MAX_ROWS) {
            ViewGroup.LayoutParams lp = binding.hgRecallList.getLayoutParams();
            lp.height = Math.round(ROW_DP * MAX_ROWS
                    * activity.getResources().getDisplayMetrics().density);
            binding.hgRecallList.setLayoutParams(lp);
        }
        binding.hgRecallLink.setOnClickListener(v -> {
            dialog.dismiss();
            if (listener != null) listener.onOpenReservations();
        });
        // 链接底距＝原 18dp＋导航区真实高度：单体贴屏底，不让位会被
        // 手势横杠压住、点击被系统手势吃掉（他立的全应用规矩）
        binding.getRoot().post(() -> {
            WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(binding.getRoot());
            if (insets == null) return;
            int nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            if (nav <= 0) return;
            binding.hgRecallLink.setPadding(binding.hgRecallLink.getPaddingLeft(),
                    binding.hgRecallLink.getPaddingTop(),
                    binding.hgRecallLink.getPaddingRight(),
                    Math.round(18 * activity.getResources().getDisplayMetrics().density) + nav);
        });
        dialog.show();
    }

    /** 集数副行：完结＝「全X集」、未完结＝「更新至X集」；集数未知回落上线文案，再无则空。 */
    private static String subText(HgSeriesItem item) {
        int count = item.getEpisodeCount();
        if (count > 0) return item.isFinished() ? "全" + count + "集" : "更新至" + count + "集";
        String due = item.getDueStartText();
        return due == null ? "" : due;
    }

    private interface OnPick {
        void onPick(HgSeriesItem item);
    }

    private static final class RecallAdapter extends RecyclerView.Adapter<RecallAdapter.Holder> {

        private final List<HgSeriesItem> items;
        private final OnPick onPick;

        RecallAdapter(List<HgSeriesItem> items, OnPick onPick) {
            this.items = items;
            this.onPick = onPick;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemHongguoRecallBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            HgSeriesItem item = items.get(position);
            holder.binding.hgRecallItemTitle.setText(item.getTitle() == null ? "" : item.getTitle());
            holder.binding.hgRecallItemSub.setText(subText(item));
            ImgUtil.load(item.getTitle() == null ? "" : item.getTitle(),
                    item.getCoverUrl(), holder.binding.hgRecallCover);
            holder.binding.hgRecallDivider.setVisibility(
                    position == items.size() - 1 ? View.GONE : View.VISIBLE);
            holder.binding.getRoot().setOnClickListener(v -> onPick.onPick(item));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        private static final class Holder extends RecyclerView.ViewHolder {
            final ItemHongguoRecallBinding binding;

            Holder(ItemHongguoRecallBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}
