package com.fongmi.android.tv.hongguo.ui;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Parcelable;
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
import com.fongmi.android.tv.hongguo.drama.DramaCellPage;
import com.fongmi.android.tv.hongguo.drama.DramaClient;
import com.fongmi.android.tv.hongguo.drama.DramaFilterRow;
import com.fongmi.android.tv.hongguo.drama.DramaItem;
import com.fongmi.android.tv.hongguo.drama.DramaItemKind;
import com.fongmi.android.tv.hongguo.model.HgContinuation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 剧场页（2026-10-30 从零重建，他亲定底栏第二签）：简单分类浏览。
 * 大分类四格（他 2026-10-30 改口径，旧「AI 归漫剧侧」作废）：真人剧/
 * 漫剧/AI剧/视频，各配两行小分类筹（主题＋设定、跨行单选、无数据
 * 整行不显）＋网格/瀑布。
 * 数据分法（分类探针实拉配对定版，账 hongguo-theater-ai-20261030）：
 * 服务端类型类 content_category_type——「1」真人剧走 39 频道、
 * 「1004」漫剧走 36 频道（漫剧格剔除 AI 类收纯）、「1004_ai_video」
 * AI剧在 36 频道里实拉为零、只在发现流（-1,16）成量出现，AI 格即
 * 发现流按该字段过滤成格；ai_usage_type 是作者声明标记、两类都带，
 * 不是分家字段。小分类筹走官方筛选面（distribution landpage，他
 * 2026-10-30 发官方实图亲证、探针账 hongguo-chip-fix-20261030）：
 * 面板按格 scene 下发维度表，筹落两行——全部主题
 * （category_dim_theme）＋全部设定（category_dim_role），跨行单选
 * （他亲令），点筹走同口内容路按 scene 分格取数；面板拉不到筹行
 * 整行不显、频道流照常。频道/发现流续页：开页 tab 级 has_more 恒
 * false，靠续页凭证在位判有下页＋continuation 逐页推进（旧版两处
 * 皆断、网格卡死开页，同账 §三）。
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
    private LinearLayout chipArea;
    private HorizontalScrollView themeScroll;
    private HorizontalScrollView roleScroll;
    private LinearLayout themeRow;
    private LinearLayout roleRow;
    private final List<TextView> themeChipViews = new ArrayList<>();
    private final List<TextView> roleChipViews = new ArrayList<>();
    /** 筛选面面板按格缓存（key＝格下标）；pending 防重入、dead 拉败后本页内不重试。 */
    private final Map<Integer, List<DramaFilterRow>> panelCache = new HashMap<>();
    private final Set<Integer> panelPending = new HashSet<>();
    private final Set<Integer> panelDead = new HashSet<>();
    /** 跨行单选的当前筛选（维度键＋值），皆 null＝未筛。 */
    private String activeDimKey;
    private String activeDimValue;
    private final List<DramaItem> items = new ArrayList<>();
    private final List<DramaItem> clips = new ArrayList<>();
    private int channel = 0;
    private HgContinuation continuation;
    private boolean hasMore;
    private boolean loading;
    private boolean reqFilter;
    private int filterOffset;
    private String filterSession;
    private final List<String> filterSeen = new ArrayList<>();
    private int clipChain;
    private int aiChain;
    /** 切格/点筹后首批数据到达要整表淡入承接（到达前为 true）。 */
    private boolean pendingContentFade;
    /** 分格状态保留（常驻口径，他 2026-10-30 定）：切格离开时整份
     * 存下，再切回来原样恢复、不重拉；没存过/上次没拉成的格才重拉。 */
    private final Map<Integer, TabState> tabStates = new HashMap<>();
    /** 当前格是否已成功落过一页（存档凭据；load(true) 起复位）。 */
    private boolean pageSettled;
    /** 请求代次：切格后旧格的在途回包一律作废，防串进当前格。 */
    private int loadGen;

    private static final class TabState {
        final List<DramaItem> items = new ArrayList<>();
        final List<DramaItem> clips = new ArrayList<>();
        HgContinuation continuation;
        boolean hasMore;
        boolean reqFilter;
        int filterOffset;
        String filterSession;
        final List<String> filterSeen = new ArrayList<>();
        int clipChain;
        int aiChain;
        String dimKey;
        String dimValue;
        boolean settled;
        Parcelable scrollState;
    }

    /** 入口（短剧首页底栏）：与首页同口径常驻——已有实例提到前台
     * 复用，格/筹/滚动位置全留住；没有实例才新建。 */
    public static void start(android.content.Context context) {
        Intent intent = new Intent(context, HongguoTheaterActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        HgTransition.fade(context, intent); // 签位口径：淡入复用，不硬切
    }

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
        search.setOnClickListener(v -> HgTransition.push(this,
                new Intent(this, HongguoSearchActivity.class)));
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
                if (channel != idx) switchTab(idx);
            });
            tabViews[i] = t;
            tabs.addView(t);
        }
        root.addView(tabs);

        chipArea = new LinearLayout(this);
        chipArea.setOrientation(LinearLayout.VERTICAL);
        chipArea.setVisibility(View.GONE);
        themeRow = new LinearLayout(this);
        themeRow.setOrientation(LinearLayout.HORIZONTAL);
        themeRow.setPadding(dp(12), dp(2), dp(12), dp(3));
        themeScroll = new HorizontalScrollView(this);
        themeScroll.setHorizontalScrollBarEnabled(false);
        themeScroll.addView(themeRow);
        chipArea.addView(themeScroll);
        roleRow = new LinearLayout(this);
        roleRow.setOrientation(LinearLayout.HORIZONTAL);
        roleRow.setPadding(dp(12), dp(3), dp(12), dp(6));
        roleScroll = new HorizontalScrollView(this);
        roleScroll.setHorizontalScrollBarEnabled(false);
        roleScroll.addView(roleRow);
        chipArea.addView(roleScroll);
        root.addView(chipArea);

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
        ensurePanel(channel);
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
            chipArea.setVisibility(View.GONE);
        } else {
            grid.setLayoutManager(new GridLayoutManager(this, 3));
            grid.setAdapter(gridAdapter);
            rebuildChips();
        }
    }

    private View buildBottomBar() {
        // 定稿：剧场底栏白色（与首页黑块底栏分开），选中深色、未选灰
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Color.WHITE);
        bar.setPadding(0, 0, 0, navBarHeight());
        LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, dp(50), 1f);
        bar.addView(bottomCell(R.drawable.ic_drama_home, "首页", false, v -> goHomeFeed()), cell);
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

    /** 回短剧首页：不销毁本页，把首页提到前台，本页留在任务栈里
     * 常驻（与首页同口径），再进剧场原样回来。 */
    private void goHomeFeed() {
        HongguoFeedActivity.start(this);
    }

    @Override
    public void finish() {
        super.finish();
        HgTransition.applyFadeExit(this);
    }

    @Override
    public void onBackPressed() {
        goHomeFeed();
    }

    /** 切格：离开的格整份存下，目标格有存货就原样恢复（数据/筹/
     * 滚动都不动、不重拉），没存货或上次没拉成才整格新拉。 */
    private void switchTab(int idx) {
        saveTabState(channel);
        loadGen++;
        loading = false;
        channel = idx;
        selectTab(); // 签高亮立即跟手，内容区走淡交、不硬切
        TabState st = tabStates.get(idx);
        if (st != null && st.settled) {
            // 有存货整份恢复也走淡交（HgTransition 共享口径）：整表
            // 绑定/换布局/滚动恢复全落在淡出后的不可见帧，不许原地砸出
            HgTransition.fadeSwap(() -> restoreTabState(st), grid, chipArea);
        } else {
            activeDimKey = null;
            activeDimValue = null;
            pendingContentFade = true;
            HgTransition.fadeSwap(() -> {
                rebuildChips();
                applyLayout();
                load(true);
            }, grid, chipArea);
        }
        ensurePanel(idx);
    }

    private void saveTabState(int ch) {
        TabState s = new TabState();
        s.items.addAll(items);
        s.clips.addAll(clips);
        s.continuation = continuation;
        s.hasMore = hasMore;
        s.reqFilter = reqFilter;
        s.filterOffset = filterOffset;
        s.filterSession = filterSession;
        s.filterSeen.addAll(filterSeen);
        s.clipChain = clipChain;
        s.aiChain = aiChain;
        s.dimKey = activeDimKey;
        s.dimValue = activeDimValue;
        s.settled = pageSettled;
        RecyclerView.LayoutManager lm = grid.getLayoutManager();
        s.scrollState = lm == null ? null : lm.onSaveInstanceState();
        tabStates.put(ch, s);
    }

    private void restoreTabState(TabState s) {
        items.clear();
        items.addAll(s.items);
        clips.clear();
        clips.addAll(s.clips);
        continuation = s.continuation;
        hasMore = s.hasMore;
        reqFilter = s.reqFilter;
        filterOffset = s.filterOffset;
        filterSession = s.filterSession;
        filterSeen.clear();
        filterSeen.addAll(s.filterSeen);
        clipChain = s.clipChain;
        aiChain = s.aiChain;
        activeDimKey = s.dimKey;
        activeDimValue = s.dimValue;
        pageSettled = s.settled;
        loading = false;
        selectTab();
        applyLayout();
        gridAdapter.notifyDataSetChanged();
        clipAdapter.notifyDataSetChanged();
        boolean empty = channel == TAB_CLIPS ? clips.isEmpty() : items.isEmpty();
        stateText.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) stateText.setText("暂时没有内容，点按重试");
        if (s.scrollState != null && grid.getLayoutManager() != null) {
            grid.getLayoutManager().onRestoreInstanceState(s.scrollState);
        }
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

    // ---------- 小分类筹（官方筛选面两行：主题＋设定） ----------

    private static final String DIM_THEME = "category_dim_theme";
    private static final String DIM_ROLE = "category_dim_role";

    /** 格下标→体裁（筛选面 genre 取值兼 scene 映射源）；视频格无筛选面。 */
    private static String genreOf(int ch) {
        if (ch == 0) return "short_play";
        if (ch == TAB_MANJU) return "comic_series";
        if (ch == TAB_AI) return "ai_series";
        return null;
    }

    private DramaFilterRow panelRow(int ch, String dimKey) {
        List<DramaFilterRow> rows = panelCache.get(ch);
        if (rows == null) return null;
        for (DramaFilterRow r : rows) if (dimKey.equals(r.key)) return r;
        return null;
    }

    /** 面板按格拉一份（内存缓存）；拉败不显筹、频道流照常，本页内不重试。 */
    private void ensurePanel(final int ch) {
        String genre = genreOf(ch);
        if (genre == null || panelCache.containsKey(ch)
                || panelPending.contains(ch) || panelDead.contains(ch)) return;
        panelPending.add(ch);
        DramaClient.fetchFilterPanel(genre, new HgCallback<List<DramaFilterRow>>() {
            @Override
            public void onSuccess(List<DramaFilterRow> rows) {
                runOnUiThread(() -> {
                    panelPending.remove(ch);
                    panelCache.put(ch, rows);
                    if (channel == ch) {
                        boolean chipWasShown = chipArea.getVisibility() == View.VISIBLE;
                        rebuildChips();
                        // 筹行后到：整行淡入，不许凭空砸出（切格淡交
                        // 进行中时 alpha 归淡交统一管，这里不插手）
                        if (!chipWasShown && chipArea.getVisibility() == View.VISIBLE
                                && grid.getAlpha() > 0.99f) {
                            HgTransition.fadeIn(chipArea, 150);
                        }
                    }
                });
            }

            @Override
            public void onError(int code, String msg) {
                runOnUiThread(() -> {
                    panelPending.remove(ch);
                    panelDead.add(ch);
                });
            }
        });
    }

    private void rebuildChips() {
        themeRow.removeAllViews();
        roleRow.removeAllViews();
        themeChipViews.clear();
        roleChipViews.clear();
        if (channel == TAB_CLIPS) {
            chipArea.setVisibility(View.GONE);
            return;
        }
        boolean themeShown = fillChipRow(themeRow, themeChipViews, panelRow(channel, DIM_THEME));
        boolean roleShown = fillChipRow(roleRow, roleChipViews, panelRow(channel, DIM_ROLE));
        themeScroll.setVisibility(themeShown ? View.VISIBLE : View.GONE);
        roleScroll.setVisibility(roleShown ? View.VISIBLE : View.GONE);
        chipArea.setVisibility(themeShown || roleShown ? View.VISIBLE : View.GONE);
        styleChips();
    }

    /** 一行筹：行首「全部」（清本行筛选）＋面板选项；行无选项整行不显。 */
    private boolean fillChipRow(LinearLayout rowView, List<TextView> views, DramaFilterRow row) {
        if (row == null || row.options.isEmpty()) return false;
        final String dimKey = row.key;
        views.add(makeChip(dimKey, null, "全部"));
        rowView.addView(views.get(0));
        for (DramaFilterRow.Option op : row.options) {
            TextView t = makeChip(dimKey, op.value, op.name);
            views.add(t);
            rowView.addView(t);
        }
        return true;
    }

    private TextView makeChip(final String dimKey, final String value, String name) {
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(12.5f);
        t.setPadding(dp(11), dp(5), dp(11), dp(5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        t.setTag(value);
        t.setOnClickListener(v -> {
            // 跨行单选（他亲令）：选任一维度的值即按它筛、另一行清选；
            // 点任一行「全部」清筛选回本格默认流；同值再点无动作。
            if (value == null) {
                if (activeDimKey == null) return;
                activeDimKey = null;
                activeDimValue = null;
            } else {
                if (dimKey.equals(activeDimKey) && value.equals(activeDimValue)) return;
                activeDimKey = dimKey;
                activeDimValue = value;
            }
            styleChips();
            pendingContentFade = true;
            HgTransition.fadeSwap(() -> load(true), grid);
        });
        return t;
    }

    private void styleChips() {
        styleChipRow(themeChipViews, DIM_THEME);
        styleChipRow(roleChipViews, DIM_ROLE);
    }

    private void styleChipRow(List<TextView> views, String dimKey) {
        for (int i = 0; i < views.size(); i++) {
            TextView t = views.get(i);
            boolean on = i == 0 ? activeDimKey == null
                    : dimKey.equals(activeDimKey) && String.valueOf(activeDimValue).equals(t.getTag());
            t.setTextColor(on ? Color.WHITE : 0xFFB9B9C0);
            t.setBackground(roundBg(on ? 0xFFE8402F : 0xFF232328, dp(13)));
        }
    }

    // ---------- 取数 ----------

    private void load(boolean first) {
        if (loading) return;
        loading = true;
        final int gen = ++loadGen;
        reqFilter = activeDimValue != null && channel != TAB_CLIPS;
        if (first) {
            pageSettled = false;
            items.clear();
            clips.clear();
            gridAdapter.notifyDataSetChanged();
            clipAdapter.notifyDataSetChanged();
            continuation = null;
            filterOffset = 0;
            filterSession = null;
            filterSeen.clear();
            clipChain = 0;
            aiChain = 0;
            hasMore = false;
            stateText.setText("加载中…");
            stateText.setVisibility(View.VISIBLE);
        }
        HgCallback<DramaCellPage> cb = new HgCallback<DramaCellPage>() {
            @Override
            public void onSuccess(DramaCellPage page) {
                runOnUiThread(() -> {
                    if (gen == loadGen) onPage(page, first);
                });
            }

            @Override
            public void onError(int code, String msg) {
                runOnUiThread(() -> {
                    if (gen != loadGen) return;
                    loading = false;
                    pendingContentFade = false;
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
        } else if (reqFilter) {
            // 筛选路：官方筛选面内容口，游标（offset/session/已展示表）
            // 由本页持有递进；服务端按 scene 分格，回包不再客户端分格。
            DramaClient.fetchFilterList(genreOf(channel), activeDimKey, activeDimValue,
                    first ? 0 : filterOffset, first ? null : filterSession,
                    new ArrayList<>(filterSeen), cb);
        } else if (channel == TAB_AI) {
            // AI剧格数据源＝发现流（与视频格同口），回包在 onPage 按
            // 服务端类型类 1004_ai_video 过滤成格。
            if (first || continuation == null) DramaClient.fetchChannel(-1, 16, cb);
            else DramaClient.fetchChannelMore(continuation, cb);
        } else if (first || continuation == null) {
            int[] ch = CHANNELS[channel];
            DramaClient.fetchChannel(ch[0], ch[1], cb);
        } else {
            DramaClient.fetchChannelMore(continuation, cb);
        }
    }

    /**
     * 频道/发现流续页推进（旧版两断已修，账 hongguo-chip-fix-20261030
     * §三）：开页 tab 级 has_more 恒 false，靠续页凭证在位判有下页；
     * 续页回包自带真实 has_more 与 next_offset，凭证逐页推进
     * （HgContinuation.advanced——旧版从不推进、续拉恒拉同一页）。
     */
    private void advanceContinuation(DramaCellPage page, boolean first) {
        if (page.getContinuation() != null) {
            continuation = page.getContinuation();
        } else if (!first && continuation != null && page.getNextOffset() != null) {
            continuation = continuation.advanced(page.getNextOffset(), page.getSessionId());
        }
        hasMore = page.isHasMore() || (first && continuation != null);
    }

    /**
     * 切格/点筹后首批真出货的数据到达：整表淡入承接（HgTransition
     * 口径），不许数据直接砸进可见表；整页滤空自动续拉时不消费，
     * 把淡入留给真正出货的那一页。
     */
    private void arriveFadeIn(boolean addedAny) {
        if (!pendingContentFade || !addedAny) return;
        pendingContentFade = false;
        grid.setAlpha(0f);
        HgTransition.contentIn(grid);
    }

    private void onPage(DramaCellPage page, boolean first) {
        loading = false;
        pageSettled = true;
        if (channel == TAB_CLIPS) {
            advanceContinuation(page, first);
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
            arriveFadeIn(added > 0);
            stateText.setVisibility(clips.isEmpty() ? View.VISIBLE : View.GONE);
            if (clips.isEmpty()) stateText.setText("暂时没有内容，点按重试");
            // 整页被过滤光但还有下页：自动续拉（最多连 3 页），免得短片格假空。
            if (added == 0 && hasMore && clipChain < 3) {
                clipChain++;
                load(false);
            }
            return;
        }
        if (reqFilter) {
            // 筛选游标递进：offset/session 取回包；已展示 id 全数登记
            // （含被下方去重跳过的——服务端按 filter_ids 排除，登记
            // 越全翻页越不重）。筛选回包按 scene 天然分格，不再走
            // 下面的客户端分格过滤。
            String no = page.getNextOffset();
            if (no != null) {
                try {
                    filterOffset = Integer.parseInt(no);
                } catch (NumberFormatException e) {
                    filterOffset += page.getItems().size();
                }
            } else {
                filterOffset += page.getItems().size();
            }
            if (page.getSessionId() != null) filterSession = page.getSessionId();
            hasMore = page.isHasMore();
            for (DramaItem it : page.getItems()) {
                if (it.getSeriesId() != null) filterSeen.add(it.getSeriesId());
            }
        } else {
            advanceContinuation(page, first);
        }
        // 分格过滤（服务端类型类为准，不靠标题猜）：AI 格只收
        // 1004_ai_video（频道面另加 SERIES 型门），漫剧格剔除 AI 类收纯。
        List<DramaItem> src = page.getItems();
        if (!reqFilter && channel == TAB_AI) {
            List<DramaItem> f = new ArrayList<>();
            for (DramaItem it : src) {
                if (it.kind == DramaItemKind.SERIES && it.isAiDrama()) f.add(it);
            }
            src = f;
        } else if (!reqFilter && channel == TAB_MANJU) {
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
        arriveFadeIn(added > 0);
        stateText.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        if (items.isEmpty()) stateText.setText("暂时没有内容，点按重试");
        if (channel == TAB_AI && !reqFilter) {
            if (added > 0) aiChain = 0;
            // 整页被过滤光但还有下页：自动续拉（最多连 4 页），免得
            // AI 格假空（与短片格 clipChain 同法）。
            if (added == 0 && hasMore && aiChain < 4) {
                aiChain++;
                load(false);
            }
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
