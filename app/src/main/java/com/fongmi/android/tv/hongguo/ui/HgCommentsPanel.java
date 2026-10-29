package com.fongmi.android.tv.hongguo.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogHongguoCommentEditorBinding;
import com.fongmi.android.tv.databinding.DialogHongguoCommentsBinding;
import com.fongmi.android.tv.databinding.ItemHongguoCommentBinding;
import com.fongmi.android.tv.databinding.ItemHongguoRepliesMoreBinding;
import com.fongmi.android.tv.databinding.ItemHongguoReplyBinding;
import com.fongmi.android.tv.hongguo.HgEmoji;
import com.fongmi.android.tv.hongguo.HgFormat;
import com.fongmi.android.tv.hongguo.HgTimeText;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.api.HgCommentClient;
import com.fongmi.android.tv.hongguo.api.HgLoginAuthProvider;
import com.fongmi.android.tv.hongguo.model.HgComment;
import com.fongmi.android.tv.login.LoginManager;
import com.fongmi.android.tv.utils.ImgUtil;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * 评论面板（官方实拍 113610 白面口径完工版）：白底圆角顶、头部左 ⌄ 收起
 * ＋居中双页签「评论 N／剧评 M」——评论＝单集评论、剧评＝整剧评论，两套
 * 数据各自翻页/回复/点赞；行构成＝头像＋灰名＋正文（表情码渲染成图形、
 * 长文 4 行截断带「… 展开」）＋短时间＋回复入口＋右列点赞（心形＋数，
 * comment/do_action 乐观更新、失败回滚）；楼层回复是独立缩进行、逐条
 * 可赞可回；底部浅灰胶囊输入条（单集语境才显）点开白面编辑层（带表情
 * 条）发评论，回复走 reply/add 二级链。数据全走 hongguo 包自有
 * {@link HgCommentClient}（协议出处见该类；不直连规则年代
 * api/HongguoCommentApi）。弹幕不在本面（发表走独立弹幕编辑层，与评论
 * 两条链，口径不串）。
 *
 * <p>线程口径：拉取在 OkHttp 分发线程，回调里只把结果贴回主线程更新
 * 适配器。评论总数（单集档）经 onTotal 回报页面右列计数。</p>
 */
public final class HgCommentsPanel {

    private HgCommentsPanel() {
    }

    // ---------- 第一页进程内缓存（评论打开慢对策，定根 comment-open-slow-dig） ----------
    // 接口首包固有约 1.4–1.9s 且宿主是独占冷域名，零缓存时每次打开全价重付。
    // 按 seriesId+vid 缓存第一页 45 秒：重开先显缓存、后台静默刷新替换；
    // 当前播放条目空闲期预拉一页填缓存（每条目只发一次、失败不重试）。
    // 双页签后两个档各用自己的键：单集＝seriesId|vid、剧评＝seriesId|series。

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

    private static String cacheKey(HgFeedItem item, boolean series) {
        if (series) return item.seriesId + "|series";
        return item.seriesId + "|" + item.videoId;
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
        String key = item.seriesId + "|" + (ep ? item.videoId : "series");
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
     * @param positionSupplier 发表评论时取当前播放进度（毫秒）喂 business.offset；
     *                         不给时按 0。评论面板自身不持有播放器，由宿主喂。
     */
    public static void show(Activity activity, HgFeedItem item, LongConsumer onTotal,
                            LongSupplier positionSupplier) {
        DialogHongguoCommentsBinding binding = DialogHongguoCommentsBinding.inflate(
                LayoutInflater.from(activity));
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        dialog.setContentView(binding.getRoot());
        binding.hgCmCollapse.setOnClickListener(v -> dialog.dismiss());
        int half = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.6f);
        dialog.setOnShowListener(d -> {
            FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet == null) return;
            sheet.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            try {
                BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(sheet);
                behavior.setPeekHeight(half);
                behavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            } catch (IllegalArgumentException ignored) {
            }
        });
        Loader loader = new Loader(activity, binding, item, onTotal);
        binding.hgCmTabEpisode.setOnClickListener(v -> loader.switchTab(false));
        binding.hgCmTabSeries.setOnClickListener(v -> loader.switchTab(true));
        binding.hgCmInputBar.setOnClickListener(v -> {
            // 未登录先拉壳子统一登录（与互动四件同口径），不开编辑层
            if (!HgLoginAuthProvider.isLoggedIn()) {
                pullLogin(activity);
                return;
            }
            showEditor(activity, loader, loader.epTab(), null, positionSupplier);
        });
        dialog.show();
        loader.start();
    }

    /** 未登录拉壳子统一登录托管；投递不出去时如实提示（与流页互动同文案）。 */
    private static void pullLogin(Activity activity) {
        if (!LoginManager.loginHongguoDelivered()) {
            android.widget.Toast.makeText(activity, "登录界面暂时打不开，稍后再试",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** 回复目标：根评论 id＋被点的那条（评论本体或某条楼层回复）。 */
    private static final class ReplyTarget {
        final String rootId;
        final HgComment target;

        ReplyTarget(String rootId, HgComment target) {
            this.rootId = rootId;
            this.target = target;
        }
    }

    /**
     * 评论/回复编辑层（白面小 Dialog）：空文本发表不可点、发送中禁用防
     * 连点、失败不关层提示再试；表情条 53 码点插 [码] 文本（与官方同存
     * 码机制）；评论成功关层＋整表刷新（新评论由服务端回带、不本地编
     * 造），回复成功关层＋根评论回复数本地 +1 并刷新其楼层。
     *
     * @param reply 非空＝回复模式（标题「回复 @名」、走 reply/add）。
     */
    private static void showEditor(Activity activity, Loader loader, Loader.TabState tab,
                                   ReplyTarget reply, LongSupplier positionSupplier) {
        DialogHongguoCommentEditorBinding eb = DialogHongguoCommentEditorBinding.inflate(
                LayoutInflater.from(activity));
        android.app.Dialog dialog = new android.app.Dialog(activity);
        dialog.setContentView(eb.getRoot());
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT));
            int w = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.88f);
            dialog.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        HgFeedItem item = loader.item;
        if (reply != null) {
            String name = reply.target.author == null || reply.target.author.isEmpty()
                    ? "红果用户" : reply.target.author;
            eb.hgCeTitle.setText("回复 @" + name);
            eb.hgCeInput.setHint("回复 @" + name);
        }
        // 表情条：53 个字形钮横排，点一下把 [码] 插到光标处
        float density = activity.getResources().getDisplayMetrics().density;
        for (int i = 0; i < HgEmoji.NAMES.length; i++) {
            final String code = "[" + HgEmoji.NAMES[i] + "]";
            TextView tv = new TextView(activity);
            tv.setText(HgEmoji.GLYPHS[i]);
            tv.setTextSize(22);
            int pad = Math.round(7 * density);
            tv.setPadding(pad, Math.round(4 * density), pad, Math.round(4 * density));
            tv.setOnClickListener(v -> {
                int at = Math.max(0, eb.hgCeInput.getSelectionStart());
                String cur = eb.hgCeInput.getText() == null ? "" : eb.hgCeInput.getText().toString();
                eb.hgCeInput.setText(cur.substring(0, at) + code + cur.substring(at));
                eb.hgCeInput.setSelection(Math.min(eb.hgCeInput.getText().length(), at + code.length()));
            });
            eb.hgCeEmojiRow.addView(tv);
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
            HgCallback<Void> cb = new HgCallback<Void>() {
                @Override
                public void onSuccess(Void ignored) {
                    activity.runOnUiThread(() -> {
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        dialog.dismiss();
                        android.widget.Toast.makeText(activity, "已发表",
                                android.widget.Toast.LENGTH_SHORT).show();
                        if (reply == null) loader.refreshAfterPost();
                        else loader.afterReplyPosted(tab, reply.rootId);
                    });
                }

                @Override
                public void onError(int httpCode, String message) {
                    activity.runOnUiThread(() -> {
                        if (activity.isFinishing() || activity.isDestroyed()
                                || !dialog.isShowing()) return;
                        if (httpCode == HgCommentClient.CODE_NOT_LOGIN) {
                            dialog.dismiss();
                            pullLogin(activity);
                            return;
                        }
                        sending[0] = false;
                        eb.hgCeError.setVisibility(View.VISIBLE);
                        watcher.afterTextChanged(eb.hgCeInput.getText());
                    });
                }
            };
            if (reply == null) {
                final long offsetMs = positionSupplier == null ? 0
                        : Math.max(0, positionSupplier.getAsLong());
                loader.session.fqClient.publishComment(item.seriesId, item.videoId, text, offsetMs, cb);
            } else {
                loader.session.fqClient.publishReply(tab.series, item.seriesId, item.videoId,
                        reply.rootId, reply.target.id, reply.target.userId, text, cb);
            }
        });
        dialog.show();
        eb.hgCeInput.requestFocus();
    }

    /** 面板数据与列表状态（随面板生灭，不进会话）：两个页签各一份。 */
    private static final class Loader {
        private final Activity activity;
        private final DialogHongguoCommentsBinding binding;
        private final HgFeedItem item;
        private final LongConsumer onTotal;
        private final HgFeedSession session = HgFeedSession.get();
        private final TabState epTab;
        private final TabState srTab;
        private TabState active;
        private Adapter adapter;
        private android.view.View skeleton;

        /** 一个页签的全部列表状态。 */
        static final class TabState {
            final boolean series;
            final List<HgComment> comments = new ArrayList<>();
            final Map<String, List<HgComment>> repliesCache = new HashMap<>();
            final Map<String, String> repliesCursor = new HashMap<>();
            final Set<String> repliesMore = new HashSet<>();
            final Set<String> expanded = new HashSet<>();
            final Set<String> repliesLoading = new HashSet<>();
            final Set<String> contentExpanded = new HashSet<>();
            final Set<String> contentTruncated = new HashSet<>();
            /** 点赞本地镜像（id → [digged, 计数]）：乐观更新与失败回滚都走它，全量刷新时清。 */
            final Map<String, long[]> likes = new HashMap<>();
            String cursor = "";
            boolean hasMore;
            boolean loading;
            boolean loaded;
            /** 已种缓存、正在后台静默刷新第一页：回包整表替换、失败保留缓存不报错。 */
            boolean refreshing;
            boolean failed;
            long total = -1;

            TabState(boolean series) {
                this.series = series;
            }
        }

        Loader(Activity activity, DialogHongguoCommentsBinding binding, HgFeedItem item, LongConsumer onTotal) {
            this.activity = activity;
            this.binding = binding;
            this.item = item;
            this.onTotal = onTotal;
            boolean hasEpisode = item.videoId != null && !item.videoId.isEmpty();
            this.epTab = hasEpisode ? new TabState(false) : null;
            this.srTab = new TabState(true);
            this.active = hasEpisode ? epTab : srTab;
            binding.hgCmTabEpisode.setVisibility(hasEpisode ? View.VISIBLE : View.GONE);
        }

        TabState epTab() {
            return epTab;
        }

        void start() {
            adapter = new Adapter();
            LinearLayoutManager lm = new LinearLayoutManager(activity);
            binding.hgCmList.setLayoutManager(lm);
            binding.hgCmList.setAdapter(adapter);
            binding.hgCmList.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                    if (dy <= 0 || active.loading || !active.hasMore) return;
                    int last = lm.findLastVisibleItemPosition();
                    if (last >= adapter.rows.size() - 3) loadNext(active);
                }
            });
            syncTabs();
            activate(active);
        }

        void switchTab(boolean series) {
            TabState target = series ? srTab : epTab;
            if (target == null || target == active) return;
            active = target;
            syncTabs();
            if (!target.loaded && !target.loading) activate(target);
            else {
                adapter.rebuild();
                syncChrome();
            }
        }

        /** 页签样式与输入条显隐跟当前档走：输入条只在单集评论档。 */
        private void syncTabs() {
            paintTab(binding.hgCmTabEpisode, "评论", epTab == null ? -1 : epTab.total, active == epTab);
            paintTab(binding.hgCmTabSeries, "剧评", srTab.total, active == srTab);
            binding.hgCmInputBar.setVisibility(active == epTab && epTab != null
                    ? View.VISIBLE : View.GONE);
        }

        private void paintTab(TextView tv, String name, long total, boolean selected) {
            if (tv.getVisibility() != View.VISIBLE) return;
            String label = total >= 0 ? name + " " + HgFormat.countLine(total) : name;
            SpannableString span = new SpannableString(label);
            if (total >= 0) {
                span.setSpan(new RelativeSizeSpan(0.78f), name.length(), label.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            tv.setText(span);
            tv.setTextColor(selected ? 0xFF1F1F1F : 0xFF9A9A9A);
            tv.setTextSize(selected ? 16 : 15);
            tv.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }

        /** 首次激活一个页签：有缓存先种＋静默刷新，没缓存显骨架拉第一页。 */
        private void activate(TabState tab) {
            FirstPage hit = cacheGet(cacheKey(item, tab.series));
            if (hit != null) {
                tab.comments.addAll(hit.items);
                tab.cursor = hit.nextCursor;
                tab.hasMore = hit.hasMore;
                tab.total = hit.total;
                tab.loaded = true;
                if (tab == epTab && onTotal != null) onTotal.accept(hit.total);
                syncTabs();
                adapter.rebuild();
                syncChrome();
                tab.refreshing = true;
                loadNext(tab);
            } else {
                syncChrome();
                loadNext(tab);
            }
        }

        /** 空态/骨架/列表三者的显隐跟当前档状态走。 */
        private void syncChrome() {
            if (!active.comments.isEmpty()) {
                hideSkeleton();
                binding.hgCmEmptyBox.setVisibility(View.GONE);
                binding.hgCmList.setVisibility(View.VISIBLE);
                return;
            }
            binding.hgCmList.setVisibility(View.GONE);
            if (active.loading || active.refreshing) {
                binding.hgCmEmptyBox.setVisibility(View.GONE);
                showSkeleton();
                return;
            }
            hideSkeleton();
            binding.hgCmEmptyBox.setVisibility(View.VISIBLE);
            if (active.failed) {
                binding.hgCmEmptyTitle.setText((active.series ? "剧评" : "评论") + "加载失败，稍后再试");
                binding.hgCmEmptySub.setVisibility(View.GONE);
            } else {
                binding.hgCmEmptyTitle.setText(active.series ? "暂无剧评" : "暂无评论");
                binding.hgCmEmptySub.setVisibility(View.VISIBLE);
                binding.hgCmEmptySub.setText(active.series
                        ? "期待你的第一条剧评" : "期待你的第一条评论");
            }
        }

        /** 空白期骨架行（浅灰头像块＋两行灰条 ×3，白面版）。 */
        private void showSkeleton() {
            if (skeleton != null) return;
            android.widget.LinearLayout box = new android.widget.LinearLayout(activity);
            box.setOrientation(android.widget.LinearLayout.VERTICAL);
            float d = activity.getResources().getDisplayMetrics().density;
            for (int i = 0; i < 3; i++) {
                android.widget.LinearLayout row = new android.widget.LinearLayout(activity);
                row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                android.view.View avatar = new android.view.View(activity);
                android.graphics.drawable.GradientDrawable avBg = new android.graphics.drawable.GradientDrawable();
                avBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                avBg.setColor(0xFFF0F0F0);
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
                line1.setBackgroundColor(0xFFF2F2F2);
                lines.addView(line1, new android.widget.LinearLayout.LayoutParams(
                        Math.round(90 * d), Math.round(10 * d)));
                android.view.View line2 = new android.view.View(activity);
                line2.setBackgroundColor(0xFFF5F5F5);
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

        private void loadNext(TabState tab) {
            if (tab.loading) return;
            tab.loading = true;
            final boolean isRefresh = tab.refreshing;
            final String reqCursor = isRefresh ? "" : tab.cursor;
            HgCallback<HgComment.Page> cb = new HgCallback<HgComment.Page>() {
                @Override
                public void onSuccess(HgComment.Page page) {
                    activity.runOnUiThread(() -> {
                        tab.loading = false;
                        if (reqCursor.isEmpty()) cachePut(cacheKey(item, tab.series), page);
                        if (isRefresh) {
                            tab.refreshing = false;
                            tab.comments.clear();
                            tab.likes.clear();
                        }
                        tab.comments.addAll(page.items);
                        tab.cursor = page.nextCursor;
                        tab.hasMore = page.hasMore;
                        boolean firstLanding = !tab.loaded;
                        tab.loaded = true;
                        tab.failed = false;
                        tab.total = page.total;
                        if (tab == epTab && (firstLanding || isRefresh) && onTotal != null) {
                            onTotal.accept(page.total);
                        }
                        syncTabs();
                        if (tab == active) {
                            adapter.rebuild();
                            syncChrome();
                        }
                    });
                }

                @Override
                public void onError(int httpCode, String message) {
                    activity.runOnUiThread(() -> {
                        tab.loading = false;
                        if (isRefresh) {
                            // 静默刷新失败：保留已种的缓存内容，不报错、不清表
                            tab.refreshing = false;
                            return;
                        }
                        if (tab.comments.isEmpty()) tab.failed = true;
                        if (tab == active) syncChrome();
                    });
                }
            };
            if (tab.series) session.fqClient.seriesComments(item.seriesId, reqCursor, cb);
            else session.fqClient.episodeComments(item.seriesId, item.videoId, reqCursor, cb);
        }

        /** 发表评论成功后整表从头刷新：新评论与新总数都以服务端回带为准。 */
        void refreshAfterPost() {
            TabState tab = epTab;
            if (tab == null || tab.loading) return;
            tab.comments.clear();
            tab.repliesCache.clear();
            tab.repliesCursor.clear();
            tab.repliesMore.clear();
            tab.expanded.clear();
            tab.likes.clear();
            tab.cursor = "";
            tab.hasMore = false;
            tab.loaded = false;
            if (tab == active) {
                adapter.rebuild();
                syncChrome();
            }
            loadNext(tab);
        }

        /** 回复发表成功：根评论回复数 +1、其楼层缓存作废（展开着就重拉）。 */
        void afterReplyPosted(TabState tab, String rootId) {
            for (int i = 0; i < tab.comments.size(); i++) {
                HgComment c = tab.comments.get(i);
                if (c.id.equals(rootId)) {
                    tab.comments.set(i, c.withReplyCount(c.replyCount + 1));
                    break;
                }
            }
            tab.repliesCache.remove(rootId);
            tab.repliesCursor.remove(rootId);
            tab.repliesMore.remove(rootId);
            if (tab.expanded.contains(rootId)) loadReplies(tab, rootId, false);
            if (tab == active) adapter.rebuild();
        }

        private void toggleReplies(TabState tab, HgComment comment) {
            String id = comment.id;
            if (tab.expanded.contains(id)) {
                tab.expanded.remove(id);
                adapter.rebuild();
                return;
            }
            tab.expanded.add(id);
            if (tab.repliesCache.containsKey(id)) {
                adapter.rebuild();
                return;
            }
            adapter.rebuild();
            loadReplies(tab, id, false);
        }

        private void loadReplies(TabState tab, String commentId, boolean more) {
            if (!tab.repliesLoading.add(commentId)) return;
            String cur = more ? tab.repliesCursor.getOrDefault(commentId, "") : "";
            HgCallback<HgComment.Page> cb = new HgCallback<HgComment.Page>() {
                @Override
                public void onSuccess(HgComment.Page page) {
                    activity.runOnUiThread(() -> {
                        tab.repliesLoading.remove(commentId);
                        List<HgComment> list = tab.repliesCache.computeIfAbsent(
                                commentId, k -> new ArrayList<>());
                        list.addAll(page.items);
                        tab.repliesCursor.put(commentId, page.nextCursor);
                        if (page.hasMore) tab.repliesMore.add(commentId);
                        else tab.repliesMore.remove(commentId);
                        if (tab == active) adapter.rebuild();
                    });
                }

                @Override
                public void onError(int httpCode, String message) {
                    activity.runOnUiThread(() -> {
                        tab.repliesLoading.remove(commentId);
                        if (tab == active) adapter.rebuild();
                    });
                }
            };
            if (tab.series) session.fqClient.seriesReplies(item.seriesId, commentId, cur, cb);
            else session.fqClient.episodeReplies(item.seriesId, item.videoId, commentId, cur, cb);
        }

        /** 点赞列绑定：本地镜像优先于回包值，两态心形＋计数上下排。 */
        private void bindLike(View box, ImageView icon, TextView count,
                              TabState tab, HgComment c, boolean isReply) {
            long[] st = tab.likes.get(c.id);
            boolean digged = st != null ? st[0] == 1 : c.digged;
            long likes = st != null ? st[1] : c.likes;
            icon.setImageResource(digged ? R.drawable.ic_hg_heart_fill : R.drawable.ic_hg_like);
            count.setText(HgFormat.countLine(likes));
            count.setTextColor(digged ? 0xFFFF3B5C : 0xFF8A8A8A);
            box.setOnClickListener(v -> toggleLike(tab, c, isReply));
        }

        /** 行内「回复」入口：未登录先拉登录，已登录开回复模式编辑层。 */
        private void openReplyEditor(TabState tab, HgComment root, HgComment target) {
            if (!HgLoginAuthProvider.isLoggedIn()) {
                pullLogin(activity);
                return;
            }
            showEditor(activity, this, tab, new ReplyTarget(root.id, target), null);
        }

        /** 点赞/取消赞：本地镜像先翻、服务端回执失败再翻回（与流页互动同口径）。 */
        private void toggleLike(TabState tab, HgComment target, boolean isReply) {
            if (!HgLoginAuthProvider.isLoggedIn()) {
                pullLogin(activity);
                return;
            }
            long[] prev = tab.likes.get(target.id);
            boolean curDigged = prev != null ? prev[0] == 1 : target.digged;
            long curLikes = prev != null ? prev[1] : target.likes;
            boolean next = !curDigged;
            tab.likes.put(target.id, new long[]{next ? 1 : 0, Math.max(0, curLikes + (next ? 1 : -1))});
            adapter.rebuild();
            session.fqClient.doCommentAction(next, target.id,
                    isReply ? HgCommentClient.OBJECT_REPLY : HgCommentClient.OBJECT_COMMENT,
                    new HgCallback<Void>() {
                        @Override
                        public void onSuccess(Void ignored) {
                        }

                        @Override
                        public void onError(int httpCode, String message) {
                            activity.runOnUiThread(() -> {
                                if (prev == null) tab.likes.remove(target.id);
                                else tab.likes.put(target.id, prev);
                                if (tab == active) adapter.rebuild();
                                if (httpCode == HgCommentClient.CODE_NOT_LOGIN) {
                                    pullLogin(activity);
                                } else {
                                    android.widget.Toast.makeText(activity,
                                            "点赞没成功，已恢复原来的状态",
                                            android.widget.Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                    });
        }

        /** 行模型：评论行／楼层回复行／展开更多回复尾行，切换状态后整体重建。 */
        private static final class Row {
            static final int COMMENT = 0;
            static final int REPLY = 1;
            static final int MORE = 2;
            final int type;
            final HgComment comment;
            /** REPLY/MORE 行所属根评论。 */
            final HgComment parent;

            Row(int type, HgComment comment, HgComment parent) {
                this.type = type;
                this.comment = comment;
                this.parent = parent;
            }
        }

        private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
            final List<Row> rows = new ArrayList<>();

            void rebuild() {
                rows.clear();
                TabState tab = active;
                for (HgComment c : tab.comments) {
                    rows.add(new Row(Row.COMMENT, c, null));
                    if (!tab.expanded.contains(c.id)) continue;
                    List<HgComment> replies = tab.repliesCache.get(c.id);
                    if (replies != null) {
                        for (HgComment r : replies) rows.add(new Row(Row.REPLY, r, c));
                    }
                    if (tab.repliesLoading.contains(c.id)
                            || tab.repliesMore.contains(c.id)) {
                        rows.add(new Row(Row.MORE, null, c));
                    }
                }
                notifyDataSetChanged();
            }

            @Override
            public int getItemViewType(int position) {
                return rows.get(position).type;
            }

            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                LayoutInflater inflater = LayoutInflater.from(parent.getContext());
                if (viewType == Row.REPLY) {
                    return new ReplyHolder(ItemHongguoReplyBinding.inflate(inflater, parent, false));
                }
                if (viewType == Row.MORE) {
                    return new MoreHolder(ItemHongguoRepliesMoreBinding.inflate(inflater, parent, false));
                }
                return new CommentHolder(ItemHongguoCommentBinding.inflate(inflater, parent, false));
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                Row row = rows.get(position);
                if (holder instanceof CommentHolder) bindComment((CommentHolder) holder, row.comment);
                else if (holder instanceof ReplyHolder) bindReply((ReplyHolder) holder, row);
                else if (holder instanceof MoreHolder) bindMore((MoreHolder) holder, row.parent);
            }

            @Override
            public int getItemCount() {
                return rows.size();
            }

            private void bindComment(CommentHolder holder, HgComment c) {
                ItemHongguoCommentBinding b = holder.binding;
                TabState tab = active;
                bindAvatar(b.hgCAvatar, c);
                b.hgCAuthor.setText(authorName(c));
                View.OnClickListener goAuthor = authorClick(holder, c);
                b.hgCAvatar.setOnClickListener(goAuthor);
                b.hgCAuthor.setOnClickListener(goAuthor);
                // 剧评档带评分：十分制折五星（与作者页剧评行同口径）
                if (tab.series && c.score >= 0) {
                    b.hgCStars.setVisibility(View.VISIBLE);
                    paintStars(b.hgCStars, c.score);
                } else {
                    b.hgCStars.setVisibility(View.GONE);
                }
                bindContent(b.hgCContent, b.hgCExpand, b.hgCCollapse, tab, c);
                b.hgCTime.setText(HgTimeText.commentTime(c.created));
                b.hgCReply.setOnClickListener(v -> openReplyEditor(tab, c, c));
                bindLike(b.hgCLikeBox, b.hgCLikeIcon, b.hgCLikeCount, tab, c, false);
                boolean open = tab.expanded.contains(c.id);
                if (c.replyCount > 0) {
                    b.hgCRepliesBtn.setVisibility(View.VISIBLE);
                    b.hgCRepliesText.setText(open ? "收起" : "展开" + c.replyCount + "条回复");
                    b.hgCRepliesChev.setVisibility(open ? View.GONE : View.VISIBLE);
                    b.hgCRepliesBtn.setOnClickListener(v -> toggleReplies(tab, c));
                } else {
                    b.hgCRepliesBtn.setVisibility(View.GONE);
                }
            }

            private void bindReply(ReplyHolder holder, Row row) {
                ItemHongguoReplyBinding b = holder.binding;
                TabState tab = active;
                HgComment r = row.comment;
                bindAvatar(b.hgRAvatar, r);
                b.hgRAuthor.setText(authorName(r));
                View.OnClickListener goAuthor = authorClick(holder, r);
                b.hgRAvatar.setOnClickListener(goAuthor);
                b.hgRAuthor.setOnClickListener(goAuthor);
                bindContent(b.hgRContent, b.hgRExpand, b.hgRCollapse, tab, r, replyPrefix(r));
                b.hgRTime.setText(HgTimeText.commentTime(r.created));
                b.hgRReply.setOnClickListener(v -> openReplyEditor(tab, row.parent, r));
                bindLike(b.hgRLikeBox, b.hgRLikeIcon, b.hgRLikeCount, tab, r, true);
            }

            private void bindMore(MoreHolder holder, HgComment parent) {
                ItemHongguoRepliesMoreBinding b = holder.binding;
                TabState tab = active;
                boolean loadingNow = tab.repliesLoading.contains(parent.id);
                b.hgMText.setText(loadingNow ? "回复加载中…" : "展开更多回复");
                b.hgMChev.setVisibility(loadingNow ? View.GONE : View.VISIBLE);
                holder.itemView.setOnClickListener(loadingNow ? null
                        : v -> loadReplies(tab, parent.id, true));
            }
        }

        private static final class CommentHolder extends RecyclerView.ViewHolder {
            final ItemHongguoCommentBinding binding;

            CommentHolder(ItemHongguoCommentBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }

        private static final class ReplyHolder extends RecyclerView.ViewHolder {
            final ItemHongguoReplyBinding binding;

            ReplyHolder(ItemHongguoReplyBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }

        private static final class MoreHolder extends RecyclerView.ViewHolder {
            final ItemHongguoRepliesMoreBinding binding;

            MoreHolder(ItemHongguoRepliesMoreBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }

    // ---------- 行内共用绑定 ----------

    private static String authorName(HgComment c) {
        return c.author == null || c.author.isEmpty() ? "红果用户" : c.author;
    }

    private static void bindAvatar(ImageView view, HgComment c) {
        if (c.avatar != null && !c.avatar.isEmpty()) {
            view.setVisibility(View.VISIBLE);
            ImgUtil.loadCircle("", c.avatar, view);
        } else {
            view.setVisibility(View.GONE);
        }
    }

    /** 点头像/名进作者页（与流页同一去处；uid 没带不挂假入口）。 */
    private static View.OnClickListener authorClick(RecyclerView.ViewHolder holder, HgComment c) {
        if (c.userId == null || c.userId.isEmpty()) return null;
        return v -> HongguoAuthorActivity.start(
                holder.itemView.getContext(), c.userId, c.author, c.avatar, null);
    }

    /** 十分制折五星（满星橘、空星灰，与作者页剧评行 paintStars 同口径）。 */
    private static void paintStars(android.widget.LinearLayout row, int scoreTen) {
        int filled = Math.round(scoreTen / 2f);
        if (filled < 0) filled = 0;
        if (filled > 5) filled = 5;
        for (int i = 0; i < row.getChildCount(); i++) {
            ((ImageView) row.getChildAt(i)).setImageResource(i < filled
                    ? R.drawable.ic_hg_author_star_on
                    : R.drawable.ic_hg_author_star_off);
        }
    }

    private static CharSequence replyPrefix(HgComment r) {
        if (r.replyTo == null || r.replyTo.isEmpty()) return null;
        String prefix = "回复 " + r.replyTo + "：";
        SpannableString span = new SpannableString(prefix);
        span.setSpan(new ForegroundColorSpan(0xFF6B7F99), 0, prefix.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return span;
    }

    /**
     * 正文绑定：表情码渲染成图形；折叠态 4 行截断，布局后量到省略号才亮
     * 末行右端「… 展开」盖钮；展开态全显、曾截断过的给「收起」。
     *
     * @param prefix 非空时拼在正文前（如回复行的「回复 X：」彩色前缀）。
     */
    private static void bindContent(TextView tv, TextView expandBtn, TextView collapseBtn,
                                    Loader.TabState tab, HgComment c, CharSequence prefix) {
        CharSequence body = HgEmoji.render(c.content);
        if (prefix != null) {
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
            sb.append(prefix);
            sb.append(body);
            tv.setText(sb);
        } else {
            tv.setText(body);
        }
        boolean open = tab.contentExpanded.contains(c.id);
        tv.setMaxLines(open ? Integer.MAX_VALUE : 4);
        expandBtn.setVisibility(View.GONE);
        collapseBtn.setVisibility(open && tab.contentTruncated.contains(c.id)
                ? View.VISIBLE : View.GONE);
        tv.setTag(c.id);
        if (!open) {
            tv.post(() -> {
                if (!c.id.equals(tv.getTag())) return;
                Layout layout = tv.getLayout();
                if (layout != null && layout.getLineCount() >= 4
                        && layout.getEllipsisCount(3) > 0) {
                    tab.contentTruncated.add(c.id);
                    expandBtn.setVisibility(View.VISIBLE);
                }
            });
        }
        expandBtn.setOnClickListener(v -> {
            tab.contentExpanded.add(c.id);
            // 展开只影响本行：直接改本行形态，不整表重建（保滚动位）
            tv.setMaxLines(Integer.MAX_VALUE);
            expandBtn.setVisibility(View.GONE);
            collapseBtn.setVisibility(View.VISIBLE);
        });
        collapseBtn.setOnClickListener(v -> {
            tab.contentExpanded.remove(c.id);
            tv.setMaxLines(4);
            collapseBtn.setVisibility(View.GONE);
            expandBtn.setVisibility(View.VISIBLE);
        });
    }

    private static void bindContent(TextView tv, TextView expandBtn, TextView collapseBtn,
                                    Loader.TabState tab, HgComment c) {
        bindContent(tv, expandBtn, collapseBtn, tab, c, null);
    }
}
