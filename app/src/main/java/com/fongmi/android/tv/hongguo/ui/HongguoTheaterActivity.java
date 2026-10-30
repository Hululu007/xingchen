package com.fongmi.android.tv.hongguo.ui;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.drama.DramaCategory;
import com.fongmi.android.tv.hongguo.drama.DramaCellPage;
import com.fongmi.android.tv.hongguo.drama.DramaClient;
import com.fongmi.android.tv.hongguo.drama.DramaItem;
import com.fongmi.android.tv.hongguo.drama.DramaItemKind;
import com.fongmi.android.tv.hongguo.model.HgContinuation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 剧场页（2026-10-30 从零重建，他亲定底栏第二签）：简单分类浏览。
 * 大分类四格（他 2026-10-30 改口径，旧「AI 归漫剧侧」作废）：真人剧/
 * 漫剧/AI剧/视频，各配一行小分类筹（单选、无数据整行不显）＋网格/瀑布。
 * 数据分法（分类探针实拉配对定版，账 hongguo-theater-ai-20261030）：
 * 服务端类型类 content_category_type——「1」真人剧走 39 频道、
 * 「1004」漫剧走 36 频道（漫剧格剔除 AI 类收纯）、「1004_ai_video」
 * AI剧在 36 频道里实拉为零、只在发现流（-1,16）成量出现，AI 格即
 * 发现流按该字段过滤成格；ai_usage_type 是作者声明标记、两类都带，
 * 不是分家字段。筹面各走各格数据源：真人/漫剧筹取自各自频道回包
 * 抽取，AI 筹只取本格 AI 条目自带的 category_schema，不借别格类目。
 * 视频格：UGC 短片/合集双列瀑布卡。点封面直接进播放页开播，不绕
 * 详情页（定稿口径）；底栏白色（定稿），与首页黑块底栏分开。
 */
public class HongguoTheaterActivity extends AppCompatActivity {

    private static final int[][] CHANNELS = {{39, 39}, {36, 36}};
    private static final String[] CHANNEL_NAMES = {"真人剧", "漫剧", "AI剧", "视频"};
    private static final int TAB_MANJU = 1;
    private static final int TAB_AI = 2;
    private static final int TAB_CLIPS = 3;

    private RecyclerView grid;
    private GridAdapter gridAdapter;
    private ClipAdapter clipAdapter;
    private TextView[] tabViews;
    private TextView stateText;
    private HorizontalScrollView chipScroll;
    private LinearLayout chipRow;
    private final List<TextView> chipViews = new ArrayList<>();
    private final List<DramaCategory> categories = new ArrayList<>();
    private String activeCategoryId;
    private final List<DramaItem> items = new ArrayList<>();
    private final List<DramaItem> clips = new ArrayList<>();
    private int channel = 0;
    private HgContinuation continuation;
    private boolean hasMore;
    private boolean loading;
    private boolean reqLanding;
    private int landingOffset;
    private int clipChain;
    private int aiChain;

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private int navBarHeight() {
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        HgWindowStyle.apply(this, false, true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF111114);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), statusBarHeight() + dp(8), dp(10), dp(4));
        TextView title = new TextView(this);
        title.setText("剧场");
        title.setTextColor(Color.WHITE);
        title.setTextSize(19);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView search = new ImageView(this);
        search.setImageResource(R.drawable.ic_drama_search);
        search.setColorFilter(Color.WHITE);
        search.setPadding(dp(8), dp(8), dp(8), dp(8));
        search.setOnClickListener(v -> startActivity(new Intent(this, HongguoSearchActivity.class)));
        top.addView(search, new LinearLayout.LayoutParams(dp(40), dp(40)));
        root.addView(top);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(10), dp(2), dp(10), dp(6));
        tabViews = new TextView[CHANNEL_NAMES.length];
        for (int i = 0; i < CHANNEL_NAMES.length; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(CHANNEL_NAMES[i]);
            t.setTextSize(14.5f);
            t.setPadding(dp(13), dp(6), dp(13), dp(6));
            t.setOnClickListener(v -> {
                if (channel != idx) {
                    channel = idx;
                    activeCategoryId = null;
                    categories.clear();
                    rebuildChips();
                    selectTab();
                    applyLayout();
                    load(true);
                }
            });
            tabViews[i] = t;
            tabs.addView(t);
        }
        root.addView(tabs);

        chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setVisibility(View.GONE);
        chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setPadding(dp(12), dp(2), dp(12), dp(6));
        chipScroll.addView(chipRow);
        root.addView(chipScroll);

        grid = new RecyclerView(this);
        grid.setPadding(dp(8), dp(2), dp(8), dp(8));
        grid.setClipToPadding(false);
        gridAdapter = new GridAdapter();
        clipAdapter = new ClipAdapter();
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy <= 0 || loading || !hasMore) return;
                int last = lastVisiblePosition();
                int count = channel == TAB_CLIPS ? clips.size() : items.size();
                if (last >= count - 6) load(false);
            }
        });
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));

        stateText = new TextView(this);
        stateText.setTextColor(0xFF9E9EA7);
        stateText.setTextSize(13.5f);
        stateText.setGravity(Gravity.CENTER);
        stateText.setPadding(0, dp(10), 0, dp(10));
        stateText.setVisibility(View.GONE);
        stateText.setOnClickListener(v -> {
            if ((channel == TAB_CLIPS ? clips : items).isEmpty()) load(true);
        });
        root.addView(stateText);

        root.addView(buildBottomBar());
        setContentView(root);
        selectTab();
        applyLayout();
        load(true);
    }

    private int lastVisiblePosition() {
        RecyclerView.LayoutManager lm = grid.getLayoutManager();
        if (lm instanceof GridLayoutManager) {
            return ((GridLayoutManager) lm).findLastVisibleItemPosition();
        }
        if (lm instanceof StaggeredGridLayoutManager) {
            int[] pos = ((StaggeredGridLayoutManager) lm)
                    .findLastVisibleItemPositions(null);
            int max = -1;
            for (int p : pos) max = Math.max(max, p);
            return max;
        }
        return -1;
    }

    private void applyLayout() {
        if (channel == TAB_CLIPS) {
            grid.setLayoutManager(new StaggeredGridLayoutManager(2,
                    StaggeredGridLayoutManager.VERTICAL));
            grid.setAdapter(clipAdapter);
            chipScroll.setVisibility(View.GONE);
        } else {
            grid.setLayoutManager(new GridLayoutManager(this, 3));
            grid.setAdapter(gridAdapter);
            chipScroll.setVisibility(categories.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private View buildBottomBar() {
        // 定稿：剧场底栏白色（与首页黑块底栏分开），选中深色、未选灰
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Color.WHITE);
        bar.setPadding(0, 0, 0, navBarHeight());
        LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, dp(50), 1f);
        bar.addView(bottomCell(R.drawable.ic_drama_home, "首页", false, v -> finish()), cell);
        bar.addView(bottomCell(R.drawable.ic_drama_theater, "剧场", true, null), cell);
        return bar;
    }

    private View bottomCell(int icon, String label, boolean selected, View.OnClickListener click) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(selected ? 0xFF111114 : 0xFF8E8E96);
        c.addView(iv, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(10);
        t.setTextColor(selected ? 0xFF111114 : 0xFF8E8E96);
        c.addView(t);
        if (click != null) c.setOnClickListener(click);
        return c;
    }

    private void selectTab() {
        for (int i = 0; i < tabViews.length; i++) {
            boolean on = i == channel;
            tabViews[i].setTextColor(on ? Color.WHITE : 0xFF9E9EA7);
            tabViews[i].setTypeface(on ? android.graphics.Typeface.DEFAULT_BOLD
                    : android.graphics.Typeface.DEFAULT);
            tabViews[i].setBackground(roundBg(on ? 0xFF2C2C30 : Color.TRANSPARENT, dp(14)));
        }
    }

    private GradientDrawable roundBg(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    // ---------- 小分类筹 ----------

    private void rebuildChips() {
        chipRow.removeAllViews();
        chipViews.clear();
        if (categories.isEmpty() || channel == TAB_CLIPS) {
            chipScroll.setVisibility(View.GONE);
            return;
        }
        chipScroll.setVisibility(View.VISIBLE);
        addChip(null, "全部");
        for (DramaCategory c : categories) addChip(c.id, c.name);
        styleChips();
    }

    private void addChip(String id, String name) {
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(12.5f);
        t.setPadding(dp(11), dp(5), dp(11), dp(5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> {
            boolean same = (id == null && activeCategoryId == null)
                    || (id != null && id.equals(activeCategoryId));
            if (same) return;
            activeCategoryId = id;
            styleChips();
            load(true);
        });
        chipViews.add(t);
        chipRow.addView(t);
    }

    private void styleChips() {
        for (int i = 0; i < chipViews.size(); i++) {
            boolean on = (i == 0 && activeCategoryId == null)
                    || (i > 0 && categories.get(i - 1).id.equals(activeCategoryId));
            TextView t = chipViews.get(i);
            t.setTextColor(on ? Color.WHITE : 0xFFB9B9C0);
            t.setBackground(roundBg(on ? 0xFFE8402F : 0xFF232328, dp(13)));
        }
    }

    // ---------- 取数 ----------

    private void load(boolean first) {
        if (loading) return;
        loading = true;
        reqLanding = activeCategoryId != null && channel != TAB_CLIPS;
        if (first) {
            items.clear();
            clips.clear();
            gridAdapter.notifyDataSetChanged();
            clipAdapter.notifyDataSetChanged();
            continuation = null;
            landingOffset = 0;
            clipChain = 0;
            aiChain = 0;
            hasMore = false;
            stateText.setText("加载中…");
            stateText.setVisibility(View.VISIBLE);
        }
        HgCallback<DramaCellPage> cb = new HgCallback<DramaCellPage>() {
            @Override
            public void onSuccess(DramaCellPage page) {
                runOnUiThread(() -> onPage(page, first));
            }

            @Override
            public void onError(int code, String msg) {
                runOnUiThread(() -> {
                    loading = false;
                    if ((channel == TAB_CLIPS ? clips : items).isEmpty()) {
                        stateText.setText("取数据失败，点按重试");
                        stateText.setVisibility(View.VISIBLE);
                    }
                });
            }
        };
        if (channel == TAB_CLIPS) {
            if (first || continuation == null) DramaClient.fetchChannel(-1, 16, cb);
            else DramaClient.fetchChannelMore(continuation, cb);
        } else if (channel == TAB_AI && !reqLanding) {
            // AI剧格数据源＝发现流（与视频格同口），回包在 onPage 按
            // 服务端类型类 1004_ai_video 过滤成格。
            if (first || continuation == null) DramaClient.fetchChannel(-1, 16, cb);
            else DramaClient.fetchChannelMore(continuation, cb);
        } else if (reqLanding) {
            DramaClient.fetchCategoryLanding(activeCategoryId, first ? 0 : landingOffset, cb);
        } else if (first || continuation == null) {
            int[] ch = CHANNELS[channel];
            DramaClient.fetchChannel(ch[0], ch[1], cb);
        } else {
            DramaClient.fetchChannelMore(continuation, cb);
        }
    }

    private void onPage(DramaCellPage page, boolean first) {
        loading = false;
        if (channel == TAB_CLIPS) {
            hasMore = page.isHasMore();
            if (page.getContinuation() != null) continuation = page.getContinuation();
            Set<String> seen = new HashSet<>();
            for (DramaItem it : clips) {
                String key = it.getVid() != null ? it.getVid() : it.getSeriesId();
                if (key != null) seen.add(key);
            }
            int added = 0;
            for (DramaItem it : page.getItems()) {
                if (it.kind == DramaItemKind.SERIES) continue;
                String key = it.getVid() != null ? it.getVid() : it.getSeriesId();
                if (key == null || !seen.add(key)) continue;
                clips.add(it);
                added++;
            }
            clipAdapter.notifyDataSetChanged();
            stateText.setVisibility(clips.isEmpty() ? View.VISIBLE : View.GONE);
            if (clips.isEmpty()) stateText.setText("暂时没有内容，点按重试");
            // 整页被过滤光但还有下页：自动续拉（最多连 3 页），免得短片格假空。
            if (added == 0 && hasMore && clipChain < 3) {
                clipChain++;
                load(false);
            }
            return;
        }
        if (reqLanding) {
            landingOffset += page.getItems().size();
            hasMore = !page.getItems().isEmpty();
        } else {
            hasMore = page.isHasMore();
            if (page.getContinuation() != null) continuation = page.getContinuation();
            // AI 格的筹不取频道回包抽取（那是发现流的混合面），只取
            // 本格条目自带分类，见 rebuildAiCategories；其余格照旧。
            if (first && channel != TAB_AI && !page.getCategories().isEmpty()) {
                categories.clear();
                categories.addAll(page.getCategories());
                rebuildChips();
            }
        }
        // 分格过滤（服务端类型类为准，不靠标题猜）：AI 格只收
        // 1004_ai_video（频道面另加 SERIES 型门），漫剧格剔除 AI 类收纯。
        List<DramaItem> src = page.getItems();
        if (channel == TAB_AI) {
            List<DramaItem> f = new ArrayList<>();
            for (DramaItem it : src) {
                boolean keep = reqLanding ? it.isAiDrama()
                        : (it.kind == DramaItemKind.SERIES && it.isAiDrama());
                if (keep) f.add(it);
            }
            src = f;
        } else if (channel == TAB_MANJU && !reqLanding) {
            List<DramaItem> f = new ArrayList<>();
            for (DramaItem it : src) if (!it.isAiDrama()) f.add(it);
            src = f;
        }
        Set<String> seen = new HashSet<>();
        for (DramaItem it : items) if (it.getSeriesId() != null) seen.add(it.getSeriesId());
        int added = 0;
        for (DramaItem it : src) {
            String sid = it.getSeriesId();
            if (sid == null || !seen.add(sid)) continue;
            items.add(it);
            added++;
        }
        gridAdapter.notifyDataSetChanged();
        stateText.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        if (items.isEmpty()) stateText.setText("暂时没有内容，点按重试");
        if (channel == TAB_AI) {
            if (!reqLanding) rebuildAiCategories();
            if (added > 0) aiChain = 0;
            // 整页被过滤光但还有下页：自动续拉（最多连 4 页），免得
            // AI 格假空（与短片格 clipChain 同法）。
            if (added == 0 && hasMore && aiChain < 4) {
                aiChain++;
                load(false);
            }
        }
    }

    /** AI剧格筹面：只取本格已收 AI 条目自带的分类（category_schema 的
     * id＋名），逐条攒齐去重；不借真人/漫剧任何一格的类目。 */
    private void rebuildAiCategories() {
        List<DramaCategory> union = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (DramaItem it : items) {
            if (it.itemCategories == null) continue;
            for (DramaCategory c : it.itemCategories) {
                if (c.id != null && !c.id.isEmpty() && seen.add(c.id)) union.add(c);
            }
        }
        StringBuilder now = new StringBuilder();
        for (DramaCategory c : categories) now.append(c.id).append(',');
        StringBuilder next = new StringBuilder();
        for (DramaCategory c : union) next.append(c.id).append(',');
        if (!now.toString().equals(next.toString())) {
            categories.clear();
            categories.addAll(union);
            rebuildChips();
        }
    }

    /** 定稿：点封面直接进播放页开播（从断点集），不绕详情页。 */
    private void openDrama(DramaItem item) {
        if (item.getSeriesId() == null) return;
        HongguoFeedActivity.startSeries(this, item.getSeriesId(), item.getTitle(), 0);
    }

    /** 定稿：视频签点卡进视频页——合集走专辑队列、单条走单条形态。 */
    private void openClip(DramaItem item) {
        if (item.kind == DramaItemKind.ALBUM) {
            HongguoFeedActivity.startVideoAlbum(this, item);
            return;
        }
        HongguoFeedActivity.startVideo(this, item);
    }

    // ---------- 正剧网格 ----------

    private class GridAdapter extends RecyclerView.Adapter<GridAdapter.H> {

        class H extends RecyclerView.ViewHolder {
            final ImageView cover;
            final TextView title;
            final TextView meta;

            H(LinearLayout cell) {
                super(cell);
                cover = cell.findViewWithTag("cover");
                title = cell.findViewWithTag("title");
                meta = cell.findViewWithTag("meta");
            }
        }

        @NonNull
        @Override
        public H onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout cell = new LinearLayout(HongguoTheaterActivity.this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setPadding(dp(5), dp(6), dp(5), dp(10));
            ImageView cover = new ImageView(HongguoTheaterActivity.this);
            cover.setTag("cover");
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackground(roundBg(0xFF232328, dp(8)));
            int w = (getResources().getDisplayMetrics().widthPixels - dp(46)) / 3;
            cell.addView(cover, new LinearLayout.LayoutParams(-1, (int) (w * 1.38f)));
            TextView title = new TextView(HongguoTheaterActivity.this);
            title.setTag("title");
            title.setTextColor(0xFFF2F2F4);
            title.setTextSize(13);
            title.setMaxLines(1);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
            tlp.topMargin = dp(6);
            cell.addView(title, tlp);
            TextView meta = new TextView(HongguoTheaterActivity.this);
            meta.setTag("meta");
            meta.setTextColor(0xFF8E8E96);
            meta.setTextSize(11);
            meta.setMaxLines(1);
            cell.addView(meta);
            return new H(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull H h, int position) {
            DramaItem item = items.get(position);
            if (item.getCoverUrl() != null && !item.getCoverUrl().isEmpty()) {
                Glide.with(HongguoTheaterActivity.this).load(item.getCoverUrl()).into(h.cover);
            } else {
                h.cover.setImageDrawable(null);
            }
            h.title.setText(item.getTitle() == null ? "" : item.getTitle());
            int total = item.getEpisodeCount();
            h.meta.setText(total > 0
                    ? (item.isFinished() ? "全" + total + "集" : "更新至" + total + "集")
                    : "");
            h.itemView.setOnClickListener(v -> openDrama(item));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    // ---------- 短片瀑布 ----------

    private class ClipAdapter extends RecyclerView.Adapter<ClipAdapter.H> {

        class H extends RecyclerView.ViewHolder {
            final ImageView cover;
            final TextView heat;
            final TextView badge;
            final TextView title;
            final ImageView avatar;
            final TextView author;

            H(LinearLayout cell) {
                super(cell);
                cover = cell.findViewWithTag("cover");
                heat = cell.findViewWithTag("heat");
                badge = cell.findViewWithTag("badge");
                title = cell.findViewWithTag("title");
                avatar = cell.findViewWithTag("avatar");
                author = cell.findViewWithTag("author");
            }
        }

        @NonNull
        @Override
        public H onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout cell = new LinearLayout(HongguoTheaterActivity.this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setPadding(dp(5), dp(6), dp(5), dp(12));
            FrameLayout frame = new FrameLayout(HongguoTheaterActivity.this);
            ImageView cover = new ImageView(HongguoTheaterActivity.this);
            cover.setTag("cover");
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackground(roundBg(0xFF232328, dp(8)));
            frame.addView(cover, new FrameLayout.LayoutParams(-1, -1));
            TextView badge = new TextView(HongguoTheaterActivity.this);
            badge.setTag("badge");
            badge.setTextColor(Color.WHITE);
            badge.setTextSize(10.5f);
            badge.setPadding(dp(6), dp(2), dp(6), dp(2));
            badge.setBackground(roundBg(0xCC1B1B1E, dp(4)));
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2);
            blp.leftMargin = dp(6);
            blp.topMargin = dp(6);
            frame.addView(badge, blp);
            TextView heat = new TextView(HongguoTheaterActivity.this);
            heat.setTag("heat");
            heat.setTextColor(0xFFF2F2F4);
            heat.setTextSize(11);
            heat.setPadding(dp(6), dp(2), dp(6), dp(2));
            heat.setBackground(roundBg(0x99000000, dp(4)));
            FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2);
            hlp.gravity = Gravity.BOTTOM | Gravity.LEFT;
            hlp.leftMargin = dp(6);
            hlp.bottomMargin = dp(6);
            frame.addView(heat, hlp);
            cell.addView(frame, new LinearLayout.LayoutParams(-1, -2));
            frame.setTag("frame");
            TextView title = new TextView(HongguoTheaterActivity.this);
            title.setTag("title");
            title.setTextColor(0xFFF2F2F4);
            title.setTextSize(13);
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
            tlp.topMargin = dp(6);
            cell.addView(title, tlp);
            LinearLayout authorRow = new LinearLayout(HongguoTheaterActivity.this);
            authorRow.setOrientation(LinearLayout.HORIZONTAL);
            authorRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
            alp.topMargin = dp(5);
            ImageView avatar = new ImageView(HongguoTheaterActivity.this);
            avatar.setTag("avatar");
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            authorRow.addView(avatar, new LinearLayout.LayoutParams(dp(16), dp(16)));
            TextView author = new TextView(HongguoTheaterActivity.this);
            author.setTag("author");
            author.setTextColor(0xFF8E8E96);
            author.setTextSize(11.5f);
            author.setMaxLines(1);
            author.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, -2, 1f);
            nlp.leftMargin = dp(5);
            authorRow.addView(author, nlp);
            cell.addView(authorRow, alp);
            return new H(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull H h, int position) {
            DramaItem item = clips.get(position);
            // 混比封面：按条目 id 哈希在三档比例里定一档，同条不跳变。
            int w = (getResources().getDisplayMetrics().widthPixels - dp(42)) / 2;
            float[] ratios = {1.30f, 1.02f, 1.18f};
            String key = item.getVid() != null ? item.getVid()
                    : String.valueOf(item.getSeriesId());
            float ratio = ratios[Math.abs(key.hashCode()) % ratios.length];
            View frame = (View) h.cover.getParent();
            ViewGroup.LayoutParams flp = frame.getLayoutParams();
            flp.height = (int) (w * ratio);
            frame.setLayoutParams(flp);
            if (item.getCoverUrl() != null && !item.getCoverUrl().isEmpty()) {
                Glide.with(HongguoTheaterActivity.this).load(item.getCoverUrl()).into(h.cover);
            } else {
                h.cover.setImageDrawable(null);
            }
            boolean album = item.kind == DramaItemKind.ALBUM;
            h.badge.setVisibility(album ? View.VISIBLE : View.GONE);
            if (album) {
                h.badge.setText(item.getBadge() != null && !item.getBadge().isEmpty()
                        ? item.getBadge() : "合集");
            }
            String heat = item.getRecText();
            h.heat.setVisibility(heat != null && !heat.isEmpty() ? View.VISIBLE : View.GONE);
            if (heat != null) h.heat.setText(heat);
            h.title.setText(item.getTitle() == null ? "" : item.getTitle());
            String author = item.getAuthorName();
            h.author.setText(author == null ? "" : author);
            if (item.getAuthorAvatar() != null && !item.getAuthorAvatar().isEmpty()) {
                Glide.with(HongguoTheaterActivity.this).load(item.getAuthorAvatar())
                        .circleCrop().into(h.avatar);
            } else {
                h.avatar.setImageDrawable(null);
            }
            h.itemView.setOnClickListener(v -> openClip(item));
        }

        @Override
        public int getItemCount() {
            return clips.size();
        }
    }
}
