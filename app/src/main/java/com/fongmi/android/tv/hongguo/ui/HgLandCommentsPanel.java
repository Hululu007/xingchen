package com.fongmi.android.tv.hongguo.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 横屏评论面板（2026-10-31 他亲定形态，出处＝他发的官方截图
 * 113856）：右侧停靠框出、播放画面挤到左侧继续播——不是底部弹
 * 层、不是盖层。面板宽＝屏宽 40%（截图实测 0.4004，普查分册与
 * 官方包均无代码原值，待裁）。深底形态按截图：顶「N条评论」＋
 * 右上 ×、评论列表、底部浏览注脚行。
 * 行形态与行为复用竖屏评论单 HgCommentsSheet 的定版：头像 34
 * （无图色块字母兜底）＋名 13 粗＋正文 13＋脚行（赞 N · 展开回
 * 复，点脚行展开、回复缩进 44），字号行距逐项沿用，仅底色与字
 * 色按横屏深形态换。数据同源 HongguoCommentApi（episodeComments
 * ＋episodeReplies 分页），只看不发（与评论单同口径：无输入口、
 * 无发送链，底部只留「评论区仅供浏览」注脚）。
 * 竖屏评论单一行未动，两面各自成件、只共享数据层。
 */
public final class HgLandCommentsPanel {

    /** 面板宽占比（截图实测 0.4004 取 0.40，官方原值未抠到、待裁）。 */
    public static final float WIDTH_RATIO = 0.40f;

    /** 宿主回调：真实 total 回写（评论钮计数签同源）＋关闭请求。 */
    public interface Host {
        void onTotal(long total);

        void onClose();
    }

    private static final int[] AV_COLORS = {
            0xFFE63A2E, 0xFFE8A13A, 0xFF3E8E6E, 0xFF4A6FD4,
            0xFF8A5FC0, 0xFFC2507A, 0xFF2E8EA6, 0xFF7A6A53};

    private static final class Row {
        final HongguoCommentApi.Comment comment;
        final boolean reply;
        boolean expanded;

        Row(HongguoCommentApi.Comment c, boolean reply) {
            comment = c;
            this.reply = reply;
        }
    }

    private final AppCompatActivity activity;
    private final Host host;
    private final ExecutorService worker;
    private final LinearLayout root;
    private final TextView title;
    private final TextView footText;
    private final LandAdapter adapter;
    private String seriesId;
    private String vid;
    private boolean bound;
    private boolean destroyed;

    public HgLandCommentsPanel(AppCompatActivity activity, Host host) {
        this.activity = activity;
        this.host = host;
        float density = activity.getResources().getDisplayMetrics().density;
        java.util.function.IntUnaryOperator dp = v -> (int) (v * density + 0.5f);
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "hg-land-comments");
            t.setDaemon(true);
            return t;
        });

        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF1D1D20);

        // 头：标题居中「N条评论」＋右上 ×（官方截图形态）
        FrameLayout head = new FrameLayout(activity);
        title = new TextView(activity);
        title.setText("评论");
        title.setTextColor(0xFFF2F2F4);
        title.setTextSize(15);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        head.addView(title, new FrameLayout.LayoutParams(-1, -1));
        ImageView close = new ImageView(activity);
        close.setImageResource(R.drawable.ic_drama_close);
        close.setColorFilter(Color.WHITE);
        close.setPadding(dp.applyAsInt(10), dp.applyAsInt(10),
                dp.applyAsInt(10), dp.applyAsInt(10));
        close.setOnClickListener(v -> {
            if (host != null) host.onClose();
        });
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(
                dp.applyAsInt(38), dp.applyAsInt(38));
        closeLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        closeLp.rightMargin = dp.applyAsInt(6);
        head.addView(close, closeLp);
        root.addView(head, new LinearLayout.LayoutParams(-1, dp.applyAsInt(52)));
        View headLine = new View(activity);
        headLine.setBackgroundColor(0x14FFFFFF);
        root.addView(headLine, new LinearLayout.LayoutParams(-1, 1));

        // 体：评论列表（行形态沿评论单，见类头注）
        RecyclerView list = new RecyclerView(activity);
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setPadding(dp.applyAsInt(16), dp.applyAsInt(4),
                dp.applyAsInt(16), dp.applyAsInt(10));
        list.setClipToPadding(false);
        adapter = new LandAdapter(dp);
        list.setAdapter(adapter);
        list.addItemDecoration(new RecyclerView.ItemDecoration() {
            private final Paint linePaint = new Paint();

            {
                linePaint.setColor(0x14FFFFFF);
            }

            @Override
            public void onDrawOver(@NonNull Canvas c, @NonNull RecyclerView parent,
                                   @NonNull RecyclerView.State state) {
                int rows = adapter.rowsSize();
                for (int i = 0; i < parent.getChildCount(); i++) {
                    View child = parent.getChildAt(i);
                    int pos = parent.getChildAdapterPosition(child);
                    if (pos < 0 || pos >= rows - 1) continue;
                    float y = child.getBottom() - 0.5f;
                    c.drawRect(child.getLeft(), y, child.getRight(), y + 1f, linePaint);
                }
            }
        });
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm != null && dy > 0
                        && lm.findLastVisibleItemPosition() >= adapter.getItemCount() - 3) {
                    adapter.loadMore();
                }
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));

        // 脚：只看口径注脚（评论单 .s-foot 同文同义，深形态换色＋
        // 手势区让位由宿主 insets 喂入）
        View footLine = new View(activity);
        footLine.setBackgroundColor(0x14FFFFFF);
        root.addView(footLine, new LinearLayout.LayoutParams(-1, 1));
        footText = new TextView(activity);
        footText.setText("评论区仅供浏览");
        footText.setTextColor(0xFF8A8A93);
        footText.setTextSize(12.5f);
        footText.setGravity(Gravity.CENTER);
        footText.setPadding(dp.applyAsInt(16), dp.applyAsInt(11),
                dp.applyAsInt(16), dp.applyAsInt(13));
        root.addView(footText);
    }

    public View view() {
        return root;
    }

    /** 手势区让位：宿主 insets 捕获的底部系统栏高喂进来补脚行下边距。 */
    public void setBottomInset(int px) {
        float density = activity.getResources().getDisplayMetrics().density;
        int base = (int) (13 * density + 0.5f);
        footText.setPadding(footText.getPaddingLeft(), footText.getPaddingTop(),
                footText.getPaddingRight(), base + px);
    }

    public boolean isBound() {
        return bound;
    }

    /**
     * 绑到某集：与当前所绑不同才整表重拉（切集跟随、重复开面板
     * 不重拉）。计数与列表与竖屏评论单同源同法。
     */
    public void bind(String sid, String v) {
        if (destroyed || sid == null || v == null) return;
        if (bound && sid.equals(seriesId) && v.equals(vid)) return;
        bound = true;
        seriesId = sid;
        vid = v;
        title.setText("评论");
        adapter.reset(sid, v);
        adapter.loadMore();
    }

    public void destroy() {
        destroyed = true;
        worker.shutdownNow();
    }

    private static GradientDrawable ovalBg(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    /** 与评论单 CommentsAdapter 同构的分页/展开逻辑（深形态行）。 */
    private final class LandAdapter extends RecyclerView.Adapter<LandAdapter.H> {
        private final java.util.function.IntUnaryOperator dp;
        private final List<Row> rows = new ArrayList<>();
        private String sid;
        private String v;
        private String cursor;
        private boolean hasMore = true;
        private boolean loading;
        private boolean firstLoaded;
        private int generation;

        LandAdapter(java.util.function.IntUnaryOperator dp) {
            this.dp = dp;
        }

        int rowsSize() {
            return rows.size();
        }

        void reset(String s, String vv) {
            sid = s;
            v = vv;
            generation++;
            rows.clear();
            cursor = null;
            hasMore = true;
            loading = false;
            firstLoaded = false;
            notifyDataSetChanged();
        }

        void loadMore() {
            if (loading || !hasMore || destroyed || sid == null) return;
            loading = true;
            final int gen = generation;
            final String cur = cursor;
            final String s = sid;
            final String vv = v;
            worker.execute(() -> {
                HongguoCommentApi.Page page = HongguoCommentApi.episodeComments(s, vv, cur);
                activity.runOnUiThread(() -> {
                    if (destroyed || gen != generation) return;
                    loading = false;
                    firstLoaded = true;
                    if (page == null) {
                        hasMore = false;
                        notifyDataSetChanged();
                        return;
                    }
                    if (page.total > 0) {
                        // 计数回写单独隔离（与评论单同纪律）：标题与
                        // 宿主计数签哪一步抛了都不连坐入列
                        try {
                            title.setText(HongguoFeedActivity.HgTimeTextCount.format(page.total)
                                    + "条评论");
                            if (host != null) host.onTotal(page.total);
                        } catch (Throwable ignored) {
                        }
                    }
                    for (HongguoCommentApi.Comment c : page.items) rows.add(new Row(c, false));
                    cursor = page.nextCursor;
                    hasMore = page.hasMore;
                    notifyDataSetChanged();
                });
            });
        }

        private void expandReplies(Row row) {
            row.expanded = true;
            int at = rows.indexOf(row);
            if (at >= 0) notifyItemChanged(at);
            final int gen = generation;
            final String s = sid;
            final String vv = v;
            final String cid = row.comment.id;
            worker.execute(() -> {
                HongguoCommentApi.Page page = HongguoCommentApi.episodeReplies(s, vv, cid, "");
                activity.runOnUiThread(() -> {
                    if (destroyed || gen != generation || page == null) return;
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
            final TextView letter;
            final TextView name;
            final TextView text;
            final TextView meta;

            H(LinearLayout row) {
                super(row);
                FrameLayout avaBox = row.findViewWithTag("avatarBox");
                avatar = avaBox.findViewWithTag("avatar");
                letter = avaBox.findViewWithTag("letter");
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
            row.setPadding(0, dp.applyAsInt(11), 0, dp.applyAsInt(11));
            FrameLayout avaBox = new FrameLayout(activity);
            avaBox.setTag("avatarBox");
            TextView letter = new TextView(activity);
            letter.setTag("letter");
            letter.setGravity(Gravity.CENTER);
            letter.setTextColor(Color.WHITE);
            letter.setTextSize(13);
            letter.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            avaBox.addView(letter, new FrameLayout.LayoutParams(
                    dp.applyAsInt(34), dp.applyAsInt(34)));
            ImageView avatar = new ImageView(activity);
            avatar.setTag("avatar");
            avaBox.addView(avatar, new FrameLayout.LayoutParams(
                    dp.applyAsInt(34), dp.applyAsInt(34)));
            row.addView(avaBox, new LinearLayout.LayoutParams(
                    dp.applyAsInt(34), dp.applyAsInt(34)));
            LinearLayout col = new LinearLayout(activity);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1f);
            clp.leftMargin = dp.applyAsInt(10);
            TextView name = new TextView(activity);
            name.setTag("name");
            name.setTextColor(0xFFC9C9CF);
            name.setTextSize(13);
            name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            col.addView(name);
            TextView text = new TextView(activity);
            text.setTag("text");
            text.setTextColor(0xFFF2F2F4);
            text.setTextSize(13);
            text.setLineSpacing(0, 1.33f);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
            tlp.topMargin = dp.applyAsInt(2);
            col.addView(text, tlp);
            TextView meta = new TextView(activity);
            meta.setTag("meta");
            meta.setTextColor(0xFF8A8A93);
            meta.setTextSize(11.5f);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-2, -2);
            mlp.topMargin = dp.applyAsInt(4);
            col.addView(meta, mlp);
            row.addView(col, clp);
            return new H(row);
        }

        @Override
        public void onBindViewHolder(@NonNull H h, int position) {
            if (position >= rows.size()) {
                // 底部状态行（加载/空/到底），与评论单同文
                h.name.setText("");
                h.text.setTextColor(0xFF8A8A93);
                h.text.setText(!firstLoaded ? "加载中…" : (rows.isEmpty() ? "还没有评论" : ""));
                h.meta.setText("");
                h.letter.setText("");
                h.letter.setBackground(null);
                h.avatar.setImageDrawable(null);
                return;
            }
            Row row = rows.get(position);
            HongguoCommentApi.Comment c = row.comment;
            if (row.reply) {
                h.itemView.setPadding(dp.applyAsInt(44), dp.applyAsInt(8), 0, dp.applyAsInt(8));
            } else {
                h.itemView.setPadding(0, dp.applyAsInt(11), 0, dp.applyAsInt(11));
            }
            String author = c.author == null ? "" : c.author;
            h.letter.setText(author.isEmpty() ? "" : author.substring(0, 1));
            int seed = (author.isEmpty() ? 0 : author.charAt(0)) + author.length();
            h.letter.setBackground(ovalBg(AV_COLORS[seed % AV_COLORS.length]));
            if (c.avatar != null && !c.avatar.isEmpty()) {
                Glide.with(activity).load(c.avatar).circleCrop().into(h.avatar);
            } else {
                h.avatar.setImageDrawable(null);
            }
            h.name.setText(author);
            h.text.setTextColor(0xFFF2F2F4);
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
                    ? v -> expandReplies(row) : null);
        }

        @Override
        public int getItemCount() {
            return rows.size() + 1;
        }
    }
}
