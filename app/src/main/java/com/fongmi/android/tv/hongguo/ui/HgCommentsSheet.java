package com.fongmi.android.tv.hongguo.ui;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.HongguoCommentApi;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 评论单（2026-10-30 从零重建）：只看——本集评论列表＋回复展开＋
 * 翻页，没有输入框、没有发送口（纯本地口径，他定版）。数据走壳子
 * 评论栈 HongguoCommentApi（匿名读口）。
 */
public final class HgCommentsSheet {

    private HgCommentsSheet() {
    }

    private static final class Row {
        final HongguoCommentApi.Comment comment;
        final boolean reply;
        boolean expanded;

        Row(HongguoCommentApi.Comment c, boolean reply) {
            comment = c;
            this.reply = reply;
        }
    }

    public static void show(AppCompatActivity activity, String seriesId, String vid, String title) {
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        float density = activity.getResources().getDisplayMetrics().density;
        java.util.function.IntUnaryOperator dp = v -> (int) (v * density + 0.5f);

        FrameLayout root = new FrameLayout(activity);
        root.setBackground(roundBg(0xFF1C1C1E, dp.applyAsInt(18)));

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp.applyAsInt(18), dp.applyAsInt(14), dp.applyAsInt(18), dp.applyAsInt(8));
        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView headText = new TextView(activity);
        headText.setText("评论");
        headText.setTextColor(Color.WHITE);
        headText.setTextSize(16);
        headText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        head.addView(headText, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView close = new ImageView(activity);
        close.setImageResource(R.drawable.ic_drama_close);
        close.setColorFilter(0xFFAAAAAA);
        close.setPadding(dp.applyAsInt(8), dp.applyAsInt(6), dp.applyAsInt(2), dp.applyAsInt(6));
        close.setOnClickListener(v -> dialog.dismiss());
        head.addView(close, new LinearLayout.LayoutParams(dp.applyAsInt(32), dp.applyAsInt(32)));
        col.addView(head);

        RecyclerView list = new RecyclerView(activity);
        list.setLayoutManager(new LinearLayoutManager(activity));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, 0, 1f);
        llp.topMargin = dp.applyAsInt(6);
        col.addView(list, llp);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        dialog.setContentView(root);
        dialog.show();
        View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet != null) {
            sheet.setBackgroundColor(Color.TRANSPARENT);
            ViewGroup.LayoutParams slp = sheet.getLayoutParams();
            slp.height = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.78f);
            sheet.setLayoutParams(slp);
        }

        ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "hg-comments");
            t.setDaemon(true);
            return t;
        });
        dialog.setOnDismissListener(d -> worker.shutdownNow());

        CommentsAdapter adapter = new CommentsAdapter(activity, dp, worker, seriesId, vid, headText);
        list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm != null && dy > 0 && lm.findLastVisibleItemPosition() >= adapter.getItemCount() - 3) {
                    adapter.loadMore();
                }
            }
        });
        adapter.loadMore();
    }

    private static GradientDrawable roundBg(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private static final class CommentsAdapter extends RecyclerView.Adapter<CommentsAdapter.H> {
        private final AppCompatActivity activity;
        private final java.util.function.IntUnaryOperator dp;
        private final ExecutorService worker;
        private final String seriesId;
        private final String vid;
        private final TextView headText;
        private final List<Row> rows = new ArrayList<>();
        private String cursor;
        private boolean hasMore = true;
        private boolean loading;
        private boolean firstLoaded;

        CommentsAdapter(AppCompatActivity a, java.util.function.IntUnaryOperator dp,
                        ExecutorService worker, String seriesId, String vid, TextView headText) {
            activity = a;
            this.dp = dp;
            this.worker = worker;
            this.seriesId = seriesId;
            this.vid = vid;
            this.headText = headText;
        }

        void loadMore() {
            if (loading || !hasMore) return;
            loading = true;
            final String cur = cursor;
            worker.execute(() -> {
                HongguoCommentApi.Page page = HongguoCommentApi.episodeComments(seriesId, vid, cur);
                activity.runOnUiThread(() -> {
                    loading = false;
                    firstLoaded = true;
                    if (page == null) {
                        hasMore = false;
                        notifyDataSetChanged();
                        return;
                    }
                    if (page.total > 0) headText.setText("评论 " + page.total);
                    for (HongguoCommentApi.Comment c : page.items) rows.add(new Row(c, false));
                    cursor = page.nextCursor;
                    hasMore = page.hasMore;
                    notifyDataSetChanged();
                });
            });
        }

        private void expandReplies(H holder, Row row) {
            row.expanded = true;
            int at = rows.indexOf(row);
            notifyItemChanged(at);
            final String cid = row.comment.id;
            worker.execute(() -> {
                HongguoCommentApi.Page page = HongguoCommentApi.episodeReplies(seriesId, vid, cid, "");
                activity.runOnUiThread(() -> {
                    if (page == null) return;
                    int pos = rows.indexOf(row);
                    if (pos < 0) return;
                    List<Row> sub = new ArrayList<>();
                    for (HongguoCommentApi.Comment c : page.items) sub.add(new Row(c, true));
                    rows.addAll(pos + 1, sub);
                    notifyItemRangeInserted(pos + 1, sub.size());
                });
            });
        }

        class H extends RecyclerView.ViewHolder {
            final ImageView avatar;
            final TextView name;
            final TextView text;
            final TextView meta;

            H(LinearLayout row) {
                super(row);
                avatar = row.findViewWithTag("avatar");
                name = row.findViewWithTag("name");
                text = row.findViewWithTag("text");
                meta = row.findViewWithTag("meta");
            }
        }

        @NonNull
        @Override
        public H onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp.applyAsInt(10), 0, dp.applyAsInt(10));
            ImageView avatar = new ImageView(activity);
            avatar.setTag("avatar");
            row.addView(avatar, new LinearLayout.LayoutParams(dp.applyAsInt(34), dp.applyAsInt(34)));
            LinearLayout col = new LinearLayout(activity);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1f);
            clp.leftMargin = dp.applyAsInt(10);
            TextView name = new TextView(activity);
            name.setTag("name");
            name.setTextColor(0xFFB8B8BE);
            name.setTextSize(12.5f);
            col.addView(name);
            TextView text = new TextView(activity);
            text.setTag("text");
            text.setTextColor(0xFFF2F2F4);
            text.setTextSize(14);
            text.setLineSpacing(0, 1.25f);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
            tlp.topMargin = dp.applyAsInt(3);
            col.addView(text, tlp);
            TextView meta = new TextView(activity);
            meta.setTag("meta");
            meta.setTextColor(0xFF8E8E96);
            meta.setTextSize(12);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-2, -2);
            mlp.topMargin = dp.applyAsInt(5);
            col.addView(meta, mlp);
            row.addView(col, clp);
            return new H(row);
        }

        @Override
        public void onBindViewHolder(@NonNull H h, int position) {
            if (position >= rows.size()) {
                // 底部状态行（加载/空/到底）
                h.name.setText("");
                h.text.setText(!firstLoaded ? "加载中…" : (rows.isEmpty() ? "还没有评论" : ""));
                h.meta.setText("");
                h.avatar.setImageDrawable(null);
                return;
            }
            Row row = rows.get(position);
            HongguoCommentApi.Comment c = row.comment;
            if (row.reply) {
                h.itemView.setPadding(dp.applyAsInt(44), dp.applyAsInt(8), 0, dp.applyAsInt(8));
            } else {
                h.itemView.setPadding(0, dp.applyAsInt(10), 0, dp.applyAsInt(10));
            }
            if (c.avatar != null && !c.avatar.isEmpty()) {
                Glide.with(activity).load(c.avatar).circleCrop().into(h.avatar);
            } else {
                h.avatar.setImageDrawable(roundBg(0xFF3A3A3E, dp.applyAsInt(17)));
            }
            h.name.setText(c.author == null ? "" : c.author);
            String body = c.content == null ? "" : c.content;
            if (c.replyTo != null && !c.replyTo.isEmpty()) {
                body = "回复 @" + c.replyTo + "：" + body;
            }
            h.text.setText(body);
            StringBuilder meta = new StringBuilder();
            if (c.likes > 0) meta.append("赞 ").append(c.likes);
            if (!row.reply && c.replyCount > 0) {
                if (meta.length() > 0) meta.append("  ·  ");
                meta.append(row.expanded ? "已展开回复" : "展开 " + c.replyCount + " 条回复");
            }
            h.meta.setText(meta.toString());
            h.meta.setOnClickListener(!row.reply && c.replyCount > 0 && !row.expanded
                    ? v -> expandReplies(h, row) : null);
        }

        @Override
        public int getItemCount() {
            return rows.size() + 1;
        }
    }
}
