package com.fongmi.android.tv.hongguo.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogHongguoLandEpisodesBinding;
import com.fongmi.android.tv.databinding.ItemHongguoEpisodeBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * 横屏页选集侧抽屉（官方 caa：右锚 244dp、关闭图标＋「选集」标题＋列表）：
 * 集格与竖屏选集单同布局（item_hongguo_episode）但深浅分开：板底官方
 * 定版 #FF161616 深板，文字色/集格底走本类深底口径（COLOR_* 白色阶＋
 * bg_hg_ep_cell_land），不许拿竖屏浅底的黑字系与 bg_hg_ep_cell 串用。
 * 数据同走 session.items 的同剧位置表，不复制解析。列数按抽屉宽适配
 * 3 列（官方抽屉内列数未抠到）。
 */
public final class HgEpisodeDrawer {

    private static final int COLUMNS = 3;

    // 深板（#FF161616）语境文字色：本类即横屏抽屉专用、与竖屏选集单的
    // 浅底黑字系（HgEpisodeSheet 同名常量）分开，不许串。当前集橙与浅底
    // 同值（品牌色跨语境一致）；其余三档官方 caa 无集格色实物（列表由
    // 适配器绑定、无布局文件），按白色阶推断：未看纯白、已看白 60%、
    // 不可播白 30%，与 #161616 对比依次约 15:1／6.5:1／3:1（不可播本
    // 就是弱化档）。
    private static final int COLOR_UNWATCHED = 0xFFFFFFFF;
    private static final int COLOR_WATCHED = 0x99FFFFFF;
    private static final int COLOR_UNPLAYABLE = 0x4DFFFFFF;
    private static final int COLOR_CURRENT = 0xFFFA7705;

    private HgEpisodeDrawer() {
    }

    public static void show(Activity activity, HgFeedSession session, int currentGlobalPos,
                            IntConsumer onPick) {
        if (currentGlobalPos < 0 || currentGlobalPos >= session.items.size()) return;
        HgFeedItem current = session.items.get(currentGlobalPos);
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < session.items.size(); i++) {
            HgFeedItem it = session.items.get(i);
            if (it.seriesId != null && it.seriesId.equals(current.seriesId) && !it.seriesHead) {
                positions.add(i);
            }
        }
        positions.sort((a, b) -> Integer.compare(
                session.items.get(a).episodeIndex, session.items.get(b).episodeIndex));
        if (positions.isEmpty()) positions.add(currentGlobalPos);

        DialogHongguoLandEpisodesBinding binding = DialogHongguoLandEpisodesBinding.inflate(
                LayoutInflater.from(activity));
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(binding.getRoot());
        Window window = dialog.getWindow();
        if (window != null) {
            window.setGravity(Gravity.END);
            int width = (int) (244 * activity.getResources().getDisplayMetrics().density);
            window.setLayout(width, WindowManager.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.dimAmount = 0.45f;
            window.setAttributes(lp);
        }
        binding.hgLdClose.setOnClickListener(v -> dialog.dismiss());

        HgStore.Progress progress = current.seriesId == null
                ? null : HgStore.progressOf(current.seriesId);
        binding.hgLdGrid.setLayoutManager(new GridLayoutManager(activity, COLUMNS));
        binding.hgLdGrid.setAdapter(new RecyclerView.Adapter<EpHolder>() {
            @NonNull
            @Override
            public EpHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                EpHolder holder = new EpHolder(ItemHongguoEpisodeBinding.inflate(
                        LayoutInflater.from(parent.getContext()), parent, false));
                // 集格底换横屏专用深底版（item 布局默认是竖屏浅底 bg_hg_ep_cell）
                holder.binding.hgEpNum.setBackgroundResource(R.drawable.bg_hg_ep_cell_land);
                return holder;
            }

            @Override
            public void onBindViewHolder(@NonNull EpHolder h, int index) {
                int globalPos = positions.get(index);
                HgFeedItem ep = session.items.get(globalPos);
                TextView tv = h.binding.hgEpNum;
                tv.setText(ep.episodeIndex > 0 ? String.valueOf(ep.episodeIndex)
                        : String.valueOf(index + 1));
                boolean isCurrent = globalPos == currentGlobalPos;
                tv.setSelected(isCurrent);
                boolean watched = progress != null && ep.episodeIndex > 0
                        && (ep.episodeIndex < progress.episodeIndex
                        || (ep.episodeIndex == progress.episodeIndex && progress.positionMs > 0));
                if (isCurrent) {
                    tv.setTextColor(COLOR_CURRENT);
                    tv.setTypeface(null, Typeface.BOLD);
                } else if (ep.videoId == null) {
                    tv.setTextColor(COLOR_UNPLAYABLE);
                    tv.setTypeface(null, Typeface.NORMAL);
                } else if (watched) {
                    tv.setTextColor(COLOR_WATCHED);
                    tv.setTypeface(null, Typeface.NORMAL);
                } else {
                    tv.setTextColor(COLOR_UNWATCHED);
                    tv.setTypeface(null, Typeface.NORMAL);
                }
                h.binding.hgEpPlaying.setVisibility(isCurrent ? View.VISIBLE : View.GONE);
                h.itemView.setOnClickListener(v -> {
                    dialog.dismiss();
                    if (globalPos != currentGlobalPos) onPick.accept(globalPos);
                });
            }

            @Override
            public int getItemCount() {
                return positions.size();
            }
        });
        int curIdx = positions.indexOf(currentGlobalPos);
        if (curIdx > 0) binding.hgLdGrid.post(() -> binding.hgLdGrid.scrollToPosition(curIdx));
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
