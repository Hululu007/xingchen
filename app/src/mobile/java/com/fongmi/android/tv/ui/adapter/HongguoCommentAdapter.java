package com.fongmi.android.tv.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.HongguoCommentApi;
import com.fongmi.android.tv.databinding.AdapterHongguoCommentBinding;
import com.fongmi.android.tv.databinding.AdapterHongguoFooterBinding;
import com.fongmi.android.tv.databinding.AdapterHongguoReplyBinding;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 红果评论列表 Adapter：评论行（头像/名字/时间/正文/评分/点赞/回复）＋页脚行
 * （加载中/失败重试）。回复以内嵌展开区呈现，展开状态由宿主（Dialog）持有，
 * 本类只负责按状态渲染——含每条评论的回复分页游标纪律在宿主侧。
 */
public class HongguoCommentAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int FOOTER_NONE = 0;
    public static final int FOOTER_LOADING = 1;
    public static final int FOOTER_ERROR = 2;

    private static final int TYPE_COMMENT = 0;
    private static final int TYPE_FOOTER = 1;

    /** 单条评论的回复展开状态（宿主持久持有，Adapter 只读渲染）。 */
    public static class ReplyBox {
        public boolean expanded;
        public boolean loading;
        public boolean failed;
        public boolean hasMore;
        public final List<HongguoCommentApi.Comment> items = new ArrayList<>();
    }

    public interface Listener {
        /** 取某条评论的回复展开状态，没有则返回 null（视为未展开）。 */
        ReplyBox replyBoxOf(String commentId);

        /** 点了「回复 N」：展开/收起由宿主处理，处理完回调 notify。 */
        void onToggleReplies(HongguoCommentApi.Comment item);

        /** 点了回复区底部的「展开更多回复」。 */
        void onMoreReplies(HongguoCommentApi.Comment item);

        /** 点了页脚的失败重试行。 */
        void onFooterRetry();
    }

    private final Listener listener;
    private final List<HongguoCommentApi.Comment> mItems = new ArrayList<>();
    private int footer = FOOTER_NONE;

    public HongguoCommentAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setItems(List<HongguoCommentApi.Comment> items) {
        mItems.clear();
        if (items != null) mItems.addAll(items);
        notifyDataSetChanged();
    }

    public void appendItems(List<HongguoCommentApi.Comment> items) {
        if (items == null || items.isEmpty()) return;
        int start = mItems.size();
        mItems.addAll(items);
        notifyItemRangeInserted(start, items.size());
    }

    public List<HongguoCommentApi.Comment> items() {
        return mItems;
    }

    public void setFooter(int state) {
        if (footer == state) return;
        footer = state;
        notifyDataSetChanged();
    }

    public int getFooter() {
        return footer;
    }

    /** 某条评论的回复状态变化后刷新该行。 */
    public void refreshComment(String commentId) {
        for (int i = 0; i < mItems.size(); i++) {
            if (mItems.get(i).id.equals(commentId)) {
                notifyItemChanged(i);
                return;
            }
        }
    }

    @Override
    public int getItemViewType(int position) {
        return position < mItems.size() ? TYPE_COMMENT : TYPE_FOOTER;
    }

    @Override
    public int getItemCount() {
        return mItems.size() + (footer == FOOTER_NONE ? 0 : 1);
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_FOOTER) return new FooterHolder(AdapterHongguoFooterBinding.inflate(inflater, parent, false));
        return new CommentHolder(AdapterHongguoCommentBinding.inflate(inflater, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof FooterHolder) {
            FooterHolder footerHolder = (FooterHolder) holder;
            boolean error = footer == FOOTER_ERROR;
            footerHolder.binding.text.setText(error ? R.string.hongguo_comment_failed : R.string.hongguo_comment_loading);
            footerHolder.binding.text.setOnClickListener(error ? v -> listener.onFooterRetry() : null);
            return;
        }
        CommentHolder commentHolder = (CommentHolder) holder;
        HongguoCommentApi.Comment item = mItems.get(position);
        AdapterHongguoCommentBinding binding = commentHolder.binding;
        Context context = binding.getRoot().getContext();
        if (!item.avatar.isEmpty()) {
            Glide.with(binding.avatar).load(item.avatar).circleCrop()
                    .placeholder(R.drawable.ic_logo).error(R.drawable.ic_logo).into(binding.avatar);
        } else {
            binding.avatar.setImageResource(R.drawable.ic_logo);
        }
        binding.author.setText(item.author);
        binding.time.setText(formatTime(item.created));
        if (item.score != null && !item.score.isEmpty()) {
            binding.score.setVisibility(View.VISIBLE);
            binding.score.setText(item.score + " 分");
        } else {
            binding.score.setVisibility(View.GONE);
        }
        if (item.replyTo != null && !item.replyTo.isEmpty()) {
            binding.content.setText(context.getString(R.string.hongguo_comment_reply_to, item.replyTo) + "：" + item.content);
        } else {
            binding.content.setText(item.content);
        }
        binding.likes.setText(item.likes > 0 ? context.getString(R.string.hongguo_comment_likes, item.likes) : "赞");
        ReplyBox box = listener.replyBoxOf(item.id);
        boolean expanded = box != null && box.expanded;
        if (item.replyCount > 0) {
            binding.replies.setVisibility(View.VISIBLE);
            binding.replies.setText(context.getString(R.string.hongguo_comment_replies) + " " + item.replyCount + (expanded ? " ▴" : " ▾"));
            binding.replies.setOnClickListener(v -> listener.onToggleReplies(item));
        } else {
            binding.replies.setVisibility(View.GONE);
            binding.replies.setOnClickListener(null);
        }
        bindRepliesBox(binding, item, box);
    }

    private void bindRepliesBox(AdapterHongguoCommentBinding binding, HongguoCommentApi.Comment item, ReplyBox box) {
        ViewGroup container = binding.repliesBox;
        container.removeAllViews();
        if (box == null || !box.expanded) {
            container.setVisibility(View.GONE);
            return;
        }
        container.setVisibility(View.VISIBLE);
        Context context = container.getContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        for (HongguoCommentApi.Comment reply : box.items) {
            AdapterHongguoReplyBinding row = AdapterHongguoReplyBinding.inflate(inflater, container, false);
            row.author.setText(reply.author);
            row.time.setText(formatTime(reply.created));
            if (reply.replyTo != null && !reply.replyTo.isEmpty()) {
                row.content.setText(context.getString(R.string.hongguo_comment_reply_to, reply.replyTo) + "：" + reply.content);
            } else {
                row.content.setText(reply.content);
            }
            row.likes.setText(reply.likes > 0 ? context.getString(R.string.hongguo_comment_likes, reply.likes) : "赞");
            container.addView(row.getRoot());
        }
        TextView foot = new TextView(context);
        foot.setTextSize(12);
        foot.setTextColor(0x80000000);
        foot.setPadding(0, 12, 0, 4);
        if (box.loading) {
            foot.setText(R.string.hongguo_comment_loading);
            container.addView(foot);
        } else if (box.failed) {
            foot.setText(R.string.hongguo_comment_failed);
            foot.setOnClickListener(v -> listener.onMoreReplies(item));
            container.addView(foot);
        } else if (box.hasMore) {
            foot.setText(R.string.hongguo_comment_more_replies);
            foot.setOnClickListener(v -> listener.onMoreReplies(item));
            container.addView(foot);
        }
    }

    static String formatTime(long epochSeconds) {
        if (epochSeconds <= 0) return "";
        Date date = new Date(epochSeconds * 1000L);
        Date now = new Date();
        String pattern = new SimpleDateFormat("yyyy", Locale.US).format(date).equals(new SimpleDateFormat("yyyy", Locale.US).format(now))
                ? "MM-dd HH:mm" : "yyyy-MM-dd";
        return new SimpleDateFormat(pattern, Locale.getDefault()).format(date);
    }

    static class CommentHolder extends RecyclerView.ViewHolder {
        final AdapterHongguoCommentBinding binding;

        CommentHolder(AdapterHongguoCommentBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    static class FooterHolder extends RecyclerView.ViewHolder {
        final AdapterHongguoFooterBinding binding;

        FooterHolder(AdapterHongguoFooterBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
