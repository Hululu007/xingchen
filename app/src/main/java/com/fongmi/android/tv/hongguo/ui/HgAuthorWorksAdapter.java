package com.fongmi.android.tv.hongguo.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.databinding.ItemHongguoAuthorReviewBinding;
import com.fongmi.android.tv.databinding.ItemHongguoAuthorWorkBinding;
import com.fongmi.android.tv.hongguo.HgFormat;
import com.fongmi.android.tv.hongguo.model.HgAuthorWork;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 红果作者页列表适配器（作品/收藏/点赞三签共用）：KIND_VIDEO/POST 走双列
 * 卡（item_hongguo_author_work），KIND_COMMENT（剧评）走整宽行
 * （item_hongguo_author_review，星级＝expand.score 十分制折五星）。
 */
public class HgAuthorWorksAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface Listener {
        void onWorkClick(HgAuthorWork work);
    }

    private static final int TYPE_CARD = 0;
    private static final int TYPE_REVIEW = 1;

    private final List<HgAuthorWork> items = new ArrayList<>();
    private final Listener listener;

    public HgAuthorWorksAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setItems(List<HgAuthorWork> list) {
        items.clear();
        if (list != null) {
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            for (HgAuthorWork w : list) if (w != null && keep(seen, w)) items.add(w);
        }
        notifyDataSetChanged();
    }

    public void addItems(List<HgAuthorWork> list) {
        if (list == null || list.isEmpty()) return;
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (HgAuthorWork w : items) if (w != null && w.id != null && !w.id.isEmpty()) {
            seen.add(w.kind + ":" + w.id);
        }
        int at = items.size();
        int added = 0;
        for (HgAuthorWork w : list) {
            if (w != null && keep(seen, w)) {
                items.add(w);
                added++;
            }
        }
        if (added > 0) notifyItemRangeInserted(at, added);
    }

    /**
     * 同签内去重（2026-10-29 去重专项）：同一条内容（kind＋id）只留首现，
     * 防服务端分页重叠时同一 post 在同页签摆两遍。id 为空的条目不参与
     * 去重（无从判同）；同剧多帖是不同 id、照常全摆，不在此列。
     */
    private static boolean keep(java.util.HashSet<String> seen, HgAuthorWork w) {
        if (w.id == null || w.id.isEmpty()) return true;
        return seen.add(w.kind + ":" + w.id);
    }

    public int size() {
        return items.size();
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).kind == HgAuthorWork.KIND_COMMENT ? TYPE_REVIEW : TYPE_CARD;
    }

    /** 剧评行占满两列，卡占一列（GridLayoutManager 的 SpanSizeLookup 用）。 */
    public int spanOf(int position) {
        return getItemViewType(position) == TYPE_REVIEW ? 2 : 1;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_REVIEW) {
            return new ReviewHolder(ItemHongguoAuthorReviewBinding.inflate(inflater, parent, false));
        }
        return new CardHolder(ItemHongguoAuthorWorkBinding.inflate(inflater, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        HgAuthorWork w = items.get(position);
        if (holder instanceof ReviewHolder) bindReview(((ReviewHolder) holder).binding, w);
        else bindCard(((CardHolder) holder).binding, w);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onWorkClick(w);
        });
    }

    private void bindCard(ItemHongguoAuthorWorkBinding b, HgAuthorWork w) {
        ImgUtil.load(w.title, w.cover, b.hgAwCover);
        String overlay = null;
        if (!w.subTitles.isEmpty()) overlay = String.join(" · ", w.subTitles);
        else if (w.episodeCount > 0) overlay = "全" + w.episodeCount + "集";
        else if (w.playCount > 0) overlay = HgFormat.countLine(w.playCount) + "播放";
        if (overlay != null && !overlay.isEmpty()) {
            b.hgAwOverlay.setVisibility(View.VISIBLE);
            b.hgAwOverlay.setText(overlay);
        } else {
            b.hgAwOverlay.setVisibility(View.GONE);
        }
        b.hgAwTitle.setText(w.title);
        if (!w.authorName.isEmpty()) {
            b.hgAwAvatar.setVisibility(View.VISIBLE);
            b.hgAwAuthor.setVisibility(View.VISIBLE);
            ImgUtil.loadCircle(w.authorName, w.authorAvatar, b.hgAwAvatar);
            b.hgAwAuthor.setText(w.authorName);
        } else {
            b.hgAwAvatar.setVisibility(View.GONE);
            b.hgAwAuthor.setVisibility(View.GONE);
        }
        // 点赞段＝描边心图标＋数（实拍 113562）；无 countable 时照实拍摆「点赞」标签
        b.hgAwDigg.setText(w.diggCount > 0 ? HgFormat.countLine(w.diggCount) : "点赞");
    }

    private void bindReview(ItemHongguoAuthorReviewBinding b, HgAuthorWork w) {
        if (!w.authorAvatar.isEmpty()) {
            b.hgArAvatar.setVisibility(View.VISIBLE);
            ImgUtil.loadCircle(w.authorName, w.authorAvatar, b.hgArAvatar);
        } else {
            b.hgArAvatar.setVisibility(View.GONE);
        }
        b.hgArAuthor.setText(w.authorName.isEmpty() ? "红果用户" : w.authorName);
        if (w.score >= 0) {
            b.hgArStars.setVisibility(View.VISIBLE);
            paintStars(b.hgArStars, w.score);
        } else {
            b.hgArStars.setVisibility(View.GONE);
        }
        b.hgArContent.setText(w.title);
        b.hgArDiggBox.setVisibility(w.diggCount > 0 ? View.VISIBLE : View.GONE);
        b.hgArDigg.setText(w.diggCount > 0 ? HgFormat.countLine(w.diggCount) : "");
    }

    /** 十分制折五星（满星橘、空星灰，真矢量星取代旧字形星串）。 */
    private static void paintStars(LinearLayout row, int scoreTen) {
        int filled = Math.round(scoreTen / 2f);
        if (filled < 0) filled = 0;
        if (filled > 5) filled = 5;
        for (int i = 0; i < row.getChildCount(); i++) {
            ((ImageView) row.getChildAt(i)).setImageResource(i < filled
                    ? com.fongmi.android.tv.R.drawable.ic_hg_author_star_on
                    : com.fongmi.android.tv.R.drawable.ic_hg_author_star_off);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private static final class CardHolder extends RecyclerView.ViewHolder {
        final ItemHongguoAuthorWorkBinding binding;

        CardHolder(ItemHongguoAuthorWorkBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    private static final class ReviewHolder extends RecyclerView.ViewHolder {
        final ItemHongguoAuthorReviewBinding binding;

        ReviewHolder(ItemHongguoAuthorReviewBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
