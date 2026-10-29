package com.fongmi.android.tv.hongguo.ui;

import android.app.Dialog;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

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
 * 「系列剧」半屏单（研究账 §5.4＋行构成定版 dig-seriesdialog）：进多季
 * 系列前先选部——专辑里的每一部各列一条（大封面、剧名、分类筹行、简介、
 * 数据副行、热度、收藏/预约二态钮），点中哪部进哪部的集流。只在回包真
 * 带专辑关系（album_data_list）时才弹；各行只拼回包实证字段，引言/演员/
 * 点赞数字段无回包样本（账 U5）不编、不显。
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
            // 单高定版（dig-seriessdk §5）：官方系列单＝屏高×0.6（全屏
            // 变体×1.0 是官方 b14 配置位，自家不做变体、固定 0.6）；
            // 旧 WRAP_CONTENT 内容自适应是自定值，作废
            int sheetHeight = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.6);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, sheetHeight);
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.dimAmount = 0.45f;
            window.setAttributes(lp);
        }
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
                // 分类筹行（官方 a80 标签筹位）：数据＝category_schema 分类名，
                // 最多三枚、枚距 6dp；角标（爆剧/新剧）是另一件（vm0 图标标签
                // 位），自家无图标资产，仍拼进副行不冒充筹
                b.hgRowTags.removeAllViews();
                java.util.List<String> tags = item.getTags();
                if (tags != null && !tags.isEmpty()) {
                    b.hgRowTags.setVisibility(View.VISIBLE);
                    float density = b.hgRowTags.getResources().getDisplayMetrics().density;
                    int max = Math.min(3, tags.size());
                    for (int i = 0; i < max; i++) {
                        TextView chip = new TextView(b.hgRowTags.getContext());
                        chip.setText(tags.get(i));
                        chip.setTextSize(11);
                        chip.setTextColor(0xFF666666);
                        chip.setBackgroundResource(R.drawable.bg_hg_tag_chip);
                        chip.setPadding((int) (6 * density), (int) (2 * density),
                                (int) (6 * density), (int) (2 * density));
                        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT);
                        if (i > 0) clp.setMarginStart((int) (6 * density));
                        b.hgRowTags.addView(chip, clp);
                    }
                } else {
                    b.hgRowTags.setVisibility(View.GONE);
                }
                // 描述文本（官方行有此位，字号未抠到、12sp 近似）：回包简介
                if (item.getDesc() != null && !item.getDesc().isEmpty()) {
                    b.hgRowDesc.setVisibility(View.VISIBLE);
                    b.hgRowDesc.setText(item.getDesc());
                } else {
                    b.hgRowDesc.setVisibility(View.GONE);
                }
                StringBuilder sub = new StringBuilder();
                // 副行只拼数据段：上线文案（预约剧）→集数→完结→角标；
                // sub_title_list 文案段在解析侧另有承载，不与分类筹混用
                if (item.getDueStartText() != null && !item.getDueStartText().isEmpty()) {
                    sub.append(item.getDueStartText());
                }
                if (item.getEpisodeCount() > 0) {
                    if (sub.length() > 0) sub.append(" · ");
                    sub.append("全").append(item.getEpisodeCount()).append("集");
                }
                if (item.isFinished()) {
                    if (sub.length() > 0) sub.append(" · ");
                    sub.append("已完结");
                }
                if (item.getBadge() != null && !item.getBadge().isEmpty()) {
                    if (sub.length() > 0) sub.append(" · ");
                    sub.append(item.getBadge());
                }
                b.hgRowSub.setText(sub.toString());
                b.hgRowSub.setVisibility(sub.length() > 0 ? View.VISIBLE : View.GONE);
                if (item.getRecText() != null && !item.getRecText().isEmpty()) {
                    b.hgRowHeat.setVisibility(View.VISIBLE);
                    b.hgRowHeat.setText(item.getRecText());
                } else {
                    b.hgRowHeat.setVisibility(View.GONE);
                }
                bindAction(b, item, interactions);
                b.hgRowAction.setOnClickListener(v -> {
                    // 二态钮走互动真口子（只认 seriesId）：预约剧走预约、
                    // 其余走收藏；点击前先过登录门（S19，照播放页 onAction
                    // 门法：未登录拉壳子登录、不落状态——互动层内并无登录
                    // 处理，门只能由调用方守），过门后按本地镜像重绑
                    if (interactions.requiresLogin()) {
                        boolean delivered = com.fongmi.android.tv.login.LoginManager
                                .loginHongguoDelivered();
                        if (!delivered) {
                            android.widget.Toast.makeText(activity,
                                    "登录界面暂时打不开，稍后再试",
                                    android.widget.Toast.LENGTH_SHORT).show();
                        }
                        return;
                    }
                    if (item.hasAppointment()) interactions.toggleReserve(item.getSeriesId());
                    else interactions.toggleFavorite(item.getSeriesId());
                    bindAction(b, item, interactions);
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

    /**
     * 行尾二态钮（官方定版：收藏/已收藏与预约/已预约两分支）：带预约面
     * （hasAppointment）的行是预约钮、其余是收藏钮；态读互动层本地镜像
     * （与右列/剧条同源）。钮面样式借作者页关注钮两态（橙渐变/浅底深字，
     * 官方 KMP 钮底色未抠到，标近似）。
     */
    private static void bindAction(ItemHongguoSeriesRowBinding b, HgSeriesItem item,
                                   HgInteractions interactions) {
        boolean appt = item.hasAppointment();
        boolean on = appt ? interactions.isReserved(item.getSeriesId())
                : interactions.isFavorite(item.getSeriesId());
        b.hgRowAction.setText(appt ? (on ? "已预约" : "预约") : (on ? "已收藏" : "收藏"));
        b.hgRowAction.setBackgroundResource(on
                ? R.drawable.bg_hg_followed_btn : R.drawable.bg_hg_follow_btn);
        b.hgRowAction.setTextColor(on ? 0xFF333333 : 0xFFFFFFFF);
    }

    private static final class RowHolder extends RecyclerView.ViewHolder {
        final ItemHongguoSeriesRowBinding binding;

        RowHolder(ItemHongguoSeriesRowBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
