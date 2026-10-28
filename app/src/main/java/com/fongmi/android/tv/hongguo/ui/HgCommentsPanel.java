package com.fongmi.android.tv.hongguo.ui;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.databinding.DialogHongguoCommentEditorBinding;
import com.fongmi.android.tv.databinding.DialogHongguoCommentsBinding;
import com.fongmi.android.tv.databinding.ItemHongguoCommentBinding;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.api.HgLoginAuthProvider;
import com.fongmi.android.tv.hongguo.model.HgComment;
import com.fongmi.android.tv.login.LoginManager;
import com.fongmi.android.tv.utils.ImgUtil;
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
import java.util.function.LongSupplier;

/**
 * 评论面板（四阶段灌实）：单集评论列表＋上滑翻页＋回复展开，全走 hongguo 包
 * 自有 {@link com.fongmi.android.tv.hongguo.api.HgCommentClient}（协议出处见
 * 该类类头；不直连规则年代的 api/HongguoCommentApi，理由同在该类类头）。
 *
 * <p>发评论（单集）：请求档位已由续挖定版（dig-search2-20261028.md §7：
 * commit_source 3/group_type 30/data_type 4/comment_type 不填），面板底部
 * 假输入条点开编辑层发送，成功后整表刷新；整剧剧评（评分帖）是另一条链，
 * 不在此面发送，剧评面不显输入条。回复发送（commit_source 9）未接。</p>
 *
 * <p>线程口径：翻页与回复都在 OkHttp 分发线程拉取，回调里只把结果贴回主线程
 * 更新适配器，主线程不做网络与解析。评论总数经 onTotal 回报页面——右列评论
 * 计数以此为准（数据驱动，没数不亮）。</p>
 */
public final class HgCommentsPanel {

    private HgCommentsPanel() {
    }

    // ---------- 第一页进程内缓存（评论打开慢对策，定根 comment-open-slow-dig） ----------
    // 接口首包固有约 1.4–1.9s 且宿主是独占冷域名，零缓存时每次打开全价重付。
    // 按 seriesId+vid 缓存第一页 45 秒（报告建议区间 30–60 秒取中值）：
    // 重开先显缓存、后台静默刷新替换；当前播放条目空闲期预拉一页填缓存
    // （该接口已实证同档案连发无恙，预拉每条目只发一次、失败不重试）。

    private static final long FIRST_PAGE_TTL_MS = 45_000;

    private static final class FirstPage {
        final List<HgComment> items;
        final long total;
        final String nextCursor;
        final boolean hasMore;
        final long savedMs;

        FirstPage(HgComment.Page page) {
            this.items = new ArrayList<>(page.items);
            this.total = page.total;
            this.nextCursor = page.nextCursor;
            this.hasMore = page.hasMore;
            this.savedMs = System.currentTimeMillis();
        }
    }

    private static final Map<String, FirstPage> FIRST_PAGE_CACHE = new HashMap<>();
    private static final Set<String> PREFETCHING = new HashSet<>();

    private static String cacheKey(HgFeedItem item) {
        boolean ep = item.videoId != null && !item.videoId.isEmpty();
        return item.seriesId + "|" + (ep ? item.videoId : "series");
    }

    private static FirstPage cacheGet(String key) {
        synchronized (FIRST_PAGE_CACHE) {
            FirstPage hit = FIRST_PAGE_CACHE.get(key);
            if (hit == null) return null;
            if (System.currentTimeMillis() - hit.savedMs > FIRST_PAGE_TTL_MS) {
                FIRST_PAGE_CACHE.remove(key);
                return null;
            }
            return hit;
        }
    }

    private static void cachePut(String key, HgComment.Page page) {
        if (page == null || page.items == null) return;
        synchronized (FIRST_PAGE_CACHE) {
            FIRST_PAGE_CACHE.put(key, new FirstPage(page));
        }
    }

    /** 当前播放条目空闲期预拉评论第一页（只一页、每条目一次、在飞/有新鲜缓存不发）。 */
    public static void prefetchFirstPage(HgFeedItem item) {
        if (item == null || item.seriesId == null) return;
        boolean ep = item.videoId != null && !item.videoId.isEmpty();
        if (ep && (item.commentCount == null || item.commentCount.trim().isEmpty()
                || "0".equals(item.commentCount.trim()))) return;
        String key = cacheKey(item);
        if (cacheGet(key) != null) return;
        synchronized (FIRST_PAGE_CACHE) {
            if (!PREFETCHING.add(key)) return;
        }
        HgCallback<HgComment.Page> cb = new HgCallback<HgComment.Page>() {
            @Override
            public void onSuccess(HgComment.Page page) {
                cachePut(key, page);
                synchronized (FIRST_PAGE_CACHE) {
                    PREFETCHING.remove(key);
                }
            }

            @Override
            public void onError(int httpCode, String message) {
                synchronized (FIRST_PAGE_CACHE) {
                    PREFETCHING.remove(key);
                }
            }
        };
        HgFeedSession session = HgFeedSession.get();
        if (ep) session.fqClient.episodeComments(item.seriesId, item.videoId, "", cb);
        else session.fqClient.seriesComments(item.seriesId, "", cb);
    }

    public static void show(Activity activity, HgFeedItem item) {
        show(activity, item, null, null);
    }

    public static void show(Activity activity, HgFeedItem item, LongConsumer onTotal) {
        show(activity, item, onTotal, null);
    }

    /**
     * @param positionSupplier 发表时取当前播放进度（毫秒）喂 business.offset；
     *                         不给时按 0。评论面板自身不持有播放器，由宿主喂。
     */
    public static void show(Activity activity, HgFeedItem item, LongConsumer onTotal,
                            LongSupplier positionSupplier) {
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
        Loader loader = new Loader(activity, binding, item, onTotal);
        boolean episode = item.videoId != null && !item.videoId.isEmpty();
        binding.hgCmInputBar.setVisibility(episode ? View.VISIBLE : View.GONE);
        if (episode) {
            binding.hgCmInputBar.setOnClickListener(v -> {
                // 未登录先拉壳子统一登录（与互动四件同口径），不开编辑层
                if (!HgLoginAuthProvider.isLoggedIn()) {
                    if (!LoginManager.loginHongguoDelivered()) {
                        android.widget.Toast.makeText(activity,
                                "登录界面暂时打不开，稍后再试",
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                showCommentEditor(activity, loader, item, positionSupplier);
            });
        }
        dialog.show();
        loader.start();
    }

    /**
     * 发评论编辑层（自家小 Dialog，深底照红果面口径；官方框体是社区
     * Compose 编辑器、无经典布局可抄）：构成照发弹幕编辑层——空文本发表
     * 不可点、发送中禁用防连点、失败不关层提示再试；成功关层、提示已发表
     * 并整表刷新（新评论由服务端列表回带，不在本地编造一条）。
     */
    private static void showCommentEditor(Activity activity, Loader loader, HgFeedItem item,
                                          LongSupplier positionSupplier) {
        DialogHongguoCommentEditorBinding eb = DialogHongguoCommentEditorBinding.inflate(
                LayoutInflater.from(activity));
        android.app.Dialog dialog = new android.app.Dialog(activity);
        dialog.setContentView(eb.getRoot());
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT));
            int w = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.85f);
            dialog.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        final boolean[] sending = {false};
        android.text.TextWatcher watcher = new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                boolean ready = s != null && !s.toString().trim().isEmpty() && !sending[0];
                eb.hgCePublish.setEnabled(ready);
                eb.hgCePublish.setAlpha(ready ? 1f : 0.45f);
            }
        };
        eb.hgCeInput.addTextChangedListener(watcher);
        eb.hgCeCancel.setOnClickListener(v -> dialog.dismiss());
        eb.hgCePublish.setOnClickListener(v -> {
            if (sending[0]) return;
            String text = eb.hgCeInput.getText() == null ? "" : eb.hgCeInput.getText().toString().trim();
            if (text.isEmpty()) return;
            sending[0] = true;
            eb.hgCePublish.setEnabled(false);
            eb.hgCePublish.setAlpha(0.45f);
            eb.hgCeError.setVisibility(View.GONE);
            final long offsetMs = positionSupplier == null ? 0
                    : Math.max(0, positionSupplier.getAsLong());
            loader.session.fqClient.publishComment(item.seriesId, item.videoId, text, offsetMs,
                    new HgCallback<Void>() {
                        @Override
                        public void onSuccess(Void ignored) {
                            activity.runOnUiThread(() -> {
                                if (activity.isFinishing() || activity.isDestroyed()) return;
                                dialog.dismiss();
                                android.widget.Toast.makeText(activity, "已发表",
                                        android.widget.Toast.LENGTH_SHORT).show();
                                loader.refreshAfterPost();
                            });
                        }

                        @Override
                        public void onError(int httpCode, String message) {
                            activity.runOnUiThread(() -> {
                                if (activity.isFinishing() || activity.isDestroyed()
                                        || !dialog.isShowing()) return;
                                sending[0] = false;
                                eb.hgCeError.setVisibility(View.VISIBLE);
                                watcher.afterTextChanged(eb.hgCeInput.getText());
                            });
                        }
                    });
        });
        dialog.show();
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
        /** 已种缓存、正在后台静默刷新第一页：回包整表替换、失败保留缓存不报错。 */
        private boolean refreshing;
        private android.view.View skeleton;

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
            FirstPage hit = cacheGet(cacheKey(item));
            if (hit != null) {
                // 先显缓存（45 秒内），再后台静默刷新替换——重开不再全价等首包
                comments.addAll(hit.items);
                cursor = hit.nextCursor;
                hasMore = hit.hasMore;
                firstLoaded = true;
                if (onTotal != null) onTotal.accept(hit.total);
                binding.hgCmTitle.setText(hit.total > 0 ? "评论 " + hit.total : "评论");
                binding.hgCmEmpty.setVisibility(View.GONE);
                binding.hgCmList.setVisibility(View.VISIBLE);
                adapter.notifyDataSetChanged();
                refreshing = true;
                loadNext();
            } else {
                showSkeleton();
                loadNext();
            }
        }

        /** 空白期骨架行（头像块＋两行灰条 ×3）：替代纯文字「加载中…」，感知空白更短。 */
        private void showSkeleton() {
            binding.hgCmEmpty.setVisibility(View.GONE);
            android.widget.LinearLayout box = new android.widget.LinearLayout(activity);
            box.setOrientation(android.widget.LinearLayout.VERTICAL);
            float d = activity.getResources().getDisplayMetrics().density;
            for (int i = 0; i < 3; i++) {
                android.widget.LinearLayout row = new android.widget.LinearLayout(activity);
                row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                android.view.View avatar = new android.view.View(activity);
                android.graphics.drawable.GradientDrawable avBg = new android.graphics.drawable.GradientDrawable();
                avBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                avBg.setColor(0x33FFFFFF);
                avatar.setBackground(avBg);
                row.addView(avatar, new android.widget.LinearLayout.LayoutParams(
                        Math.round(36 * d), Math.round(36 * d)));
                android.widget.LinearLayout lines = new android.widget.LinearLayout(activity);
                lines.setOrientation(android.widget.LinearLayout.VERTICAL);
                android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                        0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMarginStart(Math.round(10 * d));
                lines.setLayoutParams(lp);
                android.view.View line1 = new android.view.View(activity);
                line1.setBackgroundColor(0x2EFFFFFF);
                lines.addView(line1, new android.widget.LinearLayout.LayoutParams(
                        Math.round(90 * d), Math.round(10 * d)));
                android.view.View line2 = new android.view.View(activity);
                line2.setBackgroundColor(0x24FFFFFF);
                android.widget.LinearLayout.LayoutParams l2 = new android.widget.LinearLayout.LayoutParams(
                        Math.round(200 * d), Math.round(10 * d));
                l2.topMargin = Math.round(8 * d);
                lines.addView(line2, l2);
                row.addView(lines);
                android.widget.LinearLayout.LayoutParams rp = new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rp.topMargin = Math.round(18 * d);
                box.addView(row, rp);
            }
            binding.hgCmContainer.addView(box, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
            skeleton = box;
        }

        private void hideSkeleton() {
            if (skeleton != null) {
                binding.hgCmContainer.removeView(skeleton);
                skeleton = null;
            }
        }

        private boolean episodeMode() {
            return item.videoId != null && !item.videoId.isEmpty();
        }

        private void loadNext() {
            if (loading) return;
            loading = true;
            // 静默刷新走第一页口径（cursor 空串），与续页分开记账
            final boolean isRefresh = refreshing;
            final String reqCursor = isRefresh ? "" : cursor;
            HgCallback<HgComment.Page> cb = new HgCallback<HgComment.Page>() {
                @Override
                public void onSuccess(HgComment.Page page) {
                    activity.runOnUiThread(() -> {
                        loading = false;
                        hideSkeleton();
                        if (reqCursor.isEmpty()) cachePut(cacheKey(item), page);
                        if (isRefresh) {
                            refreshing = false;
                            comments.clear();
                        }
                        comments.addAll(page.items);
                        cursor = page.nextCursor;
                        hasMore = page.hasMore;
                        if (!firstLoaded || isRefresh) {
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
                        if (isRefresh) {
                            // 静默刷新失败：保留已种的缓存内容，不报错、不清表
                            refreshing = false;
                            return;
                        }
                        hideSkeleton();
                        if (comments.isEmpty()) {
                            binding.hgCmEmpty.setVisibility(View.VISIBLE);
                            binding.hgCmEmpty.setText("评论加载失败，稍后再试");
                        }
                    });
                }
            };
            if (episodeMode()) session.fqClient.episodeComments(item.seriesId, item.videoId, reqCursor, cb);
            else session.fqClient.seriesComments(item.seriesId, reqCursor, cb);
        }

        /** 发表成功后整表从头刷新：新评论与新总数都以服务端回带为准。 */
        void refreshAfterPost() {
            if (loading) return;
            comments.clear();
            cursor = "";
            hasMore = false;
            firstLoaded = false;
            adapter.notifyDataSetChanged();
            loadNext();
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
                // 点头像/名进作者页（与流页同一去处；uid 没带不挂假入口）
                if (c.userId != null && !c.userId.isEmpty()) {
                    View.OnClickListener goAuthor = v -> HongguoAuthorActivity.start(
                            holder.itemView.getContext(), c.userId, c.author, c.avatar, null);
                    b.hgCAvatar.setOnClickListener(goAuthor);
                    b.hgCAuthor.setOnClickListener(goAuthor);
                } else {
                    b.hgCAvatar.setOnClickListener(null);
                    b.hgCAuthor.setOnClickListener(null);
                }
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
