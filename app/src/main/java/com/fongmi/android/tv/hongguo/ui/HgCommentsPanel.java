package com.fongmi.android.tv.hongguo.ui;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.databinding.DialogHongguoCommentsBinding;
import com.fongmi.android.tv.databinding.ItemHongguoCommentBinding;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.model.HgComment;
import com.fongmi.android.tv.utils.ImgUtil;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongConsumer;

/**
 * 评论面板（四阶段灌实）：单集评论列表＋上滑翻页＋回复展开，全走 hongguo 包
 * 自有 {@link com.fongmi.android.tv.hongguo.api.HgCommentClient}（协议出处见
 * 该类类头；不直连规则年代的 api/HongguoCommentApi，理由同在该类类头）。
 *
 * <p>只读说明（如实）：发评论/回复的请求模型在样本里只抠到模型类
 * （classes11 saas AddCommentRequest/AddReplyRequest），短剧评论的枚举取值
 * （comment_type/group_type/commit_source 的具体组合）没在已反编译的构造点
 * 定版，按纪律不落发送，面板只读；定版后在此加输入行即可。</p>
 *
 * <p>线程口径：翻页与回复都在 OkHttp 分发线程拉取，回调里只把结果贴回主线程
 * 更新适配器，主线程不做网络与解析。评论总数经 onTotal 回报页面——右列评论
 * 计数以此为准（数据驱动，没数不亮）。</p>
 */
public final class HgCommentsPanel {

    private HgCommentsPanel() {
    }

    public static void show(Activity activity, HgFeedItem item) {
        show(activity, item, null);
    }

    public static void show(Activity activity, HgFeedItem item, LongConsumer onTotal) {
        DialogHongguoCommentsBinding binding = DialogHongguoCommentsBinding.inflate(
                LayoutInflater.from(activity));
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        dialog.setContentView(binding.getRoot());
        binding.hgCmClose.setOnClickListener(v -> dialog.dismiss());
        int half = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.6f);
        dialog.setOnShowListener(d -> {
            FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet == null) return;
            try {
                BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(sheet);
                behavior.setPeekHeight(half);
                behavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            } catch (IllegalArgumentException ignored) {
            }
        });
        dialog.show();
        new Loader(activity, binding, item, onTotal).start();
    }

    /** 面板一份数据的加载与列表状态（随面板生灭，不进会话）。 */
    private static final class Loader {
        private final Activity activity;
        private final DialogHongguoCommentsBinding binding;
        private final HgFeedItem item;
        private final LongConsumer onTotal;
        private final HgFeedSession session = HgFeedSession.get();
        private final List<HgComment> comments = new ArrayList<>();
        private final Map<String, List<HgComment>> repliesCache = new HashMap<>();
        private final Map<String, String> repliesCursor = new HashMap<>();
        private final Set<String> repliesMore = new HashSet<>();
        private final Set<String> expanded = new HashSet<>();
        private final Set<String> repliesLoading = new HashSet<>();
        private Adapter adapter;
        private String cursor = "";
        private boolean hasMore;
        private boolean loading;
        private boolean firstLoaded;

        Loader(Activity activity, DialogHongguoCommentsBinding binding, HgFeedItem item, LongConsumer onTotal) {
            this.activity = activity;
            this.binding = binding;
            this.item = item;
            this.onTotal = onTotal;
        }

        void start() {
            adapter = new Adapter();
            LinearLayoutManager lm = new LinearLayoutManager(activity);
            binding.hgCmList.setLayoutManager(lm);
            binding.hgCmList.setAdapter(adapter);
            binding.hgCmList.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                    if (dy <= 0 || loading || !hasMore) return;
                    int last = lm.findLastVisibleItemPosition();
                    if (last >= comments.size() - 3) loadNext();
                }
            });
            binding.hgCmEmpty.setText("加载中…");
            loadNext();
        }

        private boolean episodeMode() {
            return item.videoId != null && !item.videoId.isEmpty();
        }

        private void loadNext() {
            if (loading) return;
            loading = true;
            HgCallback<HgComment.Page> cb = new HgCallback<HgComment.Page>() {
                @Override
                public void onSuccess(HgComment.Page page) {
                    activity.runOnUiThread(() -> {
                        loading = false;
                        comments.addAll(page.items);
                        cursor = page.nextCursor;
                        hasMore = page.hasMore;
                        if (!firstLoaded) {
                            firstLoaded = true;
                            if (onTotal != null) onTotal.accept(page.total);
                            binding.hgCmTitle.setText(page.total > 0 ? "评论 " + page.total : "评论");
                        }
                        if (comments.isEmpty()) {
                            binding.hgCmEmpty.setVisibility(View.VISIBLE);
                            binding.hgCmEmpty.setText("暂无评论");
                            binding.hgCmList.setVisibility(View.GONE);
                        } else {
                            binding.hgCmEmpty.setVisibility(View.GONE);
                            binding.hgCmList.setVisibility(View.VISIBLE);
                            adapter.notifyDataSetChanged();
                        }
                    });
                }

                @Override
                public void onError(int httpCode, String message) {
                    activity.runOnUiThread(() -> {
                        loading = false;
                        if (comments.isEmpty()) {
                            binding.hgCmEmpty.setVisibility(View.VISIBLE);
                            binding.hgCmEmpty.setText("评论加载失败，稍后再试");
                        }
                    });
                }
            };
            if (episodeMode()) session.fqClient.episodeComments(item.seriesId, item.videoId, cursor, cb);
            else session.fqClient.seriesComments(item.seriesId, cursor, cb);
        }

        private void toggleReplies(HgComment comment) {
            String id = comment.id;
            if (expanded.contains(id)) {
                expanded.remove(id);
                adapter.notifyDataSetChanged();
                return;
            }
            expanded.add(id);
            if (repliesCache.containsKey(id)) {
                adapter.notifyDataSetChanged();
                return;
            }
            loadReplies(comment, false);
        }

        private void loadReplies(HgComment comment, boolean more) {
            String id = comment.id;
            if (!repliesLoading.add(id)) return;
            String cur = more ? repliesCursor.getOrDefault(id, "") : "";
            HgCallback<HgComment.Page> cb = new HgCallback<HgComment.Page>() {
                @Override
                public void onSuccess(HgComment.Page page) {
                    activity.runOnUiThread(() -> {
                        repliesLoading.remove(id);
                        List<HgComment> list = repliesCache.computeIfAbsent(id, k -> new ArrayList<>());
                        list.addAll(page.items);
                        repliesCursor.put(id, page.nextCursor);
                        if (page.hasMore) repliesMore.add(id);
                        else repliesMore.remove(id);
                        adapter.notifyDataSetChanged();
                    });
                }

                @Override
                public void onError(int httpCode, String message) {
                    activity.runOnUiThread(() -> {
                        repliesLoading.remove(id);
                        adapter.notifyDataSetChanged();
                    });
                }
            };
            if (episodeMode()) session.fqClient.episodeReplies(item.seriesId, item.videoId, id, cur, cb);
            else session.fqClient.seriesReplies(item.seriesId, id, cur, cb);
        }

        private final class Adapter extends RecyclerView.Adapter<Holder> {
            @NonNull
            @Override
            public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new Holder(ItemHongguoCommentBinding.inflate(
                        LayoutInflater.from(parent.getContext()), parent, false));
            }

            @Override
            public void onBindViewHolder(@NonNull Holder holder, int position) {
                HgComment c = comments.get(position);
                ItemHongguoCommentBinding b = holder.binding;
                if (c.avatar != null && !c.avatar.isEmpty()) {
                    b.hgCAvatar.setVisibility(View.VISIBLE);
                    ImgUtil.loadCircle("", c.avatar, b.hgCAvatar);
                } else {
                    b.hgCAvatar.setVisibility(View.GONE);
                }
                b.hgCAuthor.setText(c.author == null || c.author.isEmpty() ? "红果用户" : c.author);
                b.hgCContent.setText(c.content);
                StringBuilder meta = new StringBuilder();
                if (c.created > 0) meta.append(formatTime(c.created));
                if (c.likes > 0) {
                    if (meta.length() > 0) meta.append(" · ");
                    meta.append("赞 ").append(c.likes);
                }
                b.hgCMeta.setText(meta.toString());
                boolean open = expanded.contains(c.id);
                if (c.replyCount > 0) {
                    b.hgCRepliesBtn.setVisibility(View.VISIBLE);
                    b.hgCRepliesBtn.setText(open ? "收起回复" : "展开" + c.replyCount + "条回复");
                    b.hgCRepliesBtn.setOnClickListener(v -> toggleReplies(c));
                } else {
                    b.hgCRepliesBtn.setVisibility(View.GONE);
                }
                List<HgComment> replies = repliesCache.get(c.id);
                if (open && replies != null && !replies.isEmpty()) {
                    b.hgCReplies.setVisibility(View.VISIBLE);
                    StringBuilder sb = new StringBuilder();
                    for (HgComment r : replies) {
                        if (sb.length() > 0) sb.append("\n\n");
                        sb.append(r.author == null || r.author.isEmpty() ? "红果用户" : r.author);
                        if (r.replyTo != null && !r.replyTo.isEmpty()) sb.append(" 回复 ").append(r.replyTo);
                        sb.append("：").append(r.content);
                    }
                    if (repliesMore.contains(c.id)) sb.append("\n\n展开更多回复");
                    b.hgCReplies.setText(sb.toString());
                    b.hgCReplies.setOnClickListener(v -> {
                        if (repliesMore.contains(c.id)) loadReplies(c, true);
                    });
                } else if (open && repliesLoading.contains(c.id)) {
                    b.hgCReplies.setVisibility(View.VISIBLE);
                    b.hgCReplies.setText("回复加载中…");
                    b.hgCReplies.setOnClickListener(null);
                } else {
                    b.hgCReplies.setVisibility(View.GONE);
                }
            }

            @Override
            public int getItemCount() {
                return comments.size();
            }
        }

        private static final class Holder extends RecyclerView.ViewHolder {
            final ItemHongguoCommentBinding binding;

            Holder(ItemHongguoCommentBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }

    private static String formatTime(long epochSec) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(epochSec * 1000L));
        } catch (Exception e) {
            return "";
        }
    }
}
